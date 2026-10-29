# Dependencies & SBOM

Versions are the single source of truth in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml);
this table is a human-readable mirror. License details: [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md).

> Pin review to a tag; versions move. The standalone Node.js rendezvous server has **zero
> runtime dependencies** (stdlib `http` + `crypto` only).

## Security-critical (cryptography & storage)

| Component | Module | Version | Role | Provenance |
|---|---|---|---|---|
| Bouncy Castle | `org.bouncycastle:bcprov-jdk18on` | 1.85 | X-Wing (ML-KEM-768+X25519), ML-DSA-65, Ed25519, HKDF/SHA3, Argon2id — pure-Java PQC | Maven Central |
| Google Tink (JVM core) | `com.google.crypto.tink:tink` | 1.18.0 | XChaCha20-Poly1305 AEAD | Maven Central |
| Google Tink (Android runtime) | `com.google.crypto.tink:tink-android` | 1.18.0 | Android AEAD runtime; kept in step with the JVM artifact | Maven Central |
| SQLCipher | `net.zetetic:sqlcipher-android` | 4.9.0 | AES-256 encrypted database | Maven Central |
| AndroidX Security | `androidx.security:security-crypto` | 1.1.0-alpha06 | EncryptedSharedPreferences + Keystore master key | Google Maven |
| `com.aura:aura-crypto` | in-repo (`crypto/`) | 0.1.0 | Aurora's own crypto core | Built locally → `libs/maven` |

## Platform & app

| Component | Module | Version |
|---|---|---|
| Kotlin stdlib | `org.jetbrains.kotlin:kotlin-stdlib` | 1.9.23 |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.8.0 |
| Hilt (DI) | `com.google.dagger:hilt-android` / compiler | 2.51.1 |
| Room | `androidx.room:*` | 2.6.1 |
| AndroidX SQLite | `androidx.sqlite:sqlite-ktx` | 2.4.0 |
| Core KTX | `androidx.core:core-ktx` | 1.13.0 |
| ShareTarget | `androidx.sharetarget:sharetarget` | 1.2.0 |
| Compose BOM | `androidx.compose:compose-bom` | 2024.05.00 |
| Compose Activity / Nav / Lifecycle / Hilt-nav | various | 1.9.0 / 2.7.7 / 2.7.0 / 1.2.0 |
| ZXing core / android-embedded | `com.google.zxing:core` / `com.journeyapps:...` | 3.5.3 / 4.3.0 |
| NanoHTTPD (in-app server) | `org.nanohttpd:nanohttpd` | 2.3.1 |
| OkHttp | `com.squareup.okhttp3:okhttp` | 4.12.0 |
| WebRTC | `io.github.webrtc-sdk:android` | 125.6422.07 |
| JUnit (test) | `junit:junit` | 4.13.2 |

## Provenance notes
- **Vendored Maven repo** (`libs/maven/`): holds the locally built `com.aura:aura-crypto`
  artifact, so a fresh clone builds without external sibling folders. Auditors should confirm
  the vendored artifact matches the `crypto/` source.
- **Pure-JVM cryptographic core:** the post-quantum stack is now pure-Java BouncyCastle (X-Wing/ML-DSA) +
  Google Tink — the crypto module no longer links liboqs, so there is no JNI surface in it.
  SQLCipher and WebRTC still ship native libraries in the app. ⚠ BouncyCastle's X-Wing tracks an
  in-progress IETF draft; pin the version.
- **Dead artifact, not a dependency:** `libs/maven/org/openquantumsafe/liboqs-java/0.3.0/`
  (`liboqs-java-0.3.0.jar` + `.pom`) is **still vendored** even though nothing depends on it.
  Delete it before any release; it is currently the only reason the repo still contains liboqs
  bytes.

## Notes
- SQLCipher is **4.9.0** (16 KB-ready per its release notes) and AGP is **8.3.2**. The app sets
  `useLegacyPackaging = true`; ELF/APK alignment has not been verified on a 16 KB device, so the
  16 KB claim is unproven. See [`PROJECT_STATUS.md`](PROJECT_STATUS.md).
- This human-readable inventory **is** the project's SBOM; a machine-readable CycloneDX export
  is not published.
- Optional provenance follow-up: recording upstream hashes/commits for the vendored
  `libs/maven` artifacts would let reviewers confirm they match upstream byte-for-byte.
