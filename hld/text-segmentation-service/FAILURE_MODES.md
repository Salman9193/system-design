# Text Segmentation Service — Failure Modes

The defining property of this system: **its worst failures are silent.** Segmentation almost never
throws — it just returns *different* tokens, and everything downstream quietly degrades.

---

## The Silent Failures (ranked by damage)

| # | Failure | Symptom | Detection | Mitigation |
|---|---------|---------|-----------|------------|
| 1 | **Index/query dictionary skew** | documents unreachable; recall drops | **only** a labelled recall canary | version pinning; stamp `dictVersion` per segment |
| 2 | **Partial dictionary load** | some words missing ⇒ wrong splits | checksum verification at load | content-addressed artifacts; **fail readiness, don't serve** |
| 3 | **Bad dictionary published** | quality regression across the fleet | canary recall eval; CTR monitoring | blocking pre-rollout eval; staged rollout; pointer-flip rollback |
| 4 | **Non-deterministic tie-break** | two replicas disagree on identical input | replica-diff canary (same text → both pods) | fixed iteration + tie-break order |
| 5 | **Unicode normalization drift** | same-looking text tokenizes differently | normalize at ingest; assert NFKC | pin Unicode version; normalize once, at the edge |

**#1 is the one to lead with in an interview.** It's invisible to every standard health signal —
latency normal, errors zero, CPU fine — and it destroys search quality.

---

## The Loud Failures (easier, but still need design)

| Failure | Blast radius | Mitigation |
|---------|-------------|------------|
| Dictionary artifact store (CDN/blob) down | **new pods can't start**; running pods fine | bake last-known-good into the image; cache locally; long TTLs |
| Config/manifest service down | version resolution fails | **fail static** — keep serving the last-known version, never fall back to "latest" |
| OOM from a huge tenant overlay | pod crashloop | upload-time quotas; LRU cap; overlay memory bulkhead |
| Pathological input (10 MB string) | thread monopolised | length caps; chunk at sentence boundaries; timeouts |
| Batch job saturates the cluster | query latency spikes | **separate pools** for batch vs interactive; priority classes |
| Thundering herd after deploy | all pods fetch the artifact at once | jittered fetch; peer-to-peer or CDN; pre-pull in init container |

---

## Degradation Ladder

The search path must **degrade, not fail** — a query with mediocre tokens beats a 500:

```
1. Normal        → base dict + tenant overlay + HMM
2. Overlay miss  → base dict only          (log it; slightly worse for that tenant)
3. HMM disabled  → dictionary DP only      (shed CPU under load)
4. Dict unloadable → character bigrams     (crude but functional — search still returns results)
5. Total failure → return the raw string as one token (never 500 the query path)
```

**Level 4 is the interesting one:** character-level fallback keeps CJK search *working*, just with
worse precision — vastly better than an unavailable search box.

---

## What to Alert On

Not just the usual latency/error signals — those stay green during the worst failure:

- **Recall on a labelled query set** — the *only* real detector of failure #1.
- **`dictVersion` cardinality in flight** — more than the expected 1–2 versions means skew.
- **Segmentation-length distribution drift** — average tokens/char shifting is an early warning of
  a bad dictionary (over-splitting or under-splitting).
- **OOV rate** — a sudden spike means the dictionary didn't load fully.
- **Replica-diff rate** — same input, two pods, different output ⇒ determinism broken.

> **The principle:** when failures are silent, you must alert on **output distributions**, not just
> on system health. Nothing in the standard golden-signals dashboard would catch a dictionary skew.
