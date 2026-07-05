# DNS

DNS (Domain Name System) is the internet's directory: it maps human-friendly names
like `example.com` to IP addresses. Every request starts here, which makes DNS both a
powerful routing tool and a critical dependency — a DNS outage takes *everything*
down. Interviewers probe it because it's also how geo-routing, failover, and CDNs
begin.

---

## What DNS Does

You type a name; machines need an IP. DNS is the lookup in between. It's a distributed,
hierarchical, heavily-cached system — no single server holds the whole map; it's
delegated across a tree of nameservers.

---

## The Resolution Flow

When a client resolves `example.com`, it walks a caching hierarchy, stopping at the
first level that knows the answer:

```
  browser cache → OS cache → recursive resolver
                                   │  (if not cached, walk the tree:)
                                   ├─► root nameserver      → "ask the .com servers"
                                   ├─► TLD nameserver (.com) → "ask example.com's servers"
                                   └─► authoritative server  → "example.com is 192.0.2.10"
                                   ◄── answer cached at each level on the way back
```

1. **Browser / OS caches** — checked first; most lookups never leave the machine.
2. **Recursive resolver** (your ISP's or a public one) — does the legwork on the
   client's behalf, and caches aggressively.
3. **Root → TLD → authoritative** — the delegation chain, walked only on a cache miss.
   The **authoritative** nameserver is the source of truth for that domain.

Each level caches results for a **TTL**, so popular names resolve almost instantly and
the root/TLD servers aren't hammered.

---

## Record Types (the common ones)

| Record | Maps | Use |
|--------|------|-----|
| **A** | Name → IPv4 | The basic lookup |
| **AAAA** | Name → IPv6 | IPv6 lookup |
| **CNAME** | Name → another name | Aliases (`www` → `example.com`) |
| **MX** | Name → mail server | Email routing |
| **NS** | Domain → nameservers | Delegation |
| **TXT** | Name → text | Verification, SPF/DKIM, etc. |

---

## DNS as a System-Design Tool

DNS isn't just lookup — it's the first place you can steer traffic:

- **DNS-based load balancing:** return multiple A records, or rotate them (round-robin
  DNS), to spread clients across servers. Coarse, but zero infrastructure.
- **Geo / latency-based routing:** an authoritative server can return a *different* IP
  based on where the query came from — sending users to the nearest region or CDN edge.
  This is how multi-region routing and CDNs begin.
- **Failover:** health-checked DNS can stop handing out the IP of a dead region and
  point elsewhere. Coarse (bounded by TTL), but a real disaster-recovery lever.

### The TTL trade-off (know this one)
TTL is how long resolvers cache a record:

- **Low TTL** → changes and failover propagate fast, but more DNS queries and load.
- **High TTL** → fewer queries, but changes (including failover) propagate slowly —
  clients keep using the old IP until caches expire.

> The staff move: "DNS gives me coarse, global routing for free — geo-routing to the
> nearest region and health-checked failover — but it's bounded by TTL, so it's slow
> to react. I keep DNS TTLs low enough for tolerable failover, and do the *fast*,
> fine-grained load balancing at L4/L7 closer to the servers. DNS steers to the right
> region; the load balancer steers within it."

---

## DNS as a Critical Dependency

Because every request begins with a DNS lookup, DNS is a systemic single point of
failure: if resolution breaks, healthy servers become unreachable — the outage looks
total even though nothing else is down. Treat it accordingly:

- Use **multiple, redundant** authoritative nameservers (often across providers).
- Beware **caching during incidents** — a bad record can linger for the TTL; a
  too-high TTL turns a quick fix into a long outage.
- DNS is a common **propagation-delay** gotcha: "I changed the record, why is traffic
  still going to the old server?" — caches.

---

## The Summary

- **DNS maps names → IPs** via a cached, hierarchical resolution flow (browser/OS →
  resolver → root → TLD → authoritative).
- **Records:** A/AAAA (IPs), CNAME (alias), MX (mail), NS (delegation).
- **As a tool:** coarse global routing — DNS load balancing, geo/latency routing (the
  basis of CDNs and multi-region), and health-checked failover — all governed by the
  **TTL trade-off** (fast reaction vs query load).
- **As a risk:** it's the first hop and a systemic dependency — make it redundant and
  mind caching during changes.
