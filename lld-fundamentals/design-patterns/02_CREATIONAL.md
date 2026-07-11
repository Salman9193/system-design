# Creational Patterns

How objects get created — decoupling *what* is built from *how* and *when*.

---

## Singleton

**Intent:** exactly one instance, with a global access point.
**When:** shared, stateful coordinators — a configuration registry, a connection pool, a
single physical device's controller.
**Structure:** private constructor + static accessor. Prefer the **Bill Pugh holder idiom**
(lazy, thread-safe, no locking) or an `enum`.

```java
class Machine {
    private static class Holder { static final Machine INSTANCE = new Machine(); }
    public static Machine getInstance() { return Holder.INSTANCE; }
    private Machine() {}
}
```

**Watch out:** global mutable state hurts testability; don't use it as a dumping ground.
**Used in this repo:** [Vending Machine](#lld-vending-machine) — the single `VendingMachine`.

---

## Factory Method

**Intent:** defer object creation to a dedicated method, so callers don't hard-code
concrete classes.
**When:** the exact type to build depends on context, or you want one place to change how
things are made.

```java
class StateFactory {
    static State idle(Machine m)     { return new IdleState(m); }
    static State dispensing(Machine m){ return new DispensingState(m); }
}
```

**Used in this repo:** [Vending Machine](#lld-vending-machine) — `StateFactory` builds and
wires each state.

---

## Abstract Factory

**Intent:** create **families** of related objects without specifying concretions.
**When:** you must swap a whole set together — e.g. a `WidgetFactory` producing matching
`Button` + `Checkbox` for a platform theme.
**Contrast:** Factory Method makes *one* product; Abstract Factory makes a *family*.

---

## Builder

**Intent:** construct a complex object step by step, separating construction from
representation.
**When:** many optional parameters or invariants to enforce — avoids telescoping
constructors.

```java
Pizza p = new Pizza.Builder().size("L").cheese(true).build();
```

**When to prefer over a constructor:** more than a handful of fields, several optional.

---

## Prototype

**Intent:** create new objects by **cloning** an existing instance.
**When:** creation is expensive and a template exists, or you need copies configured like a
prototype. In Java, back it with a proper deep `copy()` rather than raw `clone()`.
