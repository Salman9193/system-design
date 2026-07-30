# Distributed Rate Limiter — Trade-offs

## 1. Where the Limit Lives

| | Local only | **Hybrid (chosen)** | Global only |
|---|-----------|---------------------|-------------|
| Accuracy | N× over | **bounded error** | exact |
| Latency | none | **near-zero** (local fast path) | round trip/request |
| Store load | none | **low** (batched/near-threshold) | high |
| Complexity | trivial | **moderate** | simple |

**Chosen: hybrid.** Local decisions for the obvious cases, shared truth for the boundary. It's the
only option that is both fast enough for the hot path and accurate enough to mean anything. The cost
is the complexity of reconciling two views — which the rest of these trades manage.

---

## 2. Algorithm

| | Fixed window | Sliding log | **Sliding counter** | Token bucket |
|---|-------------|-------------|---------------------|--------------|
| Memory/key | O(1) | **O(requests)** | O(1) | O(1) |
| Accuracy | boundary 2× hole | exact | **~99%** | exact-ish |
| Bursts | allowed at edges | smooth | smooth | **controlled (cap)** |
| Cost | cheapest | expensive | cheap | cheap (Lua) |

**Chosen: sliding-window counter as default; token bucket where bursts should be allowed.** The
counter gives smooth limiting at O(1) memory and ~0.003% error on Cloudflare's published 400M-request
analysis; token bucket is the pick when a short burst up to a cap is desirable (most API quotas).
Fixed window only when its boundary hole is genuinely acceptable and you want the absolute cheapest
path.

---

## 3. Accuracy vs. Latency (the core dial)

**Chosen: approximate by default, exact only where money or safety depends on it.** Most limits exist
to prevent *abuse and overload*, where "about N" is fine and speed matters more. Reserve exact
counting (synchronous global) for **billing quotas and hard safety limits**, and make it a per-rule
choice — not a platform-wide stance.

---

## 4. Fail Open vs. Fail Closed

**Chosen: per-rule policy, with a local-fallback limiter as the graceful middle.** No global default
is correct — a login endpoint and a static page want opposite behavior when the store is down. The
platform's job is to make the choice *explicit and per-rule*, and to offer the local fallback so
"store is down" degrades instead of failing hard.

---

## 5. Placement: Gateway vs. Sidecar vs. In-Process

| | API gateway | Sidecar | In-process library |
|---|------------|---------|--------------------|
| App code | **untouched** | untouched | must integrate |
| Latency | +1 hop | +localhost hop | **none** |
| Consistency | **centralized** | per-pod | per-pod |
| Polyglot | **yes** | yes | one language |

**Chosen: gateway for most, in-process for the latency-critical few.** The gateway keeps rate
limiting out of application code and gives one consistent enforcement point; drop to in-process only
where that extra hop is unaffordable. Same "library vs. service" reasoning as the
[Text Segmentation Service](#hld-text-segmentation-service).

---

## 6. Store: Redis vs. Purpose-Built

| | Redis-class | Custom/CRDT-based |
|---|-------------|-------------------|
| Time to build | **low** | high |
| Atomic ops | INCR, Lua | must build |
| Familiar ops | **yes** | no |
| Extreme scale | shard it | possibly better |

**Chosen: Redis (or a Redis-compatible managed store).** Atomic `INCR` and atomic Lua scripting are
*exactly* the primitives a rate limiter needs, and operational familiarity is worth a lot on a hot-
path dependency. Build something custom only at a scale where Redis sharding genuinely stops working
— which is rare, and which you should be able to say you'd *measure* before committing to.
