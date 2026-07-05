# LLM Inference Serving Platform — Deep Dives

This is the deepest "inside the box" AI design, so the deep dives are mechanical and
specific. Each is a place the interviewer says "show me how that actually works."

---

## Deep Dive 1 — Continuous Batching (the throughput mechanism)

### The problem with static batching
A GPU is massively underutilized by one request, so you batch. But **static batching**
— collect N requests, run them together to completion — has two killers for LLMs:
requests have wildly different output lengths, so the whole batch waits for the
longest one (head-of-line blocking), and you idle while waiting to fill the batch.

### Continuous / in-flight batching
The scheduler manages the batch **at token granularity**. Each decode step, it:
- removes sequences that just emitted a stop token (freeing their slot + KV memory),
- admits queued requests into the freed slots (running their prefill, then joining
  decode).

So the GPU is always working on a full batch of *active* sequences, regardless of
their differing lengths. This is the single biggest throughput win in LLM serving and
the reason production serving engines exist.

> "Static batching wastes the GPU because a 20-token reply waits behind a 2,000-token
> one. Continuous batching swaps sequences in and out of the running batch each token
> step, so a finished short request immediately frees its slot for a queued one — the
> GPU stays saturated across mixed lengths."

### The prefill/decode tension
Admitting a new request means running its **prefill** (processing its whole prompt),
which is a compute spike that can stall the ongoing **decodes** (raising everyone's
inter-token latency). Schedulers manage this by chunking/interleaving prefill with
decode. Naming this tension shows real depth.

---

## Deep Dive 2 — KV-Cache Memory (the concurrency ceiling)

### Why memory, not compute, is the limit
Each active sequence holds a KV cache proportional to its length. The sum of all
active sequences' KV caches must fit in GPU memory alongside the model weights. So the
number of concurrent sequences — your throughput — is **bounded by KV memory**, not by
raw compute. Managing that memory *is* managing throughput.

### Paged KV cache
Naively, you'd reserve a contiguous KV region per request sized for its max possible
length. Since actual length is unknown and usually much shorter, this **wastes most of
the memory** to internal fragmentation — so you can serve far fewer requests than the
GPU could hold.

The fix borrows from OS virtual memory: allocate KV memory in small fixed-size
**pages**, assigned to a sequence on demand as it grows. Benefits:
- Near-zero fragmentation → many more concurrent sequences → higher throughput.
- Pages can be **shared** across sequences with a common prefix (e.g. the same system
  prompt), saving memory and prefill compute — prefix caching.

> "Concurrency here is KV-memory-bound, so the win is paging the KV cache: allocate it
> in small pages on demand instead of reserving max-length regions per request. That
> removes the fragmentation that otherwise wastes most of the memory, and it lets
> sequences sharing a prefix share pages — so a common system prompt is stored and
> prefilled once."

### Preemption
Under pressure, the scheduler can preempt a sequence (evict its KV, recompute later)
to keep admitting others — trading recompute for fairness/throughput.

---

## Deep Dive 3 — Model Placement Across a Scarce Fleet

### The problem
Each model is many GB; a GPU holds one or a few. With many models/versions/fine-tunes
and limited GPUs, you can't load everything everywhere. Placement decides which
replicas hold which models.

### Approaches
- **Demand-based placement:** allocate replica count per model by traffic — hot models
  get many replicas, cold models few.
- **On-demand loading:** cold models aren't resident; load on first request, accepting
  a **cold-start** penalty (seconds-to-minutes to load weights into GPU memory). Keep a
  small warm pool for latency-sensitive models.
- **Fine-tune multiplexing:** many fine-tunes of one base model share the base weights
  resident in GPU memory; only the small adapter differs per request. One GPU can then
  serve dozens of fine-tuned variants for barely more than one base model — a major
  efficiency lever.

### Cold start is the hard part
Because loading is slow, **autoscaling a model up is slow** — unlike a stateless CPU
service you can't spin up in milliseconds. So the autoscaler must be **predictive**
(scale ahead of demand), keep warm capacity for spiky models, and the router must
route around not-yet-warm replicas.

> "The wrinkle versus stateless serving is cold start: loading weights takes seconds
> to minutes, so I can't reactively autoscale a model on a spike. I'd place hot models
> with headroom, keep warm pools for latency-sensitive ones, load cold models on
> demand behind a queue, and multiplex fine-tunes over shared base weights so variants
> are nearly free."

---

## Deep Dive 4 — Throughput vs Latency (the central tension)

Every knob trades these two:
- **Bigger batches** → higher throughput (tokens/sec per GPU, lower $/token) but higher
  latency (more per-step work, queueing).
- **Smaller batches** → lower latency (faster TTFT) but worse utilization and higher
  cost.

### Resolving it
- **Separate interactive from batch traffic** by priority: interactive gets latency
  headroom (smaller effective batch, admitted fast); batch requests fill spare
  capacity to push utilization up without hurting interactive SLAs.
- **SLA-aware admission:** cap batch size to keep TTFT within the interactive SLA,
  then backfill with batch work.
- **Speculative decoding** for latency-critical paths: a small draft model proposes
  several tokens, the big model verifies them in one pass — faster decode with
  identical output (see `fundamentals/llm-inference-serving.md`).

> "Throughput and latency pull opposite ways on batch size, so I don't pick one — I
> segregate traffic: interactive requests get admitted fast with a bounded batch to
> protect TTFT, and batch/offline work backfills spare GPU capacity to keep
> utilization high. That way the same fleet serves both without the batch load
> hurting interactive latency."

---

## Deep Dive 5 — Multi-Tenancy & Fairness

Many client apps share the fleet, so one tenant must not degrade another.

- **Token-based quotas & rate limits** per tenant (tokens are the resource — see the
  model-gateway LLD).
- **Fair scheduling:** the scheduler allocates GPU time fairly across tenants (e.g.
  weighted fair queuing on tokens), so a heavy tenant can't starve others.
- **Isolation:** noisy-neighbor protection via admission control and per-tenant
  concurrency caps.
- **Priority tiers:** interactive > batch; premium tenants > best-effort.

> "Fairness is on tokens, not requests, since a request can be 10 or 10,000 tokens. I'd
> use weighted fair queuing over tokens with per-tenant concurrency caps, so a tenant
> submitting huge batch jobs can't monopolize the GPUs and starve interactive tenants."

This connects the platform to the model-gateway LLD, where token-based limiting is
implemented as a concrete component.
