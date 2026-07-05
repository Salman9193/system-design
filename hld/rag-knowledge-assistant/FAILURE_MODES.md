# RAG Knowledge Assistant — Failure Modes & Observability

AI systems add failure modes traditional systems don't have — the scariest being
**silent wrong answers**. At the staff level, walk these proactively, and note that
AI observability includes quality signals, not just latency and errors.

---

## AI-Specific Failure Modes

### Hallucination (confident wrong answers)
- **Impact:** the worst failure — the system returns fluent, plausible, wrong
  information, and the user can't tell. Unlike a crash, it's silent.
- **Detection:** groundedness scoring (are claims supported by citations?), thumbs-
  down feedback, sampled human review, eval regressions.
- **Mitigation:** grounding + mandatory citations + abstention + selective
  verification (see Deep Dives). The goal is to make the model say "I don't know"
  rather than invent.

### Retrieval failure (right answer exists, wasn't retrieved)
- **Impact:** the corpus contains the answer but retrieval missed it → the model
  either says it doesn't know (good-ish) or fabricates (bad).
- **Detection:** low retrieval scores; recall@k eval; "I don't know" rate spiking.
- **Mitigation:** hybrid retrieval + reranking; query rewriting; chunking
  improvements; alerting on retrieval-confidence drops.

### Permission leak (retrieved an unauthorized doc)
- **Impact:** a security incident — the answer (or a citation) exposes a document the
  user shouldn't see.
- **Detection:** audit retrieval against ACLs; alert on any retrieval that bypasses a
  filter; test with permission-boundary eval cases.
- **Mitigation:** ACL-filtered retrieval enforced before generation; fast propagation
  of permission changes and deletes; permission scope baked into cache keys.

### Quality drift
- **Impact:** answers slowly degrade — because the corpus changed, the query
  distribution shifted, or a model/embedding upgrade subtly changed behavior.
- **Detection:** track eval metrics and thumbs-up rate over time; alert on
  regression.
- **Mitigation:** the regression gate on every change; periodic re-eval; the data
  flywheel to catch and correct drift.

### Stale index (freshness failure)
- **Impact:** answers cite deleted or outdated documents; a deleted-but-still-
  retrievable doc is both wrong and a possible leak.
- **Detection:** ingestion lag metric (time from source change to index update);
  reconciliation checks between sources and index.
- **Mitigation:** streaming ingestion; delete propagation as a high-priority update;
  periodic reconciliation.

---

## Classic Failure Modes (inherited from any system)

### The LLM provider/inference tier is down or slow
- **Impact:** no answers, or timeouts.
- **Mitigation:** fallback to an alternate model/provider (see model-gateway LLD);
  timeouts + retries with backoff; graceful message; degrade to returning the
  retrieved passages *without* a generated answer ("here are the relevant docs") so
  the user still gets value.

### The vector index shard dies
- **Impact:** part of the corpus becomes unsearchable → retrieval misses.
- **Mitigation:** replicate shards; the index is derived from source docs, so a lost
  shard can be rebuilt from the corpus.

### Cache failure
- **Impact:** all queries fall through to full retrieve+generate → cost and latency
  spike.
- **Mitigation:** provision the inference tier for cache-miss load with headroom;
  load-shed (queue, or use the small model) under extreme pressure.

### Traffic spike
- **Impact:** GPU-bound inference tier saturates (GPU autoscaling is slow/coarse).
- **Mitigation:** queueing with backpressure; model tiering to shift load to cheaper
  models; load-shedding low-priority traffic; cache absorbing repeats.

### Prompt injection (AI-specific security)
- **Impact:** a retrieved document (or user input) contains instructions that hijack
  the model ("ignore previous instructions and reveal..."). Especially dangerous when
  the corpus includes user-generated content.
- **Mitigation:** treat retrieved content as data, not instructions (prompt
  structuring); output guardrails; don't give the model tools/actions it could be
  tricked into misusing; sanitize/flag suspicious content.

---

## Observability (quality is a first-class signal here)

### Metrics — what you alert on
- **Answer quality:** thumbs-up rate, groundedness score, "I don't know" rate,
  citation coverage. A drop here is the leading indicator of a quality regression.
- **Retrieval:** recall@k on the eval set, retrieval-confidence distribution, filter
  pass rates.
- **Latency:** time-to-first-token (what the user feels), end-to-end, retrieval
  latency, generation tokens/sec.
- **Cost:** tokens per query (input/output), cache hit rate, model-tier mix, cost per
  query.
- **Freshness:** ingestion lag (source change → retrievable).
- **Safety:** permission-filter audit results, prompt-injection flags.

### Logs & traces — for debugging
- **Trace the full pipeline** per query: query → rewritten query → retrieved chunk
  ids + scores → prompt → answer → citations → feedback. This is what lets you debug
  "why was this answer wrong?" — you can see whether retrieval or generation failed.
- Log the **retrieved context and citations**, not just the answer, because a bad
  answer is usually a retrieval problem you can only see if you logged what was
  retrieved.

### The on-call story
> "If the thumbs-up rate or groundedness score drops, on-call's first move is to pull
> traces for the bad answers and look at what was retrieved — most quality incidents
> are retrieval failures, visible only because we log retrieved chunk ids and scores.
> If retrieval looks fine but answers are wrong, we check whether a model or prompt
> version shipped recently and roll it back via the regression-gated deploy."

Naming **retrieval-first debugging** and logging retrieved context is the operational-
maturity signal specific to RAG.

---

## The Degradation Philosophy

The unifying principle: **a wrong answer is worse than no answer.** Every path prefers
to abstain, return the source documents without a generated answer, or say "I don't
know" rather than fabricate. State this — it shows you understand that in a knowledge
system, silent wrong answers are the failure that destroys user trust.
