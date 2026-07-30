# Distributed Rate Limiter — AI Evolution

## Where AI Fits (and Emphatically Doesn't)

**Not in the decision.** Allow-or-reject is a hot-path, deterministic, correctness-and-latency-
critical operation. A probabilistic model deciding each request would add latency and non-determinism
to the exact place that can afford neither. The counter math stays exactly as it is.

**Where AI helps is in setting and adapting the *limits* — the control plane, offline, advisory** —
the same principle as every other design in this repo: put the model where non-determinism is
acceptable and keep the hot path a pure function.

---

## 1. Adaptive Limits

Today limits are static numbers a human picks and rarely revisits. They're almost always wrong —
too low (throttling legitimate growth) or too high (useless against abuse).

A model over historical traffic can propose **per-key, per-endpoint, time-of-day** limits: higher
during known peaks, tighter on endpoints that correlate with abuse, personalized to a tenant's normal
pattern. The limiter still *enforces* a concrete number each instant — the model just **chooses that
number**, offline, with human review.

---

## 2. Anomaly-Based Limiting

Static limits can't tell a viral-but-legitimate spike from an attack — both look like "lots of
requests." Behavioral models can separate them:

- A user's request pattern suddenly matching known credential-stuffing signatures → tighten *that*
  key's limit dynamically.
- A tenant's traffic 10× above their baseline but matching their *shape* → likely legitimate; don't
  throttle.

This shifts rate limiting from "**everyone gets the same number**" toward "**limits adapt to
behavior**" — closer to fraud detection than to a fixed quota. The enforcement primitive is
unchanged; the *limit* becomes a function of a risk score computed off the hot path.

---

## 3. Abuse & Bot Detection Feeding the Limiter

The richest near-term use: a **detection pipeline** (offline or near-line) scores keys for
abuse-likelihood, and the limiter consumes the score as an input to the limit. Clean separation:

```
traffic logs → risk model (offline) → per-key risk score → limiter reads score, adjusts limit
                                                            (enforcement stays deterministic)
```

The model never sees an individual request in real time; it shapes the *rules* the fast path applies.

---

## 4. What Must Not Change

- **The hot path stays deterministic.** No model call per request. Ever.
- **Enforcement stays explainable.** "You exceeded 100/min" is a defensible 429; "a model felt you
  were suspicious" is not — it's a support nightmare and often a compliance problem.
- **Failure stays safe.** If the ML scoring pipeline is down, the limiter falls back to static limits
  and keeps working. The intelligence is an *enhancement*, never a *dependency* of the hot path.

> **The consistent principle across this repo:** AI belongs where its latency and non-determinism are
> affordable — offline, advisory, human-reviewed — and the serving path stays a fast, deterministic,
> explainable function. A rate limiter makes the case starkly, because the hot path is *so* latency-
> and correctness-critical that there's simply no room for a model in it.
