# Data Distribution — Overview

Once your data outgrows a single machine — for storage, throughput, or availability —
you distribute it across many. That single move creates every hard problem in this
module: split the data and queries get complicated; copy the data and keeping copies in
sync trades off against availability and latency. This is the material most HLD deep
dives are made of, so it's worth getting the mental model crisp.

---

## Why Distribute Data

One machine has ceilings: finite disk, finite CPU/IOPS, and it's a single point of
failure. Distributing data across nodes lets you:

- **Scale storage** — hold more data than one machine can.
- **Scale throughput** — spread reads and writes across many machines.
- **Stay available** — survive a node (or a whole datacenter) failing.
- **Reduce latency** — keep data geographically close to users.

---

## The Two Core Techniques

Almost everything here is a combination of two orthogonal ideas:

| | Partitioning (sharding) | Replication |
|--|------------------------|-------------|
| What | Split data into subsets, each on a different node | Keep **copies** of data on multiple nodes |
| On each node | *Different* data | The *same* data |
| Buys you | Storage + write scale | Availability, read scale, durability, locality |
| Introduces | Hot spots, cross-shard queries, rebalancing | Consistency problems, replication lag, conflicts |

- **Partitioning** answers "the data is too big / too many writes for one node" — split
  it up.
- **Replication** answers "I need it available, fast to read, and safe" — copy it.

They're **combined** in practice: shard the dataset, and replicate each shard.

```
        Shard A            Shard B            Shard C
   ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
   │ leader  ──┐   │  │ leader  ──┐   │  │ leader  ──┐   │
   │ follower  │   │  │ follower  │   │  │ follower  │   │   partition across shards,
   │ follower ◄┘   │  │ follower ◄┘   │  │ follower ◄┘   │   replicate within each
   └───────────────┘  └───────────────┘  └───────────────┘
```

---

## The Central Tension (the thread through every tab)

The moment data lives in more than one place, a question appears: when a write hits one
copy, what do the others show, and what happens if they can't talk to each other? You
**cannot** have perfect consistency, perfect availability, and low latency all at once
across a network. Every choice in this module — which replication topology, which
consistency model, CP vs AP — is a point on that trade-off surface, formalized by **CAP
and PACELC**. Keep that tension in mind; it's what ties partitioning, replication,
consistency, and consensus together.

---

## A Note on Boundaries (avoid two common confusions)

- **This module vs Storage & databases:** that module was about *choosing and using* a
  database; this one is about *scaling it across machines*. They stack.
- **Distributed consistency vs transaction isolation:** the Storage module's isolation
  levels are about *concurrent transactions on one database*. The consistency models
  here are about *replicas of data agreeing across machines*. Related, but different
  questions — and the word "consistency" means something different again in ACID
  (constraints) and in CAP (linearizability). This module flags which is which.

---

## What's Ahead

- **Partitioning & sharding** — how to split data, and the hot-spot and cross-shard
  problems it creates.
- **Replication** — leader-follower, multi-leader, leaderless; sync vs async; lag.
- **Consistency models** — strong to eventual, and the useful middle grounds.
- **CAP & PACELC** — the theorems that name the trade-off.
- **Consensus** — how nodes agree despite failures (Raft), and where it's used.
