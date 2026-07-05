# SQL vs NoSQL

This is the decision interviewers love, and the weak answer is "NoSQL scales better."
The strong answer is that it's not a contest — you pick based on **data shape, access
pattern, scale, and consistency needs**, the modern lines are blurred, and real systems
use **both**. Here's how to reason about it out loud.

---

## The Comparison

| Dimension | Relational (SQL) | NoSQL |
|-----------|------------------|-------|
| Schema | Fixed, enforced | Flexible / schemaless |
| Data shape | Structured, related | Varies by family (KV, doc, wide-column, graph) |
| Scaling | Vertical + read replicas; sharding is heavier | Horizontal by design |
| Consistency | Strong (ACID) | Often eventual (BASE); tunable in many |
| Queries | Flexible, ad-hoc, joins | Optimized for known access patterns |
| Transactions | First-class, multi-row | Limited (improving) |
| Relationships | Joins | Denormalize, or a graph DB |
| Best for | Correctness, complex queries, related data | Scale, flexible schema, specific shapes |

---

## When to Choose SQL

- **Structured data with relationships** — entities that reference each other.
- **Transactions and strong consistency** — money, orders, inventory, bookings.
- **Complex or unpredictable queries** — joins, aggregations, reporting, ad-hoc analysis.
- **Correctness matters more than raw scale** — which is true for most systems' core
  data.

Honestly, this describes a large share of applications — which is why "default to
relational" is good advice.

---

## When to Choose NoSQL

- **Massive scale / throughput** beyond one machine, especially write-heavy.
- **Flexible or rapidly-evolving schema** — self-contained documents that change shape.
- **Simple or well-known access patterns** — you know exactly how you'll read the data.
- **Availability over strong consistency** — the system must stay up under partitions,
  and eventual consistency is acceptable.
- **A specific data shape** — a graph of relationships, high-volume time-series, or
  large blobs.

Match the *family* to the shape (see NoSQL families) — the choice isn't "NoSQL," it's
"a document store" or "a wide-column store."

---

## The Modern Nuance (say this — it's the differentiator)

The old binary — "SQL = correct but doesn't scale, NoSQL = scales but weak guarantees"
— is dated:

- **Distributed SQL / NewSQL** (e.g. Spanner, CockroachDB, Vitess) give you SQL, joins,
  and **strong consistency** *and* horizontal scale — dissolving the "relational can't
  scale" premise.
- **NoSQL is adding guarantees** — many stores now offer tunable/strong consistency and
  limited transactions.

So the frontier isn't SQL-vs-NoSQL; it's "what consistency, scale, and query model does
this workload need," with more options that mix and match than before.

---

## Polyglot Persistence (the real-world answer)

Production systems rarely pick one. They use the right store per workload —
**polyglot persistence**:

- **Relational** for orders, users, payments (transactional core).
- **Key-value cache** for sessions and hot lookups.
- **Object storage** for media and blobs.
- **Search index** for full-text search.
- **Wide-column / time-series** for events and metrics.
- **Graph** for social or recommendation relationships.

> The staff move: "I start relational for the transactional core, because ACID and
> flexible queries are the safe default, and I justify any move off it by a specific
> need — a cache, a graph, firehose writes, blobs. Then I say it's polyglot: I'll use a
> key-value cache, object storage for media, maybe a search index — the right store per
> workload. And I'd note distributed SQL now offers scale with strong consistency, so
> the SQL-vs-NoSQL framing itself is a bit outdated."

---

## The Summary

- **It's fit, not a contest** — decide on data shape, access pattern, scale, and
  consistency.
- **SQL** for structured, transactional, query-flexible, correctness-critical data
  (most core data).
- **NoSQL** for scale, flexible schema, known access patterns, availability, or a
  specific shape — picking the right *family*.
- **The nuance:** distributed SQL scales with strong consistency; NoSQL adds
  guarantees — the binary is blurred.
- **Real systems are polyglot** — the right store per workload.
