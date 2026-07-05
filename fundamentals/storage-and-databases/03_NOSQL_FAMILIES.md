# NoSQL Families

"NoSQL" isn't one thing — it's four different database families that each trade the
relational model's guarantees for a specific benefit: horizontal scale, flexible
schema, or a data shape relational handles poorly. The staff-level skill is knowing
*which family* fits an access pattern, not treating NoSQL as a monolith.

---

## Why NoSQL Exists

NoSQL databases emerged to handle things relational databases strain on: **massive
horizontal scale**, **flexible/evolving schemas**, **very high write throughput**, and
**specific data shapes** (graphs, huge sparse tables). They generally achieve this by
relaxing guarantees relational databases hold sacred.

### BASE instead of ACID
Where relational offers ACID, many NoSQL systems offer **BASE**: **B**asically
**A**vailable, **S**oft state, **E**ventual consistency. They favor availability and
partition tolerance over immediate consistency (the CAP trade-off — Data distribution
module). A write may not be instantly visible everywhere, but the system stays up and
scales. For a like count that's fine; for a bank balance it isn't.

---

## The Four Families

| Family | Data model | Best for | Weak at |
|--------|-----------|----------|---------|
| **Key-value** | `key → value` (opaque) | Caching, sessions, simple fast lookups | Anything needing queries over the value |
| **Document** | JSON-like documents | App/catalog data, nested objects, flexible schema | Cross-document joins, multi-doc transactions |
| **Wide-column** | Rows with dynamic columns, partitioned | Massive write throughput, time-series, huge scale | Ad-hoc queries not on the partition key |
| **Graph** | Nodes + edges | Relationship-heavy queries (social, fraud, recs) | Bulk analytical scans |

### Key-value
The simplest model: a key maps to an opaque value. Blazing fast point lookups, trivial
to scale. Perfect for **caching, session storage, feature flags, and simple lookups**.
You can't query *inside* the value — if you need that, use a document store.

### Document
Stores **documents** (JSON-like), so a whole object — with nested fields and arrays —
lives together and is fetched in one read. **Flexible schema** (documents in a
collection can differ), great for **application data and catalogs** where objects are
self-contained and the schema evolves. Weaker at joining across documents.

### Wide-column
Rows keyed by a **partition key**, each row holding a dynamic, sparse set of columns.
Built for **enormous write throughput and scale** — time-series, event logs, sensor
data, messaging. The catch: you design the schema **around your queries** (query by
partition key), and ad-hoc queries outside that pattern are painful.

### Graph
Data as **nodes and edges**, optimized for **traversing relationships**. Queries like
"friends of friends," "what did people who bought X also buy," or fraud-ring detection
are cheap traversals here but would be brutal multi-join queries in relational. Best
when the **relationships are the point**.

---

## Common NoSQL Traits

Across the families, recurring themes:

- **Horizontal scaling is built in** — designed to shard across many nodes from the
  start.
- **Denormalization is normal** — data is often duplicated and stored in the shape it's
  read, since joins are limited or absent.
- **Query-driven schema design** — you model the data around the access patterns, not
  the other way around. (This is the opposite of relational's "normalize, then query
  however.")
- **Consistency is often eventual** — though many modern NoSQL systems now offer tunable
  or strong consistency and limited transactions.

> The staff move: "I don't say 'use NoSQL' — I pick the family by access pattern.
> Key-value for a cache or sessions, document for self-contained app objects,
> wide-column for firehose write volume and time-series, graph when the relationships
> are the query. And I design the schema around the queries, because most NoSQL stores
> make you commit to your access patterns up front."

---

## The Summary

- **NoSQL is four families**, each a different trade, not one alternative to SQL.
- **BASE over ACID** — many trade immediate consistency for availability and scale.
- **Key-value** (fast lookups/cache), **document** (flexible self-contained objects),
  **wide-column** (massive writes/time-series), **graph** (relationship traversals).
- **Model around your queries** — the schema follows the access pattern.
