# Batch Data Processing — MapReduce and Its Descendants

How do you run a simple computation over **terabytes of data on thousands of machines** without
hand-writing the distributed plumbing every time? That question, and Google's 2004 answer to it, is
the origin of the entire "big data" era — and it's the first paper in the
[MIT 6.824](#fu-data-distribution) sequence for good reason: it's the cleanest possible introduction
to distribution, fault tolerance, and locality all at once.

Source: Dean & Ghemawat, **"MapReduce: Simplified Data Processing on Large Clusters"** (OSDI 2004).
https://research.google/pubs/mapreduce-simplified-data-processing-on-large-clusters/

---

## The Core Idea

Google engineers kept rewriting the *same* distributed machinery — partition the data, schedule
work, handle dead machines, move bytes around — just to run computations that were, underneath,
trivial. MapReduce **separates the *what* from the *how*.** You write two pure functions; the
framework does everything else.

```
map    (k1, v1)        → list(k2, v2)     // transform each record independently
reduce (k2, list(v2))  → list(v2)         // combine all values that share a key
```

**Word count** is the "hello world":

```
map(docName, contents):
    for each word w in contents:
        emit(w, "1")

reduce(word, counts):
    emit(word, sum(counts))
```

`map` emits `(word, 1)` per word; the framework **groups every `1` for a given word**; `reduce`
sums them. The identical 30 lines run on one laptop or on 2,000 machines — the programmer never
touches a socket, a thread, or a retry.

> **Why this was revolutionary:** it let engineers *"with no experience of distributed systems"*
> exploit a thousand machines in half an hour. The restriction to two functions is exactly what
> makes automatic parallelization and fault tolerance *possible* — a recurring theme: **a narrower
> model is easier to make robust.**

---

## How It Executes

```
  input split 0 ─┐                                    ┌─► output file 0
  input split 1 ─┼─► [MAP workers] ──shuffle──► [REDUCE workers] ─┤
  input split 2 ─┘   run map(),      sort by     run reduce()     └─► output file 1
                     spill to        key,
                     local disk      group
                        ▲                                  ▲
                        └──────── MASTER assigns tasks, tracks state, ─────┘
                                  reschedules on failure
```

1. Input split into **M pieces** (16–64 MB each); many copies of the program start on the cluster.
2. One copy is the **master**; the rest are **workers**. Master assigns M map + R reduce tasks to
   idle workers.
3. A **map worker** reads its split, runs `map`, buffers `(k, v)` pairs in memory.
4. Buffers spill to **local disk**, partitioned into R regions by `hash(key) mod R`. Master learns
   the locations.
5. A **reduce worker** RPC-reads its partition from every map worker's disk, then **sorts by key**
   to group values (external sort if it won't fit in memory).
6. It runs `reduce` per key, appending output to a final file (in GFS).
7. All tasks done → master wakes the user program.

**The shuffle** — that sort-and-regroup between map and reduce — is the expensive heavy lifting.
It's where "group all values with the same key" physically happens, and it's usually the
bottleneck.

---

## The Three Ideas That Make It *Good*

The paper's real substance isn't the API — it's these three, each a distributed-systems lesson that
recurs everywhere in this repo.

### 1. Fault tolerance via re-execution

The master pings workers; a dead worker's tasks are simply **re-run elsewhere.** Because `map` and
`reduce` are **deterministic**, re-execution yields identical output — so failure handling is just
"do it again."

- Completed **map** tasks are re-run on failure (their output was on the dead machine's *local*
  disk, now unreachable).
- Completed **reduce** tasks are **not** (their output is already in the global file system).
- Atomic commit: tasks write to **private temp files** and **atomically rename** on completion, so a
  duplicate or half-finished execution never corrupts the output.

They once had **80 machines go unreachable mid-job**; the master re-ran their work and the job
finished. *(Same instinct as [Raft's](#fu-data-distribution) "just retry the RPC" and the
copy→verify→switch idempotency in the [Sharded DB HLD](#hld-sharded-database-platform).)*

### 2. Locality — move the computation, not the data

Network bandwidth was the scarce resource. GFS keeps **3 replicas** of each 64 MB block; the master
**schedules each map task on a machine that already holds a replica** of its input. Most data is
read from local disk and consumes **zero network**.

> This is the ancestor of every "process data where it lives" design — Bigtable's tablet locality in
> [Database Scaling](#fu-database-scaling), and why an [LSM memtable](#fu-database-scaling) buffers
> writes locally before flushing.

### 3. Backup tasks — beat the stragglers

A single slow machine (bad disk, CPU contention) drags out the whole job's tail. Near completion,
the master launches **backup copies** of the remaining in-progress tasks; **first to finish wins.**
Costs a few percent more compute; their sort benchmark ran **44% slower without it.**

> The **straggler problem is universal** — it's the same tail-latency disease the
> [LLM Inference Serving](#hld-llm-inference-serving) and
> [Distributed Rate Limiter](#hld-distributed-rate-limiter) HLDs fight. "Redundant work to kill the
> tail" is a pattern worth having a name for.

---

## Refinements Worth Knowing

- **Combiner** — a pre-reduce on the map side. Word count emits thousands of `(the, 1)` per split; a
  combiner sums them **locally** to one `(the, 5000)` before the shuffle. Huge bandwidth savings on
  **skewed (Zipfian)** data. (Works only when reduce is commutative + associative.)
- **Partitioning function** — control which reduce task a key lands in; `hash(hostname(url)) mod R`
  keeps one host's URLs in one output file.
- **Skipping bad records** — a record that deterministically crashes your code is detected (a dying
  worker sends a "last gasp" UDP packet with the record's sequence number) and skipped on
  re-execution. Pragmatic over pure.

---

## Real-World Use Cases

The paper's lasting point: *"many real world tasks are expressible in this model."* The shape —
**map each record independently, then combine by key** — fits an enormous range of problems.

### 1. Search Indexing (MapReduce's original killer app)

Google **rewrote its production web-search indexing** on MapReduce. Input: 20+ TB of crawled docs.
The **inverted index** — `word → list(document IDs)` — is textbook MapReduce: `map` emits
`(word, docID)` per document; `reduce` groups all docIDs for a word. The rewrite dropped one phase
from ~3,800 lines of C++ to ~700, and made the pipeline **operable without babysitting failures.**
Ties directly to [Implement Trie](https://salman9193.github.io/dsa-problems/#strings/implement-trie)
and the [Search Typeahead HLD](#hld-search-typeahead).

### 2. Log & Clickstream Analysis

The daily bread of data engineering. **Count URL access frequency** (`map` emits `(url, 1)` from
request logs, `reduce` sums) — page-view counts, unique visitors, error-rate rollups, funnel
analysis. Any "aggregate a metric across billions of log lines" job is a MapReduce.

### 3. ETL — Reshaping Data at Scale

Extract-Transform-Load: pull raw events, reshape them, load into a warehouse. **Distributed sort**
(the paper's benchmark), format conversion, deduplication, joining datasets by key — the plumbing of
every analytics warehouse. `map` extracts the join key; the shuffle groups matching records; `reduce`
merges them.

### 4. Graph Computations

The **reverse web-link graph** is in the paper: `map` emits `(target, source)` for every link;
`reduce` gathers all sources pointing at a target. PageRank was originally run as **iterated**
MapReduce jobs. This exposed MapReduce's weakness (each iteration re-reads from disk), which is
exactly what spawned Pregel and Spark GraphX.

### 5. Machine Learning at Scale (batch)

**Feature extraction** over huge corpora, computing term vectors, training-data generation, and
batch scoring. The paper lists *"large-scale machine learning"* and the **Google News / Froogle
clustering** as early uses. Modern parallel: distributed feature pipelines feeding
[embeddings and vector search](#fu-embeddings-and-vector-search).

### 6. Data Mining & Reporting

**Google Zeitgeist** (most-popular-queries reports) and **geographic extraction** from web pages
were MapReduce jobs. Any periodic "compute a summary/report over the full dataset" task — top-N
queries, trend detection, aggregate dashboards — fits.

| Domain | `map` emits | `reduce` does |
|--------|-------------|---------------|
| Inverted index | `(word, docID)` | collect docIDs per word |
| Log analytics | `(url, 1)` | sum counts |
| ETL / sort | `(sortKey, record)` | pass through (sorted by shuffle) |
| Web-link graph | `(target, source)` | gather sources per target |
| Term vectors (ML) | `(host, termVector)` | sum & prune vectors |
| Reporting | `(query, 1)` | sum, keep top-N |

**The unifying test:** *can the work be phrased as "transform each record, then combine by key"?* If
yes, it parallelizes and fault-tolerates for free.

---

## Honest Limitations (what 6.824 has you critique)

| Limitation | Consequence | What fixed it |
|------------|-------------|---------------|
| **Single master** | paper *aborts* the job on master failure ("unlikely") | modern schedulers make the master HA via [consensus](#fu-data-distribution) |
| **Batch only** | high throughput, **not** low latency or streaming | Storm/Flink/Kafka Streams for streaming |
| **Everything hits disk** (map→local, reduce→GFS) | fault-tolerant but slow for **iterative** work | **Spark** keeps data in memory → 10–100× on ML/graph iteration |
| **Two-stage rigidity** | complex jobs = many chained MR passes | Spark/Flink DAG engines express multi-stage flows directly |

> **The staff framing:** *"MapReduce made distribution, fault tolerance, and locality free for
> anything expressible as map-then-reduce-by-key — via re-execution, data-local scheduling, and
> backup tasks. Its costs are batch-only latency and disk-per-stage I/O, which is why iterative and
> streaming workloads moved to Spark and Flink. But the mental model — partition, process locally,
> shuffle by key, combine — is still how you reason about any large-scale data job."*

---

## Descendants (the lineage to name)

- **Hadoop** — the open-source MapReduce + HDFS (GFS clone) that democratized it.
- **Spark** — keeps intermediate data **in memory** (RDDs), crushing MapReduce on iterative ML and
  graph work; a superset that still uses map/shuffle/reduce underneath.
- **Flink / Kafka Streams / Storm** — **streaming** successors: process records as they arrive
  rather than in batches (the [streaming vs. batch](https://salman9193.github.io/dsa-problems/#guides/KLEES_ALGORITHM)
  distinction from the DSA side).
- **Dataflow / Beam** — unified batch + streaming, the model Google published *after* learning
  MapReduce's limits.

**The through-line:** every one of these inherited MapReduce's three lessons — **re-execution for
fault tolerance, locality-aware scheduling, redundant work to kill stragglers** — and relaxed its
restrictions (in-memory, streaming, richer DAGs). Understanding the 2004 paper is understanding the
floor the entire modern data stack was built on.

---

## Summary

- **MapReduce = `map` (transform each record) + `reduce` (combine by key)**, with the framework
  handling partitioning, scheduling, failures, and data movement.
- **Master/worker**: M map tasks + R reduce tasks; the **shuffle** (sort-and-group by key) is the
  expensive middle.
- **Three durable ideas:** re-execution (deterministic ⇒ retry = fault tolerance), **locality**
  (compute where the data lives), **backup tasks** (redundant work kills the straggler tail).
- **Fits** indexing, log analytics, ETL, graph, batch ML, reporting — anything phrasable as
  transform-then-combine-by-key.
- **Limits:** single master, batch-only, disk-per-stage → **Spark/Flink** relaxed these, but the
  mental model endures.
