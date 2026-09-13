# Notification Service — Design

## The Shape: a Pipeline Organized Around the Recipient

```
   senders (product, marketing, ops)
        │  publish notification requests
        ▼
   ┌──────────────┐   append to a durable log, keyed by recipient
   │  INGEST API  │──► Kafka (partitioned by user id)
   └──────────────┘
        ▼
   ┌───────────────────────────────────────────────┐
   │  DECISION / ORCHESTRATION (per-user)           │
   │   • preferences & quiet hours   • dedup/aggregate
   │   • frequency caps              • relevance scoring
   │   • channel selection           • TIMING (when to send)
   │   local per-user state (RocksDB / sharded store)│
   └───────────────┬───────────────────────────────┘
        ▼ (push, time) decisions
   ┌──────────────┐   distributed cron; fires at the chosen time
   │  SCHEDULER   │──► buffers per time-slot (Kafka topic per hour)
   └──────────────┘
        ▼
   ┌──────────────┐   last-mile checks, then hand to providers
   │  DELIVERY    │──► APNs / FCM / email / SMS / in-app inbox
   └──────────────┘
        ▼ delivery/open/click events ──► back into relevance
```

**The one structural idea:** *partition everything by recipient.* All requests, signals,
preferences, and history for a given user land on the **same processing host**, so the
"should this user get this, on which channel, when?" decision is made from **local state** — no
fan-out of remote calls per notification. Both anchor systems do exactly this (ATC partitions Kafka
by member id; CCG partitions the inbox by user-UUID).

---

## Two Lanes: Transactional vs. Marketing

The first split. They share delivery but nothing else:

| | **Transactional** | **Marketing / engagement** |
|---|-------------------|----------------------------|
| Examples | OTP, security, "order shipped" | "try this", "people you may know" |
| Path | **fast lane** — minimal checks, immediate | **smart lane** — dedup, score, schedule |
| Dropping | **never** | routine (suppress low-value) |
| Timing | now | optimized (quiet hours, best time) |
| Delivery guarantee | at-least-once, tracked | best-effort |

**Route at ingest.** A transactional OTP must not sit in a scheduler waiting for the "optimal" time —
it bypasses the smart lane entirely. Conflating the two is the classic design mistake.

---

## Component 1 — Ingest API

A single entry point (gRPC/REST) for all senders. It:
- validates and drops malformed requests early (cheap filtering before they cost anything);
- stamps category, priority, expiry, and dedup key;
- appends to **Kafka, partitioned by recipient user id** — durable buffer + natural per-user ordering.

Durability here is what makes the rest **at-least-once**: once it's in the log, a crash downstream
replays rather than loses.

## Component 2 — Decision / Orchestration (the brain)

This is where a notification platform earns its keep. Per user, from **local state**, it decides
*whether, what, where, and when*:

- **Preferences & quiet hours** — opt-outs, per-category settings, "no push at midnight."
- **Deduplication & aggregation** — collapse duplicates and related events into one message
  ("3 people liked your post"). Needs a short window of per-user recent-notification state.
- **Frequency capping** — enforce "≤ N/day per user/category" (a per-user
  [rate limiter](#hld-distributed-rate-limiter) — the same problem, keyed by user).
- **Relevance scoring** — consume ML scores (pushed from offline jobs) to rank/drop low-value
  notifications and pick the channel (drop vs. in-app vs. push vs. email).
- **Timing** — decide *when* (see Deep Dives; this is where Uber's linear-programming scheduler lives).

**Why local state matters:** reads to a local store (RocksDB on SSD) take ~a couple ms; the same data
via remote calls is 10–100 ms. At 100k decisions/sec, that difference is the system working or
melting. Partitioning by user id is what makes the state local.

## Component 3 — Scheduler

A **distributed cron** that fires a chosen push at its chosen time, at tens of thousands of
triggers/sec. Pattern (from CCG): bucket (push, time) assignments into a **Kafka topic per time
slot** (e.g., per hour of the horizon), and a workflow engine (Cadence-style) turns consumption of
each slot's topic on when that time arrives. Must be **idempotent** and support **rescheduling** (a
newer notification can change an earlier one's plan).

## Component 4 — Delivery

Does **last-mile checks** (is this still valid? is the store still open? did the user just open the
app, making this redundant?), then hands to the channel providers (APNs, FCM, email, SMS) or writes
to the **in-app inbox**. Runs **async behind a Kafka buffer** for smooth load and retries. Emits
delivery/open/click events that flow back into relevance.

---

## Data Model (per-user, co-located)

- **inbox / buffer** — pending notifications for a user, partitioned by user id (CCG uses sharded
  MySQL docstore; ATC uses Samza local state).
- **preferences** — per-category channel + frequency settings, quiet hours, time zone.
- **recent-send history** — for dedup and frequency capping (short TTL).
- **relevance scores / ML models** — pushed from offline training, read locally at decision time.

Everything is keyed by user id so one user's whole decision context lives on one host.
