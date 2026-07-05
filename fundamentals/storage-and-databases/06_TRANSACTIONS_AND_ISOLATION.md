# Transactions & Isolation

A transaction groups operations so they succeed or fail together. The hard part isn't
atomicity — it's **isolation**: what happens when transactions run concurrently. This
tab goes deep on isolation levels, the anomalies they prevent, and how databases enforce
them. It's a favorite interview probe because most engineers know "ACID" but not the
levels underneath the "I."

---

## Transactions (recap)

A **transaction** is a unit of work that's **atomic** (all-or-nothing) and **durable**
(survives crashes once committed) — the A and D of ACID from the Relational tab. The
classic example: transferring money debits one account and credits another; either both
happen or neither does. The interesting behavior appears when many transactions run at
once — that's isolation.

---

## Why Isolation Matters

If transactions ran one at a time (serially), there'd be no problem — but that's far too
slow. Databases run them **concurrently**, which means they can interfere. **Isolation
levels** let you choose how much interference to prevent, trading **correctness for
concurrency/performance**: stronger isolation is more correct but allows less
parallelism.

---

## The Anomalies (what can go wrong)

Concurrency bugs that isolation levels are defined to prevent:

- **Dirty read** — reading data another transaction wrote but hasn't committed (it might
  roll back, so you read something that never "happened").
- **Non-repeatable read** — reading the same row twice in one transaction and getting
  different values, because another transaction committed a change in between.
- **Phantom read** — re-running a query and getting different *rows*, because another
  transaction inserted/deleted rows matching your condition.
- **Lost update** — two transactions read-modify-write the same row and one overwrites
  the other's change.

---

## Isolation Levels

The SQL standard defines four levels, each preventing more anomalies:

| Level | Dirty read | Non-repeatable read | Phantom read |
|-------|------------|---------------------|--------------|
| **Read uncommitted** | Possible | Possible | Possible |
| **Read committed** | Prevented | Possible | Possible |
| **Repeatable read** | Prevented | Prevented | Possible |
| **Serializable** | Prevented | Prevented | Prevented |

- **Read committed** (a common default) — you only read committed data, but the same
  read can change within your transaction.
- **Repeatable read** — rows you've read won't change, but new rows can still appear
  (phantoms).
- **Serializable** — the strongest: transactions behave as if run one at a time. Fully
  correct, but the most contention/least concurrency.

The staff-level point: **pick the lowest level that's still correct for your workload.**
Serializable everywhere is safe but slow; read committed is often enough; know which
anomalies your logic can actually tolerate.

---

## How Databases Enforce Isolation

Two broad strategies for concurrency control:

- **Pessimistic (locking)** — a transaction locks rows/tables it touches so others must
  wait. Correct, but locks cause **contention and deadlocks**.
- **Optimistic (MVCC — Multi-Version Concurrency Control)** — the database keeps
  multiple versions of a row, so **readers see a consistent snapshot without blocking
  writers, and writers don't block readers**. Conflicts are detected at commit. MVCC is
  why modern databases (Postgres, others) achieve high concurrency — reads and writes
  don't fight. **Snapshot isolation** is the common MVCC-based level.

Naming MVCC — "readers don't block writers" — is a strong depth signal.

---

## Distributed Transactions (the hard extension)

Within one database, ACID transactions are well-understood. **Across services or
shards**, they're much harder — and the staff move is usually to **avoid needing them**:

- **Two-phase commit (2PC)** — a coordinator asks all participants to prepare, then
  commit. Gives atomicity across nodes, but is **blocking and slow**, and the
  coordinator is a failure point.
- **Sagas** — instead of one distributed transaction, a sequence of local transactions
  with **compensating actions** to undo on failure. Eventually consistent, resilient,
  and the common pattern in microservices (ties to Async & messaging).
- **Design to avoid** — keep data that must change together in one place (single-shard
  transactions), so you rarely need cross-node atomicity.

> The staff move: "I pick the lowest isolation level that's correct — often read
> committed, serializable only where I truly need it — and I lean on MVCC so reads don't
> block writes. Across services I avoid distributed transactions: I keep transactional
> data co-located so it's a single-node transaction, and where I can't, I use a saga
> with compensating actions rather than two-phase commit, which is blocking and fragile."

---

## The Summary

- **Transactions** = atomic + durable; **isolation** governs concurrent behavior.
- **Anomalies:** dirty read, non-repeatable read, phantom, lost update.
- **Levels** (read uncommitted → serializable) trade correctness for concurrency — pick
  the lowest that's correct.
- **MVCC** gives high concurrency (readers don't block writers); locking is the
  pessimistic alternative.
- **Distributed transactions are costly** — prefer single-shard transactions or sagas
  over two-phase commit.
