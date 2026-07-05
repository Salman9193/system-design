# CDN

A CDN (Content Delivery Network) is a globally distributed fleet of **edge servers**
that cache content close to users. It's the single biggest lever for reducing latency
on the public path, and it doubles as an origin-offload and DDoS-absorption layer. It
builds directly on DNS (which routes users to the nearest edge) and on caching.

---

## Why a CDN Exists

The speed of light is a hard limit: a user in Sydney hitting an origin in Virginia
pays ~150–200ms round trip *per request*, before any server work. A CDN puts a copy of
your content in an edge location near Sydney, so the user's request travels tens of
kilometres, not thousands. The wins:

- **Latency** — serve from a nearby edge instead of the distant origin.
- **Origin offload** — the edge absorbs most traffic; your origin sees a fraction.
- **Spike & DDoS absorption** — the massive edge fleet soaks up bursts and attacks
  before they reach you.
- **Bandwidth cost** — edge bandwidth is cheaper than origin egress, and cache hits
  don't hit the origin at all.

---

## How Users Reach the Nearest Edge

Two mechanisms route a user to a close edge (tying back to DNS):

- **GeoDNS** — the CDN's authoritative DNS returns the IP of the edge nearest the
  resolver.
- **Anycast** — many edges share one IP, and internet routing delivers packets to the
  topologically-closest one.

Either way, the user connects to a nearby edge without knowing it.

---

## Getting Content to the Edge: Pull vs Push

| | Pull (origin pull) | Push |
|--|--------------------|------|
| How | Edge fetches from origin on first miss, then caches | You upload content to the CDN ahead of time |
| Best for | Most web content — automatic, lazy | Large files you want pre-warmed (video, releases) |
| First request | Slow (cache miss → origin fetch) | Fast (already at the edge) |
| Effort | Low — just set cache headers | Higher — manage uploads |

**Pull is the default:** the first request for an object misses, the edge fetches it
from the origin and caches it, and every subsequent nearby request is a fast hit.

---

## What to Cache (and the hard part)

- **Classic:** static assets — images, video, JS, CSS, fonts. Highly cacheable,
  identical for all users.
- **Increasingly:** cacheable dynamic content and even **edge compute** (running logic
  at the edge — personalization, auth checks, A/B routing — close to the user).

**Cache control** is where the difficulty lives:
- **TTLs** decide how long the edge serves a cached copy.
- **Invalidation / purging** — the hard problem: when content changes, stale copies
  sit in edges worldwide. You either wait out the TTL or explicitly purge.
- **Cache busting** — the common trick: put a version/hash in the URL
  (`app.a1b2c3.js`), so a new version is a *new* URL that can't collide with a cached
  old one. Sidesteps invalidation entirely for versioned assets.
- **Cache key** — what counts as "the same" object (URL, and sometimes headers/query).
  Getting this wrong causes cache misses or, worse, serving one user's content to
  another.

> The staff move: "I push static and cacheable content to a CDN so most requests never
> reach the origin — it's the biggest latency win on the public path and my first
> DDoS-absorption layer. The trade-off is cache invalidation, so I version asset URLs
> for cache-busting and reserve explicit purges for the rare must-update-now case. For
> per-user dynamic content I cache carefully or use edge compute, watching the cache
> key so I never serve one user's data to another."

---

## Where the CDN Sits

The CDN is the outermost layer of the request path — the first thing the user hits:

```
  user → CDN edge (cache hit? serve here) ──miss──► origin (LB → gateway → services)
```

On a hit, the request is answered at the edge and never touches your infrastructure.
On a miss, the edge forwards to the origin, caches the response, and serves it. This
also makes the CDN a natural **TLS-terminating, security-filtering front door**.

---

## The Summary

- **A CDN caches content at edge servers near users** — cutting latency, offloading the
  origin, and absorbing spikes/attacks.
- **Users reach the nearest edge** via GeoDNS or anycast.
- **Pull (lazy) is the default; push pre-warms** large or critical files.
- **Cache invalidation is the hard part** — solve it with versioned URLs for assets and
  explicit purges for the rest; mind the cache key.
- **It sits at the very front** of the path — the first hop, and a natural security/TLS
  layer.
