# Critical Path (CPM) — Making the Dependency DAG Actionable

The scheduler already runs **Kahn's topological sort** over a job DAG to decide *what can run*.
With one more pass it can answer the far more useful question:

> **Which jobs actually determine when the whole batch finishes — and which ones can slip for
> free?**

That's the **Critical Path Method** (Kelley & Walker, 1959), and it's the same DP we use
everywhere else: **linearize, then relax.**

---

## Forward Pass — Earliest Start / Finish

With unlimited workers, a job starts once **all** its dependencies finish. So:

```
earliestFinish[v] = duration[v] + max( earliestFinish[u] : u → v )      // u is a dependency
makespan          = max over v of earliestFinish[v]
```

Computed in **topological order**, this is O(V + E).

**The makespan is the longest duration-weighted path** — the *critical path*. No amount of extra
workers can beat it: it's a hard lower bound on wall-clock time.

> DSA twin: this is exactly
> [Parallel Courses III #2050](https://salman9193.github.io/dsa-problems/#graphs/parallel-courses-iii).

---

## Backward Pass — Latest Start / Finish, and Slack

Run the same relaxation in **reverse topological order**, starting from the deadline:

```
latestFinish[u] = min( latestFinish[v] - duration[v] : u → v )          // v is a dependent
slack[u]        = latestFinish[u] - earliestFinish[u]
```

- **`slack[u] == 0`** → `u` is on the **critical path**. Any delay to it delays the entire batch,
  one-for-one.
- **`slack[u] > 0`** → `u` can slip by that much **for free**.

---

## Worked Example

```
jobs:  A(1)   B(2)   C(3) ─→ D(4) ─┐
        └──────┴──────┴────────────┴─→ E(5)
(A, B, C have no deps; D depends on C; E depends on A, B, C, D)
```

| job | duration | earliestFinish | latestFinish | **slack** |
|-----|----------|----------------|--------------|-----------|
| A | 1 | 1 | 7 | **6** |
| B | 2 | 2 | 7 | **5** |
| C | 3 | 3 | 3 | **0** ← critical |
| D | 4 | 7 | 7 | **0** ← critical |
| E | 5 | 12 | 12 | **0** ← critical |

**Makespan = 12. Critical path = C → D → E.** Jobs A and B are irrelevant to the deadline —
optimising them buys **nothing**. That is the entire value of the calculation.

---

## Why the Scheduler Should Care

| Use | How the critical path helps |
|-----|-----------------------------|
| **Priority assignment** | Auto-prioritise zero-slack jobs — they're the ones with no room |
| **SLA / ETA prediction** | The makespan is the earliest honest completion estimate |
| **Where to optimise** | Speeding up a job with slack changes **nothing**. Only the critical path moves the deadline |
| **Impact of a failure** | A critical job failing delays everything; a slack job failing may cost nothing |
| **Capacity planning** | The critical path is the floor — more workers can't beat it |

This turns the `SchedulingPolicy` **Strategy** into something genuinely smart: a
`CriticalPathPolicy` that orders the ready queue by *slack ascending* (least slack = most
urgent) rather than by a hand-assigned priority.

---

## Implementation Sketch

```java
// Forward pass — topological order (Kahn), same traversal the scheduler already does
int[] earliest = new int[n];
for (int u : topoOrder)
    for (int v : dependents(u))
        earliest[v] = Math.max(earliest[v], earliest[u] + duration[v]);
int makespan = max(earliest);

// Backward pass — reverse topological order
int[] latest = new int[n];
Arrays.fill(latest, makespan);
for (int u : reversed(topoOrder))
    for (int v : dependents(u))
        latest[u] = Math.min(latest[u], latest[v] - duration[v]);

// Slack
for (int i = 0; i < n; i++) slack[i] = latest[i] - earliest[i];   // 0 ⇒ critical
```

**O(V + E)** for both passes.

---

## The Honest Limits (say these out loud in an interview)

**1. This assumes unlimited workers.** With a **bounded** worker pool, the critical path is only
a *lower bound* — the real makespan can be worse, and the problem becomes
**resource-constrained project scheduling (RCPSP)**, which is **NP-hard**. The scheduler's greedy
list-dispatch is then a heuristic with the classic Graham (1966) bounds — see
[Task Scheduler #621](https://salman9193.github.io/dsa-problems/#arrays/task-scheduler).

**2. PERT's probabilistic version is systematically biased.** Replacing fixed durations with
random ones and summing means/variances along the critical path **understates** the true
duration: the critical path is itself random, and completion is a **max** over converging paths,
so `E[max(X,Y)] ≥ max(E[X], E[Y])` — the **merge bias** (MacCrimmon & Ryavec, 1964). The correct
tool for stochastic durations is **Monte Carlo simulation**, not this DP.

**3. Crashing is not a DP.** "Pay to shorten jobs and hit the deadline cheaply" cannot be solved
by greedily shortening the cheapest critical job — crashing one path just makes another path
critical, and with parallel critical paths you must crash a **cut**. It's a min-cost-flow /
LP problem (Fulkerson, 1961).

**Knowing where the DP *stops* being the right tool is the more valuable half of this.**
