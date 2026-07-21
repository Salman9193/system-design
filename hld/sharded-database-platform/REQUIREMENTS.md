# Sharded Database Platform — Requirements

Design the **platform** that lets an organisation run sharded relational databases as a service:
hundreds of application teams, thousands of MySQL/Postgres instances, self-serve.

This is the "build Vitess" problem. It's a strong Staff/Principal exercise because the hard parts
are **operational, not algorithmic** — and because the naive answer ("put a proxy in front") falls
apart under the first follow-up question.

> Background: [Database Scaling in Practice](#fu-database-scaling) — the ladder, and where
> middleware sharding sits among RDS / Aurora / Bigtable / Spanner.

---

## Functional Requirements

1. **Provision a keyspace** — a logical database, sharded or unsharded, self-serve.
2. **Route queries** — applications speak ordinary SQL and don't know the shard map.
3. **Reshard online** — split 1 → N shards (or merge) with **seconds** of write unavailability, not
   hours.
4. **Automatic failover** — promote a replica when a primary dies, without split-brain.
5. **Backup & point-in-time restore** — continuous, without taking instances out of service.
6. **Online schema change** — `ALTER TABLE` on a billion-row sharded table without locking it.
7. **Read routing policy** — per-query choice of primary vs replica, and of freshness.
8. **Observability** — per-shard/per-query metrics, slow-query attribution, topology state.

## Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| **Durability** | **zero acknowledged-write loss**, ever — the non-negotiable one |
| Write unavailability (failover) | < 30 s |
| Write unavailability (resharding cutover) | **< 5 s** |
| Routing overhead | < 1 ms p99 added latency |
| Scale | 10k+ database instances, 100k+ shards, 10M+ QPS aggregate |
| Self-serve | a team provisions a keyspace without a DBA ticket |
| Blast radius | one keyspace's failure must not affect others |

---

## The Requirements That Actually Shape the Design

Three of these do all the work:

**1. Zero acknowledged-write loss.** This forbids a lot of otherwise-attractive designs. It means
failover must never promote a replica that's missing committed transactions, which means you need
**durable position tracking** (GTIDs) and **fencing** of the old primary. Most naive failover designs
lose writes here.

**2. Online resharding with a seconds-long cutover.** This is the single hardest feature. It forces a
**copy → catch-up → verify → switch → retire** protocol with a change-data-capture stream, because
you cannot stop writes for the hours a bulk copy takes.

**3. Applications speak plain SQL.** This forces a real **SQL parser** in the routing layer. You
can't route what you can't parse, and "just regex the WHERE clause" fails immediately on joins,
subqueries, and prepared statements.

---

## Explicit Non-Goals

- **Replacing the storage engine.** We use stock MySQL/Postgres. (Replacing it is the Aurora
  approach — a different design, see [Database Scaling](#fu-database-scaling).)
- **Full ACID across shards by default.** Cross-shard 2PC exists but is opt-in and slow; the design
  encourages keeping transactions inside a shard.
- **Arbitrary cross-shard analytics.** OLAP goes to a warehouse via CDC, not through this platform.

---

## Scale Estimates

- 500 keyspaces; median 4 shards, largest 1,000+.
- Each shard: 1 primary + 2–3 replicas ⇒ ~10k MySQL instances.
- Routing tier: stateless, ~1,000 pods.
- Topology metadata: small (MBs) but **read constantly** and **must be strongly consistent**.

**The asymmetry to notice:** the *data* is petabytes, but the **topology metadata is tiny and
critically consistent**. That asymmetry drives the control-plane/data-plane split in the next tab.
