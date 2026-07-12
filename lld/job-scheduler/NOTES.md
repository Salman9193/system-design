# Job Scheduler — Notes & Trade-offs

> **Design-patterns reference:** [LLD Fundamentals → Design Patterns](#lf-design-patterns).

## The DSA Bridge (why this LLD doubles as algorithm revision)

Three DSA problems are *literally implemented* inside this scheduler:

| Scheduler component | The algorithm | The DSA problem |
|---------------------|---------------|-----------------|
| **Ready queue** | Binary heap — `O(log n)` insert/extract-min | [Find Median from Data Stream](https://salman9193.github.io/dsa-problems/#design/find-median-data-stream), [Merge K Sorted Lists](https://salman9193.github.io/dsa-problems/#linked-list/merge-k-sorted-lists) |
| **Dependencies** | Kahn's topological sort (in-degree counters, unblock on completion) | [Course Schedule](https://salman9193.github.io/dsa-problems/#graphs/course-schedule) / [II](https://salman9193.github.io/dsa-problems/#graphs/course-schedule-ii) |
| **Per-type cooldown** | Greedy: run the most-backlogged eligible task, idle otherwise | [Task Scheduler #621](https://salman9193.github.io/dsa-problems/#arrays/task-scheduler) |

The neatest realisation: **the dependency engine is Kahn's algorithm executed incrementally
over time.** A classic topological sort computes the whole order up front; here, each job's
*completion* decrements its dependents' in-degrees, and a job becomes runnable exactly when its
in-degree hits zero. Same algorithm, unrolled across wall-clock time.

---

## Why a `PriorityBlockingQueue`, not a `PriorityQueue` + `sleep`

The naive worker loop:

```java
while (true) {
    Job j = queue.poll();
    if (j == null || !j.isDue()) { Thread.sleep(1); continue; }   // ← busy-wait
    run(j);
}
```

This spins, burns CPU, and reacts late. The right primitive lets **the queue do the waiting**:

- `PriorityBlockingQueue.take()` blocks until *something* is available.
- `DelayQueue.take()` blocks until the **head's delay expires** — precisely "wake me when the
  earliest job is due, or sooner if an earlier job arrives."

**Trade-off worth stating:** `DelayQueue` is the cleaner fit for pure time-based scheduling,
but it orders *only* by delay. Since we need `(time, priority, FIFO)`, we use a
`PriorityBlockingQueue` with a comparator and a short bounded wait when the head isn't due
yet. In production you'd combine a `ReentrantLock` + `Condition.awaitUntil(nextRunAt)` for an
exact, wake-on-earlier-arrival implementation.

---

## Starvation, and the Fairness Escape Hatch

Strict priority **starves** low-priority jobs. Two mitigations, and both are why
`SchedulingPolicy` is a **Strategy**:

- **Aging** — raise effective priority with waiting time.
- **Fair-share / weighted round-robin** — guarantee each class a slice.

The FIFO `seq` tiebreak already prevents reordering *within* a priority level.

---

## Failure Semantics (decide these explicitly)

| Situation | Choice made here | Alternative |
|-----------|------------------|-------------|
| Job fails, retries left | Re-enqueue at `now + backoff` | Dead-letter immediately |
| Job fails permanently | Dependents stay **blocked forever** | Cascade-fail dependents (often better — say so) |
| Dependency cycle | Reject at submission (cycle check) | Detect at runtime → deadlock |
| Cancel a `RUNNING` job | No-op (cooperative) | Interrupt the thread (needs interruptible bodies) |
| Recurring job fails | Retry, then resume its schedule | Skip this occurrence |

"Dependents of a permanently-failed job stay blocked" is the **subtle bug** in most naive
designs — it's a silent leak. Cascade-failing them (and notifying) is usually the right call.

---

## Concurrency Checklist (the Java fundamentals this forces)

- `ExecutorService` vs. raw threads; why a fixed pool bounds resource use.
- `BlockingQueue` / `DelayQueue` / `PriorityBlockingQueue`; `Delayed`, `Comparable`.
- `ReentrantLock` + `Condition` (`awaitUntil`) — the exact-wakeup primitive.
- `ConcurrentHashMap`, `AtomicLong`, `CopyOnWriteArrayList` (listeners: many reads, rare writes).
- **Run the job body outside every lock** — the single most common deadlock/latency bug here.
- Interruption & graceful shutdown (`awaitTermination`, then interrupt).

---

## Testing Checklist

- Priority ordering; FIFO within a priority.
- Delayed jobs fire at the right time (± tolerance); no busy-wait (measure CPU).
- Dependencies: linear chain, diamond DAG, and a **cycle** (must be rejected).
- Retries: exponential backoff timing, exhaustion → FAILED.
- Cooldown: two jobs of one type spaced ≥ `n`; verify makespan matches the **#621 formula**.
- Recurring: fires repeatedly at the interval; cancel stops it.
- Concurrency: many submitters + many workers, no lost/duplicated jobs.
- Shutdown: in-flight jobs complete; queue drains.

---

## Extension Points

- **Distributed scheduling** — this is an in-process design. Going multi-node needs leader
  election (consensus), persistent job state, and exactly-once/at-least-once semantics. That's
  an **HLD** problem, not an LLD one — a good thing to say out loud.
- **Persistence** — durable queue (DB/WAL) so jobs survive restarts.
- **Cron expressions** — a parser feeding `nextFireTime()` (Template Method already has the
  seam).
- **Backpressure** — bounded queue + rejection policy when overloaded.
- **Priority aging / fair-share** — new `SchedulingPolicy` implementations, no engine changes.
