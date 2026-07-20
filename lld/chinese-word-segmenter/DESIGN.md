# Chinese Word Segmenter — Design

A **pipeline of stages**, each with a clean contract. The engine is dictionary-driven with a
statistical fallback.

---

## The Pipeline

```
  "我爱北京天安门, hello 2024!"
        │
        ▼  ① REGEX PRE-SPLIT — separate Han blocks from Latin/digits/punctuation
   ["我爱北京天安门"] ["hello"] ["2024"] …
        │
        ▼  ② DAG BUILD — every dictionary word starting at every position
   {0:[0], 1:[1], 2:[2,3], 3:[3], 4:[4,6], 5:[5], 6:[6]}
        │
        ▼  ③ ROUTE DP — max log-probability path (backward relax)
   route[i] = max over j∈DAG[i] of ( log freq(s[i..j]) − log total + route[j+1] )
        │
        ▼  ④ WALK THE ROUTE — emit words; buffer runs of unknown single chars
   我 / 爱 / 北京 / 天安门
        │
        ▼  ⑤ HMM VITERBI — re-segment the unknown buffers (B/M/E/S)
        │
        ▼  ⑥ MERGE — reassemble in original order
```

**Stages ②–③ are the engine; ⑤ is the safety net.** Everything else is plumbing.

---

## ① The Prefix Dictionary — and Why *Not* a Trie

This is the most interesting decision in the whole design.

To build the DAG you repeatedly ask: *"is `s[k..i]` a word, and is it worth extending further?"*
The obvious structure is a **Trie**. Jieba instead uses a **flat hash map in which every prefix of
every word is also a key**:

```
word "北京大学" (freq 2000) registers:
    "北"       → 0        (prefix only)
    "北京"     → 2000     (a real word)
    "北京大"   → 0        (prefix only)
    "北京大学" → 2000     (a real word)

freq > 0  ⇒ a real word
freq == 0 ⇒ prefix only — keep extending
absent    ⇒ dead end — STOP extending
```

That third case is the whole point: **the "absent" check gives O(1) early termination**, which is
exactly what a Trie's child-pointer lookup gives you — but without the pointer chasing.

### The trade-off (state this explicitly in an interview)

| | Trie | Prefix hash map |
|---|---|---|
| Lookup per extension | O(1) child pointer | O(1) hash |
| Memory | **shares prefixes** — compact | stores each prefix as a **separate key** — larger |
| Cache behaviour | pointer chasing, poor locality | **flat array, good locality** |
| Serialization | tree — needs traversal | **flat map — trivial to marshal/cache** |
| Implementation | node classes, more code | one `HashMap` |

Jieba trades **memory for locality and simplicity**. For a ~350k-word dictionary the prefix map is
roughly 2M entries — larger than a Trie, but flat, cache-friendly, and dumpable to a cache file in
one shot. On modern hardware that usually wins.

