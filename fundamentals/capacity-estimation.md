# Capacity Estimation — The Back-of-Envelope Playbook

Capacity estimation plays a meaningful role in staff-level system design interviews
because **scale influences almost every decision that follows**. If a system serves
100M daily active users, how many requests per second is that? How much storage
after a year? What happens at 10× traffic? At what point does a single database
stop being enough?

The goal is not precision — it's turning a vague scale ("millions of users") into
concrete numbers that **drive architecture decisions**. Estimation that ends in "so
I need to shard" or "so this fits in memory" is doing its job.

---

## The Numbers Every Engineer Should Know

### Powers of 10 → data sizes
| Value | Bytes | Rough meaning |
|-------|-------|---------------|
| 1 thousand (KB) | 10³ | A short text record |
| 1 million (MB) | 10⁶ | A small image |
| 1 billion (GB) | 10⁹ | Fits in RAM on one box |
| 1 trillion (TB) | 10¹² | Needs many disks / sharding |
| 1 quadrillion (PB) | 10¹⁵ | internet-scale, distributed FS |

### Time → seconds (for QPS math)
- 1 day = **86,400 seconds** (memorize as ~10⁵)
- 1 month ≈ 2.5M seconds
- 1 year ≈ 31.5M seconds (~3 × 10⁷)

### Latency numbers (Jeff Dean's, rounded, order-of-magnitude)
| Operation | Latency |
|-----------|---------|
| L1 cache reference | ~1 ns |
| Main memory reference | ~100 ns |
| SSD random read | ~16 µs |
| Read 1 MB sequentially from memory | ~4 µs |
| Round trip within same datacenter | ~500 µs |
| Read 1 MB from SSD | ~1 ms |
| Disk seek | ~2–10 ms |
| Round trip CA ↔ Netherlands | ~150 ms |

The key takeaways: **memory is ~100× faster than SSD, ~10,000× faster than disk
seek**; **cross-region round trips are ~150ms** (so you can't do them synchronously
on a hot path); **same-DC round trip is ~0.5ms** (fan-out within a DC is cheap).

---

## The Estimation Recipe

### Step 1 — QPS (queries per second)
```
QPS = DAU × actions_per_user_per_day ÷ 86,400
Peak QPS = QPS × peak_factor   (peak_factor is typically 2–3×)
```

> 100M DAU, each does 10 reads/day:
> 100M × 10 ÷ 86,400 ≈ 11,600 QPS average → ~30,000 QPS peak.

Always compute **read QPS and write QPS separately** — the ratio drives caching,
replication, and storage choices.

### Step 2 — Storage
```
Storage/day  = new_records_per_day × bytes_per_record
Storage/year = Storage/day × 365
Total        = Storage/year × retention_years  (× replication_factor)
```

> 100M new records/day at 1 KB each = 100 GB/day = ~36 TB/year.
> With 3× replication = ~110 TB/year. → Won't fit on one node; shard.

### Step 3 — Bandwidth
```
Bandwidth = QPS × payload_bytes
```

> 30K QPS × 500 bytes = 15 MB/s ingress. Trivial.
> But 30K QPS × 5 MB video chunk = 150 GB/s → this is a CDN problem, not an
> app-server problem.

### Step 4 — Memory (for caching)
Apply the **80/20 rule**: 20% of the data serves 80% of the requests. Cache the hot
20%.
```
Cache size = hot_fraction × total_data
```

> 36 TB total, cache the hot 20% = ~7 TB. That's too big for one machine's RAM
> (~256 GB), so the cache itself must be **distributed** (a Redis/Memcached cluster
> of ~30 nodes). This is the kind of conclusion estimation should produce.

---

## Worked Example — URL Shortener

> **Assumptions:** 100M new URLs/day, 100:1 read:write ratio, 5-year retention,
> 500 bytes per record.

**Write QPS:** 100M ÷ 86,400 ≈ **1,160 writes/s** (~3,500 peak).
**Read QPS:** 100:1 ratio → **116,000 reads/s** (~350,000 peak).
→ *This is read-heavy. Caching and read replicas are mandatory.*

**Storage:** 100M × 500 bytes = 50 GB/day → 18 TB/year → **90 TB over 5 years**.
→ *Won't fit on one node. Shard by short-URL hash.*

**Cache:** hot 20% of active URLs. If ~1% of all-time URLs are hot at any moment,
that's a few hundred GB → a **Redis cluster**, not a single instance.

**Bandwidth:** 350K reads/s × 500 bytes ≈ **175 MB/s** egress. Manageable behind a
CDN + cache.

**The conclusions that flow from these numbers:**
1. Read-heavy → cache aggressively, use read replicas.
2. 90 TB → shard the datastore.
3. Redirect must be < 50ms → serve from cache/edge, not the origin DB.
4. Write path is light (~3.5K peak) → a single primary per shard is fine.

Notice every number **led to a design decision**. That's the point.

---

## Rules of Thumb

- **Round aggressively.** 86,400 → 10⁵. 365 → 400. The interviewer wants the
  order of magnitude, not the third significant figure.
- **Separate reads from writes.** The ratio is one of the most important numbers.
- **Peak factor 2–3×.** Traffic is never flat; design for the peak.
- **Replication multiplies storage.** 3× is the common default.
- **1 GB fits in RAM; 1 TB does not.** This single fact decides "single node vs
  distributed" for caches and in-memory stores.
- **Cross-region is ~150ms.** Anything on a synchronous hot path must stay
  in-region.
- **Make every estimate lead somewhere.** If a number doesn't change a decision,
  you don't need it.

---

## What the Interviewer Is Actually Scoring

Not arithmetic. They're scoring whether you can:
1. Translate scale into QPS/storage/bandwidth quickly.
2. Identify the **binding constraint** (is this read-heavy? storage-bound?
   latency-bound?).
3. Let that constraint **drive the architecture** ("so I need to shard / cache /
   use a CDN").

A candidate who computes perfect numbers but doesn't connect them to decisions has
missed the point. A candidate who does rough math and immediately says "so this is
a read-heavy, storage-bound system — I'll shard and cache" has nailed it.
