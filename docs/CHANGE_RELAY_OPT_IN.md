# Change record: DERP is opt-in as a message transport

**Date:** 2026-10-28
**Status:** implemented; debug + release compile; 224/224 app unit tests pass
**Files:** 8 edited, 2 test classes added. No schema, wire-format, or protocol change.

---

## 1. The separation this implements

Aurora has two independent planes:

| Plane | Mechanism | Server's role |
|---|---|---|
| **Connection establishment** | check-in, `/find` candidate exchange, `/signal` (pairing, SDP, ICE), `/wait` long-poll, `/tap` | Broker. Never carries a message body. |
| **Message transport** | WebRTC data channel, direct TCP, `/relay/stream` (DERP) | Out of the path, out of the path, **in the path**. |

The change makes the third transport opt-in while leaving the first plane untouched. The two
planes share a server and a TCP connection pool, which is exactly why the boundary had to be
established by reading the call sites rather than by assumption.

---

## 2. Requirement → implementation

| # | Requirement | Where it is satisfied |
|---|---|---|
| 1 | Direct P2P preferred, subject to the IP policy | Unchanged: `MessageSender.flushPending` tries RTC → TCP → relay, in that order |
| 2 | DERP message forwarding off by default, enableable in Settings | `AuroraSettings.relayEnabled` (default `false`), `PrivacySection` switch |
| 3 | Enabling requires a warning about encrypted payloads and metadata | `AlertDialog` in `PrivacySection`; copy names the server, the metadata, and the cost of leaving it off |
| 4 | Disabling must not disable discovery, rendezvous, queries, or wake-up | Nothing outside `DerpTransport` is gated — see §3 |
| 5 | No permitted path → informative status, not silent pending | `MessageSender.noDeliveryPath` → `ConversationViewModel.deliveryBlocked` → conversation banner |
| 6 | Audit DERP and rendezvous call sites; do not assume | §3, produced by exhaustive grep over `app/src/main` |
| 7 | Document changes, behaviour, tests | This document |

---

## 3. Call-site audit: signalling vs. message payload

Exhaustive over `app/src/main/**/*.kt`. `DerpTransport` occurs in exactly four places.

### 3.1 DERP — the only message-carrying path

| Site | Call | Class | Gated? |
|---|---|---|---|
| `MessageSender.kt:111` | `derpTransport.send(nodeId, frame)` | **message payload** — sealed `msg` frames | **yes** |
| `AppWiring.kt:71` | `derpTransport.resume()` | **inbound payload collection** — opens the socket so the server flushes frames buffered for us | **yes** |
| `AppWiring.kt:59` | `derpTransport.init(scope)` | scope assignment; performs no I/O and opens no socket | no (harmless, kept) |
| `DerpTransport.kt:173` | `inbox.processFrame(frame)` | inbound payload handling (reachable only via the socket) | transitively |

**DERP has no discovery, rendezvous, candidate-exchange, or wake role.** Its only inbound
actions are `CHALLENGE`/`AUTH` (authenticating *the relay socket itself*) and `INBOUND`
(a relayed message). There is no call site where disabling DERP could remove a discovery or
wake capability, because no such call site exists.

### 3.2 Rendezvous — signalling only, none gated

| Call | Site | Class | Carries a message body? |
|---|---|---|---|
| `checkIn` | `SyncEngine.kt:185` | registration / presence | no |
| `find` | `MessageSender.kt:138` | connection-candidate exchange | no |
| `verifyCandidates` | `MessageSender.kt:141` | candidate authentication | no |
| `probe`, `firstReachable` | `RendezvousClient.kt:218-219` | server selection | no |
| `waitForWake` | `SyncEngine.kt:153` | **wake-up** long-poll | no |
| `tap` | `MessageSender.kt:116`, `:206` | **wake-up** (contentless nudge) | no |
| `postSignal` | `PairingSignal.kt:31`, `RtcSignalCodec.kt:48`, `CallSignalCodec.kt:51` | pairing, SDP/ICE, call setup | no |
| `getSignals` | `SyncEngine.kt:218` | drain of the above | no |
| `publishPrekeys` | `SyncEngine.kt:203` | key distribution | no |
| `fetchPrekeyBundle` | `ScannerPairing.kt:183` | PQXDH handshake input | no |
| `getTurnCredentials` | `SyncEngine.kt:193` | NAT-traversal credentials | no |

Every one of these still runs with the relay off. `tap` in particular is the wake mechanism
that replaces the relay: when a message cannot be delivered, `flushPending` taps the peer so
their app comes online and a later flush can reach them directly.

### 3.3 What `onWoken` does and does not do

`SyncEngine.onWoken` is a side effect invoked *after* `waitForWake` returns true
(`SyncEngine.kt:173`); the loop then proceeds to `tick()` regardless. Gating its body means
"on wake, don't open the relay socket". It does not gate the wake, the check-in, the signal
drain, or the flush — those are in `tick()`.

---

## 4. Behaviour

| Relay setting | RTC | Direct TCP | DERP | Wake tap | Discovery |
|---|---|---|---|---|---|
| **off** (default) | tried | tried if policy permits | **never** | yes | yes |
| on | tried | tried if policy permits | tried last | only if direct fails | yes |

