# Search Typeahead — Trade-offs

At staff you're expected to name what you gave up for every major decision, unprompted.
This file catalogs the trade-offs baked into the design so you can surface them
deliberately. A design presented without its trade-offs reads as shallow, however
correct it is.

---

## The Central Trade-off: Precompute vs Compute-on-Read

| | Precompute top-K per node (chosen) | Compute on read |
|--|-----------------------------------|-----------------|
| Serving latency | O(prefix) — a lookup | O(subtree) — traverse + sort |
| Memory | High — K pairs per node (~5–10× inflation) | Low — just the Trie structure |
| Freshness | Bounded by rebuild cycle | Always current |
| Build cost | Heavy offline merge | None |

**Decision:** precompute. For a read-dominated (1M QPS), latency-critical (<50ms)
system, paying memory and build cost to make serving a lookup is clearly right.
Compute-on-read would blow the latency budget on common prefixes.

---

## Freshness vs Simplicity

| | Periodic rebuild (chosen for v1) | Real-time updates |
|--|----------------------------------|-------------------|
| New/trending queries appear | After next rebuild (minutes–hours) | Within seconds |
| Serving complexity | Simple — static Trie, hot-swapped | Complex — merge streaming layer |
| Correctness | Easy to reason about | Race conditions, approximate counts |

**Decision:** start with periodic rebuild; add a streaming trending layer as a
deliberate extension (Deep Dive 5). Trading freshness for simplicity in v1 is a
scoping call — and adding the fast layer on top when asked shows range.

---

## Global vs Personalized Ranking

| | Global (chosen as default) | Personalized |
|--|---------------------------|--------------|
| Relevance | Same for everyone | Tailored per user |
| Edge cacheability | Excellent — results shared | Poor — results are per-user |
| Latency | Fast (edge-served) | Slower (blend at service layer) |
| Complexity | Low | Higher — per-user signal store |

**Decision:** global by default so the edge cache stays effective for the anonymous
majority; blend personalization only at the service layer for logged-in users. This
is the sharpest trade-off in the design — personalization directly fights
cacheability.

---

## Sharding by Prefix vs by Hash

| | Shard by prefix (chosen) | Shard by hash of query |
|--|--------------------------|------------------------|
| Routing | Trivial — prefix picks the shard | A prefix's completions scatter across shards |
| Query fan-out | One shard per query | Must gather from all shards |
| Skew | High — Zipfian prefix distribution | Even |

**Decision:** shard by prefix so each query hits exactly one shard, then handle the
resulting skew explicitly (split hot prefixes, replicate, consistent hashing).
Hashing the query would spread load evenly but shatter each prefix's completions
across every shard — unacceptable for a prefix-lookup workload.

---

## Consistency vs Latency

**Decision:** eventual consistency. Suggestions do not need to be perfectly current —
a slightly stale top-K is fine. This buys aggressive caching and no coordination on
the hot path. Named explicitly: "I'm accepting up to one-rebuild-cycle of staleness
in exchange for edge-cacheable, sub-50ms reads."

---

## Exact vs Approximate Counts (trending layer)

**Decision:** approximate. For the near-real-time trending layer, use a Count-Min
Sketch rather than exact counters. You trade a small, bounded over-count error for
the ability to track millions of candidate queries' frequencies in fixed memory.
For ranking (where relative order matters more than exact counts), the approximation
is acceptable.

---

## The Meta-Point

Every one of these trade-offs points the same direction the requirements did:
**latency and read-scale win, at the cost of freshness, memory, and some accuracy.**
That coherence — every decision serving the same binding constraint — is itself the
staff-level signal. When you can say "I keep choosing latency over freshness because
that's what this system's usage demands," you're demonstrating judgment, not
recall.
