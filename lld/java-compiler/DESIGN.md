# Java Compiler — Design & Architecture

The compiler is a **pipeline of phases**, each with a strict input → output contract. That
single decision is what makes the project tractable, testable, and extensible.

---

## The Pipeline

```
  source .java
      │  chars
      ▼
 ┌─────────────┐
 │ 1. LEXER    │  chars   → tokens          DSA: DFA / state machine, keyword trie
 └─────────────┘
      │  Token[]
      ▼
 ┌─────────────┐
 │ 2. PARSER   │  tokens  → AST             DSA: recursive descent + Pratt precedence
 └─────────────┘
      │  AST (Composite)
      ▼
 ┌─────────────┐
 │ 3. SEMANTIC │  AST     → annotated AST   DSA: scoped symbol table, type checking
 └─────────────┘
      │  typed AST
      ▼
 ┌─────────────┐
 │ 4. IR       │  AST     → three-address   DSA: CFG (a graph!), basic blocks
 └─────────────┘
      │  IR + CFG
      ▼
 ┌─────────────┐
 │ 5. OPTIMIZE │  IR      → better IR       DSA: dataflow analysis, dominators, DCE
 └─────────────┘
      │  optimized IR
      ▼
 ┌─────────────┐
 │ 6. CODEGEN  │  IR      → JVM instrs      DSA: stack machine, operand-stack modeling
 └─────────────┘
      │  instructions + constant pool
      ▼
 ┌─────────────┐
 │ 7. EMIT     │  → .class bytes            Binary format, constant pool, stack maps
 └─────────────┘
      │
      ▼  `java Hello`  ← the real JVM runs it
```

**Front end** (1–3) understands the *language*; **middle end** (4–5) is language- and
target-agnostic; **back end** (6–7) knows the *target*. Keeping that boundary honest is why
you could later retarget to a different backend, or front-end a different language.

---

## Design Patterns — and Why Each Earns Its Place

| Pattern | Where | The problem it solves |
|---------|-------|----------------------|
| **Composite** | The AST | Expressions/statements form a part-whole tree; uniform treatment of leaf and composite nodes |
| **Visitor** | Every AST pass (type-check, interpret, codegen) | *The* canonical fix for "many operations over a fixed node hierarchy" — add a new pass without touching a single node class |
| **Interpreter** | Tree-walking evaluator (Stage 2.5) | Run the AST directly, before codegen exists — proves the front end works |
| **Builder** | Constant pool & class file | Assemble a complex binary artifact incrementally, deduplicating entries |
| **Strategy** | Optimization passes, register/slot allocation | Passes are pluggable and reorderable |
| **Chain of Responsibility** | The phase pipeline itself | Each phase consumes the previous phase's output |
| **Factory** | Token & AST node creation | One place to construct, centralizing invariants |

### Why Visitor is the keystone

The AST node types are **fixed and few** (Binary, Literal, If, While, Call…), but the
**operations over them keep multiplying** (type-check, constant-fold, interpret, emit,
pretty-print, dump). If each operation were a method on the nodes, every new pass would edit
every node class:

```java
// WITHOUT Visitor — every new pass touches every node
class BinaryExpr { Type typeCheck(); void codegen(); Object eval(); String print(); ... }
```

With Visitor, each pass is **one new class** and the nodes never change:

```java
interface AstVisitor<R> {
    R visitBinary(BinaryExpr e);
    R visitLiteral(LiteralExpr e);
    R visitIf(IfStmt s);
    ...
}
class TypeChecker implements AstVisitor<Type> { ... }   // a pass
class CodeGenerator implements AstVisitor<Void> { ... } // another pass
```

**The trade-off (state it in interviews):** Visitor makes *adding operations* cheap but
*adding node types* expensive (every visitor must handle the new node). That's exactly the
right trade for a compiler — the grammar is stable, the passes are not. It's the "expression
problem," and Visitor picks the side that fits.

---

## Key Data Structures

| Structure | Phase | Why |
|-----------|-------|-----|
| **DFA / state machine** | Lexer | Recognize tokens in one pass, O(n) |
| **Trie (or hash set)** | Lexer | Keyword vs. identifier lookup |
| **AST (n-ary tree)** | Parser | The program's structure |
| **Symbol table: stack of hash maps** | Semantic | Lexical scoping — push a scope on entry, pop on exit; lookup walks outward |
| **Three-address code** | IR | Flat, easy to optimize |
| **Control-flow graph** | IR/Opt | Basic blocks as nodes, jumps as edges — enables dataflow |
| **Constant pool (hash map + list)** | Emit | Deduplicated table of strings/refs the class file requires |

The symbol table is the neatest DSA moment: **lexical scoping is literally a stack of hash
maps**, and name resolution is "walk up the stack until you find it."

---

## Error Handling Strategy

A compiler that dies on the first error is useless. Design for:

- **Errors as data** — collect a list of diagnostics (phase, line, column, message), don't
  throw on the first one.
- **Panic-mode recovery** in the parser — on a syntax error, skip tokens until a
  synchronizing token (`;`, `}`), then keep parsing to find *more* errors.
- **Phases gate** — don't run codegen if semantic analysis produced errors.

---

## Testing Strategy (per phase, since phases are isolated)

- **Lexer:** source → expected token list (golden tests).
- **Parser:** source → expected AST shape (pretty-print and diff).
- **Semantic:** ill-typed programs → expected error messages.
- **Interpreter:** program → expected output (before codegen even exists).
- **Codegen (the killer test):** compile → run `java` on the output → compare stdout with
  the interpreter's output. **The interpreter becomes the oracle for the compiler.**
- **Bytecode:** run `javap -c` on our `.class` and on `javac`'s, and diff.
