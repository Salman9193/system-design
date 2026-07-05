# Proxies & NAT

A **proxy** is an intermediary that sits in the traffic path and forwards it. The
direction it faces — toward clients or toward servers — changes everything about what
it's for. **NAT** is a specific, ubiquitous address-translation function that lets
private networks reach the internet. Both are the plumbing your load balancers,
gateways, and CDNs are built on.

---

## Forward Proxy vs Reverse Proxy

The distinction is *whom the proxy represents*.

| | Forward proxy | Reverse proxy |
|--|---------------|---------------|
| Sits in front of | **Clients** | **Servers** |
| Represents | The client, to the internet | The server, to clients |
| Client knows about it? | Yes (configured to use it) | No (thinks it's talking to the server) |
| Typical uses | Egress control, corporate filtering, anonymity, outbound caching | Load balancing, TLS termination, caching, hiding backends |

### Forward proxy — client-side intermediary
Sits between a group of clients and the internet, making outbound requests *on their
behalf*. Used for corporate egress control and filtering (block/allow sites), caching
outbound requests, and anonymizing clients (the destination sees the proxy's IP, not
the client's).

### Reverse proxy — server-side intermediary
Sits in front of your servers; clients think they're talking to the server but hit the
proxy. **This is the important one for system design** — because your **load balancer,
API gateway, and CDN are all reverse proxies.** What a reverse proxy buys you:

- **Load balancing** across backend instances.
- **TLS termination** — decrypt at the edge, so backends don't each handle TLS.
- **Caching & compression** — serve/compress at the proxy.
- **Hiding topology** — clients never see backend IPs; you can change the backend fleet
  freely.
- **A single ingress point** — one place for auth, rate limiting, and security
  (connects to the gateway in Communication).

```
   clients ─► reverse proxy ─► [ server A ]
              (LB / gateway    [ server B ]
               / CDN)          [ server C ]
```

> The staff move: "Whenever I put a load balancer, gateway, or CDN in a design, I'm
> adding a reverse proxy — an intermediary in front of the servers that gives me one
> ingress point for TLS termination, load balancing, caching, and security, while
> hiding the backend topology so I can scale or replace instances without clients
> noticing."

---

## NAT — Network Address Translation

NAT maps addresses between a private network and the public internet. It exists for two
reasons: **IPv4 exhaustion** (let many private hosts share one public IP) and **security**
(private hosts aren't directly reachable from outside).

### How it works
When a host on a private network (`10.0.2.5`) makes an outbound request, the NAT
device rewrites the packet's source to its own **public** IP and remembers the mapping.
Replies come back to the public IP, and NAT translates them back to the private host.
Many internal hosts thus share one public address.

- **Source NAT (SNAT):** rewrites the source on the way out — the common "let private
  hosts reach the internet" case.
- **Destination NAT (DNAT) / port forwarding:** rewrites the destination on the way in
  — used to expose a specific internal service.

### Why it matters in system design
- **NAT gateway:** in a cloud VPC, hosts in **private subnets** have no public IP, so
  they reach the internet *outbound* (to pull updates, call external APIs) through a
  **NAT gateway** in the public subnet. Inbound is still blocked — they can start
  connections out, but nothing can start a connection in.
- **Security by default:** because private hosts aren't publicly addressable, NAT is an
  implicit inbound firewall. Combined with private subnets, it's why your database
  can call out for a patch but can't be reached from the internet.

> "Backends live in private subnets with no public IP. They reach out through a NAT
> gateway — so they can pull dependencies or call third-party APIs — but nothing on the
> internet can initiate a connection to them. Inbound comes only through the reverse
> proxy / load balancer. That asymmetry is a security feature."

---

## The Summary

- **Forward proxy** = client-side intermediary (egress control, filtering, anonymity).
- **Reverse proxy** = server-side intermediary — and your **LB, gateway, and CDN are
  reverse proxies**: one ingress for TLS termination, balancing, caching, security,
  and topology hiding.
- **NAT** maps private↔public addresses: lets many private hosts share one public IP
  (IPv4 exhaustion) and keeps them unreachable inbound (security). A **NAT gateway**
  gives private-subnet backends outbound-only internet access.
