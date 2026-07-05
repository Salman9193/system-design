# LLM Inference Serving Platform — High-Level Design

The system is organized around one scarce resource — the GPU — and one central
component — the **scheduler** that decides what runs on it. Everything else (routing,
memory management, streaming) exists to keep the GPUs maximally utilized while meeting
latency SLAs.

---

## Architecture

```
  ┌────────┐   ┌──────────────┐   ┌───────────────┐
  │ Client │──▶│ Gateway       │──▶│ Router         │
  │ apps   │   │ (auth, token  │   │ (pick model    │
  │        │◀──│  rate-limit,  │   │  replica by    │
  │        │   │  quotas)      │   │  model+load)   │
  └────────┘   └──────────────┘   └───────┬────────┘
     ▲   streamed tokens                  │
     │                                    ▼
     │                     ┌──────────────────────────────┐
     │                     │ Inference replica (per model) │
     │                     │  ┌────────────────────────┐   │
     └─────────────────────│  │ Scheduler / batcher    │   │
                           │  │ (continuous batching,  │   │
                           │  │  queue, admission)     │   │
                           │  └───────────┬────────────┘   │
                           │              ▼                │
                           │  ┌────────────────────────┐   │
                           │  │ Model on GPU(s)         │   │
                           │  │ + KV-cache manager      │   │
                           │  │ (paged GPU memory)      │   │
                           │  └────────────────────────┘   │
                           └──────────────────────────────┘

  Control plane: model registry, autoscaler, placement, metrics/observability
```

---

## Request Flow

1. **Gateway** — authenticates the caller, enforces **token-based** rate limits and
   per-tenant quotas, validates the request. Rejects/queues before expensive work.
2. **Router** — selects a replica that (a) has the requested model loaded and (b) has
   capacity (shortest queue / most free KV memory). Model placement is non-trivial
   because not every GPU holds every model (see deep dives).
3. **Scheduler / batcher** (on the replica) — the heart. Admits the request into the
   **continuously-batched** running set, managing the queue and the throughput-vs-
   latency trade-off.
4. **Model + KV-cache manager** — runs prefill then decode, storing per-sequence
   keys/values in **paged** GPU memory so many sequences share the KV space
   efficiently.
5. **Streaming** — tokens are streamed back through the gateway to the client (SSE/
   websocket) as they're generated.

---

## The Scheduler (the core component)

This is what the design is really about. It continuously decides which requests run
in the current GPU batch.

- **Continuous (in-flight) batching:** at each token step, finished sequences leave
  the batch and queued requests join — so the GPU never idles waiting for the slowest
  request in a static batch (see `fundamentals/llm-inference-serving.md`).
- **Prefill vs decode scheduling:** prefill (processing a new prompt) is compute-heavy
  and bursty; decode (generating tokens) is steady. The scheduler interleaves them —
  too much prefill starves ongoing decodes (hurting others' token latency); too little
  delays new requests' first token. This balance is a key tuning problem.
- **Admission control:** if the KV cache is full, new requests must wait (or be
  rejected/shed) — you can't admit a request you have no memory to serve.
- **Priority & fairness:** interactive requests get latency priority; batch requests
  fill spare capacity; per-tenant fairness prevents one tenant from starving others.

---

## KV-Cache Memory Management

Concurrency is bounded by KV-cache memory, so managing it well directly increases how
many requests a GPU serves.

- **Paged KV cache:** instead of reserving a big contiguous KV region per request
  (wasteful, since final length is unknown), allocate KV memory in small **pages** on
  demand — like virtual memory paging. This slashes fragmentation and lets far more
  sequences share the GPU, raising throughput.
- **Eviction/preemption:** under memory pressure, a long-running sequence can be
  preempted (its KV recomputed or offloaded later) to admit others — a scheduling
  policy decision.

Naming paged KV memory as the thing that lifts the concurrency ceiling is a strong
"inside the box" signal.

---

## Model Registry & Placement (control plane)

Many models, scarce GPUs — you can't load every model on every GPU (each model is
many GB).

- **Registry:** tracks model artifacts, versions, and fine-tuned variants.
- **Placement:** decides which replicas load which models, based on demand. Hot models
  get more replicas; cold models get few or are loaded on demand.
- **Cold start:** loading a model into GPU memory takes seconds-to-minutes, so scaling
  a model up is slow — the autoscaler must be predictive, and requests for a not-yet-
  loaded model face a cold-start latency (mitigated by keeping warm replicas or fast
  loading).
- **Fine-tune sharing:** many fine-tunes of one base model can share the base weights
  in memory (only the adapter differs), letting one GPU serve many variants cheaply —
  a real efficiency lever worth naming.

---

## Data Model / Interfaces

**Inference request:**
```
InferenceRequest {
  model_id, version
  prompt (tokens or text)
  params { max_tokens, temperature, stop, stream }
  tenant_id, priority (interactive | batch)
}
```

**API:**
```
POST /v1/generate        → streamed tokens (SSE), or full response if stream=false
GET  /v1/models          → available models/versions
```

Token-based accounting is attached to every request (input + output tokens) for
quotas, billing, and rate limiting.

---

## Why This Shape

Every decision serves the binding constraint (GPU utilization under latency SLAs):

- **Scheduler with continuous batching** → keeps GPUs busy; the #1 cost lever.
- **Paged KV cache** → raises the concurrency ceiling (memory-bound) → more throughput
  per GPU.
- **Router + placement** → many models on scarce GPUs without loading everything
  everywhere.
- **Priority/fairness scheduling** → interactive latency SLAs met while batch fills
  spare capacity; multi-tenant fairness.
- **Streaming** → fast time-to-first-token despite slow full generation.

The deep dives (`DEEP_DIVES.md`) go inside the scheduler, KV memory, and model
placement — the components an interviewer will drill.
