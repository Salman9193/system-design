# Overview — Where Is Your Scaling Boundary?

The [Storage & Databases](#fu-storage-and-databases) and [Data Distribution](#fu-data-distribution)
sections cover the **theory**: sharding strategies, replication topologies, consistency models,
CAP. This section covers what actually happens when you apply that theory in production — and how
the systems you'll be asked about (**RDS, Aurora, Vitess, Bigtable, Spanner, DynamoDB**) each draw
the line differently.

---

## The One Question That Organises Everything

> **Where does your system stop being one machine?**

Every database scaling architecture is an answer to that. The interesting part is that there are
only a handful of distinct answers, and each one moves the boundary to a different place:

| Boundary | What scales out | Example |
|----------|-----------------|---------|
| **Nothing** — one box, made bigger | nothing | RDS single instance |
| **Reads** — copies of the whole dataset | read throughput | RDS + read replicas |
| **Storage** — compute and storage split apart | storage, durability, read replicas | **Aurora** |
| **Tables** — different tables on different DBs | independent domains | vertical / functional split |
| **Rows** — one table across many DBs | reads **and writes** | **Vitess**, Citus |
| **Everything, from day one** | reads, writes, storage | **Bigtable**, Spanner, DynamoDB |

Each rung costs you something you had on the rung below — usually a **transaction guarantee** or a
**query capability**. The whole skill is knowing which rung you actually need and refusing to climb
higher.

---

## The Convergent Insight

Three of these systems arrive at the *same* structural idea from completely different directions:

```
         MONOLITHIC RDBMS                    DISTRIBUTED FILESYSTEM
                 │                                     │
     Aurora: push storage DOWN               Bigtable: build UP from
     into a distributed service              a shared storage layer
                 │                                     │
                 └──────────► SEPARATE COMPUTE ◄───────┘
                              FROM STORAGE
                                     ▲
                                     │
                     Vitess: keep the monolith, add a
                     ROUTING LAYER ABOVE it  (the exception)
```

**Aurora and Bigtable converge on compute/storage separation** — Aurora by taking MySQL and pushing
the storage engine into a multi-tenant distributed service, Bigtable by building tablet servers on
top of GFS/Colossus from the start. Neither node owns its data; both can therefore fail over in
seconds, because there's nothing to copy.

**Vitess is the interesting counter-example.** It doesn't change MySQL at all — it puts a *query
router* in front and an *orchestrator* around it. That's a fundamentally different bet: preserve the
engine you know, pay the complexity in the layer above.

---

## Why This Section Exists

In a Staff-level interview, "we'll shard the database" is not an answer — it's the *beginning* of
one. The follow-ups are all operational:

- What's the shard key, and what happens when it's wrong?
- How do you **resplit a shard** with live traffic on it?
- What breaks when a query spans shards?
- How does a failover work, and how long is the write outage?
- Who decides which replica a read goes to?

**These are the questions that separate people who've read about sharding from people who've done
it** — and they're exactly what the systems in this section were built to answer.

---

## What's Ahead

| Tab | Covers |
|-----|--------|
| **The Scaling Ladder** | rung by rung: what breaks, and the fix — the canonical YouTube/MySQL narrative |
| **Managed & Decoupled** | RDS (managed instance) → Aurora (*"the log is the database"*) |
| **Sharding Middleware** | Vitess: VTGate, VTTablet, resharding, reparenting |
| **Native Scale-Out** | Bigtable, Spanner, DynamoDB — scale-out as the starting assumption |
| **Choosing** | the decision framework, comparison table, and interview framing |
