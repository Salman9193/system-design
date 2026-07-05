# Embeddings & Vector Search — Inside the Box

When you say "I'll use a vector database," the follow-up is "how does similarity
search actually work, and what does it cost?" This box has two halves: **embeddings**
(turning data into vectors that capture meaning) and **approximate nearest-neighbor
(ANN) search** (finding similar vectors fast). Both underpin RAG, semantic search,
semantic caching, and recommendations.

---

## Embeddings — Meaning as Geometry

An **embedding** is a fixed-length vector (e.g. 768 or 1536 dimensions) produced by a
model, such that semantically similar inputs land close together in vector space and
dissimilar ones land far apart. "How do I reset my password" and "I forgot my login"
map to nearby vectors even though they share almost no words.

- **Why it matters:** it turns "find semantically similar text" into "find nearby
  vectors" — a geometric problem with fast algorithms. This is what keyword search
  cannot do: keyword search matches tokens, embeddings match *meaning*.
- **Similarity metric:** usually **cosine similarity** (angle between vectors) or dot
  product. Close vectors = similar meaning.
- **The embedding model is a versioned artifact.** If you change it, every stored
  vector must be **re-embedded** — old and new vectors aren't comparable. This is a
  real operational constraint (see failure modes).

---

## The Search Problem: Exact Is Too Slow

Given a query vector, you want the *k* most similar vectors out of millions or
billions. Exact nearest-neighbor means comparing the query to every stored vector —
O(N) per query, far too slow at scale. So production systems use **Approximate
Nearest Neighbor (ANN)**: trade a little recall (you might miss a few true neighbors)
for a massive speed-up (sub-linear search).

The core trade-off to name: **recall vs latency vs memory**. Higher recall costs more
time and/or memory. The right operating point depends on the application — a
recommendation feed tolerates lower recall than a legal-document retriever.

---

## The Main ANN Index Types

| Index | Idea | Strength | Trade-off |
|-------|------|----------|-----------|
| **Flat (brute force)** | Compare to everything | Exact, simple | O(N) — only for small sets |
| **IVF** (inverted file) | Cluster vectors; search only the nearest clusters | Fast, tunable | Misses neighbors near cluster borders |
| **HNSW** (graph) | Navigable small-world graph; greedy-walk to neighbors | Excellent recall/latency; the common default | High memory; slower to build |
| **PQ** (product quantization) | Compress vectors into compact codes | Huge memory savings | Approximate distances; some recall loss |

Real systems often **combine** them (e.g. IVF + PQ for memory-efficient scale, or
HNSW for quality). The two dials you'll discuss:
- **HNSW:** `M` (graph connectivity) and `efSearch` (how much of the graph to
  explore) — higher = better recall, more latency.
- **IVF:** `nprobe` (how many clusters to search) — higher = better recall, more
  latency.

> The staff move: "I'd default to HNSW for its recall/latency curve, and reach for
> product quantization if memory becomes the constraint at scale — accepting a small
> recall hit. I'd tune `efSearch`/`nprobe` against a labeled retrieval set to hit our
> recall target at the latency budget, rather than guessing."

---

## Building and Serving the Index

- **Offline (build path):** embed the corpus (batch inference over the embedding
  model), build the ANN index, persist it. Expensive, periodic — the same offline
  build pattern as other systems.
- **Online (serving path):** embed the query (one fast inference), search the index
  for top-*k*, return. Latency-critical.

### Metadata filtering
Real retrieval isn't pure vector search — you filter by metadata too ("only docs
this user can access," "only from the last year"). Combining a filter with ANN is
non-trivial: pre-filtering can shrink the candidate set below *k*; post-filtering can
throw away most of what you retrieved. Naming this **filtered-ANN** challenge is a
depth signal.

### Sharding
At billions of vectors, the index is sharded. You can shard **randomly** (query all
shards, merge top-*k* — high fan-out) or **by cluster/metadata** (route to relevant
shards — routing complexity, skew). Same sharding trade-offs as any large index.

---

## Chunking (the quiet make-or-break for RAG)

Before embedding documents you must **chunk** them — split into passages. This is
deceptively important:

- **Too large:** a chunk covers many topics, so its single embedding is muddy and
  retrieval is imprecise; you also waste prompt tokens.
- **Too small:** a chunk lacks context to be meaningful on its own.
- **Techniques:** fixed-size windows, sentence/paragraph boundaries, **overlap**
  between chunks so a concept split across a boundary isn't lost, and
  structure-aware chunking (by heading/section).

Retrieval quality often depends more on chunking than on the fancy index. Raising
this shows you've actually built RAG, not just read about it.

---

## Hybrid Search (the production reality)

Pure vector search misses exact matches (product codes, names, rare terms) that
keyword search nails; pure keyword search misses semantic matches. Production systems
use **hybrid retrieval**: run both lexical (e.g. BM25) and vector search, then
combine the rankings (e.g. reciprocal rank fusion), often followed by a **reranker**
model that scores the merged candidates for final ordering.

> "I'd use hybrid retrieval — vector for semantic recall, lexical for exact terms —
> fuse the results, then rerank the top candidates with a cross-encoder for
> precision. Pure vector search alone tends to miss exact identifiers, which
> frustrates users."

---

## Capacity & Cost

- **Memory** is usually the binding constraint. A billion 768-dim float32 vectors is
  ~3 TB raw; HNSW adds graph overhead on top. Quantization (PQ) is how you fit it —
  trading recall for a large memory reduction.
- **Query latency** is set by the index type and its recall dials.
- **Build cost** is dominated by embedding the corpus (batch inference) and index
  construction; re-embedding on a model change is a full rebuild.

---

## The Interview Checklist

1. **Embeddings** — similar meaning → nearby vectors; cosine similarity; the model is
   a versioned artifact (change = re-embed everything).
2. **Why ANN** — exact is O(N); approximate trades recall for sub-linear search.
3. **Index types** — HNSW (default), IVF, PQ (memory), and their recall/latency/memory
   trade-offs.
4. **Chunking** — often the real quality lever in RAG.
5. **Hybrid + rerank** — vector alone misses exact terms; combine and rerank.
6. **Filtering & sharding** — filtered-ANN is hard; shard by random vs metadata.
7. **Cost** — memory-bound; quantization is the escape valve.
