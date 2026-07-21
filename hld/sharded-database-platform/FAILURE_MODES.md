# Sharded Database Platform — Failure Modes

Ranked by how badly they end. The top two are **unrecoverable**, which is why the design spends most
of its complexity budget on them.

---

## 1. Split-Brain (two primaries) — *unrecoverable*

**How:** the primary is partitioned, not dead. The orchestrator promotes a replica. Clients that can
still reach the old primary keep writing to it. Both accept conflicting writes to the same rows.

**Why it's catastrophic:** there is no correct merge. Two committed, conflicting histories exist and
you must *choose* which writes to discard.

**Prevention (all three, not one):**
- **Leases, not flags** — a primary that can't renew its lease **demotes itself**.
- **Fencing** — actively disable the old primary before promoting (revoke lease, set read-only,
  block at the network layer).
- **Consensus for the decision** — promotion is decided by a quorum-backed store (etcd), never by a
  single observer that might itself be the one partitioned.

**Detection:** alert on *any* moment where two instances claim the primary lease for one shard. This
should be impossible; alert on it anyway.

---

## 2. Data Loss During Resharding — *unrecoverable*

**How:** cutover happens before the targets have fully caught up. Writes that landed on the source
between the last applied change and the switch are simply gone.

**Prevention:**
- **Verify before switching** (checksum source vs target) — the non-negotiable gate.
- **Freeze writes, then wait for the target to reach the source's final position**, *then* flip.
- **Keep the source read-only, not deleted**, for a recovery window after cutover.
- Cut over **reads first** — a routing bug then shows up as stale reads, not lost writes.

---

## 3. Topology Service Outage — *recoverable if designed for*

**How:** etcd/ZooKeeper goes down or is partitioned.

**Blast radius depends entirely on one design choice:**

| If routers… | Result |
|-------------|--------|
| read topology **per query** | ❌ **total outage** |
| **cache it and serve from cache** | ✅ queries keep flowing; only *changes* are blocked |

**Design for "fail static":** keep serving the last-known-good topology indefinitely. **No failover,
no resharding, no schema change — but the product stays up.** The one nuance: primaries whose leases
expire will demote themselves, so a *long* topology outage does eventually degrade writes. That's the
correct trade (safety over availability for writes).

---

## 4. Scatter-Gather Storm

**How:** someone deploys a query without the shard key. It hits all 1,000 shards. At 100 QPS that's
100,000 backend queries/sec, and it starves everything else.

**Why it's insidious:** the query is *correct*, passes review, and works fine in staging with 4
shards. **The cost scales with shard count, so it degrades as you grow** — the failure arrives months
after the code does.

**Mitigations:** per-query shard-count limits; require an explicit annotation for scatter queries;
separate connection pools so scatter traffic can't starve single-shard traffic; and **alert on
scatter-query ratio**, not just latency.

---

## 5. Hot Shard

**How:** one tenant, one celebrity user, or a time-based shard key concentrates load.

**Mitigations:** cache the hot key; give it a dedicated shard; sub-shard the key. **Splitting doesn't
help if a single key is hot** — that case is unfixable by sharding, and the honest answer is caching
or application-level partitioning.

---

## 6. Replication Lag Cascade

**How:** a bulk operation (backfill, resharding copy, schema change) generates writes faster than
replicas apply them. Replicas lag; replica reads go stale; the app routes more reads to the primary;
the primary saturates; **lag gets worse**.

**Mitigations:** **throttle all bulk operations on observed replica lag** (the single most important
control); circuit-break replica reads past a staleness threshold; run bulk work against dedicated
replicas.

---

## 7. The Sidecar Becomes the Bottleneck

**How:** connection pooling means every query passes through the sidecar. A GC pause, a slow pool, or
exhausted pool capacity stalls a whole shard.

**Mitigations:** pool sizing with headroom; separate pools per workload class (OLTP vs bulk);
timeouts everywhere; and remember that **the sidecar failing looks identical to the database failing**
from the app's perspective — so health checks must distinguish them, or you'll fail over a perfectly
healthy primary.

---

## Degradation Ladder

```
1. Normal                → route by shard key, replica reads, semi-sync
2. Topology unavailable  → serve from cached map; block changes           (fail static)
3. Replica lag high      → route reads to primary; throttle bulk work
4. A shard unavailable   → fail only queries touching it — NOT all queries  ← blast-radius control
5. Primary lost          → fence, promote most-current replica, reparent
6. Overload              → shed scatter queries first, keyed queries last
```

**Step 4 is the one that separates a good design from a bad one.** A single shard being down must
degrade *that key range*, not the entire keyspace. Scatter queries will fail; single-shard queries for
other ranges must keep working.

---

## What to Alert On

- **Two primaries for one shard** — should be impossible; alert loudly.
- **Replication lag** per shard, and *acked* vs *applied* position gaps.
- **Scatter-query ratio** — the leading indicator of future collapse.
- **Per-shard QPS skew** — detects hot shards before they melt.
- **Topology staleness** in routers — how old is each router's cached map?
- **Resharding verification failures** — must block the cutover automatically.
