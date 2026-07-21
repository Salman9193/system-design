# Choosing — A Decision Framework

---

## Start With the Actual Bottleneck

Most "we need to scale the database" conversations skip this. Name the constraint first, because
each one has a *different* answer:

| The bottleneck | The fix | Do **not** reach for |
|----------------|---------|----------------------|
| Slow queries | **indexes**, query rewriting, caching | sharding |
| Read throughput | read replicas + read classification | sharding |
| Replica lag | parallel replication, fewer writes, prefetch | more replicas (makes it worse) |
| Storage size / growth | Aurora-style decoupled storage; archiving | bigger instances forever |
| Failover time | Aurora, or automated reparenting (Vitess) | manual runbooks |
| Operational toil | managed service or orchestration | more headcount |
| **Write throughput** | **sharding** (Vitess) or a natively distributed store | anything else — this is the one that *needs* it |

> **Only one row in that table genuinely requires sharding.** Write throughput. Everything else has
> a cheaper answer, and reaching for shards first is the most common expensive mistake in this
> area.

---

## The Decision Tree

```
Do you have a write-throughput problem that one large machine can't hold?
│
├── NO ──► Do you need SQL, joins, and transactions?
│          ├── YES ──► RDS / Aurora / managed Postgres
│          │           (add read replicas; classify reads; index properly)
│          └── NO  ──► DynamoDB / Bigtable if the access pattern is key-based
│                      and you value predictable latency
│
└── YES ─► Are you already on MySQL/Postgres with an existing app?
           ├── YES ──► Vitess (MySQL) / Citus (Postgres)
           │           keep the engine, add the routing layer
           └── NO  ──► Greenfield: choose a natively distributed store
                       ├── need SQL + strong distributed transactions ──► Spanner / CockroachDB
                       ├── key + range scans, huge scale, write-heavy ──► Bigtable / HBase
                       └── key-value, predictable latency, managed    ──► DynamoDB
```

---

## The Comparison

| | RDS | Aurora | Vitess | Bigtable | Spanner | DynamoDB |
|---|---|---|---|---|---|---|
| **Write scaling** | 1 node | 1 node | **sharded** | **sharded** | **sharded** | **sharded** |
| **Read scaling** | replicas | **15 shared-storage readers** | replicas/shard | tablet servers | replicas | auto |
| **Compute/storage split** | no | **yes** | no | **yes** | **yes** | yes |
| **SQL** | full | full | most | no | **yes** | no |
| **Distributed txns** | n/a | n/a | 2PC (slow) | no | **yes** | limited |
| **Auto-partition** | — | — | assisted resharding | **yes** | **yes** | **yes** |
| **Ops burden** | low | low | **high** | low (managed) | low | **lowest** |
| **Lock-in** | low | medium | low (OSS) | high | high | high |

---

## Rules of Thumb

1. **Postgres/MySQL on a big managed instance goes further than people think.** Modern hardware
   handles tens of thousands of writes/sec. Exhaust vertical scaling and indexing before
   distributing.
2. **Read scaling and write scaling are different problems.** Replicas fix reads and do nothing for
   writes. Confusing the two leads to buying the wrong solution.
3. **The shard key is a one-way door.** Changing it later means rewriting the data layout. Spend
   real time here.
4. **Distributed transactions are a design smell.** If a workflow needs 2PC across shards, the shard
   key probably isn't grouping the right things together.
5. **Choose the boring option for the boring parts.** Sharding your *user* table might be
   unavoidable; sharding your *config* table never is.
6. **Migration cost dominates.** Moving a live system between these is a multi-quarter project —
   which is why the sequence (RDS → Aurora → Vitess) is more common than a jump to Spanner.

---

## In an Interview

When you say "we'll shard," expect follow-ups. **Have these ready:**

- **Shard key** — what it is, and why. What queries does it make cheap, and which does it break?
- **Cross-shard queries** — which ones exist, and how you handle them (scatter-gather? denormalise?
  a secondary index table?).
- **Resharding** — the copy → catch up → verify → switch → retire sequence, and where the only
  write freeze occurs.
- **Failover** — who elects the new primary, what stops split-brain (a lock service / consensus),
  and how long writes are unavailable.
- **Read consistency** — which reads go to replicas, which must hit the primary, and how you deliver
  read-your-own-writes.
- **What you gave up** — naming the losses (cross-shard transactions, joins, global secondary
  indexes) demonstrates you've actually done this.

**The strongest move is to *not* jump to sharding.** Walking the ladder out loud — "first indexes,
then replicas with read classification, then a functional split; we'd only shard when writes exceed
one machine, and here's how we'd pick the key" — shows judgement. Jumping straight to a distributed
database signals the opposite.

---

## Related in This Repo

- **Theory:** [Storage & Databases](#fu-storage-and-databases),
  [Data Distribution](#fu-data-distribution) (partitioning, replication, consistency, CAP,
  consensus), [Caching](#fu-caching).
- **Applied:** [**Sharded Database Platform**](#hld-sharded-database-platform) — the full 7-tab HLD
  that *designs* the middleware layer described in the Sharding Middleware tab (online resharding,
  split-brain prevention, lookup vindexes, connection pooling math).
- **Applied:** [Search Typeahead](#hld-search-typeahead),
  [Text Segmentation Service](#hld-text-segmentation-service) (versioned artifacts and the
  copy→verify→switch rollout pattern),
  [RAG Knowledge Assistant](#hld-rag-knowledge-assistant).
