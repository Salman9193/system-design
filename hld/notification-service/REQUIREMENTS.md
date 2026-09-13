# Notification Service — Requirements

Design the platform that delivers notifications — push, email, SMS, in-app — for a large product:
many internal senders (product, marketing, ops), billions of messages, and one shared goal that's
easy to state and hard to hit: **send the right message, to the right person, on the right channel,
at the right time — and no junk.**

This is one of the most common Staff design prompts, and it's deceptive: the "send a push" part is
trivial; the platform exists to solve everything *around* it — dedup, rate-limiting per user,
channel choice, timing, and preventing the notification fatigue that makes people disable
notifications entirely.

> Two production systems anchor this design and are cited throughout: **LinkedIn's Air Traffic
> Controller (ATC)** for member-first orchestration, and **Uber's Consumer Communication Gateway
> (CCG)** for ML-optimized timing. See the Engineering Blogs section.

---

## Functional Requirements

1. **Accept notification requests** from many internal services via a single API.
2. **Multi-channel delivery** — push (APNs/FCM), email, SMS, in-app inbox; pick the channel per
   message/user.
3. **User preferences** — per-user, per-category opt-in/out, quiet hours, channel preferences,
   frequency caps.
4. **Deduplication & aggregation** — merge duplicate/related notifications ("3 people liked your
   post", not 3 pushes).
5. **Rate limiting / frequency capping** — per user, per category (the fatigue guard).
6. **Scheduling** — deliver at the right *time* (respect quiet hours, time zones, predicted best
   time), not just immediately.
7. **Templating & localization** — render content per locale/device.
8. **Tracking** — delivery, open, click; feed back into relevance.

## Non-Functional Requirements

| Requirement | Target | Why it's hard |
|-------------|--------|---------------|
| **Scale** | **billions/month**, ~100k+ sends/sec peak | fan-out from one event to millions of users |
| **Latency (transactional)** | seconds end-to-end | an OTP or "driver arrived" must be instant |
| **Latency (marketing)** | minutes–hours is fine | these are *scheduled*, not real-time |
| **Reliability** | **at-least-once for critical**, no loss of an OTP | but *dedup* so at-least-once ≠ spam |
| **Deliverability** | high | respect provider limits or get throttled/blocked |
| **User trust** | **the real objective** | over-notify once and they disable forever |

---

## The Requirements That Actually Shape the Design

**1. Two traffic classes with opposite needs.** *Transactional* (OTP, security, "your order shipped")
must be **immediate and never dropped**. *Marketing/engagement* ("try this restaurant") is
**deferrable, droppable, and should be optimized for timing and relevance.** A single pipeline that
treats them the same either delays OTPs or spams users. **Separating these two is the first design
decision.**

**2. The enemy is fatigue, not delivery.** The naive system maximizes *sends*; the right system
*minimizes sends while maximizing value*. LinkedIn's ATC exists because uncoordinated teams each
sending notifications produced complaints and opt-outs — unifying under one "decision maker" let them
send **50% fewer emails and cut complaints 65%.** So the platform's job is as much **suppression** as
delivery.

**3. Fan-out amplifies everything.** One event ("celebrity posted") can notify millions. The system
must fan out massively *and* apply per-user preferences/caps/dedup to each of those millions —
cheaply. This is why per-user state and partitioning-by-recipient dominate the architecture.

---

## Scale Estimates

- 100M+ active users; billions of notifications/month ⇒ ~50k–100k sends/sec average, higher peaks.
- Per-user state (preferences, recent-send history, relevance scores) ~a few KB ⇒ 100s of GB hot
  state — **partitioned by user id.**
- Fan-out spikes: a single broadcast can enqueue tens of millions of per-user decisions in seconds.

**The asymmetry that drives the design:** the *ingest* is a firehose of events, but the *decision*
("should this specific user get this?") is inherently **per-user** — so the whole system is organized
around **partitioning by recipient**, which is exactly what both ATC and CCG do.

## Out of Scope

Actual device delivery to APNs/FCM/carriers (we hand off to those), and the content/ML models
themselves (we consume relevance scores; we don't train them here).

---

## Engineering Blogs & Primary Sources

This design is drawn directly from two production notification platforms that solved exactly this
problem at scale. They're complementary: **LinkedIn's ATC** is the definitive account of *member-first
orchestration* (dedup, capping, channel choice), and **Uber's CCG** is the definitive account of
*ML-optimized timing*.

- **LinkedIn — "Air Traffic Controller: Member-First Notifications at LinkedIn."**
  https://www.linkedin.com/blog/engineering/messaging-notifications/air-traffic-controller-member-first-notifications-at-linkedin
  The centralize-everything argument, made concrete. ATC unified all member communication under one
  "decision maker," which cut emails ~50% and complaints ~65%. Key design points this HLD builds on:
  **partition by member id** (so every member's context is co-located), **local RocksDB state on SSD**
  (~2 ms reads vs. 10–100 ms remote), holistic per-member selection (drop less-relevant
  notifications), and the "5 Rights" framing (right message, member, channel, time — and don't
  over-send). → backs **Design** (recipient partitioning, local state), **Deep Dives** (dedup/channel),
  and **Trade-offs** (centralize).

- **Apache Samza — ATC case study (implementation detail).**
  https://samza.apache.org/case-studies/linkedin
  The architectural breakdown ATC's own post summarizes: three components — **Partitioners**
  (hash by recipient, drop malformed), **Relevance processors** (ML models in RocksDB, score → pick
  channel: drop/email/push), **Pipeline processors** (dedup, frequency cap, and a **scheduler on local
  state** so a push isn't sent at midnight). Confirms this HLD's decision-tier design almost
  one-to-one. → backs **Design** and **Deep Dives**.

- **Uber — "How Uber Optimizes the Timing of Push Notifications using ML and Linear Programming."**
  https://www.uber.com/us/en/blog/how-uber-optimizes-push-notifications-using-ml/
  The timing deep-dive. Uber's **Consumer Communication Gateway (CCG)** handles billions of
  notifications/month with four components (**Persistor** → inbox in sharded MySQL docstore
  partitioned by user-UUID; **Schedule Generator** → ML + linear program; **Scheduler** → Cadence +
  per-hour Kafka topics; **Push Delivery** → last-mile checks). The core idea this HLD's Deep Dives
  tab is built on: schedule notifications as an **[assignment problem](https://en.wikipedia.org/wiki/Assignment_problem)
  solved by an integer linear program**, with an **XGBoost** model scoring `P(convert within 24h |
  push, time)` — the ILP notices expiring pushes, exploits time-varying scores, and drops the excess.
  → backs **Deep Dives** (timing/ILP), **Design** (four components), and **AI Evolution**.

**The through-line:** two independent teams, same architecture — **centralize all sends behind one
decision-maker, partition by recipient so per-user context is local, suppress aggressively (the
product is sending *less*), and treat timing/relevance as an ML-scored optimization.** LinkedIn proved
the orchestration half (fewer, better notifications); Uber proved the timing half (an ILP over
predicted conversion). Together they *are* this HLD.
