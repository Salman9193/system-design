# Consensus

Consensus is how a group of nodes **agrees on a single value or an order of operations
despite failures** — the foundation under leader election, distributed locks, replicated
logs, and atomic commit. It's the hardest problem in the module, but you only need the
high-level shape: why it's needed, how a majority makes it work, what Raft does, and why
you use it sparingly.

---

## The Problem

Multiple nodes must **agree** — on who the leader is, on the order of writes in a log, on
whether a transaction commits — even though nodes crash, messages are lost or delayed,
and there's no shared clock. Getting independent, failure-prone machines to reach one
consistent decision is genuinely hard, and naive approaches (ask everyone, take a vote)
break under partitions and message loss.

**Where it's needed:**
- **Leader election** — agree on the single leader for single-leader replication (and
  fence out the old one to prevent split-brain).
- **Replicated logs** — agree on the exact order of operations across replicas.
- **Distributed locks / coordination** — one holder at a time.
- **Atomic commit** — agree to commit-or-abort a transaction across nodes.
- **Cluster membership / config** — agree on who's in the cluster.

---

## Quorums / Majorities — How Progress Is Made

Consensus algorithms make decisions by **majority (quorum)**: a decision is final once a
majority of nodes agree. With **2f + 1** nodes you tolerate **f** failures — a 5-node
cluster survives 2 failures and still has a majority (3) to make progress.

The consequence: consensus needs a **majority reachable** to proceed. If a partition
leaves no side with a majority, the cluster **stops accepting writes** rather than
diverge — that's **CP behavior** by design. Availability is traded for correctness.

---

## The Algorithms (high level)

- **Paxos** — the original, proven-correct consensus algorithm. Correct but famously hard
  to understand and implement, which spawned many variants.
- **Raft** — designed to be **understandable**, and now the common choice. It breaks
  consensus into three parts:
  1. **Leader election** — nodes elect a leader by majority vote, organized into
     **terms**; if the leader fails, a new election starts.
  2. **Log replication** — clients send commands to the leader, which appends them to its
     log and replicates to followers; an entry is **committed** once a majority have it.
  3. **Safety** — only a node with an up-to-date log can become leader, so committed
     entries are never lost or reordered.

You don't need the proof — you need to say *what* it guarantees (agreement on an ordered
log, tolerating a minority of failures) and *how* (an elected leader + majority commit).

---

## Where Consensus Shows Up

You rarely implement consensus; you **use systems built on it**:

- **Coordination services** — ZooKeeper, etcd, Consul — provide leader election, config,
  service discovery, and distributed locks as a service, backed by consensus.
- **The control plane** of distributed databases — metadata, shard assignment, leader
  election — runs on consensus.
- **Atomic commit** across shards can be backed by consensus (a more robust alternative
  to blocking two-phase commit).

---

## The Cost (why you use it sparingly)

Consensus isn't free:

- **Latency** — every decision needs a round trip to a majority. That's fine for
  occasional coordination, too slow for every data operation.
- **Availability** — it needs a majority alive; lose too many nodes (or partition badly)
  and it halts to stay correct.
- **Throughput** — funneling everything through one consensus group is a bottleneck.

So the pattern is: use consensus for the **control plane** — who's leader, config,
membership, locks — and keep the **data plane** (high-throughput reads/writes) off the
consensus path, using replication and quorums instead.

> The staff move: "Consensus is how nodes agree despite failures — in practice Raft, via
> an elected leader and majority commit, tolerating a minority failing. But it costs a
> majority round trip and halts without a majority, so I don't run bulk data through it.
> I use it for coordination — leader election, config, locks, membership — usually via
> etcd or ZooKeeper rather than rolling my own, and I keep the hot data path on
> replication and quorums. It's also what makes single-leader failover safe by fencing
> out a split-brain leader."

---

## The Summary

- **Consensus = agreement on a value/order despite failures** — under leader election,
  replicated logs, locks, atomic commit.
- **Majority-based:** 2f + 1 nodes tolerate f failures; no majority → it halts (CP).
- **Raft** (understandable) or Paxos: elected leader + majority-committed log + safety.
- **Used via** etcd/ZooKeeper/Consul for coordination; it backs failover and metadata.
- **Costly** (majority round trip, needs a majority alive) → use it for the control
  plane, keep the data plane on replication/quorums.
