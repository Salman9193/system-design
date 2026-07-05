# LLM Inference Serving Platform — Trade-offs

Every decision trades against the same axis the requirements named: **GPU utilization/
cost vs latency**, plus **complexity**. Name these unprompted.

---

## Continuous vs Static Batching

| | Continuous batching (chosen) | Static batching |
|--|------------------------------|-----------------|
| GPU utilization | High — no idle waiting | Low — waits for slowest in batch |
| Latency under mixed lengths | Good | Bad — head-of-line blocking |
| Implementation | Complex scheduler | Simple |

**Decision:** continuous batching. The utilization win is decisive for a cost-
dominated GPU system, and it's what makes mixed request lengths tolerable. The cost is
a much more complex scheduler — worth it.

---

## Paged vs Contiguous KV Cache

| | Paged KV (chosen) | Contiguous per-request |
|--|-------------------|------------------------|
| Memory efficiency | High — near-zero fragmentation | Low — reserves max length |
| Concurrency | Many more sequences | Far fewer |
| Prefix sharing | Yes | No |
| Complexity | Higher (page table, allocator) | Simple |

**Decision:** paged. Since concurrency is memory-bound, removing fragmentation
directly raises throughput, and prefix sharing is a bonus. The complexity of a paging
allocator is justified by the throughput gain.

---

## Bigger vs Smaller Batches (throughput vs latency)

**Decision:** don't globally pick — **segregate by workload**. Interactive traffic
runs with latency-bounded admission (protect TTFT); batch traffic backfills to push
utilization. A single global batch size would force a bad compromise; separating the
two lets each be optimized. The trade-off is scheduler complexity and needing to
classify traffic by priority.

---

## On-Demand Loading vs Always-Warm

| | On-demand (cold start) | Always-warm |
|--|------------------------|-------------|
| Cost | Low — GPUs freed when idle | High — GPUs reserved idle |
| First-request latency | Slow (load weights) | Fast |
| Best for | Cold/rare models | Hot/latency-sensitive models |

**Decision:** hybrid by model heat. Hot and latency-sensitive models stay warm; cold
models load on demand behind a queue. The trade-off is managing the warm-pool size —
too small hurts latency, too large wastes scarce GPUs.

---

## Fine-Tune Multiplexing vs Dedicated Replicas

**Decision:** multiplex fine-tunes over shared base weights where the serving stack
supports it. Serving many adapters over one resident base model is dramatically
cheaper than a dedicated replica per variant. The trade-off is added serving
complexity and some per-request adapter-switching overhead — usually well worth it
when there are many variants.

---

## Self-Hosting vs Proxying to Providers

**Decision (scoped to this design):** self-host on our GPU fleet. Self-hosting gives
control over cost, latency, data privacy, and custom/fine-tuned models, at the cost of
operating a GPU fleet (scarcity, cold starts, ops burden). Proxying to external
providers trades that control for zero infra but higher marginal cost and less
control — which is the model-gateway LLD's domain. Real orgs often do **both**:
self-host high-volume workloads, burst to providers.

---

## Speculative Decoding: On vs Off

**Decision:** on for latency-critical paths. A small draft model proposes tokens the
big model verifies in one pass, cutting decode latency with **identical** output. The
trade-off is added complexity and GPU memory for the draft model, plus diminishing
returns when the draft and target disagree often — so apply it where latency matters
most.

---

## The Meta-Point

Every trade serves the binding constraint: **maximize tokens/sec per GPU (cost) while
meeting latency SLAs.** Continuous batching and paged KV raise utilization; workload
segregation and warm pools protect latency; multiplexing and self-hosting control
cost. The coherence — every knob pointed at cost-under-latency — is the staff signal.
And the honest framing that real systems combine self-hosting *and* provider proxying
shows you're reasoning about the actual operating point, not a purist design.
