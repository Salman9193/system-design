# Communication — Overview & the Mental Model

Every distributed system is services talking to each other. Before memorizing
protocols, get the **mental model** right, because the single most common muddle —
in interviews and in practice — is treating things from different layers as if they
were alternatives. "Should I use TCP or REST?" is a category error: they're not
competing; they're stacked.

---

## The Three Layers (get this straight first)

Communication choices live at three distinct layers. Each sits on top of the one
below:

```
  ┌───────────────────────────────────────────────────────────┐
  │  API PARADIGM   — how you model the interface              │
  │  REST · GraphQL · RPC (gRPC) · SOAP · WebSocket messaging  │
  ├───────────────────────────────────────────────────────────┤
  │  APPLICATION PROTOCOL — how messages are framed            │
  │  HTTP/1.1 · HTTP/2 · HTTP/3 · gRPC · WebSocket · TLS       │
  ├───────────────────────────────────────────────────────────┤
  │  TRANSPORT      — how bytes move between hosts             │
  │  TCP · UDP · QUIC                                          │
  └───────────────────────────────────────────────────────────┘
```

- **REST is not an alternative to HTTP** — it's an architectural *style* that uses
  HTTP.
- **gRPC is a paradigm (RPC) that rides HTTP/2** — it spans two layers.
- **A WebSocket is an application protocol**; the messaging you do over it is the
  paradigm.

Saying "I'd expose a REST API over HTTP/2 on TCP" is precise. Saying "I'll use TCP
instead of REST" reveals the layers aren't clear. **Naming the layer you're operating
at is a clarity signal**; conflating them is the classic tell of a shakier answer.

The three tabs that follow map exactly onto these layers — Transport Protocols,
Application Protocols, API Paradigms — then Gateway/Load Balancing and Auth/
Observability cover the infrastructure the traffic flows through.

---

## The Other Axis: Synchronous vs Asynchronous

Orthogonal to the layer stack is *when* the caller gets its answer. This is arguably
the more important design decision:

| | Synchronous (request/response) | Asynchronous (messaging/events) |
|--|-------------------------------|----------------------------------|
| Shape | Caller sends, **waits** for a reply | Caller sends, **moves on**; reply later or never |
| Coupling | Tighter — both must be up at once | Looser — a broker decouples them in time |
| Examples | REST call, gRPC call, DB query | Message queue, pub/sub, event stream |
| Good for | "I need the answer now" (read a profile, place an order) | Decoupling, buffering spikes, fan-out, long work |
| Failure impact | Callee down → caller blocked/fails | Broker buffers → callee can be down briefly |
| Cost | Latency is end-to-end and visible | Eventual consistency, harder to reason about |

- **Synchronous** is the default for user-facing reads and anything needing an
  immediate answer — it's simple and the latency is honest.
- **Asynchronous** is how you decouple services, absorb traffic spikes, fan work out,
  and run anything slow. It trades immediacy for resilience and scale.

> The staff move: "I'd make the user-facing path synchronous for a fast, direct
> answer, but push anything slow, spiky, or fan-out — sending emails, updating
> downstream services, processing uploads — onto an async queue, so a burst or a
> slow consumer can't stall the request path."

Message queues and event streaming are their own deep topic (their own fundamental —
see the roadmap). This tab establishes the *axis*; the mechanics of brokers,
delivery guarantees, and ordering live there.

---

## Real-Time Server → Client (a preview)

A recurring sub-problem is pushing data *to* the client (notifications, live feeds,
chat). The options — polling, long-polling, Server-Sent Events, and WebSockets —
sit across the protocol and paradigm layers and are compared in the API Paradigms
tab. The short version: **poll when updates are rare, SSE for one-way streams,
WebSockets for true bidirectional real-time** — and don't reach for a WebSocket when
a periodic poll would do.

---

## How to Use This Section

When a design calls for two services to talk, decide deliberately at each layer:

1. **Sync or async?** — the first and biggest fork.
2. **Which paradigm?** — REST, gRPC, GraphQL, messaging (see API Paradigms).
3. **Which protocol?** — usually HTTP/2 or /3; gRPC for internal; WebSocket for
   real-time (see Application Protocols).
4. **Transport** — TCP by default; UDP/QUIC when you have a reason (see Transport
   Protocols).
5. **What's in the path?** — load balancer, API gateway, auth, and how you observe
   it all (last two tabs).

The rest of this fundamental is that decision tree, filled in.
