# Test Implementation Status

Tracks what's built against the plan in [`TEST_ARCHITECTURE.md`](TEST_ARCHITECTURE.md).
Last updated: 2026-10-27. Counts are recorded results from the hardening run,
not a fresh execution during this documentation update. CI for source revision
`9ebea7b` was checked and passed.

## Summary

| | Verified green | Skipped |
|---|---|---|
| `crypto` module | **161** | 0 |
| `app` module | **179** | 0 |
| `rendezvous-server` (Node) | **18** | 0 |
| **Total** | **358** | 0 |

Run (both pure-JVM/Robolectric, no native deps, CI-friendly):
- `./gradlew -p crypto test` → 161 pass.
- `./gradlew -p crypto publish` — **required before app tests locally**: the app consumes
  `aura-crypto` from the jar in `libs/maven`, so without it app tests run against stale crypto
  (CI does this automatically).
- `./gradlew :app:testDebugUnitTest` → 179 pass.
- `cd rendezvous-server && node --test` → 18 pass (real server process, HTTP + raw WebSocket).

## Additional validation recorded on 2026-10-27

- Five Jazzer crypto targets ran approximately 45 minutes each, totaling about 168 million
  inputs without a reported crash or forgery. Fuzz inputs are not counted as unit tests.
- Server verification helpers were exercised with three million inputs without findings.
- Emulator upgrade checks preserved identity and database access for the published
  hardening changes. A forged external call-answer intent was rejected.
- Release screenshot blocking, app-lock acceptance/rejection, and duress wipe were checked
  on an emulator. These checks do not replace two-phone end-to-end testing.
- Added regression coverage includes call-answer tokens, relay target restrictions,
  decrypted-media sharing, incoming share URI validation, and backup KDF pins.
- CI also runs gitleaks, dependency scanning, and the plaintext-write guardrail.

Detailed security-work reports are maintained locally; they are not an independent audit.
Open validation and compatibility work: [`PROJECT_STATUS.md`](PROJECT_STATUS.md).

## Crypto — all pure-JVM now ✅

The liboqs/JNI removal means the **entire** crypto suite (incl. the post-quantum primitives)
runs on CI; there is no longer a device-only "native" tier or any `assumeTrue` skip guards.

- **Primitives:** `HkdfTest` + `HkdfRfc5869KatTest` (RFC 5869 KATs), `SymmetricCipherTest` +
  `SymmetricCipherKatTest` (Tink XChaCha vs. clean-room reference), `HybridKemTest` (X-Wing),
  `HybridSignerTest` (ML-DSA-65 + Ed25519, incl. the 3309-byte FIPS size), `NodeIdentityTest`,
  `PrekeyManagerTest`, utils.
- **Known-answer / known-bug vectors:** `AcvpKatTest` (**independent-authority NIST ACVP**:
  ML-KEM-768 keygen + encapsulation + decapsulation and ML-DSA-65 keygen, byte-exact vs the
  published vectors — closes the keygen/encaps gap), `WycheproofTest` (x25519 / ed25519 / xchacha /
  mlkem_768 / **mldsa_65 verify** edge + known-bug corpora — incl. ML-DSA context binding,
  modified-signature, zero-key, and incorrect-length rejection), `ClassicalKatTest`
  (Ed25519 RFC 8032, X25519 RFC 7748), `PqcKatTest` (deterministic ML-KEM-768 / ML-DSA-65 /
  X-Wing sizes + round-trip + cross-version regression digests).
- **KEM Double Ratchet (the single ratchet):** `KemDoubleRatchetTest` (round-trips, out-of-order,
  simultaneous steps, tamper/replay, **post-compromise healing**), `KemRatchetCodecTest`
  (wire frame + session persistence round-trips), `KemRatchetManagerTest` (store-backed,
  initiator-first auto-bootstrap, healing through persistence, **SAS agreement + media-at-rest
  key** now folded in), `CryptoAttacks` (adversarial: replay/reflection/forgery/garbage). The
  symmetric `RatchetManager` and its tests have been retired.

## App ✅

- **Storage / migrations** (Robolectric + in-memory Room): `RoomDaoTest` (incl. the `kem_ratchet`
  backup/load/delete round-trip), `StoreAdapterConformanceTest` (fake↔Room incl. OPK ordering),
  `AuroraDatabaseMigrationTest` (7→8 / 8→9 / 9→10 data-preservation, run against the real
  `Migration` objects).
- **Security orchestration** (Robolectric + mockk): `VerifyPairingTest` (SAS attempt-limit →
  blocklist → cryptographic wipe), `SecureWipeTest` (every collaborator invoked; one failure
  doesn't abort the rest), `AppLockTest` (real vs decoy PIN, exponential lockout — via a
  prefs/clock seam), `BackupsTest` (passphrase round-trip, wrong-passphrase fail-closed),
  `AuroraSettingsBlocklistTest`, `DisappearingMessagesTest` (expiry stamping + purge incl. media),
  `SignalCodecAadTest` (call vs RTC AEAD domain separation on the shared ratchet).
- **Integration sims** (Robolectric): `EndToEndPairMessageSimTest` (real MessageSender +
  TcpMessageServer over the in-memory transport, driving the live KEM ratchet — now incl.
  unknown-sender drop, tamper rejection, wire opacity, idempotent re-ack),
  `PqxdhHandshakeSimTest` (the real X-Wing PQXDH handshake — both peers derive the same root),
  plus the two-peer rendezvous/transport sims.
- **Security/pure-logic:** `EncryptedMediaStoreTest`, `ContactEraserTest`, `WireFramesTest`,
  `StreaksTest`, `CheckinSigningTest` (the signing-string contract shared with the Node server).

## Pending — device/UI only ⛔

Still best authored on an emulator (Hilt graph / Compose / real Keystore), not a JVM sandbox:
ViewModel (Turbine) + Compose UI tests, the `PairingCoordinator`/`Scanner`/`Receiver`
orchestration, `SyncEngine` routing, the `CallController`/`RtcTransport` state machines, the real
`RendezvousClient` HTTP, and an on-device end-to-end capstone (pair → verify → text → media →
delete).

## Infra added
- `crypto/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, `org.json` (test).
- `app/build.gradle.kts`: `kotlin-test-junit`, `kotlinx-coroutines-test`, real `org.json`,
  `aura-crypto`, Robolectric, `androidx.test:core`, Turbine; `testOptions.unitTests.isReturnDefaultValues = true`.
