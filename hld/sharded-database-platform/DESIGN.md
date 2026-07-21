# Sharded Database Platform — Design

## The Fundamental Split: Control Plane vs Data Plane

Everything follows from this. They have opposite requirements:

| | **Control plane** | **Data plane** |
|---|---|---|
| Handles | topology, resharding, failover, schema | actual queries |
| Traffic | rare, small | **constant, huge** |
| Consistency | **strongly consistent** (one truth about who's primary) | eventual is fine |
| Availability need | high | **extreme** |
| If it's down | no new operations | **the product is down** |

> **The rule that makes the system survivable: the data plane must keep serving when the control
> plane is down.** Routers cache the topology and keep using the last-known-good map. A failed etcd
> should stop *resharding*, not *queries*.

---

## Architecture

```
   Application (plain MySQL/Postgres wire protocol)
        │
   ┌────▼──────────────────────────────────────┐
   │  ROUTER TIER  (stateless, horizontally     │   DATA PLANE
   │  scaled)                                   │
   │   • parse SQL  • consult cached topology   │
   │   • route / scatter-gather / merge         │
   │   • pick primary vs replica                │
   └────┬───────────────────────────────────────┘
        │
   ┌────▼─────┐  ┌──────────┐  ┌──────────┐
   │ SIDECAR  │  │ SIDECAR  │  │ SIDECAR  │   one per DB instance
   │  MySQL   │  │  MySQL   │  │  MySQL   │   • conn pooling • query guards
   │ shard -80│  │shard 80- │  │ replica  │   • health • backup • CDC source
   └──────────┘  └──────────┘  └──────────┘
        ▲              ▲              ▲
        │   watch      │              │
   ┌────┴──────────────┴──────────────┴────┐
   │  TOPOLOGY SERVICE (etcd / ZooKeeper)  │   CONTROL PLANE
   │   shard map · primary leases · locks   │
   └────┬───────────────────────────────────┘
        │
   ┌────▼───────────────────────────────────┐
   │  ORCHESTRATOR (workflow engine)         │
   │   reshard · failover · backup · schema  │
   └─────────────────────────────────────────┘
```

---

## Component 1 — The Router (stateless)

The application's entire view of the database.

**What it must do:**
- **Parse SQL properly.** A real parser, not pattern matching — you need the AST to find the shard
  key, rewrite queries, and plan merges.
- **Plan the query:**
  - shard key present and equality ⇒ **single-shard** (the fast path, target >95% of traffic)
  - shard key range ⇒ **subset of shards**
  - no shard key ⇒ **scatter-gather** across all shards, then merge
- **Merge results** — `ORDER BY`, `LIMIT`, and aggregates must be recomputed after gathering.
  (`LIMIT 10` across 8 shards means fetching 10 from each and re-sorting; `AVG` must be decomposed
  into `SUM`/`COUNT` — **you cannot average the averages.**)
- **Choose the target** — primary or replica, per query annotation or policy.

**Why stateless matters:** routers hold only cached topology, so you scale them like web servers and
lose them without consequence. **All the durable state lives in the topology service and the
databases.**

## Component 2 — The Sidecar (per instance)

Colocated with each database. It's the workhorse, and its jobs are mostly about **protecting MySQL
from the application**:

| Job | Why |
|-----|-----|
| **Connection pooling** | per-connection memory is what kills MySQL first (see Deep Dives) |
| **Query guards** | reject missing `LIMIT`, enforce timeouts, cap open transactions, blacklist |
| **Result consolidation** | identical in-flight queries share one execution |
| **Row cache** | cache by primary key; invalidate from the replication stream |
| **Health reporting** | feeds failover decisions |
| **Backup & CDC** | streams changes for resharding and for downstream consumers |

## Component 3 — Topology Service

A small, strongly consistent store (etcd/ZooKeeper/Consul) holding:
- the **shard map** (keyspace → shards → key ranges → instances)
- **who is primary**, as a **lease**, not a flag
- **distributed locks** serialising dangerous operations

**This is the system's brain and its most dangerous dependency.** Two rules:
1. **Never in the query hot path** — routers watch and cache; a topology outage must not stop reads.
2. **Primary identity is a lease with a TTL**, so a partitioned primary *self-demotes* when it can't
   renew. That's what prevents two primaries.

## Component 4 — Orchestrator

A **workflow engine** running long, resumable, multi-step operations: reshard, failover, backup,
schema change. These take hours and must survive the orchestrator restarting, so each is a durable
state machine with idempotent steps — the same pattern as the
[Job Scheduler LLD](#lld-job-scheduler).

---

## The Sharding Model

**Keyspace** = a logical database. **Shard** = a key range within it. The shard key is mapped by a
**vindex** — a function from a column value to a shard:

| Vindex type | Mapping | Use |
|-------------|---------|-----|
| **Hash** | `hash(user_id)` → range | even spread; the default |
| **Range** | `created_at` → range | time-series; **risks hot shards** |
| **Lookup** | a *table* mapping value → shard | secondary access paths (see Deep Dives) |

**Keyspace IDs, not shard numbers.** Rows map to a *keyspace ID* (say a 64-bit hash), and shards own
**ranges of keyspace ID** (`-80`, `80-`). Resharding then just re-partitions the ranges — no row
needs a new identity. **If you map rows directly to shard numbers (`user_id % N`), changing `N`
rewrites everything.** This indirection is the single most important schema decision in the platform.

---

## Query Flow

```
SELECT * FROM orders WHERE user_id = 42
  → parse → user_id is the shard key → hash(42) → keyspace ID → shard 80-
  → single shard → sidecar → MySQL → return                       ~1 ms

SELECT COUNT(*) FROM orders WHERE status = 'open'
  → parse → no shard key → SCATTER to all shards
  → each returns a partial count → router SUMs them                ~slowest shard
```

**The second query is the one to design against.** It's correct, it's easy to write, and it gets
slower as you add shards. Making it *visible* (metrics, warnings, opt-in) is a platform
responsibility — see Failure Modes.
