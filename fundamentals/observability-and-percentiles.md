# Percentiles, Tail Latency & Observability

Every HLD in this repo says "monitor p99 latency." This page is *why* — the concept that separates
people who recite "watch the golden signals" from people who actually understand what a healthy
system looks like. The short version: **averages lie, the tail is what users feel, and at scale the
tail is not an edge case — it's Tuesday.**

This deepens the golden-signals checklist in [Communication → Observability](#fu-communication);
that page is the *what to monitor*, this is the *how to reason about it.*

---

## What p95 / p99 Actually Mean

A percentile is a **position in a sorted list**, not a measurement. Sort every request by latency:

```
p50 (median): the request 50% of the way up  → half were faster, half slower
p95:          the request 95% of the way up  → 95% were ≤ this, slowest 5% worse
p99:          the request 99% of the way up  → 99% were ≤ this, slowest 1% worse
p99.9:        99.9% of the way up            → the slowest 1-in-1000
```

**"p99 = 20 ms" reads as: 99% of requests finished in ≤ 20 ms; the slowest 1% took longer.** That's
it. p99 is not "the worst case" (that's the max) and not "the average of the slow ones" — it's the
line 99% of requests fall under.

---

## Why the Average Lies

Latency is **not** a bell curve. It's heavily **right-skewed with a long tail**: most requests cluster
near a fast median, and a small fraction take 10–100× longer (a GC pause, a cache miss, lock
contention, a slow disk, a neighbor stealing CPU). The mean does two bad things at once:

- **It's dragged by the tail** — a few 500 ms requests pull the average up, so it overstates the
  typical experience.
- **It hides the tail** — the same average smooths over the fact that some requests were catastrophic.

```
p50 = 10 ms,  p99 = 500 ms,  average ≈ 25 ms
   → 25 ms describes NEITHER the typical request (10) NOR the bad one (500). It describes nobody.
```

**The deepest point: averages have no user.** No real request took "the average latency." But some
real user *did* experience p99. Percentiles describe experiences that actually happened; the mean
describes a fiction. *A percentile is a position in a sorted list, not a measurement.*

---

## Why the Tail Dominates at Scale (the system-design insight)

This is what makes percentiles an *architecture* concept, not just a dashboard one — the core of
Google's **"The Tail at Scale"** (Dean & Barroso, 2013).

**One user request usually fans out to many backends, and it's only as fast as the *slowest* one.**
If a request touches `n` independent backends and waits for all of them, the whole thing is under the
single-service p99 only if *every* backend is fast:

```
P(all n fast) = 0.99ⁿ
   n = 1    → 99%   fast    (the tail is 1% of requests)
   n = 10   → 90%          (already 1 in 10 requests hits a slow backend)
   n = 100  → 37%          → 63% of user requests hit a p99 backend!
```

**Dean & Barroso's headline: a service with a 1-second p99, queried across 100 servers, returns in
over a second for ~63% of user requests.** The one-in-a-hundred event becomes the *common* case — you
didn't get slower, you just rolled the dice 100 times per page.

> **The reframe worth memorizing:** *your p99 is what a normal Tuesday feels like to somebody.* At
> 100 fan-out calls per page, **most page loads contain a p99 event.** "Our p99 is fine" and "one
> user in six has a bad time" can be the exact same sentence. This is why fan-out systems obsess over
> the tail, and why the [Search Typeahead](#hld-search-typeahead) and
> [Sharded DB](#hld-sharded-database-platform) HLDs care about p99 far more than the average.

10–40 fan-out is routine (Netflix, Amazon, and Google have all described requests in these ranges),
so this isn't a pathological edge case — it's the normal shape of a microservice request.

---

## The Rules People Get Wrong

### 1. You cannot average or sum percentiles

**Averaging p99 across servers is meaningless** (a percentile isn't an additive quantity). And you
**cannot add percentiles across pipeline stages:**

```
parse p99 = 100µs, match p99 = 100µs, publish p99 = 100µs
   → end-to-end p99 is NOT 300µs.
```

The requests slow in *parsing* usually aren't the ones slow in *publishing*, so summing **overstates**
the total; and when one common cause (a saturated pool) stalls every stage at once, summing
**understates** it. **Only an end-to-end measurement, taken from when each request actually arrived,
is meaningful.** (Beware *coordinated omission* — dropped/slow requests that never get sampled make
your numbers look better than reality.)

### 2. p99 needs enough samples to mean anything

A percentile is only as trustworthy as the samples above the line:

```
10,000 requests → p99 is 100 samples, p99.9 is 10, p99.99 is 1 (= the max, not a statistic)
```

**Rule of thumb: you want at least a few hundred samples above the line.** So p99 wants tens of
thousands of requests per window, p99.9 wants hundreds of thousands. **On a low-traffic service,
alert on p95 or widen the window** — a "p99" computed from 8 requests is noise. (This is also why
latency is captured with an **HDR histogram**: it absorbs millions of samples in bounded memory and
still reads an accurate quantile.)

### 3. Which percentile to watch depends on fan-out and stakes

| Percentile | Watch it for | Why |
|-----------|--------------|-----|
| **p50 (median)** | capacity & trend shifts | a moving median = systemic change (slow deploy, saturated pool), not tail noise |
| **p95 / p99** | **user-facing SLOs** | the typical *bad* experience; what users actually complain about |
| **p99.9** | wide fan-out, or money/safety-critical | after fan-out, even 1-in-1000 becomes common; the case for it is *arithmetic*, not perfectionism |

**Pick the percentile whose tail mass matches how often you're willing to miss — after fan-out.**

---

## How You Actually Fix a Bad Tail

You don't *eliminate* variance — it's inherent in shared infrastructure (VMs fighting for cache and
network, background daemons stealing cycles, queues backing up on bursts). You **tolerate** it. Dean
& Barroso's "tail-tolerant" techniques:

- **Hedged requests** — once a request exceeds, say, its p95, fire a *duplicate* to a second replica
  and take whichever answers first. In the paper's BigTable benchmark, hedging after a 10 ms delay
  cut **p99.9 for a 100-server read from 1,800 ms to 74 ms — for only 2% more requests.** The single
  best tail-latency lever in most systems.
- **Tied requests** — enqueue on two servers at once; each cancels the other's copy the moment one
  starts executing, removing even the hedge delay.
- **Timeouts at a realistic tail percentile** (not a generous round constant), with a **retry budget**
  capping retries as a fraction of traffic so recovery can't become a retry storm.
- **Reduce fan-out** where you can — fewer sequential/parallel dependencies means fewer dice rolls.

> **The mental shift:** the goal isn't a system with no slow components (impossible), it's a system
> that stays fast *despite* slow components. Redundancy against the tail, not elimination of it.

---

## Percentiles in the SLO Framework

Percentiles are the raw material of **SLOs** (Service Level Objectives):

- **SLI** (Indicator) — the measured thing: "proportion of requests served in < 200 ms."
- **SLO** (Objective) — the target: "99% of requests < 200 ms over 28 days" (i.e., **p99 < 200 ms**).
- **SLA** (Agreement) — the contractual promise with penalties, usually looser than the internal SLO.
- **Error budget** — `100% − SLO`. A 99.9% SLO gives a 0.1% budget; spend it on risk (deploys,
  experiments). Burn it too fast → freeze changes.

**Alert on the SLO via burn rate, not on raw p99 crossing a line** — a brief p99 blip that doesn't
threaten the monthly budget shouldn't page anyone; a slow steady burn should. This is what turns
percentiles from a dashboard number into an operational discipline.

---

## The Three Pillars (where percentiles live)

Observability rests on three pillars; percentiles are a *metrics* concept but you need all three to
act on them:

- **Metrics** — cheap, aggregated numbers over time (the p99 latency graph, request rate, error
  rate). Tells you *that* something is wrong.
- **Logs** — discrete event records. Tells you *what* happened on a specific request.
- **Traces** — one request's full path across every service, with per-hop timing. Tells you *where*
  the latency went — essential when a p99 spike is caused by one slow dependency five hops deep.

**A p99 alert tells you the tail got worse; a trace tells you which backend caused it.** That pairing
— metric to detect, trace to localize — is the core observability loop.

> The **golden signals** to watch (Google SRE): **Latency** (percentiles!), **Traffic**, **Errors**,
> **Saturation**. Note: measure latency of *successful* and *failed* requests separately — a fast
> failure can flatter your latency numbers while the service is actually broken.

---

## Interview & Design Notes

- **When asked "how would you monitor this?"** → "Golden signals per service and dependency, latency
  at **p95/p99 not average**, alert on the SLO's error-budget burn rate. I care about the tail because
  the request fans out, so the tail is what users actually feel."
- **Volunteer the fan-out math.** "This request touches ~15 services, so even a good per-service p99
  means a meaningful fraction of users hit a slow one — I'd add hedged requests on the critical path."
  That's a strong Staff-level signal.
- **Know the anti-patterns:** averaging percentiles, summing them across stages, and computing p99 on
  a service with too little traffic. Catching these in a design review is exactly the judgment the
  round tests.

---

## Engineering Blogs & Primary Sources

- **J. Dean & L. A. Barroso (2013), "The Tail at Scale,"** *Communications of the ACM* 56(2):74–80.
  DOI: [10.1145/2408776.2408794](https://doi.org/10.1145/2408776.2408794). The foundational paper:
  why fan-out makes the tail dominate, and the tail-tolerant techniques (hedged/tied requests) that
  fix it — including the BigTable result (p99.9: 1,800 ms → 74 ms at +2% load).

- **Google SRE Book — "Monitoring Distributed Systems" (the Four Golden Signals) and "Service Level
  Objectives."** https://sre.google/sre-book/monitoring-distributed-systems/ and
  https://sre.google/sre-book/service-level-objectives/. The SLI/SLO/error-budget framework and the
  latency/traffic/errors/saturation checklist this page builds on.

- **M. Brooker (2021), "Tail Latency Might Matter More Than You Think."**
  https://brooker.co.za/blog/2021/04/19/latency.html
  Why a service can be *inside* its percentile targets while a real share of users have a bad time —
  the composition-of-many-calls problem, made intuitive.

**The through-line:** performance is a *distribution*, not a number. The average describes a user who
doesn't exist; **p95/p99 describe the experiences that actually happen**, and fan-out makes the tail
the common case. Monitor the tail, set SLOs on it, alert on the error-budget burn, and fix it with
redundancy (hedged requests) rather than the impossible dream of a system with no slow parts.
