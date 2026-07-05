# LLM Inference Serving Platform — Failure Modes & Observability

A GPU-bound platform has distinctive failure modes centered on the scarce resource
(GPU memory/compute) and the slow control actions (model loading). Walk these
proactively; observability here is heavy on GPU and token metrics.

---

## Failure Modes

### GPU out-of-memory (OOM)
- **Impact:** the KV cache fills and the replica can't admit — or worse, crashes — if
  memory isn't managed defensively.
- **Detection:** KV-memory-utilization metric near capacity; admission-wait spikes.
- **Mitigation:** **admission control** — never admit a sequence you can't allocate KV
  for; preempt/evict long sequences under pressure; cap max context length; paged KV
  to use memory efficiently. OOM should be prevented by admission, not hit at runtime.

### A GPU / replica dies
- **Impact:** in-flight sequences on that replica are lost; its model capacity drops.
- **Detection:** health checks; sudden queue growth on remaining replicas.
- **Recovery:** replicate models across replicas; the router steers around the dead
  one; clients retry idempotently. Because generation is stateful mid-stream, a failed
  request must restart (no partial resume) — so keep responses idempotent to retry.

### Cold-start storm
- **Impact:** a spike for a cold (not-loaded) model triggers slow loads; requests pile
  up behind minutes-long weight loading.
- **Detection:** cold-start latency metric; queue depth for a specific model.
- **Mitigation:** predictive autoscaling (scale ahead of demand); warm pools for
  spiky/latency-sensitive models; load-shed or queue-with-ETA during a cold start;
  fast model loading.

### Latency SLA breach from batch pressure
- **Impact:** heavy batch/offline load inflates batch size and starves interactive
  requests' TTFT.
- **Detection:** interactive TTFT crossing SLA while utilization is high.
- **Mitigation:** priority scheduling — cap batch admission to protect interactive
  TTFT; shed/deprioritize batch work first.

### A runaway / very long generation
- **Impact:** a request generating to a huge max_tokens holds KV memory and a slot for
  a long time, hurting others.
- **Detection:** per-request token counters; long-lived sequences.
- **Mitigation:** enforce max_tokens caps; timeouts; preemption; token-based fairness
  so one long generation doesn't monopolize.

### Noisy neighbor (multi-tenant)
- **Impact:** one tenant's burst degrades others.
- **Mitigation:** per-tenant token quotas, concurrency caps, weighted fair scheduling
  (see deep dives).

### Bad model version deployed
- **Impact:** a new model/version serves degraded or broken outputs.
- **Detection:** canary the new version on a traffic slice; compare quality/latency
  metrics before full rollout.
- **Recovery:** the registry enables instant rollback to the previous version;
  atomic version switch.

---

## Observability (GPU and tokens are first-class)

### Metrics — what you alert on
- **GPU utilization** and **KV-memory utilization** per replica — the core health/cost
  signals. Low GPU utilization means wasted money; high KV utilization predicts
  admission stalls.
- **Throughput:** tokens/sec per replica and fleet-wide.
- **Latency:** time-to-first-token (interactive SLA), inter-token latency, queue wait.
- **Batch metrics:** batch size, prefill/decode ratio, preemption rate.
- **Cold starts:** model load time, warm-pool hit rate.
- **Per-tenant:** tokens consumed, quota usage, fairness (is any tenant starved?).
- **Cost:** $/token, GPU-hours, utilization efficiency.

### Logs & traces
- Trace a request: gateway → router → replica → scheduler admission → prefill → decode
  → stream. Latency attribution shows whether time went to queueing, prefill, or
  decode.
- Log admission decisions and preemptions — essential for debugging why a request was
  slow or shed.

### The on-call story
> "If interactive TTFT breaches SLA, on-call checks GPU and KV-memory utilization
> first. High KV utilization with a growing queue means we're concurrency-bound — shed
> or deprioritize batch traffic to reclaim slots. If it's a specific model's queue,
> it's likely a cold-start storm — check warm-pool hit rate and load times. If quality
> dropped rather than latency, check for a recent model-version rollout and roll back
> via the registry."

Naming **GPU/KV utilization as the first dashboard** is the operational-maturity
signal specific to inference serving.

---

## The Degradation Philosophy

Under overload, the platform sheds gracefully in priority order: **protect interactive
TTFT SLAs first, shed/queue batch work, then apply backpressure to the lowest-priority
tenants** — rather than letting utilization collapse or letting every request degrade
equally. And admission control means the system says "wait" (queue with backpressure)
rather than accepting work it has no GPU memory to serve. A predictable "queued, ETA
2s" beats an unpredictable timeout.
