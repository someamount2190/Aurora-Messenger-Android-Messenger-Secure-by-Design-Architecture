# Rendezvous Wire Protocol

The rendezvous server only maps a short-lived `nodeId → address` so peers can find each
other and wake a sleeping device. It is **in-memory only** (restart wipes all state),
**logs no payloads/IPs/nodeIds**, and is never in the path of message content. This spec
describes the canonical **standalone Node.js server** (`rendezvous-server/index.js`,
deployed at `api.auroramessenger.com`); the embeddable in-app server (`com.aura.server`,
NanoHTTPD) is byte-compatible with differences noted in §7.

Transport: HTTPS with **certificate pinning** in the client (a rogue CA cannot intercept).
All bodies are JSON unless noted. All signed strings are UTF-8 with `|` delimiters.

## 1. Endpoints

| Method · Path | Auth | Purpose | Response |
|---|---|---|---|
| `POST /checkin` | signed body | Register `nodeId → ip:port`, 15-min TTL | `{status:"registered", ttlSeconds:900}` |
| `GET /checkin` | none | Liveness hint | `{status:"ok", hint:...}` |
| `GET /find/:nodeId` | none | Return **10** candidates (1 real + 9 decoys, shuffled) | `{candidates:[{ip,port,timestamp,signature}×10]}` |
| `POST /signal/:nodeId` | none | Enqueue one opaque (E2E) payload; wakes a parked `/wait` | `{status:"queued"}` |
| `GET /signal/:nodeId` | drain headers | Drain queued payloads | `{payloads:[...]}` |
| `POST /tap/:nodeId` | none | Contentless wake | `{status:"tapped"}` |
| `GET /wait/:nodeId` | drain headers | Long-poll; returns on tap/signal or after 25 s | `{wake:true}` / `{wake:false}` |
| `POST /prekeys/:nodeId` | drain headers | Publish signed PQXDH bundle (public only); body `{spk, opks, signPub}` — `signPub` required | `{status:"stored"}` |
| `GET /prekeys/:nodeId` | none | Fetch bundle; **pops one OPK** | `{spk:{...}, opk:{...}\|null, signPub}` |
| `GET /turn-credentials/:nodeId` | drain headers | Short-lived TURN credentials (coturn REST, 900 s); **404 unless `COTURN_SHARED_SECRET` is set** | `{username, password, ttlSeconds, uris}` |
| `WS /relay/stream` | challenge-signed (§9) | Blind relay of sealed frames between nodes | see §9 |
| `GET /health` | none | Liveness probe (no identifiers); CORS `*` | `{status:"ok", uptimeSeconds, since}` |
| `GET /source` | none | AGPL-3.0 §13 source pointer | `{source, license:"AGPL-3.0-or-later"}` |

## 2. Signed messages

Only three canonical strings are signed *for the server* (pairing messages are opaque
`/signal` payloads — see [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md) §5):

| Message | Canonical string | Signed by | Server verifies |
|---|---|---|---|
| check-in | `aura-checkin-v1\|<nodeId>\|<ip>\|<port>\|<timestamp>` | hybrid (ML-DSA-65 + Ed25519) | **Ed25519 half only** (server has no ML-DSA; reads the PQ length dynamically); peers verify the full hybrid at `/find` time |
| drain / wait / prekeys / turn auth | `aura-drain-v1\|<nodeId>\|<ts>` | Ed25519 | Ed25519, via headers |
| relay connection | `aura-relay-v1\|<nodeId>\|<nonce>` | Ed25519 | Ed25519, against the checked-in key (§9) |

**Drain auth headers** (`GET /signal`, `GET /wait`, `POST /prekeys`):
`X-Drain-Ts` (ms), `X-Drain-Pub` (b64 Ed25519 pub — must equal the key that checked in),
`X-Drain-Sig` (b64 Ed25519 sig over the drain string). Freshness window: **±5 min**. So only
the key holder can read their own signals or publish their own prekeys.

## 3. The `/find` decoy format

`GET /find/:nodeId` returns exactly **10** candidates `{ip, port, timestamp, signature}`,
**shuffled**:
- **1 real** = the node's stored check-in with its **real hybrid signature** (3361 B).
- **9 decoys** = random RFC-1918-style private IPs, timestamps up to 5 min in the past, and
  **random padding signatures of identical length** (3361 B).

Only the legitimate caller can tell the real one apart: it re-derives
`aura-checkin-v1|nodeId|ip|port|timestamp` per candidate and verifies the hybrid signature
against the target's known keys — only the real candidate verifies. A passive observer of a
`/find` response cannot identify the real address.

## 4. Constants (production / Node.js)

