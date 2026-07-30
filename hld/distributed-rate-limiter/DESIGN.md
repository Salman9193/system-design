# Distributed Rate Limiter — Design

## The Core Tension

Every design here is a point on one axis:

```
   LOCAL ONLY                     HYBRID                      GLOBAL ONLY
   (per-server counters)     (local + shared truth)     (shared store per request)
   ─────────────────────────────────────────────────────────────────────────►
   fast, inaccurate          fast AND accurate           accurate, slow
   N× overcount              small bounded error         a round trip per request
```

The naive ends are both wrong: **local-only** overcounts by the fleet size; **global-only** puts a
network round trip on every request. **The production answer is the middle** — and the design is
mostly about *how* you split work between a local fast path and a shared source of truth.

---

## Architecture

```
        request
           │
   ┌───────▼────────┐   rate-limit check happens HERE, before real work
   │  App server /  │   ┌──────────────────────────┐
   │  API gateway   │──►│ local limiter (in-proc)  │  fast path: obvious allow/reject
   │                │   │  • token bucket per key  │  with a locally-held share of the limit
   │                │   └───────────┬──────────────┘
   └────────────────┘               │ only when near the threshold, or to sync
                                     ▼
                         ┌───────────────────────────┐
                         │  SHARED STORE (Redis-class)│  the source of truth for counts
                         │  • sharded by key          │  atomic INCR / Lua scripts
                         │  • replicated per shard    │
                         └───────────┬────────────────┘
                                     │ watches
                         ┌───────────▼────────────┐
                         │  Config service         │  limits & rules, hot-reloaded
                         └─────────────────────────┘
```

**Where the check runs matters.** Putting it at the **API gateway** (or a sidecar) centralizes it,
keeps app code clean, and is the common choice; putting it **in-process** shaves the last hop. Either
way the *logic* below is identical.

---

## The Algorithms, Distributed

The [LLD](#lld-rate-limiter) derives these on one node. Here's what each costs once the state is
shared:

### Fixed Window — trivial, slightly wrong
```
key = "user:42:minute:2091"        // window baked into the key
INCR key ; EXPIRE key 60           // atomic in Redis
allow if result <= limit
```
One atomic op, auto-expiring. **The flaw** (from the LLD): a burst straddling the window boundary
lets through **2× the limit** in a short span — `limit` at 11:59:59 and `limit` again at 12:00:00.
Cheap and usually good enough; know the boundary hole.

### Sliding Window Log — exact, expensive
Store every request timestamp in a sorted set; count entries in `(now − window, now]`.
```
ZADD key now now ; ZREMRANGEBYSCORE key 0 (now-window) ; ZCARD key
```
**Exact**, but **O(requests) memory per key** — a hot key with 10k req/window stores 10k timestamps.
Use only when precision is worth the memory.

### Sliding Window Counter — the usual winner
Keep two fixed-window counts (current + previous) and weight the previous by how far into the
current window you are:
```
estimate = prev_count × (1 − elapsed_fraction) + cur_count
```
**O(1) memory per key**, no boundary doubling, and the error is tiny on real traffic — **Cloudflare
reported under 1% error** deploying exactly this. This is the sensible default for a distributed
limiter.

### Token Bucket — for controlled bursts
A bucket refills at a steady rate up to a cap; each request takes a token. Distributed via a **Lua
script** so refill-check-decrement is atomic:
```
tokens = min(cap, tokens + elapsed × rate)
if tokens >= 1 { tokens -= 1 ; allow } else { reject }
```
Best when you want to **permit short bursts** (cap) while bounding the sustained rate — API quotas
that shouldn't punish a quick flurry.

> **Atomicity is the whole game.** Read-modify-write across the fleet must be atomic or two servers
> race and both allow. Redis gives this via single commands (`INCR`) or **Lua scripts that run
> atomically** on one shard — which is also *why the key must live on one shard* (below).

---

## Sharding the Shared Store

At 1M checks/sec one Redis node isn't enough. **Shard by the rate-limit key** (`user:42`,
`apikey:abc`) so that:

- All of one key's traffic lands on **one shard** ⇒ its counter is atomic there (no cross-shard
  coordination for a single limit).
- Load spreads across shards by the natural key distribution.

**The consequence to state:** a single key is capped by a **single shard's** throughput. A
globally hot key (one enormous tenant) can hotspot one shard — the same one-key-hotspot problem as
[database sharding](#fu-database-scaling), and it needs the same answers (local pre-filtering,
dedicated handling).

---

## The Local Fast Path (how you hit < 1 ms)

Consulting the shared store on *every* request is the naive global design and it's too slow at scale.
The standard optimization: **give each server a local share and only coordinate when it matters.**

Two workable schemes:

1. **Local token bucket + async sync.** Each server holds a local bucket; periodically (every 100 ms)
   it reconciles with the shared store. Most requests are decided **locally, in nanoseconds**; the
   shared store bounds drift. Accepts small overage for big latency wins.
2. **Approximate-then-confirm.** Decide locally when the answer is obvious (well under or well over);
   only consult the shared store when **near the threshold**, where accuracy actually matters. Cuts
   shared-store traffic by the fraction of requests that are nowhere near the limit — usually most of
   them.

> **The principle:** don't pay for global coordination on requests whose outcome is obvious. Spend
> the round trip only near the boundary, where the fleet's views could actually disagree on
> allow/reject.

---

## Configuration & Response

- **Rules** (`limit`, `window`, `algorithm`, `key template`, `fail-mode`) live in a **config
  service**; servers watch for changes and hot-reload. Never redeploy to change a limit.
- **Rejection** returns `429` with `Retry-After` and `X-RateLimit-Limit / -Remaining / -Reset`
  headers so well-behaved clients self-throttle instead of hammering.
