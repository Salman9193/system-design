# LLM Inference Serving Platform — Scaling, Multi-Region & Cost

The operational-reality file. This platform's economics are unusual — GPUs are scarce,
expensive, and slow to provision — so scaling and cost reasoning are tightly coupled
and especially load-bearing.

---

## Multi-Region

### The concerns
- **GPU scarcity is regional and uneven.** Unlike CPU, you can't assume abundant GPU
  capacity in every region — availability constrains where you can serve.
- **Latency:** inference should be near users for TTFT, but GPU availability may force
  routing to a region with capacity.
- **Data residency:** prompts may contain regulated data, pinning inference to a
  region.

### The design
- **Regional inference clusters** where GPUs are available, routed by proximity *and*
  capacity — sometimes trading a little latency for available capacity.
- **Model artifacts replicated** to each region's registry so any region can load any
  model.
- **Global control plane** (registry, placement, autoscaling policy) coordinating
  regional fleets.

> "GPU scarcity makes this different from a stateless service: I can't assume every
> region has spare GPUs, so routing balances proximity against where capacity actually
> exists. Residency requirements can pin inference to a region regardless of capacity,
> so the control plane has to reason about both at once."

---

## Scaling Dimensions

### Scaling throughput
- **More replicas** per hot model; **continuous batching** and **paged KV** to
  maximize each replica's tokens/sec (the per-unit efficiency that determines how many
  replicas you need).
- **Bigger models** that exceed one GPU use **model parallelism** (tensor/pipeline) to
  split across GPUs — adding inter-GPU communication cost.

### Scaling model count
- **Placement + fine-tune multiplexing** (see deep dives) to serve many models/variants
  on a bounded fleet without loading everything everywhere.

### The autoscaling wrinkle
- GPU autoscaling is **slow and coarse** (cold starts, scarce/expensive instances), so
  it must be **predictive** rather than reactive — scale ahead of forecast demand, keep
  warm buffers, and use queueing/load-shedding to absorb the gap during scale-up.

---

## Cost Reasoning (dominant and unusual)

GPU-hours dominate, and GPUs are among the most expensive compute you can rent — so
cost efficiency *is* the design goal.

### The levers (in rough order of impact)
- **Utilization** — the biggest lever. Continuous batching + paged KV turn a GPU that
  might serve one request at a time into one serving dozens. A poorly-utilized GPU
  fleet can cost several times more for the same work.
- **Right-sizing models** — model tiering (serve the smallest model that meets the
  quality bar per request); quantization to fit models on cheaper/fewer GPUs at a small
  quality cost.
- **Fine-tune multiplexing** — dozens of variants on shared base weights instead of a
  replica each.
- **Batch backfill** — offline/batch work fills spare interactive capacity so you pay
  for GPUs you're already renting rather than a separate fleet.
- **Warm-pool discipline** — enough warm capacity for latency, not so much that idle
  GPUs burn money.
- **Speculative decoding** — more tokens/sec per GPU on latency paths.

> "Because GPU-hours dominate, utilization is the whole game. Continuous batching and
> paged KV are the primary cost levers — they multiply how much work each rented GPU
> does. On top of that: tier and quantize models to the smallest that passes eval,
> multiplex fine-tunes over shared weights, and backfill spare capacity with batch
> work. I'd track $/token and GPU utilization as the headline cost metrics."

---

## Evolution — How This Grows

1. **v1:** single-region fleet, continuous batching, paged KV, router + registry,
   token-based multi-tenancy.
2. **+ Fine-tune multiplexing:** serve many adapters over shared base weights.
3. **+ Speculative decoding:** cut decode latency on interactive paths.
4. **+ Model parallelism:** serve models too large for one GPU.
5. **+ Multi-region:** regional clusters with capacity/residency-aware routing.
6. **+ Heterogeneous hardware:** route by hardware class (cheaper accelerators for
   small models, top-end for large), optimizing $/token per workload.
7. **+ Disaggregated prefill/decode:** run the compute-heavy prefill and the memory-
   bound decode on separate optimized pools — an advanced efficiency step worth naming
   as a direction.

Each step deepens the same core (scheduler + KV memory + placement) rather than
replacing it — evidence the original design was sound.

---

## The Through-Line

Everything about scaling this platform reduces to one sentence: **make each scarce,
expensive GPU do as much useful token-generation as possible while meeting latency
SLAs.** Utilization is the lever, KV memory is the ceiling, cold start is the
constraint on elasticity, and $/token is the score. Keeping that single frame is what
makes the design read as staff-level rather than a list of techniques.
