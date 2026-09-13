# Notification Service — Scaling

The system ingests a firehose of events but makes an inherently **per-user** decision on each — so it
scales the way both ATC and CCG scale: **partition by recipient, keep the decision state local, and
let a durable log absorb the spikes.**

---

## The Three Tiers

| Tier | Scales by | Constraint |
|------|-----------|-----------|
| **Ingest / Kafka** | add partitions/brokers | ordering is per-partition (fine — we key by user) |
| **Decision tier** | add hosts; **partitioned by user id** | per-host local state size |
| **Delivery** | stateless workers | **provider** rate limits (external) |

**The decision tier is the interesting one, and partitioning by user id is what makes it scale.** All
of one user's context (preferences, caps, history, scores) lives on one host, so a decision is a
**local read (~ms)**, not a fan-out of remote calls. Add users → add partitions → add hosts. Linear,
because users are independent.

---

## Why Local State Is the Load-Bearing Choice

At 100k decisions/sec, the difference between a **~2 ms local RocksDB read** and a **10–100 ms remote
call** is the difference between a fleet of tens of hosts and one of thousands (or a system that
simply can't keep up). ATC puts member tracking events, device info, and relevance scores into a
**local RocksDB on SSD**, reachable because everything for a member is on the same host via
partition-by-member-id.

**The offline/online split:** heavy work (training relevance models, computing scores) runs
**offline** and **pushes results into the local store**; the online decision path only *reads* them.
The hot path never trains a model or makes a slow call — the same "keep the serving path a fast pure
function" principle as the [rate limiter](#hld-distributed-rate-limiter) and
[LLM serving](#hld-llm-inference-serving) HLDs.

---

## Absorbing Fan-Out Spikes

A broadcast can enqueue tens of millions of per-user decisions in seconds. Scaling for that means
**decoupling ingest rate from decision rate** with the durable log:

- Kafka takes the write spike at firehose speed;
- the decision tier drains at its sustainable rate (a broadcast is rarely urgent, so a few minutes of
  drain is fine);
- **transactional traffic rides a separate, higher-priority topic** so it's never stuck behind a
  marketing broadcast.

**The counter-intuitive bit:** you do *not* size the decision tier for peak broadcast rate — you size
it for sustainable throughput and let the log buffer the peaks, because marketing latency is elastic.
Only transactional needs peak provisioning, and it's a small fraction of volume.

---

## Scheduling at Scale

The scheduler fires tens of thousands of triggers/sec. The CCG pattern: bucket (push, time)
assignments into a **Kafka topic per time slot**, and a workflow engine enables consumption of each
slot's topic as its time arrives — turning "wake up N million scheduled pushes at 9am" into
"start consuming the 9am topic," which is just throughput, not a thundering herd of timers.

---

## Cost Notes

- **SMS is the expensive channel** (per-message carrier cost); push is nearly free. Channel selection
  is a **cost** lever, not just a UX one — pushing instead of texting saves real money at billions/mo.
- **The decision tier's cost is dominated by state, not compute** — local SSD state per user. Keeping
  per-user state small (short-TTL history, compact scores) is the main cost control.
- **Suppression saves money twice:** every notification the relevance layer drops is a provider send
  you don't pay for *and* a step toward the fatigue budget — the rare optimization that improves cost
  and UX at once.
