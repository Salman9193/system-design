# Text Segmentation Service — Scaling

Segmentation is **CPU-bound, stateless, and embarrassingly parallel** — but the *dictionary* is
state, and that's what makes scaling non-trivial.

---

## The Scaling Profile

| Property | Consequence |
|----------|-------------|
| O(n) in text length, pure function | scales **horizontally, linearly** |
| No shared mutable state | no coordination, no locks |
| 300 MB dictionary resident per process | **memory**, not CPU, caps pod density |
| Dictionary rarely changes, read constantly | heavy caching + immutable artifacts win |

**The bottleneck is memory per pod, not throughput.** That inverts the usual scaling instinct.

---

## Horizontal Scaling

- **Stateless pods behind a load balancer** — autoscale on CPU.
- **Memory-mapped dictionary** so co-located pods share physical pages (300 MB × N ⇒ 300 MB).
- **Tenant-affinity routing** (consistent hash on `tenantId`) so overlays cache-hit — while
  keeping the service *logically* stateless, so any pod can still serve any tenant on failover.

---

## Vertical / Per-Process Efficiency

| Lever | Gain |
|-------|------|
| **Double-array Trie** instead of prefix hash map | ~3–5× less dictionary memory ⇒ more pods/host |
| **Compiled artifact** (serialized map) | boot time seconds → ms |
| **Batch API** (many texts per request) | amortises RPC overhead; big win for indexing |
| **Off-heap / memory-mapped dictionary** | removes 300 MB from GC scope — **major** JVM latency win |
| **JIT warmup before ready** | first-request latency ~10× better |

**The GC point is worth stating:** a 2M-entry `HashMap<String,Integer>` is millions of live objects
that every GC must trace. Moving it **off-heap** (memory-mapped, primitive-encoded) can matter more
for p99 than any algorithmic change.

---

## Batch vs. Interactive: Separate Everything

| | Batch (indexing) | Interactive (query) |
|---|---|---|
| Optimise for | **throughput** | **p99 latency** |
| Deployment | library inside Spark/Beam executors | co-located library or service pods |
| Caching | **off** (documents are unique) | on (queries are skewed) |
| Version | **pinned for the whole job** | pinned per index segment |
| Resources | preemptible/spot, large heaps | reserved, latency-tuned |

They share the engine and **nothing else** — separate pools, separate scaling policies, separate
priority classes. Mixing them is how a reindex takes down search.

---

## Scaling the Dictionary Itself

The base dictionary grows with language coverage and tenants:

- **Shard by language, not by tenant** — a pod serving Chinese needn't hold Japanese or Thai.
  Route by detected/declared language. This keeps each pod's resident set bounded as coverage grows.
- **Tenant overlays stay small by quota** — enforced at build time.
- **Lazy overlay loading with LRU** — thousands of tenants, only the active ones resident.

---

## Cost Notes

- Segmentation is **cheap**: CPU-bound µs-per-sentence work. The dominant costs are **memory
  footprint** and **reindexing compute**, not segmentation itself.
- **A dictionary update that forces a full reindex of 10⁹ docs can cost more than months of
  serving.** That economic fact is precisely why dual-tokenization and staged rollout exist — the
  design choice is driven by reindex cost, not by segmentation cost.

---

## Why the Dictionary Rollout Is a Database Pattern

The dictionary-versioning protocol in this design isn't specific to NLP — it's the **same protocol
used to reshard a live database**:

```
copy  →  catch up  →  VERIFY  →  switch  →  retire
```

| Here | In a sharded database |
|------|----------------------|
| build the new dictionary artifact | bulk-copy rows to new shards |
| dual-tokenize during migration | tail the change stream to catch up |
| **canary recall eval (blocking)** | **checksum source vs target (blocking)** |
| flip the version pointer | atomically update the shard map |
| keep the old artifact for rollback | keep the source read-only for a window |

**The invariant is identical: never cut over on faith, and make the step before the cutover
reversible.** See [Sharded Database Platform → Deep Dives](#hld-sharded-database-platform) for the
database version, and [Database Scaling](#fu-database-scaling) for where it sits in the ladder.
