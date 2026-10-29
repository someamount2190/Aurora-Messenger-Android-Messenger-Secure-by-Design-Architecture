# Data at Rest

Everything persisted on the device, and the key protecting each. Key lifecycle is in
[`KEY_MANAGEMENT.md`](KEY_MANAGEMENT.md).

## Inventory

| Store | Contents | Protected by | Notes |
|---|---|---|---|
| **SQLCipher database** | Messages, contacts, ratchet & prekey state, call/disappearing metadata | DB key under Keystore master key (AES-256) | SQLCipher 4.9.0; schema versioned & exported (`app/schemas/`), current version **12** |
| **EncryptedSharedPreferences** | Identity private keys, PIN verifiers | Keystore-held master key | PQ keys too large for the TEE directly |
| **Plain SharedPreferences** | Non-secret settings: onboarding flag, IP-exposure policy, relay toggle, language, theme | Nothing — `MODE_PRIVATE` only | `aura_settings` and the locale prefs are **not** EncryptedSharedPreferences; they hold no keys or message data |
| **Encrypted media** | Photos, videos, voice messages | Local-only per-contact random media key (stored with the ratchet record; never transmitted) | Decrypted in-memory only; a voice recording is briefly plaintext while being captured (see below); inside `FLAG_SECURE` window |
| **Encrypted backup (opt-in)** | User-selected export | Argon2id(passphrase) → XChaCha20-Poly1305 | Passphrase never stored |
| **Android Keystore** | Master key (wraps the above) | Hardware/TEE | Root of the at-rest hierarchy |

## What is NOT stored
- No persistent server-side message history. **When the opt-in relay is enabled**, the server
  temporarily buffers sealed frames (up to 60 seconds for offline delivery) and sees live routing
  relationships; in-memory state and lack of request logs do not hide those relationships from
  its operator. With the relay off, no message payload reaches the server at all.
- No *persistent* plaintext media cache. Playback and sharing decrypt in memory through a
  non-exported media provider. One exception: while a voice message is being recorded,
  `PlaintextScratch` writes it in the clear to `cacheDir/voice` (MediaRecorder needs a seekable
  MP4); it is deleted when the recording is sealed or cancelled and swept at launch. Explicit
  exports and recipient apps may retain their own copies.
- No backup passphrase.
- No third-party push tokens (wake is via the contentless `/tap`+`/wait` long-poll).

## Deletion semantics
- **Cryptographic erase:** destroying keys (not bytes) renders all on-disk ciphertext
  unrecoverable instantly — the basis for contact deletion, duress wipe, and the decoy PIN.
- **Disappearing messages:** per-conversation timers erase messages on both devices.
- **Contact deletion** cascades to messages, media, and ratchet keys, and signs a
  `contactremove` so the peer wipes its copy too.
- **"Clear All Data"** deletes the five group tables explicitly as well as dropping the database
  file (`SecureWipe.kt`), so the logical row-drop is complete even if the file delete is blocked.

## Hardening at rest
- In release builds, `FLAG_SECURE` blocks screenshots/screen-recording and content in the recents preview.
- App lock with a **decoy PIN** (opens an empty app) and optional **duress wipe**.

## Residual risk
At-rest protection assumes the device is **locked and uncompromised**. An unlocked or
rooted/malware-infected device (ADV-X / ADV-5 in [`THREAT_MODEL.md`](THREAT_MODEL.md)) defeats
these protections — endpoint security is an explicit non-goal.

- The app lock now requires the current real PIN to change or remove it (`AppLock.disableLock`),
  so someone holding an unlocked phone cannot silently strip it; a wrong PIN counts toward the
  lockout. `SecureWipe` bypasses the check internally because it runs after key destruction.
