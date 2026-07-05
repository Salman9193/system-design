# Transport Protocols — TCP, UDP, QUIC

The transport layer moves bytes between two hosts. You almost always inherit this
choice from the protocol above (HTTP → TCP, or HTTP/3 → QUIC), but interviewers open
the box: "why is TCP reliable?", "when would you use UDP?" Know the mechanics.

---

## TCP — Reliable, Ordered, Connection-Oriented

TCP is the default transport for almost everything (HTTP, gRPC, database
connections). It provides a **reliable, ordered byte stream** over an unreliable
network.

**What it gives you:**
- **Connection setup** via a 3-way handshake (SYN, SYN-ACK, ACK) — one round trip
  before any data flows.
- **Reliability** — every byte is acknowledged; lost packets are retransmitted.
- **Ordering** — bytes arrive in the order sent (via sequence numbers).
- **Flow control** — the receiver advertises a window so a fast sender doesn't
  overwhelm a slow receiver.
- **Congestion control** — TCP backs off when the network is congested (slow start,
  congestion avoidance), which is what keeps the internet from collapsing.

**What it costs:**
- **Handshake latency** — a round trip before data (plus another 1–2 for TLS on top).
- **Head-of-line blocking** — because it's one ordered stream, a single lost packet
  stalls *everything* behind it until it's retransmitted, even unrelated data. (This
  is the flaw HTTP/2 hit and HTTP/3 fixed by leaving TCP — see Application Protocols.)
- **Per-connection state** — servers track state for every connection; connection
  count is a real capacity dimension.

> The staff move: "TCP gives me reliability and ordering for free, at the cost of
> handshake latency and head-of-line blocking. For a typical request/response service
> that's exactly the trade I want, so TCP is the default — I only leave it when the
> latency or the head-of-line cost actually hurts."

---

## UDP — Fast, Connectionless, Best-Effort

UDP is a thin wrapper over IP: **send a datagram and hope it arrives**. No handshake,
no acknowledgments, no ordering, no congestion control.

**What it gives you:**
- **No setup** — fire immediately, no round trip.
- **Low overhead** — tiny header, no per-connection state.
- **No head-of-line blocking** — datagrams are independent; a lost one doesn't stall
  others.

**What it costs:**
- **No reliability** — packets can be lost, duplicated, or reordered. If you need
  reliability, you build it yourself on top.

**When UDP wins — when timeliness beats completeness:**
- **Real-time media** (voice/video): a dropped audio packet should be *skipped*, not
  retransmitted — a late packet is useless, and you'd rather have a tiny glitch than a
  stall.
- **Gaming**: the latest position matters; a stale retransmitted one doesn't.
- **DNS**: a single small request/response — cheaper as one UDP round trip than a TCP
  handshake.
- **Telemetry/metrics** at high volume where losing a few samples is fine.

> "UDP is the right call when a late packet is worthless — live audio, gaming,
> DNS. You give up reliability and ordering, so if you need those you either build
> them on top or you shouldn't be using UDP."

---

## QUIC — TCP's Reliability Without Its Flaws

QUIC is a newer transport built **on top of UDP** that reimplements reliability,
ordering, and congestion control in user space, while fixing TCP's two big problems.
It's the transport under **HTTP/3**.

**What it fixes:**
- **No head-of-line blocking across streams** — QUIC carries multiple independent
  streams, so a lost packet only stalls *its own* stream, not the others. (HTTP/2
  multiplexed streams over one TCP connection and still suffered TCP-level head-of-
  line blocking; QUIC solves it at the transport.)
- **Faster connection setup** — combines the transport and TLS handshakes, so
  connections establish in fewer round trips (often 1-RTT, and 0-RTT for resumed
  connections).
- **Connection migration** — a connection is identified by an ID, not the IP/port
  tuple, so it survives a network change (Wi-Fi → cellular) without re-establishing.

**What it costs:**
- Newer, more CPU (user-space, encryption-heavy); some networks/middleboxes handle it
  worse than TCP. But it's now widely deployed for web traffic.

> "QUIC is why HTTP/3 is faster on lossy/mobile networks: it keeps TCP's reliability
> but removes cross-stream head-of-line blocking and cuts handshake round trips by
> folding in TLS. The trade is more CPU and being newer than rock-solid TCP."

---

## The Decision

| Need | Transport |
|------|-----------|
| Reliable, ordered request/response (the default) | **TCP** |
| Timeliness over completeness (media, gaming, DNS) | **UDP** |
| TCP's reliability but lower latency + no cross-stream HOL (modern web, mobile) | **QUIC / HTTP/3** |

The one-liner: **TCP by default; UDP when a late packet is useless; QUIC when you
want TCP's guarantees without its handshake and head-of-line cost.** In most system
designs you'll say "HTTP over TCP" and move on — the value is being able to justify it
and to know when HTTP/3-over-QUIC earns its place.
