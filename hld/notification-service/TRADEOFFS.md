# Notification Service — Trade-offs

## 1. One Pipeline vs. Two Lanes (transactional / marketing)

| | Single pipeline | **Two lanes (chosen)** |
|---|-----------------|------------------------|
| Simplicity | higher | lower |
| OTP latency | risks delay | **immediate** |
| Marketing optimization | compromised | full (score/schedule) |
| Failure isolation | shared blast radius | **isolated** |

**Chosen: two lanes.** Transactional and marketing have opposite requirements (never-drop-now vs.
droppable-optimized-later); one pipeline can't serve both without failing one. The cost is running
two paths — worth it, because a delayed OTP or a spammed user are both product-killers.

---

## 2. Centralized Decision-Maker vs. Each Team Sends Its Own

| | Each team sends | **Central platform (chosen)** |
|---|-----------------|-------------------------------|
| Team autonomy | high | lower |
| Global frequency cap | **impossible** | enforced |
| Dedup across teams | **impossible** | enforced |
| Consistency | none | one policy |

**Chosen: centralize — one "decision maker" for all notifications.** This is *the* lesson of both
ATC and CCG: uncoordinated teams each optimizing their own sends collectively spam the user. Only a
central platform can enforce a *global* per-user budget and dedup across sources. LinkedIn's move to
ATC cut complaints 65%; Uber built CCG because 15+ hours/week/team was going into manual conflict
management. **The whole value proposition is global coordination.**

---

## 3. Immediate vs. Scheduled (ML-optimized timing)

| | Send immediately | **ML-scheduled (chosen for marketing)** |
|---|------------------|------------------------------------------|
| Latency | lowest | deferred |
| Relevance/conversion | lower | **higher** |
| Complexity | trivial | ILP + ML models |
| Right for | transactional | marketing/engagement |

**Chosen: immediate for transactional, ML-scheduled for marketing.** The timing optimization
(assignment-problem ILP over predicted conversion) is pure upside for deferrable notifications and
pure overhead for urgent ones — so it's applied per lane, not globally.

---

## 4. Partition by Recipient vs. by Notification

**Chosen: by recipient (user id).** Every per-user decision (preferences, caps, dedup, history) needs
*all of that user's context in one place*. Partitioning by user co-locates it, making decisions
**local reads (~ms)** instead of remote calls (10–100ms). Partitioning by notification would scatter
a user's context and force a remote fetch per decision — fatal at 100k/sec. This single choice is why
both anchor systems are recipient-partitioned.

---

## 5. Fan-Out on Write vs. on Read

**Chosen: hybrid.** Fan-out-on-write (push into each recipient's inbox) gives fast reads but explodes
for huge-fan-out sources; on-read is cheap to write but slow to view. Fan out on write for the common
case, compute on read for celebrity-scale sources — the standard feed compromise.

---

## 6. Build vs. Buy

| | Build | Buy (SNS, OneSignal, Braze, Courier…) |
|---|-------|----------------------------------------|
| Control over ranking/timing | **total** | limited |
| Time to value | quarters | **days** |
| Per-user intelligence | yours to build | vendor's |
| Cost at scale | headcount | per-message $$ |

**Chosen (honestly): buy the delivery, build the intelligence — if scale justifies it.** Device
delivery to APNs/FCM/carriers is undifferentiated; use a provider. The **decision layer** (dedup,
capping, relevance, timing) is where the product value is, and worth building **only at the scale
where fatigue and conversion move real numbers** — which is exactly when LinkedIn and Uber built
theirs. Below that scale, a SaaS platform is the right call, and saying so is a strength.