> **When the Trie wins:** memory-constrained environments, or when you also need prefix
> *enumeration* (autocomplete). See
> [Implement Trie #208](https://salman9193.github.io/dsa-problems/#strings/implement-trie).

---

## ② DAG Construction

For each start position `k`, walk forward while the fragment remains a *prefix*, recording every
position where it's a *real word*:

```java
for (int k = 0; k < n; k++) {
    List<Integer> ends = new ArrayList<>();
    int i = k;
    String frag = s.substring(k, k + 1);
    while (i < n && freq.containsKey(frag)) {   // absent ⇒ not even a prefix ⇒ stop
        if (freq.get(frag) > 0) ends.add(i);    // a real word ends here
        i++;
        if (i < n) frag = s.substring(k, i + 1);
    }
    if (ends.isEmpty()) ends.add(k);            // fallback: the single character
    dag.put(k, ends);
}
```

`DAG[k]` = all end positions of words starting at `k`. The single-character fallback guarantees
the graph is **connected** — there is always *some* path, so the DP can never fail.

**Complexity:** O(n · L) where `L` is the longest word (bounded, ~10) ⇒ effectively **O(n)**.

---

## ③ The Route DP — Maximum-Probability Path

Score a segmentation as the product of its word probabilities. Products of small numbers
**underflow**, so work in **log space** — which turns the product into a sum:

```
P(segmentation) = ∏ P(wᵢ)          →     log P = Σ log P(wᵢ)
P(w) = freq(w) / total             →     log P(w) = log freq(w) − log total
```

Maximising a product of probabilities becomes **maximising a sum of log-weights** — i.e. the
**longest path in a weighted DAG**:

```java
route[n] = 0.0;
for (int i = n - 1; i >= 0; i--)                    // positions are ALREADY topological
    route[i] = max over j ∈ DAG[i] of
                 ( log(freq(s[i..j])) − logTotal + route[j + 1] );
```

**This is the same skeleton as everything else in the repo: *linearize, then relax*.** Here the
linearization is free — character positions are inherently ordered, and every edge goes
left→right. **O(n)**.

### Why DP and not greedy longest-match

Greedy "take the longest dictionary word" is the classic naive segmenter and it is *wrong* on real
ambiguity, because the longest first word can strand a bad remainder:

```
北京大学生
  greedy:  北京大学 / 生       (longest first match wins)
  DP:      北京 / 大学生       (if that path has higher total probability)
```

The DP considers the **whole sentence's** probability, not a locally-longest prefix. That single
change is most of jieba's accuracy over naive maximum-matching.

---

## ⑤ Unknown Words — HMM + Viterbi

Words absent from the dictionary (names, brands, neologisms) appear in the route as **runs of
single characters**. Rather than emit them one-by-one, buffer the run and re-segment it with a
**Hidden Markov Model** over four hidden states:

| State | Meaning |
|-------|---------|
| **B** | Begin of a word |
| **M** | Middle |
| **E** | End |
| **S** | Single-character word |

The observation is the character; the hidden state is its **position within a word**. **Viterbi**
finds the most probable state sequence given start / transition / emission probabilities, and the
tag sequence is decoded back into words (`BME` ⇒ one 3-char word; `S` ⇒ a 1-char word).

**Legal transitions only** — this is the constraint that makes the output well-formed:

```
B → M, E        (a begin must be followed by middle or end)
M → M, E
E → B, S        (after an end, a new word starts)
S → B, S
```

`B → S` or `E → M` are structurally impossible, and encoding that in the transition table is what
prevents nonsense taggings.

**Viterbi is itself a DP** — `V[t][state] = max over prev of (V[t−1][prev] + trans + emit)` — the
same relax-over-a-lattice pattern, with **O(n · |S|²)** time (`|S| = 4`, so linear).

---

## ④ / ⑥ Modes — the Strategy Pattern

All four modes share stages ①–②; they differ only in what they do with the DAG:

| Mode | Behaviour | Use |
|------|-----------|-----|
| **Default** | route DP + HMM on unknown runs | general text |
| **No-HMM** | route DP only | deterministic / reproducible output |
| **Full** | emit **every** word in the DAG (overlapping) | index recall |
| **Search** | default, then also emit 2- and 3-char sub-words of long tokens | search recall |

`SegmentMode` is a **Strategy** — adding a mode is a new class, not an `if` in the engine.

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `SegmentMode` (default/full/search/no-HMM) | the segmentation policy is the thing most likely to change |
| **Strategy** | `UnknownWordModel` (HMM, none, future: neural) | the OOV recogniser is swappable |
| **Singleton** (lazy, holder idiom) | the shared default `Tokenizer` | one dictionary per process |
| **Builder** | `Tokenizer.Builder` | many optional knobs (dict path, user dicts, HMM on/off, cache) |
| **Decorator** | user dictionary layered over the base dictionary | add/override words without mutating the base |
| **Template Method** | `cut()` skeleton with mode-specific hooks | the pipeline is fixed; the steps vary |
| **Chain of Responsibility** | the stage pipeline | each stage consumes the previous stage's output |
| **Flyweight** | one immutable dictionary shared by all tokenizers | the dictionary is huge; never copy it |

---

## Concurrency & Initialization

The dictionary is ~350k entries; parsing it costs seconds. Two decisions:

1. **Lazy initialization with the holder idiom** (or double-checked locking with a `volatile`
   field). Never parse on class-load — many programs import the library and never segment.
2. **A marshalled cache file**, invalidated by comparing **mtime** against the source dictionary.
   Cold start parses and writes the cache; every later start memory-maps it.

After init the dictionary is **immutable and shared** — segmentation is then pure and
**thread-safe with no locking**. Runtime `addWord` uses copy-on-write or a concurrent overlay map,
so readers never block.

> **The rule:** mutate at init, read concurrently forever after. That's what makes a shared
> 2M-entry map safe without synchronisation on the hot path.

---

## Testing Strategy

- **DAG build** — golden DAGs for known sentences.
- **Route DP** — the ambiguous cases (`北京大学生`) with controlled frequencies, asserting the
  higher-probability path wins.
- **Viterbi** — tag sequences are *legal* (no `B→S`), and names segment as single tokens.
- **Modes** — full mode ⊇ default mode; search mode ⊇ default mode.
- **Offsets** — `tokenize()` offsets reconstruct the original string exactly.
- **Mixed input** — Latin/digits/punctuation pass through unbroken.
- **Determinism** — same input + same dictionary ⇒ byte-identical output, across runs *and*
  across JVMs.
