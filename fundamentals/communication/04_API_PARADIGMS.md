# API Paradigms — REST, SOAP, GraphQL, gRPC, and Real-Time

An API paradigm is *how you model the interface* — the style in which a client asks
for what it wants. These sit on top of the protocols. This is the layer with the most
choices and the comparison interviewers most often want, so know each one's shape,
its sweet spot, and its cost.

---

## REST — Resource-Oriented over HTTP

REST models the world as **resources** (nouns) addressed by URLs, manipulated with
HTTP methods (verbs): `GET /users/42`, `POST /orders`, `DELETE /carts/7`.

**Pros:**
- **Universal & simple** — every language, browser, and tool speaks HTTP/JSON; trivial
  to call and debug (curl, browser).
- **Stateless & cacheable** — HTTP caching, CDNs, and proxies work out of the box;
  `GET`s cache naturally.
- **Uses HTTP semantics** — status codes, methods, and idempotency are well-understood.

**Cons:**
- **Over-/under-fetching** — an endpoint returns a fixed shape, so clients often get
  more than they need or must make **multiple round trips** to assemble a screen (the
  problem GraphQL targets).
- **Many endpoints / versioning churn** — rich clients drive lots of endpoints and
  `/v2/` proliferation.
- **No enforced contract** — without extra tooling (OpenAPI), the schema is
  convention, not compile-time-checked.

**Sweet spot:** public/browser-facing APIs, CRUD-shaped resources, anything that
benefits from HTTP caching. **The safe default for a public API.**

---

## GraphQL — Client-Specified Queries

GraphQL exposes a **single endpoint** and a **schema**; the client sends a query
describing *exactly* the fields it wants, across multiple resources, in one request.

**Pros:**
- **No over-/under-fetching** — the client asks for precisely the fields it needs.
- **One round trip for a whole screen** — fetch a user, their orders, and each order's
  items in a single query. Great for rich mobile/web UIs and aggregating many backends.
- **Strong typed schema** — introspectable, self-documenting.

**Cons:**
- **Server complexity** — resolvers, and the **N+1 query problem** (a naive resolver
  fires a DB query per item) needs batching/dataloaders.
- **Caching is harder** — it's usually `POST` to one URL, so HTTP/CDN caching doesn't
  apply for free; you cache at the application level.
- **Expensive/abusive queries** — a client can request a huge nested graph; you need
  query cost limits and depth limiting.

**Sweet spot:** rich clients aggregating many resources/backends, where flexible
fetching outweighs the loss of easy HTTP caching. **A BFF (backend-for-frontend)
pattern.**

---

## gRPC — Contract-First RPC

gRPC models the interface as **procedure calls** ("call `GetUser(id)`"), using
Protobuf over HTTP/2 (see Application Protocols). It's a paradigm *and* a protocol
stack.

**Pros:**
- **Fast** — binary Protobuf + HTTP/2 multiplexing; much lighter than JSON/HTTP.
- **Strongly-typed contract** — the `.proto` generates typed stubs; mismatches fail at
  compile time.
- **Streaming** — client/server/bidirectional streaming built in.

**Cons:**
- **Not browser-native / not human-readable** — binary, needs a proxy for browsers,
  harder to debug by eye.
- **Tighter coupling** — schema/codegen is great internally but heavier for loosely-
  coupled public consumers.

**Sweet spot:** **internal service-to-service** communication in a microservices mesh
where speed and typed contracts matter and the consumers are other services, not
browsers.

---

## SOAP — The Enterprise Predecessor

SOAP is an older, **XML-based**, protocol-heavy standard with rigid contracts (WSDL)
and built-in specs for security, transactions, and reliability (WS-* standards).

**Pros:**
- **Formal contract (WSDL)** and mature enterprise features (WS-Security, distributed
  transactions).
- Strong typing and tooling in enterprise/legacy ecosystems.

**Cons:**
- **Verbose and heavy** — XML envelopes, lots of ceremony.
- **Rigid and complex** — largely superseded by REST/gRPC for new work.

**Sweet spot:** legacy/enterprise integrations (banking, telecom, government) and
existing SOAP ecosystems. **Rarely chosen for greenfield systems** — know it so you
can say why you'd pick REST/gRPC instead, and recognize it when it appears.

---

## Real-Time: Polling, Long-Polling, SSE, WebSockets

For pushing data *to* the client, pick the lightest option that meets the need:

| Approach | How it works | Best for | Cost |
|----------|-------------|----------|------|
| **Short polling** | Client asks on a timer | Rare updates; simplicity | Wasteful requests; latency = poll interval |
| **Long polling** | Server holds the request open until there's data | Occasional updates without sockets | Ties up a connection; awkward at scale |
| **SSE** (Server-Sent Events) | One long-lived HTTP response streams events server→client | **One-way** live feeds (notifications, tickers) | Server→client only; auto-reconnect built in |
| **WebSockets** | Persistent full-duplex channel | **Bidirectional** real-time (chat, collab, gaming) | Stateful connections to manage/scale |

> The staff move: "I don't reach for WebSockets by default. If updates are rare, I
> poll. If it's one-way server-to-client, SSE is simpler. I only use WebSockets when I
> genuinely need bidirectional real-time — because persistent stateful connections are
> a real scaling burden."

---

## The Comparison at a Glance

| | REST | GraphQL | gRPC | SOAP | WebSocket |
|--|------|---------|------|------|-----------|
| Model | Resources | Query graph | Procedures | Operations (XML) | Message channel |
| Protocol | HTTP | HTTP | HTTP/2 | HTTP/XML | WS (upgraded HTTP) |
| Format | JSON (usually) | JSON | Protobuf (binary) | XML | Any |
| Contract | Optional (OpenAPI) | Schema | `.proto` (strict) | WSDL (strict) | None |
| Fetching | Fixed per endpoint | Client-specified | Fixed per method | Fixed | Streaming |
| Caching | Easy (HTTP) | Hard | Hard | Hard | N/A |
| Browser-native | Yes | Yes | No (needs proxy) | Yes | Yes |
| Best for | Public/CRUD APIs | Rich clients aggregating data | Internal service-to-service | Legacy/enterprise | Bidirectional real-time |

---

## How to Choose (the decision, stated simply)

- **Public / browser-facing, CRUD-ish?** → **REST** (default; cacheable, universal).
- **Rich client pulling many resources at once?** → **GraphQL** (kill over-fetching;
  accept harder caching).
- **Internal service-to-service, performance-sensitive?** → **gRPC** (binary, typed,
  streaming).
- **Bidirectional real-time?** → **WebSockets** (else SSE/polling).
- **Legacy enterprise ecosystem?** → **SOAP** (only when you must).

The staff-level answer usually **mixes** them: REST or GraphQL at the public edge,
gRPC between internal services, WebSockets/SSE for the real-time slice. Naming *why
each fits its slice* — and that a real system uses several — is the signal, not
crowning one winner.
