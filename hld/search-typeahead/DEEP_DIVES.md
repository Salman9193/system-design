# Search Typeahead — Deep Dives

This is where the interview is won or lost. The deep dives get ~25 of the 45
minutes and are the senior/staff boundary. Each one opens a black box the interviewer will
push you into. Drive these proactively.

---

## Deep Dive 1 — The Trie, and Why Top-K Lives on the Node

### The naive Trie problem
A basic Trie answers "is this a prefix?" in O(prefix length). But autocomplete needs
the **top-K most popular completions** below a prefix. Naively, that means: walk to
the prefix node, then **traverse the entire subtree** collecting all completions and
sorting by score. For a prefix like "a", that subtree could be millions of nodes —
far too slow for a <50ms budget at 1M QPS.

### The fix: precompute top-K at every node
Store, at each Trie node, the **top-K completions for the subtree rooted there**,
already sorted. Now a query is:
1. Walk the prefix — O(prefix length), typically < 20 steps.
2. Return the node's stored `topK` — O(K).

No subtree traversal at request time. This is the single most important
optimization in the system.

### How it's built (bottom-up merge)
During the offline build, compute each node's top-K from its children:
```
topK(node) = merge(
    node's own terminal query (if any),
    topK(child) for every child
).take(K)
```
A leaf's top-K is just its own query. A parent merges its children's top-K lists
(each already ≤ K, sorted) — a K-way merge, take the top K. This is the same k-way
merge as merging sorted lists (LeetCode #23), done bottom-up over the Trie.

### The cost (name this trade-off)
- **Memory:** every node now stores up to K (query, score) pairs → the index
  inflates ~5–10×. For ~20 GB of Trie, that's the price of O(prefix) serving.
- **Build time:** the merge is done once per rebuild, offline, so it doesn't touch
  the hot path.

> "I'm trading memory and build-time compute for serving latency — storing top-K on
> every node turns a subtree traversal into a single lookup. For a read-dominated
> system at 1M QPS with a 50ms budget, that trade is clearly correct."

### The "build it yourself" moment
If the interviewer says "set the Trie library aside," you can show the node struct,
the walk, and the bottom-up merge — that's the mechanism. This is exactly the
"inside the tools" test from `frameworks/STAFF_VS_SENIOR.md`.

---

## Deep Dive 2 — Ranking

### What "popularity" means
The score driving top-K is not a raw count — it's a **time-decayed popularity**.
A query that was huge last year but dead now should not outrank a currently trending
one.

### Time decay
Apply exponential decay so recent searches weigh more:
```
score = Σ over each occurrence of  e^(-λ · age)
```
where `age` is how long ago the search happened and `λ` tunes the half-life. Compute
this in the aggregation stage over a sliding window (e.g. last 7–30 days).

### Beyond raw popularity (staff-level extensions)
- **Personalization:** blend global score with the user's own history (a user who
  searches "python" often should see it ranked higher). Store a small per-user
  signal and merge at serving time — but this breaks the edge cache (results are no
  longer identical for everyone), so it's a real trade-off between relevance and
  cacheability.
- **Context:** location and time-of-day shift rankings ("weather" ranks higher in
  the morning).
- **Freshness boost:** newly trending queries get a temporary multiplier so they
  surface before their raw count would justify.

### The trade-off to name
> "Personalization improves relevance but destroys the edge cache's hit rate,
> because suggestions are no longer shared across users. I'd cache the *global*
> top-K at the edge and blend in personalization at the service layer only for
> logged-in users — keeping the cache effective for the anonymous majority."

---

## Deep Dive 3 — Caching & the Hot Path

### Layered caching
1. **Client cache** — recent prefixes; backspacing is free.
2. **Edge/CDN cache** — the big win. Common prefixes ("a"–"amaz") have identical
   global results and cache beautifully near the user. Target: the large majority of
   requests served here.
3. **Service result cache (Redis)** — `prefix → top-K` for hot prefixes that missed
   the edge.
4. **In-memory Trie** — the origin, hit only on a full cache miss.

### Cache strategy (see `fundamentals/caching.md`)
- **TTL-based**, aligned to the rebuild cycle. If the Trie rebuilds hourly, a
  ~1-hour TTL bounds staleness to one rebuild.
- **Invalidate on rebuild:** when new shards ship, bump a version in the cache key
  (`v42:new y`) so old entries orphan naturally — no need to enumerate keys.

### Failure modes on the hot path
- **Thundering herd** when a hot prefix's cache entry expires: thousands of misses
  hit the Trie at once. → Request coalescing (one recompute, others wait) +
  probabilistic early refresh.
- **Hot prefix** ("a" gets enormous traffic): replicate that shard / keep short
  prefixes in an in-process cache on every service node.

---

## Deep Dive 4 — Sharding the Index

~20 GB doesn't fit on one node with headroom, and 1M QPS needs horizontal spread.

### Shard by prefix
Partition the Trie by first character(s): shard A handles "a*", shard B "b*", etc.
Route a query to its shard by prefix.
- **Pro:** simple routing; a query only ever touches one shard.
- **Con:** **skew** — "s" and "t" prefixes are far more common than "z" or "q", so
  those shards are hotter.

### Fixing skew
- Split hot prefixes further ("sa*", "sb*" as separate shards).
- Use **consistent hashing** on the prefix so shards can be added/rebalanced without
  a full remap when a shard gets hot.
- Replicate hot shards for read scale.

> "I'd shard by prefix for routing simplicity, but call out the skew: real query
> distributions are Zipfian, so I'd split the hottest prefixes into their own shards
> and replicate them. Consistent hashing lets me add capacity for a hot shard
> without remapping everything."

---

## Deep Dive 5 — Near-Real-Time Trending (the hard follow-up)

The v1 rebuilds periodically, so a brand-new viral query ("[breaking news event]")
won't appear until the next rebuild. The interviewer will often ask: *"How do you
surface trending queries within seconds?"*

### Approach: a fast layer over the slow index
- Keep the periodically-rebuilt Trie as the base.
- Add a **streaming layer**: aggregate the last few minutes of search logs in near
  real time (a streaming job maintaining approximate counts, e.g. Count-Min Sketch
  for memory efficiency).
- At serving time, **merge** the base top-K with the streaming trending set for that
  prefix.

### The trade-off
> "This adds a second ranking source and merge step on the serving path, costing
> some latency and complexity. I'd only merge for prefixes with active trending
> signal, and use a Count-Min Sketch to track trending counts in bounded memory
> rather than exact counts — trading a little accuracy for the ability to keep
> millions of candidate queries' counts in RAM."

This connects to the "implement the stream processing yourself" push — you can
describe the sketch and the merge, not just name a stream processor.
