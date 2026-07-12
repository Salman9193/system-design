# Java Compiler — Roadmap

Each milestone is a **working compiler** for a slightly bigger language — never a broken
half-one. Every milestone lists what we build, the **DSA** it exercises, the **LLD** pattern
it introduces, and the **Java/JVM** fundamental it forces you to learn.

---

## Milestone 0 — Skeleton & Contracts *(setup)*

Define the phase interfaces and the diagnostic (error) type. No logic yet — just the
contracts the whole pipeline hangs on.

- **Deliverable:** `Token`, `Diagnostic`, phase interfaces, a `Pipeline` that chains them.
- **LLD:** Chain of Responsibility (the pipeline), errors-as-data.

---

## Milestone 1 — Lexer: chars → tokens

Turn `x = 1 + 2;` into `[IDENT(x), ASSIGN, INT(1), PLUS, INT(2), SEMI]`.

- **Build:** scanner with single-char lookahead; number/identifier/string literals; keyword
  recognition; comments/whitespace skipping; **line & column tracking** (needed for every
  error message thereafter).
- **DSA:** finite state machine / DFA; trie or hash set for keywords; `O(n)` single pass.
- **LLD:** Factory for token creation.
- **Java/JVM:** `char` vs `int`, Unicode/UTF-16 basics, `String` internals.
- **Test:** golden token lists.

---

## Milestone 2 — Parser: tokens → AST

Turn tokens into a tree. Expressions with correct **precedence and associativity** are the
whole game here.

- **Build:** recursive-descent parser; **Pratt / precedence-climbing** for expressions;
  statements (`if`, `while`, blocks, declarations); panic-mode error recovery.
- **DSA:** recursive descent, precedence climbing, grammar → code; the AST is a **Composite**
  tree.
- **LLD:** **Composite** (AST) + **Visitor** (introduced here for the AST pretty-printer —
  the first pass, and the cheapest place to learn the pattern).
- **Java/JVM:** sealed/abstract class hierarchies, `instanceof` patterns, immutability.
- **Test:** pretty-print the AST and diff.

---

## Milestone 3 — Semantic Analysis: AST → typed AST

Catch what the grammar can't: undeclared variables, type errors, wrong argument counts.

- **Build:** **symbol table** (a *stack of hash maps* = lexical scoping); type checker as a
  Visitor; annotate each node with its type; collect diagnostics.
- **DSA:** **scoped symbol table** (stack of hash maps), tree traversal, type unification.
- **LLD:** **Visitor** in earnest (`TypeChecker implements AstVisitor<Type>`).
- **Java/JVM:** static vs dynamic types, primitive widening, `boolean` isn't an `int`
  (but *is* on the JVM stack — a great gotcha).
- **Test:** ill-typed programs → expected errors.

---

## Milestone 3.5 — Tree-Walking Interpreter *(the oracle)*

Before compiling, **run** the AST. This proves the front end and gives us a reference
implementation to test codegen against.

- **Build:** `Interpreter implements AstVisitor<Object>`; an environment (scoped map) for
  values.
- **LLD:** **Interpreter pattern**; a second Visitor — proving the pattern's payoff (new
  pass, zero node changes).
- **Why it matters:** in Milestone 6, "compiled output == interpreter output" becomes the
  test that catches nearly every codegen bug.

---

## Milestone 4 — JVM Bytecode 101: emit a *hello world* `.class` by hand

Before compiling anything, **hand-assemble** the simplest possible class file and get the
JVM to run it. This is the steepest learning cliff — do it in isolation.

- **Build:** class file writer: magic `0xCAFEBABE`, version, **constant pool**, access flags,
  fields, methods, `Code` attribute. Emit a `main` that prints a constant.
- **DSA:** the **constant pool** is a deduplicating hash map + indexed list (Builder pattern).
- **LLD:** **Builder** (class file / constant pool).
- **Java/JVM:** ⭐ the big one — constant pool structure, **type descriptors**
  (`([Ljava/lang/String;)V`), `getstatic`/`ldc`/`invokevirtual`, **operand stack vs local
  variable slots**, `max_stack` / `max_locals`.
- **Test:** `java Hello` runs; `javap -c Hello` matches expectation.

---

## Milestone 5 — Codegen: expressions & statements → bytecode

Now connect the front end to the emitter.

