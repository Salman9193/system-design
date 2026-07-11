# Behavioral Patterns

How objects distribute responsibility and interact — the family LLD interviews lean on most.

---

## Strategy

**Intent:** define a family of interchangeable algorithms behind one interface; pick at
runtime.
**When:** the same task has multiple implementations (sorting orders, pricing rules, change
algorithms, routing policies).

```java
interface ChangeStrategy { Map<Integer,Integer> makeChange(int amt, Map<Integer,Integer> inv); }
```

**Used in this repo:** [Vending Machine](#lld-vending-machine) — `ChangeStrategy`
(bounded-DP coin change); [LLM Model Gateway](#lld-llm-model-gateway) — model-routing
strategy; [Rate Limiter](#lld-rate-limiter) — pluggable limiting algorithms.

---

## Observer

**Intent:** one-to-many dependency — when a subject changes, all observers are notified.
**When:** decouple "something happened" from who reacts (events, dashboards, telemetry).

```java
interface Observer { void onEvent(Event e); }   // subject keeps a list, notifies on change
```

**Used in this repo:** [Vending Machine](#lld-vending-machine) — `MachineObserver` for
low-stock / low-coin / change-unavailable alerts.

---

## State

**Intent:** let an object alter its behaviour when its internal state changes — it appears
to change class.
**When:** a finite state machine with distinct per-state behaviour; replaces sprawling
`if (status == …)` chains.

```java
interface State { void insertCoin(int c); void selectProduct(String code); void cancel(); }
```

**Used in this repo:** [Vending Machine](#lld-vending-machine) — `Idle / HasMoney /
Dispensing / SoldOut`.

---

## Command

**Intent:** encapsulate a request as an object — parameterize, queue, log, and undo
operations.
**When:** undo/redo, task queues, transactional actions, remote-control-style dispatch.

---

## Template Method

**Intent:** define an algorithm's skeleton in a base method, deferring specific steps to
subclasses.
**When:** several variants share a fixed overall flow but differ in a few steps.

---

## Chain of Responsibility

**Intent:** pass a request along a chain of handlers until one handles it.
**When:** middleware pipelines, request validation/auth layers, escalation logic.

---

## Others at a Glance

| Pattern | Intent | Typical use |
|---------|--------|-------------|
| **Iterator** | Sequential access without exposing internals | Custom collections |
| **Mediator** | Centralize many-to-many interactions | UI dialogs, chat rooms |
| **Visitor** | Add operations to a type hierarchy without changing it | AST traversal, reporting |
| **Memento** | Capture/restore state without breaking encapsulation | Snapshots, undo |

---

> **Cross-reference — DSA:** the Vending Machine's `Strategy` implementation is the
> **coin change** dynamic program with reconstruction. See the DSA write-up:
> [Coin Change (dsa-problems)](https://salman9193.github.io/dsa-problems/) → Dynamic
> Programming → Coin Change.
