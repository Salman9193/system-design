# IP & Ports

Two addresses locate any running service on the internet: an **IP address** (which
machine) and a **port** (which service on that machine). Together they're what a
connection is opened to. This tab covers addressing and how it shapes system design —
public vs private networks, subnets, and why your backends should be unreachable from
the internet.

---

## IP Addresses — Which Machine

An IP address identifies a host on a network. Two versions coexist:

- **IPv4** — 32-bit, written as `192.0.2.10`. ~4.3 billion addresses, which the
  internet **exhausted** — the main reason NAT (next tab) exists.
- **IPv6** — 128-bit, written as `2001:db8::1`. A practically unlimited space,
  designed to end exhaustion; adoption is ongoing.

### Public vs private addresses (a design-critical distinction)
Certain IPv4 ranges are **private** — reserved for internal networks and *not routable
on the public internet* (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`). The rest
are **public** — globally reachable.

This split is the backbone of secure architecture: your servers get **private** IPs
and are unreachable from the internet; only a small public-facing edge (a load
balancer or gateway) has a **public** IP. More on this in Network Security.

---

## Ports — Which Service

A single machine runs many services, so a **port** (0–65535) identifies which one a
packet is for. The pair `(IP address : port)` is a **socket**, and a connection is
uniquely identified by the four-tuple `(source IP, source port, dest IP, dest port)`.

- **Well-known ports:** 80 (HTTP), 443 (HTTPS), 53 (DNS), 22 (SSH), 5432 (Postgres).
- **Ephemeral ports:** the OS assigns a temporary high-numbered port to the *client*
  side of each connection, which is how one client makes many simultaneous
  connections.

So "connect to `example.com`" really means: resolve the name to an IP (DNS), then open
a TCP connection to that IP on port 443.

---

## Subnets & CIDR — Dividing a Network

Large networks are split into **subnets** — smaller ranges — for routing efficiency,
isolation, and security. Ranges are written in **CIDR** notation: `10.0.1.0/24` means
the first 24 bits are the network prefix, leaving 8 bits (256 addresses) for hosts.

- A smaller number after the slash = a bigger network (`/16` = 65,536 addresses; `/24`
  = 256).
- Subnets let you separate concerns: a **public subnet** for internet-facing pieces, a
  **private subnet** for backends and databases, each with its own routing and firewall
  rules.

You don't need to do subnet math in an interview, but you should be able to say "I'd
put the load balancer in a public subnet and the app servers and database in private
subnets" and know what that means.

---

## How This Shows Up in System Design

Modern cloud designs are built on this addressing model:

- **VPC (virtual private cloud):** your own isolated private network in the cloud, with
  a private IP range you subdivide into subnets.
- **Public subnet:** holds only internet-facing resources — the load balancer / gateway
  with a public IP.
- **Private subnet:** holds app servers, databases, caches — private IPs, no direct
  internet route in. They reach the internet *outbound* through a NAT gateway (next
  tab) and are reached *inbound* only via the load balancer.
- **Security groups / firewall rules** control which IPs and ports can talk to what
  (Network Security tab).

```
      internet
         │  (public IP)
   ┌─────▼─────────────────────────────┐  VPC (e.g. 10.0.0.0/16)
   │  public subnet  10.0.1.0/24        │
   │     [ load balancer ]              │
   │         │ (private IPs)            │
   │  private subnet 10.0.2.0/24        │
   │     [ app servers ] [ database ]   │  ← no inbound internet route
   └────────────────────────────────────┘
```

> The staff move: "I'd give backends private IPs in private subnets, with only the
> load balancer public. That shrinks the attack surface to one entry point — nothing
> can reach the database directly from the internet — and outbound access from private
> subnets goes through a NAT gateway. Addressing *is* your first security boundary."

---

## The Summary

- **IP** = which machine (IPv4 exhausted → IPv6 and NAT); **public vs private** ranges
  are the basis of secure topology.
- **Port** = which service; a connection is the `(src IP, src port, dst IP, dst port)`
  four-tuple.
- **Subnets/CIDR** split a network for routing, isolation, and security.
- **In practice:** VPC with public subnet (LB only) + private subnets (everything
  else) is the default secure shape — and it's pure IP-addressing discipline.
