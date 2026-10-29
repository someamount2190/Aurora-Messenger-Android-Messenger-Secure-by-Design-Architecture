# Audit Scope & Self-Assessment

This document orients an independent reviewer: what Aurora is, what it claims, what it
deliberately does **not** claim, the assumptions it rests on, and the residual risks we
already know about. It is the map; the territory is the source and the rest of `docs/`.

> Status: Aurora is **pre-alpha** and has **not** had an independent third-party security
> audit. Do not rely on it for high-risk threat models until it has.

## How to read this package

| Start here | For |
|---|---|
| [`README.md`](../README.md) | Overview, security claims/non-claims, primitives table |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | Components, trust boundaries, diagrams |
| [`docs/CRYPTO_SPEC.md`](CRYPTO_SPEC.md) | Primitives, formats, handshake, ratchet, SAS |
| [`docs/PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md) | Rendezvous wire protocol |
| [`docs/THREAT_MODEL.md`](THREAT_MODEL.md) | STRIDE + LINDDUN threat model (threat IDs) |
| [`docs/KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md) · [`docs/DATA_AT_REST.md`](DATA_AT_REST.md) | Key lifecycle & at-rest data |
| [`docs/DEPENDENCIES.md`](DEPENDENCIES.md) · [`docs/BUILD_REPRODUCIBILITY.md`](BUILD_REPRODUCIBILITY.md) | Supply chain & build |
| [`docs/SOURCE_DECOMPOSITION.md`](SOURCE_DECOMPOSITION.md) | Structural-health pass: large mixed-concern files & decomposition plan |
| [`docs/CRYPTO_TEST_VECTORS.md`](CRYPTO_TEST_VECTORS.md) | Per-primitive test-vector / KAT sources & applicability |
| [`docs/CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md) | Locked plan: replace hand-rolled crypto with libraries, move to FIPS PQC + X-Wing |
| [`docs/STRUCTURE_MAP.md`](STRUCTURE_MAP.md) | Verified file inventory and the documentation drift ledger |
| [`docs/CHANGE_RELAY_OPT_IN.md`](CHANGE_RELAY_OPT_IN.md) | Relay opt-in change record: audit table, behaviour matrix, known gaps |
| [`SECURITY.md`](../SECURITY.md) | Disclosure policy |

## System under review

- **Android client** (`app/`) — Kotlin, Jetpack Compose, Hilt, Room/SQLCipher.
- **Cryptographic core** (`crypto/`, `com.aura:aura-crypto`) — Android-free, hybrid PQ.
- **Rendezvous server** — standalone Node.js (`rendezvous-server/`) and an embeddable
  in-app server (`com.aura.server`); the hosted instance is `api.auroramessenger.com`.

**Pin the audit to a specific tag/commit.** Versions move; cite the `versionName` and
git tag (e.g. `v0.2.2-pre`) under review so findings map to exact source.

## In scope
Cryptographic core; pairing/verification; rendezvous protocol & servers; at-rest
protection; app hardening; build/supply-chain. (See [`SECURITY.md`](../SECURITY.md) for the
report-eligibility version of this list.)

## Out of scope (non-goals — by design, not oversight)
- **Endpoint security.** A compromised, rooted, or malware-infected device defeats E2E
  encryption. Aurora protects data *in transit* and *at rest*, not a hostile OS.
- **Anonymity / unobservability.** Aurora hides message *content* and *identities*, not the
  fact that you run Aurora or your IP from the rendezvous. No onion routing / traffic-analysis
  resistance in the default path.
- **Availability.** The free single-instance rendezvous offers no DoS guarantees.
- **Groups / multi-device.** Aurora is one device per identity. Group tables and tests exist
  (`com.aura.group`, five tables) but no group flow is reachable from the shipped UI, so group
  messaging is outside the audit surface.

## Assumptions
- The Android Keystore / TEE provides a trustworthy hardware-backed master key.
- The user verifies the SAS code out-of-band (in person or over a trusted channel) — this
  is what upgrades QR pairing from TOFU to MITM-resistant.
- The underlying primitives (ML-KEM-768 & X25519 via **X-Wing**, ML-DSA-65, Ed25519,
  XChaCha20-Poly1305, SHA-3, Argon2id) are secure at their chosen parameter sets, and their
  implementations (**Bouncy Castle** PQC, **Google Tink**, SQLCipher) are correct. The stack
  is pure-JVM — nothing links liboqs (the unused jar is still vendored; see
  [`CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md)).
  Caveat: BouncyCastle's X-Wing tracks an in-progress IETF draft (-07), not a finalized standard.
