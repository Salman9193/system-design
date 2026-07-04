# Rate Limiter — Problem & Requirements

**Prompt:** Design a rate limiter — a component that decides whether a given request
is allowed or should be rejected, based on how many requests a client has made
recently.

This is the canonical LLD problem that's also an HLD problem. As LLD it tests clean
object design (multiple interchangeable algorithms → Strategy pattern), the
Open/Closed principle, and concurrency. As HLD it tests distributed coordination.
This template focuses on the LLD, with notes on the distributed extension.

---

## Clarifying Questions

- **What are we limiting on?** Per user? Per IP? Per API key? → *Assume a generic
  "key" so it works for any of them.*
- **What's the limit?** e.g. 100 requests per minute. → *Make it configurable.*
- **What algorithm?** → *The interviewer usually wants you to support multiple and
  discuss trade-offs — which is the whole point of the Strategy pattern here.*
- **Single machine or distributed?** → *Start single-machine, thread-safe; then
  discuss the distributed extension.*
- **What happens on reject?** → *Return false (HTTP 429 Too Many Requests at the API
  layer). Out of scope: queuing/retry — flag where it plugs in.*

---

## Functional Requirements

1. `allow(key)` returns `true` if the request is within the limit, `false` if it
   should be rejected.
2. Support **multiple algorithms** (token bucket, sliding window, ...) that are
   **interchangeable**.
3. Limits are **configurable** (requests per time window).

### Out of scope (state it)
- Request queuing / delayed retry — the limiter only decides allow/reject.
- Distributed coordination — designed single-node first, extension noted.

---

## Non-Functional Requirements

| Requirement | Target | Why |
|-------------|--------|-----|
| **Latency** | O(1) per `allow` call | It's on every request's hot path |
| **Thread safety** | Correct under concurrent access | Many requests hit the same key concurrently |
| **Memory** | Bounded per key | Can't grow unbounded with request history |
| **Extensibility** | Add an algorithm without touching existing code | Open/Closed — the key design signal |
| **Accuracy** | Depends on algorithm | Trade-off between precision and cost |

---

## The Algorithms (and their trade-offs)

The reason this problem is a Strategy-pattern showcase: there are several standard
algorithms, each with a different accuracy/memory/burst trade-off.

| Algorithm | Idea | Pro | Con |
|-----------|------|-----|-----|
| **Token Bucket** | Tokens refill at a fixed rate; each request consumes one; empty = reject | Allows controlled bursts; O(1); tiny memory | Burst up to bucket size |
| **Leaky Bucket** | Requests flow out at a fixed rate from a queue | Smooth, constant output rate | Queuing adds latency; can drop |
| **Fixed Window** | Count requests per fixed clock window (e.g. per minute) | Trivial; tiny memory | **Boundary burst** — 2× limit across a window edge |
| **Sliding Window Log** | Store timestamps; count those within the last window | Exact | O(N) memory per key — stores every timestamp |
| **Sliding Window Counter** | Weighted blend of current + previous fixed window | Near-exact; O(1) memory | Slight approximation |

**Token Bucket** is the most common production choice (used by many API gateways).
**Sliding Window Counter** is the elegant fix for Fixed Window's boundary-burst
problem at O(1) memory. Being able to explain why Fixed Window is flawed and how
Sliding Window Counter fixes it is a strong signal.

---

## The Fixed-Window Boundary Problem (know this)

Fixed Window counts per clock minute. Limit = 100/min. A client sends 100 requests
at 00:00:59 and another 100 at 00:01:00 — both windows accept, so **200 requests in
2 seconds** slip through, 2× the intended limit. Sliding Window solves this by
considering a rolling window rather than a fixed clock boundary.

---

## Why This Is a Great LLD Problem

- **Strategy pattern** falls out naturally — multiple interchangeable algorithms
  behind one `allow(key)` interface.
- **Open/Closed** is directly testable — "add a new algorithm" should mean "add a
  class," not "edit a switch."
- **Concurrency** is real — the same key is hit by many threads; the counter/bucket
  update must be atomic.
- **Extends to distributed** — the single-node design generalizes to a shared store
  (Redis), which is the HLD follow-up.

The design (`DESIGN.md`) and implementation (`Solution.java`) show all of this.
