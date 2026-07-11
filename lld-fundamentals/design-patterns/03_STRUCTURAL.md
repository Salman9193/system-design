# Structural Patterns

How objects and classes are composed into larger structures while keeping them flexible.

---

## Adapter

**Intent:** convert one interface into another the client expects.
**When:** integrating a third-party or legacy class whose API doesn't match yours.
**Structure:** wrapper that implements the target interface and delegates to the adaptee.

```java
class LegacyToTarget implements Target {
    private final Legacy legacy;
    public void request() { legacy.oldRequest(); }
}
```

---

## Decorator

**Intent:** add responsibilities to an object dynamically by **wrapping** it — a
composition-based alternative to subclassing.
**When:** many optional, combinable behaviours (buffering, compression, encryption on a
stream).

```java
Reader r = new BufferedReader(new InputStreamReader(in));  // decorators stacked
```

**Contrast:** Decorator changes *behaviour*; Adapter changes *interface*.

---

## Facade

**Intent:** a single simplified entry point over a complex subsystem.
**When:** you want to hide orchestration of many classes behind one clean API (an
`OrderService` fronting inventory + payment + shipping).

---

## Composite

**Intent:** treat individual objects and compositions of objects uniformly through a common
interface — a part/whole **tree**.
**When:** recursive structures: file systems, UI component trees, org charts.

```java
interface Node { int size(); }        // File and Folder both implement it
```

---

## Proxy

**Intent:** a stand-in that controls access to another object.
**When:** lazy loading (virtual proxy), access control (protection proxy), remoting, or
caching in front of an expensive resource.

---

## Bridge

**Intent:** decouple an **abstraction** from its **implementation** so the two vary
independently.
**When:** a class explodes combinatorially along two axes (shape × rendering API) — bridge
turns `m × n` subclasses into `m + n`.

---

## Flyweight

**Intent:** share fine-grained immutable objects to cut memory when you have vast numbers of
them.
**When:** millions of near-identical objects (glyphs, tiles) — separate shared *intrinsic*
state from per-use *extrinsic* state.
