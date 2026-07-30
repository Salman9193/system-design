# Distributed Rate Limiter — Scaling

The limiter must **outscale the service it protects** — it sees every request the service sees, plus
it can't add meaningful latency doing so. That constraint shapes everything.

---

## Scaling Profile

| Property | Consequence |
|----------|-------------|
| One check per request, on the hot path | must be **faster to scale than the app tier** |
| Per-key state is tiny (~50 bytes) | it's a **throughput** problem, not a storage one |
| Keys are independent | shards **horizontally, cleanly**, by key |
| Coordination is the cost | the whole game is **minimizing cross-server agreement** |

**The bottleneck is coordination bandwidth, not compute or storage.** Two counters and a timestamp
are nothing to compute or store; the expense is making N servers agree on them. So scaling is mostly
about *doing less coordination.*

---

## Scaling the Shared Store

- **Shard by key** — a key's whole counter lives on one shard, so single-key ops stay atomic with no
  cross-shard talk. Load spreads by key distribution.
- **Replicate each shard** for availability; reads can go to replicas, but **counter writes go to the
  primary** (the count is the truth and must be linearizable within its shard).
- **Consistent hashing** for the key→shard map so adding shards moves minimal keys — same technique
  as any [sharded store](#fu-database-scaling).

**The ceiling on a single key is one shard's throughput.** A key hotter than one node can handle
*cannot* be scaled by adding shards (you can't split one counter across nodes atomically); it must be
handled by the **local fast path** absorbing most of its traffic before the shard sees it.

---

## The Local Fast Path Is the Real Scaler

The single most important scaling decision: **most requests must be decided locally, without touching
the shared store.**

```
1M req/sec, naive:   1M shared-store ops/sec   → the store is the bottleneck
1M req/sec, hybrid:  ~50k ops/sec              → 95% decided locally (obvious allow/reject),
                                                  only near-threshold requests coordinate
```

Cut shared-store traffic by an order of magnitude and the store stops being the limit. **This is why
local + periodic sync beats global-per-request at scale** — not for latency alone, but because it
*removes load from the one shared component.*

---

## Multi-Region

The hard version. A global limit across regions forces a choice:

| Approach | Accuracy | Latency | Notes |
|----------|----------|---------|-------|
| **Per-region limits** (split the global limit) | approximate | **low** (all local) | simplest; a region under its share while another is over |
| **One global store** (one region owns the counters) | exact | **high** for remote regions (cross-region RTT on the hot path) | usually unacceptable |
| **Async cross-region reconciliation** | eventually accurate | low | regions sync counts every N ms; bounded drift |

**Chosen default: per-region limits with async reconciliation.** Cross-region round trips on the hot
path are a non-starter (100+ ms), so each region enforces locally and regions reconcile in the
background. **Accept that a global "1000/min" might briefly allow somewhat more when traffic is
lopsided across regions** — that's the price of not putting an ocean on the hot path, and it's almost
always the right price.

> This is the CAP trade in miniature: a *globally consistent* count needs cross-region coordination
> (slow); *available and fast* means each region decides locally and reconciles later (approximate).
> See [CAP & PACELC](#fu-data-distribution).

---

## Cost Notes

- The limiter is **cheap per check** but runs on **every** request, so its cost scales 1:1 with total
  traffic — the local fast path is a **cost** optimization as much as a latency one (fewer shared-
  store ops = smaller Redis fleet).
- **Over-provisioning the shared store is tempting and wasteful** — the right fix for store load is
  almost always *more local decisions*, not more Redis nodes.
