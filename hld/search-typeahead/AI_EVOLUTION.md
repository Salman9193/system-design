# Search Typeahead — AI Evolution (Traditional → AI)

The base design (see the other tabs) is a strong *traditional* system: a precomputed
Trie with top-K-per-node, ranked by time-decayed popularity, served from cache. This
tab shows how AI/ML extends it — following the traditional→AI progression from
`frameworks/AI_SYSTEM_DESIGN.md`. The point isn't to replace the Trie with a model;
it's to show where ML earns its place and what it costs.

---

## Where the Traditional Design Falls Short

The Trie approach is fast and cheap, but it's **purely lexical and popularity-based**:

1. **No semantic understanding.** It completes prefixes by stored strings. Typing
   "affordable flights" won't surface "cheap airfare" — different words, same intent.
2. **Cold-start / long-tail gaps.** A never-before-typed prefix has no popular
   completions; rare but valid queries get no love.
3. **Typos break it.** "reciept" walks to a dead Trie branch (the base design only
   adds fuzzy matching as a late evolution step).
4. **No personalization or context.** Same completions for everyone, ignoring the
   user's history, location, or the current session.

Each gap is where an ML component can help — with a cost.

---

## The AI Evolutions (each with its trade-off)

### 1. Semantic / embedding-based candidates
Alongside the Trie, retrieve completions by **embedding similarity**: embed the
prefix/partial query and find semantically related popular queries in a vector index
(see `fundamentals/embeddings-and-vector-search.md`). Merge these with the Trie's
lexical completions — a **hybrid** of exact-prefix and semantic recall, exactly like
hybrid retrieval in RAG.
- **Buys:** intent matching ("affordable flights" → "cheap airfare"), long-tail
  coverage.
- **Costs:** an embedding model on (or near) the hot path, a vector index to maintain,
  and the recall/latency tuning that ANN requires — against a <50ms budget, so the
  embedding + ANN lookup must be fast or precomputed.

### 2. Learned ranking (replace the hand-tuned score)
The base design ranks by time-decayed popularity — a hand-crafted heuristic. A
**learned ranker** (a model over features: popularity, recency, personal history,
location, session context, click-through) orders candidates far better.
- **Buys:** better ordering, personalization, context-awareness.
- **Costs:** a ranking model to train, serve, and evaluate; features to compute at
  serving time; and **personalization breaks the edge cache** (results are no longer
  identical across users) — the same trade-off the base design flagged, now sharper.

### 3. Typo tolerance via embeddings
Semantic candidates naturally absorb typos: "reciept" embeds near "receipt", so
embedding retrieval returns the right completion without an explicit edit-distance
search.
- **Buys:** robustness to misspelling for free from the semantic path.
- **Costs:** folded into the embedding path's costs above.

### 4. Generative completions (the frontier, use with caution)
An LLM can *generate* completions rather than retrieve them. Powerful for novel/long
queries, but:
- **Costs & risks:** LLM latency almost certainly **blows the <50ms budget** on the
  hot path; token cost at ~1M QPS is prohibitive; and generation can **hallucinate**
  nonsensical or unsafe suggestions. Realistically this is offline (pre-generate
  candidate completions for popular prefixes and index them), not on the live
  keystroke path.
- **The staff move:** name that generation is the wrong tool for a sub-50ms,
  1M-QPS hot path — this is a "should this even use an LLM?" moment, and the honest
  answer is *not directly*. Use it offline to expand the candidate pool, keep serving
  from precomputed structures.

---

## The Hybrid Reality (what you'd actually build)

Production autocomplete is **both**, not either/or:

```
prefix ─┬─► Trie (lexical, precomputed top-K)     ─┐
        └─► Vector index (semantic candidates)     ─┼─► merge ─► learned ranker ─► top-K
                                                     │            (features, personal,
        (offline) LLM-expanded candidate pool ──────┘             context)
```

- **Lexical + semantic** candidates cover exact-prefix and intent.
- **Learned ranker** orders them with personalization/context.
- **LLM offline** expands the candidate pool for the long tail without touching hot-
  path latency.
- The **Trie + cache** still carries the bulk of hot, common prefixes cheaply — you
  don't pay for ML where the cheap deterministic path already wins.

---

## The AI-Specific Concerns This Adds

Adopting ML here pulls in the extra rubric dimensions from
`frameworks/AI_SYSTEM_DESIGN.md`:

- **Eval:** you can no longer assert correctness — measure suggestion quality by
  click-through and offline ranking metrics, and gate ranker/embedding changes on it.
- **Cost/latency:** embedding lookups and ranking inference add hot-path cost against a
  brutal latency budget; precompute wherever possible.
- **Cache tension:** personalization improves relevance but erodes the edge cache — the
  central trade-off, now driven by ML.
- **Freshness of models:** the embedding model is a versioned artifact; changing it
  means re-embedding the candidate corpus.
- **Guardrails:** generated or learned suggestions need safety filtering (no offensive/
  unsafe completions) — a content-moderation concern absent from the pure-popularity
  design.

---

## The Point of This Tab

The strongest AI answer to "add AI to autocomplete" is **not** "replace it with an
LLM." It's: keep the fast deterministic core, add a semantic path and a learned ranker
where they earn their place, push generation offline where its latency/cost/safety
problems don't hit the hot path, and stay honest that most keystroke traffic is best
served by the cheap precomputed Trie. That judgment — adopting ML surgically, not
wholesale, and knowing when *not* to — is the staff-level signal for AI system design.
