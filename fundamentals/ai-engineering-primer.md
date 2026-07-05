# AI Engineering Primer — From Traditional Software to AI Systems

AI engineering is the discipline of building **production systems that include a
machine-learning or LLM component**. It sits on top of everything in the rest of
`fundamentals/` — a RAG system still needs caching, sharding, and load balancing —
but it adds a probabilistic component that behaves unlike any traditional
dependency. This primer is the on-ramp; the other AI fundamentals
(`llm-inference-serving.md`, `embeddings-and-vector-search.md`) open specific boxes.

---

## What Makes AI Engineering Different

A traditional service transforms input to output through code you wrote and can
reason about line by line. An AI system routes some of that transformation through a
**model** — a learned function you did not write, can't fully explain, and which
returns a *probable* answer rather than a *correct* one. Everything below follows
from that.

### Deterministic vs probabilistic
- **Traditional:** `f(x)` always returns the same `y`. You test with
  `assert f(2) == 4`.
- **AI:** `model(x)` returns a sample from a distribution. `assert` doesn't apply;
  you **evaluate** over many examples and measure a score.

This is the root difference. Testing, debugging, versioning, and improvement all
change because of it.

---

## The Four Versioned Artifacts

In traditional software, you version **code**. In an AI system you version **four**
things, and any one changing can change behavior:

1. **Code** — the application logic around the model.
2. **Model** — weights, version, provider. A model upgrade can silently shift
   behavior.
3. **Prompt** — the instructions/template. A prompt is behavior-defining code that
   happens to be English; it must be version-controlled and tested.
4. **Data** — the retrieval corpus, the fine-tuning set, the few-shot examples.
   Change the data, change the outputs.

A staff-level answer treats prompts and data as first-class, versioned, tested
artifacts — not as strings someone edits in a config.

---

## Evaluation Replaces Unit Testing

You cannot write `assert answer == "correct"` for a generative system. Instead you
build an **eval harness**:

- **A dataset** of representative inputs, ideally with reference outputs or rubrics.
- **Metrics** appropriate to the task:
  - *Exact/structured:* exact-match, JSON-schema-valid, contains-required-field.
  - *Semantic:* embedding similarity to a reference answer.
  - *LLM-as-judge:* another model scores the output against a rubric (cheap, scalable,
    noisy — calibrate against human labels).
  - *Human review:* the gold standard for high-stakes; expensive, so sampled.
- **A regression gate:** before shipping a new model/prompt, run the eval suite and
  block if quality drops. This is CI for AI.

> The staff move: "I'd gate every prompt or model change on an eval suite with a
> held-out set, tracking task-specific metrics plus a hallucination/groundedness
> score, so we can't silently regress quality. Offline eval catches the obvious
> breaks; I'd confirm with an online A/B on a small traffic slice."

---

## Tokens: The New Unit of Cost and Latency

LLMs process and produce **tokens** (~¾ of a word each). Tokens are simultaneously:

- **The cost unit.** Pricing is per input token + per output token. LLM inference is
  frequently the dominant line item, often 10–100× a traditional request.
- **The latency unit.** Output is generated one token at a time, so latency grows
  with output length. The relevant metrics are **time-to-first-token (TTFT)** and
  **tokens-per-second (throughput)**, not a single round-trip number.
- **The capacity unit.** Context windows are bounded in tokens; you budget input
  tokens (prompt + retrieved context + history) against that limit.

Capacity estimation for an AI feature therefore includes:
```
cost/day  = requests/day × (input_tokens × price_in + output_tokens × price_out)
latency   ≈ TTFT + output_tokens / tokens_per_second
```
Controlling tokens — caching, retrieving less context, using a smaller model for
easy queries, truncating history — is a core design activity, not an afterthought.

---

## Hallucination Is the Default, Not an Edge Case

A generative model will produce fluent, confident, **wrong** output when it doesn't
know — that's its default behavior, because it's sampling plausible text, not
looking up facts. Traditional systems fail loudly (errors, timeouts); AI systems
fail **silently and convincingly**. Mitigations:

- **Grounding (RAG):** give the model retrieved facts and instruct it to answer only
  from them. Turns "recall from weights" into "read from context."
- **Attribution:** require citations so answers are checkable.
- **Output validation:** schema checks, verifier models, business-rule guardrails.
- **Confidence & abstention:** let the system say "I don't know" rather than
  fabricate.
- **Human-in-the-loop:** for high-stakes outputs, the model drafts and a human
  approves.

---

## The Training/Serving Split (Offline vs Online)

Like the batch/serving split in traditional systems, AI systems have two paths:

- **Offline (build path):** train or fine-tune models, generate embeddings, build
  vector indexes, run evals. Throughput-oriented, latency-insensitive, expensive but
  periodic.
- **Online (serving path):** run inference on live requests. Latency-critical,
  cost-sensitive per request, must be highly available.

Keeping them separate — and knowing which concerns live where — is the same
architectural discipline as separating the ranking-build path from the serving path
in the search-typeahead design.

---

## RAG vs Fine-Tuning (the choice you'll be asked to defend)

Two ways to make a model "know" your domain:

| | RAG (retrieve + generate) | Fine-tuning |
|--|---------------------------|-------------|
| Mechanism | Fetch relevant docs, put them in the prompt | Adjust model weights on your data |
| Freshness | **Live** — update the corpus, done | Stale — needs retraining |
| Attribution | Natural — cite retrieved sources | Hard — knowledge is baked in |
| Cost to update | Cheap — re-index | Expensive — retrain |
| Best for | Factual, changing, citable knowledge | Style, format, behavior, narrow domains |
| Failure mode | Retrieves wrong context → wrong answer | Forgets/overfits; hallucinates confidently |

The staff answer is usually **"RAG first, fine-tune for behavior"**: RAG for
knowledge that must be fresh and citable, fine-tuning (or few-shot) for how the model
should *behave*. And often both.

---

## The Data Flywheel

Traditional systems improve when you ship code. AI systems improve when you **close
the loop**:
```
serve → capture signal (thumbs, corrections, click-through) → curate/label
      → retrain / refine prompt / improve retrieval → serve better → repeat
```
Designing the capture step — what signal, how it's stored, how it feeds back — is
what makes the system compound over time. A design that includes the flywheel is
thinking about the system's life, not just its launch.

---

## The Checklist for Any AI Feature

1. **Should this use ML at all?** Sometimes a rule beats a model. Say so.
2. **RAG, fine-tune, or prompt?** Defend the choice.
3. **How is quality measured?** Eval harness + regression gate.
4. **What's the token cost and latency?** Estimate it; control it.
5. **How does it hallucinate, and how do you guard against it?**
6. **What's the offline vs online split?**
7. **How does it improve over time?** The flywheel.

Answering these unprompted is the difference between "used an LLM" and "engineered
an AI system."
