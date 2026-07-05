# Application Protocols — HTTP, gRPC, WebSockets, TLS

Application protocols frame the messages that ride the transport. HTTP dominates, but
its versions differ in ways that matter, and gRPC and WebSockets solve problems HTTP
request/response doesn't. TLS wraps all of them.

---

## HTTP — and Why the Version Matters

HTTP is a request/response protocol: the client sends a request (method, path,
headers, body), the server sends a response (status, headers, body). It's stateless
by design — each request is independent — which is what makes it scale horizontally.
But the versions have real performance differences.

### HTTP/1.1
- One request at a time per connection; a response must complete before the next
  request on that connection (**head-of-line blocking at the application level**).
- Browsers work around it by opening **many parallel connections**, which wastes
  resources.
- **Keep-alive** reuses a connection across requests (avoiding repeated handshakes),
  the main efficiency win.

### HTTP/2
- **Multiplexing:** many concurrent requests/responses over a **single** TCP
  connection, interleaved as "streams" — no more many-connections workaround.
- **Header compression** (HPACK) — headers repeat a lot; compressing them saves
  bandwidth.
- **Server push** (largely deprecated in practice).
- **The catch:** streams are multiplexed over one TCP connection, so a lost packet
  causes **TCP-level head-of-line blocking** that stalls *all* streams — HTTP/2 moved
  the blocking down a layer rather than removing it.

### HTTP/3
- Runs over **QUIC (UDP)** instead of TCP (see Transport Protocols).
- **Removes cross-stream head-of-line blocking** — a lost packet stalls only its own
  stream.
- **Faster setup** (folded TLS handshake) and **connection migration** across
  networks.
- Best on lossy/mobile networks; now widely deployed.

> The staff move: "HTTP/2 gave us multiplexing over one connection but still suffered
> TCP head-of-line blocking; HTTP/3 fixes that by moving to QUIC. For an internal
> service-to-service mesh I'd default to HTTP/2 (or gRPC on it); for user-facing edge
> traffic, especially mobile, HTTP/3 is worth it."

---

## gRPC — RPC over HTTP/2

gRPC is a high-performance **Remote Procedure Call** framework (a paradigm — see API
Paradigms) that uses **HTTP/2** as its protocol and **Protocol Buffers** (a compact
binary format) for serialization.

**Why it's fast and popular for internal services:**
- **Binary Protobuf** is smaller and faster to encode/decode than JSON text.
- **HTTP/2 multiplexing** — many concurrent calls on one connection.
- **Streaming** — supports client-streaming, server-streaming, and bidirectional
  streaming, not just unary request/response.
- **Schema-first / codegen** — the `.proto` contract generates strongly-typed client
  and server stubs, catching mismatches at compile time.

**The trade-offs:**
- **Not human-readable** (binary) — harder to debug by eye than JSON/REST.
- **Limited native browser support** — browsers can't speak raw gRPC easily (needs a
  proxy like gRPC-Web), so it's mostly **internal service-to-service**, not a public
  browser-facing API.

> "gRPC is my default for internal service-to-service calls: binary Protobuf and
> HTTP/2 make it fast, and the `.proto` contract gives type-safe stubs and streaming.
> I keep REST/JSON at the public edge because it's browser-friendly and debuggable —
> gRPC's binary format and browser limits make it a poor public API."

---

## WebSockets — Full-Duplex, Persistent

A WebSocket **upgrades** an HTTP connection into a persistent, **bidirectional**
channel: after the handshake, either side can send messages any time, with low
per-message overhead. It's a protocol built for **real-time** communication.

**When to use it:**
- True bidirectional real-time: chat, collaborative editing, live dashboards,
  multiplayer, trading feeds.

**When not to:**
- One-way server→client streaming — **Server-Sent Events (SSE)** is simpler (it's
  just an HTTP response that stays open).
- Rare updates — **polling** is simpler and cheaper than holding a socket open.

**The cost:** persistent connections are **stateful** — the server holds one open
socket per client, which is a real capacity/scaling concern (connection count,
sticky routing, reconnection handling). Contrast with stateless HTTP, where any
server can handle any request.

---

## TLS — Encryption Underneath All of Them

TLS provides **encryption** (privacy), **integrity** (tamper detection), and
**authentication** (the server proves its identity via a certificate). "HTTPS" is
just HTTP over TLS.

- **Handshake cost:** classic TLS adds 1–2 round trips on top of the TCP handshake to
  negotiate keys — part of why connection reuse (keep-alive) matters. TLS 1.3 cut this
  to one round trip (and 0-RTT for resumption); QUIC folds it into the transport
  handshake.
- **mTLS (mutual TLS):** both sides present certificates, so the *client* is
  authenticated too — common for service-to-service auth inside a mesh (a hook into
  the Auth tab).
- **Termination:** TLS is often terminated at the load balancer / gateway, which then
  talks plaintext (or re-encrypted) to backends — see the next tab.

---

## Choosing an Application Protocol

| Use case | Protocol |
|----------|----------|
| Public / browser-facing API | **HTTP** (REST/GraphQL over it), HTTP/3 at the edge |
| Internal service-to-service | **gRPC** (HTTP/2 + Protobuf) for speed & typed contracts |
| Bidirectional real-time | **WebSockets** |
| One-way server→client stream | **SSE** (simpler than WebSockets) |
| Everything, encrypted | **TLS** underneath (always, in practice) |

The through-line: **HTTP for the public edge (debuggable, universal), gRPC for
internal calls (fast, typed), WebSockets/SSE for real-time, TLS everywhere.** Match
the protocol to whether the caller is a browser, another service, or a live client —
that fit is the judgment being tested.
