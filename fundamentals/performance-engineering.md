# Performance Engineering — Why Software Isn't Free Anymore

For 30 years, software got faster every year for free: Moore's Law shrank transistors and clock
speeds climbed, so the *same code* ran faster on next year's chip. **That ended around 2004.** Since
then, making software fast is an engineering discipline again — and the payoff is enormous. MIT's
6.172 opens with a case study that takes one computation (matrix multiply) from naive Python to
hand-tuned code and gets a **53,292× speedup** on the *same machine*. This page is that story and the
transferable principles inside it.

Source: MIT 6.172 *Performance Engineering of Software Systems*, Lecture 1 (Leiserson, 2018).
https://ocw.mit.edu/courses/6-172-performance-engineering-of-software-systems-fall-2018/

> **Why a systems-design engineer should care:** you rarely need a 50,000× speedup, but you routinely
> need to know *why* something is slow and which lever actually moves it. The mental models here —
> the memory hierarchy, cache locality, "where does the data live" — are the same ones behind
> [MapReduce's locality](#fu-data-processing), [LSM memtables](#fu-database-scaling), and
> [direction-optimizing BFS](https://salman9193.github.io/dsa-problems/#guides/DIRECTION_OPTIMIZING_BFS).

---

## Why Performance Stopped Being Free: the End of Dennard Scaling

Two different scaling laws powered the "free lunch," and only one of them died.

- **Moore's Law** (transistor *count* doubles ~every 2 years): **still going**, slower.
- **Dennard scaling** (as transistors shrink, power density stays constant, so you can raise the
  clock frequency for free): **broke down around 2004–2005.**

Dennard's 1974 insight was that voltage and current shrink with transistor size, so power (V×I) drops
as fast as area — letting clock speeds climb ~40%/generation at constant power. It stopped because
**leakage current and threshold voltage don't scale down** past a point, so power density shot up.
The projection was stark: had clock scaling continued its 25–30%/year trend, chip power density
would have reached that of a **rocket nozzle, then the sun's surface.** Intel hit this "power wall"
and **cancelled its 4 GHz Tejas/Jayhawk chips in 2004.**

```
before 2004:  faster clock every year → same code runs faster → performance was FREE
after  2004:  clock flat (~3-4 GHz) → vendors add CORES, vectors, caches instead
              → software must be ADAPTED to use them → performance is ENGINEERED
```

**The consequence that defines modern performance:** a chip's peak is now spread across **many cores,
wide vector units, and a deep cache hierarchy** — and naive code uses almost none of it.

> This is the same "the bottleneck moved" story as the rest of this repo. In 2004 the bottleneck
> stopped being *clock speed* and became *parallelism and data movement* — which is exactly what the
> case study below exploits.

---

## The Case Study: One Computation, 53,292× Faster

Square matrix multiply (`C = A·B`, n=4096) is `2n³ ≈ 2^37` floating-point operations. The test
machine (AWS, 2× 9-core Haswell, AVX) peaks at **~836 GFLOPS**. Watch how much of that peak each
version captures:

| # | Version | Time | Speedup vs prev | % of peak |
|---|---------|------|-----------------|-----------|
| 1 | Python (nested loops) | 21,042 s (~6 h) | 1× | **0.001%** |
| 2 | Java | 2,387 s | 8.8× | 0.007% |
| 3 | C (Clang) | 1,156 s | 2× | 0.014% |
| 4 | + interchange loops (cache) | 178 s | **6.5×** | 0.093% |
| 5 | + compiler `-O3` | 54.6 s | 3.3× | 0.30% |
| 6 | + parallel loops (all cores) | 3.04 s | **18×** | 5.4% |
| 7 | + tiling (cache blocking) | 1.79 s | 1.7× | 9.2% |
| 8 | + parallel divide-and-conquer | 1.30 s | 1.4× | 12.6% |
| 9 | + compiler vectorization | 0.70 s | 1.9× | 23.5% |
| 10 | + AVX intrinsics | **0.39 s** | 1.8× | **41.7%** |

**Version 10 is 53,292× faster than Version 1 — and competitive with Intel's professionally-tuned
MKL library.** The entire gain came from *matching the code to the hardware*, not changing the
algorithm (it's the same `O(n³)` triple loop throughout).

---

## The Transferable Lessons

You won't hand-write AVX in an interview. But five ideas from this progression are the actual content,
and they generalize far beyond matrix multiply.

### 1. Language choice is a 100× lever (interpreted vs compiled)

Python → C alone was **~18×** (mostly). Python is *interpreted* — a loop re-reads, re-parses, and
dispatches every statement each iteration. C compiles straight to machine code. Java sits between:
**bytecode + JIT** (interpret first, then compile hot paths to machine code once they run enough).

> **The rule:** the interpreter loop is pure overhead per operation. For tight numeric inner loops,
> a compiled language is 10–50× before you optimize anything. (This is *why* Python numeric code
> pushes the loop into C via NumPy — you're paying for C underneath.)

### 2. Loop order changes speed by 18× — and it's all cache locality

The single most repo-relevant lesson. The **same triple loop** in different orders:

| Loop order (outer→inner) | Time | Last-level cache miss rate |
|--------------------------|------|----------------------------|
| `i, k, j` | **178 s** | **1.0%** |
| `i, j, k` | 1,156 s | 7.7% |
| `j, k, i` | 3,057 s | **15.4%** |

**6.5× from reordering three loops, zero algorithm change.** Why: matrices are stored **row-major**
(a whole row is contiguous in memory), and the CPU fetches memory in **64-byte cache lines**, not
single elements. The good order (`i,k,j`) walks all three matrices *along rows* — each cache line it
pulls in gets fully used before eviction (**spatial locality**). The bad order walks `B` *down a
column* — consecutive accesses are 4096 elements apart, so each 64-byte line delivers **one useful
number and 56 wasted bytes**, and the line is evicted before reuse.

```
row-major matrix in memory:  [ row 0 ][ row 1 ][ row 2 ] ...
walk along a row  → next element is adjacent      → cache line reused → FAST
walk down a column → next element is n apart       → new line each time → SLOW (cache miss)
```

> **This is the same principle behind the [Unique Paths rolling
> array](https://salman9193.github.io/dsa-problems/#dynamic-programming/unique-paths):** the
> *iteration direction over memory* is load-bearing. It's also why databases pick row- vs
> column-major storage by access pattern (OLTP reads rows; OLAP scans columns —
> [Database Scaling](#fu-database-scaling)). **"Which way do you walk memory?" is one of the highest-
> leverage questions in performance.**

### 3. After 2004, you must use the parallelism explicitly

Version 6 (parallel loops across all 18 cores) was an **18× jump** — the single biggest step. The
machine *had* the cores the whole time; the code just wasn't using them. And the rule of thumb from
the slides: **parallelize the outer loop, not the inner** (parallelizing `i` gave 3.18 s;
parallelizing the inner `j` gave 531 s — 160× worse, because the overhead of spawning work swamps the
tiny inner body).

> **Amdahl-flavored takeaway:** the free lunch became a *do-it-yourself* lunch. The performance is
> there — spread across cores and vector lanes — but it's opt-in. This is why "parallel," "concurrency,"
> and "optimization" exploded in job postings right after 2004.

### 4. Cache blocking (tiling): restructure to reuse data while it's hot

Once parallel, the bottleneck is again **data movement**, not compute. **Tiling** breaks the matrices
into small sub-blocks (tile size `s`) that fit in cache, and finishes all the work on a block before
moving on — so each loaded value is reused many times before eviction. In the case study, tiling cut
**cache references 62% and cache misses 68%.** The tile size is a *tuning parameter* found by
experiment (best was 32).

The elegant generalization: **recursive divide-and-conquer** (Version 8) tiles for *every* cache
level at once — split the matrix in halves recursively, and each level of recursion naturally fits a
smaller cache. It needs **coarsening** (stop recursing at a base-case size and switch to a simple
loop) to avoid function-call overhead dominating — the same coarsening idea as
[balanced-tree](https://salman9193.github.io/dsa-problems/#guides/BALANCED_TREES) and quicksort
base cases.

> **The principle: restructure the computation so data is reused while it's still in cache.** "Where
> does the data live, and how many times do I touch it per load?" — the same instinct as MapReduce's
> "move compute to the data."

### 5. Vectorization (SIMD): one instruction, many data

Modern CPUs have **vector units** — a single instruction operates on a whole vector of values at once
(**SIMD** = Single Instruction, Multiple Data). Version 9 let the compiler auto-vectorize (`-O3
-march=native -ffast-math`) for ~2×; Version 10 used **AVX intrinsics** (C functions mapping directly
to vector instructions) for another ~2×, reaching 41% of peak.

> **The rule:** the compiler vectorizes *conservatively* (many machines lack the newest instructions),
> so you unlock it with flags — or, for the last mile, write intrinsics. Rarely worth it in
> application code; essential in libraries (BLAS, MKL, crypto, codecs).

---

## The Meta-Lesson: Think → Code → Measure → Repeat

The slide that ties it together shows a loop: **think, code, then run-run-run to measure many
implementations.** Every version above was a *measured* hypothesis — the cache miss rates came from
`cachegrind`, the speedups from timing. Nobody guessed which loop order or tile size won; they
measured.

> **This is the single most important habit.** Performance intuition is famously unreliable
> ("premature optimization is the root of all evil" — Knuth; "more sins are committed in the name of
> efficiency than for any other reason" — Wulf). The discipline is: **measure first, optimize the
> proven bottleneck, measure again.** It's the same evidence-driven loop as profiling a slow query or
> load-testing a service before scaling it.

---

## The Modern Performance Hierarchy (what to reach for, in order)

Ordered by leverage-per-effort, which is roughly the case-study order:

1. **Algorithm / data structure** — a better `O()` beats any constant-factor tuning (not shown here
   because MM stays `O(n³)`, but it's rule zero).
2. **Language / runtime** — compiled vs interpreted; the 10–50× baseline.
3. **Cache locality** — loop order, data layout, access patterns. *Huge, and free-ish.*
4. **Parallelism** — use all the cores (and, at scale, all the machines).
5. **Cache blocking / tiling** — reuse data while hot.
6. **Vectorization (SIMD)** — one instruction, many data.
7. **Hand-tuned intrinsics / assembly** — the last mile, for libraries only.

Most application code should stop at 3–4. Libraries go to 7. **Knowing where to stop is the
judgment.**

---

## Engineering Blogs & Primary Sources

- **MIT 6.172 — Performance Engineering of Software Systems (Leiserson et al.).**
  https://ocw.mit.edu/courses/6-172-performance-engineering-of-software-systems-fall-2018/
  The full course; Lecture 1 is this matrix-multiply case study end to end (the source of the
  53,292× progression and every number above).

- **Dennard et al. (1974), "Design of Ion-Implanted MOSFETs with Very Small Physical Dimensions,"**
  *IEEE Journal of Solid-State Circuits* 9(5):256–268. DOI:
  [10.1109/JSSC.1974.1050511](https://doi.org/10.1109/JSSC.1974.1050511). The scaling law whose
  ~2004 breakdown ended the free lunch.

- **Sutter (2005), "The Free Lunch Is Over: A Fundamental Turn Toward Concurrency in Software,"**
  *Dr. Dobb's Journal*. http://www.gotw.ca/publications/concurrency-ddj.htm
  The famous essay naming the shift: clock scaling stopped, so software must go parallel.

- **Intel Intrinsics Guide.** https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
  The reference for the AVX intrinsic functions Version 10 uses (also cited in the lecture).

- **Drepper (2007), "What Every Programmer Should Know About Memory."**
  https://people.freebsd.org/~lstewart/articles/cpumemory.pdf
  The deep reference on cache lines, locality, and the memory hierarchy behind lesson #2.

**The through-line:** performance stopped being free in 2004, and the free-lunch clock speed was
replaced by parallelism and a deep memory hierarchy the compiler won't fully use for you. Fast modern
code comes from **matching the computation to the hardware** — pick the right language, walk memory
the right way, use every core, reuse data while it's in cache, and vectorize the hot loop — and
**measuring at every step** because your intuition is wrong more often than you'd think.
