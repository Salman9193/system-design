# Sharded Database Platform — Scaling

The platform has three tiers that scale by completely different mechanisms — and the interesting
constraints are in the smallest one.

---

## Scaling the Tiers

| Tier | Scales by | Constraint |
|------|-----------|-----------|
| **Routers** | horizontally, stateless | trivial — add pods |
| **Shards** | resharding | **operationally expensive**, hours per split |
| **Topology service** | ✗ **doesn't scale out well** | quorum writes; **the real limit** |

**The topology service is the scaling ceiling of the whole platform.** etcd/ZooKeeper is a consensus
system: writes go through a quorum, and adding members makes writes *slower*, not faster.

**How to live within it:**
- **Keep it out of the hot path** — routers watch and cache; queries never read it.
- **Store little** — shard maps and leases only. Never metrics, never per-query state.
- **Shard the topology itself by cell/region** — each cell has its own topology service, with a small
  global one for cross-cell metadata. This is the standard escape hatch.

---

## Cells / Regions

```
   ┌────────── CELL: us-east ──────────┐   ┌────────── CELL: eu-west ──────────┐
   │  routers · sidecars · MySQL       │   │  routers · sidecars · MySQL       │
   │  LOCAL topology service           │   │  LOCAL topology service           │
   └───────────────┬───────────────────┘   └───────────────┬───────────────────┘
                   └────────► GLOBAL topology ◄────────────┘
                              (keyspace definitions only, rarely written)
```

**Why cells:** a topology failure is contained to one cell; routers prefer local replicas (latency);
and the global store handles so little traffic it never becomes a bottleneck.

**The rule: a cell must be able to serve reads with the global topology unavailable.** If it can't,
you've built a global single point of failure with extra steps.

---

## Growing the Shard Count

Resharding is measured in **hours to days**, dominated by the bulk copy. Implications:

- **Split before you need to**, not when you're on fire. Resharding a saturated shard is far harder
  because the copy competes with production traffic.
- **Split by powers of two** (1→2→4→8). Halving key ranges is clean and keeps the mapping simple.
- **Throttle the copy on replica lag** — an unthrottled resharding copy will itself cause the outage
  you're trying to prevent.
- **Watch capacity, not size:** the trigger for splitting is *write throughput or working-set size*,
  not raw bytes.

**The counter-intuitive part:** the biggest constraint on shard count isn't the databases — it's the
**number of concurrent resharding operations your team and network can absorb.** That's a human and
bandwidth limit, not a technical one.

---

## Read Scaling Within a Shard

Once a shard exists, scale reads without resharding:
- Add replicas (cheap, async).
- Route by freshness policy — stale-tolerant reads to replicas.
- Cache at the sidecar (row cache) and above (see [Caching](#fu-caching)).

**Read scaling is easy; write scaling requires resharding.** Keep them separate in your head — most
"we need to reshard" moments are actually read problems with a cheaper fix.

---

## Cost Notes

- **Replicas dominate instance cost** — every shard × every replica.
- **Resharding is expensive twice**: engineer time, plus temporarily running source *and* target.
- **Over-sharding has a real ongoing cost** — more instances, more failover surface, more
  scatter-gather fan-out. **Under-sharding is a capacity emergency; over-sharding is a permanent
  tax.** Neither is free, and the second one is easy to inflict on yourself early.
