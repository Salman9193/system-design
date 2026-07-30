# RAG Knowledge Assistant — Requirements

**Prompt:** Design a system that answers user questions over a large corpus of
private/enterprise documents — an AI assistant that gives grounded, cited answers
from a knowledge base (support docs, internal wikis, product manuals, etc.).

This is the canonical AI system design. It exercises the entire AI stack — ingestion,
chunking, embeddings, vector search, retrieval, LLM generation, caching, and
evaluation — and every AI-specific rubric dimension (hallucination, cost-per-token,
eval, the offline/online split). It also embodies the traditional→AI progression:
it's "search, but the system reads the results and writes the answer."

---

## Phase 1 — Clarify Scope

### Clarifying questions worth asking
- **What's the corpus?** Size, format (PDFs, HTML, tickets), update frequency? →
  *Assume millions of documents, updated continuously.*
- **Who are the users and what's the access model?** Can everyone see everything, or
  is retrieval **permission-scoped** per user? → *Assume per-user access control —
  this is a real and hard requirement.*
- **Grounded-only, or can it use general knowledge?** → *Grounded: answer only from
  retrieved docs, with citations. Refuse when unsupported.*
- **Conversational (multi-turn) or single-shot Q&A?** → *Multi-turn, so we carry
  history.*
- **Latency expectation?** → *Interactive — first token in ~1–2s, streamed.*

---

## Functional Requirements

1. **Answer a natural-language question** using relevant documents from the corpus.
2. **Ground and cite** — every answer references the source passages it used.
3. **Respect permissions** — a user only ever retrieves documents they're allowed to
   see.
4. **Ingest and keep the corpus fresh** — new/updated/deleted docs are reflected.
5. **Multi-turn** — follow-up questions use conversation context.

### Explicitly out of scope (state it)
- Training/fine-tuning a base model — we use an existing LLM and embedding model.
- Actions/agents (the assistant answers; it doesn't take actions) — flag as an
  extension.
- Cross-document reasoning beyond what retrieval surfaces.

---

## Non-Functional Requirements

| Requirement | Target | Why it matters |
|-------------|--------|----------------|
| **Answer quality** | High groundedness, low hallucination | The defining quality bar — measured by eval, not tests |
| **Latency** | First token ~1–2s, streamed | Interactive UX; full answer is slow (generation) |
| **Freshness** | New docs retrievable within minutes | Stale knowledge = wrong answers |
| **Cost** | Bounded cost per query | LLM generation dominates; tokens are the cost unit |
| **Permissions** | Strict — never leak an unauthorized doc | A retrieval leak is a security incident |
| **Scale** | Millions of docs, thousands of QPS | Drives sharding of the vector index |

**The binding constraints to state:** this system is judged first on **answer
quality** (groundedness — does it hallucinate?), and second on **cost and latency**,
which are dominated by LLM inference. Permissions are a hard security constraint that
constrains retrieval. Everything bends toward: retrieve the *right, permitted*
context, and generate a *grounded, cited* answer at bounded token cost.

---

## Capacity & Cost Estimation

See `fundamentals/capacity-estimation.md` and `ai-engineering-primer.md`.

**Corpus / index:**
- ~10M documents, chunked ~10× → ~100M chunks. At a 768-dim embedding, ~3 KB/vector
  → ~300 GB of vectors, plus index overhead → **sharded vector index** (see
  `embeddings-and-vector-search.md`).

**Query cost (the AI-specific part):**
- Per query: embed the question (cheap), ANN search (cheap), then **LLM generation**
  over (system prompt + retrieved chunks + history + question). If we retrieve ~5
  chunks × ~500 tokens = ~2,500 input tokens + ~500 output tokens.
- LLM inference dominates cost and latency. At thousands of QPS this is the line item
  that matters → **caching and context-size control are first-class**.

**The conclusions that flow from the numbers:**
1. 100M chunks → shard the vector index; build embeddings offline.
2. LLM generation dominates cost/latency → cache aggressively (exact + semantic),
   retrieve *few* high-quality chunks (not many mediocre ones), use a smaller model
   for easy queries.
3. Freshness in minutes → a streaming ingestion path, not just periodic rebuilds.
4. Per-user permissions → retrieval must filter by ACL (filtered-ANN), which
   constrains the index design.

---

## The One-Sentence Framing

> "This is a grounded question-answering system over a permissioned corpus. The
> core loop is retrieve-then-generate: embed the question, fetch the most relevant
> *permitted* passages from a sharded vector index, and have an LLM write a cited
> answer using only those passages. The hard parts are retrieval quality (garbage
> context → garbage answer), hallucination control (grounding + citation +
> abstention), permissions (never retrieve what the user can't see), and cost
> (LLM tokens dominate, so cache and retrieve tightly). Quality is measured by an
> eval harness, not unit tests."

That framing names the loop (retrieve-then-generate), the four hard parts
(retrieval, hallucination, permissions, cost), and the AI-native way to verify it
(eval) — before drawing a box.

---

## Engineering Blogs & Primary Sources

RAG has an unusually clean lineage: one founding paper, then a wave of engineering writeups solving
the practical failures (bad chunks, weak retrieval). These map onto this HLD's tabs.

- **Lewis et al. (2020) — "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks."**
  https://arxiv.org/abs/2005.11401
  The paper that named RAG. Its core thesis is this HLD's foundation: combine **parametric memory**
  (the model's weights) with **non-parametric memory** (an external retrieval index) so answers are
  grounded in retrieved passages, not just recalled — improving factual accuracy and **provenance**
  (you can cite the source). → backs the **Requirements** (why retrieve at all) framing.

- **Anthropic — "Introducing Contextual Retrieval" (2024).**
  https://www.anthropic.com/news/contextual-retrieval
  The most actionable improvement to the naive pipeline, and it validates two of this HLD's points at
  once: (1) **hybrid retrieval** — combining embeddings with **BM25 lexical search** beats either
  alone (this HLD's "optionally hybrid" retriever step); (2) naive chunking **loses context**, so
  prepend an LLM-generated context blurb to each chunk before embedding. Reports large retrieval-
  failure reductions from these two changes. → backs the **Design** (hybrid retrieve) and **Deep
  Dives** (chunking) tabs.

- **Pinecone — "Retrieval-Augmented Generation" and the vector-DB learn series.**
  https://www.pinecone.io/learn/retrieval-augmented-generation/
  The vector-database vendor's practical guide to the serving path this HLD draws: embedding, **ANN
  search**, top-k retrieval, and the metadata-filtering that this HLD makes load-bearing for
  **permission-filtered retrieval**. Also the two-stage retrieve-then-rerank pattern
  (https://www.pinecone.io/learn/two-stage-retrieval/) that the Design tab's rerank step uses. →
  backs the **Design** (ANN + rerank) and **Scaling** tabs.

- **Anthropic — Model Context Protocol (MCP).** https://modelcontextprotocol.io/
  The emerging standard for connecting models to external tools and data sources — the productionized,
  standardized form of "the retriever fetches from your systems." Relevant to how this HLD's
  ingestion connectors evolve. → context for **AI Evolution**.

**A note on honesty:** unlike rate limiting, RAG's best sources split into **one academic paper**
(Lewis, the origin) plus **vendor engineering guides** (Anthropic, Pinecone) — the field is young
enough that the canonical "we ran this at scale for years" retrospective doesn't exist yet. The
**through-line** they share is this HLD's spine: *retrieval quality dominates answer quality, so the
engineering is in chunking, hybrid retrieval, and reranking — the generation model is the easy part.*
