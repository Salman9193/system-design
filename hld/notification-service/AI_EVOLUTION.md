# Notification Service — AI Evolution

## AI Isn't an Add-On Here — It's the Core

Unlike most systems in this repo (where the rule is "keep the model off the hot path"), a modern
notification platform is **already ML-centric by design** — because the core question, *"should this
specific user get this specific message, on which channel, at what time?"*, is a prediction problem.
The delivery mechanics are commodity; **the intelligence is the product.** Both anchor systems are
built around this: ATC's relevance processors and CCG's XGBoost-scored ILP.

The discipline is *where* the ML runs: **models are trained and scored offline and pushed into local
state; the online path reads scores and solves a fast optimization.** The hot path stays deterministic
and quick even though the *inputs* to its decision are ML-derived.

---

## 1. Relevance & Ranking (already core)

Rank and drop notifications by predicted value. Uber scores each (push, time) pair with an **XGBoost
model** predicting `P(convert within 24h)`; ATC scores incoming requests to choose drop / in-app /
email / push. This is what turns "send everything" into "send the few things worth sending" — the
fatigue guard, learned rather than rule-based.

**Near-term:** multi-objective models (conversion **and** long-term retention **and** opt-out risk,
not just clicks), and **near-real-time features** so the score reflects what the user did five minutes
ago, not yesterday.

---

## 2. Timing (the ILP + ML combination)

Covered in Deep Dives: an ML model scores (push, time) pairs, and an **integer linear program** picks
the schedule that maximizes total predicted value under business constraints (caps, spacing, expiry,
send windows). This is the clean pattern — **ML for the values, optimization for the decision** — and
it's more robust than a greedy model-only approach because the constraints are *guaranteed*, not
learned.

---

## 3. Send-Time & Channel Personalization

Learn *per user* when they engage (someone checks their phone at 7am; someone else at 11pm) and which
channel they act on (push-ignorer who opens every email). The system already has the feedback loop —
delivery/open/click events flow back — so these become standard learned features rather than global
heuristics like a fixed "quiet hours" window.

---

## 4. Where LLMs Fit

- **Content generation & localization:** draft notification copy, adapt tone per user, localize —
  offline, human-reviewed, never a per-send blocking call.
- **Summarization for aggregation:** an LLM can turn "5 related events" into one natural summary
  notification, a smarter version of "3 people liked your post."
- **Not in the send decision hot path:** the allow/drop/schedule decision must stay a fast,
  explainable optimization — "we sent this because it scored 0.7 and fit your 2/day budget" is
  defensible; "an LLM felt like it" is not, and it's too slow at 100k/sec.

---

## 5. What Must Not Change

- **Suppression stays aggressive.** The most important thing AI does here is send *less*. A model that
  optimizes engagement without an opt-out/fatigue objective will learn to spam — the objective
  function must price in long-term trust, or you rebuild the problem you started with.
- **Transactional stays rule-based and immediate.** No model decides whether to send an OTP. AI
  governs the *marketing* lane; the *critical* lane is deterministic.
- **The optimization stays constrained.** Frequency caps, quiet hours, and expiry are **hard linear
  constraints**, not soft model preferences — so a mis-trained model can't override them.

> **The consistent principle:** AI decides *what's worth sending and when*, offline and scored into
> local state; a fast constrained optimization makes the actual per-user call. The model raises
> relevance; the constraints protect the user. A notification platform is the case where the ML *is*
> the system — and precisely because of that, the guardrails (caps, lanes, suppression) matter more,
> not less.
