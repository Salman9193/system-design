# Replication

Replication keeps copies of data on multiple nodes — the source of availability, read
scaling, durability, and geo-locality. The catch is that copies can disagree: which
node accepts writes, whether you wait for copies to sync, and how far replicas lag are
all trade-offs that lead straight into consistency. This tab covers the topologies and
their costs.

---

## Why Replicate

- **Availability** — a node (or datacenter) fails and the data is still served from a
  copy.
- **Read scaling** — serve reads from many replicas, multiplying read throughput.
- **Durability** — more copies, less chance of losing data.
- **Geo-locality** — a replica near the user cuts read latency (Networking).

---

## Replication Topologies

The key question is **which node(s) accept writes**.

| Topology | Writes go to | Pros | Cons |
|----------|-------------|------|------|
| **Single-leader** | One leader; followers replicate | Simple; no write conflicts | Leader is a write bottleneck / failure point |
| **Multi-leader** | Several leaders (e.g. per region) | Write availability & locality | **Write conflicts** to resolve |
| **Leaderless (quorum)** | Any replica; read/write quorums | High availability; no failover | Client/coordinator does more work; tunable-but-tricky consistency |

### Single-leader (leader-follower)
All writes go to the **leader**, which replicates them to **followers**; reads can come
from any replica. Simple and conflict-free (one source of truth for writes), which is
why it's the most common default. On leader failure, a follower is **promoted**
(failover).

### Multi-leader
Multiple nodes accept writes — typically **one leader per region**, so each region
writes locally (great for latency and write availability). The price: the same record
can be written in two places at once, producing **write conflicts** you must resolve
(last-write-wins — which loses data; application merge logic; or CRDTs).

### Leaderless (quorum-based)
Any replica accepts reads and writes; the client (or a coordinator) writes to several
replicas and reads from several, using **quorums** to get consistency (Dynamo-style —
see Consistency models). No leader means no failover step and high availability, at the
cost of more client-side coordination.

---

## Synchronous vs Asynchronous Replication

Orthogonal to topology: does the leader **wait** for followers before confirming a
write?

| | Synchronous | Asynchronous |
|--|-------------|--------------|
| Leader confirms | After follower(s) ack | Immediately, replicates in background |
| Durability/consistency | Stronger — copy exists elsewhere | Weaker — unreplicated writes can be lost on failure |
| Latency | Higher (waits for replica) | Lower |
| Availability | A slow/dead follower can stall writes | Writes proceed regardless |

- **Async** is common for its low latency, accepting **replication lag** and the risk of
  losing the last few writes if the leader dies before replicating.
- **Sync** guarantees the write survives on another node but ties write latency and
  availability to a follower.
- **Semi-synchronous** (wait for *at least one* follower) is a practical middle ground:
  durability of one synced copy without waiting for all.

---

## Replication Lag & Its Anomalies

With async replication, followers **lag** the leader — and reading a lagging follower
causes user-visible bugs:

- **Read-your-writes violation** — you post a comment (write to leader), then your feed
  reads a stale follower and your comment is missing.
- **Monotonic-read violation** — successive reads hit followers at different lag, so you
  see data, then see it *disappear* (time going backwards).

Mitigations (these are consistency guarantees, next tab): read your own recent writes
from the leader (or a synced replica), pin a user to one replica (sticky routing), or
track write versions and wait for the replica to catch up.

---

## Failover (single-leader's tricky part)

When the leader dies, promote a follower — but this is where things break:

- **Detecting failure** vs a slow leader (false positives).
- **Split-brain** — two nodes both think they're leader and both accept writes (needs
  fencing / consensus to prevent).
- **Lost writes** — an async follower promoted before it received the leader's last
  writes loses them.
- **Choosing the most up-to-date** follower to promote.

Robust failover leans on **consensus** (final tab) for leader election and fencing.

> The staff move: "I default to single-leader replication — one source of truth for
> writes, no conflicts, and it's simple — with async replication for latency, while
> handling read-your-writes by serving a user's own recent reads from the leader. I go
> multi-leader or leaderless only when write availability or multi-region write latency
> demands it, and then I'm explicit about conflict resolution. And I back failover with
> consensus, because split-brain is the failure that corrupts data."

---

## The Summary

- **Replication buys** availability, read scale, durability, locality — at the cost of
  keeping copies in sync.
- **Topologies:** single-leader (simple, default), multi-leader (write locality, but
  conflicts), leaderless (HA via quorums).
- **Sync vs async:** durability/consistency vs latency/availability; semi-sync is the
  middle.
- **Async lag** breaks read-your-writes and monotonic reads — fix with routing/quorum
  guarantees.
- **Failover** is subtle (split-brain, lost writes) and wants **consensus** behind it.
