# Notification Service — Failure Modes

The defining risk of a notification platform isn't an outage — it's **eroding user trust.** A system
that silently over-sends does more lasting damage than one that's briefly down, because users who
disable notifications never come back. Failures are ranked with that in mind.

---

## 1. The Spam Cascade — *the reputation-killer*

**How:** dedup fails, or a frequency cap is bypassed, or a retry loop re-sends — and users get
duplicate or excessive notifications. **Consequence:** mass opt-outs and app-notification disabling,
which is often **irreversible** — the exact failure ATC and CCG were built to prevent.

**Prevention:**
- **Idempotency/dedup keys** on every notification; a repeat within the window is dropped.
- **Frequency caps as a hard budget**, enforced in the per-user partition (local, atomic).
- **A global kill-switch** per category/campaign to stop a misfiring sender instantly.
- **Alert on per-user send-rate**, not just system throughput — a user getting 20 pushes/hour is
  invisible in aggregate metrics and glaring in a per-user distribution.

---

## 2. Dropping a Critical Notification (the mirror)

**How:** an OTP or security alert is lost — routed through the smart lane and dropped as "low value,"
or lost to a crash before the durable log.

**Prevention:** the **transactional fast lane never drops** and bypasses relevance/scheduling
entirely; **persist at ingest** so a downstream crash replays; **at-least-once with acks**. Losing a
2FA code locks a user out — treat transactional loss as a Sev-1, separate from marketing.

---

## 3. Provider Throttling / Deliverability Collapse

**How:** you exceed APNs/FCM rate limits or trash your email sender reputation with a bad blast →
providers throttle or blocklist you, and *legitimate* notifications stop arriving.

**Prevention:** **rate-limit outbound per provider** (their limits, not yours); warm up email
sending-domains; monitor bounce/spam-complaint rates and back off; isolate marketing blasts so they
can't burn the reputation transactional email depends on.

---

## 4. Fan-Out Overload

**How:** a broadcast to millions enqueues tens of millions of per-user decisions in seconds and
swamps the decision tier — or a celebrity event fans out on write to millions of inboxes at once.

**Prevention:** **Kafka absorbs the spike**, decision tier drains at its own rate (backpressure);
**transactional traffic on a separate topic/priority** so a broadcast can't delay an OTP; hybrid
fan-out for huge-fan-out sources.

---

## 5. Stale / Expired Notifications

**How:** the scheduler or a backlog delays a time-sensitive push ("driver arriving," a flash promo)
so it arrives *after* it's useful — actively worse than not sending.

**Prevention:** **expiry timestamps** checked at delivery; drop rather than send stale; last-mile
relevance checks ("did the user just open the app?" "is the store still open?").

---

## 6. The Decision Store Goes Down

**How:** the per-user state store (RocksDB/sharded DB holding preferences, caps, history) is
unavailable.

**Prevention & posture:** local state on the processing host (host-affinity) means a host failure
loses only its partition, not the fleet; **fail safe = suppress marketing, still deliver
transactional** on a degraded path (better to skip a "try this" than block an OTP because preferences
are unreadable); rebuild per-user state from the durable log on recovery.

---

## Degradation Ladder

```
1. Normal              → full decisioning: dedup, cap, score, schedule
2. Relevance ML down   → skip scoring; deliver on simple rules (caps + quiet hours still on)
3. Decision store slow → transactional still flows; marketing paused (fail safe)
4. A partition down    → that user-range degraded; everyone else unaffected
5. Provider throttled  → back off that channel; escalate to a backup channel where possible
6. Overload            → shed MARKETING first, protect transactional always
```

**Step 6 is the whole philosophy:** when you must shed load, drop the droppable (marketing) and never
the critical (transactional). The lanes exist precisely so this is possible.

---

## What to Alert On

- **Per-user send-rate distribution** — the only real detector of the spam cascade (aggregate looks
  fine while individuals get hammered).
- **Opt-out / notification-disable rate** — the leading indicator of trust erosion; a spike means the
  system is over-sending *now*.
- **Transactional delivery success & latency** — separately from marketing; an OTP miss is Sev-1.
- **Provider bounce/complaint/throttle rates** — deliverability health.
- **Decision-tier lag** behind the ingest log — how far behind real-time the pipeline is running.
