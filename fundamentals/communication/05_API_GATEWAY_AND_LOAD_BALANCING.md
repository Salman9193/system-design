# API Gateway & Load Balancing

Between the client and your services sit two pieces of infrastructure that get
conflated constantly: the **load balancer** (spreads traffic across instances) and the
**API gateway** (a smart entry point that does cross-cutting work). Knowing precisely
who does what — and where each sits — is the clarity signal here.

---

## Load Balancer — Spread Traffic, Survive Failures

A load balancer distributes incoming requests across multiple backend instances so no
one instance is overwhelmed and the loss of an instance doesn't take down the service.
It's the foundation of horizontal scaling and availability.

### Layer 4 vs Layer 7 (the key distinction)

| | L4 (transport) | L7 (application) |
|--|----------------|------------------|
| Sees | IP + port (TCP/UDP) | Full request (HTTP path, headers, cookies) |
| Routes by | Connection-level info | Content — path, host, header |
| Speed | Faster, less work | Slightly slower, more CPU |
| Capabilities | Just forwards connections | Path routing, TLS termination, header rewrite, sticky sessions |

- **L4** forwards packets/connections without looking inside — fast, protocol-agnostic,
  good for raw throughput.
- **L7** understands HTTP, so it can route `/api/*` to one service and `/images/*` to
  another, terminate TLS, and inspect/modify requests. Most application load balancing
  is L7.

### Balancing algorithms
- **Round robin** — rotate through instances. Simple; ignores load.
- **Least connections** — send to the instance with the fewest active connections.
  Better when request durations vary.
- **Weighted** — bias toward bigger instances.
- **Hash / IP hash** — hash a key (client IP, session id) so a client sticks to one
  instance (**sticky sessions**) — needed for stateful backends, but it hurts even
  distribution and complicates scaling.

### Health checks
The load balancer continuously probes instances and **removes unhealthy ones** from
rotation, routing around failures automatically. This is what turns "an instance died"
from an outage into a non-event — and it's why health-check endpoints matter.

> The staff move: "I'd use an L7 load balancer so I can route by path and terminate
> TLS, with least-connections balancing since request durations vary, and health
> checks to eject bad instances automatically. I avoid sticky sessions unless the
> backend is stateful — they undermine even load distribution and make scaling
> harder, so I'd rather keep services stateless."

---

## API Gateway — The Smart Front Door

An API gateway is a single entry point in front of your services that handles the
**cross-cutting concerns** every service would otherwise reimplement. It's especially
valuable in a microservices architecture, where you don't want each of 50 services
doing its own auth, rate limiting, and logging.

**What it typically does:**
- **Routing** — send requests to the right backend service (path/host based).
- **Authentication & authorization** — verify the caller once, at the edge, before
  traffic reaches services (see the Auth tab).
- **Rate limiting & throttling** — protect backends from abuse/overload (see the Rate
  Limiter LLD).
- **TLS termination** — decrypt at the edge.
- **Request/response transformation** — reshape, aggregate, or version.
- **Aggregation / BFF** — combine several backend calls into one client response.
- **Observability** — a natural place for centralized logging, metrics, and tracing.
- **Caching** — cache common responses at the edge.

> "In a microservices setup I put an API gateway at the edge so auth, rate limiting,
> TLS termination, and logging live in one place instead of being reimplemented in
> every service. Services stay focused on business logic. The risk is the gateway
> becoming a fat bottleneck or single point of failure, so I keep it stateless,
> horizontally scaled, and behind a load balancer — and resist putting business logic
> in it."

---

## Gateway vs Load Balancer vs Reverse Proxy (untangling them)

These overlap, which is why they're confused. The clean mental model:

- **Reverse proxy** — the general concept: a server that sits in front of backends and
  forwards requests to them. Both of the below *are* specialized reverse proxies.
- **Load balancer** — a reverse proxy whose job is **distributing traffic** across many
  instances for scale and availability. Focus: *which instance*.
- **API gateway** — a reverse proxy whose job is **cross-cutting API concerns** (auth,
  rate limiting, routing, aggregation). Focus: *what to do with the request*.

They're **complementary and often layered**:

```
client → load balancer (spread + health) → API gateway (auth, rate-limit, route)
                                                    → service A
                                                    → service B (behind its own LB)
```

A common real setup: an L7 load balancer distributes across gateway instances; the
gateway authenticates and routes to services; each service sits behind its own
internal load balancer. Being able to place each box correctly — and say why — is the
point.

---

## Service Discovery (the internal counterpart)

Inside a dynamic system, instances come and go (autoscaling, deploys, failures), so
callers can't hardcode addresses. **Service discovery** lets a service find healthy
instances of another:

- **Client-side discovery** — the caller queries a registry and picks an instance.
- **Server-side discovery** — the caller hits a load balancer / gateway that knows the
  instances (more common, simpler clients).
- A **registry** (with health checking) is the source of truth for "who's alive."

This is what makes load balancing work in an elastic fleet: the balancer's instance
list is kept live by discovery, so scaling and failures update routing automatically.

---

## The Summary

- **Load balancer:** *which instance* — spread traffic (L4 vs L7), balance
  (round-robin/least-conn), health-check out failures.
- **API gateway:** *what to do with the request* — auth, rate limit, route, aggregate,
  observe, at one edge.
- **They layer:** LB in front for distribution, gateway for cross-cutting concerns,
  internal LBs per service; service discovery keeps the instance lists live.

Keep the gateway thin (no business logic) and everything behind it stateless and
horizontally scaled, so the front door scales and fails gracefully.
