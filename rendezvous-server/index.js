// Aurora standalone rendezvous server (Phase 8 graduation from the in-app NanoHTTPD
// server). Behaviour-identical to com.aura.server.AuroraRendezvousServer:
//
//   POST /checkin        verify hybrid (Dilithium-3 + Ed25519) check-in, store
//                        nodeId→IP, 15-min TTL
//   GET  /find/:nodeId   return exactly 10 candidates (real + padding), each signed
//   POST /signal/:nodeId queue an opaque (encrypted) signaling payload (wakes /wait)
//   GET  /signal/:nodeId drain queued payloads
//   POST /tap/:nodeId    contentless wake — nudge a backgrounded node online
//   GET  /wait/:nodeId   authenticated long-poll; parked until a tap/signal or timeout
//   POST /prekeys/:nodeId  authenticated publish of a signed PQXDH prekey bundle
//   GET  /prekeys/:nodeId  fetch a peer's bundle (pops one one-time prekey)
//   GET  /turn-credentials/:nodeId  authenticated short-lived TURN credentials (if configured)
//   WS   /relay/stream  authenticated blind relay of sealed frames (see the relay section)
//
// Zero logs of payloads or message content. In-memory only — restart wipes all
// state. The signed check-in string and 10-candidate /find format match the
// Kotlin client byte-for-byte, so the same app talks to either server. The server
// validates the Ed25519 half of the hybrid check-in (it has no Dilithium); the full
// hybrid signature is stored and served so peers verify it post-quantum themselves.
//
// Run: node index.js   (listens on PORT, default 8080)
// Deploy: a $5 VPS (Iceland / Switzerland recommended). Front with a TLS
// terminator (Caddy/nginx) and pin its certificate in the app (Phase 8).

const http = require('http');
const crypto = require('crypto');
const START_MS = Date.now();

const PORT = process.env.PORT || 8080;
// AGPL-3.0 §13: users interacting with this server over the network must be able to
// obtain its (possibly modified) source. GET /source points them at it. Operators of
// a MODIFIED version must set SOURCE_URL to where THEIR source is published.
const SOURCE_URL = process.env.SOURCE_URL || 'https://github.com/someamount2190/AuroraMessenger';
// Bind IPv4 wildcard by default. An IPv6 (::) bind is reachable in-host but some
// NAT/forwarders (QEMU slirp, certain VPS setups) don't relay to the v4-mapped
// socket; 0.0.0.0 is the reliable choice for a public rendezvous server.
const HOST = process.env.HOST || '0.0.0.0';
const TTL_MS = 15 * 60 * 1000;     // 15-minute registration TTL (Privacy Policy §3.2)
const CHECKIN_FRESHNESS_MS = 5 * 60 * 1000;
const CHECKIN_RATE_LIMIT_MS = 60 * 1000;
const FIND_RATE_LIMIT_PER_MIN = 60;  // headroom for active delivery retries (5s cadence)
const SIGNAL_POST_RATE_LIMIT_PER_MIN = 30;
const PREKEY_FETCH_RATE_LIMIT_PER_MIN = 60;  // each fetch pops a one-time prekey; cap OPK drain
const MAX_QUEUE_LEN = 64;
// Per-source-IP share of one node's signal queue, so one sender can't fill the whole queue.
// Keyed on the connecting IP (not a payload field the sender controls); sized with headroom
// for several honest users behind one carrier-NAT address.
const PER_SENDER_QUEUE_MAX = 8;
const CANDIDATE_COUNT = 10;
const MAX_BODY = 64 * 1024;
// Long-poll "tap" wake (Phase 9). A backgrounded app parks one authenticated GET
// /wait/{nodeId}; the server holds it open (no bytes flow) until a tap/signal lands
// for that node or WAIT_HOLD_MS elapses, then the client re-parks. This is the only
// always-on footprint — the server relays a single contentless wake, never content;
// the real message/call then flows directly peer-to-peer.
const WAIT_HOLD_MS = 25 * 1000;          // under typical 60s proxy read timeouts
const TAP_RATE_LIMIT_PER_MIN = 30;       // per-IP cap on contentless wakes
// Forward-secret PQXDH prekey bundles. Only PUBLIC prekeys + signatures are stored —
// never private material, never content. A node publishes a signed bundle (auth'd like
// the drain); an initiator GETs it and the server pops one one-time prekey per fetch.
const PREKEY_OPK_MAX = 100;              // cap on one-time prekeys retained per node
const TURN_CRED_RATE_LIMIT_PER_MIN = 20;  // cap on ephemeral TURN credential generation per IP
// No default: without an operator-set secret, /turn-credentials is disabled (404) rather
// than minting credentials anyone could forge from a secret published in the source.
const COTURN_SHARED_SECRET = process.env.COTURN_SHARED_SECRET || '';
const TURN_REALM = process.env.TURN_REALM || 'turn.auroramessenger.com';
const TURN_PORT = process.env.TURN_PORT || '5349';
// nodeId↔key binding. Defaults ON: a check-in MUST carry the KEM + signing keys and prove they
// hash to the claimed nodeId, otherwise anyone could squat/overwrite another node's mapping (and
// the per-nodeId check-in clock would let them lock the victim out). The current app always sends
// keys; an operator can opt out with STRICT_BINDING=0 only if knowingly supporting legacy clients.
const STRICT_BINDING = process.env.STRICT_BINDING !== '0';
// Hybrid check-in signature size: [4B len][ML-DSA-65 sig 3309][Ed25519 sig 64].
// /find padding signatures match this length so the real candidate can't be
// distinguished by signature size. (Was Dilithium-3's 3293 before the FIPS migration.)
const HYBRID_SIG_BYTES = 4 + 3309 + 64;

