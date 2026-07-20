# Native Scale-Out — Bigtable, Spanner, DynamoDB

The third answer: **don't bolt scale-out onto a single-node database — start from the assumption
that data lives on many machines.** Everything else follows from that.

---

## Bigtable — The Template

Google's 2006 design, and the direct ancestor of **HBase** and (partly) **Cassandra**.

### The Data Model

A sparse, distributed, persistent **sorted map**:

```
(row key, column family:qualifier, timestamp) → value
```

Rows are kept **sorted by row key**, which is the single most important fact about it: it means
**range scans are cheap** and **row key design determines everything** (more below).

### The Architecture

```
   Client ──► (caches tablet locations, then talks DIRECTLY to tablet servers)
                          │
   ┌──────────┐     ┌─────▼──────┐  ┌────────────┐  ┌────────────┐
   │  Master  │     │Tablet svr 1│  │Tablet svr 2│  │Tablet svr N│
   │ assigns  │     │  tablets   │  │  tablets   │  │  tablets   │
   │ tablets, │     └─────┬──────┘  └─────┬──────┘  └─────┬──────┘
   │ balances │           └───────────────┼───────────────┘
   └────┬─────┘                           ▼
        │                    ╔═══════════════════════════╗
   ┌────▼─────┐              ║  GFS / Colossus            ║
   │  Chubby  │  lock svc,   ║  (SSTables + commit logs)  ║
   │          │  master      ╚═══════════════════════════╝
   └──────────┘  election
```

**Three things to notice:**

1. **A table is split into *tablets* — contiguous row-key ranges.** It starts as one tablet and
   **auto-splits as it grows**. Partitioning is range-based and automatic; there is no shard key to
   choose and no resharding project.

2. **Tablet servers own no storage.** SSTables live in the distributed filesystem. So a tablet
   server failure means **reassigning tablets, not moving data** — the same decoupling Aurora
   arrived at from the other direction.

3. **The master is not in the data path.** Clients cache tablet locations (found through a
   three-level hierarchy: Chubby → root tablet → METADATA tablets → user tablets) and then talk
   straight to tablet servers. The master can be down and reads/writes continue.

### LSM-Trees — Why Writes Are Fast

Bigtable stores data as **SSTables** (immutable sorted files) with an in-memory **memtable**:

```
write → commit log (sequential) + memtable (in memory)     ← no random disk I/O
memtable full → flush to a new immutable SSTable
periodically → compact SSTables together
read → merge memtable + relevant SSTables (bloom filters skip most)
```

**Writes become sequential appends.** That's the LSM-tree bargain (O'Neil et al., 1996): **fast
writes and write amplification via compaction**, versus a B-tree's in-place updates and read
efficiency. It's why LSM-based stores dominate write-heavy workloads — and why they need compaction
tuning, which is the operational cost you're accepting.

> Contrast with [Indexing](#fu-storage-and-databases): B-trees optimise reads and in-place updates;
> LSM-trees optimise writes and pay at read/compaction time.

### Row Key Design Is the Whole Game

Because rows are sorted, the row key determines both locality **and** hotspots:

| Row key | Result |
|---------|--------|
| `timestamp` | **all writes hit the last tablet** — a hotspot; the cluster is idle |
| `userId#timestamp` | spreads writes; keeps one user's history contiguous ✅ |
| `reverse(domain)/path` | groups a site's pages together (the original web-index use) |

**Sequential keys are the classic Bigtable mistake.** The fix — salting, hashing, or field-swapping
the prefix — trades away some range-scan ability for spread. Same trade-off as any
[shard key choice](#fu-data-distribution), just with sorting making it sharper.

---

## Spanner — Adding Transactions Back

Bigtable gave up cross-row transactions and SQL. Spanner (2012) gets them back at global scale:

- **Sharded across Paxos groups.** Each shard is a replicated state machine, so the *shard itself*
  is highly available — not a single primary with followers.
- **TrueTime** — an API exposing **clock uncertainty as an interval** `[earliest, latest]`, backed
  by GPS and atomic clocks. By **waiting out the uncertainty** before committing, Spanner assigns
  globally meaningful timestamps and achieves **external consistency** (linearizability) for
  distributed transactions.
- Result: distributed SQL with strong consistency — the thing the NoSQL generation said you had to
  give up.

> **The insight worth stealing:** everyone else treats clock skew as an error to be hidden. Spanner
> **exposes uncertainty as a first-class value** and pays for correctness by waiting. Making
> uncertainty explicit, then budgeting for it, is a broadly useful engineering pattern.
>
> The cost is real: commit latency includes a **commit wait** on the order of the clock uncertainty,
> and it needs specialised hardware in every datacenter — which is why "just build Spanner" isn't
> advice most organisations can take.

---

## DynamoDB — Managed, Hash-Partitioned, Predictable

- **Hash partitioning** on the partition key; optional sort key gives local ordering.
- **Scaling is automatic and invisible** — no instances to size.
- **Single-digit-ms latency** at effectively any scale, with **provisioned or on-demand capacity**.
- Deliberately **restricted query model**: you query efficiently by key, and secondary access needs
  explicit GSIs/LSIs.

**The philosophy is the interesting part:** DynamoDB makes the *expensive* operations
**impossible rather than slow**. You cannot write an accidental full-table join, so you cannot
accidentally take production down with one query. That's the opposite of the SQL bargain — less
expressive power in exchange for **predictable** performance.

Its ancestor, **Dynamo** (2007), introduced consistent hashing, vector clocks, and quorum reads/
writes to a generation of systems — though modern DynamoDB is a different implementation.

---

## The Shared Structure

Strip away the branding and all three do the same four things:

| Concern | Bigtable | Spanner | DynamoDB |
|---------|----------|---------|----------|
| **Partitioning** | range (tablets), auto-split | range, auto-split | **hash** on partition key |
| **Replication** | via the filesystem | **Paxos** per shard | quorum across AZs |
| **Storage** | separate (Colossus) | separate (Colossus) | managed internally |
| **Transactions** | single-row | **distributed, external consistency** | single-item; limited multi-item |
| **Query model** | key + range scan | **SQL** | key-based, GSIs |

**And all of them separate compute from storage, auto-partition, and refuse to let you write a
query that can't scale.** Those three properties *are* "cloud-native database."

---

## References

- **F. Chang et al. (2006), "Bigtable: A Distributed Storage System for Structured Data,"**
  *OSDI '06*, pp. 205–218 (Best Paper). Journal version: *ACM TOCS* 26(2), 2008,
  DOI: [10.1145/1365815.1365816](https://doi.org/10.1145/1365815.1365816).
- **J. C. Corbett et al. (2012), "Spanner: Google's Globally-Distributed Database,"** *OSDI '12*,
  pp. 251–264. Journal version: *ACM TOCS* 31(3), 2013. Source of TrueTime and external consistency.
- **P. O'Neil, E. Cheng, D. Gawlick & E. O'Neil (1996), "The Log-Structured Merge-Tree
  (LSM-tree),"** *Acta Informatica* 33(4):351–385.
  DOI: [10.1007/s002360050048](https://doi.org/10.1007/s002360050048).
- **G. DeCandia et al. (2007), "Dynamo: Amazon's Highly Available Key-value Store,"** *SOSP '07* —
  consistent hashing, vector clocks, quorums.
