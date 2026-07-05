# Relational Databases & ACID

The relational database is the default for a reason: it stores structured data with
enforced relationships, answers arbitrary queries with joins and aggregations, and
guarantees correctness through ACID transactions. When in doubt, start here — "boring
and correct" is a feature, and most systems' core data is relational.

---

## The Relational Model

Data lives in **tables** of rows and columns, with a fixed **schema** (each column has
a type). Rows are linked across tables by **keys** — a primary key identifies a row, a
foreign key references another table's row. **SQL** is the declarative query language:
you say *what* you want, the database figures out *how* to get it.

The power is in **relationships and queries**: you can join tables, filter, group, and
aggregate in ways the database optimizes for you — without pre-planning every access
pattern the way NoSQL often requires.

---

## ACID — The Correctness Guarantees

Relational databases (and some others) provide **ACID** transactions:

- **Atomicity** — all operations in a transaction succeed, or none do. A transfer that
  debits one account and credits another can't half-happen.
- **Consistency** — a transaction moves the database from one valid state to another,
  respecting all constraints (foreign keys, uniqueness, checks).
- **Isolation** — concurrent transactions don't interfere; each sees a consistent view
  (the depth is the Transactions & isolation tab).
- **Durability** — once committed, data survives crashes (it's persisted, typically via
  a write-ahead log).

ACID is why relational databases are the home for **money, orders, inventory** — anything
where a wrong or half-applied write is unacceptable.

---

## Normalization (and denormalization)

**Normalization** organizes data to eliminate redundancy: each fact is stored once, and
relationships are expressed by keys. This prevents **update anomalies** — if a
customer's address lives in one place, you update it once, not in a thousand order rows.

- Higher normal forms progressively remove redundancy; in practice, most schemas aim
  for a sensible middle ground.
- **Denormalization** is the deliberate reverse — duplicating data to avoid expensive
  joins on read-heavy paths. It's a **read-optimization trade-off**: faster reads, but
  you now maintain multiple copies and risk them drifting. Normalize by default;
  denormalize consciously where read performance demands it.

---

## When Relational Shines

- **Structured data with relationships** — entities that reference each other.
- **Transactions & strong consistency** — money, orders, bookings; correctness-critical
  writes.
- **Complex / ad-hoc queries** — joins, aggregations, reporting, queries you didn't
  anticipate.
- **Data integrity** — constraints, foreign keys, and uniqueness enforced by the
  database, not hoped for in application code.

---

## The Limits (where it strains)

- **Horizontal write scaling is harder.** The traditional path is scale *up* (a bigger
  machine), then **read replicas** for read scaling, then **sharding** for write scaling
  — and sharding a relational DB is operationally heavy (that's the Data distribution
  module). Note: distributed SQL databases now scale horizontally while keeping SQL and
  strong consistency, so "relational can't scale" is dated (see SQL vs NoSQL).
- **Rigid schema** — schema changes (migrations) need care at scale.
- **Joins get expensive** at very large scale, which is what pushes some workloads to
  denormalized NoSQL.

> The staff move: "I default to a relational database for structured, transactional
> data — it gives me ACID, strong consistency, and flexible queries, and it's the safe
> choice for correctness-critical data like orders and payments. I only move off it when
> a specific need — massive write scale, a graph workload, schemaless documents — pushes
> me there. Starting relational and justifying any departure is stronger than reaching
> for NoSQL by default."

---

## The Summary

- **Relational = tables + schema + keys + SQL**, with **ACID** (atomicity, consistency,
  isolation, durability) for correctness.
- **Normalize** to remove redundancy; **denormalize** deliberately for read speed.
- **Best for** structured, related, transactional data and flexible queries — which is
  most systems' core data.
- **Strains** on horizontal write scale and huge-scale joins — the reasons to consider
  NoSQL or distributed SQL, covered next.
