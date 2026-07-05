# LLM Model Gateway — Notes, Patterns & Extensions

The wrap-up: the patterns, the concurrency story, and the two AI-native extensions
(semantic caching, distributed state) that an interviewer will probe.

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** (×2) | `ModelProvider`, `RoutingStrategy` | Interchangeable backends and interchangeable routing policies |
| **Factory** | `GatewayFactory` | Assemble a gateway from config without callers naming concretions |
| **Chain of Responsibility** | The `complete()` pipeline | Composable stages: rate-limit → cache → route → call → fallback → account |
| **Dependency Injection** | `ModelGateway` constructor | Gateway depends on injected interfaces, so everything is swappable |

**Two Strategy hierarchies is the headline.** Providers and routing policies vary
independently, so each gets its own interface. Recognizing that these are *two*
separate axes of variation — not one — is the design insight.

**Why the pipeline is Chain-of-Responsibility-like:** each stage does one thing and
passes control on. You can insert a new stage (a PII/guardrail filter, a
prompt-injection check) without touching the others — the same Open/Closed benefit
the Strategy interfaces give, applied to the request flow.

---

## The Open/Closed Demonstration

Two "how would you add..." questions the interviewer will ask:

**"Add a new model provider."**
> "I write a class implementing `ModelProvider` and add it to the provider list. The
> gateway, router, and existing providers don't change — and it's automatically
> available as a fallback target, because fallback just iterates providers behind the
> interface."

**"Add a new routing policy (e.g. route by capability, or by current provider load)."**
> "I write a class implementing `RoutingStrategy` and register it in the factory.
> Nothing else changes."

If adding a provider meant editing a `switch` inside the gateway, that's the
anti-pattern Strategy exists to remove.

---

## Why Liskov Substitution Is Load-Bearing Here

Fallback works **only because** every provider is a true drop-in substitute for every
other behind `ModelProvider`. When the primary fails, the gateway calls the next
provider with the identical `complete(req)` contract and gets back the same `Response`
type. That's Liskov substitutability turned directly into **resilience** — a nice
example of a SOLID principle doing concrete work, not just being cited.

---

## Concurrency

- **Token buckets** are per-key, each guarded by synchronizing on its own bucket
  array — different tenants never contend (same approach as the Rate Limiter LLD).
- **Usage accounting** uses atomics (`AtomicLong`) and a `ConcurrentHashMap`, so
  recording is lock-free and correct under concurrency.
- **Providers** are stateless, so concurrent calls are safe.
- **The cache** must be a concurrent structure (it is — `ConcurrentHashMap`); a
  distributed cache handles concurrency in the shared store.

The scaling follow-up: for a very hot tenant, per-key bucket synchronization can
contend — replace with atomic CAS on the token count, as with the Rate Limiter.

---

## Retry & Fallback (the resilience core)

The `callWithFallback` logic:
1. Build an ordered list: chosen provider first, then the rest as fallbacks.
2. For each provider, retry up to N times on **transient** errors (timeout, 5xx,
   provider rate-limit).
3. On exhausting a provider's retries, move to the next provider.
4. If all fail, surface an error.

**Nuances to name:**
- **Only retry idempotent/transient failures.** A malformed-request (4xx) shouldn't be
  retried or failed-over — it'll fail everywhere. Distinguish transient from permanent.
- **Backoff** between retries (exponential + jitter) to avoid hammering a struggling
  provider.
- **Circuit breaker:** if a provider is failing consistently, stop routing to it for a
  cooldown rather than retrying every request into a known-down backend — protects
  latency and the provider. A production gateway adds this around each provider.
- **Fallback can cross quality tiers**, so record which provider actually served the
  request (the design does — `Response.servedByModel`), since a fallback answer may
  differ in quality/cost.

---

## Semantic Caching (the AI-native extension)

Exact-match caching rarely hits — natural-language prompts are rarely byte-identical.
**Semantic caching** hits far more often by matching on *meaning*:

```
get(req):  embed(req.prompt) → nearest neighbor within req.tenant scope
           → if similarity >= threshold, return that entry's response, else miss
put(req):  embed(req.prompt) → store (vector → response) under tenant scope
```

**The critical cautions (say these):**
- **Threshold tuning:** too loose and you serve a cached answer to a
  meaningfully-different question (a subtle correctness bug); too tight and hit rate
  collapses. Tune against a labeled set.
- **Permission-awareness:** the cache key/scope must include the tenant (and, for RAG,
  the permission scope), so one tenant's cached answer is never served to another. A
  naive semantic cache is a cross-user data leak waiting to happen.
- **Staleness:** if the underlying knowledge changes, cached answers go stale — TTL or
  invalidation applies, same as any cache.

This is exactly the traditional→AI progression: a cache, but keyed on embedding
similarity instead of exact match, with new correctness and privacy pitfalls.

---

## Token-Based Rate Limiting (resource = tokens)

The gateway limits by **tokens**, not requests, because a request can be 100 or
100,000 tokens — requests are a meaningless unit for LLM load. It reuses the token
bucket from the Rate Limiter LLD, but the cost of an `allow` call is the request's
token count, not 1. Two wrinkles:
- **Estimation before the call:** you rate-limit on *estimated* input tokens up front,
  then reconcile with actual (input + output) tokens after, since output length isn't
  known in advance.
- **Two-dimensional limits** are common: tokens-per-minute *and* requests-per-minute,
  since providers limit on both.

---

## The Distributed Extension (LLD → HLD bridge)

Behind a load balancer, many gateway instances each see only part of the traffic, so
single-node cache and counters break (limits multiplied, cache not shared). Fix:
- **Shared cache:** Redis for exact; a shared vector index for semantic.
- **Shared token counters:** Redis with atomic operations (a Lua script for
  check-and-decrement), as in the Rate Limiter's distributed extension.
- **The interfaces don't change** — `ResponseCache` and `TokenRateLimiter` get
  Redis-backed implementations, injected into the same `ModelGateway`. The gateway
  code is untouched.

**Trade-off:** shared state adds a network hop per request (~0.5ms) and makes Redis a
critical dependency — soften with local approximate limits/caches in front of the
authoritative shared store.

---

## Interview Checklist

1. **Two Strategy hierarchies:** providers and routing policies vary independently.
2. **Pipeline** as composable stages (Chain of Responsibility).
3. **Open/Closed:** add a provider or policy = add a class.
4. **Liskov → resilience:** fallback works because providers are substitutable.
5. **Retry/fallback nuances:** transient-only, backoff, circuit breaker.
6. **Semantic cache:** meaning-based hits; tune the threshold; **permission-aware**.
7. **Token-based limiting:** tokens are the resource; estimate then reconcile.
8. **Distributed:** shared cache + counters behind the same interfaces.
