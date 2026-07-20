# The Scaling Ladder — What Breaks, and the Fix

The canonical progression, using YouTube's MySQL → Vitess journey as the worked example. **Each
rung is a response to a specific thing that broke on the rung below.** Learning the *failure* is
more useful than learning the fix.

---

## Rung 0 — One Database, A Few App Servers

Where everything starts, and it's correct to start here. Simple, transactional, well-understood.

**What breaks:**
- Queries slow as data grows.
- **Backups need downtime** (or at least hurt).
- One server failure = **data loss**.
- Global users are far away — latency.

---

## Rung 1 — Vertical Scaling

Buy a bigger machine. Genuinely underrated: modern hardware runs a *lot* of database.

**What breaks:** cost curves superlinearly, and there is a **hard ceiling**. You also still have a
single point of failure, so this fixes throughput but not availability.

---

## Rung 2 — Read Replicas

Asynchronously replicate the primary to N replicas; send reads there.

**What breaks: staleness.** A replica is *seconds* behind. A user updates their profile, refreshes,
and sees the old value.

**The fix — classify your reads.** Not all reads are equal:

| Read type | Goes to | Example |
|-----------|---------|---------|
| **Replica read** | a replica | video view counts, feeds, browsing — a few seconds stale is fine |
| **Primary read** | the primary | *your own* settings right after you changed them |

**This is the practical face of CAP.** Partition tolerance is mandatory, so you're choosing between
consistency and availability — and the real answer is that you choose **per query**, not per system.
See [CAP & PACELC](#fu-data-distribution).

> **Read-your-own-writes** is the specific guarantee at stake. Cheaper implementations: pin a user
> to the primary for N seconds after they write, or route by "has this user written recently?"

---

## Rung 3 — Write Load Outgrows Replication

Now writes are the problem. Replicas fall behind — not because the primary can't take the writes,
but because **replicas can't apply them fast enough**. Classic MySQL replication applies the relay
log **single-threaded**, so one replica thread races many primary threads. It loses.

**Lag grows without bound**, and every replica read gets more wrong.

**Fixes, in escalating order:**

1. **Parallel replication** (modern MySQL applies in parallel by schema or by writeset).
2. **Reduce the write volume** — batch, debounce counters, move hot counters out of the DB.
3. **Warm the replica's cache ahead of the apply thread.** YouTube's *Prime Cache* read ahead in
   the relay log, inspected upcoming queries' `WHERE` clauses, and **pre-loaded those rows into
   memory** — so the apply thread hit memory instead of disk.

> **Why Prime Cache is a good idea to steal:** the replica's bottleneck wasn't CPU, it was
> **synchronous disk I/O inside a single-threaded loop**. You can't parallelise the loop, but you
> *can* make each iteration not touch disk. **Prefetch based on lookahead** is a general technique —
> the same shape as speculative execution or readahead in a filesystem.

It bought time. It did not remove the ceiling.

---

## Rung 4 — Vertical / Functional Split

Move different **tables** to different databases: users here, video metadata there, comments
elsewhere.

**Why it's the right next step:** it's cheap, it's reversible, and it usually maps to team
boundaries. Each domain scales and gets operated independently.

**What breaks:** you lose **cross-domain joins and transactions**. A query joining users to videos
now happens in the application. This is the first real loss of relational power — and it's the one
people underestimate.

---

## Rung 5 — Horizontal Sharding

Split a **single table's rows** across many databases by a shard key (user ID, video ID, tenant).
Now reads *and writes* scale.

**What breaks — and this is the big one:**

| Loss | Detail |
|------|--------|
| **Cross-shard transactions** | atomicity across shards needs 2PC — slow, and a new failure mode |
| **Cross-shard queries** | joins, aggregates, `ORDER BY` over all data get hard or impossible |
| **Secondary indexes** | an index on a non-shard-key column must be **global**, and kept in sync |
| **Routing burden** | someone must map a query → shard, and handle scatter-gather |
| **Rebalancing** | resharding live traffic is now a *project*, not a config change |

**The shard key is the decision that makes or breaks the system.** Pick one that (a) appears in
almost every query, (b) spreads load evenly, (c) keeps things you need to transact over *together*.
Getting it wrong is expensive to undo — see [Partitioning & Sharding](#fu-data-distribution).

**Where that routing burden goes** is precisely what distinguishes the next three tabs:

- Into the **application** — YouTube's early approach: the client knew the shard map. It works, and
  it spreads database logic through every service.
- Into a **middleware layer** — **Vitess**: a proxy speaks SQL, routes, and hides the sharding.
- Into the **database itself** — **Bigtable / Spanner / DynamoDB**: sharding is the engine's job.

---

## Rung 6 — Operational Load Becomes the Bottleneck

At thousands of instances, the *system* is fine and the **humans** are the problem. Failover,
backups, schema changes, and resharding are each multi-step, risky, and manual.

**What breaks:** a routine operation done by a tired human at 3 a.m. Misconfigure one replica's
primary and you get cascading inconsistency.

**The fix is automation of the boring things:**
- **Reparenting** — promoting a replica to primary, repointing every other replica, rerouting
  traffic: automated, not run from a wiki page.
- **Backups** — taken from replicas, without downtime, continuously.
- **Resharding** — copy, verify, then atomically switch traffic.

> **The lesson worth internalising:** past a certain scale, your database architecture is judged by
> its **operational surface**, not its benchmark. Every one of the systems in this section is
> mostly an answer to "how do I run thousands of these without a large team of humans?"

---

## The Ladder, In One Table

| Rung | Fix | What it costs you |
|------|-----|-------------------|
| 1 | bigger machine | money; hard ceiling remains |
| 2 | read replicas | **staleness** (classify reads) |
| 3 | replication tuning / prefetch | complexity; buys time only |
| 4 | functional split | cross-domain joins & transactions |
| 5 | sharding | cross-shard transactions, queries, indexes |
| 6 | automation | up-front engineering investment |

**Climb only as far as your actual problem.** Most systems never need rung 5 — and the ones that
skip straight to it usually pay the costs without needing the benefits.
