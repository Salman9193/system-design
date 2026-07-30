# Distributed Rate Limiter — Requirements

Design a rate limiter that works across a **fleet** of servers: enforce "at most N requests per
window per key" when the traffic for a single key is spread over hundreds of machines behind a load
balancer.

This is the distributed continuation of the [Rate Limiter LLD](#lld-rate-limiter), which covers the
single-node algorithms (token bucket, sliding window) and their concurrency. **The moment there is
more than one server, the hard question is: where does the counter live?** — and that question is
this whole document.

---

## Why the Single-Node Answer Breaks

A rate limiter is trivial on one machine: a counter in a hash map. Behind a load balancer it falls
apart immediately:

```
limit = 100 req/min for user X
  server A sees 60 of X's requests  → counter 60, allows all
  server B sees 60 of X's requests  → counter 60, allows all
  actual: 120 allowed → 20% over the limit, silently
```

**N servers → effectively N× the limit**, and it's invisible until someone abuses it. Everything
here exists to make N servers enforce **one** shared limit.

---

## Functional Requirements

1. **Enforce a limit** — "≤ N requests per window" per key, across the whole fleet.
2. **Flexible keys** — per user, per API key, per IP, per endpoint, or composite (user + endpoint).
3. **Multiple rules** — different limits for different tiers/routes, evaluated together.
4. **Standard response** — reject over-limit with `429 Too Many Requests` and a `Retry-After` /
   `X-RateLimit-*` headers so clients can back off.
5. **Runtime configuration** — change limits without redeploying.
6. **Multiple algorithms** — token bucket (bursty), sliding window (smooth) selectable per rule.

## Non-Functional Requirements

| Requirement | Target | Why it's hard |
|-------------|--------|---------------|
| **Latency overhead** | **< 1–2 ms p99** added to every request | it's on the hot path of *every* call |
| **Throughput** | 1M+ checks/sec fleet-wide | the limiter must outscale what it protects |
| **Accuracy** | close to the limit, tunable | perfect accuracy costs latency; name the trade |
| **Availability** | must **fail open or closed by policy** | the limiter must not take down the service it guards |
| **Consistency** | eventual is usually fine | strict counting is expensive; most limits tolerate small overage |

---

## The Three Requirements That Shape Everything

**1. It's on the hot path of every request.** A rate-limit check runs before your actual work, on
100% of traffic. So its **added latency and its availability are the service's latency and
availability.** A limiter that adds 20 ms or that goes down and blocks all traffic is worse than no
limiter. This single fact rules out any design that does a slow or unreliable lookup per request.

**2. "Accuracy" is a dial, not a constant.** Enforcing *exactly* N needs a single source of truth
consulted synchronously — slow. Allowing N ± a few percent lets you cache, batch, and approximate —
fast. **The right answer is almost always "approximately N, cheaply," and the interview is about
knowing which way to turn that dial for a given use case** (billing vs. abuse-prevention differ).

**3. Failure mode is a product decision, not a technical default.** When the limiter's backing store
is unreachable, do you **fail open** (allow everything — protects availability, risks abuse) or
**fail closed** (reject everything — protects the backend, kills the product)? There's no universal
answer; it must be a *stated, per-rule policy.*

---

## Scale Estimates

- 1M requests/sec fleet-wide ⇒ **1M rate-limit checks/sec** (one per request).
- 10M distinct keys active in a window (users + IPs + API keys).
- Per-key state is tiny (a counter + timestamp, ~50 bytes) ⇒ ~500 MB of hot state — **fits in
  memory**, which is why Redis-class stores are the usual backing.
- Budget: **< 1 ms** of the request's latency for the check.

**The tension in those numbers:** 1M synchronous round trips/sec to a shared store is a lot of load
and a lot of added latency — which is exactly why the design leans on **local decisions plus
approximate global coordination**, not a database call per request.

## Out of Scope

DDoS mitigation at the network edge (that's a different layer — scrubbing centers, SYN cookies),
and per-request *authentication* (the limiter keys off identity but doesn't establish it).

---

## Engineering Blogs & Primary Sources

This design isn't hypothetical — the biggest infrastructure companies have published how they built
exactly this. Reading these turns the tabs here from "textbook" into "here's how it actually went in
production." Each is keyed to the tab it most reinforces.

- **Cloudflare — "How we built rate limiting capable of scaling to millions of domains" (2017).**
  https://blog.cloudflare.com/counting-things-a-lot-of-different-things/
  The canonical sliding-window-counter writeup, with **real accuracy numbers**: on 400M requests
  from 270k sources, only **0.003%** were wrongly allowed or limited (avg 6% rate gap). Also the
  source of the **async-increment + mitigation-flag** trick — increment counters off the request
  path, and once a client is over the limit, cache that decision in server memory so subsequent
  requests are blocked with *no store lookup at all.* → backs the **Design** (sliding counter) and
  **Scaling** (local fast path) tabs.

- **GitHub — "How we scaled the GitHub API with a sharded, replicated rate limiter in Redis"
  (2021).** https://github.blog/engineering/infrastructure/how-we-scaled-github-api-sharded-replicated-rate-limiter-redis/
  Memcached → Redis migration with **client-side sharding, one primary + read replicas per cluster,
  and Lua scripts for atomicity.** Crucially documents two failures this HLD warns about: a **shared
  cache evicting rate-limit data let users circumvent limits**, and routing to different data centers
  broke counting. The feature-flag'd, percentage-based rollout is the copy→verify→switch pattern. →
  backs the **Deep Dives** (atomicity/sharding) and **Failure Modes** tabs.

- **Stripe — "Scaling your API with rate limiters" (Paul Tarjan, 2017).**
  https://stripe.com/blog/rate-limiters
  Four *complementary* limiter types (request-rate, concurrent-request, fleet-usage, worker-usage),
  and the operational discipline this HLD stresses: **hook limiters in so a bug or a Redis outage
  fails safe, gate them behind feature flags, and watch metrics for how often they trigger.** The
  companion gist has the production Ruby+Redis Lua. → backs the **Trade-offs** (multiple rules) and
  **Failure Modes** (fail-safe) tabs.

- **Brandur Leach — "Rate Limiting, Cells, and GCRA" (2015).** https://brandur.org/rate-limiting
  The clearest explanation of **GCRA** (the generic cell rate algorithm — token bucket without a
  background refill process), and the basis of the `redis-cell` module. → deepens the **Design**
  (algorithms) tab.

- **ClassDojo — "Better Rate Limiting With Redis Sorted Sets" (2015).**
  https://engineering.classdojo.com/blog/2015/02/06/rolling-rate-limiter/
  The sliding-window-**log** implemented with Redis sorted sets (`ZADD`/`ZREMRANGEBYSCORE`/`ZCARD`) —
  the exact tradeoff this HLD flags (exact but O(requests) memory). → the **Design** tab's sliding-log
  entry.

- **envoyproxy/ratelimit** (reference implementation). https://github.com/envoyproxy/ratelimit
  A production gRPC rate-limit *service* with a descriptor hierarchy and two-Redis topology — what the
  **gateway/sidecar placement** trade-off in this HLD looks like as real, deployable code. → backs the
  **Trade-offs** (placement) tab.

**The through-line across all of them:** *local decisions to stay fast, a shared store for truth,
atomic ops (Lua) to avoid the overcount race, TTLs so counters self-heal, and a deliberate fail-safe
posture.* Every one of these companies converged on the same shape — which is the strongest evidence
that the shape is right.
