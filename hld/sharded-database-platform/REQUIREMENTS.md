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

---

## Engineering Blogs & Primary Sources

This "build Vitess yourself" design maps directly onto Vitess itself and the companies running it at
scale. These sources confirm the specific protocols this HLD derives — and in one case, word-for-word.

- **Vitess — Resharding (official docs).**
  https://vitess.io/docs/reference/vreplication/reshard/
  The resharding workflow, described exactly as this HLD's **Deep Dives** tab derives it:
  *"Vitess copies, verifies, and keeps data up-to-date on new shards while the existing shards
  continue to serve live traffic… the migration occurs with only a few seconds of read-only
  downtime."* The command sequence — `create` (copy) → `VDiff` (verify) → `SwitchTraffic` (cutover,
  which **stops writes on the source and waits for the target to catch up**) → `complete` (retire) —
  is precisely the **copy → catch up → verify → switch → retire** protocol, including the write-freeze
  at cutover. → backs the **Deep Dives** (online resharding) tab.

- **Slack — "Scaling Datastores at Slack with Vitess" (2020).**
  https://slack.engineering/scaling-datastores-at-slack-with-vitess/
  The war story that validates the whole design: Slack runs its monolith and services on Vitess,
  sharding by **keyspace** (not just by team, which had caused **team hot-spots** — this HLD's hot-key
  problem). When COVID drove a **50% query-rate jump in one week**, they horizontally resharded a busy
  keyspace live — *"without resharding and moving to Vitess, we would've been unable to scale… leading
  to downtime."* → backs the **Scaling** and **Failure Modes** (hot shard) tabs.

- **Vitess — VReplication (design docs).** https://vitess.io/docs/reference/vreplication/vreplication/
  The change-data-capture engine underneath resharding, online schema changes, and materialized views
  — the mechanism behind the "catch up" step. Confirms that **online schema change and resharding share
  one primitive** (this HLD's Deep Dives point that they're the same skeleton). → backs **Deep Dives**
  (online schema change).

- **PlanetScale — how Vitess handles it (engineering blog).** https://planetscale.com/blog
  Vitess-as-a-managed-service; their posts on **online DDL, connection pooling, and reparenting** are
  the operational reality behind this HLD's control-plane. Reinforces the **adopt-don't-build** verdict
  in the **Trade-offs** tab. (PlanetScale is Vitess's primary commercial steward.)

- **ByteByteGo — "How YouTube Supports Billions of Users" (MySQL → Vitess).**
  https://blog.bytebytego.com/p/how-youtube-supports-billions-of
  The origin narrative — YouTube built Vitess when it outgrew one MySQL — and the source that seeded
  the [Database Scaling](#fu-database-scaling) fundamentals. The scaling *ladder* this platform sits
  atop. → context for **Requirements**.

**The through-line:** every one of these confirms the same non-obvious claims this HLD makes — that
resharding is **copy→verify→switch with a seconds-long write freeze**, that the shard key choice
determines hot-spots, and that at real scale you **adopt Vitess rather than build**. When the
reference implementation's own docs match your derived protocol step-for-step, the design is sound.
