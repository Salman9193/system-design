# Partitioning & Sharding

Partitioning splits a dataset so each node holds a subset — the way you scale past one
machine's storage and write throughput. It sounds simple ("put half the rows here, half
there"), but the *how* — which key, which strategy — determines whether load spreads
evenly or one node melts, and whether your common queries stay fast or turn into
expensive fan-outs. This is a top HLD deep-dive topic.

---

## Vertical vs Horizontal Partitioning

- **Vertical partitioning** — split by *columns/features*: put some columns or tables on
  one node, others elsewhere (e.g. user profiles on one DB, user activity on another).
  Useful for separating concerns, but limited — a single table still can't exceed one
  node.
- **Horizontal partitioning (sharding)** — split by *rows*: each shard holds a subset of
  the rows, same schema. This is the scalable one, and "sharding" usually means this.

---

## Sharding Strategies

How you map a row to a shard is the core decision:

| Strategy | How | Pros | Cons |
|----------|-----|------|------|
| **Range** | Partition by key ranges (A–M, N–Z; by date) | Efficient range scans; simple | Hot spots if keys skew (e.g. newest date shard gets all writes) |
| **Hash** | Hash the key → shard | Even distribution, no range hot spots | Loses range-query locality; naive resharding moves everything |
| **Consistent hashing** | Hash keys *and* nodes onto a ring | Adding/removing a node moves minimal data | More complex; needs virtual nodes for balance |
| **Directory / lookup** | A lookup service maps key → shard | Flexible, can rebalance freely | The lookup is a dependency and potential bottleneck/SPOF |

### Range-based
Keeps keys ordered, so **range queries** ("all orders in June") are efficient — they hit
one or a few contiguous shards. The danger is **skew**: a time-based key sends all new
writes to the last shard (a hot spot).

### Hash-based
Hashing the key scatters rows evenly, killing range hot spots — but you **lose
locality** (a range query must hit every shard), and adding a node re-hashes and moves
almost all data. That last problem is what consistent hashing fixes.

### Consistent hashing
Hash both keys and nodes onto a ring; a key belongs to the next node clockwise. Add or
remove a node and only the keys between it and its neighbor move — **minimal
reshuffling**. **Virtual nodes** (each physical node placed at many ring positions)
smooth out imbalance. This is the standard for distributed caches and databases.

---

## Choosing the Shard Key (the decision that makes or breaks it)

The shard key determines both load distribution and query efficiency. A good key:

1. **Distributes load evenly** — high cardinality, no skew. (User ID hashes well; "country"
   doesn't — most users in a few countries.)
2. **Matches access patterns** — so your **common queries hit a single shard**. If you
   usually query by user, shard by user ID and a user's data lives on one shard.

These can conflict, and resolving that trade-off *is* the skill.

---

## Hot Spots / Hot Keys

Even with a decent key, load can concentrate: a **celebrity user**, a **viral item**, a
**monotonically increasing key** (timestamps, auto-increment IDs) all funnel traffic to
one shard. Mitigations:

- Pick a better key (hash, or a composite that spreads the hot entity).
- **Salt / split** a hot key across sub-shards (append a bucket suffix), then fan-out
  reads.
- **Cache** the hot key in front of the shard.

Naming the hot-key problem *and* a mitigation unprompted is a strong signal.

---

## Rebalancing

As you add nodes, data must move. Two approaches:

- **Consistent hashing** minimizes movement (only neighbor's keys shift).
- **Fixed large partition count:** create many more partitions than nodes up front and
  distribute partitions over nodes; adding a node just reassigns whole partitions — no
  re-hashing of keys. A clean, widely-used approach.

Avoid strategies where adding one node reshuffles the whole dataset.

---

## Cross-Shard Queries (the tax)

Sharding's cost: a query that isn't scoped to the shard key must **scatter-gather** —
fan out to all shards and merge results. It's slower, and **joins across shards** are
painful (data isn't co-located). Aggregations and secondary-index queries similarly fan
out. So:

> The staff move: "I choose the shard key to do two jobs — spread load evenly and keep
> the common queries single-shard, so most requests touch one node. I use consistent
> hashing (or a fixed large partition count) so adding capacity moves minimal data, and
> I plan for hot keys — a celebrity or a monotonic key — with salting or a cache.
> Cross-shard queries are the tax I design *around*, by shaping the key to the access
> pattern, because scatter-gather and cross-shard joins don't scale."

---

## The Summary

- **Sharding = horizontal partitioning** across nodes for storage/write scale.
- **Strategies:** range (locality, hot-spot risk), hash (even, no locality), consistent
  hashing (minimal reshuffle), directory (flexible, adds a dependency).
- **The shard key is everything** — spread load evenly *and* keep common queries
  single-shard.
- **Hot keys** need salting/caching; **rebalancing** should move minimal data;
  **cross-shard queries** are the tax you design around.
