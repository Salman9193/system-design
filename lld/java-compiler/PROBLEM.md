# Java Compiler (MiniJava → JVM Bytecode) — Problem

Build a compiler, in Java, that translates a subset of Java ("MiniJava") into **real JVM
bytecode** — a `.class` file the actual `java` command can run.

This is a **long-running, incremental project**. It exists because a compiler is the best
LLD/DSA crossover there is: every phase is a classic algorithm, every phase boundary is a
design-pattern decision, and emitting bytecode forces genuine JVM fluency.

---

## The Goal

```
$ java MiniJavac Hello.java     # our compiler
$ java Hello                    # the real JVM runs OUR bytecode
Hello, world
42
```

Nothing teaches the JVM as concretely as producing a class file it accepts and verifies.

---

## Scope — MiniJava, Grown Over Time

Full Java is a multi-year effort. We start with a subset that still exercises **every**
compiler phase, then grow it. Each stage is a working compiler, not a broken half-one.

| Stage | Language features | New phase work |
|-------|-------------------|----------------|
| **S1** | int/boolean expressions, `println` | lexer, parser, codegen |
| **S2** | variables, assignment, `if`, `while` | symbol table, local slots, jumps |
| **S3** | methods (static), calls, parameters, return | method descriptors, invocation, frames |
| **S4** | classes, fields, `new`, instance methods | object model, `invokevirtual`, heap |
| **S5** | inheritance, arrays, strings | vtables, `checkcast`, array opcodes |
| **S6+** | optimization passes | CFG, dataflow, DCE, constant folding |

**Explicitly out of scope (initially):** generics, lambdas, interfaces, exceptions,
concurrency, the full standard library. Each becomes a later stage if we want it.

---

## Functional Requirements

1. **Compile** a MiniJava source file to a valid `.class` file.
2. **Reject invalid programs** with useful errors (line/column, what was expected) — for
   lexical, syntactic, *and* semantic (type/scope) errors.
3. **Produce verifiable bytecode** — the JVM's verifier must accept it (this is a real
   constraint, not a nicety; see the JVM Primer tab).
4. **Be inspectable** — dump tokens, AST, symbol table, IR, and disassembled bytecode at
   each phase, so the pipeline is observable (and debuggable).

## Non-Functional Requirements

- **Phase-isolated** — each phase has a clean input/output contract, so phases can be
  tested, replaced, or skipped independently.
- **Extensible language** — adding a syntax feature shouldn't require touching every class
  (this is what the Visitor pattern buys us).
- **Hand-rolled emission** — we write the `.class` bytes ourselves (no ASM), because the
  point is to learn the format.

---

## Why This Project (the payoff)

- **DSA:** state machines, tries, recursive descent, precedence climbing, trees, scoped hash
  maps, graphs (CFG), dataflow analysis, graph coloring.
- **LLD:** Visitor, Composite, Interpreter, Builder, Strategy, Chain of Responsibility,
  Factory — each used where it genuinely belongs, not bolted on.
- **Java fundamentals:** the constant pool, operand stack vs. local slots, type descriptors,
  the four `invoke*` opcodes, stack map frames, and how `javac` desugars what you write.

See the **Roadmap** tab for the phase-by-phase plan, and the **JVM Primer** tab for the
bytecode fundamentals we'll need from Stage 1.
