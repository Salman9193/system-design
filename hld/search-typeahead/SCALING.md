# Search Typeahead — Scaling, Multi-Region & Cost

The operational-reality file: how the system scales geographically, what it costs,
and how it evolves. At staff these are expected without being asked — owning the system
end-to-end means owning its global footprint and its bill.

---

## Multi-Region

### Why it matters here
The binding constraint is latency (<50ms). A cross-region round trip is ~150ms (see
`fundamentals/capacity-estimation.md`) — that alone blows the budget. So **serving
must be regional**: users hit an edge and origin in their own region.

### The design
- **Replicate the built Trie to every region.** The Trie is derived, read-only
  serving data, so replication is straightforward: build once (or per-region), ship
  the artifact to all regions.
- **Edge caches are inherently regional** — CDNs serve from the nearest PoP.
- **The build pipeline can be centralized** (one global aggregation of search logs)
  or regionalized (per-region trending). A hybrid is common: global base ranking +
  regional trending overlay, since trending queries are often location-specific.

### The trade-off
> "Serving is fully regional to hold the latency budget — every region has its own
> edge, suggest service, and Trie replica. The build can stay centralized for the
> global ranking, with a per-region trending overlay, because what's trending in one
> country often isn't in another. The cost is replicating ~20GB of Trie to every
> region on each rebuild, which is cheap relative to the latency win."

### Consistency across regions
Eventual. Regions may briefly disagree on rankings during a rebuild rollout — which
is completely fine for suggestions. No cross-region coordination on the hot path.

---

## Scaling Dimensions

### Scaling reads (the main axis)
- **Edge cache** is the primary scaler — it absorbs the bulk of ~1M QPS before the
  origin. More traffic → more edge capacity, which CDNs provide elastically.
- **Stateless suggest service** scales horizontally — add instances behind the load
  balancer.
- **Trie shard replicas** scale read capacity for cache-miss traffic.

### Scaling the index size
- As the query corpus grows, add shards (finer prefix partitioning).
- Consistent hashing on the prefix lets you add a shard for a hot range without
  remapping everything.

### Scaling the build
- The offline pipeline scales with search volume independently of serving. It's a
  batch/streaming aggregation — parallelize the count and the Trie build by prefix
  range.

---

## Cost Reasoning (the staff differentiator)

Naming the expensive parts and how you'd control them is a staff signal. The costs
here:

### Memory is the dominant cost
Precomputing top-K per node inflates the Trie ~5–10×. Replicated across regions and
shard replicas, that memory footprint multiplies.
- **Control:** tune K (do we need top-10 or is top-5 enough?); prune rarely-queried
  branches from the serving Trie (keep them only in the build corpus); use a compact
  Trie representation (e.g. a succinct/DAWG structure) to shrink the footprint.

### Edge/CDN egress
Serving ~1M QPS of small responses from the edge has bandwidth cost, but responses
are tiny (a few hundred bytes) and highly cacheable, so this is modest.
- **Control:** high cache-hit rate keeps origin egress low; compress responses.

### Build pipeline compute
Aggregating billions of daily search signals and rebuilding Tries is periodic batch
compute.
- **Control:** rebuild frequency is a dial — hourly vs every-15-minutes trades
  freshness against compute cost. Only the trending overlay needs to be fast; the
  base can rebuild less often.

> "The dominant cost is memory, because top-K-per-node inflates the Trie and we
> replicate it across regions and replicas. I'd control it by tuning K, pruning
> cold branches from the serving structure, and using a compact Trie encoding. The
> build compute is a freshness dial — I'd rebuild the base ranking hourly but run
> the trending overlay on a tight streaming loop, so I only pay for real-time where
> it matters."

---

## Evolution — How This Grows Over Time

A staff engineer designs for the system's future, not just its launch:

1. **v1:** global rankings, periodic rebuild, prefix-sharded Trie, edge cache.
2. **+ Trending:** streaming overlay for near-real-time viral queries.
3. **+ Personalization:** per-user signal blended at the service layer for logged-in
   users (accepting the edge-cache hit).
4. **+ Context:** location/time-of-day ranking adjustments.
5. **+ Fuzzy matching:** tolerate typos ("seach" → "search") via edit-distance
   search over the Trie or a separate correction layer.
6. **+ Entity suggestions:** extend beyond query strings to structured entities
   (people, places, products) with their own ranking signals.

Each step is additive and plugs into the two-path architecture without redesigning
it — which is itself a sign the original design was sound. Naming this roadmap
unprompted shows you're thinking about the system's lifecycle, not just passing the
interview.
