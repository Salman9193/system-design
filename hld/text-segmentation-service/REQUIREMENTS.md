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
