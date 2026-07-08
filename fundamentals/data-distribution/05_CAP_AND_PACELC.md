# CAP & PACELC

CAP is the most-cited and most-*mis*-cited theorem in system design. Stated well, it
names the fundamental trade-off in a replicated system; stated badly ("pick 2 of 3"),
it misleads. PACELC extends it to describe the trade-off you actually make every day.
Getting this right — especially raising PACELC — is a clear staff-level signal.

---

## The CAP Theorem

In a distributed (replicated) system, when a **network partition** happens — nodes
can't all talk to each other — you must choose between:

- **Consistency (C)** — every read sees the latest write (linearizability); all nodes
  agree.
- **Availability (A)** — every request gets a non-error response.

You **cannot have both during a partition**. A node cut off from its peers either:

- **refuses to answer** (or errors) to avoid serving stale/divergent data → keeps **C**,
  sacrifices **A**; or
- **answers anyway** with possibly-stale data → keeps **A**, sacrifices **C**.

### The crucial clarifications (say these)
- **Partition tolerance isn't optional.** In any real distributed system, partitions
  *will* happen, so "P" is a given — the real choice is **C or A, only during a
  partition.**
- It is **not** "pick 2 of 3 always." CAP only describes behavior **while partitioned**;
  the rest of the time you can have both.
- CAP's **C is linearizability**, not ACID's consistency. Same word, different meaning.

---

## CP vs AP Systems

Systems lean one way when partitioned:

| | CP (consistency-first) | AP (availability-first) |
|--|------------------------|-------------------------|
| During a partition | Refuse/limit requests to stay consistent | Stay up, allow stale/divergent reads |
| Good for | Correctness-critical: config, locks, balances, uniqueness | Always-on: feeds, carts, counters, sessions |
| Examples (lean) | Systems using consensus (etcd/ZooKeeper), Spanner, HBase | Dynamo-style, Cassandra (tunable), Riak |

"Lean" matters — many systems are **tunable** (Cassandra can be more CP or AP per query
via quorums), so the label describes a default, not a law.

---

## PACELC — The Part CAP Leaves Out

CAP only talks about partitions, which are **rare**. But there's a trade-off you make
**constantly, even when everything is healthy**. PACELC captures it:

> **If** there is a **P**artition, choose **A** or **C** — **E**lse (normal operation),
> choose **L**atency or **C**onsistency.

The "else" is the insight: **even without a partition, strong consistency costs
latency**, because keeping replicas linearizable requires coordination (waiting for a
quorum, a round trip to the leader). So every replicated system also chooses, in the
common case, between being fast and being strongly consistent.

| Classification | Partition → | Normal → | Example (lean) |
|----------------|-------------|----------|----------------|
| **PA/EL** | Availability | Latency | Dynamo, Cassandra |
| **PC/EC** | Consistency | Consistency | Spanner, etcd |
| **PA/EC** | Availability | Consistency | (mixed) |
| **PC/EL** | Consistency | Latency | (mixed) |

- **PA/EL** — available under partition, low-latency normally (accepts staleness). The
  "web scale, always-on" profile.
- **PC/EC** — consistent always, paying latency for it. The "correctness matters"
  profile.

---

## The Practical Takeaway

- Partitions are real but rare; the **everyday** decision is PACELC's "else": **latency
  vs consistency**. You make it far more often than the CAP one.
- The choice is **per workload, not per system** — you can be **PC/EC for balances and
  PA/EL for feeds** in the same product, backed by different stores or tunable quorums.

> The staff move: "I frame it as PACELC, not just CAP, because partitions are rare but
> the latency-versus-consistency trade is constant. For money and uniqueness I take PC/EC
> — pay the coordination latency, and refuse rather than diverge during a partition. For
> feeds, counters, and sessions I take PA/EL — stay fast and available, tolerate
> staleness. Same product, different choices per data type. Saying 'this system is CP'
> is too coarse; the decision lives at the data level."

---

## The Summary

- **CAP:** during a **partition**, choose **C or A** — and P is mandatory, so it's really
  C-vs-A *while partitioned*, not "2 of 3."
- **CP vs AP:** refuse-to-stay-correct vs stay-up-and-diverge; many systems are tunable.
- **PACELC:** adds the everyday trade — **Else, Latency vs Consistency** — which you make
  constantly, partition or not.
- **Decide per workload:** strong/consistent where correctness demands it, available/
  low-latency where staleness is harmless.
