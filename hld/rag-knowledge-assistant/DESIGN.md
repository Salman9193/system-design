# RAG Knowledge Assistant — High-Level Design

Like the search-typeahead design, this splits into two paths at different timescales:
an **ingestion/build path** (offline — turn documents into a searchable vector index)
and a **query/serving path** (online — retrieve then generate). The retrieve-then-
generate loop on the serving path is the heart of the system.

---

## The Two-Path Architecture

```
     INGESTION / BUILD PATH  (offline, keeps the index fresh)
  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌───────────┐   ┌──────────────┐
  │ Documents│──▶│ Ingest & │──▶│ Chunk    │──▶│ Embed     │──▶│ Vector index │
  │ (sources)│   │ extract  │   │ (+ACL,   │   │ (embedding│   │ (sharded ANN │
  │          │   │ text     │   │  metadata)│   │  model)   │   │  + metadata) │
  └──────────┘   └──────────┘   └──────────┘   └───────────┘   └──────────────┘
                                                                       ▲
        QUERY / SERVING PATH  (online, retrieve-then-generate)         │
  ┌────────┐  ┌──────────┐  ┌───────────────┐  ┌──────────────┐        │
  │ User   │─▶│ Gateway  │─▶│ Retriever      │─▶│ (hybrid +    │────────┘
  │ (multi-│  │ (auth,   │  │ - embed query  │  │  rerank,     │
  │  turn) │◀─│  cache)  │  │ - ANN + filter │  │  ACL filter) │
  └────────┘  └────┬─────┘  └───────┬────────┘  └──────────────┘
                   │                │ top-k passages
              cache hit             ▼
                   │        ┌────────────────────┐   ┌──────────────┐
                   └───────▶│ Prompt builder     │──▶│ LLM (grounded│──▶ streamed
                            │ (context+history+  │   │  generation, │    cited answer
                            │  guardrail prompt) │   │  citations)  │
                            └────────────────────┘   └──────────────┘
```

---

## Ingestion / Build Path (offline)

Turns raw documents into a fresh, searchable, permission-tagged vector index.

1. **Ingest & extract** — pull documents from sources (wikis, ticket systems, file
   stores), extract clean text from each format (PDF, HTML, etc.). Handle
   create/update/delete so the index stays consistent with the sources.
2. **Chunk** — split each document into passages (see
   `embeddings-and-vector-search.md`; chunking quality strongly affects answer
   quality). Attach **metadata**: source, timestamp, and crucially the **ACL** (who
   may see this).
3. **Embed** — run each chunk through the embedding model (batch inference) to get a
   vector.
4. **Index** — upsert vectors + metadata into the sharded ANN index.

**Freshness:** rather than only periodic full rebuilds, run this as a **streaming
pipeline** — a document change event flows through extract→chunk→embed→upsert within
minutes. Deletes must propagate (a deleted doc must stop being retrievable
immediately — a stale permitted-but-deleted doc is both a quality and a security
problem).

---

## Query / Serving Path (online)

The retrieve-then-generate loop.

1. **Gateway** — authenticates the user (establishing their identity/permissions),
   applies **token-based rate limiting**, and checks the **response cache** (exact +
   semantic) before doing expensive work.
2. **Retriever:**
   - Embed the (context-aware) query with the same embedding model used at build
     time.
   - **ANN search** the vector index for candidate passages, **filtered by the
     user's ACL** so only permitted docs are eligible (filtered-ANN).
   - Optionally **hybrid** (vector + lexical) and **rerank** the candidates for
     precision, keeping the top few.
3. **Prompt builder** — assemble the LLM prompt: a guardrail system prompt ("answer
   only from the provided context; cite sources; say you don't know if unsupported"),
   the retrieved passages, the conversation history (trimmed to a token budget), and
   the question.
4. **LLM generation** — generate a **grounded, cited** answer, **streamed** token by
   token so the user sees a fast first token. Citations map back to the retrieved
   passages.
5. **Capture signal** — log the query, retrieved context, answer, and user feedback
   (thumbs up/down) for the eval set and the data flywheel.

---

## Data Model

**Chunk (in the index):**
```
Chunk {
  id, document_id
  text
  embedding: vector[768]
  acl: [principal_ids]      // who may retrieve this
  source, timestamp, metadata
}
```

**Conversation:**
```
Conversation { id, user_id, turns: [ {question, answer, cited_chunk_ids} ] }
```

The critical modeling decisions: **the ACL lives on the chunk** so retrieval can
filter by permission at search time; and **citations are first-class** (the answer
records which chunks it used) so answers are checkable and the flywheel can learn
from them.

---

## API

```
POST /ask
{ "conversation_id": "...", "question": "How do I rotate my API key?" }

→ streamed response:
   tokens... "You can rotate your API key in Settings → Security [1]..."
   { "citations": [ {"n":1, "document_id":"doc_42", "title":"API Keys", "url":"..."} ] }
```

- Streamed (server-sent events) so first token is fast.
- Citations returned alongside the text, referencing retrieved sources.
- Stateless per request except for the referenced conversation (history fetched by
  id).

---

## Why This Shape

Every decision traces to the binding constraints (quality, then cost/latency, with
permissions as a hard constraint):

- **Retrieve-then-generate** → grounds the answer in real documents, which is the
  primary hallucination defense.
- **ACL on the chunk + filtered retrieval** → permissions enforced at the only place
  that can leak, the retrieval step.
- **Two paths** → expensive embedding/indexing stays offline; the hot path is
  retrieve + generate.
- **Cache at the gateway** → the most expensive work (LLM generation) is skipped for
  repeated/similar questions.
- **Streaming** → hides slow generation behind a fast first token.
- **Signal capture** → feeds eval and the data flywheel so quality compounds.

The deep dives (`DEEP_DIVES.md`) go inside retrieval quality, hallucination control,
permissions, and cost — where the interview time is spent.
