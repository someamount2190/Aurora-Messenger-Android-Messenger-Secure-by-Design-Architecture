# Threat Model (STRIDE + LINDDUN)

Formal threat model for Aurora Messenger. Security threats follow **STRIDE**; privacy/metadata
threats follow **LINDDUN**. Every threat has a stable ID (e.g. `T-PAIR-01`, `P-LINK-01`) so
audit findings can reference it. Read alongside [`ARCHITECTURE.md`](ARCHITECTURE.md) (the DFD
this walks), [`CRYPTO_SPEC.md`](CRYPTO_SPEC.md), and [`PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md).

## 1. System description
Two Android devices exchange messages and calls **directly (P2P)**, end-to-end encrypted. A
near-zero-knowledge **rendezvous** maps `nodeId → address` and relays contentless wakes and
opaque signaling. A **blind relay** can also carry sealed message frames, but it is an opt-in
transport, **off by default**; when enabled the server is in the path of message metadata (never
content). See the DFD in [`ARCHITECTURE.md`](ARCHITECTURE.md) §2.

## 2. Assets

| ID | Asset | Why it matters |
|---|---|---|
| AS-1 | Message & call **content** | The core secret to protect, in transit and at rest |
| AS-2 | **Identity private keys** (KEM + signing) | Compromise enables impersonation / decryption |
| AS-3 | **Session/ratchet & prekey state** | Compromise enables decryption of a session |
| AS-4 | **Contact graph** (who talks to whom) | Sensitive metadata |
| AS-5 | **Reachability metadata** (device online, IP) | Observable; minimized but not zero |
| AS-6 | **At-rest data** (DB, media, backups) | Target of device theft |
| AS-7 | **Availability** of pairing/wake | Best-effort; explicit non-goal for guarantees |

## 3. Adversary model

| ID | Adversary | Capabilities | In scope |
|---|---|---|---|
| ADV-1 | Passive network | Observe all traffic | Yes |
| ADV-2 | Active network / MITM | Intercept, modify, inject, replay; rogue CA | Yes |
| ADV-3 | Malicious / compromised rendezvous operator | Full control of the server and its memory | Yes (content must stay safe) |
| ADV-4 | Malicious peer | A contact you paired with | Partial (they see what you send them) |
| ADV-5 | Device thief / coercer | Physical access to a locked device; coercion | Partial (see hardening) |
| ADV-6 | Future quantum adversary | Harvest-now-decrypt-later + quantum computer | Yes |
| ADV-7 | Supply-chain attacker | Tamper with deps/build/distribution | Partial (see T-SUPPLY) |
| ADV-X | **On-device malware / rooted OS** | Read process memory, keystrokes | **Out of scope (non-goal)** |

## 4. Trust boundaries & assumptions
- Each **device** is trusted to its own user; plaintext and private keys live there.
- The **rendezvous operator is untrusted for content** (ADV-3) but relied on for liveness.
- The **network is hostile** (ADV-1/2) everywhere.
- **Assumptions:** Android Keystore/TEE is sound; the user verifies the **SAS out-of-band**;
  the underlying primitives and their libraries (Bouncy Castle, Google Tink, SQLCipher) are correct
  at their parameter sets.

## 5. STRIDE

### Network & transport (data plane)
| ID | Threat (STRIDE) | Mitigation | Residual |
|---|---|---|---|
| T-NET-01 | (I) Eavesdrop message content | E2E XChaCha20-Poly1305 under ratchet keys; hybrid PQ+classical KEM (`CRYPTO_SPEC` §1,6) | Endpoint compromise (ADV-X) |
| T-NET-02 | (T) Tamper/forge messages | AEAD integrity + per-message keys; hybrid-signed handshake | — |
| T-NET-03 | (S) MITM the session | PQXDH transcript-bound root + **mutual SAS** (`CRYPTO_SPEC` §5,7) | User skips/!verifies SAS |
| T-NET-04 | (I) Harvest-now-decrypt-later | Hybrid PQ KEM; FS ratchet; OPK destroyed on use | Legacy-fallback sessions (T-PAIR-03) |
| T-NET-05 | (R) Replay frames | Ratchet counters + skipped-key caps (512 ahead / 1024 stored) | — |

### Rendezvous (control plane / ADV-3)
| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| T-RZV-01 | (I) Server reads content | Server only sees `nodeId→addr` + opaque payloads; content E2E; in-memory, no logs (`PROTOCOL` §8) | Reachability metadata (P-DET-01) |
| T-RZV-02 | (S) Spoof a node / squat nodeId | `nodeId = SHA3-256(keys)` + STRICT_BINDING enforcement (`PROTOCOL` §5) | — |
| T-RZV-03 | (T) Forge/inject a peer's address | `/find` candidates carry hybrid sigs; only the real one verifies to the holder (`PROTOCOL` §3) | — |
| T-RZV-04 | (I) Drain another node's signals | Authed drain/wait (`X-Drain-*`, Ed25519, ±5 min) (`PROTOCOL` §2) | — |
| T-RZV-05 | (S) Rogue CA intercepts TLS | Certificate pinning (+ backup pin) in client | Pin mismanagement |
| T-RZV-06 | (D) Flood / abuse | Per-IP rate limits; bounded queue; body caps (`PROTOCOL` §4) | Single-instance DoS (non-goal) |
| T-RZV-07 | (I) Identify real address from `/find` | 9 length-matched signed decoys, shuffled (`PROTOCOL` §3) | Active correlation by ADV-3 |
| T-RZV-08 | (S/I) Receive another node's relayed frames | Relay sockets answer a fresh nonce with an Ed25519 signature under the checked-in key; only the addressed node can ack a ticket (`PROTOCOL` §9) | — |
| T-RZV-09 | (T) Relay fakes delivery / drops frames | Ack is the recipient's own inbox response; client drops frames whose `from` disagrees with the relay | A malicious relay can still forge an ack or drop frames (same as an on-path attacker on TCP) |
| T-RZV-10 | (D) Crash or exhaust the relay | Per-connection error isolation; frame/buffer/in-flight/rate/socket caps; unauthenticated sockets dropped in 10 s (`PROTOCOL` §4) | Single-instance DoS (non-goal) |
| T-RZV-11 | (I) Peer learns our IP | User setting: direct for any / verified-only / never; "never" uses relay-only ICE + the blind relay, which must be enabled for that policy to deliver | The rendezvous still sees our IP; relay-only calls need TURN; the relay is off by default |

### Pairing
| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| T-PAIR-01 | (S) MITM during QR pairing | Mutual SAS; codes derived locally, never sent; MITM → mismatched roots | SAS not checked out-of-band |
| T-PAIR-02 | (T) Tamper prekey bundle / substitute keys | Bundle's hybrid signing key must hash (with the QR's KEM key) to the scanned nodeId; each prekey hybrid-signed (ML-DSA-65 + Ed25519), both halves verified (`CRYPTO_SPEC` §8); transcript-bound root | — |
| T-PAIR-03 | (I) Downgrade to no-FS legacy handshake | No legacy handshake: a missing or unverifiable bundle aborts pairing (fail closed) | Suppressing prekeys blocks pairing (DoS) |
| T-PAIR-04 | (S) OPK reuse / stale code | OPK deleted on consume; missing OPK → `pairreject` (fail closed) | — |

### Cryptography
| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| T-CRY-01 | (I) One primitive broken (classical or PQ) | Hybrid everywhere; both must break (`CRYPTO_SPEC` §1) | Both broken |
| T-CRY-02 | (T) Cross-protocol / context confusion | Domain-separated KDF labels per use (`CRYPTO_SPEC` §2) | — |
| T-CRY-03 | (I) Nonce reuse (XChaCha20) | 192-bit nonces; per-message keys | RNG failure |

### At rest / device (ADV-5)
| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| T-STORE-01 | (I) Read DB from a stolen device | SQLCipher (AES-256); key under Keystore master key | Unlocked device |
| T-STORE-02 | (I) Recover "deleted" data | Cryptographic erase (destroy keys, not bytes) | — |
| T-STORE-03 | (S) Shoulder-surf / coercion | App lock, **decoy PIN**, optional **duress wipe**, `FLAG_SECURE` | Sophisticated forensics on unlocked device |
| T-STORE-04 | (I) Plaintext media on disk | Media decrypted in-memory; encrypted at rest | — |

### Supply chain (ADV-7)
| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| T-SUPPLY-01 | (T) Tampered dependency | Pinned versions; vendored `libs/maven`; SBOM (`DEPENDENCIES.md`) | Upstream compromise pre-pin |
| T-SUPPLY-02 | (T) Tampered released APK | Signed release; documented cert fingerprint (`BUILD_REPRODUCIBILITY.md`) | Repro build not yet established |
| T-SUPPLY-03 | (E) Modified server hides source | AGPL §13 `/source` endpoint | Operator non-compliance |

## 6. LINDDUN (privacy / metadata)

| ID | Threat | Mitigation | Residual |
|---|---|---|---|
| P-LINK-01 | **Linkability** of sessions to an identity | No account/phone/email; identity is a key pair | nodeId is stable per install |
| P-IDENT-01 | **Identifiability** of users | No PII collected; pairing in person | IP visible to rendezvous |
| P-NREP-01 | Non-repudiation undermining deniability | (Trade-off) signatures give authenticity, not deniability | Deniable messaging is a non-goal |
| P-DET-01 | **Detectability** that two parties communicate | Content P2P by default; server sees reachability and signalling; decoy `/find`. With the opt-in relay enabled it also sees message sender, recipient, timing and size | Operator/ISP traffic correlation |
| P-DISC-01 | Information **disclosure** of metadata at server | In-memory, 15-min TTL, no logs (`PROTOCOL` §8) | Live memory of a compromised server |
| P-UNAW-01 | User **unawareness** of metadata exposure | Disclosed in Privacy Policy, README, this doc | — |
| P-NC-01 | **Non-compliance** with stated policy | Zero-log design; AGPL source; warrant canary | Trust in operator |

## 7. Residual risks & explicit non-goals
- **Post-compromise "healing" is implemented but review-gated** — the KEM Double Ratchet
  (`CRYPTO_SPEC` §6) mixes fresh X-Wing entropy into the root on each direction change; it is
  bespoke protocol crypto pending dedicated review (see [`AUDIT_SCOPE.md`](AUDIT_SCOPE.md)).
- **Endpoint security is out of scope** (ADV-X): a rooted/malware device defeats E2E.
- **No anonymity / traffic-analysis resistance** in the default path; the rendezvous learns
  reachability + IP (P-DET-01, P-IDENT-01). ShadowMesh is an opt-in scaffold, off by default,
  and is **not** a working anonymity layer.
- **Availability:** single free rendezvous, no DoS guarantees (T-RZV-06).
- **Direct-P2P over NAT:** two peers both behind CGNAT have no direct path. With the relay
  enabled (opt-in, off by default) messages fall back to it; with it off the message stays
  pending and the conversation says so. Calls between them need TURN either way.
- **Prekey suppression blocks pairing** (T-PAIR-03) — it can no longer downgrade it.
- **The blind relay can forge acks or drop frames** (T-RZV-09); it cannot read or alter content.
- **Media has no relay path.** Its outbound TCP fallback now consults the same
  `IpExposureGate` as text (`MediaTransfer.kt`), so a relay-only contact cannot learn our IP
  from a media send; the media stays pending and the conversation reports no permitted path.
  Inbound direct media is gated too: `handleIncomingTcp` refuses the socket **before reading
  the payload** when the contact may not have a direct path with us. The policy governs the
  whole path, not just who dials — a peer whose own policy is permissive cannot override our
  stricter one. Media still arrives over a TURN-relayed RTC channel, which the policy permits
  (inbound RTC sessions are created with `ipGate.relayOnly(from)`, so relay-only ICE is
  TURN-only). Inbound **text** over direct TCP is not yet gated (same shape as the old media
  gap); it is tracked as a follow-up in `STRUCTURE_MAP.md`.
- **The app lock requires the current real PIN to change or remove it.** Disabling the lock,
  changing the PIN, and replacing the decoy PIN all go through `AppLock.tryUnlock`, so someone
  holding an unlocked phone cannot silently strip the lock, and a wrong PIN counts toward the
  existing lockout. `SecureWipe` uses an internal unguarded removal because it runs after key
  destruction.
- **No independent audit yet.**

## 8. Assurance (threat → test)
| Area | Evidence |
|---|---|
| Pairing root / transcript binding / SAS mismatch on tamper | `crypto/.../PairingCryptoTest.kt` |
| Check-in / drain signed-message formats | `app/src/test/.../server/CheckinSigningTest.kt` |
| Ratchet derivation, skipped-key bounds, AEAD round-trips | crypto unit tests (`TEST_ARCHITECTURE.md`) |
| ML-KEM-768 / ML-DSA-65 / X-Wing KATs + attack vectors | pure-JVM crypto tests on CI (RFC + Wycheproof + deterministic KATs; `TEST_ARCHITECTURE.md`) |
| End-to-end pairing over production rendezvous | live real-phone↔emulator test (`AUDIT_SCOPE.md` §Prior validation) |

Gaps in coverage and device-only paths are tracked in [`TEST_STATUS.md`](TEST_STATUS.md).
