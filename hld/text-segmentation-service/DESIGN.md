# Text Segmentation Service — Design

## High-Level Architecture

```
              ┌──────────────── CONTROL PLANE ────────────────┐
              │  Dictionary Registry (versioned, immutable)   │
              │    base:v42 ──┐   tenant:acme:v7 ──┐          │
              │  Build pipeline: raw → prefix-map → compiled  │
              │  artifact (content-addressed, checksummed)    │
              └───────┬──────────────────────┬────────────────┘
                      │ publish              │ notify (version manifest)
                      ▼                      ▼
              ┌──────────────┐      ┌──────────────────┐
              │ Blob storage │      │ Config service / │
              │  + CDN       │      │  version manifest│
              └──────┬───────┘      └────────┬─────────┘
                     │ pull artifact          │ watch
   ┌─────────────────┴────────────────────────┴──────────────────┐
   │                        DATA PLANE                            │
   │                                                              │
   │  A. SEARCH PATH (co-located library, not a network hop)      │
   │     ┌────────────┐            ┌────────────┐                 │
   │     │  Indexer   │            │Query parser│                 │
   │     │ +segmenter │            │ +segmenter │                 │
   │     └─────┬──────┘            └─────┬──────┘                 │
   │           │ writes dictVersion       │ reads dictVersion     │
   │           │ into the index metadata  │ from the index        │
   │           └───────────► SAME VERSION ◄┘                      │
   │                                                              │
   │  B. API PATH (stateless service, autoscaled)                 │
   │     LB → [Segmenter pods] → response + dictVersion header    │
   │              │  in-memory: base dict (shared) + tenant LRU   │
   └──────────────┴───────────────────────────────────────────────┘
```

---

## The Central Decision: Library or Service?

**Both — and choosing per-consumer is the design.**

| | **Embedded library** (search path) | **Remote service** (API path) |
|---|---|---|
| Latency | **µs** — no network | ms — network + queue |
| Determinism | trivially same code+dict in one process | needs version pinning across pods |
| Memory | 300 MB **per host** | amortised across tenants |
| Deploy | requires redeploying the *consumer* | independent |
| Multi-tenancy | poor | **good** |

**Rule:** if the caller is latency-critical and internal, **embed**. If it's external,
multi-tenant, or independently deployed, **serve**. Running the identical engine both ways is only
safe because the engine is a pure function of `(text, dictionary version, mode)`.

---

## Dictionary as a Versioned Artifact

The single most important structural choice: **dictionaries are immutable, content-addressed
build outputs — not files you edit in place.**

```
raw word list (git)  →  build  →  compiled prefix map + checksum  →  blob store
                                   dict-base-v42-sha256:9f3c…
```

- **Immutable & versioned.** `v42` always means the same bytes, forever.
- **Compiled, not parsed at boot.** The build emits the serialized prefix map, so a pod loads it
  by memory-mapping rather than parsing 350k lines (seconds → milliseconds).
- **Content-addressed.** The checksum *is* the identity, so a corrupted or partial download is
  detectable, and two pods claiming `v42` provably agree.
- **Effective version = `(base, tenantOverlay)` pair.** Both are pinned; both are echoed in every
  response.

---

## The Index/Query Consistency Protocol

This is the crux of the whole system:

1. **The indexer stamps the version.** Every document (or index segment) records the
   `dictVersion` used to tokenize it.
2. **The query path reads that version** from the index it's searching and segments the query with
   **the same** version.
3. **Upgrading the dictionary is a reindex**, not a config flip. New version ⇒ build a new index
   segment ⇒ atomically swap when complete.
4. **During migration both versions are live.** Old segments keep serving with `v42`; new segments
   use `v43`; the query path fans out and segments *per segment version*.

> **The mental model:** the dictionary is part of the index's **schema**, not its configuration.
> Changing it is a schema migration with a backfill — with all the staged-rollout machinery that
> implies.

---

## Request Flow (API path)

```
POST /v1/segment  { text, mode, tenantId, dictVersion? }
   │
   ├─ resolve version: explicit pin, else tenant's current pointer
   ├─ load: base dict (shared, resident) + tenant overlay (LRU cache)
   │        └─ miss → pull artifact from CDN → build overlay → cache
   ├─ segment (pure function; no I/O, no shared mutable state)
   └─ 200 { tokens:[{word,start,end}], dictVersion:"base:v42+acme:v7" }
```

**Always return the version.** It makes every result reproducible and every bug report actionable —
without it, "the segmentation changed" is unfalsifiable.

---

## Multi-Tenancy: One Base, Many Overlays

The base dictionary is 300 MB; a tenant overlay is kilobytes. So:

- **One shared, immutable base** per process (Flyweight — never copied).
- **Tenant overlays in an LRU cache**, layered over the base at lookup time (Decorator).
- **Never one process per tenant** — that would multiply 300 MB by the tenant count.

Cold-tenant requests pay an overlay fetch (~10 ms); warm ones are free. Route by tenant hash for
cache affinity (see Scaling).

---

## Batch Path

Reindexing 10⁹ documents is a **data-processing job**, not an API workload:

- Spark/Beam job; the segmenter runs **as a library inside the mappers** (never RPC per document).
- The dictionary is a **broadcast artifact** — pulled once per executor.
- **The version is pinned for the entire job**, so a mid-job dictionary publish cannot split the
  corpus across two tokenizations.
- Output stamps `dictVersion` alongside the tokens.
