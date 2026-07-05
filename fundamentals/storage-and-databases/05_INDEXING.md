# Indexing

An index is the single most important tool for making reads fast — and a tax on every
write. Understanding *how* indexes work, and the **B-tree vs LSM-tree** split, is a
common "open the box" moment: interviewers ask why a query is slow, or why one database
handles writes better than another, and the answer is usually the index structure.

---

## The Problem Indexes Solve

Without an index, finding rows matching a condition means scanning the **entire table**
— O(n), catastrophic at scale. An **index** is an auxiliary data structure that maps a
column's values to the locations of matching rows, turning a full scan into a fast
lookup — much like the index at the back of a book lets you jump to a page instead of
reading every one.

The trade: indexes **speed up reads** but **slow down writes** (every insert/update must
also update the indexes) and **consume space**. So you index deliberately.

---

## B-tree — The Read-Optimized Default

The **B-tree** (specifically B+tree) is the classic index structure and the default in
relational databases.

- A balanced, sorted tree: lookups, insertions, and deletions are **O(log n)**.
- Because it keeps keys **sorted**, it's excellent for both **point lookups** ("id =
  42") and **range queries** ("created between X and Y", "ORDER BY", "prefix matches").
- **Read-optimized:** reads are fast and predictable; writes are decent but must
  maintain the tree structure in place.

B-trees are why relational databases handle mixed read/range workloads so well.

---

## LSM-tree — The Write-Optimized Alternative

The **Log-Structured Merge-tree (LSM)** is built for **write throughput**, used by
wide-column and many NoSQL stores (Cassandra, RocksDB, LevelDB).

- Writes go to an in-memory buffer (fast, sequential), which is periodically flushed to
  disk as sorted immutable files; background **compaction** merges those files.
- Writes are **sequential appends** rather than in-place updates, so write throughput is
  very high.
- The cost is **read amplification** — a read may check several files — mitigated by
  Bloom filters and compaction. So LSM trades some read cost for large write gains.

---

## B-tree vs LSM-tree

| | B-tree | LSM-tree |
|--|--------|----------|
| Optimized for | Reads, range queries | Writes, high ingest |
| Writes | In-place, moderate | Sequential appends, very high throughput |
| Reads | Fast, predictable | Can touch multiple files (read amplification) |
| Typical home | Relational databases | Wide-column / NoSQL (Cassandra, RocksDB) |
| Choose when | Read-heavy, range-heavy | Write-heavy, huge ingest |

> The staff move: "The index structure is the read/write trade-off in disguise. B-trees
> keep data sorted for fast point and range reads, which is why relational databases use
> them. LSM-trees turn writes into sequential appends for huge write throughput at the
> cost of read amplification, which is why write-heavy stores like Cassandra use them.
> So if the workload is write-firehose time-series, I lean LSM; if it's read- and
> range-heavy, B-tree."

---

## Index Concepts Worth Naming

- **Primary vs secondary index** — the primary index is on the primary key; secondary
  indexes are on other columns you query.
- **Composite (multi-column) index** — indexes several columns together; **column order
  matters** (an index on (a, b) helps queries filtering on a, or a-and-b, but not b
  alone).
- **Covering index** — contains all columns a query needs, so the query is answered
  **from the index alone** without touching the table — a big speedup.
- **Unique index** — enforces uniqueness and speeds lookups.

---

## The Cost — Don't Over-Index

Every index must be updated on every write to the indexed columns, so indexes **slow
writes and consume storage**. Over-indexing a write-heavy table can hurt more than it
helps. The discipline:

- Index the columns you **filter, sort, join, or look up** on — driven by actual access
  patterns.
- Don't index everything "just in case."
- On write-heavy tables, be especially sparing.

---

## The Summary

- **Indexes make reads fast (O(log n)) at the cost of slower writes and space.**
- **B-tree** = sorted, read- and range-optimized — the relational default.
- **LSM-tree** = append-optimized for massive write throughput, at some read cost — the
  NoSQL/write-heavy choice.
- **Index by access pattern** (composite order matters; covering indexes avoid table
  lookups), and **don't over-index** — every index taxes writes.