const nodes = new Map();           // nodeIdHex -> { ip, port, timestamp, sigB64, ed25519Pub, storedAt }
const signals = new Map();         // nodeIdHex -> [payload, ...]
const lastCheckin = new Map();     // nodeIdHex -> ms
const findHits = new Map();        // ip -> [ms, ...]
const prekeyFetchHits = new Map(); // ip -> [ms, ...] (per-IP prekey-fetch limiter)
const signalActivity = new Map();  // nodeIdHex -> ms (last queue activity, for expiry)
const signalPostHits = new Map();  // ip -> [ms, ...]
const tapHits = new Map();         // ip -> [ms, ...]
const turnCredHits = new Map();    // ip -> [ms, ...]
const waiters = new Map();         // nodeIdHex -> { res, timer } (one parked long-poll)
const prekeys = new Map();         // nodeIdHex -> { spk, opks: [json,...], storedAt }

// Blind relay (protocol v2, see docs/PROTOCOL_RENDEZVOUS.md). A WebSocket at /relay/stream
// carries already-sealed frames between two authenticated nodes when no direct path exists.
// The server sees only opaque payloads plus the from/to nodeIds it must route on.
const RELAY_AUTH_TIMEOUT_MS = 10 * 1000;        // unauthenticated sockets are dropped after this
// Recipient must ack an online delivery within this (env override exists for tests).
const RELAY_COMMIT_TIMEOUT_MS = Number(process.env.RELAY_COMMIT_TIMEOUT_MS) || 15 * 1000;
const RELAY_BUFFER_TTL_MS = 60 * 1000;          // frames for an offline node wait this long
const RELAY_TICKET_TTL_MS = 10 * 60 * 1000;     // routing state for a ticket is reaped after this
const RELAY_MAX_FRAME = 2 * MAX_BODY;           // largest WebSocket frame accepted (payload + envelope)
const RELAY_MAX_INFLIGHT_PER_SOCKET = 256;      // unresolved sends one socket may have open
const RELAY_MAX_TICKETS = 100000;               // global cap; new sends are refused beyond it
const RELAY_MSG_RATE_PER_MIN = 600;             // per-socket inbound message cap
const RELAY_MAX_SOCKETS_PER_IP = 16;
const tickets = new Map();        // ticketHex -> { from, to, ref, senderSocket, delivered, timer, createdAt }
const nodeSockets = new Map();    // nodeIdHex -> authenticated socket
const relayBuffers = new Map();   // nodeIdHex -> [{ ticket, from, payload, expiresAt }, ...]
const socketsPerIp = new Map();   // ip -> count of open relay sockets

// Complete a parked /wait for nodeId with a contentless wake (or a timeout no-op).
// Guards against double-send: the waiter is removed before responding.
function resolveWaiter(nodeId, wake) {
  const w = waiters.get(nodeId);
  if (!w) return;
  waiters.delete(nodeId);
  clearTimeout(w.timer);
  try { send(w.res, 200, { wake }); } catch (e) { /* socket already gone */ }
}

function purge() {
  const cutoff = Date.now() - TTL_MS;
  for (const [k, v] of nodes) if (v.storedAt < cutoff) nodes.delete(k);
  for (const [k, v] of lastCheckin) if (v < cutoff) lastCheckin.delete(k);
  // Drop signal queues with no recent activity (also reaps never-drained queues).
  for (const [k, v] of signalActivity) {
    if (v < cutoff) { signals.delete(k); signalActivity.delete(k); }
  }
  for (const [k, v] of prekeys) if (v.storedAt < cutoff) prekeys.delete(k);
  // Reap relay routing state and frames buffered for nodes that never came back.
  const now = Date.now();
  for (const [k, v] of tickets) {
    if (v.createdAt < now - RELAY_TICKET_TTL_MS) { clearTimeout(v.timer); tickets.delete(k); }
  }
  for (const [k, arr] of relayBuffers) {
    const valid = arr.filter((f) => f.expiresAt > now);
    if (valid.length === 0) relayBuffers.delete(k); else relayBuffers.set(k, valid);
  }
  // Reap per-IP rate-limiter buckets whose timestamps have all aged out, so these maps don't
  // grow one entry per distinct/rotating source IP forever (slow memory exhaustion).
  const rlCutoff = Date.now() - 60000;
  for (const m of [findHits, signalPostHits, tapHits, prekeyFetchHits, turnCredHits]) {
    for (const [k, arr] of m) {
      const fresh = arr.filter((t) => t > rlCutoff);
      if (fresh.length === 0) m.delete(k); else m.set(k, fresh);
    }
  }
}

