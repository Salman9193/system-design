# Raft — Deep Dive

The [Consensus](#fu-data-distribution) tab gives the shape: an elected leader, majority commit, use
it sparingly. This tab is the **mechanism** — the level at which you could implement it, defend it in
a design interview, or follow along with **MIT 6.824**. If the overview said *"you don't need the
proof,"* this is the proof's worth of detail, kept practical.

> **Why this depth is worth it:** "we use etcd for leader election" is a fine sentence until an
> interviewer asks *how leader election actually stays safe under a partition.* Knowing Raft's three
> rules — and the one commitment subtlety almost everyone gets wrong — is what separates naming the
> tool from understanding it.

Source: Ongaro & Ousterhout, **"In Search of an Understandable Consensus Algorithm"** (USENIX ATC
2014). https://raft.github.io/raft.pdf · visualization: https://raft.github.io/

---

## The Problem Raft Solves: a Replicated Log

Everything reduces to one idea: **keep an identical, ordered log of commands on every server.** If
every server applies the same commands in the same order to the same starting state, they all end up
in the same state — this is the **replicated state machine** model. So "make the servers agree" becomes
"make the logs identical," and Raft is an algorithm for exactly that.

```
        ┌── log ──────────────────────────┐
 S1     │ x=1 │ y=2 │ x=5 │ z=9 │           │   apply in order → same state
 S2     │ x=1 │ y=2 │ x=5 │ z=9 │           │   as long as the logs match
 S3     │ x=1 │ y=2 │ x=5 │                 │   (S3 is one behind — catching up)
        └─────────────────────────────────┘
```

The whole job: get new entries into a **majority** of logs, in the same order, despite crashes,
lost messages, and no shared clock.

---

## Piece 1 — Leader Election

Raft uses a **strong leader**: all client writes go through the leader, and **log entries only flow
leader → follower, never the other way.** This is what makes it simpler than Paxos.

**Server states:** every server is a **follower**, **candidate**, or **leader**.

**Terms** are Raft's logical clock — a monotonically increasing number. Each term has **at most one
leader**. Terms let servers detect stale information: a message from an older term is rejected.

```
follower ──(no heartbeat before election timeout)──▶ candidate
candidate ──(wins majority of votes)──▶ leader
candidate/leader ──(sees a higher term)──▶ follower
```

**The election:**
1. A follower hears no heartbeat within its **election timeout** → bumps its term, becomes a
   candidate, votes for itself, and sends `RequestVote` RPCs.
2. Each server grants **at most one vote per term**, first-come — so **at most one candidate can win
   a majority.** That's what guarantees a single leader per term.
3. Win a majority → become leader, start sending heartbeats (empty `AppendEntries`) to suppress new
   elections.

**The split-vote problem and its fix:** if several followers time out at once, votes split and nobody
wins. Raft's fix is **randomized election timeouts** (e.g. 150–300 ms, each server picks randomly).
One server almost always times out first, wins, and establishes itself before others start.
**Randomization dissolves the symmetry** — the same "randomness beats a hard case" move as
[skip lists](https://salman9193.github.io/dsa-problems/#design/design-skiplist) and
[quickselect pivots](https://salman9193.github.io/dsa-problems/#guides/DP_TAXONOMY).

---

## Piece 2 — Log Replication

Once elected, the leader serves client requests:

1. Append the command to its own log as a new entry (with the current **term** and an **index**).
2. Send `AppendEntries` RPCs to followers in parallel.
3. When a **majority** have the entry, the leader marks it **committed**, applies it to its state
   machine, and returns to the client. It tells followers the commit index on later RPCs so they
   apply it too.

**The Log Matching Property** — the invariant that keeps logs consistent:

> If two logs contain an entry with the **same index and term**, then (a) they store the same
> command, and (b) **all preceding entries are identical.**

Raft maintains this with a **consistency check** on every `AppendEntries`: the RPC includes the
`(index, term)` of the entry **immediately preceding** the new ones. A follower **rejects** the RPC
if it doesn't have a matching entry there. On rejection, the leader **decrements `nextIndex`** for
that follower and retries — walking backward until it finds the last point where the logs agree, then
overwriting the follower's divergent tail forward. **The leader's log is the truth; conflicting
follower entries are overwritten.**

```
leader:   1 │ 1 │ 2 │ 3 │ 3        ← leader forces followers to match this
follower: 1 │ 1 │ 2 │ 2            ← index 4 term mismatch (3 vs 2)
          → AppendEntries with prev=(idx3,term2) succeeds,
            follower's (idx4,term2) is overwritten with (idx4,term3), then idx5 appended
```

---

## Piece 3 — Safety (the part that's actually subtle)

Election + replication aren't enough on their own; two extra rules prevent committed data from ever
being lost.

### The Election Restriction

**A candidate cannot win unless its log is at least as up-to-date as a majority of the cluster.**
`RequestVote` carries the candidate's last log `(index, term)`; a voter **refuses** if its own log is
more up-to-date ("more up-to-date" = higher last term, or same term and longer). 

**Why:** this guarantees the winner already contains **every committed entry** (the *Leader
Completeness Property*). A committed entry lives on a majority; any election-winner also needs a
majority; those majorities **overlap in at least one server** — so the winner has seen the entry.
Committed data can never be elected away.

### The Commitment Rule — the one everyone gets wrong

Naively: "an entry is committed once it's on a majority." **That is not safe for entries from a
previous term**, and this is the subtlety 6.824's Raft lab is built to catch.

Raft's exact rule: **a leader may only mark an entry committed by counting replicas if the entry is
from its own *current* term.** Entries from earlier terms only become committed **indirectly** — when
a current-term entry above them gets committed (which, by Log Matching, drags the earlier ones with
it).

```
Why counting-a-past-term is unsafe (paper's Figure 7):
  S1 (leader, term 4) has replicated an entry from term 2 to a majority.
  If it commits it just because a majority has it...
  ...S5 could still win term-5 election (its log is longer in term 3) and OVERWRITE that entry.
  → a "committed" entry gets lost. Forbidden.
The fix: S1 must commit a term-4 entry first; that pins everything below it.
```

**If you remember one hard thing about Raft, make it this.** It's the classic interview follow-up and
the classic lab bug.

---

## Failure Scenarios (what interviewers probe)

| Failure | What Raft does |
|---------|----------------|
| **Leader crashes** | followers time out, elect a new leader (with all committed entries, by the restriction) |
| **Follower crashes** | leader retries `AppendEntries` indefinitely; follower catches up on return |
| **Partition, leader on minority side** | it **can't reach a majority → can't commit**; the majority side elects a new leader and makes progress |
| **Old leader returns after partition** | it sees a **higher term**, immediately **steps down** to follower — no split-brain |
| **Split vote** | randomized timeouts break the tie on the next round |

**The partition case is the whole point:** an old leader stranded on the minority side *cannot* do
damage, because it can't get a majority to commit, and the moment it hears a higher term it demotes
itself. **This is exactly the "leases + fencing + quorum" story** from the
[Sharded Database Platform](#hld-sharded-database-platform) failover section — Raft is the algorithm
that makes that safe.

---

## Where You Actually Meet Raft

You almost never implement it — you run systems built on it:

- **etcd** (Kubernetes' brain), **Consul**, **TiKV**, **CockroachDB**, **YugabyteDB**, **RabbitMQ
  quorum queues**, **Redpanda** — all Raft-backed.
- **ZooKeeper** uses **ZAB**, a close cousin (atomic broadcast with the same leader + majority shape).
- The **control plane** of the [Sharded Database Platform](#hld-sharded-database-platform) — topology,
  who's primary, config — is a Raft/consensus store (etcd). The **data plane** deliberately stays off
  it (see the [Consensus](#fu-data-distribution) cost discussion).

---

## Raft vs. Paxos vs. Multi-Paxos

| | Paxos | Multi-Paxos | **Raft** |
|---|-------|-------------|----------|
| Agrees on | a single value | a log | **a log** |
| Leader | none (symmetric) | a distinguished proposer | **strong, explicit** |
| Understandability | notoriously hard | harder | **designed for it** |
| Log flow | any direction | — | **leader → follower only** |
| In practice | rare directly | Google Chubby/Spanner | **the common choice** |

Raft's bet was that **understandability is a feature** — an algorithm engineers can actually implement
correctly beats a marginally more elegant one they get subtly wrong. The [user study in the paper
found 33 of 43 students answered Raft questions better than Paxos]. That bet won: most new systems
pick Raft.

---

## The Staff-Interview Summary

> "Consensus means agreeing on an ordered log despite failures; in practice that's **Raft**. It has a
> **strong leader** elected by majority vote (terms as a logical clock, randomized timeouts to avoid
> split votes), replicates its log to followers, and **commits an entry once a majority store it**.
> Safety comes from two rules: only a node whose log is **at least as up-to-date as a majority** can
> be elected (so winners hold all committed entries), and a leader **only commits current-term entries
> by count** — past-term entries commit indirectly, or a returning leader could overwrite them. A
> partitioned old leader can't reach a majority and steps down on seeing a higher term, so there's **no
> split-brain**. I'd use it via **etcd/Consul for the control plane** and keep the high-throughput data
> path off the consensus round trip."

That paragraph is most of what a design round wants on consensus — and every clause of it is a thing
6.824's Raft lab makes you feel in your bones.

---

## Study Path (MIT 6.824)

If you're using the course as the vehicle:
1. **Lecture 1 + the MapReduce paper + Lab 1** — warm up, get the Go/RPC muscle.
2. **The two Raft lectures + this paper + Lab 2A/2B** (leader election, then log replication) — the
   core. Even stopping before persistence/snapshots (2C/2D), you'll understand consensus properly.
3. **Spanner, ZooKeeper, CRAQ lectures** — cherry-pick; each maps to an HLD here (Spanner ↔ TrueTime
   clock-skew notes; ZooKeeper ↔ this control-plane discussion).

**The trap:** don't binge the lectures. Distributed systems only sticks through the papers and the
Raft lab. One lecture + its paper + notes, spaced, beats five in a row.

---

## Summary

- **Raft = a replicated log via a strong elected leader**; identical ordered logs ⇒ identical state.
- **Election:** terms + majority vote + randomized timeouts (no split votes, one leader/term).
- **Replication:** leader-only log flow; committed once a **majority** store it; Log Matching keeps
  logs identical via the `AppendEntries` consistency check.
- **Safety:** up-to-date-log election restriction (winners hold all committed entries) + **only commit
  current-term entries by count** (the subtle one).
- **No split-brain:** minority-side leader can't commit and steps down on a higher term.
- **Use via** etcd/Consul/TiKV for the control plane; keep bulk data off the consensus path.
