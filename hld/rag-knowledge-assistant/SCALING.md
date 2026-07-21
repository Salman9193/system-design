# RAG Knowledge Assistant — Scaling, Multi-Region & Cost

The operational-reality file: how the system scales, spans regions, controls its
(substantial) cost, and evolves. AI systems have a distinctive cost profile — LLM
inference dominates — so cost reasoning is especially load-bearing here.

---

## Multi-Region

### The concerns
- **Latency:** query embedding and retrieval should be regional; a cross-region hop
  (~150ms) eats the time-to-first-token budget.
- **Data residency:** enterprise/knowledge corpora often have residency requirements
  (EU data stays in the EU). This can *force* regional indexes rather than being just
  an optimization.
- **GPU availability:** inference GPUs are scarce and unevenly available across
  regions — capacity planning is regional and harder than for CPU services.

### The design
- **Replicate the vector index per region** (or partition by residency). It's derived
  data, so replication is a build-path concern.
- **Regional inference tiers** close to users for TTFT.
- **The build path can be centralized or regional**; embedding is region-agnostic
  compute, but residency rules may pin it.

> "Retrieval and inference are regional for latency and data residency — residency
> often *mandates* it for enterprise corpora. The index is derived data, so I
> replicate or partition it per region. The wrinkle versus a traditional system is
> GPU scarcity: inference capacity planning is regional and constrained, so I'd
> queue and tier models rather than assume elastic GPU autoscaling."

---

## Scaling Dimensions

### Scaling retrieval (corpus growth)
- Shard the vector index (see `embeddings-and-vector-search.md`); add shards as the
  corpus grows.
- Replicate shards for read QPS and availability.
- Quantize vectors (PQ) when memory becomes the binding constraint.

### Scaling generation (query growth) — the hard one
- The inference tier is **GPU-bound** and the dominant cost. Scale via more replicas,
  higher batch utilization (continuous batching — see
  `fundamentals/llm-inference-serving.md`), and model tiering.
- Caching is a scaling lever, not just a cost one: a high semantic-cache hit rate
  directly reduces the GPU fleet you need.

### Scaling ingestion
- The streaming pipeline scales independently (extract/chunk/embed are parallelizable
  per document). Embedding throughput is its own capacity question (batch inference on
  the embedding model).

---

## Cost Reasoning (the dominant concern)

For most RAG systems, **LLM inference is the largest cost by far** — so cost control
is a primary design activity, not an afterthought.

### Where the money goes
1. **Generation tokens** — the biggest line. Every query runs the LLM over (system
   prompt + retrieved context + history + question), then generates output.
2. **GPU fleet** — the inference tier's GPU-hours; utilization is the key lever.
3. **Embedding compute** — building and refreshing the index (offline, but non-
   trivial at corpus scale).
4. **Vector index memory** — RAM for the ANN index at scale.

### The levers (in rough order of impact)
- **Caching** — the cheapest token is the one you never generate. A permission-aware
  semantic cache with a good hit rate is the biggest single lever.
- **Context minimization** — rerank to few passages, trim/summarize history. Fewer
  input tokens per query, linearly cheaper.
- **Model tiering** — route easy queries to a small model; reserve the large model
  for hard ones. Most queries are easy.
- **GPU utilization** — continuous batching keeps GPUs busy; a poorly-batched fleet
  can cost several times more for the same work.
- **Quantization** — cheaper/faster inference and smaller index memory, at a small
  quality cost gated by eval.

> "LLM inference dominates cost, so I attack tokens and GPU utilization directly:
> a permission-aware semantic cache to skip generation, reranked tight context to
> cut input tokens, model tiering so easy queries use a cheap model, and continuous
> batching to keep GPUs utilized. I'd track cost-per-query as a first-class metric and
> gate any quality-for-cost trade (like quantization) on the eval suite."

---

## Evolution — How This Grows

1. **v1:** grounded, cited Q&A over a permissioned corpus; retrieve-then-generate;
   semantic cache; eval-gated deploys.
2. **+ Hybrid + rerank:** raise retrieval quality with lexical+vector fusion and a
   cross-encoder reranker.
3. **+ Model tiering:** a router (see the model-gateway LLD) to cut cost.
4. **+ Groundedness verification:** selective claim-checking for high-stakes queries.
5. **+ Agentic actions:** let the assistant *do* things (file a ticket, run a query),
   not just answer — which adds tool-use, function-calling, and a much larger safety
   surface (prompt injection becomes higher-stakes).
6. **+ Multi-modal:** retrieve and reason over images/tables/diagrams, not just text.
7. **+ Personalization & feedback loop:** the data flywheel — use thumbs/corrections
   to improve retrieval and refine prompts, so quality compounds.

Each step plugs into the retrieve-then-generate architecture without redesigning it —
a sign the core design is sound. Naming the agentic step's expanded safety surface
unprompted is a strong staff signal, because it shows you understand that giving an
AI system *actions* changes the risk profile fundamentally.

---

## The Cost/Quality/Latency Triangle

The through-line for scaling a RAG system: you are always balancing **quality**
(retrieval + grounding), **cost** (tokens + GPUs), and **latency** (TTFT). Nearly
every lever trades among these three, and the eval harness is what lets you make those
trades deliberately instead of blindly — measure the quality impact of every
cost-saving move before shipping it.

---

## The Metadata Database

Vector search gets the attention, but a RAG system's **metadata store** (documents, chunks,
permissions, versions, ingestion state) is an ordinary database with ordinary scaling limits — and
it's usually what breaks first.

| Concern | Pattern | Reference |
|---------|---------|-----------|
| Chunk metadata grows with the corpus | shard by `document_id` or `tenant_id` | [Database Scaling](#fu-database-scaling) |
| Permission checks on every query | **must be strongly consistent** — primary reads, not replicas | [Scaling Ladder](#fu-database-scaling) |
| Ingestion state machine | keep a job's rows on one shard so it stays transactional | [Sharded Database Platform](#hld-sharded-database-platform) |
| Re-embedding the corpus | copy → verify → switch to a new index version | [Sharded Database Platform](#hld-sharded-database-platform) |

**The permissions row is the one with teeth.** Serving a stale replica read for an access check means
showing a user a document they've just been removed from. **Freshness requirements are per-query, and
authorisation is always on the strict side of that line.**
