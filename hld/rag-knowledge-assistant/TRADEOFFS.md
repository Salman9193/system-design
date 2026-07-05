# RAG Knowledge Assistant — Trade-offs

Every major decision here trades something, and naming those trades unprompted is the
staff signal. The trades cluster around the same tension: **answer quality vs cost/
latency**, with permissions as a hard constraint that can't be traded away.

---

## RAG vs Fine-Tuning

| | RAG (chosen) | Fine-tuning the base model |
|--|--------------|----------------------------|
| Knowledge freshness | Live — update the corpus | Stale — needs retraining |
| Attribution/citation | Natural — cite retrieved docs | Very hard — knowledge is baked in |
| Cost to update | Cheap — re-index | Expensive — retrain |
| Permissions | Enforceable at retrieval | Nearly impossible — weights don't have ACLs |
| Best for | Fresh, citable, permissioned facts | Style, format, behavior |

**Decision:** RAG. For a knowledge assistant over changing, permissioned, citable
documents, RAG is clearly right — you can't put per-user ACLs into fine-tuned weights,
and you need citations and freshness. Fine-tuning (or few-shot) is still useful for
*behavior* (tone, format), so the real answer is "RAG for knowledge, light tuning for
behavior."

---

## Retrieve Few vs Retrieve Many

| | Few high-quality passages (chosen) | Many passages |
|--|-----------------------------------|---------------|
| Answer quality | Better — focused, less distraction | Worse — model distracted by noise |
| Token cost | Lower | Higher (linear in context) |
| Recall risk | May miss a needed passage | Higher recall |

**Decision:** retrieve broadly, then **rerank and keep few**. More context is not
better — models get distracted by irrelevant passages, and every extra passage costs
tokens. The reranker lets you cast a wide net for recall then narrow to precision.

---

## Semantic Cache vs Exact Cache vs No Cache

| | Semantic cache (chosen, with care) | Exact cache | No cache |
|--|-----------------------------------|-------------|----------|
| Hit rate | High | Low (exact repeats rare) | — |
| Cost saved | Large (skips generation) | Some | None |
| Risk | Serving a subtly-wrong near-match; **cross-user leak** | Low | None |

**Decision:** semantic cache, but **permission-aware** and **threshold-tuned**. The
savings are large because it skips the expensive generation step, but two dangers must
be handled: a too-loose similarity threshold serves a wrong answer to a
slightly-different question, and a naive cache could serve user A's answer (grounded in
A's private docs) to user B. Cache keys must include the permission scope.

---

## Streaming vs Waiting

**Decision:** stream. Full generation is slow (per-token), so returning the whole
answer at once means a long wait. Streaming shows the first token in ~1–2s and the
rest as it generates. The trade-off: streaming complicates the response path (partial
outputs, mid-stream error handling, applying output guardrails on a stream) — worth it
for interactive UX.

---

## Groundedness Verification: Always vs Selective

**Decision:** selective. A verification pass (checking each claim is supported by
cited passages) meaningfully reduces hallucination but adds latency and token cost.
Applying it to every query is wasteful; applying it by stakes (high-stakes queries,
low retrieval confidence) targets the cost where it matters. The trade-off is
complexity in deciding *when* to verify.

---

## Freshness: Streaming Ingestion vs Periodic Rebuild

**Decision:** streaming ingestion for freshness, with periodic full rebuilds as a
backstop. Streaming keeps the index within minutes of the sources (important:
stale = wrong answers, and stale ACLs = leaks). Full rebuilds are still needed for an
embedding-model change (which requires re-embedding everything) and to repair drift.
The trade-off is a more complex pipeline than periodic-only.

---

## Model Tiering: One Model vs Router

**Decision:** route by difficulty. Most queries don't need the largest model; a
smaller model handles easy ones at a fraction of the cost and latency, with a larger
model for hard queries. The trade-off is the routing logic itself (misrouting a hard
query to the small model hurts quality) and maintaining two model paths — handled in
the model-gateway LLD.

---

## The Meta-Point

Every trade points the same way as the requirements: **protect answer quality and
control token cost, and never compromise permissions.** RAG over fine-tuning,
few-reranked over many, semantic cache with permission-awareness, selective
verification, streaming — each spends complexity to buy quality or savings while
keeping the security constraint absolute. That coherence is the staff signal: the
trades aren't ad hoc, they all serve the same small set of binding constraints.
