# Aurora rendezvous server (standalone)

The Phase 8 graduation of the in-app NanoHTTPD rendezvous server
(`com.aura.server.AuroraRendezvousServer`) into a deployable Node.js service.
Shares the app's check-in and lookup formats. The standalone server additionally implements
wake, TURN credentials, authenticated WebSocket relay, and unknown-node lookup padding;
see [`PROTOCOL_RENDEZVOUS.md`](../docs/PROTOCOL_RENDEZVOUS.md) for in-app differences.

## What it does (and doesn't)

- Maps `nodeId → ip:port` with a **15-minute TTL** (Privacy Policy §3.2), in memory only.
- `/find/:nodeId` returns HTTP 200 and ten shuffled candidates for registered and
  unknown nodes alike. Registered nodes have one real signed record and nine decoys;
  unknown nodes have ten decoys. A caller with the target's verification keys can
  still identify a valid record; the server knows the actual mapping.
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
- Keeps no plaintext message content or persistent message history. Sealed relay frames
  can be buffered temporarily as described above. Two startup lines report the listen
  address and source URL.
  Operators must also configure proxy and hosting logs appropriately. Restart wipes all state.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/checkin` | Ed25519-verified `{nodeId, ip, port, timestamp, ed25519Pub, signature}`; 1/min/node rate limit |
| GET | `/find/:nodeId` | 10 candidates, including all-decoy unknown-node responses; 60/min/IP rate limit |
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

1. Provision a host that supports a long-running Node process, WebSocket upgrades, and
   long-poll requests. Review the host's network and logging configuration.
2. `PORT=8080 node index.js` under a process manager (systemd / pm2).
3. Front it with a TLS terminator (Caddy gets you auto-HTTPS in two lines).
4. In the app, set **Server Address** to `https://your-domain` and pin the
   certificate according to the client trust configuration. Changing the server address
   does not automatically provision a certificate pin. The in-app server supports
   a subset of the standalone protocol for local-network use.

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

Deployment requires a matching app/server protocol. This guide does not confirm that the
public service is currently available. Configure TURN separately for calls when peers
require relay-only connectivity. See [project status](../docs/PROJECT_STATUS.md).
