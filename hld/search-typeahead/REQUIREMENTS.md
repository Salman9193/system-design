# Search Typeahead / Autocomplete — Requirements

**Prompt:** Design the autocomplete system that powers search suggestions — as the
user types, show the top few most likely completions in real time.

This is a classic favorite: it looks simple, but the interviewer can drill
into Trie-at-scale, ranking, caching, and sharding — every rubric dimension. It
also connects directly to the DSA Trie work (`implement-trie`, `word-search-ii`).

---

## Phase 1 — Clarify Scope (the first 3–5 minutes)

**Do not start drawing.** Pin down what "autocomplete" means here.

### Clarifying questions worth asking
- Are we suggesting **search queries** (like a search engine's box) or **entities**
  (contacts, products)? → *Assume search queries.*
- Suggestions ranked by **global popularity**, or **personalized**? → *Start global,
  add personalization as an extension.*
- How many suggestions per keystroke? → *Top 5–10.*
- Do we need to reflect **brand-new trending queries** within seconds, or is an
  hourly/daily refresh acceptable? → *Near-real-time is a strong follow-up; start
  with periodic rebuild.*
- **Latency** target? → *This is the defining constraint — see below.*

---

## Functional Requirements

1. **Given a prefix, return the top-K most popular completions**, ranked.
2. Suggestions update as the popularity of queries changes over time.
3. Match on prefix (typing "new y" → "new york", "new york times", ...).

### Explicitly out of scope (state this — scoping is a staff signal)
- Spell correction / fuzzy matching ("seach" → "search") — flag as an extension.
- Full search execution — we only produce *suggestions*, not results.
- Personalization and per-user history — start global, note where it plugs in.

---

## Non-Functional Requirements

| Requirement | Target | Why it matters |
|-------------|--------|----------------|
| **Latency** | **< 100ms end-to-end, ideally < 50ms** | Suggestions must appear "instantly" as the user types. This is the binding constraint. |
| **Scale** | ~5B searches/day, ~10% unique prefixes | Drives sharding of the index |
| **Read:write ratio** | Extremely read-heavy | Every keystroke is a read; index updates are periodic |
| **Availability** | High (99.9%+) | Degrade gracefully — no suggestions is better than a slow box |
| **Freshness** | Minutes-to-hours acceptable (v1) | Trending terms are a follow-up, not the core |
| **Consistency** | Eventual | Suggestions need not be perfectly current |

**The key insight to state:** latency is the dominating constraint. A suggestion
that arrives after the user has typed the next character is useless. Everything in
the design bends toward serving from precomputed, in-memory structures at the edge —
never computing rankings on the hot path.

---

## Capacity Estimation

See `fundamentals/capacity-estimation.md` for the method.

**Query volume:**
- ~5B searches/day ÷ 86,400 ≈ **58K searches/s** average, ~150K/s peak.
- But autocomplete fires on **every keystroke**, not every search. A ~20-character
  query with debouncing might issue ~5–10 requests. So autocomplete QPS is roughly
  **5–10× search QPS → ~500K–1M requests/s peak.**
- → *This must be served from cache/in-memory at the edge; the origin index cannot
  see 1M QPS of cold traffic.*

**Index size:**
- Say ~100M distinct historical queries worth suggesting, average ~20 bytes each =
  ~2 GB of raw strings. A Trie with metadata (counts, top-K per node) inflates this
  ~5–10× → **~10–20 GB.**
- → *~20 GB does not fit comfortably with headroom on every edge node's RAM if we
  also cache results, and the write/rebuild path is heavy — so we shard the index by
  prefix and cache hot prefixes' results.*

**Write volume (for building the ranking):**
- Every search is a signal that increments a query's popularity. ~58K signals/s
  average. These are aggregated **offline/asynchronously**, not applied to the
  serving index synchronously.

**The conclusions that flow from the numbers:**
1. ~1M QPS peak, <50ms → serve precomputed top-K from an in-memory structure,
   heavily cached at the edge.
2. ~20 GB index → shard by prefix; no single node holds everything.
3. Ranking is computed **offline** from aggregated logs, then shipped to serving
   nodes — never computed per request.

---

## The One-Sentence Framing

> "This is a read-dominated, latency-critical system: ~1M QPS of keystroke reads
> that must return in under 50ms. The core idea is to precompute the top-K
> completions for every prefix offline, store them in a sharded in-memory Trie, and
> cache hot prefixes aggressively at the edge — so the hot path is a memory lookup,
> never a computation."

That sentence tells the interviewer you've already identified the binding
constraint (latency), the shape of the load (read-heavy, keystroke-level), and the
central technique (precompute + cache), before drawing a single box.