function allowTurnCred(ip) {
  const now = Date.now();
  const hits = (turnCredHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= TURN_CRED_RATE_LIMIT_PER_MIN) { turnCredHits.set(ip, hits); return false; }
  hits.push(now);
  turnCredHits.set(ip, hits);
  return true;
}

function allowSignalPost(ip) {
  const now = Date.now();
  const hits = (signalPostHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= SIGNAL_POST_RATE_LIMIT_PER_MIN) { signalPostHits.set(ip, hits); return false; }
  hits.push(now);
  signalPostHits.set(ip, hits);
  return true;
}

function allowTap(ip) {
  const now = Date.now();
  const hits = (tapHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= TAP_RATE_LIMIT_PER_MIN) { tapHits.set(ip, hits); return false; }
  hits.push(now);
  tapHits.set(ip, hits);
  return true;
}

// Verify a signed signal-queue drain: ts fresh, pub matches the key that checked in,
// and the Ed25519 signature over "aura-drain-v1|nodeId|ts" is valid. Auth travels in
// headers so an older client/server stays compatible (clean path, headers ignored).
function verifyDrain(nodeId, headers, node) {
  if (!node || !node.ed25519Pub) return false;
  const ts = Number(headers['x-drain-ts']);
  if (!ts || Math.abs(Date.now() - ts) > CHECKIN_FRESHNESS_MS) return false;
  const pub = headers['x-drain-pub'];
  if (pub !== node.ed25519Pub) return false;
  const sig = headers['x-drain-sig'];
  if (!sig) return false;
  let sigBuf;
  try { sigBuf = Buffer.from(sig, 'base64'); } catch { return false; }
  const msg = Buffer.from(`aura-drain-v1|${nodeId}|${ts}`, 'utf8');
  return verifyEd25519Raw(msg, sigBuf, pub);
}

// Canonical check-in string — MUST match AuroraRendezvousServer.checkinMessage.
function checkinMessage(nodeIdHex, ip, port, timestamp) {
  return Buffer.from(`aura-checkin-v1|${nodeIdHex}|${ip}|${port}|${timestamp}`, 'utf8');
}

// Verify a raw 64-byte Ed25519 signature buffer against a raw 32-byte public key.
function verifyEd25519Raw(message, sig, pubB64) {
  try {
    const rawPub = Buffer.from(pubB64, 'base64');
    if (sig.length !== 64 || rawPub.length !== 32) return false;
    // Wrap the raw key in the SPKI DER prefix Node's verifier expects.
    const der = Buffer.concat([
      Buffer.from('302a300506032b6570032100', 'hex'),
      rawPub,
    ]);
    const keyObj = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
    return crypto.verify(null, message, keyObj, sig);
  } catch (e) {
    return false;
  }
}

// Verify a check-in signature. The app signs check-ins with a Dilithium-3 + Ed25519
// HYBRID signature, wire format [4B big-endian Dilithium len][Dilithium sig][Ed25519
// sig 64B]. Node has no liboqs Dilithium, and server-side check-in verification is
// self-referential anyway (the public key is supplied in the request) — the
// post-quantum check that matters happens peer-side in verifyCandidates against the
// Dilithium key learned at pairing. So we verify the Ed25519 component here and store
// /serve the full hybrid blob unchanged so peers can do the Dilithium verification.
// A legacy 64-byte Ed25519-only signature is still accepted.
function verifyCheckinSig(message, sigB64, pubB64) {
  let sig;
  try { sig = Buffer.from(sigB64, 'base64'); } catch { return false; }
  if (sig.length === 64) return verifyEd25519Raw(message, sig, pubB64);
  if (sig.length > 4 + 64) {
    const dLen = sig.readUInt32BE(0);
    if (sig.length === 4 + dLen + 64) {
      return verifyEd25519Raw(message, sig.subarray(4 + dLen), pubB64);
    }
  }
  return false;
}

// True iff nodeId == SHA3-256(kemPub.toBytes() ‖ signPub.toBytes()). signPub.toBytes()
// is [4B BE dilithium len][dilithium][ed25519 32B]; kemPub arrives already as toBytes().
function nodeIdBindsToKeys(nodeId, kemPubB64, dilithiumPubB64, ed25519PubB64) {
  try {
    const kem = Buffer.from(kemPubB64, 'base64');
    const dil = Buffer.from(dilithiumPubB64, 'base64');
    const ed = Buffer.from(ed25519PubB64, 'base64');
    if (ed.length !== 32 || kem.length === 0 || dil.length === 0) return false;
    const dilLen = Buffer.alloc(4);
    dilLen.writeUInt32BE(dil.length, 0);
    const signPub = Buffer.concat([dilLen, dil, ed]);
    const material = Buffer.concat([kem, signPub]);
    return crypto.createHash('sha3-256').update(material).digest('hex') === nodeId;
  } catch {
    return false;
  }
}

