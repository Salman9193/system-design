# The Network Model

Networking is the layer *beneath* Communication. Communication asked "which protocol
and API style do two services use to talk?" Networking asks the more basic question:
**how does a request actually get from one machine to another across the internet?**
You rarely design at this layer, but you must understand it to reason about latency,
failures, and security — and interviewers open the box.

---

## What This Module Covers

Everything that sits between "a client wants to reach your system" and "the request
arrives at your server":

- **The layered model** (this tab) — how the network is organized.
- **IP & ports** — how machines and services are addressed.
- **DNS** — how a name becomes an address.
- **CDN** — how content is served from close to the user.
- **Proxies & NAT** — the intermediaries in the path.
- **Network security** — firewalls, private networks, VPNs, encryption in transit.

Communication owns protocols/APIs/gateways/load balancers; Networking owns getting
the bytes there in the first place.

---

## The Layered Model

Networking is organized into **layers**, each responsible for one job and building on
the one below. Two models describe it — the 7-layer **OSI** model (the textbook one)
and the 4-layer **TCP/IP** model (the practical one). They map onto each other:

| TCP/IP layer | Job | Examples |
|--------------|-----|----------|
| **Application** | The actual data/protocol services speak | HTTP, DNS, TLS, gRPC |
| **Transport** | Host-to-host delivery (ports, reliability) | TCP, UDP, QUIC |
| **Internet** | Addressing & routing across networks | IP, routers |
| **Link** | Physical/local delivery on a wire or Wi-Fi | Ethernet, Wi-Fi, MAC |

The key insight: **each layer only cares about its own job.** HTTP doesn't know how
packets are routed; IP doesn't know it's carrying HTTP. That separation is what lets
the internet work — you can swap Wi-Fi for Ethernet, or TCP for QUIC, without
rewriting the layers above.

> The staff move: "When someone asks 'TCP or REST?' I separate the layers: REST is an
> application-layer style, TCP is transport, IP is addressing/routing. They stack;
> they don't compete. Knowing which layer a problem lives at tells me where to fix it
> — a routing issue is L3, a connection issue is L4, a payload issue is L7."

---

## Encapsulation — How Data Is Wrapped

As data goes down the layers to be sent, each layer wraps it in its own header — like
nested envelopes:

```
  Application:  [ HTTP request ]
  Transport:    [ TCP header | HTTP request ]              ← adds ports, seq numbers
  Internet:     [ IP header | TCP header | HTTP request ]  ← adds source/dest IP
  Link:         [ Frame header | IP packet | ... ]         ← adds MAC, for the local hop
```

At the receiving end, each layer peels off its header and hands the payload up. This
is **encapsulation**, and it's why a "packet" means different things at different
layers (segment at L4, packet at L3, frame at L2).

---

## Packet Switching & Routing (the one-paragraph version)

Data isn't sent as one continuous stream over a dedicated line. It's chopped into
**packets**, each routed independently across the network. **Routers** forward each
packet hop-by-hop toward its destination IP, choosing a path based on routing tables.
Packets can take different routes and arrive out of order; the transport layer (TCP)
reassembles and reorders them. This **packet switching** is what makes the internet
resilient — if a link fails, packets route around it — and it's why network latency
has a variable, path-dependent component.

---

## The Journey of a Request (ties the tabs together)

When a user opens `https://example.com`, roughly this happens — and each step maps to
a tab in this module:

1. **DNS** resolves `example.com` to an IP address.
2. Routing (**IP**, packet switching) carries packets toward that IP, often via a
   **CDN** edge close to the user.
3. A **TCP + TLS** handshake establishes a secure connection (transport + security).
4. The **HTTP** request travels — usually through a **reverse proxy / load balancer**
   — to a server, crossing **firewalls** and **private network** boundaries.
5. The response streams back the same path.

The rest of this module fills in each hop. Keep this journey in mind — it's the
skeleton the details hang on.

---

## Why This Matters in System Design

You won't design IP routing, but this layer explains things you *will* reason about:

- **Latency** has a physical/network component (distance, hops, handshakes) — which is
  why CDNs and regional deployments exist.
- **Failures** happen at specific layers — knowing whether it's DNS, routing, TCP, or
  the app narrows debugging enormously.
- **Security** is layered — firewalls (L3/L4), private networks (L3), TLS (L4/L7), app
  auth (L7) — and defense in depth means covering several.

The value isn't reciting OSI layers; it's using the model to locate a problem and
justify a design choice.
