# Sharded Database Platform — Deep Dives

## 1. Online Resharding (the hardest feature)

Split shard `-80` into `-40` and `40-80`, with live traffic, losing no writes, with a cutover
measured in seconds.

```
1. PROVISION   create target shards, replicate the schema
2. COPY        bulk-copy rows whose keyspace ID falls in each new range   (hours)
3. CATCH UP    tail the source's change stream (binlog/WAL) and apply     (continuous)
               → targets converge to "source minus a few ms"
4. VERIFY      checksum source vs targets over the key ranges             ← THE SAFETY GATE
5. SWITCH READS   move replica reads to the targets       (reversible, low risk)
6. SWITCH WRITES  ┌ acquire the keyspace lock
                  │ STOP writes to the source (deny new, drain in-flight)
                  │ wait for targets to fully catch up   ← the only pause, ~seconds
                  │ atomically update the topology shard map
                  └ resume — writes now land on the targets
7. RETIRE      keep the source read-only for a while, then decommission
```

**Why this order:** every step before 6 is reversible and does no harm. Step 4 is what makes step 6
safe — **never cut over on faith**. And splitting reads (5) from writes (6) means you find routing
bugs while they're still harmless.

**The pause in step 6 is unavoidable** — you cannot atomically move a moving target. The trick is to
make it *small*: by then the targets are milliseconds behind, so the freeze is only as long as the
final drain.

> **This pattern generalises far beyond databases** — it's the same skeleton as a live table
> migration, a cache warm-and-swap, and the
> [dictionary rollout in the Text Segmentation Service](#hld-text-segmentation-service):
> **copy → catch up → verify → switch → retire.**

---

## 2. Failover Without Losing Writes

The requirement is **zero acknowledged-write loss**, and naive failover violates it constantly.

**The failure to prevent:** a primary is partitioned (not dead). The orchestrator promotes a replica.
The old primary is still accepting writes from clients that can reach it. **Two primaries. Divergent
data. Unrecoverable.**

**Three mechanisms, all required:**

1. **Primary identity is a lease, not a flag.** The primary must renew a TTL lease in the topology
   service. **If it can't renew, it demotes itself** — even if it thinks it's healthy. Self-demotion
   on lease expiry is what makes a partitioned primary safe.
2. **Fencing.** Before promoting, actively disable the old primary — revoke its lease, block it at
   the network/proxy layer, or set it read-only. Never rely on it being dead.
3. **Choose the most up-to-date replica.** Compare replication positions (GTIDs) and promote the one
   with the most committed transactions; then reparent the others to it. Promoting a lagging replica
   *is* write loss.

**The durability chain:** a write is only acknowledged after it's durable on the primary **and**
(for semi-synchronous setups) acknowledged by at least one replica. **Async replication means
failover can lose the tail of the log** — that's the trade, and it must be a conscious one.

| Mode | Failover write loss | Write latency |
|------|--------------------|---------------|
| Async | **possible** (lag window) | lowest |
| **Semi-sync** (≥1 replica acks) | none, if you promote an acked replica | +1 RTT |
| Fully sync | none | slowest; availability suffers |

---

## 3. Secondary Indexes Across Shards

The unavoidable problem: you shard `users` by `user_id`, then need `WHERE email = ?`.

| Approach | How | Cost |
|----------|-----|------|
| **Scatter-gather** | query every shard | O(shards); fine when rare |
| **Lookup vindex** | a *table* mapping `email → keyspace ID`, itself sharded by email | 2 round trips; **must stay in sync** |
| **Duplicate table** | a second copy sharded by email | double writes and storage |

**The lookup vindex is the standard answer, and its hard part is consistency.** Inserting a user must
write both the row and the lookup entry — across two shards. Options: 2PC (slow), or **write the
lookup first and treat a dangling entry as tolerable** (it points at a row that doesn't exist yet;
the reader just misses). Ordering the writes so the *survivable* inconsistency is the one that
happens is the real design move.

---

## 4. Cross-Shard Transactions

| Approach | Guarantees | Reality |
|----------|-----------|---------|
| **Keep it in one shard** | full ACID | **the actual answer** — choose the shard key so transactional data co-locates |
| **2PC** | atomic | slow; blocks on coordinator failure; a new failure mode |
| **Saga** (compensating actions) | eventual | complex, but no blocking |
| **Best-effort multi-shard** | none | fine for independent writes |

> **A cross-shard transaction is usually a shard-key smell.** If orders and order-items must commit
> together, they should share a shard key (both by `user_id`), not span shards. **Fix the layout,
> don't reach for 2PC.**

---

## 5. Connection Pooling — the Unglamorous Load-Bearing Feature

The arithmetic that motivates it:

```
1,000 app servers × 100 connections each = 100,000 connections
MySQL: ~256 KB–1 MB per connection ⇒ 25–100 GB of RAM  ← the server dies
```

The sidecar accepts all 100,000 and multiplexes them onto **a few hundred** real MySQL connections.
Because a typical query is sub-millisecond, a small pool serves enormous concurrency.

**This is why "just add a proxy" isn't a joke — it's often the entire fix.** Many teams that think
they need sharding actually need connection pooling. **It's also the cheapest thing on the ladder,
so check it first.**

---

## 6. Online Schema Change

`ALTER TABLE` on a billion rows locks the table for hours. The platform must do better:

```
create a shadow table with the new schema
  → copy rows in chunks (throttled by replication lag)
  → triggers/CDC keep the shadow in sync with ongoing writes
  → verify
  → atomic rename swap
```

Same **copy → catch up → verify → switch** skeleton as resharding. That's not a coincidence — it's
the general solution to *"change something big while it's in use."*

---

## 7. Hot Shards

Even hash sharding produces hotspots when a single key is hot (a celebrity user, a giant tenant).

| Fix | Trade-off |
|-----|-----------|
| Split the shard further | doesn't help — **one key can't be split** |
| Cache the hot key | staleness; the standard first move |
| **Give it a dedicated shard** | operational special-casing, but effective |
| Sub-shard the key (`user_id:bucket`) | app complexity; changes the access pattern |

**The one-key hotspot is the case sharding fundamentally cannot solve** — worth saying out loud,
because it shows you understand the limits of the technique rather than treating it as a cure-all.
