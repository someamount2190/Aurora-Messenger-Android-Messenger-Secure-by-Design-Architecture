# Aurora rendezvous server (standalone)

The Phase 8 graduation of the in-app NanoHTTPD rendezvous server
(`com.aura.server.AuroraRendezvousServer`) into a deployable Node.js service.
Byte-for-byte protocol compatible: the same Aurora Messenger app talks to either one.

## What it does (and doesn't)

- Maps `nodeId → ip:port` with a **15-minute TTL** (Privacy Policy §3.2), in memory only.
- `/find/:nodeId` returns exactly 10 candidates — the real one plus padding —
  each carrying the node's own check-in signature. Only the real candidate
  verifies against the requester's stored public key; the other nine seed the
  client's mesh peer table. An observer can't tell which IP is real.
- Relays opaque `/signal` payloads (pairing handshakes, WebRTC SDP/ICE). These
  are end-to-end encrypted by the clients — the server only sees ciphertext.
- **Contentless wake**: `/tap/:nodeId` nudges a backgrounded node, and a node parks an
  authenticated long-poll on `/wait/:nodeId` that resolves on a tap/signal or times out.
  No bytes of content ever flow through the wake path.
- **Forward-secret PQXDH prekeys**: stores and serves signed prekey bundles
  (`/prekeys/:nodeId`) — public keys and signatures only, popping one one-time prekey
  per fetch. No private key material ever touches the server.
- **Blind relay** (`/relay/stream`, WebSocket): carries already-sealed frames between two
  authenticated nodes when no direct path exists, buffering up to 60 s for an offline node.
  Protocol in `docs/PROTOCOL_RENDEZVOUS.md` §9.
- **Never** stores or logs message content. The single startup line is the only
  thing it ever prints. Restart wipes all state.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/checkin` | Ed25519-verified `{nodeId, ip, port, timestamp, ed25519Pub, signature}`; 1/min/node rate limit |
| GET | `/find/:nodeId` | 10 signed candidates; 10/min/IP rate limit |
| POST | `/signal/:nodeId` | queue an encrypted signaling payload (also wakes a parked `/wait`) |
| GET | `/signal/:nodeId` | drain queued payloads (authenticated — only the key holder) |
| POST | `/tap/:nodeId` | contentless wake — nudge a backgrounded node online |
| GET | `/wait/:nodeId` | authenticated long-poll, parked until a tap/signal or timeout |
| POST | `/prekeys/:nodeId` | authenticated publish of a signed PQXDH prekey bundle |
| GET | `/prekeys/:nodeId` | fetch a peer's bundle (pops one one-time prekey per request) |
| GET | `/turn-credentials/:nodeId` | authenticated short-lived TURN credentials (only if `COTURN_SHARED_SECRET` is set) |
| WS | `/relay/stream` | authenticated blind relay of sealed frames |
| GET | `/source` | AGPL Corresponding Source pointer (§13) |

The signed string is `aura-checkin-v1|nodeId|ip|port|timestamp`, identical to
the Kotlin `AuroraRendezvousServer.checkinMessage`.

## Run

```bash
node index.js            # listens on :8080 (override with PORT=...)
```

No dependencies — pure Node standard library (`http`, `crypto`).

## Deploy

1. Provision a small VPS (Iceland or Switzerland recommended for jurisdiction).
2. `PORT=8080 node index.js` under a process manager (systemd / pm2).
3. Front it with a TLS terminator (Caddy gets you auto-HTTPS in two lines).
4. In the app, set **Server Address** to `https://your-domain` and pin the
   certificate (Phase 8 cert-pinning). The in-app server stays available for
   offline/LAN use.

## Quick smoke test

```bash
curl http://localhost:8080/checkin
# {"status":"ok","hint":"POST a signed payload to check in"}
```

## Configuration

| Env var | Default | Effect |
|---|---|---|
| `PORT` / `HOST` | `8080` / `0.0.0.0` | listen address |
| `STRICT_BINDING` | on | `0` accepts check-ins without nodeId↔key binding (legacy clients only) |
| `COTURN_SHARED_SECRET` | *(unset)* | coturn `static-auth-secret`; while unset, `/turn-credentials` returns 404 |
| `TURN_REALM` / `TURN_PORT` | `turn.auroramessenger.com` / `5349` | advertised TURN URIs |
| `SOURCE_URL` | upstream repo | AGPL §13 pointer; set it if you run a modified version |

## Tests

    node --test

Spawns the server on a free port and drives it over HTTP and raw WebSocket frames (Node
built-ins only, no install step). CI runs it on every push.