Delivery status shown in the conversation:

| Condition | Banner |
|---|---|
| Pending, policy forbids direct, relay off | **"Not delivered: your IP setting blocks direct connections to this contact and the server relay is off."** |
| Pending, RTC `UNREACHABLE` | existing "couldn't connect directly" copy |
| Pending, RTC `FAILED` | existing "couldn't reach them" copy |

The new banner outranks the two transport-failure banners because it is not a connectivity
problem the peer can fix — it is a setting the user can change.

---

## 5. Code changes

### 5.1 `settings/AuroraSettings.kt`

```kotlin
private const val KEY_RELAY_ENABLED = "relay_enabled"

private val _relayEnabled = MutableStateFlow(prefs.getBoolean(KEY_RELAY_ENABLED, false))
val relayEnabled: StateFlow<Boolean> = _relayEnabled

fun setRelayEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
    _relayEnabled.value = enabled
}
```

`clearAll()` additionally resets:

```kotlin
_relayEnabled.value = false
_peerIpExposurePolicy.value = PeerIpExposurePolicy.ALWAYS_P2P
```

The second line is a **pre-existing bug fix**, independent of this feature: `clearAll()` wiped
the preferences file but never reset the in-memory policy flow, so "Clear All Data" left the
app on the old policy until the next process start.

### 5.2 `transport/MessageSender.kt`

New derived state:

```kotlin
private val _noDeliveryPath = MutableStateFlow<Set<String>>(emptySet())
val noDeliveryPath: StateFlow<Set<String>> = _noDeliveryPath.asStateFlow()
```

The relay branch is gated:

```diff
-            if (derpTransport != null) {
+            if (derpTransport != null && settings.relayEnabled.value) {
                 deliverPendingOver(contact) { frame -> derpTransport.send(nodeIdHex, frame) }
             } else {
+                // Tapping is signalling only — it carries no message payload.
                 rendezvousClient.tap(settings.serverAddress.value, nodeIdHex)
             }
```

At the end of each contact's pass, still-pending messages with no permitted path are recorded:

```kotlin
if (messageDao.pendingForContact(nodeIdHex).isNotEmpty() &&
    !hasPermittedDeliveryPath(
        relayEnabled = settings.relayEnabled.value,
        directAllowed = settings.peerIpExposurePolicy.value.allowsDirect(contact.isMutuallyVerified),
        rtcConnected = rtcTransport.isConnected(nodeIdHex)
    )
) {
    noPath += nodeIdHex
}
```

The predicate is a top-level internal function so it can be tested without the object graph:

```kotlin
internal fun hasPermittedDeliveryPath(
    relayEnabled: Boolean,
    directAllowed: Boolean,
    rtcConnected: Boolean
): Boolean = relayEnabled || directAllowed || rtcConnected
```

### 5.3 `AppWiring.kt`

```diff
-        syncEngine.onWoken = { scope.launch { derpTransport.resume() } }
+        syncEngine.onWoken = {
+            scope.launch { if (settings.relayEnabled.value) derpTransport.resume() }
+        }
```

`derpTransport.init(scope)` stays unconditional: it only assigns a coroutine scope.

### 5.4 `ui/conversation/ConversationViewModel.kt`

