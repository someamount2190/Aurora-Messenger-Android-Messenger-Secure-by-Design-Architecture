# Project status

Documentation reviewed: 2026-10-28 against the working tree. **This copy has no version
control** — the previously cited revision `9ebea7b` cannot be resolved here, and no CI result
can be attributed to the current tree.
Aurora is pre-release (`0.3.0-pre` plus unreleased changes), with no independent security audit.

## Implemented on main

- Hybrid post-quantum identity and signatures, forward-secret PQXDH pairing, and the
  X-Wing KEM ratchet (ML-KEM-768 + X25519) for messages and call signaling.
- One-to-one messaging, encrypted media, WebRTC voice/video, direct transports, and an
  authenticated blind WebSocket relay with bounded in-memory buffering. **The relay is opt-in
  and off by default**; discovery, address lookup and wake-ups are separate signalling paths
  and keep working when it is disabled.
- SQLCipher storage, Keystore-wrapped keys, encrypted backups, app lock, decoy PIN,
  optional duress wipe, and release screenshot protection.
- Hardening: in-memory decrypted-media sharing, incoming URI validation, call-answer
  tokens, unknown-node lookup padding in the standalone server, additional reserved
  relay-target restrictions, and empty Android task affinity.
- Group data layer: five tables (`groups`, `group_members`, `group_membership_events`,
  `group_messages`, `group_deliveries`), with message rows and membership events. **Not
  reachable from the UI** — nothing injects `GroupSealer`, so no group flow runs in a shipped
  build.

## Validation baseline

The last recorded automated run is 161 crypto + **233** app + 18 server tests (**412** total).
The app figure is from 2026-10-29, after the media-gate (outbound and inbound) and app-lock changes; the crypto and
server figures are the last recorded runs. Crypto fuzzing and emulator checks supplement these suites; see
[TEST_STATUS.md](TEST_STATUS.md) for their scope and remaining gaps. These results do not
establish that the protocol is secure against every adversary.

## Remaining implementation and validation

| Area | Current state | Next step |
|---|---|---|
| Android 16 KB pages | SQLCipher **4.9.0** (16 KB-ready per its release notes) and AGP **8.3.2** remain pinned; ELF/APK alignment is unproven | Upgrade AGP, inspect ELF and APK alignment, test on a 16 KB environment, and prove an existing encrypted database survives an upgrade |
| Custom server trust | Server/developer controls and custom-server HTTPS enforcement still need refactoring | Make custom-server selection explicit and review trust, pinning, and debug/release behavior |
| ShadowMesh relay | Additional reserved-target restrictions are shipped. The peer table is seeded from `/find` decoy candidates and cleared at every launch | Review paired-peer authentication and remaining SSRF boundaries; this is distinct from the authenticated rendezvous WebSocket relay |
| Obscured touches | Overlay/tapjacking handling remains open | Validate it together with Aurora's own floating call overlay |
| Pairing verification | Mutual **eight-digit** SAS, about 26.6 bits of entropy | Review resistance to repeated active handshake attempts and a longer code or commitment scheme |
| Physical devices | Recent checks include emulators | Validate fresh pairing, calls, relay delivery, upgrades, and mobile-data behavior on two real phones |
| Deployment | App and server must use matching protocols | Deploy and verify rendezvous/TLS; configure TURN for relay-only calls; confirm availability operationally |

## Recorded decisions

- **The relay is off for every install, including upgrades.** Explicit opt-in means no device
  relays without the user switching it on. Keying the default off `KEY_ONBOARDING_DONE` was
  considered and rejected. (`AuroraSettings.relayEnabled`, default `false`.)
- **Both relay directions are gated.** Outbound `send` and inbound `resume` both check the
  switch, so an opted-out device neither sends through the relay nor collects frames relayed by
  a peer that has it on. The peer's message stays pending instead of being silently delivered.
- **The legal copy was updated on 2026-10-29** (`legal/LegalDocs.kt`): the relay is described as
  optional and off by default, nodeId↔IP co-storage is disclosed, relay buffering is disclosed,
  and ShadowMesh is described as an experimental single-hop forwarder rather than a working
  decentralized network.

## Metadata privacy roadmap

The server sees IP addresses, node identifiers, and signalling relationships while processing
traffic. **It sees message metadata — sender, recipient, time, size — only when the relay is
enabled**; with the relay off, message payloads never reach it, though `/tap` and `/wait`
wake-ups still reveal that a node is online. Decoy lookup responses and short-lived memory
reduce exposure but do not make the server blind to the communication graph.

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
- [Structure map](STRUCTURE_MAP.md) — verified file inventory and the drift ledger
- [Relay opt-in change record](CHANGE_RELAY_OPT_IN.md)
