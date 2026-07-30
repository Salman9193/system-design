# Text Segmentation Service — Requirements

A **word segmentation platform**: turn unsegmented text (Chinese, Japanese, Thai — any language
without spaces) into tokens, at scale, for two very different consumers.

Built on the [Chinese Word Segmenter LLD](#lld-chinese-word-segmenter), which is the single-process
engine this service wraps.

---

## Two Consumers, One Engine

This is the framing decision that shapes everything:

| | **A. Search platform** (internal) | **B. Multi-tenant NLP API** (external) |
|---|---|---|
| Caller | Indexer + query parser | Third-party developers |
| Volume | **Billions** of docs (batch), high QPS (query) | Moderate QPS, spiky |
| Latency | Query path: **p99 < 5 ms** | p99 < 100 ms acceptable |
| Correctness bar | **Index and query must agree exactly** | Best-effort quality |
| Dictionary | One curated dictionary per corpus | **Per-tenant custom dictionaries** |
| Failure impact | **Silent recall loss** | Visible API error |

They share the engine but pull the design in opposite directions — A wants determinism and
co-location, B wants isolation and flexibility. The design must serve both without pretending
they're the same problem.

---

## Functional Requirements

1. **Segment text** — single string and batch, with mode selection (default / full / search / no-HMM).
2. **Tokenize with offsets** — `(word, start, end)` for highlighting and NER.
3. **Custom dictionaries** — per-tenant word lists, uploaded and versioned.
4. **Dictionary versioning** — every response is attributable to an exact `(base, overlay)` version pair.
5. **Version pinning** — a caller can demand a specific dictionary version and get it or a hard error.
6. **Batch/offline path** — segment a whole corpus for indexing.
7. **Dictionary lifecycle** — publish, stage, roll out, roll back.

## Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| **Determinism** | same text + same dictionary version ⇒ **byte-identical** tokens, on every replica, forever |
| Query-path latency | **p99 < 5 ms** (co-located), < 100 ms (remote API) |
| Throughput | 100k+ QPS aggregate; batch: millions of docs/hour |
| Availability | 99.95% for the API; the search path must **degrade, not fail** |
| Dictionary rollout | staged, observable, **reversible** |
| Tenant isolation | one tenant's dictionary or load cannot affect another |

---

## The Requirement That Drives the Design

> **Index-time and query-time segmentation must use the *same* dictionary version.**

If the indexer tokenizes `北京大学` as one token, but a later query — after a dictionary update —
tokenizes it as `北京 / 大学`, the document becomes **unreachable**. Nothing errors. No alert
fires. Recall just silently drops.

This makes the dictionary a **versioned, pinned artifact**, not a config file — and it makes
"just push the new dictionary everywhere" an *outage*. Everything in the Design and Deep Dives
tabs follows from this one requirement.

---

## Scale Estimates

- Base dictionary: ~350k words ⇒ ~2M prefix entries ⇒ **~200–400 MB** heap.
- Tenant overlays: 10²–10⁵ words each — small, but there may be **thousands of tenants**.
- Corpus: 10⁹ documents × ~500 chars — a full reindex is **days** of compute.
- Query path: ~10 segmentations per search request.

**The memory number is the constraint that matters:** a 300 MB dictionary per process means you
cannot naively run one process per tenant.

## Out of Scope

Full-text search itself (see [Search Typeahead](#hld-search-typeahead)), model training, and
translation. This service produces tokens; consumers do the rest.

---

## Engineering Blogs & Primary Sources

Chinese/CJK segmentation as a production search concern is well-documented by the search-engine
vendors, and jieba itself is the reference implementation. These confirm the specific claims this HLD
makes — most importantly, the index/query dictionary-consistency spine.

- **jieba — the reference segmenter.** https://github.com/fxsjy/jieba
  The prefix-dictionary + DAG + max-probability-route + HMM-Viterbi design this service productionizes.
  Its README documents the four cut modes and the user-dictionary mechanism that the
  [Chinese Word Segmenter LLD](#lld-chinese-word-segmenter) implements. → the whole design's source.

- **Elastic — Smart Chinese Analysis plugin (`analysis-smartcn`).**
  https://www.elastic.co/guide/en/elasticsearch/plugins/current/analysis-smartcn.html
  Elasticsearch's official Chinese analyzer: *"uses probabilistic knowledge to find the optimal word
  segmentation… text is first broken into sentences, then each sentence is segmented into words"* — a
  **Hidden Markov Model** over a training corpus, i.e. exactly the algorithm in this service. Confirms
  the design isn't academic: this is how a production search engine tokenizes Chinese. → backs the
  **Design** tab.

- **mimacom — "你们好: Elasticsearch and the Chinese language" (2019).**
  https://blog.mimacom.com/elasticsearch-chinese-language/
  A hands-on account that surfaces this HLD's spine directly: the analyzer plugin **must be installed
  on every node, and every node restarted** — a version/consistency constraint identical to this HLD's
  *"index-time and query-time segmentation must use the same dictionary version."* Also shows the
  Traditional-vs-Simplified pitfall (a mismatched dictionary silently mis-tokenizes). → backs the
  **Failure Modes** (dictionary skew) tab.

- **elastic/elasticsearch-analysis-smartcn (source).**
  https://github.com/elastic/elasticsearch-analysis-smartcn
  The plugin source — how Lucene's `SmartChineseAnalyzer` is wired into a distributed search engine as
  a versioned, deployable artifact. Real-world instance of the **dictionary-as-artifact** model. →
  backs the **Design** (versioned artifact) tab.

- **IK Analyzer / jieba ES plugins (`elasticsearch-analysis-ik`, `elasticsearch-jieba-plugin`).**
  https://github.com/medcl/elasticsearch-analysis-ik
  The most-used community Chinese analyzers, notable for **hot-reloadable dictionaries** (`ik_max_word`
  vs `ik_smart` = jieba's full vs. default modes) — the exact **dual-mode** and **dynamic dictionary**
  features this HLD specifies, shipped in production. → backs the **Design** (modes) and **Deep Dives**
  (dictionary rollout) tabs.

**The through-line:** the search-engine vendors independently productionize *this exact design* — an
HMM/dictionary segmenter as a **versioned, per-node analyzer artifact**, with the hard operational
constraint that **every node must run the same version** or tokenization diverges. That constraint,
which this HLD elevates to its central thesis (index/query recall silently breaks on a version
mismatch), is visible in every one of these deployment guides.
