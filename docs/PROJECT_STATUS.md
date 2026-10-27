# Project status

Documentation reviewed: 2026-10-27 against published source revision `9ebea7b`.
Aurora is pre-release (`0.3.0-pre` plus unreleased changes), with no independent security audit.

## Implemented on main

- Hybrid post-quantum identity and signatures, forward-secret PQXDH pairing, and the
  X-Wing KEM ratchet for messages and call signaling.
- One-to-one messaging, encrypted media, WebRTC voice/video, direct transports, and
  authenticated blind WebSocket relay with bounded in-memory buffering.
- SQLCipher storage, Keystore-wrapped keys, encrypted backups, app lock, decoy PIN,
  optional duress wipe, and release screenshot protection.
- Hardening: in-memory decrypted-media sharing, incoming URI validation, call-answer
  tokens, unknown-node lookup padding in the standalone server, additional reserved
  relay-target restrictions, and empty Android task affinity.

## Validation baseline

The recorded automated baseline is 161 crypto + 179 app + 18 server tests (358 total).
GitHub Actions CI for `9ebea7b` passed. Crypto fuzzing and emulator checks supplement
these suites; see [TEST_STATUS.md](TEST_STATUS.md) for their scope and remaining gaps.
These results do not establish that the protocol is secure against every adversary.

## Remaining implementation and validation

| Area | Current state | Next step |
|---|---|---|
| Android 16 KB pages | SQLCipher 4.5.6 and AGP 8.3.2 remain pinned; native-library compatibility is unresolved | Upgrade dependencies, inspect ELF and APK alignment, test on a 16 KB environment, and prove an existing encrypted database survives an upgrade |
| Custom server trust | Server/developer controls and custom-server HTTPS enforcement still need refactoring | Make custom-server selection explicit and review trust, pinning, and debug/release behavior |
| ShadowMesh relay | Additional reserved-target restrictions are shipped | Review paired-peer authentication and remaining SSRF boundaries; this is distinct from the authenticated rendezvous WebSocket relay |
| Obscured touches | Overlay/tapjacking handling remains open | Validate it together with Aurora's own floating call overlay |
| Pairing verification | Mutual six-digit SAS, about 20 bits of entropy | Review resistance to repeated active handshake attempts and a longer code or commitment scheme |
| Physical devices | Recent checks include emulators | Validate fresh pairing, calls, relay delivery, upgrades, and mobile-data behavior on two real phones |
| Deployment | App and server must use matching protocols | Deploy and verify rendezvous/TLS; configure TURN for relay-only calls; confirm availability operationally |

## Metadata privacy roadmap

The server currently sees IP addresses, node identifiers, and signaling/relay relationships
while processing traffic. Decoy lookup responses and short-lived memory reduce exposure
but do not make the server blind to the communication graph.

**Flare** (a contentless, one-time wake signal) and **Waypoint** (an unlinkable rendezvous
point for sealed signaling) are design work, not shipped protocol features. Address
derivation and healing after compromise, watch batching, and IP/timing correlation need
review before implementation. Existing `/tap`, `/wait`, `/signal`, and `/relay/stream`
remain the implemented protocol.

## Documentation map

- [Architecture](ARCHITECTURE.md) and [cryptography specification](CRYPTO_SPEC.md)
- [Rendezvous wire protocol](PROTOCOL_RENDEZVOUS.md) and [server deployment](../rendezvous-server/README.md)
- [Dependencies](DEPENDENCIES.md), [data at rest](DATA_AT_REST.md), and [key management](KEY_MANAGEMENT.md)
- [Threat model](THREAT_MODEL.md), [audit scope](AUDIT_SCOPE.md), and [security reports](../SECURITY.md)
- [Test status](TEST_STATUS.md); [TESTING.md](../TESTING.md) preserves dated historical runs
