# Auth & Observability

Two cross-cutting concerns that live on every communication path: proving *who's
allowed to do what* (auth), and being able to *see what the traffic is doing*
(observability). Auth is a hint here — it earns its own dedicated fundamental — while
observability is covered enough to design and operate the communication layer.

---

## Authentication & Authorization (the hint)

Two distinct questions, constantly conflated:

- **Authentication (authn):** *who are you?* — verifying identity.
- **Authorization (authz):** *what are you allowed to do?* — verifying permissions.

A request is authenticated first (establish identity), then authorized (check that
identity may perform this action on this resource).

### Where auth sits on the communication path
- **At the edge (gateway):** authenticate once at the API gateway so backend services
  don't each reimplement it. The gateway validates the credential and forwards a
  trusted identity to services.
- **Between services (internal):** **mTLS** is common — both services present
  certificates, so each proves its identity (see Application Protocols). This secures
  the internal mesh without passing user credentials around.

### The common mechanisms (at a glance)
- **Tokens (JWT):** a signed token carrying identity/claims; the server verifies the
  signature without a lookup (stateless auth). The workhorse of modern APIs.
- **OAuth 2.0 / OIDC:** delegated authorization — "let this app act on my behalf"
  (third-party login, scoped access).
- **API keys:** simple shared secrets for service/partner access.
- **Session cookies:** server-side sessions for browser apps.

> The staff move (kept brief): "I'd authenticate at the gateway with signed tokens so
> services get a trusted identity without each re-verifying, and use mTLS for
> service-to-service identity inside the mesh. Authorization — the per-resource
> permission check — I'd keep close to the data, since only the service owning the
> resource knows the fine-grained rules."

**This is a hint, not the deep dive.** Token lifecycle and refresh, OAuth flows, RBAC
vs ABAC, permission models, secret rotation, and revocation belong in a dedicated
Auth/Security fundamental — flag that and move on rather than rabbit-holing here.

---

## Observability — Seeing the Communication Layer

You can't operate what you can't see. Observability rests on three pillars — **metrics,
logs, traces** — and the communication layer is where much of it is captured (the
gateway and load balancer see all traffic).

### The Golden Signals (what to monitor — the checklist)
A focused, industry-standard set to watch for any service-to-service path:

1. **Latency** — how long requests take. Track **percentiles (p50/p95/p99)**, not
   averages — the tail is what users feel, and an average hides it. Separate
   successful from failed latency (a fast failure can flatter your numbers).
2. **Traffic** — request rate (RPS/QPS). The demand signal; drives capacity.
3. **Errors** — rate of failed requests (5xx, timeouts, and 4xx where relevant). The
   primary health signal.
4. **Saturation** — how full the resources are (CPU, memory, connection pools, queue
   depth). The leading indicator of *impending* trouble before errors spike.

> "I'd monitor the golden signals per service and per dependency: latency at p95/p99,
> request rate, error rate, and saturation. Saturation is the early warning —
> connection-pool or queue-depth climbing tells me I'm about to breach latency before
> errors show up."

### Communication-specific things to watch
Beyond the golden signals, the network path has its own tells:
- **Connection metrics** — open connections, connection pool exhaustion, new-connection
  rate. Pool exhaustion is a classic hidden outage cause.
- **Timeouts & retries** — a spike signals a struggling dependency; retries can amplify
  an incident (retry storms), so watch them.
- **Load balancer health** — how many instances are in/out of rotation; ejections are a
  leading failure indicator.
- **Gateway metrics** — rate-limit rejections, auth failures, per-route latency/errors.
- **Dependency health** — success rate and latency of *each* downstream call, so you can
  localize which hop is failing.

### Distributed Tracing (essential across services)
In a request that touches many services, a single latency number tells you nothing
about *where* the time went. **Distributed tracing** propagates a trace/correlation ID
across every hop, so you can see the full path of one request and attribute latency to
the specific service or call responsible. It's what makes "why is this endpoint slow?"
answerable in a microservices system.

- **Propagate a correlation ID** from the edge through every downstream call (usually
  injected/forwarded by the gateway).
- Logs across services stamped with the same ID become a single searchable story.

---

## The Summary

- **Auth = authn (who) + authz (what).** Authenticate at the edge with tokens, mTLS
  between services, authorize near the data. The rest is a dedicated Auth fundamental.
- **Observe with the golden signals** — latency (percentiles!), traffic, errors,
  saturation — plus connection/timeout/retry metrics and load-balancer health.
- **Distributed tracing with a propagated correlation ID** is what lets you debug
  latency and failures across a multi-service call path.

Design the communication layer to be *observable by default*: emit the golden signals,
propagate a trace ID from the edge, and centralize collection at the gateway — so when
something breaks, you can see which hop and why.
