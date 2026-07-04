# Rate Limiter — Object Design

The design is driven by one requirement above all: **support multiple
interchangeable algorithms, and allow adding a new one without touching existing
code.** That requirement points directly at the **Strategy pattern**, and the whole
class structure follows from it.

---

## The Class Structure

```
                 ┌──────────────────────────┐
                 │   <<interface>>          │
                 │   RateLimitStrategy      │
                 │──────────────────────────│
                 │ + allow(key): boolean    │
                 └──────────────────────────┘
                            ▲
        ┌───────────────────┼───────────────────┐
        │                   │                   │
┌───────────────┐  ┌────────────────┐  ┌──────────────────────┐
│ TokenBucket   │  │ FixedWindow    │  │ SlidingWindowCounter │
│ Strategy      │  │ Strategy       │  │ Strategy             │
└───────────────┘  └────────────────┘  └──────────────────────┘

┌──────────────────────┐         ┌───────────────────────────┐
│ RateLimiter          │────────▶│ RateLimitStrategy         │
│──────────────────────│  uses   │ (depends on abstraction)  │
│ - strategy           │         └───────────────────────────┘
│ + allow(key): boolean│
└──────────────────────┘

┌──────────────────────────┐
│ RateLimiterFactory       │  creates the right strategy from config
│ + create(config): ...    │
└──────────────────────────┘
```

---

## The Pieces (and why each exists)

### `RateLimitStrategy` (interface)
The abstraction every algorithm implements:
```java
interface RateLimitStrategy {
    boolean allow(String key);
}
```
This single method is the **Interface Segregation** principle in action — clients
depend on exactly one method, nothing more.

### Concrete strategies
`TokenBucketStrategy`, `FixedWindowStrategy`, `SlidingWindowCounterStrategy` — each
implements `allow(key)` with its own algorithm and its own per-key state. They're
**Liskov-substitutable**: anywhere a `RateLimitStrategy` is expected, any concrete
strategy works.

### `RateLimiter`
The context that holds a strategy and delegates to it:
```java
class RateLimiter {
    private final RateLimitStrategy strategy;   // depends on the ABSTRACTION
    RateLimiter(RateLimitStrategy strategy) { this.strategy = strategy; }
    boolean allow(String key) { return strategy.allow(key); }
}
```
This is **Dependency Inversion** — `RateLimiter` depends on the interface, not on
any concrete algorithm. Swap the strategy at construction and behavior changes with
zero edits to `RateLimiter`.

### `RateLimiterFactory`
Encapsulates the "which strategy from this config" decision (the **Factory**
pattern), so callers don't hard-code concrete classes:
```java
RateLimitStrategy s = RateLimiterFactory.create(config);
```

---

## How the Design Honors SOLID

| Principle | How this design satisfies it |
|-----------|------------------------------|
| **Single Responsibility** | Each strategy does one algorithm; `RateLimiter` just delegates; the factory just constructs |
| **Open/Closed** | Add `LeakyBucketStrategy` = add one class implementing the interface. **No existing class changes.** |
| **Liskov Substitution** | Any strategy is usable wherever `RateLimitStrategy` is expected |
| **Interface Segregation** | The interface has exactly one method — `allow(key)` |
| **Dependency Inversion** | `RateLimiter` and callers depend on `RateLimitStrategy`, not concretions |

The **Open/Closed** win is the one to demonstrate out loud: *"To add a leaky-bucket
algorithm, I write one new class implementing `RateLimitStrategy` and register it in
the factory. I don't modify `RateLimiter`, the interface, or any existing
strategy — the design is open to extension, closed to modification."*

---

## Per-Key State

Each strategy maintains state **per key** (per user/IP/API-key), so it needs a
concurrent map from key to that key's bucket/counter:
```java
ConcurrentHashMap<String, Bucket> buckets;
```
`computeIfAbsent` lazily creates a bucket on first sight of a key. This raises two
real concerns handled in the implementation:
1. **Concurrency** — many threads hit the same key; the per-key update must be
   atomic (synchronize on the bucket, or use atomic operations).
2. **Memory** — the map grows with distinct keys; stale keys must be evictable
   (TTL / periodic cleanup) so it doesn't grow unbounded.

---

## The Distributed Extension (HLD follow-up)

Single-node state doesn't work when requests for one key hit different servers
behind a load balancer — each server sees only part of the traffic, so the limit is
effectively multiplied by the server count.

**Fix:** move the per-key state to a **shared store (Redis)**. The token-bucket or
counter update becomes an atomic Redis operation (a Lua script for
check-and-decrement atomicity, or `INCR` + `EXPIRE` for fixed window).

**The trade-off to name:** every `allow` call now makes a network round trip to
Redis (~0.5ms same-DC), adding latency and making Redis a critical dependency. To
soften it: a local in-process limiter as a first pass (approximate) backed by Redis
for the authoritative count, or accept slight over-limiting by syncing counts
periodically rather than per request.

This is the clean bridge from LLD to HLD: the Strategy interface stays identical;
only the state store changes from an in-memory map to Redis.