function randomDummyIp() {
  const r = crypto.randomInt(3);
  const o = () => crypto.randomInt(256);
  const h = () => 1 + crypto.randomInt(254);
  if (r === 0) return `10.${o()}.${o()}.${h()}`;
  if (r === 1) return `192.168.${o()}.${h()}`;
  return `172.${16 + crypto.randomInt(16)}.${o()}.${h()}`;
}

function candidate(ip, port, timestamp, sigB64) {
  return { ip, port, timestamp, signature: sigB64 };
}

function shuffle(arr) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = crypto.randomInt(i + 1);
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

function allowFind(ip) {
  const now = Date.now();
  const hits = (findHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= FIND_RATE_LIMIT_PER_MIN) { findHits.set(ip, hits); return false; }
  hits.push(now);
  findHits.set(ip, hits);
  return true;
}

function allowPrekeyFetch(ip) {
  const now = Date.now();
  const hits = (prekeyFetchHits.get(ip) || []).filter((t) => now - t <= 60000);
  if (hits.length >= PREKEY_FETCH_RATE_LIMIT_PER_MIN) { prekeyFetchHits.set(ip, hits); return false; }
  hits.push(now);
  prekeyFetchHits.set(ip, hits);
  return true;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}

function readBody(req, cb) {
  let data = '';
  let tooBig = false;
  req.on('data', (chunk) => {
    data += chunk;
    if (data.length > MAX_BODY) { tooBig = true; req.destroy(); }
  });
  req.on('end', () => cb(tooBig ? null : data));
  req.on('error', () => cb(null));
}

