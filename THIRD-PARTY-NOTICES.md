# Third-Party Notices

Aurora Messenger is licensed under the GNU Affero General Public License v3.0
(see `LICENSE`). It builds on the following third-party components, each under its
own license. All are permissively licensed and compatible with AGPL-3.0.

| Component | Purpose | License |
|---|---|---|
| Bouncy Castle (bcprov) | X-Wing (ML-KEM-768 + X25519), ML-DSA-65, Ed25519, SHA-3, HKDF, Argon2id | MIT (Bouncy Castle License) |
| Google Tink (`tink`, `tink-android`) | XChaCha20-Poly1305 AEAD | Apache-2.0 |
| SQLCipher for Android (net.zetetic) | Encrypted database | BSD-style (Zetetic) |
| AndroidX Room | Database ORM / migrations | Apache-2.0 |
| AndroidX Security-Crypto | EncryptedSharedPreferences / Keystore-backed vaults | Apache-2.0 |
| AndroidX SQLite | SQLite support | Apache-2.0 |
| Jetpack Compose (UI, Foundation, Material3, Navigation, Lifecycle, Activity) | UI toolkit | Apache-2.0 |
| AndroidX Core-KTX, ShareTarget | Core utilities, direct-share shortcuts | Apache-2.0 |
| Hilt / Dagger | Dependency injection | Apache-2.0 |
| Kotlin Coroutines | Concurrency | Apache-2.0 |
| OkHttp | HTTP client (rendezvous) | Apache-2.0 |
| NanoHTTPD | In-app rendezvous server | BSD-3-Clause |
| WebRTC (io.github.webrtc-sdk) | Voice/video calls | BSD-3-Clause (+ third-party notices) |
| ZXing core + zxing-android-embedded | QR encode/scan | Apache-2.0 |
| JUnit (test only) | Unit tests | EPL-1.0 |

The standalone rendezvous server (`rendezvous-server/index.js`) uses only the
Node.js standard library (`http`, `crypto`), with no third-party runtime dependencies.

> **liboqs is no longer used.** The post-quantum stack is Bouncy Castle's FIPS-final
> implementations (ML-KEM-768, ML-DSA-65). The unused `liboqs-java-0.3.0.jar` is still present
> in `libs/maven/org/openquantumsafe/` but nothing depends on it; it is dead weight scheduled
> for deletion and is not part of the built app.

Full license texts are available from each project's repository. This list is
provided for attribution and is not legal advice.
