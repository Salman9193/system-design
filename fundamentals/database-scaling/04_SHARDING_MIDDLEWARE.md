# Sharding Middleware — Vitess

Aurora scales storage and reads but still has **one writer**. When writes exceed one machine, you
must shard. Vitess is the answer to: *"shard MySQL, without rewriting the application or replacing
MySQL."*

Built at YouTube, now CNCF-graduated and used by Slack, GitHub, Shopify, and others.

---

## The Bet

> **Keep MySQL exactly as it is. Put the intelligence in a layer above it.**

The opposite of Aurora (which kept the engine and replaced storage) and of Bigtable (which replaced
everything). Vitess treats MySQL as a **commodity storage unit** and builds a distributed system out
of many of them.

```
                    Application  (speaks plain MySQL protocol)
                          │
                    ┌─────▼─────┐
                    │  VTGATE   │   stateless query router
                    │           │   parses SQL, consults the VSchema,
                    │           │   routes / scatters / gathers
                    └─────┬─────┘
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
     ┌─────────┐    ┌─────────┐     ┌─────────┐
     │VTTablet │    │VTTablet │     │VTTablet │   one sidecar per MySQL
     │  MySQL  │    │  MySQL  │     │  MySQL  │
     │ shard -80│   │shard 80-│     │ replica │
     └─────────┘    └─────────┘     └─────────┘
                          ▲
                    ┌─────┴──────┐
                    │ Topology   │  etcd/ZooKeeper: shard map,
                    │  service   │  primary election, lock service
                    └────────────┘
```

---

## VTGate — The Router

The application connects to VTGate as if it were a single MySQL. VTGate:

- **Parses the SQL** (Vitess has its own SQL parser) and inspects the `WHERE` clause.
- **Routes** to the right shard when the shard key is present.
- **Scatters and gathers** when it isn't — running the query on all shards and merging.
- **Decides replica vs primary** based on the requested consistency.
- Is **stateless** — scale it horizontally, lose one, nothing breaks.

> **The abstraction, stated honestly:** it *looks* like one database, but a query without the shard
> key becomes a scatter-gather across every shard. Vitess makes sharding **invisible, not free** —
> and treating it as free is how you build a system that falls over at 10× traffic.

## VTTablet — The Sidecar

One per MySQL instance, and it does far more than proxy:

| Job | Why it matters |
|-----|----------------|
| **Connection pooling** | MySQL allocates memory per connection; thousands of app servers × direct connections = OOM. VTTablet fronts them with a small pool. |
| **Query safety** | rejects or rewrites queries missing a `LIMIT`; blacklists known-bad queries; enforces timeouts and transaction caps. |
| **Query consolidation** | if an identical query is already in flight, later callers **wait and share the result** instead of re-executing it. |
| **Row cache** | caches individual **rows by primary key** (vs MySQL's 16 KB page buffer), and invalidates from the replication stream. |
| **Health / topology reporting** | feeds the orchestration layer. |

**Two of these deserve emphasis:**

**Connection pooling is not a micro-optimisation** — it's the thing that makes the fleet possible at
all. Per-connection memory is the reason a naive architecture dies long before CPU does.

**Query consolidation solves the thundering herd at the database.** A hot row requested by 5,000
concurrent users executes **once**. This is the same shape as request coalescing in a cache, and it
belongs in your toolkit — see [Caching](#fu-caching).

**The row cache vs page cache distinction is genuinely instructive:** MySQL's buffer pool loads
**16 KB pages**, which is excellent for sequential scans and wasteful for the random single-row
access that web apps actually do. A row-level cache matches the access pattern, and **invalidating
it from the replication stream** keeps it correct without TTL guesswork.

---

## Resharding Without Downtime

The operationally hardest thing Vitess automates. Splitting one shard into four:

```
1. Provision new shards; create the schema.
2. Copy the existing data into them (bulk).
3. Tail the source's replication stream to catch up on changes made during the copy.
4. VERIFY — compare source and destination.
5. Switch reads (replica traffic first — reversible, low risk).
6. Switch writes — briefly freeze writes, drain, flip the routing, resume.
7. Retire the old shard.
```

**The pattern is general and worth naming: copy → catch up → verify → switch → retire.** It's the
same skeleton as a live table migration, a cache warm-and-swap, or the
[dictionary rollout in the Text Segmentation Service](#hld-text-segmentation-service). The
verification step before the switch, and the ability to reverse at step 5, are what make it safe.

**Only step 6 has a write interruption**, and it's seconds. Everything before it is reversible.

---

## Reparenting — Automated Failover

Promoting a replica to primary manually means: detect the failure, choose a replica, repoint every
other replica at it, and reroute traffic. Each step is seconds; together they're minutes; and a
mistake causes **split-brain or data loss**.

Vitess automates it against a **lock service** (etcd/ZooKeeper) so that exactly one node can win the
election. That lock service is the thing preventing two primaries — the classic distributed-systems
failure. See [Consensus](#fu-data-distribution).

---

## What Vitess Does and Doesn't Give You

| Gives you | Doesn't give you |
|-----------|------------------|
| Horizontal **write** scaling | free cross-shard joins |
| MySQL compatibility & familiarity | full ACID across shards (2PC exists, but it's slow) |
| Online resharding | a free lunch on shard-key choice |
| Automated failover & backups | escape from thinking about data locality |
| Connection pooling, query safety | protection from scatter-gather queries you wrote |

**The honest summary:** Vitess makes sharded MySQL *operable*. It does not make sharding's
**semantic** costs disappear — those are inherent to splitting data across machines, and no
middleware can undo them.

---

## Where It Fits

- **Best for:** an existing MySQL application that outgrew one writer, where you want to keep the
  relational model, your SQL, and your team's expertise.
- **Alternatives:** **Citus** (the same idea for PostgreSQL), **PlanetScale** (managed Vitess),
  or moving to a natively distributed store (next tab).
- **Not for:** greenfield systems that could adopt a natively-sharded database and skip the
  middleware layer entirely.
