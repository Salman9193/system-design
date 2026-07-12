# Java Compiler — Notes & Working Agreements

> **Design-patterns reference:** for the intent and trade-offs of the patterns used here —
> Visitor, Composite, Interpreter, Builder, Strategy, Chain of Responsibility, Factory —
> see [LLD Fundamentals → Design Patterns](#lf-design-patterns).

## Status

| Milestone | State |
|-----------|-------|
| 0 — Skeleton & contracts | ⬜ next |
| 1 — Lexer | ⬜ |
| 2 — Parser | ⬜ |
| 3 — Semantic analysis | ⬜ |
| 3.5 — Interpreter (oracle) | ⬜ |
| 4 — Hand-emit a `.class` | ⬜ |
| 5 — Codegen | ⬜ |
| 6+ — Methods, objects, IR, optimization | ⬜ |

*(Updated as we go — this is a long-running project.)*

---

## Decisions Locked

| Decision | Choice | Why |
|----------|--------|-----|
| **Language scope** | MiniJava subset, grown in stages | Full Java is multi-year; the subset still exercises every phase |
| **Bytecode emission** | **Hand-rolled** (no ASM) | The point is to learn the class-file format |
| **Class file version** | **49 (Java 5)** initially | Avoids mandatory `StackMapTable` until we tackle it deliberately |
| **Testing oracle** | The tree-walking interpreter | "Compiled output == interpreted output" catches most codegen bugs |
| **Errors** | Collected as data, not thrown | A compiler that dies on error #1 is useless |

---

## The Expression Problem (why Visitor, stated properly)

A compiler has **few node types, many operations**. Visitor makes adding an *operation*
cheap (one new class, zero node edits) and adding a *node type* expensive (every visitor must
handle it). That's precisely the right trade here: the grammar is stable, the passes keep
multiplying. Say this out loud in an interview and you've demonstrated you know *why* the
pattern is chosen, not just that it exists.

---

## Guardrails (lessons that save weeks)

1. **Milestone 4 is the cliff.** Hand-emitting a class file is the hardest single step. Do it
   in isolation with a hard-coded "hello world" — do **not** try to do it while also wiring up
   codegen.
2. **`javap` is the oracle.** Write the target Java, `javac` it, `javap -c` it — that's the
   bytecode you must produce. Never guess.
3. **Constant pool indices are 1-based**, and `long`/`double` entries eat **two** slots.
   Both cause maddening off-by-one bugs.
4. **`max_stack` / `max_locals` must be right** or the verifier rejects the class. Model the
   operand-stack depth as you emit.
5. **Slot 0 is `this`** in instance methods; `long`/`double` locals take two slots.
6. **Keep phases pure.** Each phase takes its input and returns new output — no phase reaches
   back into a previous one. That's what makes them testable.

---

## The DSA ↔ Compiler Map (why this project doubles as DSA revision)

| Phase | The data structure / algorithm |
|-------|-------------------------------|
| Lexer | DFA / state machine; trie for keywords |
| Parser | Recursive descent; Pratt precedence climbing; the AST is a tree |
| Semantic | **Symbol table = a stack of hash maps** (lexical scoping, literally) |
| Interpreter | Tree traversal |
| Codegen | **Stack machine** — post-order traversal *is* instruction order |
| IR | Basic blocks; **control-flow graph** |
| Optimization | **Dataflow analysis** — fixed-point iteration over a graph |
| Register/slot allocation | **Liveness → interference graph → graph coloring** |
| `switch` lowering | Jump table vs. **binary search** (`tableswitch` vs `lookupswitch`) |

See the Roadmap tab for the mapping to specific problems in the
[DSA repo](https://salman9193.github.io/dsa-problems/).

---

## Open Questions (to decide when we reach them)

- **IR design:** three-address code vs. a stack IR? (TAC is easier to optimize; a stack IR is
  closer to the target.)
- **SSA form?** It makes dataflow much cleaner but adds phi-node insertion (dominance
  frontiers). Probably worth it at Milestone 9.
- **Error recovery depth:** how aggressive should panic-mode resync be?
- **Do we ever do a `javac`-compatible frontend?** (Parsing *real* Java is a big step —
  generics alone are a monster.)

---

## Next Step

**Milestone 0 + 1** — the skeleton (phase contracts, `Token`, `Diagnostic`) and the **Lexer**,
with golden-token tests. Everything downstream depends on the token stream and on accurate
line/column tracking for error messages.
