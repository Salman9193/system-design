# Network Security

Security at the network layer is about **shrinking and controlling the attack
surface** — deciding what can reach what, encrypting traffic in transit, and never
trusting the network alone. This tab covers the network-level controls; identity and
permissions (who a request is and what they may do) are the Authentication module.
The theme is **defense in depth**: many layers, so one failure isn't fatal.

---

## Firewalls — Controlling What Can Talk

A firewall filters traffic by rules — allow or deny based on source/destination IP,
port, and protocol. It's the most basic network control.

- **Network firewalls** guard a network boundary; **host firewalls** guard a single
  machine.
- In the cloud, this is **security groups** (instance-level, stateful allow rules) and
  **network ACLs** (subnet-level).
- The discipline is **least privilege / default deny:** deny everything, then open only
  the specific ports and sources needed. The load balancer accepts 443 from the
  internet; the app tier accepts traffic *only* from the load balancer; the database
  accepts traffic *only* from the app tier.

```
  internet ──443──► [LB]  ──app port──► [app tier] ──db port──► [database]
            (only 443)     (only from LB)          (only from app tier)
```

Each hop can only be reached by the hop in front of it — a chain of least-privilege
rules.

---

## Private Networks — Isolation by Design

The strongest network control is simply **not being reachable.** Building on IP & Ports:

- Put internet-facing resources (the load balancer) in a **public subnet**; put
  everything else — app servers, databases, caches — in **private subnets** with no
  inbound internet route.
- A **VPC** isolates your whole environment into a private network you control.
- Backends reach out through a **NAT gateway** (outbound only) and are reached only via
  the load balancer (inbound). This asymmetry means a database can pull a patch but
  can't be attacked from the internet.

This is the highest-leverage network-security decision: most of the attack surface
disappears when backends simply aren't publicly addressable.

---

## Encryption in Transit — TLS (recap from Communication)

Traffic on the wire must be encrypted so it can't be read or tampered with in transit:

- **TLS** provides encryption, integrity, and server authentication — "HTTPS" is HTTP
  over TLS. Terminate it at the edge (CDN / load balancer / gateway).
- **mTLS (mutual TLS):** both sides present certificates, so *services* authenticate
  each other — the standard for securing internal service-to-service traffic in a mesh.
- Encrypt **both** external (client↔edge) and internal (service↔service) hops — don't
  assume the internal network is safe (see zero trust below).

---

## VPN — Secure Tunnels Over Public Networks

A VPN creates an encrypted tunnel across the public internet, making two networks (or a
remote user and a network) behave as if directly connected and private:

- **Site-to-site VPN:** link two networks (e.g. on-prem data centre ↔ cloud VPC)
  securely.
- **Remote-access VPN:** let an employee reach internal resources over an encrypted
  tunnel.

It's how you extend a private network's trust boundary over untrusted infrastructure.

---

## DDoS Protection

Distributed denial-of-service attacks try to overwhelm you with traffic. Network-level
defenses:

- **Edge/CDN absorption** — the massive edge fleet soaks up volumetric attacks before
  they reach the origin (a key CDN benefit).
- **Rate limiting** at the edge/gateway (see the Rate Limiter LLD).
- **Scrubbing** — routing suspicious traffic through filters that drop attack packets.

---

## Zero Trust — Don't Trust the Network Alone

The older model trusted anything *inside* the network perimeter. That's fragile: once
an attacker is in, everything is exposed. **Zero trust** flips it — **authenticate and
authorize every request** regardless of where it comes from, and encrypt internal
traffic (mTLS). The network perimeter is one layer, not the whole defense.

This is the bridge to the Authentication module: network controls decide *what can
reach what*; identity controls decide *who the request is and what it may do*. You need
both.

> The staff move: "I minimize the attack surface first — backends in private subnets,
> default-deny firewall rules so each tier only accepts traffic from the tier in front
> of it — then encrypt in transit with TLS externally and mTLS internally. But I don't
> rely on the perimeter: zero trust means every request is authenticated and authorized
> at the app layer too, so a breach of the network doesn't hand over the system.
> Defense in depth — edge, firewall, private network, mTLS, app auth — so no single
> failure is fatal."

---

## The Summary (defense in depth)

Layer the controls so no single failure is catastrophic:

1. **Edge** — CDN/DDoS absorption, TLS termination, rate limiting.
2. **Firewall** — least-privilege allow rules; each tier reachable only by the one in
   front.
3. **Private networks** — backends unreachable inbound; outbound via NAT.
4. **Encryption in transit** — TLS externally, mTLS between services.
5. **App-layer auth** — zero trust: authenticate and authorize every request (→
   Authentication module).

Network security shrinks and hardens the surface; identity secures what's left.
