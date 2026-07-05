# LLM Model Gateway / Router — Problem & Requirements

**Prompt:** Design an LLM gateway — the component every application calls instead of
hitting a model provider directly. It routes each request to the right model/provider,
handles retries and fallback when a provider fails, enforces token-based rate limits,
and caches responses. Design the object model.

This is the AI-native LLD that pairs with the Rate Limiter: it's another **Strategy-
pattern** showcase (interchangeable providers/routing policies), it reuses token-based
rate limiting, and it's the concrete component behind "I'll call an LLM" in every AI
HLD. It embodies the traditional→AI shift — it's an API gateway, but the resource is
tokens and the backends are probabilistic models.

---

## Clarifying Questions

- **Which providers/models?** One provider's models, or multiple providers plus
  self-hosted? → *Multiple, behind a uniform interface — that's the point.*
- **Routing policy?** Fixed model per request, or smart routing (by cost, latency,
  capability)? → *Support multiple interchangeable routing policies.*
- **Sync or streaming?** → *Both — many LLM calls stream tokens.*
- **What must it own?** → *Routing, fallback/retry, token rate-limiting, caching,
  and usage accounting.*
- **Single-node or distributed?** → *Design the object model single-node; note the
  distributed extension (shared cache/limits), same as the Rate Limiter.*

---

## Functional Requirements

1. **Uniform interface** — callers issue one kind of request; the gateway hides which
   provider/model serves it.
2. **Routing** — select the model/provider per request via a pluggable policy (cost-
   optimized, latency-optimized, capability-based, fixed).
3. **Fallback & retry** — on provider error/timeout/rate-limit, retry and/or fall back
   to an alternate provider so a single provider outage doesn't fail the request.
4. **Token-based rate limiting** — limit by tokens (the real resource), per tenant/key.
5. **Caching** — exact and **semantic** caching to avoid regenerating similar answers.
6. **Usage accounting** — track input/output tokens and cost per request/tenant.

### Out of scope (state it)
- The actual model inference (that's the inference-serving HLD; here providers are
  backends behind an interface).
- Prompt construction / RAG (the caller's job).

---

## Non-Functional Requirements

| Requirement | Target | Why |
|-------------|--------|-----|
| **Extensibility** | Add a provider or routing policy without touching existing code | Open/Closed — the core signal |
| **Resilience** | Survive a provider outage | Fallback across providers |
| **Latency overhead** | Minimal over the raw call | It's on every LLM request |
| **Thread safety** | Correct under concurrency | Many concurrent requests |
| **Observability** | Per-request tokens, cost, provider, latency | Cost/latency are the AI concerns |

---

## Why This Is a Great AI LLD

- **Strategy pattern (twice):** interchangeable **providers** behind one interface, and
  interchangeable **routing policies** behind another. Adding either is adding a class.
- **Chain of Responsibility / Decorator:** the request passes through a pipeline —
  rate-limit → cache → route → call → retry/fallback → account — each a composable
  stage.
- **Reuses token rate-limiting** from the Rate Limiter LLD, now as the *resource*
  rather than requests.
- **Semantic caching** is the AI-native twist on caching: cache on embedding
  similarity, not exact key match.
- **Extends to distributed** cleanly (shared cache + shared token counters), same
  bridge as the Rate Limiter.

The design (`DESIGN.md`) and implementation (`Solution.java`) build all of this; the
notes (`NOTES.md`) cover the patterns, concurrency, and the semantic-cache and
distributed extensions.
