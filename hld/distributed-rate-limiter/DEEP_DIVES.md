# Distributed Rate Limiter — Deep Dives

## 1. The Race That Overcounts — and Why Atomicity Fixes It

The bug that a naive distributed limiter always has:

```
limit = 100, current count = 99
  server A: READ 99 ──┐
  server B: READ 99 ──┤ both read before either writes
  server A: 99 < 100 → allow, WRITE 100
  server B: 99 < 100 → allow, WRITE 100
  → count is 100, but 101 requests were allowed
```

A read-modify-write split across the network races. **The fix is to make check-and-increment a
single atomic operation on whichever node owns the key:**

- **Fixed window:** `INCR` returns the post-increment value atomically — compare it to the limit in
  one step, no separate read.
- **Token bucket / sliding window:** a **Lua script**, which Redis runs atomically on one shard —
  the whole refill-check-decrement happens with no interleaving.

**This is why a key must live on a single shard.** Atomicity is cheap *within* a node and expensive
*across* nodes; keeping each key's state in one place is what lets a single atomic op settle the
decision.

---

## 2. Latency vs. Accuracy vs. Cost — the Trade Triangle

You cannot maximize all three. Every real design picks a corner and softens the other two:

```
        ACCURACY (enforce exactly N)
              /\
             /  \
            /    \
     LATENCY ──── COST
  (per-request      (load on the
   overhead)         shared store)
```

| Design | Accuracy | Latency | Store cost |
|--------|----------|---------|-----------|
| Global sync every request | **exact** | high (round trip each) | **high** (1 op/req) |
| Local + periodic sync | approximate | **lowest** (local) | **low** (batched) |
| Approximate-then-confirm | good | low (round trip only near limit) | medium |
| Local-only (no coordination) | **poor** (N× over) | lowest | none |

**How the use case picks the corner:**
- **Billing / hard quotas** → lean **accurate**: a customer paying for 1M calls must not silently
  get 1.2M. Pay the latency.
- **Abuse / DoS prevention** → lean **fast & cheap**: stopping *roughly* the right amount of abuse
  instantly beats stopping it exactly, slowly. A few percent overage is harmless.
- **Fairness between tenants** → middle: approximate is fine, but drift must be bounded.

> **The interview move:** don't ask "how do I make it accurate?" Ask "**how accurate does *this*
> limit need to be, and what will I trade for it?**" Naming the trade is the signal.

---

## 3. Fail Open or Fail Closed — a Policy, Not a Default

When the shared store is unreachable, the limiter must still answer every request. Two choices, each
a real risk:

| | **Fail open** (allow) | **Fail closed** (reject) |
|---|----------------------|--------------------------|
| Protects | **availability** — traffic flows | **the backend** — no overload |
| Risks | abuse/overload gets through | **you 429 all legitimate traffic** — self-inflicted outage |
| Good for | user-facing request paths | protecting a fragile downstream (a payment processor) |

**Neither is universally right, and that's the point.** A login endpoint might fail *closed* (better
to reject logins than let a credential-stuffing attack through); a product page might fail *open*
(better to serve pages than to 429 everyone because Redis hiccuped). **Make it a per-rule setting,
and default it consciously.**

**The softening move:** a **local fallback limiter**. If the shared store is down, fall back to a
per-server local limit (say, `globalLimit / serverCount`). It over- or under-counts during the
outage, but it degrades gracefully instead of failing hard either way.

---

## 4. Clock Skew

Sliding-window and token-bucket math uses timestamps. If servers disagree on "now," their windows
misalign and counts drift.

- **Prefer the shared store's clock** — do the time math inside the Redis Lua script using Redis's
  own `TIME`, so all servers reference one clock, not their own.
- Where servers must use local time, **keep them on NTP** and treat any design needing sub-100 ms
  cross-server agreement as suspect. (This is a gentler cousin of the
  [Spanner TrueTime problem](#fu-database-scaling) — exposing and bounding clock uncertainty.)

---

## 5. Multiple Rules at Once

Real systems evaluate several limits per request: *100/min per user* **and** *10/sec per endpoint*
**and** *1000/hour per API key*.

- **Evaluate cheapest-first and short-circuit:** check the local/most-likely-to-reject rule before
  the ones needing a round trip. The first rejection wins; no need to check the rest.
- **Reject with the *most specific* reason** and the *longest* `Retry-After`, so the client backs off
  correctly.
- Watch the cost: 3 rules = up to 3 shared-store ops/request. The **local fast path** matters more,
  not less, as rules multiply.

---

## 6. Hot Keys

One key with enormous traffic (a giant tenant, a viral endpoint) concentrates on its shard.

| Fix | Trade |
|-----|-------|
| **Local pre-filter** | absorb most of the hot key's rejections in-process; only borderline requests reach the shard |
| **Dedicated shard** for known whales | operational special-casing |
| **Sub-key sharding** (`user:42:bucket:{0..k}`) with split limit | approximate; the per-bucket limits sum to the real one |

Same structural problem, and same family of fixes, as a
[hot shard in a sharded database](#hld-sharded-database-platform) — worth calling out that the
pattern recurs.
