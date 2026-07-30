# Distributed Rate Limiter — Failure Modes

The limiter sits on the hot path of **every** request, so its failures are the *service's* failures.
The cardinal rule: **the rate limiter must never be a bigger outage than the thing it protects.**

---

## 1. The Shared Store Goes Down — *the defining failure*

Redis (or the shared store) is unreachable. Every rate-limit check now fails, on 100% of traffic.

**This is where fail-open vs. fail-closed becomes real** (see Deep Dives):

| Response | Consequence |
|----------|-------------|
| Naively block on the store | **every request hangs** → total outage, caused by the limiter |
| Fail open | traffic flows; abuse/overload unprotected for the outage |
| Fail closed | backend protected; **you 429 all legitimate users** |
| **Local fallback limiter** | each server enforces `limit/serverCount` locally — degrades, doesn't fail |

**The mandatory mitigation: a short timeout on the store call + a local fallback.** Never let a
request *wait* on the limiter's backing store. If the check doesn't answer in a couple of
milliseconds, apply the fallback policy and move on. **A slow limiter is an outage; a fast wrong
answer is survivable.**

---

## 2. The Limiter Becomes the Bottleneck

The thing meant to protect the system overloads it:

- **Hot key** melts one shard (all of a whale tenant's traffic on one node) → local pre-filter,
  dedicated shard, sub-key sharding.
- **Shared store saturated** by 1M ops/sec → this is why the **local fast path** exists; if you're
  sending one op per request you've already lost.
- **Thundering herd on recovery:** the store comes back and every server slams it at once → jittered
  reconnect, gradual ramp.

---

## 3. Silent Over-Limiting

The subtle failure: the limiter *works*, but lets through more than it should, and nobody notices.

| Cause | Effect | Detection |
|-------|--------|-----------|
| Local-only counters | N× the limit | compare allowed-total vs. limit × keys |
| Sync lag in hybrid mode | bounded overage during the lag window | monitor actual vs. configured rate |
| Clock skew | windows misalign, counts drift | alert on cross-server time delta |
| A shard silently down, failing open | that key range unlimited | per-shard health + allow-rate anomaly |

**Alert on the *output*, not just the store's health:** track the **actual allowed rate per key
class** against the configured limit. A limiter that's quietly allowing 3× shows up here and nowhere
else — the golden signals (store latency, error rate) stay green.

---

## 4. Silent Over-Rejection (the mirror)

Worse for users: the limiter rejects traffic that's under the limit.

- **Fail-closed during a store blip** → legitimate users get 429s.
- **A stuck/leaked counter** that never resets (missing `EXPIRE`, a bug in the Lua script) →
  permanent false rejection for that key.
- **Misconfigured rule** pushed to the config service → instant fleet-wide over-rejection.

**Mitigations:** TTLs on *every* counter key (a leaked counter must self-heal), canary new rules to a
slice of traffic before fleet-wide rollout, and alert on **429 rate spikes** — a sudden jump usually
means misconfig or a store problem, not real abuse.

---

## 5. Retry Storms From Rejected Clients

Rejected clients that retry immediately turn one rejection into a hammering loop — the limiter
*causes* the load spike it's supposed to prevent.

**Mitigations:** always send `Retry-After`; document exponential backoff for clients; and consider
**returning 429 fast and cheap** (from the local fast path) so that even a retry storm is absorbed in-
process without touching the shared store.

---

## Degradation Ladder

```
1. Normal            → local fast path + shared store for the boundary
2. Store slow        → timeout fast, use last-known local share
3. Store down        → local fallback limiter (limit / serverCount) per rule policy
4. Shard down        → fail that key range per policy; other keys unaffected
5. Config service down → keep running the last-known-good rules (fail static)
6. Overload          → shed at the edge; 429 from the local path, never block on the store
```

**Step 4 is the blast-radius control:** one shard failing must limit *its* key range only, never
break rate limiting for every other key.

---

## What to Alert On

- **Actual allowed rate vs. configured limit**, per key class — the only real detector of silent
  over-limiting.
- **429 rate** and its rate of change — spikes mean misconfig or a store issue more often than real
  abuse.
- **Shared-store latency p99** — because it's added directly to every request.
- **Fallback-mode activation** — how often servers are running on local fallback (i.e. flying blind).
- **Per-shard load skew** — early warning of a hot key.
