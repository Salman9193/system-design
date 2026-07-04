# Search Typeahead — Failure Modes & Observability

At L6, failure modes and observability are expected proactively — they're now
first-class rubric items in 2026, not bonus topics. This file walks what breaks, how
you detect it, and how you recover. Raise these yourself: *"Before I wrap up, let me
cover how this fails and how we'd know."*

---

## Failure Modes

### An index shard dies
- **Impact:** prefixes owned by that shard return no suggestions.
- **Detection:** health checks + a drop in that shard's request success rate.
- **Recovery:** replicate every shard (≥2 replicas); route to a replica on failure.
  Because the Trie is rebuilt from logs, a lost shard can also be regenerated from
  the last build artifact — no data is truly lost, it's derived.
- **Graceful degradation:** if a shard and its replicas are all down, return an empty
  suggestion list. **No suggestions is far better than a slow or hung search box** —
  the user can still type and hit enter.

### The cache tier fails (edge or Redis)
- **Impact:** all traffic falls through to the origin Tries — potentially ~1M QPS of
  cold reads against nodes sized for cache-miss traffic only.
- **Detection:** cache hit-rate dashboard falls off a cliff; origin QPS spikes.
- **Recovery:** the in-memory Tries can serve directly (they're the source of
  truth), but must be provisioned with enough headroom / autoscaling to survive a
  cache outage. Load-shed if necessary: drop the `limit` to fewer suggestions, or
  serve only short prefixes.

### Thundering herd on a hot prefix
- **Impact:** a popular prefix's cache entry expires and thousands of concurrent
  misses stampede the origin.
- **Detection:** origin latency spikes correlated with cache-key expiries.
- **Recovery:** request coalescing (single-flight — one request recomputes, the rest
  wait on it); probabilistic early expiry so hot keys refresh before they expire
  together.

### Cache penetration (nonexistent prefixes)
- **Impact:** requests for gibberish prefixes ("xqzk") always miss and hit the
  origin; an attacker can weaponize this.
- **Detection:** high miss rate for prefixes with no completions.
- **Recovery:** cache the negative result (empty top-K) with a short TTL; optionally
  a Bloom filter of known prefixes in front to reject impossible ones cheaply.

### The build/aggregation pipeline breaks
- **Impact:** the serving Tries go stale — no new trending queries appear, but
  **serving keeps working** on the last good Trie.
- **Detection:** "time since last successful rebuild" metric crosses a threshold;
  alert.
- **Recovery:** this is a soft failure — serving is unaffected, so it's a non-paging
  alert. Fix the pipeline, ship the next rebuild. Keep the last N build artifacts so
  a bad build can be rolled back to a known-good Trie.

### A bad Trie build ships (corrupt/empty rankings)
- **Impact:** suggestions become garbage or empty across the board.
- **Detection:** canary the new build on a small fraction of traffic; compare
  suggestion quality metrics (click-through, empty-result rate) against the current
  build before full rollout.
- **Recovery:** atomic hot-swap means atomic rollback — revert to the previous
  artifact instantly.

### Traffic spike (10×)
- **Impact:** viral event drives keystroke traffic far above peak estimate.
- **Recovery:** edge cache absorbs most of it (common prefixes dominate during a
  shared event); autoscale the stateless suggest service; load-shed by reducing
  `limit` under extreme load.

---

## Observability (first-class, not a bonus)

### Metrics — what you alert on
- **p50 / p99 / p999 suggestion latency** — the core SLO. Alert if p99 > 100ms.
- **Cache hit rate** (per layer: edge, Redis) — the leading indicator of origin
  load. A drop here predicts a latency spike.
- **Origin QPS per shard** — detects hot-shard skew.
- **Empty-suggestion rate** — a spike means a broken shard or bad build.
- **Time since last successful rebuild** — freshness health.
- **Suggestion click-through rate** — the quality signal; a drop after a build means
  a bad ranking shipped.

### Logs — for debugging an incident
- Sampled request logs (prefix, chosen shard, cache layer hit, latency) — enough to
  reconstruct why a slow request was slow, without logging all 1M QPS.
- Build pipeline logs — which stage failed, input/output row counts per stage.

### Traces — following a request across services
- Trace a suggest request: client → edge → suggest service → shard. A trace shows
  exactly where the latency budget was spent (was it the shard lookup, or the
  network hop?).

### The on-call story (say this out loud)
> "If p99 latency alerts, on-call's first stop is the cache hit-rate dashboard — a
> cache-tier problem is the most common cause of a latency spike here. If hit rate is
> healthy, they check per-shard latency for a hot shard. If suggestions are empty
> rather than slow, they check the last-rebuild status and the shard health, and can
> roll back the last build with one action."

That concrete runbook — naming the first dashboard on-call checks — is the
operational-maturity signal that reads as L6.

---

## The Degradation Philosophy

The unifying principle across all these failures: **a search box that's slow is
worse than one with no suggestions.** Every recovery path prefers to degrade
(fewer/empty suggestions, served fast) rather than block or hang. State this
philosophy explicitly — it shows you understand the product, not just the system.
