# Consistency Models

Once data is replicated, reading one copy raises a question: how fresh and how ordered
is what you see? A **consistency model** is the contract the system gives you about
that. This is where distributed systems get genuinely subtle, and where the strongest
candidates show they know it's a *spectrum* — not just "strong vs eventual" — and that
you pick per data type.

---

## First, Disambiguate "Consistency"

The word is overloaded — name which one you mean:

- **ACID consistency** (Storage module) — a transaction respects constraints. About
  validity, not replicas.
- **Isolation levels** (Storage module) — how *concurrent transactions* on one database
  interfere.
- **Distributed consistency** (this tab) — how *replicas across machines* agree on what
  the data is. Also what CAP's "C" means (linearizability).

This tab is the third one.

---

## The Spectrum: Strong → Eventual

### Strong consistency (linearizability)
Every read reflects the **most recent write** — the system behaves as if there's a
single copy, and once a write completes, everyone sees it. Easiest to reason about, but
it requires coordination across replicas, which **costs latency and availability**
(you may have to wait, or refuse to answer during a partition — CAP).

### Eventual consistency
If writes stop, replicas **eventually converge** — but a read might see **stale** data
in the meantime. Cheapest and most available/low-latency. Perfectly fine for a like
count, a view counter, or a social feed; dangerous for a bank balance or a uniqueness
check.

### The useful middle grounds
"Eventual" is often too weak and "strong" too expensive. Named intermediate guarantees:

- **Read-your-writes** — you always see your *own* writes immediately (others may see
  them later). Fixes the "I posted a comment and it vanished" bug.
- **Monotonic reads** — you never see time go backwards; once you've seen a value, you
  won't see an older one.
- **Causal consistency** — operations that are causally related (a reply after a
  message) are seen in that order by everyone; truly concurrent operations may be seen
  in different orders. A strong, practical middle ground for messaging, comments, and
  collaboration.

---

## Tunable Consistency (Quorums)

Leaderless/quorum systems let you *dial* consistency with three numbers:

- **N** — replicas per item, **W** — replicas that must ack a write, **R** — replicas
  read.
- The rule: **R + W > N** guarantees a read set overlaps the latest write set, so you
  read the newest value — effectively strong-ish consistency.
- Tune the balance:
  - `W = N, R = 1` — durable writes, fast reads, but writes are slow / less available.
  - `R = N, W = 1` — fast writes, slow reads.
  - `R + W > N` (e.g. N=3, W=2, R=2) — balanced, quorum consistency.
  - `R + W ≤ N` — fast but may read stale data (eventual).

This is how systems like Dynamo/Cassandra offer per-query consistency.

---

## Eventual Consistency in Practice: Conflict Resolution

When replicas diverge (multi-leader, or leaderless concurrent writes), something must
reconcile them:

- **Last-write-wins (LWW)** — keep the write with the latest timestamp. Simple, but
  **silently loses** the other write, and clock skew makes "latest" fuzzy.
- **Version vectors / vector clocks** — detect concurrent writes so the app (or user)
  can merge.
- **CRDTs** (conflict-free replicated data types) — data structures that merge
  automatically and correctly (counters, sets), popular for collaborative/multi-region
  data.

---

## Choosing a Model (per data type)

There is no single right answer — **pick per data type**:

| Data | Model |
|------|-------|
| Money, inventory, uniqueness, bookings | **Strong** |
| Like counts, view counts, feeds, recommendations | **Eventual** |
| Your own profile edits, "did my write land?" | **Read-your-writes** |
| Messaging, comment threads, collaboration | **Causal** |

> The staff move: "I don't pick one consistency level for the whole system — I pick per
> data type. Strong (and the coordination cost) for money and uniqueness; eventual for
> counts and feeds where staleness is invisible; read-your-writes so users always see
> their own actions; causal for messaging where order matters. In a quorum store I tune
> R and W to hit that per-workload, keeping R + W > N where I need to read the latest."

---

## The Summary

- **Consistency model = the freshness/ordering contract** across replicas (distinct from
  ACID-C and isolation).
- **Spectrum:** strong (linearizable, costly) → causal → monotonic/read-your-writes →
  eventual (cheap, stale reads possible).
- **Quorums (R + W > N)** let you tune consistency per query.
- **Eventual needs conflict resolution** — LWW (lossy), version vectors, or CRDTs.
- **Choose per data type** — strong where correctness demands it, eventual where
  staleness is harmless.
