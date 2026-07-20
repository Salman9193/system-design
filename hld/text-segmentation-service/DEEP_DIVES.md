# Text Segmentation Service — Deep Dives

## 1. Why a Dictionary Update Is an Outage (the recall-loss failure)

The failure nobody catches in review:

```
Day 1:  dictionary v42.  Indexer tokenizes  北京大学  →  ["北京大学"]
        Index posting list:  "北京大学" → [doc_7]

Day 2:  someone adds a word; dictionary v43 changes the DP's chosen path.
        Query "北京大学" now tokenizes →  ["北京", "大学"]
        Lookup "北京" → …   "大学" → …    doc_7 is NOT in either list.

Result: doc_7 is unreachable. No error. No alert. Recall silently drops.
```

**Why it's so dangerous:** the system is *healthy* by every metric — latency fine, error rate zero,
no exceptions. Only a **recall regression** reveals it, and only if you're measuring recall against
a labelled set.

**Mitigations, in order of strength:**

1. **Version pinning + reindex** (the real fix). The dictionary is index schema; changing it means
   a backfill.
2. **Dual-tokenize at index time** — index both `v42` and `v43` tokens during migration so either
   query tokenization hits. Doubles index size temporarily; enables zero-downtime cutover.
3. **Query-side expansion** — segment the query *both* ways and OR the results. Cheap, but pollutes
   relevance scoring.
4. **Canary recall tests** — a labelled query→doc set run against every dictionary candidate,
   **blocking** the rollout on recall drop. This is the guardrail that makes the whole thing safe.

---

## 2. Dictionary Rollout: Staged and Reversible

Because a bad dictionary silently degrades quality, rollout is treated like a code deploy:

```
build → checksum → canary recall eval (BLOCKING) → 1% → 10% → 50% → 100%
                          │                            │
                          └─ fail → reject             └─ recall/CTR regression → instant rollback
```

- **Rollback is a pointer flip**, not a rebuild — old artifacts are immutable and retained.
- **Pods watch a version manifest**, not a mutable file; a rollout changes the manifest and pods
  converge.
- **Never hot-swap in place.** Load the new dictionary **alongside** the old, flip an
  `AtomicReference`, then drain the old one once in-flight requests finish. Segmentation is a pure
  read against an immutable map, so the swap needs no locks and no request ever sees a half-loaded
  dictionary.

---

## 3. Cold Start & Warmup

A pod loading 350k words at boot is unavailable for seconds, and a slow pod under autoscale-storm
conditions cascades.

| Technique | Effect |
|-----------|--------|
| **Compiled artifact** (serialized prefix map) | parse seconds → **load milliseconds** |
| **Memory-mapped file** | pages fault in lazily; several pods on a host share page cache |
| **Readiness gate** | pod reports ready only *after* the dictionary is resident — never take traffic cold |
| **Pre-pulled artifact in the image / init container** | removes network from the critical boot path |
| **JIT warmup** (JVM) | run a synthetic corpus before serving; first-request latency drops ~10× |

**The subtle one:** memory-mapping means multiple pods on one host share the **same physical
pages** for the base dictionary — turning "300 MB × N pods" into "300 MB, once." That single choice
often decides pod density.

---

## 4. Caching — and Why the Obvious Cache Is Wrong

Tempting: cache `text → tokens`. Mostly wrong.

- **Query path:** the head of the query distribution is *extremely* skewed, so a small LRU on
  `(text, dictVersion, mode)` gets a high hit rate. **The version must be in the key** — otherwise
  a dictionary rollout serves stale tokenizations, which is exactly the bug we're preventing.
- **Batch path:** documents are near-unique. Caching is **pure overhead** — disable it.
- **What to cache instead:** the *compiled dictionary artifact* (huge, reused constantly) and
  **tenant overlays**. That's where the reuse actually is.

**Rule: cache the thing that's expensive to build and reused often (the dictionary), not the thing
that's cheap to compute and rarely repeated (a segmentation).**

---

## 5. Tenant Isolation

Threats: one tenant uploads a 10M-word dictionary; another sends 100 KB strings at 10k QPS.

- **Quotas** on dictionary size, entry count, and word length — enforced **at upload/build time**,
  not at request time.
- **Overlay memory cap** with LRU eviction; a huge overlay evicts *itself*, not the base dictionary.
- **Per-tenant rate limits + concurrency caps** (see [Rate Limiter](#lld-rate-limiter)).
- **Input length cap** — segmentation is linear, but a 10 MB string still monopolises a thread.
- **Bulkheads:** separate pools for batch and interactive traffic so a batch job can't starve queries.

---

## 6. Determinism Across Replicas (the thing that makes pinning meaningful)

Version pinning is worthless if two pods on the same version disagree. Sources of divergence to
eliminate:

| Source | Fix |
|--------|-----|
| Floating-point summation order in the DP | fixed iteration order over `DAG[i]`; **never** parallelise the inner max |
| Hash-map iteration order | never iterate the dictionary during segmentation — only point lookups |
| Locale/normalization differences | normalize (NFKC) at ingest, pin the Unicode version |
| HMM randomness | Viterbi is deterministic; **ties must break deterministically** (fixed state order) |
| Library/JDK version | pin in the image; treat a JDK bump like a dictionary bump — canary it |

**Tie-breaking is the sneaky one.** Two segmentations with *identical* log-probability must resolve
the same way on every replica — so `>` vs `>=` in the DP's max is a **correctness** decision, not a
style choice.
