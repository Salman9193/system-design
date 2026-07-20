# Text Segmentation Service — AI Evolution

## Where This Sits Now

Dictionary + DP + HMM is a **1990s-era statistical NLP design** that remains in production
everywhere — because for tokenization, **determinism, speed, and fixability beat raw accuracy**.
That trade is worth examining honestly, since it's changing.

---

## 1. Neural Segmentation

| | Dictionary + DP | BiLSTM-CRF | BERT-style |
|---|---|---|---|
| F1 (news) | ~95% | ~97% | **~98%** |
| OOV handling | HMM patch | **good** | **excellent** |
| Latency | **µs, CPU** | ms, GPU-preferred | 10s of ms, GPU |
| Cost | negligible | high | **very high** |
| Deterministic | **yes** | version-sensitive | version-sensitive |
| Fix a bad case | **add a word** | retrain | retrain |

**The pragmatic architecture is hybrid:** dictionary DP for the hot path, neural for the hard
cases. The `UnknownWordModel` **Strategy** is exactly the seam — route only the OOV runs (a small
fraction of text) to a neural model, keeping ~99% of the work on the cheap path.

**The blocker for search isn't accuracy — it's determinism.** A neural tokenizer that changes its
output after a model update reintroduces the index/query skew failure, but now *without* a
version-pinnable artifact you can reason about. Model version becomes index schema, which is a
harder pill than dictionary version.

---

## 2. Subword Tokenization (BPE / SentencePiece / WordPiece)

LLMs sidestep word segmentation entirely: learn a **subword vocabulary** from data and tokenize
without any linguistic dictionary. Language-agnostic, no OOV by construction.

**But it doesn't replace this service**, because subword tokens are *not words*:

- `北京大学` might become `北` + `京大` + `学` — meaningless as **search index terms**.
- Users search for **words**; BPE fragments don't align with query intent or with highlighting.

**They solve different problems:** BPE optimises for *model input compression*; word segmentation
optimises for *human-meaningful units*. A search index needs the latter. Expect both to coexist —
BPE inside models, word segmentation for retrieval, indexing, and display.

---

## 3. LLMs as the Consumer, Not the Replacement

Growing use: this service as **retrieval infrastructure for RAG** (see
[RAG Knowledge Assistant](#hld-rag-knowledge-assistant)). Hybrid retrieval — BM25 (which **needs
word tokenization**) plus vector search — is now standard, because lexical matching still beats
embeddings on rare terms, names, and exact IDs.

So the trajectory isn't "LLMs kill tokenizers." It's **tokenizers become an input to the LLM
stack**: chunking documents on word boundaries, extracting keywords for hybrid search, and building
the sparse half of a hybrid index.

---

## 4. LLM-Assisted Dictionary Curation

The realistic near-term win isn't replacing the algorithm — it's **feeding it**:

- **Mine new words** from logs, then have an LLM validate whether a candidate is a genuine word.
- **Auto-tune frequencies** by evaluating segmentation quality on held-out text.
- **Explain regressions:** "why did recall drop after v43?" → diff the dictionaries, replay the
  canary set, surface the specific words whose paths changed.

Dictionary curation is currently manual and expert-driven; it's the highest-leverage place to apply
a model, and it **preserves determinism** — the artifact is still a pinned, inspectable word list.
The model works at **build time**, not on the hot path.

> **The design principle to carry:** put the AI where its non-determinism is *acceptable* (offline,
> build-time, human-reviewed) and keep the serving path a pure, versionable function. That's what
> lets you get model-quality gains without giving up reproducible search.
