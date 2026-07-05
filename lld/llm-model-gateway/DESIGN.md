# LLM Model Gateway / Router — Object Design

The design is driven by two requirements: **interchangeable providers** behind one
interface, and **interchangeable routing policies** — both textbook Strategy — plus a
**pipeline** of stages (rate-limit → cache → route → call → fallback → account) that
each request flows through. The class structure follows directly.

---

## The Class Structure

```
   <<interface>> ModelProvider              <<interface>> RoutingStrategy
   ────────────────────────────             ─────────────────────────────
   + complete(req): Response                + select(req, providers): ModelProvider
   + name(): String
        ▲                                            ▲
   ┌────┴─────┬──────────────┐          ┌────────────┼───────────────┐
OpenAIProvider AnthropicProvider   CostOptimized  LatencyOptimized  FixedModel
SelfHostedProvider                 Routing        Routing           Routing

   ┌──────────────────────────────────────────────────────────┐
   │ ModelGateway  (the context / pipeline)                    │
   │  - providers: List<ModelProvider>                         │
   │  - router: RoutingStrategy                                │
   │  - rateLimiter: TokenRateLimiter                          │
   │  - cache: ResponseCache                                   │
   │  - accountant: UsageAccountant                            │
   │  + complete(req): Response                                │
   └──────────────────────────────────────────────────────────┘
        uses ▼            uses ▼             uses ▼
   TokenRateLimiter   ResponseCache      UsageAccountant
                      (exact | semantic)
```

---

## The Pieces (and why each exists)

### `ModelProvider` (interface) — Strategy #1
Every backend implements the same contract:
```java
interface ModelProvider {
    Response complete(Request req);
    String name();
}
```
Concrete providers (`OpenAIProvider`, `AnthropicProvider`, `SelfHostedProvider`) wrap
each backend. **Liskov-substitutable** — the gateway treats them uniformly, which is
what enables fallback (any provider can substitute for another).

### `RoutingStrategy` (interface) — Strategy #2
Picks the provider for a request:
```java
interface RoutingStrategy {
    ModelProvider select(Request req, List<ModelProvider> providers);
}
```
`CostOptimizedRouting` (cheapest capable model), `LatencyOptimizedRouting` (fastest),
`FixedModelRouting` (caller-specified). Adding a routing policy = adding a class,
touching nothing else (**Open/Closed**).

### `ModelGateway` (the context / pipeline)
Holds the collaborators and runs each request through the stages. Depends on the
**interfaces** (`RoutingStrategy`, `ModelProvider`, `TokenRateLimiter`,
`ResponseCache`), not concretions — **Dependency Inversion**. This is where fallback
and retry live.

### `TokenRateLimiter`
Limits by **tokens**, not requests — reuses the Rate Limiter LLD's token-bucket, but
the cost of a call is its token count, not 1. `allow(key, estimatedTokens)`.

### `ResponseCache` (interface)
```java
interface ResponseCache {
    Response get(Request req);      // null on miss
    void put(Request req, Response resp);
}
```
`ExactCache` (hash of the prompt) and `SemanticCache` (embedding similarity) both
implement it — Strategy again, and the AI-native part (see NOTES).

### `UsageAccountant`
Records input/output tokens, cost, provider, and latency per request/tenant — the
observability the AI concerns (cost, latency) demand.

---

## The Request Pipeline (how `complete()` flows)

```
complete(req):
  1. rateLimiter.allow(req.tenant, estimateTokens(req))   → reject if over limit
  2. cached = cache.get(req)                              → return cached on hit
  3. provider = router.select(req, providers)             → choose backend
  4. try:
        resp = callWithRetry(provider, req)               → retries on transient error
     catch provider failure:
        resp = fallback(req, providers, tried)            → try next provider
  5. cache.put(req, resp)
  6. accountant.record(req, resp, provider)
  7. return resp
```

Each stage is independent and composable — close to a **Chain of Responsibility**:
you can add a stage (e.g. a guardrail/PII filter) without disturbing the others.

---

## How the Design Honors SOLID

| Principle | How |
|-----------|-----|
| **Single Responsibility** | Provider calls one backend; router only selects; cache only caches; accountant only records |
| **Open/Closed** | Add a provider, routing policy, or cache type = add a class; the gateway is unchanged |
| **Liskov Substitution** | Any provider substitutes for any other — this is what makes fallback work |
| **Interface Segregation** | Small focused interfaces (`complete`, `select`, `get/put`) |
| **Dependency Inversion** | Gateway depends on interfaces, so providers/policies/caches are injected and swappable |

The **Liskov** point is especially load-bearing here: fallback only works *because*
every provider is a drop-in substitute for every other behind `ModelProvider`. That's
the Strategy pattern paying off as resilience.

---

## The Distributed Extension (LLD → HLD bridge)

Single-node cache and token counters don't work across many gateway instances behind a
load balancer (each sees only its slice → limits multiplied, cache not shared). The
fix mirrors the Rate Limiter's:
- **Shared cache** (Redis / vector store for the semantic cache) so all instances share
  hits.
- **Shared token counters** (Redis, atomic ops) so limits are global.
- **The interfaces don't change** — `ResponseCache` and `TokenRateLimiter` get
  Redis-backed implementations; the gateway is untouched. Clean design paying off
  again.
