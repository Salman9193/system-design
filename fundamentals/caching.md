# Caching — Eviction, Invalidation, and Write Strategies

Caching is the single most common lever for making reads fast, and it's one of the
first places a Google interviewer will push you "inside the tool." Saying "I'll add
a cache" is an L4 move. Explaining *where* it sits, *what* it evicts, *how* it stays
consistent with the source of truth, and *how* it fails is the staff-level depth.

---

## Why Cache At All

Memory is ~100× faster than SSD and ~10,000× faster than a disk seek (see
`capacity-estimation.md`). For read-heavy systems (100:1 read:write is common), a
cache absorbs the vast majority of reads, so the slow, expensive datastore only
sees the small write load plus cache misses.

The 80/20 rule makes it work: ~20% of the data serves ~80% of the requests. Cache
that hot 20% and you handle most traffic from memory.

---

## Where the Cache Sits

| Placement | What it caches | Example |
|-----------|---------------|---------|
| **Client-side** | Per-user responses | Browser cache, mobile app cache |
| **CDN / edge** | Static + cacheable content near users | Images, video, cacheable API responses |
| **In-process** | Hot objects in the app's own memory | Guava cache, local LRU |
| **Distributed cache** | Shared hot data across app servers | Redis, Memcached cluster |
| **Database cache** | Query results / buffer pool | The DB's own page cache |

A real system usually has *several* of these layers. The distributed cache (Redis /
Memcached) is the one interviews focus on because it's shared mutable state.

---

## Eviction Policies (what to remove when full)

A cache is finite, so it must evict. The policy is a deep-dive question.

| Policy | Evicts | When it fits |
|--------|--------|-------------|
| **LRU** (Least Recently Used) | The item unused longest | General purpose — recency predicts reuse |
| **LFU** (Least Frequently Used) | The item accessed least often | Stable popularity (a viral video stays hot) |
| **FIFO** | The oldest inserted | Rarely ideal; simple |
| **TTL** (time-to-live) | Anything past its expiry | When staleness has a hard time bound |
| **Random** | A random item | Surprisingly OK; avoids LRU's metadata cost |

**LRU** is the default answer and worth knowing cold — it's a HashMap + doubly
linked list giving O(1) get and put (this is LeetCode #146, and it's not a
coincidence that it's a Google favorite). **LFU** (LeetCode #460) is the follow-up
when the interviewer says "but popular items should stay even if not just accessed."

Modern production caches often use **hybrids** (e.g. TinyLFU / W-TinyLFU in Caffeine)
that combine recency and frequency.

---

## Write Strategies (how the cache and DB stay in sync)

This is where the real depth is. When data changes, the cache and the source of
truth can diverge — the strategy decides how.

### Write-through
Write to the cache **and** the DB synchronously, in the same operation.
- **Pro:** cache is always consistent with the DB; reads never miss fresh data.
- **Con:** every write pays both latencies; caches data that may never be read.

### Write-back (write-behind)
Write to the cache immediately; flush to the DB asynchronously later.
- **Pro:** very fast writes; batches DB writes.
- **Con:** **data loss risk** if the cache dies before the flush. Needs care.

### Write-around
Write straight to the DB, bypassing the cache; the cache fills on the next read
miss.
- **Pro:** avoids caching write-once-read-never data.
- **Con:** the just-written item is a guaranteed cache miss on first read.

### Cache-aside (lazy loading) — the most common
The application manages the cache explicitly:
```
read:  check cache → hit? return : miss? load from DB, put in cache, return
write: write to DB, then INVALIDATE (delete) the cache entry
```
- **Pro:** only caches what's actually read; cache failure doesn't break writes.
- **Con:** first read is always a miss; risk of stale data during the race window.

**The staff nuance:** on write, prefer to **invalidate (delete)** the cache entry,
not update it. Updating re-introduces a race (two concurrent writers can leave the
cache holding the older value). Deleting means the next reader repopulates from the
authoritative DB.

---

## Cache Invalidation — The Hard Problem

"There are only two hard things in computer science: cache invalidation and naming
things." Invalidation is genuinely where designs break.

- **TTL-based:** every entry expires after N seconds. Simple, bounds staleness, but
  you serve stale data up to the TTL and get a miss storm when popular keys expire
  together.
- **Explicit invalidation:** on write, delete the affected keys. Precise, but you
  must know every key a write affects (hard with derived/aggregated data).
- **Versioning:** embed a version in the key; a write bumps the version so old
  entries are naturally orphaned.

---

## The Two Classic Failure Modes (know these cold)

### Thundering herd / cache stampede
A hot key expires (or the cache restarts) and thousands of concurrent requests all
miss simultaneously, hammering the DB at once.
- **Fixes:** request coalescing (only one request recomputes, others wait);
  probabilistic early expiry (refresh slightly before TTL); a short lock per key.

### Cache penetration
Requests for keys that **don't exist** always miss and hit the DB every time (an
attacker can exploit this).
- **Fixes:** cache the negative result ("not found") with a short TTL; a **Bloom
  filter** in front to reject keys that definitely don't exist.

### Hot key
A single key (a celebrity's profile) gets so much traffic it overwhelms the one
cache node holding it.
- **Fixes:** replicate the hot key across nodes; add a local in-process cache layer
  in front of the distributed cache.

---

## Consistency Trade-off

A cache is, by definition, a second copy of data — so it's a source of potential
inconsistency. The trade-off you're making:

> Caching trades **consistency for latency and load reduction**. You accept a window
> where the cache may be stale, in exchange for fast reads and a protected DB.

At L6, name this explicitly: "I'm using cache-aside with a 60-second TTL, so reads
can be up to 60 seconds stale — that's acceptable for a follower count but not for
an account balance, where I'd read through to the DB or use write-through."

---

## The Interview Checklist

When you add a cache in a design, be ready to answer:
1. **Where** does it sit (edge / distributed / in-process)?
2. **What** does it evict (LRU / LFU / TTL) and why?
3. **How** do writes keep it consistent (cache-aside + invalidate is the safe
   default)?
4. **What's the staleness window**, and is that acceptable for this data?
5. **What happens** on a hot key, a stampede, or a cache-node failure?

Answering these unprompted is exactly the "own it end-to-end" signal that separates
L6 from L5.
