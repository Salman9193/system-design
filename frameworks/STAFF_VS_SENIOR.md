# Staff vs Senior — What Makes an Answer L6, Not L5

The single most common reason for downleveling at the staff level is giving an
**L5-quality answer in an L6 loop**. The same question, the same clean
architecture, the same reasonable choices — an L4 gets a strong score, an L5 gets
"solid," and an L6 gets "not yet ready" and a downlevel offer.

The jump between levels is **not about knowing more technologies**. It's about
demonstrating increasingly sophisticated judgment. This document makes that
boundary explicit.

---

## The Core Difference in One Sentence

> An L5 candidate produces a correct design when asked. An L6 candidate **owns the
> system end-to-end** — proactively surfacing failure modes, operational reality,
> cost, and multi-region concerns **without being asked**.

---

## Side-by-Side: Same Question, Different Levels

### The question: "Design a system to store and serve user notifications."

**L5 answer (solid, but capped):**
- Clarifies read/write ratio, sketches API
- Draws: client → API → queue → workers → DB → push service
- Chooses Cassandra for the notification store, justifies with write-heavy access
- Adds a cache for the unread-count read path
- Explains the data flow clearly

This is a **good design**. It would pass at L4 and score "solid" at L5. At L6 it's
incomplete — because it stops at the architecture.

**L6 answer (everything above, plus, unprompted):**
- "Let me address what happens when the push provider (APNs/FCM) is down — I'll
  queue with exponential backoff and a dead-letter queue, and degrade to
  in-app-only delivery so the user still sees notifications on next open."
- "The unread-count cache will get hot for celebrity accounts with millions of
  followers — I'll shard the counter and use approximate counts above a threshold,
  since exact unread count past 99 doesn't matter to the user."
- "For multi-region, notifications are region-local but the user may travel — I'll
  replicate asynchronously and accept that a just-sent notification might lag by a
  few hundred ms across regions, which is acceptable for this use case."
- "On cost: fanning out to a 10M-follower account on every post is expensive — I'll
  use a hybrid push/pull model, pushing for normal accounts and pulling for
  celebrities."
- "For observability: I'd alert on delivery-success-rate per provider, queue depth,
  and p99 end-to-end delivery latency. On an incident, on-call checks the DLQ size
  first."

Same core architecture. The difference is **operational ownership, failure
reasoning, cost, scale limits, and multi-region** — raised proactively.

---

## The Dimensions That Separate L5 from L6

| Dimension | L5 behavior | L6 behavior |
|-----------|-------------|-------------|
| **Driving** | Answers the interviewer's follow-ups | Drives the conversation; raises the next topic first |
| **Failure modes** | Discusses when asked | Proactively: "let me address what breaks here" |
| **Operational reality** | Focuses on the architecture | Owns deploy, monitor, scale, evolve |
| **Cost** | Rarely mentioned | Reasons about $ of fan-out, storage, egress |
| **Multi-region** | Single-region unless prompted | Raises replication, consistency across regions |
| **Trade-offs** | States the choice | States the choice **and what was sacrificed** |
| **Scale limits** | "It scales" | "Here's exactly where it breaks, and my fallback" |
| **Ambiguity** | Waits for requirements | Defines the problem; pushes back on bad requirements |
| **Cross-system** | Designs the one system | Discusses how it interacts with the org's other systems |
| **Build vs buy** | Reaches for a managed service | Reasons about when to build vs buy, and can build it |

---

## The "Push You Off the Shelf" Test

Google interviewers deliberately push candidates off memorized architectures. Real
reported examples:

- A candidate proposed a **graph database** to compute connection degrees. The
  interviewer said to set the black box aside and show the **actual graph
  algorithm**.
- A candidate reached for a **managed stream processor** on a logging pipeline. The
  interviewer asked how he'd **implement the processing himself**.

The signal: do you understand what's happening *inside* the tools, or can you only
name them? This is the clearest L5/L6 discriminator, and it's why this repo has a
large `fundamentals/` section — every black box must be openable.

**L5 move:** "I'll use Kafka for the event stream."
**L6 move:** "I'll use a log-based message queue — Kafka-style. The key property I
need is partitioned ordering: events with the same key land on the same partition
and are consumed in order. Under the hood that's an append-only commit log per
partition with consumer offsets; if I had to build it, I'd..."

---

## Staff+ Scope: Beyond a Single System

At the true staff level, the interviewer may go beyond one system:

- **"Design a platform that supports multiple teams."** Now you're reasoning about
  APIs as products, multi-tenancy, isolation, and self-service.
- **"Evolve this existing system to meet a new requirement."** Tests whether you can
  navigate legacy constraints, not just greenfield design.

What earns a strong-hire here:
- You **define the problem before solving it**.
- You **push back on requirements** that don't make engineering sense.
- You discuss **how the system interacts with other systems** in the org.
- You reason about **build vs buy** explicitly.

---

## The Proactive Phrases That Signal Staff

Keep these in your pocket. Saying them *before* being asked is the tell:

- "Before I move on, let me address the failure scenario for this component."
- "Here's where this design breaks, and what I'd do instead."
- "The trade-off I'm making is X — I'm giving up Y to get Z."
- "On cost, the expensive part here is..."
- "For multi-region, the concern is..."
- "For observability, I'd alert on... and on-call would check... first."
- "Let me push back on that requirement — I don't think we need strong consistency
  here, and eventual buys us a lot."

The hiring committee reads a written packet. **The interviewer can only write down
what you said.** Vague answers produce vague evidence, weighted lightly. Specific,
quotable, proactive statements are what get you the L6 packet.

---

## The Bottom Line

L5 is "can you produce a correct design?" L6 is "can you own this system in
production, anticipate how it fails, reason about its cost, and drive the technical
conversation?" Study the architecture to pass L5. Study the **failure modes,
operations, and trade-offs** to pass L6.
