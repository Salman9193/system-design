# System Design Prep — Staff Engineer

A structured preparation repo for the **system design rounds** of a Staff Engineer
loop. Covers both **HLD** (high-level / distributed systems design)
and **LLD** (low-level / object & API design), organized around what interviewers
actually evaluate at the staff level.

> Companion to the [DSA repo](https://github.com/Salman9193/dsa-problems). Where
> that repo drills algorithms, this one drills design judgment.

---

## Why This Repo Is Structured This Way

At the staff level, system design is **two rounds and carries the most weight** in the loop. The
most common reason for downleveling to senior is giving a senior-quality design answer —
technically correct, but missing the failure-mode reasoning, operational ownership,
cost analysis, and proactive trade-off discussion that define staff level.

So this repo is **not** a collection of memorized architectures. Each design is
broken into the exact dimensions interviewers score separately:

- **Requirements** — scoping and clarifying (10–15% of the rubric)
- **Design** — the high-level architecture (20–25%)
- **Deep Dives** — going inside 2–3 components (25–30%, the senior/staff boundary)
- **Trade-offs** — what you gave up and why (part of the 15–20%)
- **Failure Modes** — what breaks, how you detect and recover
- **Scaling** — multi-region, cost, evolution

---

## Repo Structure

```
system-design/
├── frameworks/          The approach — read these first
│   ├── HLD_FRAMEWORK.md      45-min time-boxed structure
│   ├── LLD_FRAMEWORK.md      object/API design approach
│   └── STAFF_VS_SENIOR.md    what makes an answer staff, not senior
│
├── fundamentals/        The "inside the tools" knowledge interviewers probe
│   ├── capacity-estimation.md
│   ├── caching.md
│   └── ...              (more added over time)
│
├── hld/                 Full system designs (one dir each)
│   └── search-typeahead/    ← complete template
│       ├── REQUIREMENTS.md
│       ├── DESIGN.md
│       ├── DEEP_DIVES.md
│       ├── TRADEOFFS.md
│       ├── FAILURE_MODES.md
│       └── SCALING.md
│
├── lld/                 Object & API design (one dir each)
│   └── rate-limiter/         ← complete template
│       ├── PROBLEM.md
│       ├── DESIGN.md
│       ├── Solution.java
│       └── NOTES.md
│
├── roadmap/             Prep progression
├── scripts/             GitHub Pages site generator
└── .github/workflows/   Build + deploy
```

---

## How to Use This Repo

1. **Start with `frameworks/`.** The HLD and LLD frameworks give you the time-boxed
   structure; `STAFF_VS_SENIOR.md` is the most important file — it defines the bar.
2. **Work `fundamentals/` for depth.** These are the black boxes interviewers push
   you to open ("don't just name Kafka — how does it guarantee ordering?").
3. **Study the HLD/LLD templates** as models for how to think, not scripts to
   memorize. interviewers ask original or modified prompts, so pattern mastery beats recall.
4. **Practice out loud, time-boxed.** The rounds are ~45 minutes; the framework's
   time budget is what you're graded against.

---

## The Staff-Level Bar (in one line)

A senior engineer produces a correct design when asked. A staff engineer **owns the system end-to-end** —
surfacing failure modes, cost, multi-region, and trade-offs proactively, and driving
the conversation rather than waiting for follow-ups. Every file in this repo is
written to that bar.

---

## Roadmap

**Complete:**
- ✅ Frameworks (HLD, LLD, Staff vs Senior)
- ✅ Fundamentals: capacity estimation, caching
- ✅ HLD template: Search Typeahead / Autocomplete
- ✅ LLD template: Rate Limiter

**Queued (HLD):** YouTube, Maps, cloud file storage, URL shortener, Twitter
timeline, chat/messaging, distributed rate limiter.

**Queued (fundamentals):** sharding & partitioning, replication & consensus,
consistency models, message queues, load balancing, databases, observability.

**Queued (LLD):** parking lot, notification service, elevator system, in-memory
key-value store.

---

> Last updated: 2026-07-05 — initial build: frameworks, fundamentals, HLD + LLD templates
