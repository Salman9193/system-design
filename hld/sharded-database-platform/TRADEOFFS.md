# Sharded Database Platform — Trade-offs

## 1. Where Does the Routing Logic Live?

| | **In the app** | **Middleware (this design)** | **In the database** |
|---|---|---|---|
| Examples | early YouTube, many in-house | Vitess, Citus | Spanner, CockroachDB, DynamoDB |
| App complexity | **high** — every service knows the shard map | **low** — plain SQL | **none** |
| Extra hop | none | +1 (sub-ms) | none |
| Engine freedom | keep MySQL | **keep MySQL** | must adopt the new engine |
| Migration cost | — | **low** — apps mostly unchanged | **high** — rewrite/migrate |
| Ops burden | app teams | **platform team** | vendor |

**Chosen: middleware.** It's the only option that gives horizontal write scaling *without* either
rewriting every application or migrating off the engine your team knows. The cost is that **you now
operate a distributed system** — which is what the rest of these tabs are about.

---

## 2. Hash vs Range Sharding

| | Hash | Range |
|---|---|---|
| Distribution | **even** | skewed by data shape |
| Range scans | ✗ scatter | **✓ single shard** |
| Hot spots | rare | **common** (e.g. sharding by time ⇒ all writes on the newest shard) |
| Resharding | clean split of hash space | needs rebalancing |

**Chosen: hash by default, range only when range queries dominate** and you accept hotspot
management. Time-based range sharding is the classic trap: it *guarantees* every write lands on one
shard.

---

## 3. Semi-Sync vs Async Replication

Covered in Deep Dives; the decision: **semi-synchronous** — at least one replica must acknowledge
before the client is told "committed."

**Why:** the zero-write-loss requirement is non-negotiable, and it costs one RTT. If a workload
genuinely tolerates loss (analytics events, view counters), give *that keyspace* async and be
explicit about it. **Durability should be a per-keyspace decision, not a platform-wide one.**

---

## 4. Lookup Vindex vs Scatter-Gather

| | Lookup vindex | Scatter-gather |
|---|---|---|
| Latency | 2 round trips, bounded | O(number of shards) |
| Write cost | **extra write + sync burden** | none |
| Consistency | can drift | always correct |
| Scales with shards | **yes** | **no** — gets worse as you grow |

**Chosen: scatter-gather until it hurts, then lookup vindexes for the specific hot access paths.**
Adding a lookup vindex for a query that runs twice a day is pure cost. The trigger for adding one is
*query frequency × shard count*, not elegance.

---

## 5. One Big Keyspace vs Many

| | Few large keyspaces | Many small ones |
|---|---|---|
| Blast radius | **large** | **small** |
| Cross-team transactions | possible | impossible |
| Operational overhead | lower | higher |
| Team autonomy | low | **high** |

**Chosen: a keyspace per bounded context.** This is the database expression of service boundaries —
and it makes the *impossible* things (cross-domain transactions) impossible **by construction**,
which is usually what you want anyway.

---

## 6. Build vs Adopt vs Managed

| | Build your own | Adopt Vitess/Citus | Managed (PlanetScale, etc.) |
|---|---|---|---|
| Control | total | high | low |
| Time to value | **quarters/years** | months | **days** |
| Ops burden | **enormous** | high | **low** |
| Cost | headcount | headcount | $$ |

**Chosen (honestly): adopt, don't build.** This document is a *design exercise*; in reality, building
this from scratch is justified only at extreme scale with a dedicated platform team. **Saying that
out loud in an interview is a strength, not a dodge** — the design judgement being tested includes
knowing when not to build.
