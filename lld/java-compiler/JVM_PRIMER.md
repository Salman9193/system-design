# JVM Primer — What You Must Know to Emit Bytecode

You cannot hand-write a `.class` file without genuinely understanding these. This is the
"Java fundamentals" payoff of the project — not theory, but the things the verifier will
reject you for getting wrong.

---

## 1. The JVM Is a Stack Machine

There are **no registers**. Every operation pops its operands from the **operand stack** and
pushes its result.

```
    1 + 2          becomes        iconst_1     // push 1
                                  iconst_2     // push 2
                                  iadd         // pop 2, pop 1, push 3
```

**The consequence for codegen:** a post-order traversal of the expression AST *is* the
instruction order. Visit left child (pushes), visit right child (pushes), emit the operator
(pops both, pushes result). That's why the AST → bytecode step is so clean.

---

## 2. Operand Stack vs. Local Variable Slots — the #1 confusion

Two completely different storage areas per method frame:

| | Operand stack | Local variable array |
|--|---------------|----------------------|
| What | Scratch space for computation | Named slots for parameters & locals |
| Access | push / pop (implicit) | `iload N` / `istore N` (indexed) |
| Sized by | `max_stack` | `max_locals` |

```
int x = 1 + 2;
    iconst_1        // operand stack: [1]
    iconst_2        // operand stack: [1, 2]
    iadd            // operand stack: [3]
    istore_1        // stack: [] ; local slot 1 = 3
```

**Slot rules:** in an instance method, **slot 0 is `this`**; parameters occupy the next
slots; then locals. `long` and `double` take **two** slots each (a classic bug source).

---

## 3. The Constant Pool

A class file doesn't inline strings, method names, or class names — it puts them in a
**constant pool** and refers to them by index. Entries are typed (`CONSTANT_Utf8`,
`CONSTANT_Class`, `CONSTANT_Fieldref`, `CONSTANT_Methodref`, `CONSTANT_String`, …) and are
**interlinked** (a `Methodref` points at a `Class` and a `NameAndType`, which point at
`Utf8`s).

**Design implication:** the pool is a **deduplicating hash map + indexed list** — ask for
`"java/lang/System"` twice, get the same index. That's exactly a **Builder**. (Note: pool
indices are **1-based**, and `long`/`double` entries consume **two** slots — two more classic
bugs.)

---

## 4. Type Descriptors — the JVM's Type Language

Types are encoded as strings. You will read and write these constantly:

| Java type | Descriptor |
|-----------|------------|
| `int` | `I` |
| `boolean` | `Z` (not `B`!) |
| `long` / `double` / `float` / `char` / `byte` / `short` | `J` / `D` / `F` / `C` / `B` / `S` |
| `void` | `V` |
| `String` | `Ljava/lang/String;` (note the `L…;`) |
| `int[]` | `[I` |
| `String[][]` | `[[Ljava/lang/String;` |

**Method descriptors** are `(params)return`:

```
public static void main(String[] args)   →   ([Ljava/lang/String;)V
int add(int a, int b)                    →   (II)I
String toString()                        →   ()Ljava/lang/String;
```

---

## 5. The Four `invoke` Opcodes

| Opcode | Used for | Dispatch |
|--------|----------|----------|
| `invokestatic` | `static` methods | No receiver |
| `invokevirtual` | Normal instance methods | Dynamic (vtable) |
| `invokespecial` | Constructors (`<init>`), `super.m()`, `private` | Non-virtual, exact |
| `invokeinterface` | Interface methods | Dynamic (itable) |
| `invokedynamic` | Lambdas, string concat (modern `javac`) | Bootstrapped call site |

**The object-creation dance** (three instructions, and the order surprises everyone):

```java
new StringBuilder()
    new  #2          // allocate (uninitialized) — pushes ref
    dup              // duplicate the ref (constructor consumes one)
    invokespecial #3 // call <init> on one copy
                     // the other copy remains as the result
```

---

## 6. Class File Layout

```
magic          0xCAFEBABE
minor/major    version (e.g. 52 = Java 8)
constant_pool  count + entries
access_flags   ACC_PUBLIC | ACC_SUPER ...
this_class     → cp index
super_class    → cp index (java/lang/Object)
interfaces     count + entries
fields         count + field_info[]
methods        count + method_info[]   ← each has a Code attribute
attributes     count + attribute_info[]
```

Each method's **`Code` attribute** carries: `max_stack`, `max_locals`, the bytecode itself,
an exception table, and further attributes (line numbers, **stack map frames**).

---

## 7. Stack Map Frames — the thing that will bite you

Since Java 7 (class file version 50+), the verifier requires **`StackMapTable`** — a
declaration of the operand-stack and local-variable types at each **jump target**. It exists
so verification is a single fast pass instead of a fixed-point iteration.

**Why it bites:** the moment you emit an `if` or a `while`, you have jump targets, and the
JVM will reject your class with a `VerifyError` unless the frames are correct.

**Our strategy (deliberate):** target **class file version 49 (Java 5)**, where the verifier
falls back to the old inference-based path and `StackMapTable` is *not* required. This lets
Milestones 4–5 focus on codegen. We then add stack maps properly as a later milestone —
learning them on purpose, not as an unplanned wall.

---

## 8. Tools You'll Live In

| Tool | Use |
|------|-----|
| `javap -c -p Foo` | Disassemble bytecode — **your primary debugger** |
| `javap -v Foo` | Verbose: constant pool, frames, attributes |
| `javac -g Foo.java` | Compile a reference version to compare against |
| `java -verify` / `-Xverify:all` | Force verification |
| `xxd` / hex viewer | Inspect your raw `.class` bytes |

**The workflow that makes this tractable:** write the Java you want to compile → run `javac`
on it → `javap -c` the result → *that's your target output*. Make your compiler produce that.
You're never guessing.

---

## The Fundamentals This Clears Up (as a side effect)

- Why `boolean` is an `int` on the stack (there's no boolean opcode — `Z` in descriptors,
  `iload`/`istore` in code).
- Why `long`/`double` take two slots (and two constant-pool entries).
- What string concat *really* compiles to (`StringBuilder` chains, or `invokedynamic` in
  modern `javac`).
- Why constructors are `<init>` and static initializers are `<clinit>`.
- What autoboxing, enhanced-`for`, and varargs desugar into.
- Why the JVM verifier exists and what it checks.
