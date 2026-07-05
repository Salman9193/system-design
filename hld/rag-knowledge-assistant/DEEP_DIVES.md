# RAG Knowledge Assistant — Deep Dives

The deep dives are where the AI-specific depth shows. Each opens a box an interviewer
will push on. Drive these proactively — especially retrieval quality and
hallucination, which are the make-or-break of any RAG system.

---

## Deep Dive 1 — Retrieval Quality (garbage in, garbage out)

The single biggest determinant of answer quality is **whether the right passages
were retrieved**. The LLM can only answer from what it's given; if retrieval surfaces
irrelevant or incomplete context, the answer is wrong no matter how good the model
is. So most quality work is retrieval work.

### The levers (roughly in order of impact)
1. **Chunking** — the quiet make-or-break. Chunks too large → muddy embeddings and
   wasted tokens; too small → missing context. Use structure-aware chunks with
   overlap (see `embeddings-and-vector-search.md`). This often matters more than the
   index or the model.
2. **Hybrid retrieval** — vector search alone misses exact terms (product codes,
   error strings, names). Combine vector (semantic) with lexical/BM25 (exact), fuse
   the rankings.
3. **Reranking** — ANN gives approximate top-*k*; a cross-encoder reranker rescores
   the merged candidates for precision, so the passages actually placed in the prompt
   are the best few.
4. **Query transformation** — for multi-turn, rewrite the follow-up into a
   standalone query ("what about pricing?" → "what is the pricing for API key
   rotation?") before embedding, so retrieval isn't confused by pronouns.

### The staff move
> "I'd spend my quality budget on retrieval before the model. Concretely:
> structure-aware chunking with overlap, hybrid vector+lexical retrieval, and a
> cross-encoder reranker so only the best few passages reach the prompt. I'd measure
> retrieval directly — recall@k against a labeled set — separately from end-to-end
> answer quality, because if retrieval recall is low, no prompt tweak will save the
> answer."

Measuring retrieval **independently** from generation is a strong signal — it shows
you can localize where quality is lost.

---

## Deep Dive 2 — Hallucination Control

The model can produce fluent, confident, wrong answers. In a grounded assistant, the
defenses stack:

1. **Grounding** — the whole point of RAG: instruct the model to answer *only* from
   the provided passages, turning "recall from weights" into "read from context."
2. **Citation/attribution** — require the answer to cite which passage supports each
   claim. This makes answers checkable and lets you detect unsupported claims.
3. **Abstention** — instruct and allow the model to say "I don't have that
   information" when the context doesn't support an answer. A system that refuses when
   unsure beats one that confidently fabricates.
4. **Groundedness checking** — a verification step (a cheaper model or a checker)
   confirms the answer's claims are actually supported by the cited passages;
   unsupported claims are flagged or dropped.
5. **Confidence surfacing** — retrieval scores and citation coverage give a signal;
   low-confidence answers can be hedged or escalated.

### The trade-off
> "Grounding plus mandatory citations is my primary hallucination defense — the model
> answers from retrieved passages and cites them, so claims are checkable. For
> high-stakes answers I'd add a groundedness verifier that confirms each claim is
> supported before returning. The cost is extra latency and tokens for verification,
> so I'd apply it selectively based on the query's stakes."

---

## Deep Dive 3 — Permissions (the security-critical retrieval constraint)

A retrieval leak — surfacing a document the user shouldn't see — is a security
incident, and it's subtle because the leak happens *inside* the AI pipeline. The rule:
**a user must never retrieve a passage they're not authorized to see.**

### Approaches
- **ACL on the chunk + filtered-ANN (chosen):** store each chunk's access list as
  metadata and filter the vector search by the user's principals, so unauthorized
  chunks are never candidates. This is filtered-ANN, which is non-trivial:
  pre-filtering can shrink the candidate pool below *k*; post-filtering can discard
  most of what ANN returned. You size `efSearch`/`nprobe` and the pipeline to still
  return enough *permitted* results.
- **Per-tenant indexes:** physically separate indexes per tenant/security boundary —
  strong isolation, but expensive and rigid if permissions are fine-grained.

### The pitfalls to name
- **Deletes and permission changes must propagate fast.** If a doc is unshared or
  deleted, it must stop being retrievable immediately — stale ACLs are a leak.
- **Citations can leak metadata** — even a title/URL in a citation reveals a
  document exists. Filtering must happen before retrieval, not after generation.

> "Permissions have to be enforced at retrieval, because that's the only place an
> unauthorized document can enter the answer. I'd put ACLs on the chunk and do
> filtered-ANN so unpermitted chunks are never candidates — and I'd treat permission
> revocation and deletes as high-priority index updates, since a stale ACL is a data
> leak, not just stale content."

---

## Deep Dive 4 — Cost & Latency (tokens dominate)

LLM generation is the dominant cost and the largest latency contributor. The levers:

### Caching
- **Exact-match cache** — identical prompt → cached answer. Cheap, but exact repeats
  are rare in natural language.
- **Semantic cache** — embed the query and serve a cached answer if a
  sufficiently-similar prior query exists (see the model-gateway LLD). Much higher hit
  rate, but you must tune the similarity threshold carefully — too loose and you
  serve a subtly-wrong cached answer. **And the cache must be permission-aware**: never
  serve user A's cached answer to user B if it was grounded in docs B can't see.

### Context control
- Retrieve **few high-quality** passages (via reranking), not many mediocre ones —
  fewer input tokens, better answers.
- Trim conversation history to a token budget (summarize older turns).

### Model tiering
- Route easy queries to a smaller/cheaper model and hard ones to a larger model (a
  router — see the model-gateway LLD). Most queries don't need the biggest model.

### Latency
- **Stream** the answer so time-to-first-token is what the user feels, not full
  generation time.
- Retrieval adds latency before the first token; keep it tight (fast ANN, bounded
  rerank).

> "Tokens dominate cost and latency, so I attack both: a permission-aware semantic
> cache to skip generation entirely for similar questions, tight reranked retrieval
> to minimize input tokens, model tiering so easy queries use a cheap model, and
> streaming so the user feels first-token latency, not total generation time."

---

## Deep Dive 5 — Evaluation (how you know it's good)

There are no unit tests for answer quality. The eval harness is the AI-native
equivalent and a first-class part of the design.

- **A labeled eval set** — representative questions with reference answers or rubrics,
  plus known relevant documents (to measure retrieval recall separately).
- **Metrics** — retrieval recall@k; answer groundedness (are claims supported?);
  answer correctness (LLM-as-judge calibrated against human labels; human review on a
  sample); citation accuracy; refusal correctness (does it abstain when it should?).
- **A regression gate** — any change to the prompt, model, embedding model, chunker,
  or retrieval config must pass the eval suite before shipping. This is CI for the AI
  system.
- **Online eval** — thumbs up/down and A/B tests on live traffic confirm offline eval,
  and feed the flywheel.

> "I'd gate every change — prompt, model, chunker, retrieval config — on an eval
> suite that measures retrieval recall and answer groundedness separately, so I can
> tell whether a regression came from retrieval or generation. Offline eval plus an
> online A/B on a small slice is how I ship changes without silently degrading
> quality."

This is the single most important thing that separates "wired up an LLM" from
"engineered a RAG system," and raising it unprompted is a strong staff signal.