- **Build:** `CodeGenerator implements AstVisitor<Void>`; expression codegen for a **stack
  machine** (post-order = push operands, then the op); local variable **slot allocation**;
  `if`/`while` via labels, forward jumps, and **backpatching**.
- **DSA:** stack-machine evaluation (the AST post-order *is* the instruction order);
  operand-stack depth modeling to compute `max_stack`; backpatching jump offsets.
- **LLD:** Visitor again — a third pass, still zero node changes.
- **Java/JVM:** `iload/istore/iadd`, `if_icmpge`, `goto`, why the JVM is a **stack machine**
  and not a register machine; **stack map frames** (why Java 7+ verification requires them).
- **Test:** ⭐ compiled output == interpreter output, on a corpus of programs.

---

## Milestone 6 — Methods & Calls

- **Build:** method declarations, parameters, `return`; method descriptors; `invokestatic`;
  a per-method frame (own locals & stack).
- **DSA:** call graph; activation records.
- **Java/JVM:** descriptors in depth; `invokestatic` vs `invokevirtual` vs `invokespecial`
  vs `invokedynamic`; how arguments map to local slots (and why `this` is slot 0).

---

## Milestone 7 — Classes, Fields, Objects

- **Build:** class declarations, fields, `new`, constructors, instance methods.
- **DSA:** object layout; the class table.
- **Java/JVM:** `new`/`dup`/`invokespecial <init>` (the three-instruction object-creation
  dance), `getfield`/`putfield`, why constructors are `<init>` and static initializers are
  `<clinit>`.

---

## Milestone 8 — IR & Control-Flow Graph

Introduce a real middle end: lower the AST into **three-address code**, then build the CFG.

- **Build:** TAC instructions; **basic blocks**; the **control-flow graph**.
- **DSA:** ⭐ graphs — basic-block construction, CFG edges, reachability, dominators.
- **LLD:** the IR is the seam that decouples front end from back end.

---

## Milestone 9 — Optimization Passes

- **Build:** constant folding & propagation; dead-code elimination; common-subexpression
  elimination.
- **DSA:** ⭐ **dataflow analysis** (liveness, reaching definitions) — a fixed-point
  iteration over the CFG; worklist algorithms.
- **LLD:** **Strategy** — each optimization is a pluggable, orderable pass.
- **Payoff:** this is where DSA gets *serious* — fixed-point iteration on a graph lattice.

---

## Milestone 10 — Register/Slot Allocation *(the DSA crown jewel)*

- **Build:** minimize local variable slots by reusing them for non-overlapping lifetimes.
- **DSA:** ⭐⭐ **liveness analysis** → **interference graph** → **graph coloring**. The single
  most famous "real algorithm" in compilers, and a direct application of graph theory.

---

## Milestone 11+ — Growth (pick as desired)

Inheritance & virtual dispatch (vtables) · arrays · strings & string concat · exceptions
(exception tables) · `switch` (`tableswitch` vs `lookupswitch` — a genuinely interesting
DSA choice: jump table vs binary search) · generics (erasure) · lambdas
(`invokedynamic`) · a peephole optimizer.

---

## Suggested Order of Attack

Milestones **1 → 2 → 3 → 3.5 → 4 → 5** gets you an end-to-end compiler that runs on the real
JVM. That's the core loop; everything after is depth. **Milestone 4 (hand-emitting a class
file) is the hardest single step** — it's isolated on purpose, so it can't block the front
end.

---

## Cross-References — DSA Problems

As we build, these repo problems map directly onto compiler phases:

| Compiler topic | DSA problem |
|----------------|-------------|
| Keyword lookup, prefix matching | Implement Trie (#208) |
| Expression parsing / evaluation | Basic Calculator, Decode String (stacks) |
| Balanced delimiters in the parser | Valid Parentheses (#20) |
| AST traversal (Visitor) | Binary tree traversals; Morris traversal (pointer rewiring) |
| Scoped symbol table | Hash-map design (LRU Cache-style structure work) |
| CFG & reachability | Graph BFS/DFS, Course Schedule (topological sort) |
| Pass ordering / dependencies | Course Schedule II (#210) — topological sort |
| Dataflow fixed-point | Iterative graph algorithms |
| Register allocation | **Graph coloring** (bipartite/greedy-coloring problems) |
| `switch` lowering | Binary search vs jump table |

Every phase is a data-structure problem wearing a compiler hat — which is exactly the point
of building this.
