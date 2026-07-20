# Text Segmentation Service — Trade-offs

## 1. Embedded Library vs. Remote Service

| | Library | Service |
|---|---|---|
| Latency | **µs** | ms (network) |
| Memory | 300 MB **per host** | amortised |
| Multi-tenancy | poor | **good** |
| Deploy independence | none — consumer redeploys | **independent** |
| Version skew risk | low (one process) | **needs explicit pinning** |
| Polyglot consumers | one JVM only | **any language** |

**Chosen: both.** Embed for the latency-critical internal search path; serve for external
multi-tenant traffic. Safe only because the engine is a **pure function** of
`(text, dictVersion, mode)` — the same inputs give the same outputs wherever it runs.

---

## 2. Dictionary DP vs. Neural Segmentation

| | Dictionary + DP + HMM (jieba) | Neural (BiLSTM-CRF / BERT) |
|---|---|---|
| Accuracy | good (~95% F1 on news) | **better** (~97–98%), far better on OOV |
| Latency | **~µs/sentence, CPU** | ms/sentence, wants GPU |
| Cost | negligible | **100–1000× more** |
| Explainability | **fully — trace the DAG path** | opaque |
| Fixability | **add a word, done** | retrain |
| Determinism | **exact** | version/hardware-sensitive |

**Chosen: dictionary DP as the default**, with the `UnknownWordModel` **Strategy** left open for a
neural upgrade. For search tokenization, *determinism and speed beat a couple of F1 points* — and
the ability to fix a bad segmentation by adding one dictionary entry is operationally decisive.

---

## 3. HMM On or Off

| | HMM on (default) | HMM off (`NO_HMM`) |
|---|---|---|
| OOV words (names, brands) | **recovered** | shattered into characters |
| Determinism across dict versions | weaker — new words change which runs reach the HMM | **strong** |
| Speed | slightly slower | **faster** |

**Chosen: HMM on for the API, off for search indexing.** Search needs index/query agreement above
all; a *slightly worse but perfectly reproducible* tokenization beats a better but drifting one.

---

## 4. Reindex vs. Dual-Tokenize on Dictionary Change

| | Full reindex | Dual-tokenize during migration |
|---|---|---|
| Correctness | **exact** | exact during overlap |
| Cost | **days of compute** | +~40% index size, temporarily |
| Cutover | slow | **fast, zero-downtime** |
| Rollback | slow | **instant** |

**Chosen: dual-tokenize for routine updates; full reindex for major dictionary revisions.** Small
word additions don't justify days of compute; a wholesale dictionary change does.

---

## 5. Per-Tenant Process vs. Shared Base + Overlay

| | Process per tenant | Shared base + overlay |
|---|---|---|
| Isolation | **strong** | logical only |
| Memory | 300 MB **× tenants** ✗ | **300 MB total** |
| Cold start per tenant | full load | overlay fetch (~10 ms) |
| Noisy neighbour | impossible | needs quotas + bulkheads |

**Chosen: shared base + overlay.** The memory arithmetic is decisive — 1000 tenants × 300 MB is not
a system. Isolation is recovered through quotas, LRU caps, and rate limits.

---

## 6. Prefix Hash Map vs. Trie vs. Double-Array Trie

| | Prefix hash map (jieba) | Trie | Double-array Trie |
|---|---|---|---|
| Memory | largest | medium | **smallest** |
| Locality | **good** (flat) | poor (pointers) | **excellent** |
| Build cost | low | low | **high** |
| Serialization | **trivial** | traversal | trivial (arrays) |

**Chosen: prefix hash map** for the reference implementation (simplicity, and it's what jieba
does); **double-array Trie** is the upgrade path when memory becomes the binding constraint. The
trade is build cost for size — and since dictionaries are built **rarely** and read **constantly**,
that's usually a trade worth making at scale.
