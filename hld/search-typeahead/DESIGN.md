# Search Typeahead — High-Level Design

The design splits cleanly into two paths that operate at completely different
timescales: a **serving path** (microseconds-to-milliseconds, per keystroke) and a
**data/build path** (minutes-to-hours, offline). Keeping these separate is the
central architectural decision.

---

## The Two-Path Architecture

```
                            SERVING PATH  (hot, <50ms, ~1M QPS)
   ┌────────┐    ┌─────────┐    ┌──────────────┐    ┌────────────────────┐
   │ Client │───▶│  CDN /   │───▶│ Suggest      │───▶│ In-memory Trie      │
   │(browser│    │  Edge    │    │ Service      │    │ shard (top-K/prefix)│
   │ /app)  │◀───│  cache   │◀───│ (+ result    │◀───│                     │
   └────────┘    └─────────┘    │   cache)     │    └────────────────────┘
                                 └──────────────┘
                                        ▲
                                        │ periodic push of rebuilt shards
                                        │
                            DATA / BUILD PATH  (cold, offline)
   ┌───────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────┐
   │ Search    │──▶│ Log ingest   │──▶│ Aggregation   │──▶│ Trie builder │
   │ query     │   │ (message     │   │ (count queries│   │ (compute     │
   │ logs      │   │  queue)      │   │  over window) │   │  top-K/node) │
   └───────────┘   └──────────────┘   └───────────────┘   └──────────────┘
```

---

## Serving Path (the hot path)

The entire hot path is designed to be a **lookup, never a computation**.

### 1. Client
Debounces keystrokes (waits ~50–100ms after typing stops) to avoid a request per
character. Caches recent prefixes locally so backspacing doesn't re-hit the server.

### 2. CDN / Edge cache
The first and most important optimization. A huge fraction of prefixes are common
("a", "am", "ama", "amaz"...). Their top-K results are identical for everyone (in
the global, non-personalized version), so they cache extremely well at the edge,
close to the user. **Most requests should never reach the origin.**

### 3. Suggest Service
Stateless service that, on a cache miss, routes the prefix to the correct index
shard, fetches the precomputed top-K, and returns it. It also holds a
**result cache** (Redis) of `prefix → top-K` for hot prefixes that missed the edge.

### 4. In-memory Trie shard
The heart of the system. Each shard holds a Trie for a range of prefixes. **Crucially,
each Trie node stores its own precomputed top-K completions** — so answering a query
is: walk the prefix (O(prefix length)), then return the top-K already stored at that
node. No traversal of the subtree at request time. (The deep dive covers exactly how
this is built and why.)

---

## Data / Build Path (the cold path)

This path turns raw search logs into ranked, precomputed Tries — asynchronously,
off the hot path.

### 1. Log ingestion
Every executed search is logged as a popularity signal and pushed onto a durable
message queue (Kafka-style partitioned log). This decouples the firehose of search
traffic from the aggregation stage.

### 2. Aggregation
A batch or streaming job counts query frequencies over a time window (e.g. last 7
days, with time-decay so recent searches weigh more). Output: `query → score`.

### 3. Trie builder
Takes the scored queries and builds the Trie, computing the **top-K completions for
every node** bottom-up (a node's top-K is merged from its children's top-K plus its
own terminal query). The built Trie is partitioned into shards.

### 4. Shard distribution
The freshly built shards are pushed to the serving nodes, which hot-swap the old
Trie for the new one atomically. This is how new/trending queries eventually appear
— on the next rebuild cycle.

---

## Data Model

**Serving side (in-memory Trie node):**
```
TrieNode {
    children: Map<char, TrieNode>
    topK:     List<(query, score)>   // PRECOMPUTED, sorted desc, length K
    isEnd:    boolean
}
```

**Build side (aggregated store):**
```
query_stats {
    query:  string   (primary key)
    score:  double   (time-decayed popularity)
    updated_at: timestamp
}
```

The critical modeling decision: **top-K is stored on the node, not computed from the
subtree at query time.** This trades build-time work and memory for O(prefix)
serving latency — exactly the right trade for a read-dominated, latency-critical
system.

---

## API

```
GET /suggest?prefix={p}&limit={k}

200 OK
{
  "prefix": "new y",
  "suggestions": [
    {"query": "new york",       "score": 0.98},
    {"query": "new york times", "score": 0.91},
    {"query": "new years",      "score": 0.85}
  ]
}
```

- Idempotent GET → cacheable at every layer (edge, service, client).
- `limit` capped server-side (e.g. max 10) to bound payload.
- Empty/whitespace prefix returns trending/default suggestions.

---

## Why This Shape

Every decision traces back to the binding constraint (latency) and the load shape
(read-dominated, keystroke-level):

- **Precompute top-K per node** → serving is a lookup, not a subtree traversal.
- **Two paths** → the heavy ranking work never touches the hot path.
- **Edge cache** → most of ~1M QPS is absorbed before reaching the origin.
- **Sharded Trie** → the ~20 GB index scales horizontally.
- **Periodic rebuild** → freshness is traded for serving simplicity (and the
  deep-dive shows how to add near-real-time on top).

The deep dives (`DEEP_DIVES.md`) go inside the Trie construction, the ranking, and
the caching — which is where the interviewer will spend most of the time.
