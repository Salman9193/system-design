# Rate Limiter — Notes, Patterns & Extensions

The wrap-up: which patterns the design uses and why, the concurrency reasoning, and
how the problem extends. This is the material that lets you defend the design under
follow-up pressure.

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `RateLimitStrategy` + concrete algorithms | Multiple interchangeable algorithms behind one interface — the core of the design |
| **Factory** | `RateLimiterFactory` | Encapsulates "which strategy from this config" so callers don't hard-code concretions |
| **Dependency Injection** | `RateLimiter(strategy)` | The context receives its strategy, depends on the abstraction |

**Why Strategy is the right call here:** the requirement is literally "support
multiple algorithms interchangeably." That's the textbook trigger for Strategy —
each algorithm is a family member behind a common interface, selected at runtime.
Recognizing this mapping from requirement to pattern is the signal.

**Why NOT other patterns:** you don't need State (behavior doesn't depend on an
evolving internal state machine), Observer (no event fan-out), or Singleton (a
limiter isn't inherently single-instance). Naming a pattern you *rejected* and why
is a strong staff signal — it shows you're choosing patterns deliberately, not
reflexively.

---

## The Open/Closed Demonstration

The interviewer will almost certainly ask: **"How would you add another
algorithm?"** The answer must be:

> "I write one new class implementing `RateLimitStrategy` and add a case to the
> factory. I don't modify `RateLimiter`, the interface, or any existing strategy.
> The design is open to extension, closed to modification."

If your design required editing a big `switch` inside `RateLimiter` to add an
algorithm, that's the *anti-pattern* Open/Closed exists to prevent. The Strategy
pattern moves that switch to construction time (the factory) and out of the hot
path.

---

## Concurrency — The Part That Separates Levels

The naive mistake is a single lock around the whole limiter, which serializes all
requests across all keys. The design instead:

1. Uses `ConcurrentHashMap.computeIfAbsent` to create per-key state atomically —
   two threads racing on a new key can't create two buckets.
2. Synchronizes on **the individual key's bucket/counter object**, not a global
   lock — so requests for *different* keys never contend. Only concurrent requests
   for the *same* key serialize, and only for the brief update.

**The scaling follow-up:** for very hot keys, even per-key synchronization can
contend. Replace the `synchronized` block with atomic operations (CAS on the token
count) or lock-free structures. State this as the next step if pushed on throughput.

---

## Memory — Bounding Per-Key State

Every distinct key creates an entry in the map. Left unchecked, this grows
unbounded (an attacker sending one request from a million IPs creates a million
buckets).

**Fix:** evict stale keys. Either:
- A TTL per entry — remove buckets untouched for longer than the window (× a
  factor).
- A periodic sweep that removes cold entries.
- A bounded LRU cache of buckets (accepting that evicting an active key resets its
  count — usually fine).

Naming this unprompted shows you're thinking about the adversarial/operational
reality, not just the happy path.

---

## Algorithm Trade-offs (defend your default)

| Algorithm | Memory | Accuracy | Bursts | Use when |
|-----------|--------|----------|--------|----------|
| Token Bucket | O(1) | Good | Allows controlled bursts | **Default** — API gateways, general use |
| Leaky Bucket | O(1)+queue | Smooths output | Smooths, no bursts | You need a constant output rate |
| Fixed Window | O(1) | Poor (boundary burst) | 2× at boundary | Simplest, tolerant of imprecision |
| Sliding Window Log | O(N) per key | Exact | None | Exactness matters, low volume |
| Sliding Window Counter | O(1) | Near-exact | Controlled | **Best O(1) accuracy** — the elegant fix |

**Default to Token Bucket** and be able to say why: O(1) memory, allows controlled
bursts (which is usually desirable — brief spikes are legitimate), and it's what
most real API gateways use. If asked about the Fixed Window boundary flaw, pivot to
Sliding Window Counter as the O(1) fix.

---

## The Distributed Extension (LLD → HLD bridge)

Single-node state breaks behind a load balancer: each server sees only its slice of
a key's traffic, so N servers → effectively N× the limit.

**Fix:** shared state in Redis. The per-key update becomes an atomic Redis
operation:
- **Fixed window:** `INCR key` + `EXPIRE key windowSeconds` — atomic, trivial.
- **Token bucket / sliding window:** a Lua script for atomic
  check-refill-and-decrement (Redis executes the script atomically).

**Trade-offs to name:**
- Every `allow` now costs a ~0.5ms same-DC round trip → adds latency and makes Redis
  a critical dependency on the hot path.
- Redis becomes a single point of failure / bottleneck → needs replication and can
  itself be sharded by key.
- **Softening options:** a local approximate limiter as a first pass (reject
  obvious over-limit locally, only consult Redis near the threshold); or sync counts
  periodically and accept slight over-limiting for lower latency.

The beauty: **the `RateLimitStrategy` interface is unchanged.** Only the state
backing changes from an in-memory map to Redis. That's clean design paying off.

---

## Extensions

| Variant | Change | Approach |
|---------|--------|---------|
| Per-tier limits (free vs paid) | Different limits per user class | Factory selects config by tier; strategy unchanged |
| Multiple limits at once (per-sec AND per-day) | Compose limiters | Chain strategies; allow only if all pass |
| Distributed | Shared state | Redis-backed strategies (above) |
| Dynamic config | Change limits at runtime | Hot-reload config; factory rebuilds strategy |
| Cost-based (not 1 token/request) | Weighted requests | `allow(key, cost)` — consume `cost` tokens |
| Graceful response | Tell client when to retry | Return retry-after alongside the reject |

---

## Interview Checklist

1. **Clarify:** what's the key (user/IP/API-key), the limit, single vs distributed.
2. **Name the pattern:** Strategy, because multiple interchangeable algorithms.
3. **Design to the interface:** `allow(key)`, concrete strategies, factory.
4. **Demonstrate Open/Closed:** "add an algorithm = add a class."
5. **Address concurrency:** per-key locking, not global; atomics for hot keys.
6. **Bound memory:** evict stale keys.
7. **Defend the default:** Token Bucket, and why.
8. **Extend to distributed:** Redis-backed, same interface, name the latency cost.
