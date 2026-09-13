# Notification Service — Deep Dives

## 1. Timing as an Optimization Problem (the elegant part)

The naive answer to "when should I send?" is "now" or "outside quiet hours." Uber's CCG does
something much sharper, and it's a beautiful connection to classic algorithms.

**The problem:** a user's inbox holds N candidate pushes; there are S possible delivery slots over a
time horizon (say 2/day for 7 days). Which (push, slot) pairs maximize total value, subject to
constraints? With N pushes and S slots, the number of schedules grows **factorially** — can't
enumerate.

**The reframe:** this is the **[Assignment Problem](https://en.wikipedia.org/wiki/Assignment_problem)**
— assign pushes to time slots to maximize the sum of (push, time) scores. Solve it with an **integer
linear program**:

```
maximize   Σ  s(i,t) · x(i,t)         x(i,t) ∈ {0,1}: send push i at time t
subject to linear constraints:
   • frequency cap:      Σ_i x(i,t-window) ≤ N per day
   • min spacing:        ≥ 8h between two pushes
   • send window:        only within allowed hours / store open hours
   • expiry:             send before a promo expires
```

**Why an ILP beats a greedy heuristic:** it can see globally — it'll rush an *expiring* push out even
if a more valuable one looks better right now, and it exploits time-varying scores (push A converts
at lunch *and* dinner, push B only at lunch ⇒ send B at lunch, A at dinner, capturing both). A greedy
"send the highest-scoring thing next" strands the expiring one and double-books the lunch slot.

> This is the same **assignment/matching** structure as
> [bipartite matching](https://salman9193.github.io/dsa-problems/#guides/FLOW_MATCHING) — a genuinely
> nice bridge from a DSA topic to a production system.

**Scoring the pairs:** an ML model (Uber uses **XGBoost**) predicts `P(user converts within 24h |
push i at time t)` from time features (hour, day-of-week), push features (category, deeplink), and
user features (order/engagement history by day and mealtime). The score `s(i,t)` feeds the ILP. When
the inbox exceeds the cap, the low-value pushes are simply **dropped** — suppression falls out of the
optimization for free.

---

## 2. Deduplication & Aggregation

At-least-once delivery + fan-out = the same notification can be generated multiple times, and related
events pile up. Two mechanisms:

- **Dedup:** a per-notification **idempotency/dedup key**; within a window, a repeat key is
  suppressed. Requires short-lived per-user recent-send state (why decisions are per-user and local).
- **Aggregation:** collapse related events into one message — "3 people liked your post" instead of
  three pushes. This is a **windowed reduce** per (user, notification-type): buffer for a short time,
  then emit a summary. ATC "heavily leverages local state to batch and aggregate."

**The trade:** a longer aggregation window = fewer, richer notifications but higher latency. Fine for
"likes"; wrong for an OTP. Window length is per-category.

---

## 3. Frequency Capping = a Per-User Rate Limiter

"At most N notifications/day per user (and per category)" is exactly the
[Distributed Rate Limiter](#hld-distributed-rate-limiter) problem, keyed by user id:

- the counter lives with the user's partition (local, atomic — no cross-host coordination);
- it's a **budget** the relevance/ILP layer spends on the highest-value notifications;
- caps are per-category (5 social/day but 1 marketing/day), so it's a small set of counters per user.

Because everything is partitioned by user, the cap check is a **local read** — none of the
distributed-counter complexity the rate-limiter HLD needs when a key's traffic is spread across the
fleet. **Partitioning by recipient makes the hard version easy.**

---

## 4. Channel Selection

Given a notification survives suppression, *which channel*? Push, email, SMS, or in-app inbox?

- **Relevance/urgency drives it:** high-urgency → push; informational → in-app inbox only;
  digestible → batched email.
- **Unseen-escalation** (ATC's pattern): a push left **unseen for 24–48h** gets **escalated to email**
  — the channels back each other up. Priority affects the delay (urgent job alert escalates sooner
  than a connection request).
- **Cost & deliverability:** SMS costs money and has carrier limits; email has sender-reputation
  limits; push is cheap but ignorable. The platform balances these per message.

---

## 5. Handling Fan-Out

One event → millions of recipients. The amplification point.

- **Fan-out on write vs. on read:** for an in-app feed/inbox, you can *fan out on write* (push into
  each follower's inbox) or *on read* (compute at view time). Celebrities with millions of followers
  make pure fan-out-on-write explode → a **hybrid** (fan out for most, compute-on-read for
  huge-fanout accounts) — the same [feed trade-off](#fu-data-processing) the industry converged on.
- **Backpressure:** a broadcast enqueues tens of millions of per-user decisions; Kafka absorbs the
  spike and the decision tier drains at its own rate. Transactional traffic uses a **separate
  topic/priority** so a marketing broadcast can't delay an OTP.

---

## 6. Delivery Guarantees & the OTP Problem

- **At-least-once** via the durable log: persist at ingest, ack only after delivery, replay on crash.
- **Idempotency** so at-least-once doesn't become "user got the same OTP 3 times" — dedup key again.
- **Exactly-once is a myth end-to-end** (the device might receive and fail to render); the honest
  target is **at-least-once + idempotent rendering**, with the dedup key making duplicates harmless.
- **Expiry:** many notifications are worthless if late ("driver arriving" after arrival). Delivery
  drops **expired** messages rather than sending stale ones.
