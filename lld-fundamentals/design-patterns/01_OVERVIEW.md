# Design Patterns — Overview

Reusable, named solutions to recurring object-oriented design problems, catalogued by the
"Gang of Four" (Gamma, Helm, Johnson, Vlissides, 1994). In low-level design they're the
vocabulary interviewers expect: naming the pattern signals you've seen the problem shape
before and know its trade-offs.

---

## The Three Families

| Family | Concern | Examples |
|--------|---------|----------|
| **Creational** | *How objects are created* | Singleton, Factory Method, Abstract Factory, Builder, Prototype |
| **Structural** | *How objects are composed* | Adapter, Decorator, Facade, Composite, Proxy, Bridge, Flyweight |
| **Behavioral** | *How objects interact & share responsibility* | Strategy, Observer, State, Command, Template Method, Chain of Responsibility, Iterator, Mediator, Visitor, Memento |

---

## Patterns ↔ SOLID

Patterns are how SOLID principles show up in practice:

- **Open/Closed** — Strategy, Decorator, State let you extend behaviour without editing
  existing classes.
- **Single Responsibility** — State and Command split fat classes into focused ones.
- **Dependency Inversion** — Factory and Strategy program to interfaces, not concretions.

---

## Choosing Wisely (avoid pattern-itis)

A pattern is justified only when it removes *change-driven* complexity:

- Reach for one when a specific axis is likely to vary (algorithms → Strategy; object
  lifecycle → State; creation → Factory).
- Don't add indirection a plain method or class would handle. Two states don't need the
  State pattern; ten do.
- Prefer the smallest set that covers the requirements — the interviewer rewards judgement,
  not pattern count.

---

## How This Section Works

Each pattern below is a concise reference — **intent**, **when to use**, a minimal
**structure**, and a **"Used in this repo"** annotation linking to the LLD where it appears
as a real implementation. Read a pattern here, then click through to see it working in a
full design:

- [Vending Machine](#lld-vending-machine) — State · Strategy · Singleton · Factory · Observer
- [LLM Model Gateway / Router](#lld-llm-model-gateway) — Strategy
- [Rate Limiter](#lld-rate-limiter) — pluggable algorithms

Tabs: **Creational**, **Structural**, **Behavioral**.
