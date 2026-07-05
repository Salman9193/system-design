# LLM Inference & Serving — Inside the Box

When you say "I'll call an LLM," the follow-up is "how does that serve at scale, and
why is it slow and expensive?" This is the box to open. LLM serving has a very
different performance profile from a traditional stateless service, and the reasons
are mechanical — they come from how autoregressive generation works.

---

## Why LLM Inference Is Unusual

A traditional request does a bounded amount of work and returns. LLM generation is
**autoregressive**: the model produces one token, appends it to the input, and runs
again to produce the next — repeating until it emits a stop token. So:

- **Latency grows with output length.** 500 output tokens ≈ 500 sequential forward
  passes. You can't parallelize across the tokens of a single response.
- **The two phases have different costs.** *Prefill* (processing the prompt) is
  parallel over all input tokens and compute-heavy. *Decode* (generating output) is
  one token at a time and memory-bandwidth-bound.
- **It's memory-hungry.** Model weights (billions of parameters) plus per-request
  state must live in GPU memory.

This is why the key metrics are **time-to-first-token (TTFT)** — dominated by prefill
— and **inter-token latency / tokens-per-second** — dominated by decode.

---

## The KV Cache (the single most important optimization)

At each generation step the model attends to all previous tokens. Recomputing the
attention keys and values for the whole sequence every step would be quadratic and
wasteful. Instead, the **KV cache** stores the key/value tensors for tokens already
processed, so each new token only computes its own K/V and reuses the rest.

- **Effect:** turns per-step work from "reprocess the whole sequence" into "process
  one token," making generation roughly linear instead of quadratic.
- **Cost:** the KV cache consumes GPU memory that **grows with sequence length ×
  batch size**. For long contexts and many concurrent requests, the KV cache — not
  the model weights — becomes the memory bottleneck that caps how many requests you
  can serve at once.

Knowing that the KV cache is what bounds concurrency (via memory) is a strong "inside
the box" signal.

---

## Batching — How You Get Throughput

A single request badly underutilizes a GPU. **Batching** runs many requests together
to amortize the cost of loading weights and to fill the hardware.

- **Static batching:** wait, collect a batch, run it. Simple, but a long request in
  the batch holds up the short ones (head-of-line blocking), and you pay latency
  waiting to fill the batch.
- **Continuous / in-flight batching:** the serving engine adds and removes requests
  from the running batch **at each token step**, so a finished request frees its slot
  immediately and a new one joins without waiting for the whole batch. This is the
  standard for production LLM serving — it dramatically improves GPU utilization and
  throughput under mixed request lengths.

> The staff move: "Throughput comes from continuous batching — the scheduler packs
> requests into the running batch per token step, so we're not idling the GPU waiting
> for the slowest request. The KV cache memory then caps the max batch size, which is
> the real concurrency limit."

---

## Making the Model Smaller/Faster

Techniques to cut latency, memory, and cost, each with a trade-off:

| Technique | What it does | Trade-off |
|-----------|-------------|-----------|
| **Quantization** | Store/compute weights at lower precision (e.g. 8- or 4-bit) | Big memory + speed win; small quality loss if done well |
| **Distillation** | Train a small model to mimic a big one | Cheaper/faster; ceiling on quality |
| **Speculative decoding** | A small draft model proposes tokens; the big model verifies several at once | Faster decode with identical output; added complexity |
| **Model parallelism** | Split one model across GPUs (tensor/pipeline parallel) | Serves models too big for one GPU; adds inter-GPU communication |
| **Pruning / sparsity** | Remove low-value weights | Smaller; quality risk |

The recurring theme: you trade a little quality or complexity for large gains in
cost and latency — and *which* trade is right depends on the quality bar from your
eval harness.

---

## Serving Architecture

A production LLM serving stack looks like:

```
client ──► gateway (auth, rate-limit by TOKENS, routing)
              │
              ├─► response cache (exact + semantic)  ── hit ─► return
              │
              ▼
        inference scheduler (continuous batching, queueing)
              │
              ▼
        model replicas on GPUs (KV cache in GPU memory)
              │
              ▼
        token stream ──► streamed back to client (SSE/websocket)
```

Key points:
- **Token-based rate limiting**, not request-based — tokens are the real resource
  (see the model-gateway LLD).
- **Caching** matters enormously because inference is expensive; exact-match for
  identical prompts, **semantic** cache for near-duplicates.
- **Streaming** the response (server-sent events) so the user sees the first token
  fast, since full generation is slow.
- **Autoscaling on GPU** is coarse and slow (GPUs are scarce and expensive to spin
  up), so queueing and load-shedding matter more than in CPU services.

---

## Capacity & Cost

The numbers that drive an LLM serving design:

- **Throughput** is tokens/second per GPU, not requests/second. A replica's capacity
  is roughly `(tokens/sec) / (avg output tokens per request)` requests/sec.
- **Concurrency** is capped by KV-cache memory: `GPU memory available / KV memory per
  request` ≈ max concurrent requests.
- **Cost** is dominated by GPU-hours. Utilization (via continuous batching) is the
  biggest cost lever; a poorly-batched GPU serving one request at a time can be 10×+
  less cost-efficient.

> "The concurrency ceiling here is KV-cache memory, not compute — so I'd size
> replicas by how many concurrent sequences fit in GPU memory at our context length,
> use continuous batching to keep utilization high, and cache aggressively because
> the cheapest token is the one we never generate."

---

## The Interview Checklist

1. **Why is it slow?** Autoregressive decode — one token at a time, latency ∝ output
   length.
2. **KV cache** — reuses past K/V; its memory caps concurrency.
3. **Continuous batching** — the throughput mechanism; packs the GPU per token step.
4. **Smaller/faster** — quantization, distillation, speculative decoding, and their
   quality trade-offs.
5. **Serving stack** — token rate-limiting, caching (exact + semantic), streaming.
6. **Capacity** — tokens/sec and KV memory, not requests and CPU.

Being able to walk these is exactly the "implement the stream processor yourself"
depth, applied to model serving.
