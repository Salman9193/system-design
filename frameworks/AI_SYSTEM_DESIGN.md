# AI System Design — What Changes When ML/LLMs Enter the System

System design with an AI/ML component is not a different discipline — it's the same
discipline with **new failure modes, new cost curves, and new quality dimensions**.
At the staff level, interviewers increasingly expect you to reason about systems
that include a model, and the discriminator is whether you treat the model as a
magic box or as an engineered component with its own operational reality.

This document frames the **traditional → AI** shift and the extra dimensions AI
systems are scored on, so the HLD and LLD templates in this repo can lean on it.

---

## The Core Shift: Deterministic → Probabilistic

A traditional system is deterministic: the same input yields the same output, and
you verify it with unit tests that assert exact equality. An AI system is
**probabilistic**: the same input can yield different outputs, "correct" is a
distribution rather than a value, and you verify it with **evaluation** over a
dataset, not assertions.

Almost every hard problem in AI system design flows from this one shift:

| Dimension | Traditional system | AI system |
|-----------|-------------------|-----------|
| Correctness | Deterministic; unit tests assert equality | Probabilistic; **eval** over a dataset, measured as a score |
| Testing | Pass/fail on fixed cases | Eval suites, regression on quality metrics, A/B tests |
| Failure | Crashes, errors, timeouts | **Silent wrong answers** (hallucination), quality drift |
| Latency | Roughly constant per request | Variable; grows with output length (per-token generation) |
| Cost | Per request, ~flat | **Per token**, and often 10–100× higher |
| Debugging | Stack traces, logs | Traces + the inputs/outputs + eval scores; harder to reproduce |
| Versioning | Code | Code **+ model + prompt + data** — all four are versioned artifacts |
| Improvement | Ship a code fix | Retrain / fine-tune / change prompt / improve retrieval — a **data flywheel** |

---

## The Extra Rubric Dimensions (score these, unprompted)

A traditional design is scored on requirements, architecture, deep dives,
trade-offs, and failure modes. An AI design adds these, and raising them yourself is
the staff signal:

### 1. Evaluation ("how do you know it's good?")
There are no unit tests for "is this a good answer." You need an **eval harness**: a
curated dataset, metrics (exact-match, semantic similarity, LLM-as-judge, human
review), and a regression gate so a model/prompt change can't silently degrade
quality. If you finish an AI design without saying how you'd measure quality, you've
left the biggest points on the table.

### 2. Non-determinism & reproducibility
The same request can produce different outputs. Name how you handle it: temperature
settings, seeds where available, caching, and the fact that your tests must assert
on *properties* (contains the citation, valid JSON, no PII) rather than exact
strings.

### 3. Hallucination & guardrails
The model can produce confident, fluent, **wrong** output. This is the AI analog of
data corruption, except it's the default behavior, not an edge case. Guardrails:
grounding (RAG), citation/attribution, output validation, confidence thresholds,
and human-in-the-loop for high-stakes decisions.

### 4. Cost per token (the new capacity dimension)
LLM inference is often the dominant cost, priced per token, and 10–100× the cost of
a traditional request. Capacity estimation now includes **tokens/request ×
requests/day × price/token**. Controlling it — caching, smaller models for easy
queries, truncating context — is a first-class design concern.

### 5. Latency shape (per-token, not per-request)
Generation latency grows with output length because tokens are produced one at a
time. "Time to first token" and "tokens per second" replace a single latency
number. Streaming responses is often mandatory for UX.

### 6. The data flywheel
AI systems improve by capturing usage → labeling/curating → retraining/refining →
serving a better model. Designing the loop that captures signal (thumbs up/down,
corrections, click-through) is what makes the system get better over time — a staff
concern that traditional systems don't have.

### 7. The training/serving split
There are two paths: **offline** (train/fine-tune/build indexes) and **online**
(serve inference). They have completely different scale, latency, and cost profiles
— the same two-path pattern as the batch/serving split in traditional systems, but
now the offline path produces a *model*, not just an index.

---

## The "Open the Box" Test, AI Edition

Just as interviewers push you off "I'll use Kafka," they push you off "I'll call an
LLM API" or "I'll use a vector database." Be ready to open these boxes:

| You say | They ask | You should know |
|---------|----------|-----------------|
| "I'll call an LLM" | "How does it serve at scale?" | Batching, KV cache, why latency grows with length (see `fundamentals/llm-inference-serving.md`) |
| "I'll use a vector DB" | "How does similarity search work?" | Embeddings + ANN (HNSW/IVF), recall/latency trade-off (see `fundamentals/embeddings-and-vector-search.md`) |
| "I'll use RAG" | "Why not just fine-tune?" | RAG vs fine-tuning trade-off; grounding & freshness |
| "I'll cache responses" | "Identical prompts are rare" | **Semantic** caching on embedding similarity |
| "I'll rate-limit" | "By requests?" | **Token**-based limiting; tokens are the real resource |

The repo's fundamentals exist so none of these is a black box.

---

## The Traditional → AI Progression (how to present it)

The strongest way to include AI in a design is to **show the evolution**, not to
start from scratch. For any system:

1. **Traditional baseline** — how you'd build it without ML. (Keyword search,
   rule-based ranking, deterministic logic.)
2. **Where it falls short** — the limitation ML addresses. (Keyword search misses
   semantic matches; rules can't handle the long tail.)
3. **The AI evolution** — the ML/LLM component that helps, *and its new costs*.
   (Embedding retrieval adds semantic recall but adds an index, an embedding model,
   and a recall/latency trade-off.)
4. **The hybrid reality** — production systems are usually **both**. (Keyword +
   vector = hybrid retrieval; rules + model = a model with a rule-based guardrail.)

This progression is itself the staff signal: it shows you adopt AI where it earns
its place, understand what it costs, and don't cargo-cult it where a deterministic
solution is better. **"Should this even use ML?"** is a legitimate and impressive
question to raise — often the answer for part of the system is no.

---

## The One-Line Summary

An AI system is a traditional system plus a probabilistic component — so you inherit
every traditional concern **and** add eval, hallucination/guardrails, cost-per-token,
per-token latency, non-determinism, and the data flywheel. Design for all of them,
show the traditional→AI progression, and be ready to open the model and the vector
index the same way you'd open Kafka.