const server = http.createServer((req, res) => {
  purge();
  const parsed = new URL(req.url, 'http://localhost');
  const url = parsed.pathname.replace(/\/+$/, '');
  const remoteIp = (req.socket.remoteAddress || '?').replace('::ffff:', '');

  if (req.method === 'POST' && url === '/checkin') {
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      let j;
      try { j = JSON.parse(body); } catch { return send(res, 400, { error: 'bad json' }); }
      const { nodeId, ip, port, timestamp, ed25519Pub, dilithiumPub, kemPub, signature } = j;
      if (!/^[0-9a-f]{64}$/.test(nodeId || '') || !ip || !(port >= 1 && port <= 65535) ||
          !timestamp || !ed25519Pub || !signature) {
        return send(res, 400, { error: 'invalid checkin fields' });
      }
      const now = Date.now();
      if (Math.abs(now - timestamp) > CHECKIN_FRESHNESS_MS) {
        return send(res, 400, { error: 'timestamp outside freshness window' });
      }
      if (now - (lastCheckin.get(nodeId) || 0) < CHECKIN_RATE_LIMIT_MS) {
        return send(res, 429, { error: 'rate limited' });
      }
      // nodeId↔key binding — stops a rogue squatting someone else's nodeId. Verified
      // when the KEM key is present; required only when STRICT_BINDING is on.
      if (kemPub && dilithiumPub) {
        if (!nodeIdBindsToKeys(nodeId, kemPub, dilithiumPub, ed25519Pub)) {
          return send(res, 401, { error: 'nodeId does not match keys' });
        }
      } else if (STRICT_BINDING) {
        return send(res, 401, { error: 'nodeId binding required' });
      }
      const msg = checkinMessage(nodeId, ip, port, timestamp);
      if (!verifyCheckinSig(msg, signature, ed25519Pub)) {
        return send(res, 401, { error: 'signature verification failed' });
      }
      lastCheckin.set(nodeId, now);
      nodes.set(nodeId, { ip, port, timestamp, sigB64: signature, ed25519Pub, storedAt: now });
      return send(res, 200, { status: 'registered', ttlSeconds: TTL_MS / 1000 });
    });
  }

  if (req.method === 'GET' && url === '/checkin') {
    return send(res, 200, { status: 'ok', hint: 'POST a signed payload to check in' });
  }

  if (req.method === 'GET' && url.startsWith('/find/')) {
    if (!allowFind(remoteIp)) return send(res, 429, { error: 'rate limited' });
    const nodeId = url.slice('/find/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    const target = nodes.get(nodeId);

    // Uniform response whether or not the node is registered: a registered node is one real
    // candidate padded to CANDIDATE_COUNT with dummies; an unknown node is CANDIDATE_COUNT
    // dummies. Both are 200 with the same shape, so the response is not an online-presence
    // oracle to the server operator or anyone without the node's pairing-learned signing key
    // (a paired contact can still tell real from dummy by verifying the hybrid signature — that
    // is inherent, since the server can't forge a node's signature to fake presence).
    const candidates = [];
    if (target) candidates.push(candidate(target.ip, target.port, target.timestamp, target.sigB64));
    // Dummies only — never disclose other registered nodes' real IPs as /find padding.
    while (candidates.length < CANDIDATE_COUNT) {
      candidates.push(candidate(
        randomDummyIp(),
        1024 + crypto.randomInt(64000),
        Date.now() - crypto.randomInt(300000),
        crypto.randomBytes(HYBRID_SIG_BYTES).toString('base64'),
      ));
    }
    return send(res, 200, { candidates: shuffle(candidates) });
  }

  if (req.method === 'POST' && url.startsWith('/signal/')) {
    const nodeId = url.slice('/signal/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!allowSignalPost(remoteIp)) return send(res, 429, { error: 'rate limited' });
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      const q = signals.get(nodeId) || [];
      // Bounded queue: REJECT when full rather than dropping the oldest. Drop-oldest let
      // anyone who knows a victim's nodeId evict the victim's pending real signals
      // (pairing/offers) by posting MAX_QUEUE_LEN junk frames; rejecting preserves them.
      if (q.length >= MAX_QUEUE_LEN) return send(res, 429, { error: 'queue full' });
      // Fair share per source IP, so a single flooder can't fill the queue on its own.
      if (q.filter((item) => item.ip === remoteIp).length >= PER_SENDER_QUEUE_MAX) {
        return send(res, 429, { error: 'per-sender queue limit reached' });
      }

      q.push({ body, ip: remoteIp });
      signals.set(nodeId, q);
      signalActivity.set(nodeId, Date.now());
      resolveWaiter(nodeId, true);   // wake a parked /wait (call offer / pairing)
      return send(res, 200, { status: 'queued' });
    });
  }

  // Contentless wake. Anyone who knows a nodeId can nudge it online (rate-limited);
  // worst case is a brief radio wake with nothing behind it — the actual message is
  // delivered directly peer-to-peer once the woken node comes online, and the server
  // stores nothing here.
  if (req.method === 'POST' && url.startsWith('/tap/')) {
    const nodeId = url.slice('/tap/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!allowTap(remoteIp)) return send(res, 429, { error: 'rate limited' });
    resolveWaiter(nodeId, true);
    return send(res, 200, { status: 'tapped' });
  }

  // Authenticated long-poll. Only the key holder behind nodeId can park (same signed
  // headers as the drain). Returns immediately if signals are already queued; otherwise
  // the response is held open until a tap/signal lands or WAIT_HOLD_MS elapses.
  if (req.method === 'GET' && url.startsWith('/wait/')) {
    const nodeId = url.slice('/wait/'.length);
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'wait authentication required' });
    }
    if ((signals.get(nodeId) || []).length > 0 || (relayBuffers.get(nodeId) || []).length > 0) {
      return send(res, 200, { wake: true });
    }
    // Replace any stale parked waiter for this node (e.g. a reconnect).
    resolveWaiter(nodeId, false);
    const timer = setTimeout(() => resolveWaiter(nodeId, false), WAIT_HOLD_MS);
    waiters.set(nodeId, { res, timer });
    // If the client disconnects while parked, drop the waiter and free the timer.
    req.on('close', () => {
      const w = waiters.get(nodeId);
      if (w && w.res === res) { waiters.delete(nodeId); clearTimeout(w.timer); }
    });
    return;
  }

  if (req.method === 'GET' && url.startsWith('/signal/')) {
    const nodeId = url.slice('/signal/'.length);
    // Authenticated drain — only the key holder behind this nodeId can read/empty it.
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'drain authentication required' });
    }
    const q = signals.get(nodeId) || [];
    signals.set(nodeId, []);
    signalActivity.set(nodeId, Date.now());
    return send(res, 200, { payloads: q.map((item) => item.body) });
  }

  // Publish a forward-secret prekey bundle. Authenticated with the same signed
  // X-Drain-* headers as the drain, so only the key holder behind nodeId may publish.
  if (req.method === 'POST' && url.startsWith('/prekeys/')) {
    const nodeId = url.slice('/prekeys/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'prekey publish auth required' });
    }
    return readBody(req, (body) => {
      if (!body) return send(res, 400, { error: 'missing body' });
      let j;
      try { j = JSON.parse(body); } catch { return send(res, 400, { error: 'bad json' }); }
      if (!j.spk || typeof j.spk !== 'object') return send(res, 400, { error: 'missing spk' });
      // The publisher's hybrid signing public key. Opaque to the server: the scanner checks
      // it hashes (with the QR's KEM key) to the nodeId before trusting the ML-DSA half.
      if (typeof j.signPub !== 'string' || j.signPub.length > 4096) {
        return send(res, 400, { error: 'missing signPub' });
      }
      const opks = Array.isArray(j.opks) ? j.opks.slice(0, PREKEY_OPK_MAX) : [];
      prekeys.set(nodeId, { spk: j.spk, opks, signPub: j.signPub, storedAt: Date.now() });
      return send(res, 200, { status: 'stored' });
    });
  }

  // Fetch a peer's bundle for a forward-secret pairing. Pops one one-time prekey per
  // request; returns the signed prekey with opk:null once the pool is exhausted.
  if (req.method === 'GET' && url.startsWith('/prekeys/')) {
    const nodeId = url.slice('/prekeys/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    // Rate-limit: each fetch pops a one-time prekey, so an unthrottled caller could drain the
    // whole OPK pool and force later handshakes down to the reused signed prekey.
    if (!allowPrekeyFetch(remoteIp)) return send(res, 429, { error: 'rate limited' });
    const b = prekeys.get(nodeId);
    if (!b) return send(res, 404, { error: 'no prekey bundle' });
    const opk = (b.opks && b.opks.length) ? b.opks.shift() : null;
    return send(res, 200, { spk: b.spk, opk: opk || null, signPub: b.signPub });
  }

  // Ephemeral Coturn credentials (RFC 7635) for NAT traversal across CGNAT.
  // Authenticated with the same signed X-Drain-* headers as the drain/wait, so only
  // the registered key holder behind nodeId can fetch short-lived relay tokens.
  if (req.method === 'GET' && url.startsWith('/turn-credentials/')) {
    const nodeId = url.slice('/turn-credentials/'.length);
    if (!/^[0-9a-f]{64}$/.test(nodeId)) return send(res, 400, { error: 'bad nodeId' });
    if (!COTURN_SHARED_SECRET) return send(res, 404, { error: 'turn not configured' });
    if (!allowTurnCred(remoteIp)) return send(res, 429, { error: 'rate limited' });
    if (!verifyDrain(nodeId, req.headers, nodes.get(nodeId))) {
      return send(res, 401, { error: 'turn auth required' });
    }
    const ttlSeconds = 900;
    const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
    // Ephemeral username formatted per coturn REST API: "<expiry>:<hashed_nodeId>"
    const username = `${expiry}:${crypto.createHash('sha256').update(nodeId).digest('hex').substring(0, 16)}`;
    const hmac = crypto.createHmac('sha1', COTURN_SHARED_SECRET);
    hmac.update(username);
    const password = hmac.digest('base64');
    return send(res, 200, {
      username,
      password,
      ttlSeconds,
      uris: [
        `stun:${TURN_REALM}:3478`,
        `turn:${TURN_REALM}:3478?transport=udp`,
        `turns:${TURN_REALM}:${TURN_PORT}?transport=tcp`
      ]
    });
  }

  // Liveness probe for the public status page. No identifiers, no counts —
  // just up/uptime. CORS-open so the static status page can fetch it.
  if (req.method === 'GET' && url === '/health') {
    const bodyH = JSON.stringify({ status: 'ok', service: 'aurora-rendezvous', uptimeSeconds: Math.floor((Date.now() - START_MS) / 1000), since: new Date(START_MS).toISOString() });
    res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' });
    return res.end(bodyH);
  }

  // AGPL-3.0 §13: offer the Corresponding Source to remote users.
  if (req.method === 'GET' && url === '/source') {
    return send(res, 200, { source: SOURCE_URL, license: 'AGPL-3.0-or-later' });
  }

  return send(res, 404, { error: 'unknown endpoint' });
});

