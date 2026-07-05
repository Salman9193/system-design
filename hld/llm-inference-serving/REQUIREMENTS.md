# LLM Inference Serving Platform — Requirements

**Prompt:** Design the platform that serves LLM inference — the system that takes
prompts from many client applications and returns generated tokens, running the
models on a GPU fleet at scale. This is the "black box" that a RAG assistant or any
LLM feature calls.

This design is the deepest "inside the tools" AI problem: it's where you show you
understand *how* LLM serving works (batching, KV cache, GPU memory) rather than just
calling an API. It's the infrastructure analog to designing a database or a message
queue.

---

## Phase 1 — Clarify Scope

### Clarifying questions worth asking
- **Whose models?** Serving our own open-weights models on our GPUs, or proxying to
  external providers? → *Assume we serve our own models on our own GPU fleet — that's
  the interesting design. (Proxying is the model-gateway LLD.)*
- **How many models?** One big model, or many models/versions/fine-tunes? → *Many —
  multiple base models and fine-tuned variants.*
- **Latency profile?** Interactive (chat) vs batch (offline processing)? → *Both —
  they have different SLAs and can share the fleet.*
- **Streaming?** → *Yes — token streaming is required for interactive UX.*
- **Multi-tenant?** → *Yes — many client apps/teams share the platform with isolation
  and fairness.*

---

## Functional Requirements

1. **Serve inference** — accept a prompt (+ generation params), return generated
   tokens.
2. **Stream tokens** — return output token-by-token for interactive clients.
3. **Serve many models** — multiple base models, versions, and fine-tuned variants,
   routed by request.
4. **Support interactive and batch** workloads on the shared fleet.
5. **Multi-tenant** — fair sharing, isolation, and per-tenant limits across client
   apps.

### Explicitly out of scope (state it)
- Training/fine-tuning the models — offline, separate system; we serve the artifacts.
- Provider proxying/fallback across external APIs — that's the model-gateway LLD.
- The application logic (RAG, agents) — those are clients of this platform.

---

## Non-Functional Requirements

| Requirement | Target | Why it matters |
|-------------|--------|----------------|
| **Latency** | Low TTFT (e.g. <1s); steady tokens/sec | Interactive UX; per-token generation |
| **Throughput** | Maximize tokens/sec per GPU | GPUs are the scarce, expensive resource |
| **Utilization** | Keep GPUs busy | The dominant cost lever |
| **Availability** | High | Many products depend on it |
| **Cost efficiency** | Minimize $/token | Inference is the dominant AI cost |
| **Isolation/fairness** | No tenant starves another | Multi-tenant platform |

**The binding constraints to state:** this is a **GPU-bound, cost-dominated,
latency-sensitive** system. GPUs are scarce and expensive, so the whole design
optimizes **utilization** (tokens/sec per GPU) while meeting latency SLAs. The two
metrics in tension are **throughput** (favors big batches) and **latency** (favors
small batches) — resolving that tension is the core of the design.

---

## Capacity & Cost Estimation

See `fundamentals/llm-inference-serving.md` for the mechanics.

**The unit is tokens, not requests:**
- A GPU replica serves some **tokens/second** (depends on model size, hardware,
  batching). Effective request throughput ≈ `tokens_per_sec / avg_output_tokens`.
- **Concurrency is capped by KV-cache memory:** `GPU_memory_for_kv / kv_per_sequence`
  ≈ max concurrent sequences. This — not compute — usually bounds how many requests a
  replica handles at once.

**Worked reasoning:**
> "Say a replica does ~2,000 tokens/sec and the average response is ~200 tokens —
> that's ~10 responses/sec per replica, but only if the KV cache holds enough
> concurrent sequences to keep the batch full. If KV memory only fits, say, 32
> concurrent sequences at our context length, that's my real concurrency ceiling. So
> I size the fleet by target tokens/sec ÷ per-replica tokens/sec, and I watch KV
> memory as the concurrency limiter."

**The conclusions that flow from the numbers:**
1. Cost = GPU-hours → **utilization is the #1 lever**; continuous batching is
   mandatory.
2. Concurrency is **KV-memory-bound** → memory management (paging the KV cache) is a
   core component.
3. Latency vs throughput tension → the **scheduler/batcher** is the heart of the
   system.
4. Many models on scarce GPUs → **model loading/placement** is a real problem (you
   can't fit every model on every GPU).

---

## The One-Sentence Framing

> "This is a GPU-bound, cost-dominated serving system where the scarce resource is
> GPU memory and compute, and the unit of work is the token. The core design problem
> is a scheduler that packs many requests into continuously-batched GPU work to
> maximize tokens/sec per GPU, while meeting time-to-first-token SLAs — balancing the
> throughput-vs-latency tension. KV-cache memory is the concurrency ceiling, model
> placement across the fleet is the routing problem, and streaming plus fair
> multi-tenant scheduling round it out."

That framing names the scarce resource (GPU memory), the unit (tokens), the core
component (the scheduler), and the central tension (throughput vs latency) up front.
