# LLD Framework — Object & API Design Approach

Low-level design (LLD) tests whether you can turn a set of requirements into a
clean, extensible object model: the right classes, interfaces, relationships, and
design patterns. Where HLD is about boxes and data flow at scale, LLD is about the
code-level structure inside one service.

At Google this surfaces in coding rounds ("design a parking lot / rate limiter /
notification system as classes"), in the RRK (role-related knowledge) round for
backend staff roles, and as the "set the black box aside, build it yourself" turn
inside an HLD round.

---

## The Approach (in order)

### 1. Clarify requirements & scope (like HLD, smaller)
Pin down the core operations and the invariants. What must always be true? What's
explicitly out of scope? A rate limiter: "allow or reject a request for a given
key; support multiple algorithms; be thread-safe. Out of scope: distributed
coordination for now — I'll flag where it plugs in."

### 2. Identify the entities (nouns → classes)
Extract the nouns from the requirements. Each becomes a candidate class. A parking
lot: `ParkingLot`, `Level`, `Spot`, `Vehicle`, `Ticket`. Don't over-model — only
classes that carry behavior or state earn their place.

### 3. Identify the behaviors (verbs → methods)
Extract the verbs. `park(vehicle)`, `leave(ticket)`, `allow(key)`. Assign each
behavior to the class that owns the relevant data (the Information Expert
principle).

### 4. Define relationships
- **Association** — "uses a" (a `Service` uses a `RateLimiter`)
- **Aggregation** — "has a", independent lifecycle (a `Lot` has `Level`s)
- **Composition** — "owns a", shared lifecycle (a `Level` owns its `Spot`s)
- **Inheritance / interface** — "is a" (a `TokenBucket` is a `RateLimitStrategy`)

Prefer **composition over inheritance**. Inheritance is the most-abused
relationship in LLD interviews.

### 5. Apply design patterns where they fit
Don't force patterns — but recognize where they naturally apply (see below). Naming
the pattern and *why* it fits is a strong signal.

### 6. Write the interfaces first, then implementations
Start with the abstractions. `interface RateLimitStrategy { boolean allow(String key); }`
Then implement each variant. This makes the extensibility obvious and shows you
design to interfaces, not concretions.

### 7. Address concurrency & edge cases
Thread safety, null handling, capacity limits, what happens on invalid input. At
staff level, concurrency is usually where the depth is.

---

## The Design Patterns That Show Up Most

| Pattern | When it fits | Example |
|---------|-------------|---------|
| **Strategy** | Multiple interchangeable algorithms | Rate limiter: token bucket vs sliding window |
| **Factory** | Object creation varies by type/config | Create the right strategy from config |
| **Singleton** | Exactly one instance (use sparingly) | A shared config or connection pool |
| **Observer** | One-to-many event notification | Notification fan-out, event listeners |
| **Decorator** | Add behavior without subclassing | Add logging/metrics around a component |
| **State** | Behavior depends on internal state | Vending machine, order lifecycle |
| **Command** | Encapsulate a request as an object | Undo/redo, job queues |
| **Builder** | Construct complex objects step-by-step | Building a config with many options |
| **Adapter** | Bridge incompatible interfaces | Wrapping a third-party client |

**The staff move:** recognize when a pattern *doesn't* fit and say so. Forcing a
Singleton where a plain object works, or a Strategy where there's only ever one
algorithm, is over-engineering — and over-engineering is a negative signal.

---

## SOLID — The Principles Behind the Patterns

| Principle | Meaning | LLD application |
|-----------|---------|-----------------|
| **S** — Single Responsibility | One reason to change | Each class does one thing |
| **O** — Open/Closed | Open to extend, closed to modify | Add a new strategy without touching existing ones |
| **L** — Liskov Substitution | Subtypes honor the base contract | Any `RateLimitStrategy` is swappable |
| **I** — Interface Segregation | Small, focused interfaces | Don't force clients to depend on unused methods |
| **D** — Dependency Inversion | Depend on abstractions | `Service` depends on `RateLimitStrategy`, not `TokenBucket` |

The **Open/Closed** principle is the one interviewers probe hardest: "how would you
add a new rate-limiting algorithm?" A good design answers "I add a new class
implementing the interface — I don't touch any existing code."

---

## What "Good" Looks Like

A strong LLD answer has these properties:

- **Extensible** — adding a new variant means adding a class, not editing a switch
  statement. (Open/Closed in action.)
- **Testable** — dependencies are injected, so each unit can be tested in isolation.
- **Thread-safe** where required — and you can explain the concurrency strategy
  (locks, atomics, immutability).
- **No god class** — behavior is distributed to the classes that own the data.
- **Right-sized** — no pattern for the sake of a pattern; no premature abstraction.

---

## Common Failure Modes

| Failure mode | Why it's weak | The fix |
|--------------|---------------|---------|
| One giant `Manager` class doing everything | Violates Single Responsibility | Distribute behavior to entity classes |
| Deep inheritance hierarchies | Fragile, hard to extend | Prefer composition + interfaces |
| Forcing patterns everywhere | Over-engineering | Use a pattern only where it earns its place |
| Ignoring thread safety | Misses the hard part | State the concurrency model explicitly |
| Concrete dependencies everywhere | Untestable, rigid | Depend on interfaces; inject dependencies |
| No interfaces, only classes | Can't extend cleanly | Design to abstractions first |

---

## The One-Line Summary

Nouns → classes, verbs → methods, design to interfaces, prefer composition, apply a
pattern only where it fits, and always address concurrency. Then show extensibility
by adding a new variant without touching existing code.
