# Flares & Waypoints — Metadata-Private Rendezvous Plan

> Status: **DRAFT — FOR REVIEW. No code until approved.** Plan for removing the last place
> where the rendezvous server learns *who talks to whom*: every endpoint is keyed by the
> permanent `nodeId` today (`PROTOCOL_RENDEZVOUS.md` §9: "What the relay still learns: who
> is talking to whom and when").
>
> Goal statement we want to be able to make, and test: **the server holds no messages and
> knows no relationships.**

Companion reading: [`PROTOCOL_RENDEZVOUS.md`](PROTOCOL_RENDEZVOUS.md) (current wire protocol),
[`THREAT_MODEL.md`](THREAT_MODEL.md) §6 (LINDDUN rows this plan targets),
[`PQ_RATCHET_DESIGN.md`](PQ_RATCHET_DESIGN.md) (the ratchet the new keys hang off).

---

## 1. The two new primitives

| Name | What it is | Replaces |
|---|---|---|
| **Flare** | A contentless, one-time signal: "someone you're paired with wants to reach you". Posted at an address only the two peers can compute; never reused. | `POST /tap/:nodeId`, `GET /wait/:nodeId`, the wake role of `/signal` |
| **Waypoint** | A one-time meeting point. Both phones join it after a flare, exchange sealed setup (SDP/ICE, direct-path candidates), then go P2P, or keep using the waypoint as the blind relay for that session. | `/signal/:nodeId` queue, `/find` + `/checkin` (for paired contacts), nodeId routing in `/relay/stream` |

Neither carries a nodeId. The server sees a flare address once, a waypoint once, and nothing
that connects either to an identity or to any other flare or waypoint.

### Inspiration and what we do differently

Umbera (reviewed 2026-10-27) uses week-long mailbox addresses derived from static X25519, read
with one reader key registered on all of a user's mailboxes. We deliberately differ:

| | Umbera mailbox | Aurora flare / waypoint |
|---|---|---|
| Lifetime | 1 week, many messages | one use |
| Derived from | static X25519 (quantum-recomputable) | pairing root from PQXDH/X-Wing → PQ-safe |
| Collect authorisation | one long-term reader key per user (links all mailboxes) | address = hash of a one-time token; nothing registered |
| Forward secrecy of addresses | none | hash chain with deletion: a seized phone can't recompute past addresses |
| Holds content | yes, stored ciphertext | no: flares are empty; waypoints pipe live, store nothing |
| Who can target you | anyone who can post to the mailbox (needs PoW) | only a paired contact can compute your next flare address |

---

## 2. Threat model delta

Adversaries from `THREAT_MODEL.md` §3 that this plan addresses:

- **Honest-but-curious or seized rendezvous server** (memory snapshot, operator logging).
  Today it sees `nodeId A → nodeId B` for every tap/signal/relay frame. After: it sees
  unlinkable one-time tokens.
- **Future quantum adversary with recorded server state.** Addresses come from the
  X-Wing-derived pairing root, so recomputing the social graph needs breaking ML-KEM too.
- **Later device seizure.** Hash-chained address keys are deleted as they advance, so past
  flares/waypoints can't be matched against old server records.

Explicitly **not** solved until Phase 6 (independent proxy hop): the server still sees **IP
addresses**. It can correlate poster and watcher by timing, and a waypoint links the two
joining IPs **directly**, since the server pairs those two sockets itself. This plan moves the
leak from *nodeId→nodeId* (permanent, global) to *IP→IP* (changes with network, blurred by
CGNAT), and Phase 6 removes it. The docs must say so.

Safety property: flares and waypoints carry only ratchet-sealed data, so a flaw in this
design can leak **metadata**, never **content**.

---

## 3. Key schedule (crypto module)

New per-contact secret, stored in the existing `KemRatchetManager` blob next to `sasFp` and
`mediaKey` (`REC_VERSION` 1 → 2):

```
FK_root         = HKDF(ikm = pairing root, info = "aura-flare-v1")          // at seed(), before root is wiped
FCK_dir,0       = HKDF(FK_root, info = "aura-flare-chain|" + dir)           // dir = "i2r" | "r2i"
token_dir,i     = HKDF(FCK_dir,i, info = "aura-flare-token")                // 32 B, the secret
flare_addr      = SHA-256("aura-flare-addr-v1" ‖ token_dir,i)               // what the server indexes
waypoint_dir,i  = HKDF(FCK_dir,i, info = "aura-waypoint")                   // join token for that flare
FCK_dir,i+1     = HKDF(FCK_dir,i, info = "aura-flare-next")                 // old FCK zeroised
resync_addr     = SHA-256("aura-flare-resync-v1" ‖ HKDF(FK_root, info = "resync|" + utcDay))
```

- **Two directions** so simultaneous flares never collide.
- **Lookahead window W** (proposed 2): the sender advances its index only after the waypoint
  meeting succeeds (an uncollected flare is re-posted at the *same* address), so the receiver
  only needs index `i` plus one spare for a crash between meeting and advancing. Every watched
  address costs one of the K watch slots (§4), so W must stay small.
- **Resync flare:** if a phone falls more than W behind (restore, long offline), the peer
  falls back to the day's resync address. It is linkable within one UTC day and is the only
  repeated address; disclosed in docs.
- All derivations are BC HKDF / SHA-256 library calls, as the migration plan requires.
- **Open (review-gated, §9 Q1):** re-seeding `FK` from the ratchet's root after confirmed KEM
  steps, so address prediction also heals after compromise. v1 ships without it.

---

## 4. Server protocol (v3)

All new endpoints are **unauthenticated by design**: there is no identity to authenticate.
Abuse control comes from addresses being unguessable plus per-IP caps.

| Method · Path | Body / query | Behaviour |
|---|---|---|
| `POST /flare` | `{addr}` (fixed-size body) | Store `addr` in memory, TTL 10 min. Idempotent. Wakes any watcher of `addr` on the next release tick. |
| `POST /watch` | `{tokens:[K × 32 B]}`, long-poll ≤25 s | Server hashes each token, returns which of them have a flare, deletes those flares. **Always exactly K tokens** (proposed 64), padded with random dummies, so the count of contacts isn't revealed. |
| `WS /waypoint` | `JOIN {wp}` | First joiner waits (≤ 60 s); second joiner pairs with it; server pipes frames between the two sockets, stores nothing. A third join or a re-join after close is refused, so a waypoint is single-use. |
| `GET /turn-credentials` | inside an open waypoint | TURN credentials issued per waypoint, not per nodeId. |

**Release ticks:** the server answers watchers on a fixed cadence (proposed 2 s), not the
instant a flare lands, which blurs post→collect timing. Clients add 0–1.5 s random jitter
before posting. Ticks only help against observers *outside* the server (hosting provider,
network, logs); a malicious server sets its own clock. Against the server itself, timing is
addressed by Phase 6 plus optional cover flares (Phase 5).

**Fixed sizes:** flare and watch bodies are constant length; waypoint frames are padded to
fixed cells (proposed 4 KiB; larger payloads span several cells).

**Server state:** `Map<addr, expiresAt>` and live waypoint socket pairs. Nothing else. Same
zero-log rule as `PROTOCOL_RENDEZVOUS.md` §8.

---

## 5. Client flows

**Send a message (recipient may be asleep):**
1. Seal the message with the KEM ratchet (unchanged).
2. Post a flare at `addr_i2r,i`; join `waypoint_i2r,i` and wait.
3. Recipient's `/watch` returns the hit → it joins the same waypoint.
4. Over the waypoint: exchange sealed direct-path candidates. If a direct path is allowed by
   `IpExposureGate` and works, move there; otherwise keep piping sealed frames through the
   waypoint (this *is* the blind relay now).
5. Both advance the chain index; old keys zeroised.

**Call:** same, with SDP/ICE exchanged inside the waypoint, sealed under the existing
`aura-call-v1` AAD label.

**Background:** one `/watch` long-poll covering all contacts' windows + resync addresses,
padded to K. Replaces the `/wait` loop; no drain signature needed.

---

## 6. Phasing

| Phase | Work | Exit gate |
|---|---|---|
| **0. Spec** | Write `PROTOCOL_FLARES.md` (wire format, constants, state machines), threat-model rows, test vectors. | Your approval of the spec; open questions in §9 resolved or explicitly deferred |
| **1. Crypto** | `FlareKeys` in `crypto/`: schedule from §3, window logic, blob `REC_VERSION` 2, zeroisation. | KATs + unit tests green; Jazzer target on window/advance logic; `./gradlew -p crypto publish` before app tests |
| **2. Server** | `/flare`, `/watch`, `WS /waypoint`, release ticks, fixed sizes, caps, TTL reaping. | `node --test` suite covering single-use, idempotency, padding, caps, no-log; fuzz the new parsers as in phase-2 red-team |
| **3. App** | `RendezvousClient` + `DerpTransport` move to flares/waypoints; watch service replaces `/wait`; call signaling via waypoints. | Two-emulator end-to-end (message, media, call); app tests green |
| **4. Retire nodeId paths** | Remove `/tap`, `/wait`, `/signal`, `/find`, `/checkin`, relay v2 `AUTH`, the 60 s buffer; in-app server (`AuroraRendezvousServer.kt`) follows. `/prekeys` stays (pairing only). | Server grep shows no nodeId-keyed route except `/prekeys`; docs updated |
| **5. Traffic shaping** | Fixed K tokens, cell padding, release ticks, client jitter tuned on real phones; optional low-rate cover flares. | Measured on POCO X7 Pro + Infinix: battery cost and delivery latency within agreed limits |
| **6. Independent proxy hop** | Oblivious HTTP (RFC 9458; HPKE from BC) for `/flare` and `/watch`; a CONNECT/MASQUE-style proxy (or Tor) for `WS /waypoint` streams, since OHTTP is request/response only. Both run by an operator independent of the rendezvous. | Needs a second, independent operator; deferred until one exists |
| **7. Red-team + phones** | Repeat the phase 1–2 red-team on the new surface; real-phone tests over mobile data. | No HIGH findings open |

Release builds carry no flare debug tooling or logging (project rule: no dev code in release).

---

## 7. Testing

- **Crypto:** derivation KATs; both peers derive identical windows; advance/zeroise; resync
  day rollover; blob v1 → v2 handling (clean break, see D4).
- **Server:** a flare is collected once; unknown tokens reveal nothing; watch responses are
  constant-size; a waypoint can't be joined three times; nothing outlives its TTL; no
  `console.log` beyond startup.
- **Linkability checks (new class of test):** a server-side harness records everything the
  server sees during a scripted A↔B, A↔C session and asserts that no field repeats across
  sessions and none equals or derives from a nodeId.
- **End to end:** emulator pair, then the two real phones, including asleep-recipient wake
  and calls.

---

## 8. Documentation changes

`PROTOCOL_RENDEZVOUS.md` (rewrite for v3), `THREAT_MODEL.md` §6 (P-LINK-01, P-DET-01,
P-DISC-01 residuals change), README §7 and §10 ("what the server can see"), Privacy Policy,
`CHANGELOG.md`.

---

## 9. Decisions and open questions

**Decisions needed from you:**

| # | Question | Recommendation |
|---|---|---|
| D1 | Keep the 60 s relay buffer? | **Remove.** Waypoints pipe between two live sockets; the sender waits at the waypoint instead. Makes "no message there" literally true. |
| D2 | Replace `/checkin` + `/find` for paired contacts with candidate exchange inside the waypoint? | **Yes (Phase 4).** After pairing, the server then never sees a nodeId. |
| D3 | Proxy hop (OHTTP + stream proxy) in the first release? | **No, Phase 6.** It only means something with an independent second operator. Until then, docs state the IP→IP leak plainly. |
| D4 | Compatibility with current builds? | **Clean break**, as in the crypto migration: nothing is live (droplet offline), so no users to migrate. |
| D5 | Parameters | W = 2, K = 64 (≈ 30 contacts per poll; larger lists use the next bucket, K = 256, which reveals only the bucket), resync addresses polled hourly not every poll, flare TTL 10 min with re-posting, release tick 2 s, cell 4 KiB, waypoint wait 60 s with re-joining until the flare expires: start here, tune in Phase 5. |

**Open questions for review (do not implement until resolved):**

- **Q1** Re-seeding the flare chain from the KEM ratchet so address prediction heals after
  compromise: how to agree on the switch-over index without a new desync mode.
- **Q2** Waypoint sessions for long chats: one waypoint per flare, or keep a waypoint open
  for a conversation burst and rotate on idle?
- **Q3** Spam: can a paired-but-hostile contact flood flares? Per-contact local throttling on
  the receiver, plus per-IP caps on the server: is that enough without proof of work?
- **Q4** Watch batching links the K tokens to one watcher for the duration of a poll. Does
  rotating which dummies are used per poll leak anything over time?
- **Q5** Pairing still uses `/prekeys/:nodeId`. Acceptable as the one identity-keyed route,
  or move bundle fetch into the QR flow?
