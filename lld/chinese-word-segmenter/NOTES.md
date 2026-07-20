# Chinese Word Segmenter — Notes & Trade-offs

> **Design-patterns reference:** [LLD Fundamentals → Design Patterns](#lf-design-patterns).
> **The service/deployment side:** [Text Segmentation Service (HLD)](#hld-text-segmentation-service).

## The DSA Bridge

| Component | The algorithm | The DSA problem |
|-----------|---------------|-----------------|
| **Prefix dictionary** | prefix lookup with O(1) dead-end detection | [Implement Trie #208](https://salman9193.github.io/dsa-problems/#strings/implement-trie) (the structure jieba *rejects*, and why) |
| **DAG + route DP** | max-probability path = **longest path in a weighted DAG** | [Word Break #139](https://salman9193.github.io/dsa-problems/#dynamic-programming/word-break) — *literally this problem in English* |
| **Full mode** | enumerate **all** segmentations | [Word Break II #140](https://salman9193.github.io/dsa-problems/#dynamic-programming/word-break-ii) |
| **Viterbi (B/M/E/S)** | DP over a state lattice | the same *linearize-then-relax* skeleton |
| **Position ordering** | topological order comes free | [Parallel Courses III #2050](https://salman9193.github.io/dsa-problems/#graphs/parallel-courses-iii) (weighted DAG longest path) |

**The realisation worth carrying:** Word Break asks *"can this string be segmented?"* (boolean),
Word Break II asks *"give me every segmentation"* (jieba's **full mode**), and jieba asks *"give me
the **most probable** segmentation"* — the same DAG, three different relaxations. Chinese
segmentation is Word Break with **frequencies** and a **statistical fallback**.

---

## Why Not a Trie? (the decision people will probe)

Jieba stores **every prefix of every word** as a flat hash-map key rather than building a Trie.
Both give O(1) per-character extension and O(1) dead-end detection; they differ on everything else:

- **Hash map wins:** cache locality (flat, no pointer chasing), trivial serialization (dump the
  map to a cache file), far less code.
- **Trie wins:** memory (prefixes are *shared*, not duplicated as separate keys), and it supports
  prefix **enumeration** (which segmentation doesn't need, but autocomplete does).

For a ~350k-word dictionary the prefix map is ~2M entries — bigger, but flat and dumpable. Jieba
trades **memory for locality and simplicity**, which is usually right on modern hardware.

> If asked "would you use a Trie?" — the answer isn't yes or no, it's *"depends on whether you need
> prefix enumeration and how tight memory is."* A **double-array Trie** (as used by darts/MeCab) is
> the option that gets you both compactness *and* locality, at the cost of expensive rebuilds —
> which matters because dictionaries change rarely and are read constantly.

---

## Why Log Space (a small detail that's actually load-bearing)

Segmentation score is a **product** of word probabilities. For a 30-character sentence with
probabilities around 1e-5, the product underflows `double` and every candidate collapses to `0.0` —
the DP would then pick arbitrarily.

Taking logs turns the product into a **sum**, converting "maximum probability path" into
"maximum-weight path," which is exactly the DAG longest-path DP. Log space is not an optimisation
here; **it's what makes the algorithm work at all.**

---

## Failure Modes & Deliberate Choices

| Situation | Choice here | Alternative |
|-----------|-------------|-------------|
| Word not in dictionary | buffer the single-char run, re-segment with **HMM** | emit single chars (No-HMM mode — deterministic) |
| Ambiguous split | highest **total** log-probability path | greedy longest-match (faster, less accurate) |
| Unigram model only | no context beyond word frequency | bigram/CRF/neural — better accuracy, much slower |
| HMM invents a wrong word | accepted; user dict + `suggestFreq` corrects it | disable HMM for reproducible pipelines |
| Dictionary mutated at runtime | copy-on-write overlay; readers never block | lock the dictionary (kills throughput) |

**The subtle one:** HMM mode is **not deterministic across dictionary versions** — new words change
which runs reach the HMM at all. For a *search index* that's dangerous (see the HLD), which is
precisely why `NO_HMM` exists.

---

## Concurrency Notes

- **Init once, read forever.** The dictionary is mutable only during load; afterwards it's
  effectively immutable and shared, so segmentation needs **no locking**.
- **Lazy holder idiom** for the default tokenizer — the JVM guarantees the class-init lock, giving
  thread-safe lazy initialization with zero hot-path cost (better than double-checked locking).
- **User-dict overlay** is a `ConcurrentHashMap` layered *over* the base map, so `addWord` never
  mutates the shared structure readers are traversing.
- Segmentation itself is **pure** — no shared mutable state — so it parallelises by simply
  partitioning the input (jieba's `enable_parallel` splits by line).

---

## Testing Checklist

- Golden DAGs for known sentences.
- **Ambiguity:** `北京大学生` with controlled frequencies — assert the higher-probability path wins,
  and that flipping the frequencies flips the result.
- **Viterbi legality:** no `B→S` or `E→M` in any output tag sequence.
- Full mode ⊇ default mode; search mode ⊇ default mode.
- **Offsets reconstruct the input exactly** (`text.substring(t.start, t.end).equals(t.word)`).
- Mixed Chinese/Latin/digits/punctuation pass through unbroken.
- **Determinism:** identical output across runs and JVMs for the same dictionary version.
- Concurrency: N threads segmenting while one thread calls `addWord` — no exceptions, no torn reads.

---

## Extension Points

- **POS tagging** — a parallel HMM over part-of-speech tags (jieba's `posseg`).
- **Keyword extraction** — TF-IDF / TextRank on top of the token stream (jieba's `analyse`).
- **Neural segmentation** — swap `UnknownWordModel`, or replace the whole engine behind the same
  interface (jieba's PaddlePaddle mode). The **Strategy** boundary is what makes this a drop-in.
- **Double-array Trie** — replace `PrefixDictionary` for a large memory win.
- **Bigram model** — score `P(wᵢ | wᵢ₋₁)` instead of `P(wᵢ)`; the DAG DP structure is unchanged,
  only the edge weight changes (though state must then include the previous word).
