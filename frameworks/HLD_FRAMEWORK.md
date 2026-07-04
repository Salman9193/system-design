# HLD Framework — The 45-Minute Staff-Level Approach

This is the time-boxed structure Google evaluates you against in a system design
round. The round is ~45 minutes. The interviewer is scoring specific dimensions,
and each has a rough time budget. Overspending in one area means leaving points
on the table in another.

---

## The Time Budget (what you're graded against)

| Phase | Time | Rubric weight | What's scored |
|-------|------|--------------|---------------|
| 1. Requirements & scope | 3–5 min | 10–15% | Did you ask the right questions and scope well? |
| 2. Capacity estimation | 3–5 min | 10% | Can you turn scale into concrete numbers? |
| 3. High-level design | 8–10 min | 20–25% | Right components, clear data flow, justified choices |
| 4. Deep dives (2–3 components) | 20–25 min | 25–30% | **This is what separates L5 from L6** |
| 5. Trade-offs & failure modes | 5–7 min | 15–20% | What did you give up? What breaks? How do you recover? |

**The single biggest mistake:** spending 20 minutes on the high-level design and
5 minutes on deep dives. The deep dive is where staff-level judgment shows. Invert
that instinct.

---

## Phase 1 — Requirements & Scope (3–5 min)

The prompt is deliberately ambiguous. Google is scoring whether you can define the
problem before solving it. **Do not start drawing boxes.**

### Functional requirements
What does the system *do*? List the core 2–3 features, then explicitly say what
you're **not** building (out of scope). Cutting scope is a staff signal — it shows
you can prioritize.

> "For a URL shortener, I'll focus on: (1) create short URL, (2) redirect. I'll
> treat analytics and custom aliases as out of scope for now, but flag where
> they'd plug in."

### Non-functional requirements
These drive every downstream decision:
- **Scale** — DAU, QPS, data volume, growth
- **Latency** — p50/p99 targets (e.g. "redirect < 50ms p99")
- **Consistency** — strong vs eventual? Which operations need which?
- **Availability** — is this 99.9% or 99.999%? What's the cost of downtime?
- **Read/write ratio** — read-heavy (100:1) vs write-heavy changes everything

### The staff move
Ask the *sharp* clarifying questions, not the generic ones. "Is this read-heavy?"
is fine. "Do we need to support the same short-URL resolving consistently across
regions immediately, or is eventual consistency acceptable for redirects?" shows
you're already thinking about the architecture.

---

## Phase 2 — Capacity Estimation (3–5 min)

Scale influences almost every decision that follows, so make the numbers concrete.
See `fundamentals/capacity-estimation.md` for the full playbook. The essentials:

- **QPS**: DAU × actions/user/day ÷ 86,400. Apply a peak factor (2–3×).
- **Storage**: records/day × bytes/record × retention (in years × 365).
- **Bandwidth**: QPS × payload size.
- **Memory for cache**: apply the 80/20 rule — cache the hot 20%.

> "1B redirects/day ÷ 86,400 ≈ 11.5K QPS average, ~30K QPS peak. At 500 bytes per
> URL record and 100M new URLs/day, that's ~50GB/day, ~18TB/year. That won't fit
> on one node — I'll need to shard."

The number should *lead somewhere*. Estimation that ends in "so I need to shard"
or "so this fits in memory on one box" is doing its job. Estimation for its own
sake is a waste of the time budget.

---

## Phase 3 — High-Level Design (8–10 min)

Draw the major components and the data flow between them. Keep it to the boxes
that matter: client → load balancer → API/service layer → data stores → async
workers/queues → caches.

### The rules
- **Justify every box.** Each component should earn its place. "I'm adding a cache
  here because reads are 100× writes and the hot set fits in memory."
- **Show the data flow** for the core operations — trace a write and a read
  end-to-end.
- **Name the data model.** What are the entities, what's the primary key, what's
  the access pattern? The access pattern drives the storage choice.
- **Sketch the API.** A few key endpoints with their signatures.

### The staff move
State your **default choice and the one alternative** you're weighing, then commit.
"I'll use a key-value store here — Cassandra or DynamoDB — because the access is
purely by key and I want horizontal scale over relational features. I'll come back
to the consistency setting in the deep dive." Naming the fork and committing shows
decision-making under uncertainty, which is exactly what's scored.

---

## Phase 4 — Deep Dives (20–25 min) — THE L5/L6 BOUNDARY

This is the largest block of time and the highest-signal phase. The interviewer
will pick 2–3 components and drill. Often **they** push you here ("how does the
cache stay consistent?", "what happens when a shard is hot?"). A staff candidate
often drives it themselves: *"Before I move on, let me go deep on the ranking
component, since that's where the hard problems are."*

### What a deep dive covers
For each chosen component:
- **The core mechanism** — not the tool name, the actual algorithm/data structure.
  Google will say "set the managed service aside and show me how it works."
- **The bottleneck** — where does it break under load? Hot keys, thundering herd,
  write amplification, tail latency?
- **The fix** — and its cost. Every fix trades something.

### The "inside the tools" test
When you say "I'll use Kafka," the interviewer may ask *how Kafka guarantees
ordering* or *how you'd build the processing yourself*. Naming a black box and
being unable to open it is the classic L5-capped signal. The `fundamentals/`
directory exists to make sure you can always open the box.

---

## Phase 5 — Trade-offs & Failure Modes (5–7 min)

At L6 this is **not optional and often not prompted** — you're expected to raise it
yourself. Skipping it caps you at L5.

### Trade-offs
For every major decision, state what you gave up. "I chose eventual consistency
for the feed to get availability and low latency — the cost is a user might not
see their own post for a few hundred milliseconds, which I mitigate with a
read-your-writes cache on the write path."

### Failure modes (the operational-maturity signal)
Walk through what breaks and how you detect + recover:
- **A node/shard dies** — replication, failover, rebalancing
- **A dependency is down** — timeouts, circuit breakers, graceful degradation
- **Traffic spikes 10×** — autoscaling, load shedding, backpressure
- **Data corruption / bad deploy** — rollback, backups, canaries

### Observability (now a first-class rubric item in 2026)
Do not finish without addressing: **metrics** (what you alert on), **logs** (where
they go, how you'd debug an incident), **traces** (following a request across
services). "If p99 redirect latency crosses 100ms, this alert fires; the on-call
would check the cache hit rate dashboard first, then the shard latency histogram."

---

## The One-Line Summary

Requirements → numbers → boxes → **go deep on the hard 2-3 parts** → say what
breaks and how you'd know. Drive the conversation, name your trade-offs before
you're asked, and never leave a black box unopened.

---

## Common Failure Modes (that quietly tank candidates)

| Failure mode | Why it caps you | The fix |
|--------------|-----------------|---------|
| Jumping to boxes before requirements | Reads as template memorization | Spend the first 3–5 min on scope |
| Listing tools without opening them | The L5 ceiling | Explain the mechanism inside |
| No trade-off talk | Reads as shallow | Narrate what you gave up, unprompted |
| Ignoring where your design breaks | "Can't reason about scale" | Know your breaking point + fallback |
| Skipping observability | Explicit 2026 rubric points lost | Treat monitoring as a core component |
| Waiting to be asked follow-ups | Not driving = not staff | Proactively go deep: "let me address X" |
