# Managed & Decoupled — RDS and Aurora

Two very different products that both say "managed relational database." The difference is
architectural, and it's the most useful cloud-database distinction to actually understand.

---

## RDS — A Managed Instance

RDS runs **stock MySQL/PostgreSQL on an EC2 instance with EBS storage**, and automates the
operational chores: provisioning, patching, backups, monitoring, and failover.

```
┌──────────────────────┐        ┌──────────────────────┐
│  Primary (AZ-a)      │ ─sync─▶│  Standby (AZ-b)      │   Multi-AZ = ONE standby,
│  MySQL + EBS volume  │        │  MySQL + EBS volume  │   synchronous, NOT readable
└──────────┬───────────┘        └──────────────────────┘
           │ async
           ▼
   Read replicas (own full copy of the data, independently)
```

**What it is:** the same database you'd run yourself, with the toil removed.
**What it isn't:** a different architecture. Every scaling limit of single-node MySQL is still
there.

| Property | Value |
|----------|-------|
| Write scaling | **one node** — vertical only |
| Read scaling | replicas, each a **full independent copy** |
| Failover (Multi-AZ) | promote the standby — typically **60–120 s** |
| Replica lag | async — seconds |
| Storage | EBS volume per instance; resizing is an operation |

**The key structural fact: each replica has its own complete copy of the data.** Adding a replica
means copying the whole dataset; storage cost multiplies by replica count; and a failover means
promoting a machine that holds its own storage.

---

## Aurora — Decoupled Compute and Storage

Aurora keeps the MySQL/PostgreSQL **query engine** but replaces the **storage engine** with a
purpose-built, multi-tenant, distributed storage service.

```
   ┌─────────┐   ┌─────────┐   ┌─────────┐        compute nodes hold NO data
   │ Writer  │   │Reader 1 │   │Reader N │  ...   (up to 15 readers)
   └────┬────┘   └────┬────┘   └────┬────┘
        │ redo log     │ reads       │ reads
        ▼              ▼             ▼
   ╔═══════════════════════════════════════════╗
   ║   SHARED DISTRIBUTED STORAGE SERVICE      ║
   ║   10 GB segments, each replicated 6 ways   ║
   ║   across 3 AZs (2 copies per AZ)           ║
   ║   write quorum 4/6 · read quorum 3/6       ║
   ╚═══════════════════════════════════════════╝
```

### The Central Idea: *"The log is the database"*

In classic MySQL, one application write causes **many** physical writes — data pages, double-write
buffer, binlog, redo log — and replication multiplies all of it across the network.

Aurora's insight: **the only writes that cross the network are redo log records.** The storage
service applies the log and materialises pages *itself*, in the background and on demand.

**Why that matters:** the paper's framing is that the bottleneck in high-throughput data processing
has moved **from compute and storage to the network**. Once you accept that, sending pages over the
network is the thing to eliminate — and the log is the smallest thing that fully describes a change.

### The Quorum, and Why 6 Copies

Data is split into **10 GB segments**, each replicated **6 ways across 3 AZs (2 per AZ)** — a
*Protection Group*. Writes need **4 of 6**; reads need **3 of 6**.

The design goal is **AZ + 1**: survive losing an entire availability zone *plus one more node*
without data loss, and losing a whole AZ without losing write availability.

- With 2 copies per AZ, an AZ failure removes 2 → 4 remain → **writes still succeed**.
- The 10 GB segment size is chosen to make **MTTR** small: a small segment is re-replicated fast,
  which shrinks the window in which a second failure becomes a double fault.

> **The generalisable move:** you can't easily reduce MTTF, so **reduce MTTR instead**. Smaller
> repair units = faster repair = less overlap between failures. That reasoning applies far beyond
> databases.

### Consequences of the Architecture

| Consequence | Why |
|-------------|-----|
| **Readers are cheap** | they attach to the *same* storage — no data copy per replica |
| **Failover is fast** (~30 s or less) | the new writer already sees the same storage; nothing to copy |
| **Replica lag is low** (typically ms) | replicas read shared storage, they don't replay a binlog |
| **Backups are continuous** | done by the storage layer, no impact on the engine |
| **Storage autogrows** | it's a service, not a volume |
| **Writes still go to ONE node** | Aurora scales *storage and reads*, **not writes** |

**That last row is the one people miss.** Aurora is a magnificent answer to "my storage, reads,
durability, and failover time are the problem." It is **not** an answer to "I have too many
writes." For that you still need sharding — which is why Vitess exists, and why Aurora offers
Limitless/multi-master variants that reintroduce sharding above it.

---

## RDS vs Aurora — Choosing

| | RDS | Aurora |
|---|---|---|
| Engine | stock MySQL/Postgres | MySQL/Postgres-compatible, custom storage |
| Storage | EBS volume per node | **shared distributed service** |
| Read replicas | full copies, async | **share storage**, low lag, up to 15 |
| Failover | 60–120 s | **~30 s or less** |
| Write scaling | one node | **one node** (unchanged) |
| Cost | lower | higher per hour; often cheaper at scale (no per-replica storage) |
| Portability | it *is* MySQL | compatible, but AWS-only |

**Use RDS** when you want a normal database without toil, or need exact engine compatibility /
portability. **Use Aurora** when failover time, replica lag, read fan-out, or storage growth are
your pain. **Neither fixes write throughput** — that's the next two tabs.

---

## Reference

- **A. Verbitski et al. (2017), "Amazon Aurora: Design Considerations for High Throughput
  Cloud-Native Relational Databases,"** *SIGMOD '17*, pp. 1041–1052.
  DOI: [10.1145/3035918.3056101](https://doi.org/10.1145/3035918.3056101). The source for the
  quorum design, segment sizing, and the "only redo log records cross the network" architecture.
