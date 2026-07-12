# Job Scheduler — Design

The scheduler is a **producer–consumer engine** around a time-ordered priority queue, with
policy, lifecycle, and notification kept as separate, swappable concerns.

---

## Architecture

```
   submit(job)                                        ┌──────────────┐
       │                                              │  Listeners   │  ← Observer
       ▼                                              │ (metrics,log)│
 ┌──────────────┐   dependencies satisfied?   ┌───────┴──────┐       │
 │ Pending /    │─────── topological ────────▶│ READY QUEUE  │       │
 │ Blocked map  │        (Kahn's algo)        │  min-heap by │       │
 └──────────────┘                             │  (nextRunAt, │       │
       ▲                                      │   -priority) │       │
       │ on success: unblock dependents       └───────┬──────┘       │
       │                                              │ take() blocks until due
       │                                     ┌────────┴────────┐     │
       │                                     │  WORKER POOL    │─────┘
       │                                     │  (W threads)    │
       └──── retry w/ backoff ───────────────┤  run → notify   │
             (re-enqueue at now + backoff)   └─────────────────┘
                                                      │ recurring? re-enqueue at next fire
                                                      ▼
                                                    DONE
```

---

## The Ready Queue — the heart of it

A **priority queue ordered by `(nextRunAt, −priority, submitSeq)`**:

1. **`nextRunAt` first** — a job isn't eligible before its time. Workers **block** until the
   head is due (no busy-wait).
2. **`−priority`** — among due jobs, highest priority wins.
3. **`submitSeq`** — FIFO tiebreak, so equal-priority jobs don't reorder arbitrarily.

**DSA:** this is a **binary heap** (Williams, 1964) — `O(log n)` insert and extract-min. It is
literally the same structure as the max-heap in
[Task Scheduler #621](https://salman9193.github.io/dsa-problems/#arrays/task-scheduler) and in
[Find Median from Data Stream](https://salman9193.github.io/dsa-problems/#design/find-median-data-stream).

**Java:** `DelayQueue` (blocks until the head's delay expires) or `PriorityBlockingQueue`
(+ a condition variable). The key insight: *the queue itself does the waiting*, so a worker's
loop is just `job = queue.take(); run(job);`.

---

## Dependencies — a DAG and a topological sort

Job B "depends on" A means an edge `A → B`. The scheduler keeps:

- `dependents[A]` — jobs to unblock when A succeeds.
- `remainingDeps[B]` — an **in-degree counter**.

A job enters the ready queue only when `remainingDeps == 0`. On A's success, decrement each
dependent's counter and enqueue any that hit zero.

**That is exactly Kahn's algorithm** — the scheduler is *running* a topological sort
incrementally, at execution time. A **cycle** means jobs that can never run: detect it at
submission (a cycle check) and reject.

**DSA:** [Course Schedule](https://salman9193.github.io/dsa-problems/#graphs/course-schedule) /
[Course Schedule II](https://salman9193.github.io/dsa-problems/#graphs/course-schedule-ii) —
the same in-degree peeling.

---

## Cooldown / Rate Limit — Task Scheduler #621

Each job **type** may declare a cooldown `n`: no two jobs of that type within `n` intervals.
The scheduler tracks `lastRunAt[type]`; if a job is otherwise due but its type is cooling
down, its `nextRunAt` is pushed to `lastRunAt[type] + cooldown` and it's re-queued.

The greedy consequence — always run the most-backlogged eligible task, idle when nothing is
eligible — is precisely
[Task Scheduler #621](https://salman9193.github.io/dsa-problems/#arrays/task-scheduler), and
its formula predicts the makespan of a batch.

---

## Design Patterns

| Pattern | Applied to | Why |
|---------|-----------|-----|
| **Strategy** | `SchedulingPolicy` (priority / FIFO / EDF / fair-share) | The ordering rule is the thing most likely to change |
| **Strategy** | `RetryPolicy` (fixed / exponential backoff / none) | Retry behaviour is orthogonal and pluggable |
| **State** | Job lifecycle: `PENDING → BLOCKED → READY → RUNNING → SUCCEEDED / FAILED / CANCELLED` | Behaviour and legal transitions differ per state |
| **Observer** | `JobListener` (start/success/failure/retry) | Decouple metrics, logging, alerting from the engine |
| **Builder** | `Job.Builder` | Many optional attributes (priority, delay, cron, deps, retries, cooldown) — avoids a telescoping constructor |
| **Factory** | `JobFactory` from a spec/config | One place to construct jobs with invariants |
| **Template Method** | `RecurringJob.nextFireTime()` | The reschedule skeleton is fixed; the interval rule varies (fixed-rate vs fixed-delay vs cron) |
| **Singleton** | The `Scheduler` engine | One engine per process, owning the worker pool |
| **Command** | A `Job` *is* an encapsulated request | Queue it, retry it, cancel it, log it |

---

## Job Lifecycle (State pattern)

```
   submit
     │
     ▼
  PENDING ──deps unmet──▶ BLOCKED ──last dep succeeds──▶ READY
     │                                                     │
     └────────── no deps ─────────────────────────────────▶│
                                                           │ worker takes it (when due)
                                                           ▼
                                                        RUNNING
                                                    ┌──────┴──────┐
                                              success│             │failure
                                                     ▼             ▼
                                               SUCCEEDED     retries left?
                                                     │        ├─ yes → READY (now + backoff)
                                          recurring? │        └─ no  → FAILED
                                                     └─▶ READY (next fire time)
```

`cancel()` is legal in `PENDING/BLOCKED/READY` and moves to `CANCELLED`; it's a no-op (or a
best-effort interrupt) once `RUNNING`.

---

## Concurrency Design (the part that separates good from great)

- **One shared blocking queue, W workers.** Workers `take()` — the queue blocks them until a
  job is genuinely due. **No polling loop, no `sleep(1)` spin.**
- **Why not a plain `PriorityQueue` + `synchronized`?** You'd have to poll to discover when
  the head became due, and you'd hold the lock while running jobs. A `DelayQueue` /
  `PriorityBlockingQueue` + condition gives you "wake me when the earliest job is due, or when
  something earlier arrives" for free — that's the correct primitive.
- **Guard the shared state** (dependency maps, `lastRunAt`) with a lock or concurrent maps;
  keep the *job body* execution **outside** any lock.
- **Graceful shutdown:** stop accepting, let workers drain, `awaitTermination`, then interrupt.

**Java fundamentals this forces:** `ExecutorService` vs. raw threads, `BlockingQueue` /
`DelayQueue` / `PriorityBlockingQueue`, `Delayed` and `Comparable`, `ReentrantLock` +
`Condition`, `AtomicInteger`, `ConcurrentHashMap`, interruption, and why `wait/notify` is
easy to get wrong.

---

## Starvation & Fairness

Strict priority starves low-priority jobs forever. Mitigations (make this an explicit design
statement):

- **Aging** — bump a job's effective priority the longer it waits.
- **Fair-share / weighted round-robin** across job classes — a different `SchedulingPolicy`,
  which is exactly why that's a Strategy.
