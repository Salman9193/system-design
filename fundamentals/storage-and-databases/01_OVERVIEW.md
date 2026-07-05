# Overview & Storage Types

Where your data lives is the most consequential decision in a system design — and the
hardest to change later. Get it wrong and you fight it forever; get it right and the
rest of the design falls into place. This module is about choosing deliberately. This
tab frames *how to choose* and covers the physical **storage types** (block, file,
object); the remaining tabs cover the **database** layer on top.

---

## Two Levels: Storage Types vs Databases

"Storage" spans two levels that are easy to conflate:

- **Storage types (physical):** how raw bytes are stored and accessed — block, file, or
  object storage. This is infrastructure.
- **Databases (logical):** how data is *modeled and queried* — relational (SQL) or one
  of the NoSQL families. Databases run *on top of* physical storage.

This tab is the physical level; tabs 2–6 are the logical level.

---

## How to Choose Storage (the decision lens)

Before picking a database, answer these — they drive everything:

1. **Data shape.** Structured with relationships (rows/columns)? Semi-structured
   (JSON-ish documents)? Large blobs (images, video)? A graph of connections? Key-value
   pairs?
2. **Access patterns.** Read-heavy or write-heavy? Point lookups, range scans, or
   complex joins/aggregations? Do you need flexible ad-hoc queries, or a few known ones?
3. **Scale.** How much data, how much throughput (QPS), and how fast is it growing?
4. **Consistency & durability.** Do you need strong consistency and transactions
   (money, orders), or is eventual consistency fine (a like count)? Can you *ever* lose
   a write?
5. **Latency.** How fast must reads/writes be?

The staff-level habit is to derive the storage choice from these answers, not to reach
for a favorite database. "What's the data shape and access pattern?" is the first
question, every time.

---

## Storage Types (physical)

At the infrastructure level, three fundamentally different ways to store bytes:

| Type | Model | Access | Best for | Not for |
|------|-------|--------|----------|---------|
| **Block** | Raw fixed-size blocks; a volume you format | Attached to one machine, low latency | Databases, VMs, anything needing fast random read/write | Sharing across many machines |
| **File** | Hierarchical filesystem (dirs/files) | Shared over a network (NFS/SMB) | Shared files, home dirs, config, lift-and-shift apps | Massive scale, web-native access |
| **Object** | Flat namespace of objects + metadata | HTTP API (GET/PUT by key) | Blobs: media, backups, static assets, data lakes | Low-latency random writes, transactions |

### Block storage
A raw volume (like a virtual hard disk) attached to a single machine, which formats it
with a filesystem. Lowest latency, high IOPS — this is what **databases run on**. It's
tied to one instance at a time.

### File storage
A shared, hierarchical filesystem accessed over the network, so **many machines mount
the same files**. Good for shared configuration, home directories, and legacy apps that
expect a filesystem. Doesn't scale like object storage.

### Object storage
A flat store of **objects** (blob + metadata + unique key), accessed over HTTP. It's
massively scalable, highly durable (data replicated across facilities), cheap, and
web-native — but it's not a filesystem and not for low-latency random updates (you
replace whole objects). This is where **large files belong**.

---

## The "Blobs in Object Storage, Metadata in a Database" Pattern

A recurring staff-level move: **don't put large files in your database.** Storing
images/videos/documents as rows bloats the DB, kills performance, and wastes expensive
storage. Instead:

- Put the **blob** in object storage.
- Put the **metadata** (owner, filename, size, timestamps, the object's URL/key) in your
  database.
- Serve the blob directly from object storage (often via a CDN — see Networking).

> The staff move: "For anything with large files — user uploads, media, documents — I
> store the bytes in object storage and keep only metadata and the object key in the
> database. The DB stays small and fast, object storage gives me cheap durable scale,
> and I serve the files through a CDN. Putting blobs in the database is a classic
> scaling mistake."

---

## What's Ahead

The rest of this module is the database layer:
- **Relational & ACID** — the structured, transactional default.
- **NoSQL families** — key-value, document, wide-column, graph, and when each fits.
- **SQL vs NoSQL** — the decision, and why real systems use both.
- **Indexing** — how reads get fast (B-tree vs LSM).
- **Transactions & isolation** — correctness under concurrency.

Scaling a database *across many machines* (sharding, replication, consistency) is big
enough to be its own module — Data distribution — so this module is about choosing and
using a database; that one is about scaling it.