// ── Blind relay over WebSocket (/relay/stream) ─────────────────────────────
//
// Fallback data path for two paired nodes with no direct route (both behind carrier NAT,
// or IP exposure disabled). Payloads are frames already sealed by the KEM ratchet; the
// server routes on nodeIds only and never sees content.
//
// Protocol v2 (JSON text frames):
//   server → CHALLENGE {nonce}                 on connect
//   client → AUTH {nodeId, pub, sig}           sig = Ed25519("aura-relay-v1|<nodeId>|<nonce>"),
//                                              pub must be the key registered at check-in
//   server → AUTH_OK {} | AUTH_FAIL {}         AUTH_OK is followed by any buffered INBOUNDs
//   client → RELAY {to, ref, payload}          ref: sender's own id, echoed back in replies
//   server → INBOUND {ticket, from, payload}   to the recipient (from = authenticated sender)
//   server → QUEUED {ref}                      recipient offline: buffered and woken
//   client → ACK {ticket, response}            recipient's FrameInbox result (object or null)
//   server → RESULT {ref, response}            to the sender
//   server → NACK {ref, error}                 TIMEOUT | BUSY | BAD_REQUEST
//
// Only the recipient a ticket was addressed to can ACK it, and tickets are random, so a
// third party can neither read another node's frames nor fake their delivery.

function relayMessage(nodeId, nonce) {
  return Buffer.from(`aura-relay-v1|${nodeId}|${nonce}`, 'utf8');
}

function wsFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, len]);
  } else if (len <= 0xFFFF) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function wsSendJson(socket, obj) {
  if (!socket || socket.destroyed) return;
  try { socket.write(wsFrame(0x1, Buffer.from(JSON.stringify(obj), 'utf8'))); } catch (e) { /* gone */ }
}

// Send a close frame with [code] and drop the connection.
function wsClose(socket, code) {
  if (!socket || socket.destroyed) return;
  try {
    const body = Buffer.alloc(2);
    body.writeUInt16BE(code, 0);
    socket.end(wsFrame(0x8, body));
  } catch (e) { /* gone */ }
  setTimeout(() => socket.destroy(), 1000).unref();
}

