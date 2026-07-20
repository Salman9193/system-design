# Chinese Word Segmenter (jieba-style) — Problem

Design a **word segmentation library** for Chinese text: given a sentence with **no spaces**,
split it into words.

```
Input:   我爱北京天安门
Output:  我 / 爱 / 北京 / 天安门
         (I / love / Beijing / Tiananmen)
```

Modelled on [jieba](https://github.com/fxsjy/jieba), the de-facto standard Chinese segmenter.

---

## Why This Is Hard

English gives you word boundaries for free — spaces. Chinese does not. Every position between
two characters is *potentially* a boundary, so an `n`-character sentence has `2^(n-1)` candidate
segmentations, and you must pick the right one.

Worse, the ambiguity is **real, not theoretical**:

```
北京大学生    could be   北京大学 / 生     (Peking University / student)
                  or   北京 / 大学生      (Beijing / university student)
```

Both are valid dictionary parses. Choosing correctly needs **word frequencies**, not just a
dictionary. And unknown words (names, neologisms, brands) aren't in *any* dictionary, so a purely
dictionary-driven approach silently shatters them into single characters.

---

## Functional Requirements

1. **Segment** a string into words, using a frequency dictionary.
2. **Multiple modes:**
   - **Default** — most probable segmentation (dictionary DP + HMM for unknowns)
   - **Full** — *every* word found in the dictionary (overlapping, for index recall)
   - **Search** — default, plus sub-words of long tokens (better search recall)
   - **No-HMM** — dictionary only, deterministic
3. **Unknown-word recovery** — recognise out-of-vocabulary words (names, new terms).
4. **Tokenize with offsets** — return `(word, start, end)` for highlighting and NER.
5. **User dictionaries** — load custom words; `addWord` / `delWord` at runtime.
6. **Force a segmentation** — `suggestFreq` to make a specific split win.
7. **Mixed input** — Chinese, Latin, digits, and punctuation interleaved.

## Non-Functional Requirements

- **Fast** — hundreds of thousands of characters/sec, single-threaded.
- **Thread-safe** — one shared dictionary, many concurrent callers.
- **Lazy + cached init** — the dictionary is ~350k entries; don't parse it on import, and don't
  re-parse it on every process start.
- **Deterministic** — identical input + identical dictionary ⇒ identical output. *(This matters
  more than it sounds; see the HLD.)*
- **Extensible** — new modes and new unknown-word models without touching the engine.

---

## The Core Design Question

> **Given a dictionary of words with frequencies, how do you choose among exponentially many
> segmentations — in linear time?**

The answer drives the whole design: build a **DAG** of all dictionary matches, then run a
**dynamic program** over it to find the **maximum-probability path**. Since the DAG's nodes are
character positions, they are *already topologically ordered* — so the DP is a single backward
sweep.

---

## Assumptions & Constraints

- Single JVM, in-memory dictionary (distribution/versioning is the
  [HLD](#hld-text-segmentation-service)).
- Dictionary fits in memory (~350k words ⇒ ~2M prefix entries).
- Unigram frequency model (no bigram/context model) — the deliberate accuracy/speed trade jieba
  makes.

## Out of Scope

Part-of-speech tagging, keyword extraction (TF-IDF/TextRank), and neural segmentation — all real
jieba features, but they layer *on top of* this core and are noted as extension points.
