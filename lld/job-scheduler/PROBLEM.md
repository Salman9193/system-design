# Job / Task Scheduler — Problem

Design an in-process **task scheduler**: submit jobs, and it runs them at the right time, in
the right order, on a pool of worker threads.

This is one of the most-asked LLD problems, and it's the purest LLD↔DSA bridge in the repo —
the ready queue *is* a priority heap, dependencies *are* a topological sort, and the cooldown
rule *is* the [Task Scheduler (#621)](https://salman9193.github.io/dsa-problems/#arrays/task-scheduler)
greedy.

---

## Functional Requirements

1. **Submit a job** with a priority and an optional scheduled start time.
2. **Priority scheduling** — higher-priority jobs run first; ties broken by submission time
   (FIFO within a priority) so nothing starves arbitrarily.
3. **Delayed / scheduled execution** — "run at T" or "run after a delay."
4. **Recurring jobs (cron-style)** — "every N seconds," rescheduled after each completion.
5. **Dependencies** — job B runs only after job A succeeds (a DAG of jobs).
6. **Retries with backoff** — a failed job is retried up to `k` times with exponential backoff.
7. **Cooldown / rate limit per job type** — don't run the same type more often than every `n`
   intervals (this is #621's constraint).
8. **Cancel** a pending job.
9. **Concurrent execution** on a worker pool of size `W`.
10. **Observability** — notify listeners on start / success / failure / retry.

---

## Non-Functional Requirements

- **Thread-safe** — many producers submitting, many workers consuming.
- **No busy-waiting** — workers block until a job is actually due (not spin on the clock).
- **Extensible policy** — the scheduling policy (priority, FIFO, earliest-deadline-first,
  fair-share) must be swappable without touching the engine.
- **No starvation** — low-priority jobs must eventually run (aging).
- **Graceful shutdown** — stop accepting, drain in-flight, terminate.

---

## Public API

```java
String submit(Job job);                       // returns a job id
boolean cancel(String jobId);
void addDependency(String jobId, String dependsOnId);   // B after A
void addListener(JobListener l);
void start();  void shutdown();
```

---

## The Core Design Question

> **What do the workers pull from?**

A naive `while (true) { poll(); if (nothing due) sleep(1ms); }` busy-waits and scales badly.
The right answer is a **blocking priority queue keyed by "next run time"** — workers block
until the earliest job is due, and are woken immediately when an earlier job arrives. That is
exactly what Java's `DelayQueue` / `PriorityBlockingQueue` provide, and understanding *why*
is half the point of this exercise.

---

## Assumptions & Constraints

- Single JVM, in-memory (a distributed scheduler — leader election, persistence, exactly-once
  — is a separate HLD problem, noted as an extension).
- Jobs are `Runnable`-like and idempotent enough to retry.
- Wall-clock time; monotonic clock for delays.

## Out of Scope (initially)

- Persistence/durability across restarts, distributed coordination, exactly-once semantics,
  job result storage. All are listed as extension points in the Notes tab.