| Constant | Value |
|---|---|
| `TTL_MS` | 900 000 (15 min) |
| `CHECKIN_FRESHNESS_MS` | 300 000 (5 min) |
| `CHECKIN_RATE_LIMIT_MS` | 60 000 (1/min per nodeId) |
| `FIND_RATE_LIMIT_PER_MIN` | 60 per IP |
| `SIGNAL_POST_RATE_LIMIT_PER_MIN` | 30 per IP |
| `TAP_RATE_LIMIT_PER_MIN` | 30 per IP |
| `MAX_QUEUE_LEN` | 64 per node (further posts rejected with 429; also caps relay frames buffered per node) |
| `PER_SENDER_QUEUE_MAX` | 8 queued signals per source IP per node |
| `CANDIDATE_COUNT` | 10 |
| `MAX_BODY` | 65 536 B (64 KiB) |
| `WAIT_HOLD_MS` | 25 000 (under typical 60 s proxy read timeouts) |
| `PREKEY_OPK_MAX` | 100 per node |
| `RELAY_COMMIT_TIMEOUT_MS` | 15 000 (recipient must ack a live delivery) |
| `RELAY_BUFFER_TTL_MS` | 60 000 (frames held for an offline node) |
| `RELAY_AUTH_TIMEOUT_MS` | 10 000 (unauthenticated sockets dropped) |
| `RELAY_MAX_INFLIGHT_PER_SOCKET` / `RELAY_MSG_RATE_PER_MIN` / `RELAY_MAX_SOCKETS_PER_IP` | 256 / 600 / 16 |
| `HYBRID_SIG_BYTES` | 3361 (4 + 3293 + 64) |
| `PORT` / `HOST` defaults | 8080 / `0.0.0.0` (production drop-in pins `127.0.0.1`, fronted by nginx) |
| `STRICT_BINDING` | env `=1` to require nodeId↔key binding on every check-in |

## 5. Identity binding (STRICT_BINDING)

A check-in may carry `kemPub` + `dilithiumPub`. If present, the server enforces
`nodeId == SHA3-256(kemPub ‖ signPub)` and rejects mismatches (HTTP 401). With
`STRICT_BINDING=1` (set in production), a check-in **without** the binding keys is rejected,
fully closing nodeId squatting. The in-app server is always strict.

## 6. Wake (`/tap` + `/wait`) long-poll

```mermaid
sequenceDiagram
  participant B as Backgrounded device B
  participant R as Rendezvous
  participant A as Peer A
  B->>R: GET /wait/B (authed) — parked up to 25s
  A->>R: POST /tap/B  (or POST /signal/B)
  R-->>B: {wake:true}
  B->>R: GET /signal/B (authed) — drain
  B-->>A: re-park /wait, then direct P2P
  Note over R: on timeout R returns {wake:false}; B re-parks
```

This contentless wake replaces FCM/third-party push: no message content ever touches a push
provider. The honest cost — the server can tell a device is reachable — is disclosed in the
Privacy Policy and analyzed in [`THREAT_MODEL.md`](THREAT_MODEL.md).

## 7. In-app server (`com.aura.server`) differences
Used for local-network / host mode. Byte-compatible check-in/find/drain, but: `/find` rate
limit **10**/min (vs 60), **always** strict binding, and **no** `/tap`, `/wait`, `/health`,
`/source`, `/turn-credentials` or `/relay/stream` (clients fall back to direct paths). (The host-beacon UI that used it is currently dormant — see
[`AUDIT_SCOPE.md`](AUDIT_SCOPE.md).)

## 8. Logging
The server emits exactly two `console.log` lines at startup (listen address + AGPL source
URL). No request payloads, IPs, nodeIds, queue contents, or prekey material are ever logged;
nginx access logging for the API host is disabled. State is purely in-memory with a 15-min TTL.

## 9. Blind relay (`/relay/stream`, protocol v2)

The last-resort data path when neither the WebRTC data channel nor direct TCP reaches a
peer (both behind carrier NAT, or the user's IP-exposure setting forbids direct paths). The
payload is a frame already sealed by the KEM ratchet; the server routes on nodeIds only.
JSON text frames over a WebSocket (clients must mask; fragmented and binary frames close the
connection; max frame 128 KiB):

| Direction | Message | Meaning |
|---|---|---|
| S→C | `CHALLENGE {nonce}` | sent on connect (32 random bytes, hex) |
| C→S | `AUTH {nodeId, pub, sig}` | `sig` = Ed25519 over `aura-relay-v1\|<nodeId>\|<nonce>`; `pub` must equal the key that checked in |
| S→C | `AUTH_OK {}` / `AUTH_FAIL {}` | AUTH_OK is followed by any frames buffered for this node |
| C→S | `RELAY {to, ref, payload}` | `ref` is the sender's own id (the message id), echoed in replies |
| S→C | `INBOUND {ticket, from, payload}` | to the recipient; `from` is the authenticated sender |
| S→C | `QUEUED {ref}` | recipient offline: frame buffered (60 s) and the recipient woken (`/wait`) |
| C→S | `ACK {ticket, response}` | recipient's inbox result: its `{"t":"ack","id"}` frame, or `null` if it couldn't process it |
| S→C | `RESULT {ref, response}` | the recipient's response, to the sender |
| S→C | `NACK {ref, error}` | `TIMEOUT` (no ack in 15 s), `BUSY` (caps hit), `BAD_REQUEST` |

Nothing but `AUTH` is accepted before authentication. Tickets are 128-bit random and only the
node a ticket was addressed to can ack it, so a third party can neither receive another node's
frames nor fake their delivery. The client drops an `INBOUND` whose sealed frame names a
different sender than `from`. A frame delivered but not acked before the recipient disconnects
is re-buffered; the recipient de-duplicates by message id. The newest authenticated connection
for a nodeId replaces any older one.

What the relay still learns: who is talking to whom and when, and frame sizes (the same as the
`/signal` path). A malicious relay could drop frames or forge a `RESULT`; a forged ack makes the
sender mark a message delivered that wasn't — the same exposure as an on-path attacker on the
TCP route, recorded in [`THREAT_MODEL.md`](THREAT_MODEL.md).