- TLS + certificate pinning protect the rendezvous transport against a rogue CA.

## Known limitations & residual risks
- **The KEM Double Ratchet is bespoke protocol crypto pending dedicated review.**
  Post-compromise security ("healing") is now implemented: the live message ratchet is a
  post-quantum (X-Wing) KEM Double Ratchet (Phase 5 of
  [`CRYPTO_MIGRATION_PLAN.md`](CRYPTO_MIGRATION_PLAN.md), specified in
  [`PQ_RATCHET_DESIGN.md`](PQ_RATCHET_DESIGN.md)), and the old symmetric ratchet has been
  retired. It follows the well-studied "Double Ratchet with a KEM step" pattern and passes
  its adversarial test suite, but it has not had dedicated external review — treat the
  healing guarantee as provisional until it has.
- ~~**Legacy handshake fallback.**~~ **Closed.** The identity-only (no-forward-secrecy)
  fallback has been removed: pairing now requires a verified PQXDH prekey bundle and fails
  closed otherwise, so a network attacker can no longer suppress the bundle to strip forward
  secrecy.
- **Rendezvous learns reachability.** It maps `nodeId → address` (15-min TTL, in-memory,
  no logs) and observes that a device is online; disclosed in the Privacy Policy.
- **Direct-P2P over NAT.** Messages flow peer-to-peer. Two peers both behind carrier-grade NAT
  have no direct path: with the **opt-in blind relay** enabled (off by default) messages fall
  back to it; with it off the message stays pending and the UI reports no permitted path.
  Reliability for two cellular peers is unverified. (ShadowMesh, a separate dormant scaffold,
  is not this relay and is not a fallback.)
- **Relay metadata.** When the relay is enabled the operator sees sender, recipient, timing and
  frame size for relayed messages (never content). The switch is client-side
  (`AuroraSettings.relayEnabled`); the server always offers the endpoint.
- **Media IP-exposure.** Media has no relay path, but both directions consult
  `PeerIpExposurePolicy` through `IpExposureGate` (`MediaTransfer.kt`): a relay-only contact
  cannot learn our IP from a media send, and an inbound direct TCP media frame is refused
  before the payload is read. The UI reports no permitted path. Inbound media over
  TURN-relayed RTC remains permitted, because inbound RTC sessions are created with
  `ipGate.relayOnly(from)` and therefore use TURN-only ICE. Inbound **text** over direct TCP
  is not yet gated; it is a documented follow-up.
- **App lock.** Removing or changing the lock requires the current real PIN (`AppLock`);
  `SecureWipe` uses an internal unguarded path because it runs after key destruction.
- **Dormant code:** the "Host & show my code" LAN-beacon path (`host=true`) exists in
  source but is **not reachable from the shipped UI**; the only live "Show my code" is the
  plain shared-server code. (README reconciliation tracked for Phase F.)
- **Build reproducibility** is not yet established (see `BUILD_REPRODUCIBILITY.md`).
- **No independent audit** has been performed.

## Prior validation (not a substitute for audit)
- JVM/Robolectric unit tests (see [`docs/TEST_ARCHITECTURE.md`](TEST_ARCHITECTURE.md),
  [`docs/TEST_STATUS.md`](TEST_STATUS.md)). **There is no instrumented `androidTest` suite** —
  `app/src/androidTest` is empty — and no emulator job in CI.
- Emulator soak (process/FGS survival, no leaks, no app crashes) and a live **real-phone ↔
  emulator pairing** test confirming the cross-network pairing path over the production
  rendezvous.