// Wake a backgrounded node so it reconnects and collects what is buffered for it.
function nudge(nodeId) {
  resolveWaiter(nodeId, true);
}

// Hand ticket's frame to its recipient if connected, else buffer it and wake the recipient.
// Returns 'delivered', 'queued', or 'full'.
function relayDeliver(ticket, entry) {
  const target = nodeSockets.get(entry.to);
  if (target && !target.destroyed && target.auroraRelay) {
    target.auroraRelay.pendingInbound.add(ticket);
    wsSendJson(target, { action: 'INBOUND', ticket, from: entry.from, payload: entry.payload });
    return 'delivered';
  }
  const buf = relayBuffers.get(entry.to) || [];
  if (buf.length >= MAX_QUEUE_LEN) return 'full';
  buf.push({ ticket, expiresAt: Date.now() + RELAY_BUFFER_TTL_MS });
  relayBuffers.set(entry.to, buf);
  nudge(entry.to);
  return 'queued';
}

function setupRelaySocket(socket, ip, head) {
  const state = { nodeId: null, pendingInbound: new Set(), inflight: new Set() };
  socket.auroraRelay = state;
  const nonce = crypto.randomBytes(32).toString('hex');
  let buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
  let windowStart = Date.now();
  let windowCount = 0;

  const authTimer = setTimeout(() => { if (!state.nodeId) wsClose(socket, 1008); }, RELAY_AUTH_TIMEOUT_MS);
  socket.setTimeout(5 * 60 * 1000, () => socket.destroy());   // reap silent connections

  function reply(obj) { wsSendJson(socket, obj); }

  function handleAuth(msg) {
    const nodeId = msg.nodeId;
    const node = typeof nodeId === 'string' && /^[0-9a-f]{64}$/.test(nodeId) ? nodes.get(nodeId) : null;
    let ok = false;
    if (node && typeof msg.pub === 'string' && msg.pub === node.ed25519Pub && typeof msg.sig === 'string') {
      ok = verifyEd25519Raw(relayMessage(nodeId, nonce), Buffer.from(msg.sig, 'base64'), msg.pub);
    }
    if (!ok) {
      reply({ action: 'AUTH_FAIL' });
      wsClose(socket, 1008);
      return;
    }
    clearTimeout(authTimer);
    state.nodeId = nodeId;
    const previous = nodeSockets.get(nodeId);
    nodeSockets.set(nodeId, socket);
    if (previous && previous !== socket) wsClose(previous, 1000);   // newest connection wins
    reply({ action: 'AUTH_OK' });

    const buffered = relayBuffers.get(nodeId) || [];
    relayBuffers.delete(nodeId);
    const now = Date.now();
    for (const b of buffered) {
      const entry = tickets.get(b.ticket);
      if (entry && b.expiresAt > now) relayDeliver(b.ticket, entry);
    }
  }

  function handleRelay(msg) {
    const ref = msg.ref;
    if (typeof ref !== 'string' || ref.length === 0 || ref.length > 128) return;
    if (typeof msg.to !== 'string' || !/^[0-9a-f]{64}$/.test(msg.to) ||
        typeof msg.payload !== 'string' || msg.payload.length > MAX_BODY) {
      return reply({ action: 'NACK', ref, error: 'BAD_REQUEST' });
    }
    if (state.inflight.size >= RELAY_MAX_INFLIGHT_PER_SOCKET || tickets.size >= RELAY_MAX_TICKETS) {
      return reply({ action: 'NACK', ref, error: 'BUSY' });
    }
    const ticket = crypto.randomBytes(16).toString('hex');
    const entry = {
      from: state.nodeId, to: msg.to, ref, payload: msg.payload,
      senderSocket: socket, timer: null, createdAt: Date.now(),
    };
    tickets.set(ticket, entry);
    const outcome = relayDeliver(ticket, entry);
    if (outcome === 'full') {
      tickets.delete(ticket);
      return reply({ action: 'NACK', ref, error: 'BUSY' });
    }
    if (outcome === 'queued') return reply({ action: 'QUEUED', ref });
    // Delivered to a live socket: the recipient must ack within the commit window, or the
    // sender is told TIMEOUT (it keeps the message pending and retries later).
    state.inflight.add(ticket);
    entry.timer = setTimeout(() => {
      if (!state.inflight.delete(ticket)) return;
      entry.senderSocket = null;
      reply({ action: 'NACK', ref, error: 'TIMEOUT' });
    }, RELAY_COMMIT_TIMEOUT_MS);
  }

  function handleAck(msg) {
    const ticket = msg.ticket;
    const entry = typeof ticket === 'string' ? tickets.get(ticket) : null;
    if (!entry || entry.to !== state.nodeId) return;   // only the addressed recipient can ack
    tickets.delete(ticket);
    state.pendingInbound.delete(ticket);
    clearTimeout(entry.timer);
    const sender = entry.senderSocket;
    if (sender && !sender.destroyed && sender.auroraRelay) {
      sender.auroraRelay.inflight.delete(ticket);
      const r = msg.response;
      const response = (r && typeof r === 'object' && !Array.isArray(r)) ? r : null;
      wsSendJson(sender, { action: 'RESULT', ref: entry.ref, response });
    }
  }

  function handleText(text) {
    let msg;
    try { msg = JSON.parse(text); } catch { return; }
    if (!msg || typeof msg !== 'object') return;
    if (!state.nodeId) {
      if (msg.action === 'AUTH') handleAuth(msg); else wsClose(socket, 1008);
      return;
    }
    switch (msg.action) {
      case 'RELAY': return handleRelay(msg);
      case 'ACK': return handleAck(msg);
      default: return;
    }
  }

  // Parse client frames. Clients MUST mask (RFC 6455 §5.1); fragmented messages and
  // binary frames are not part of this protocol and close the connection.
  function onData(chunk) {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    if (buf.length > RELAY_MAX_FRAME + 14) return wsClose(socket, 1009);
    while (buf.length >= 2) {
      const fin = (buf[0] & 0x80) !== 0;
      const rsv = buf[0] & 0x70;
      const opcode = buf[0] & 0x0F;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7F;
      let off = 2;
      if (rsv || !masked) return wsClose(socket, 1002);
      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (buf.length < 10) return;
        if (buf.readUInt32BE(2) !== 0) return wsClose(socket, 1009);
        len = buf.readUInt32BE(6);
        off = 10;
      }
      if (len > RELAY_MAX_FRAME) return wsClose(socket, 1009);
      if (buf.length < off + 4 + len) return;
      const mask = buf.subarray(off, off + 4);
      const payload = Buffer.from(buf.subarray(off + 4, off + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      buf = buf.subarray(off + 4 + len);

      if (opcode >= 0x8) {                      // control frames: ≤125 bytes, never fragmented
        if (len > 125 || !fin) return wsClose(socket, 1002);
        if (opcode === 0x8) return wsClose(socket, 1000);
        if (opcode === 0x9) { try { socket.write(wsFrame(0xA, payload)); } catch (e) { /* gone */ } }
        continue;
      }
      if (opcode !== 0x1 || !fin) return wsClose(socket, 1003);

      const now = Date.now();
      if (now - windowStart > 60000) { windowStart = now; windowCount = 0; }
      if (++windowCount > RELAY_MSG_RATE_PER_MIN) return wsClose(socket, 1008);

      handleText(payload.toString('utf8'));
    }
  }

  socket.on('data', (chunk) => {
    // One bad frame must cost at most this connection, never the whole server.
    try { onData(chunk); } catch (e) { socket.destroy(); }
  });

  socket.on('close', () => {
    clearTimeout(authTimer);
    const n = (socketsPerIp.get(ip) || 1) - 1;
    if (n <= 0) socketsPerIp.delete(ip); else socketsPerIp.set(ip, n);
    if (state.nodeId && nodeSockets.get(state.nodeId) === socket) nodeSockets.delete(state.nodeId);
    // Frames handed to us but never acked go back to the buffer (or to our replacement
    // connection) — the recipient de-duplicates by message id, so redelivery is safe.
    for (const t of state.pendingInbound) {
      const entry = tickets.get(t);
      if (entry && relayDeliver(t, entry) === 'full') tickets.delete(t);
    }
    // Our own unresolved sends can no longer be answered.
    for (const t of state.inflight) {
      const entry = tickets.get(t);
      if (entry) entry.senderSocket = null;
    }
  });

  socket.on('error', () => {
    try { socket.destroy(); } catch (e) { /* already gone */ }
  });

  wsSendJson(socket, { action: 'CHALLENGE', nonce });
}

server.on('upgrade', (req, socket, head) => {
  socket.on('error', () => { try { socket.destroy(); } catch (e) { /* already gone */ } });
  const path = (req.url || '').split('?')[0].replace(/\/+$/, '');
  const key = req.headers['sec-websocket-key'];
  if (path !== '/relay/stream' ||
      (req.headers['upgrade'] || '').toLowerCase() !== 'websocket' ||
      req.headers['sec-websocket-version'] !== '13' || !key) {
    socket.destroy();
    return;
  }
  const ip = (socket.remoteAddress || '?').replace('::ffff:', '');
  const open = socketsPerIp.get(ip) || 0;
  if (open >= RELAY_MAX_SOCKETS_PER_IP) {
    socket.end('HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n');
    return;
  }
  socketsPerIp.set(ip, open + 1);
  const accept = crypto.createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  socket.setNoDelay(true);
  setupRelaySocket(socket, ip, head);
});

// Relay-only traffic never hits the HTTP handler, so reap state on a timer too.
setInterval(purge, 30 * 1000).unref();

server.listen(PORT, HOST, () => {
  // The only lines ever logged — no payloads, no IPs, no message content.
  console.log(`Aurora rendezvous server listening on ${HOST}:${PORT}`);
  console.log(`Source (AGPL-3.0): ${SOURCE_URL}`);
});