```kotlin
val deliveryBlocked: StateFlow<Boolean> = messageSender.noDeliveryPath
    .map { contactId in it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

### 5.5 `ui/conversation/ConversationScreen.kt`

The existing failure banner became a `when` with the new case first:

```kotlin
val failureBanner = when {
    deliveryBlocked -> stringResource(R.string.conv_banner_no_path)
    connection == RtcState.UNREACHABLE -> stringResource(R.string.conv_banner_unreachable)
    connection == RtcState.FAILED -> stringResource(R.string.conv_banner_failed)
    else -> null
}
```

### 5.6 `ui/settings/SettingsComponents.kt`

`PrivacySection` gained a `Switch` bound to `settings.relayEnabled`, a confirmation
`AlertDialog` shown only when turning it **on** (turning it off is immediate), and an inline
conflict message when the user selects `STRICT_RELAY_ONLY` with the relay off.

The conflict guard exists because `STRICT_RELAY_ONLY` ("Never — always relay") has **no
permitted path** with the relay off and no TURN server: direct TCP is refused by policy, RTC is
limited to TURN candidates, and the relay is off. The guard is UI-only; the setting can still
be written programmatically, and `MessageSender` tolerates it by reporting the blocked state
rather than crashing.

### 5.7 Strings — 8 new keys, both locales

`settings_relay`, `settings_relay_desc`, `settings_relay_warning_title`,
`settings_relay_warning_body`, `settings_relay_warning_confirm`, `settings_relay_warning_cancel`,
`settings_ip_conflict`, `conv_banner_no_path`.

---

## 6. Tests

### `app/src/test/kotlin/com/aura/settings/AuroraSettingsRelayTest.kt` — 4 tests

| Test | Property |
|---|---|
| `relay_defaultsToOff` | opt-in, not opt-out |
| `enablingRelay_survivesNewInstance` | the choice persists across process restart |
| `clearAll_turnsRelayOff` | wipe restores the default |
| `clearAll_resetsIpExposurePolicy` | wipe restores the default policy (regression for the bug above) |

### `app/src/test/kotlin/com/aura/transport/NoDeliveryPathTest.kt` — 6 tests

| Test | Property |
|---|---|
| `permittedPath_isTrueWhenAnyPathIsAllowed` | predicate truth table (relay / direct / open RTC) |
| `permittedPath_isFalseOnlyWhenNothingIsAllowed` | the single blocked combination |
| `relayOff_andPolicyBlocksDirect_reportsNoPath_andStillTaps` | end-to-end: blocked set populated, message stays `pending`, **and `tap` is still called** — the requirement-4 regression test |
| `relayOff_butDirectPermitted_isNotReported` | reachability failure ≠ permission failure |
| `relayOn_isNotReported_evenWhenPolicyBlocksDirect` | relay is a permitted path |
| `enablingRelay_clearsTheReportedState_onNextFlush` | derived state self-heals when the setting changes |

`NoDeliveryPathTest` drives the real `MessageSender` with a `DisconnectedTransport` and a
`RecordingRendezvous` that counts taps.

---

## 7. Verification

| Check | Result |
|---|---|
| `:app:compileDebugKotlin` | pass |
| `:app:compileReleaseKotlin` | pass |
| `:app:testDebugUnitTest` | **224 tests, 0 failures, 0 errors, 0 skipped** across 44 classes |
| `TranslationParityTest` | pass — EN/RU key sets and placeholders match |
| Baseline | 214 app tests before this change; +10 here |

Pre-existing warnings, untouched: `ConversationScreen.kt:134`, `ContactList.kt:153`,
`RtcDataSession.kt:125`.

---

## 8. Known gaps and consequences

1. **Inbound asymmetry (decided: both directions gated).** With the relay off, this device never
   opens `/relay/stream`, so a peer who *has* enabled the relay cannot deliver to it — their
   message is buffered and never collected, and their UI stays pending. This is the direct
   consequence of "no message payload transits the server in either direction", and it is the
   chosen behaviour: an opted-out device should not participate in relayed delivery in either
   direction. The alternative (gate outbound only) is a one-line revert of §5.3, at the cost of
   accepting inbound relayed messages while opted out.
2. **Permitted-but-unreachable is not flagged.** A message to a CGNAT peer with
   `ALWAYS_P2P` and the relay off stays pending with no banner, because a path is *permitted* —
   it just never succeeds. Distinguishing that from "peer offline for ten seconds" needs a
   failure counter and is not implemented.
3. **Tap cadence.** With the relay off, a stuck message taps on every flush. The server
   rate-limits taps to 30/min/IP; the client has no backoff of its own.
4. ~~**Policy text.**~~ **Fixed 2026-10-29.** `legal/LegalDocs.kt` §4/§6/§7/§8 now describe the
   relay as optional, off by default, and metadata-visible when enabled; the "never in the path"
   absolute is gone.
5. **TURN is also a relay.** With `COTURN_SHARED_SECRET` configured, calls and relay-only WebRTC
   traverse coturn. Neither the policy nor the new setting mentions it.
6. ~~**Media is unchanged.**~~ **Fixed 2026-10-29.** `MediaTransfer` now applies
   `IpExposureGate.relayOnly` before its direct-TCP fallback and reports "no permitted path" for
   pending media. Media still has no relay path, so a relay-only contact's media waits for an
   RTC (TURN) channel.
7. **Upgrade default (decided: off).** `prefs.getBoolean(KEY_RELAY_ENABLED, false)` returns false
   for an existing install too. That is deliberate: explicit opt-in means no device relays
   without the user switching it on, so the upgrade path does not preserve the old automatic
   behaviour. Keying the default off `KEY_ONBOARDING_DONE` was considered and rejected.

---

## 9. Rollback

No version control. Revert, in order:

1. `MessageSender.kt` — restore `if (derpTransport != null) {`, drop `_noDeliveryPath`,
   `noDeliveryPath`, the trailing block, and `hasPermittedDeliveryPath`.
2. `AppWiring.kt` — restore the one-line `onWoken`.
3. `ConversationViewModel.kt` — drop `deliveryBlocked`.
4. `ConversationScreen.kt` — restore the two-branch `when (connection)`.
5. `SettingsComponents.kt` — restore the original `PrivacySection`.
6. `AuroraSettings.kt` — drop `KEY_RELAY_ENABLED`, `_relayEnabled`, `relayEnabled`,
   `setRelayEnabled` (keep the policy reset if you want the bug fix).
7. Delete `NoDeliveryPathTest.kt` and `AuroraSettingsRelayTest.kt`. The string keys are inert
   if left.

A `*_backup_pre_*` copy predating this change is safer than hand-reverting.

---

## 10. Relationship to `docs/STRUCTURE_MAP.md`

§2.6 now records the relay as opt-in and the delivery-status surface. Drift-ledger item 3
("server is never in the path") is **true by default, false when opted in**; the policy wording
in §8.4 was updated on 2026-10-29, so the item is closed.
