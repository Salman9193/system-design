# Sharded Database Platform — AI Evolution

## Where the Real Opportunity Is

Not in the query path. Sharded query routing is a **latency-critical, correctness-critical,
deterministic** operation — precisely the wrong place for a probabilistic model. A router that
"usually" picks the right shard is a data-corruption engine.

**The opportunity is in the control plane**, where decisions are infrequent, high-stakes, currently
made by scarce human experts, and — crucially — **reviewable before they take effect**.

> **The principle this section keeps returning to: put the model where non-determinism is acceptable
> (offline, advisory, human-approved) and keep the serving path a pure, deterministic function.**
> The same rule applied in the [Text Segmentation Service](#hld-text-segmentation-service).

---

## 1. Shard Key Recommendation

Today, choosing a shard key is expert judgement over a query log — and getting it wrong is a one-way
door.

A model can do what's tedious for humans: **analyse the whole query corpus**, cluster access patterns,
and simulate candidate keys against real traffic — *"87% of your queries filter on `tenant_id`;
sharding by `user_id` would make 61% of traffic scatter-gather."*

**This is advisory, and it should stay advisory** — but it turns a gut call into a quantified one.
The simulation is the valuable part; the model is just what makes it cheap.

---

## 2. Predictive Resharding

Resharding takes hours and is painful under load, so it should start **before** saturation. That's a
forecasting problem: per-shard growth in QPS, data size, and working set, with seasonality.

Realistic near-term: the platform *proposes* — "shard 3 will exceed capacity in ~11 days; split into
2?" — and a human approves. **Fully autonomous resharding is technically possible and operationally
terrifying**; the copy-verify-switch protocol is safe, but an unnecessary reshard still burns days of
IO and risk.

---

## 3. Anomaly Detection & Query Regression

The natural fit, because it's **detection, not action**:
- Query plans that changed shape after a deploy (single-shard → scatter).
- Per-shard load skew emerging before it's an incident.
- Replication lag patterns that precede cascades.
- **Scatter-query ratio drift** — the leading indicator from Failure Modes.

Baselines here are seasonal and multi-dimensional, which is exactly where statistical/ML detection
beats static thresholds.

---

## 4. Autonomous Operations — the Honest Assessment

| Operation | Automate fully? | Why |
|-----------|-----------------|-----|
| Failover | **already automated** | must be faster than humans; the protocol is provably safe |
| Backup/restore | **yes** | idempotent, verifiable |
| Adding replicas | **yes** | low risk, reversible |
| Resharding | **propose, human approves** | expensive, hard to reverse |
| Shard key choice | **advise only** | one-way door |
| Schema change | **propose, human approves** | semantics matter |

**The pattern: automate what's reversible and verifiable; keep a human on what's a one-way door.**
That line — not the sophistication of the model — is the actual engineering judgement.

---

## 5. What LLMs Change (and Don't)

**Do:** natural-language interfaces to topology state ("which shards are lagging?"), explaining
incidents from logs and metrics, generating migration plans for review, and translating a slow query
into a concrete indexing or shard-key recommendation.

**Don't:** anything in the write path. **An LLM cannot be part of a durability guarantee.** The
zero-write-loss requirement is met by leases, fencing, and quorum — mechanisms whose correctness you
can *prove*, not predict.

---

## 6. The Broader Trend: Serverless & Elastic

The direction of travel is that **sharding becomes invisible** — DynamoDB, Spanner, Aurora Limitless,
and Vitess-based managed offerings all hide it behind an API that just absorbs load.

**But the concepts don't disappear; they become the thing you're paying someone else to do.** You
still choose a partition key. You still get hot partitions. You still cannot have a cheap cross-shard
transaction. Understanding this design is how you reason about the managed product's limits — and how
you recognise, from the outside, which of these architectures you've actually bought.
