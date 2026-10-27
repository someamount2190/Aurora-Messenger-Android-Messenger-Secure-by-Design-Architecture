// Integration tests for the standalone rendezvous server. Each test file run spawns a real
// server process on a free port and talks to it over HTTP and raw WebSocket frames, so the
// tests exercise exactly what the app sees. Run: node --test (from rendezvous-server/).

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');

const COMMIT_TIMEOUT_MS = 600;
let port;
let server;
let serverExit = null;

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer().listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

before(async () => {
  port = await freePort();
  server = spawn(process.execPath, [path.join(__dirname, '..', 'index.js')], {
    env: { ...process.env, PORT: String(port), HOST: '127.0.0.1',
           RELAY_COMMIT_TIMEOUT_MS: String(COMMIT_TIMEOUT_MS), COTURN_SHARED_SECRET: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  server.stderr.on('data', (d) => { stderr += d; });
  server.on('exit', (code) => { serverExit = { code, stderr }; });
  await new Promise((resolve) => server.stdout.once('data', resolve));
});

after(() => { if (server && !serverExit) server.kill(); });

function assertServerAlive() {
  assert.equal(serverExit, null, `server died: ${serverExit && serverExit.stderr}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── HTTP helpers ──────────────────────────────────────────────────────────

function request(method, urlPath, { body, headers } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: urlPath, method, headers }, (res) => {
      let data = '';
      res.on('data', (c) => { data += c; });
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(data); } catch { /* not json */ }
        resolve({ status: res.statusCode, json });
      });
    });
    req.on('error', reject);
    req.end(body);
  });
}

// A node identity the server accepts: random stand-ins for the KEM and ML-DSA public keys
// (the server only hashes them) plus a real Ed25519 key it verifies signatures with.
function makeNode() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const edPub = publicKey.export({ format: 'der', type: 'spki' }).subarray(12);
  const kemPub = crypto.randomBytes(1216);
  const dilPub = crypto.randomBytes(1952);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(dilPub.length);
  const signPub = Buffer.concat([len, dilPub, edPub]);
  const nodeId = crypto.createHash('sha3-256').update(Buffer.concat([kemPub, signPub])).digest('hex');
  const sign = (msg) => crypto.sign(null, Buffer.from(msg, 'utf8'), privateKey).toString('base64');
  return {
    nodeId, sign, signPub: signPub.toString('base64'),
    edPubB64: edPub.toString('base64'), kemPubB64: kemPub.toString('base64'), dilPubB64: dilPub.toString('base64'),
  };
}

async function checkIn(node) {
  const timestamp = Date.now();
  const ip = '10.0.0.1';
  const p = 8765;
  const res = await request('POST', '/checkin', { body: JSON.stringify({
    nodeId: node.nodeId, ip, port: p, timestamp,
    ed25519Pub: node.edPubB64, dilithiumPub: node.dilPubB64, kemPub: node.kemPubB64,
    signature: node.sign(`aura-checkin-v1|${node.nodeId}|${ip}|${p}|${timestamp}`),
  }) });
  assert.equal(res.status, 200, JSON.stringify(res.json));
  return node;
}

function drainHeaders(node) {
  const ts = Date.now();
  return {
    'X-Drain-Ts': String(ts),
    'X-Drain-Pub': node.edPubB64,
    'X-Drain-Sig': node.sign(`aura-drain-v1|${node.nodeId}|${ts}`),
  };
}

// ── Minimal masked WebSocket client ───────────────────────────────────────

function maskedFrame(opcode, payload, { mask = true, fin = true } = {}) {
  const len = payload.length;
  let header;
  if (len < 126) header = Buffer.from([(fin ? 0x80 : 0) | opcode, (mask ? 0x80 : 0) | len]);
  else {
    header = Buffer.alloc(4);
    header[0] = (fin ? 0x80 : 0) | opcode;
    header[1] = (mask ? 0x80 : 0) | 126;
    header.writeUInt16BE(len, 2);
  }
  if (!mask) return Buffer.concat([header, payload]);
  const key = crypto.randomBytes(4);
  const body = Buffer.from(payload.map((b, i) => b ^ key[i & 3]));
  return Buffer.concat([header, key, body]);
}

function connect() {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, '127.0.0.1');
    const inbox = [];
    const waiters = [];
    let buf = Buffer.alloc(0);
    let upgraded = false;
    const ws = {
      inbox,
      closed: false,
      send(obj) { sock.write(maskedFrame(0x1, Buffer.from(JSON.stringify(obj)))); },
      raw(bytes) { sock.write(bytes); },
      close() { sock.destroy(); },
      // Resolve with the next message matching [pred] (already-received ones count).
      next(pred, timeoutMs = 2000) {
        const i = inbox.findIndex(pred);
        if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
        return new Promise((res, rej) => {
          const w = { pred, res };
          waiters.push(w);
          setTimeout(() => {
            const k = waiters.indexOf(w);
            if (k >= 0) { waiters.splice(k, 1); rej(new Error('timed out waiting for message')); }
          }, timeoutMs);
        });
      },
      closedPromise: null,
    };
    ws.closedPromise = new Promise((r) => sock.on('close', () => { ws.closed = true; r(); }));
    sock.on('error', () => {});
    sock.on('connect', () => {
      sock.write('GET /relay/stream HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
        `Sec-WebSocket-Key: ${crypto.randomBytes(16).toString('base64')}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
    });
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!upgraded) {
        const i = buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        if (!buf.subarray(0, i).toString().startsWith('HTTP/1.1 101')) return reject(new Error('no upgrade'));
        buf = buf.subarray(i + 4);
        upgraded = true;
        resolve(ws);
      }
      while (buf.length >= 2) {
        let len = buf[1] & 0x7f;
        let off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        if (buf.length < off + len) return;
        const opcode = buf[0] & 0x0f;
        const payload = buf.subarray(off, off + len);
        buf = buf.subarray(off + len);
        if (opcode !== 0x1) continue;
        const msg = JSON.parse(payload.toString());
        const w = waiters.find((x) => x.pred(msg));
        if (w) { waiters.splice(waiters.indexOf(w), 1); w.res(msg); } else inbox.push(msg);
      }
    });
  });
}

const is = (action) => (m) => m.action === action;

async function authed(node) {
  const ws = await connect();
  const { nonce } = await ws.next(is('CHALLENGE'));
  ws.send({ action: 'AUTH', nodeId: node.nodeId, pub: node.edPubB64,
            sig: node.sign(`aura-relay-v1|${node.nodeId}|${nonce}`) });
  await ws.next(is('AUTH_OK'));
  return ws;
}

// ── Relay ─────────────────────────────────────────────────────────────────

test('relay to an offline peer is queued and the server survives', async () => {
  const alice = await checkIn(makeNode());
  const ws = await authed(alice);
  ws.send({ action: 'RELAY', to: 'd'.repeat(64), ref: 'm-offline', payload: '{"t":"msg"}' });
  const r = await ws.next(is('QUEUED'));
  assert.equal(r.ref, 'm-offline');
  await sleep(100);
  assertServerAlive();
  assert.equal((await request('GET', '/health')).status, 200);
  ws.close();
});

test('online delivery returns the recipient\'s own ack to the sender under its ref', async () => {
  const alice = await checkIn(makeNode());
  const bob = await checkIn(makeNode());
  const a = await authed(alice);
  const b = await authed(bob);
  a.send({ action: 'RELAY', to: bob.nodeId, ref: 'msg-id-123', payload: '{"t":"msg","id":"msg-id-123"}' });
  const inbound = await b.next(is('INBOUND'));
  assert.equal(inbound.from, alice.nodeId);
  assert.equal(JSON.parse(inbound.payload).id, 'msg-id-123');
  b.send({ action: 'ACK', ticket: inbound.ticket, response: { t: 'ack', id: 'msg-id-123' } });
  const result = await a.next(is('RESULT'));
  assert.equal(result.ref, 'msg-id-123');
  assert.deepEqual(result.response, { t: 'ack', id: 'msg-id-123' });
  a.close(); b.close();
});

test('a recipient that cannot process the frame reports a null response', async () => {
  const alice = await checkIn(makeNode());
  const bob = await checkIn(makeNode());
  const a = await authed(alice);
  const b = await authed(bob);
  a.send({ action: 'RELAY', to: bob.nodeId, ref: 'bad', payload: '{}' });
  const inbound = await b.next(is('INBOUND'));
  b.send({ action: 'ACK', ticket: inbound.ticket, response: null });
  const result = await a.next(is('RESULT'));
  assert.equal(result.response, null);
  a.close(); b.close();
});

test('nothing but AUTH is accepted before authentication', async () => {
  const ws = await connect();
  await ws.next(is('CHALLENGE'));
  ws.send({ action: 'RELAY', to: 'a'.repeat(64), ref: 'x', payload: '{}' });
  await ws.closedPromise;
  assertServerAlive();
});

test('claiming another node\'s id fails without its key', async () => {
  const bob = await checkIn(makeNode());
  const mallory = makeNode();
  // Mallory's own key, Bob's nodeId.
  let ws = await connect();
  let { nonce } = await ws.next(is('CHALLENGE'));
  ws.send({ action: 'AUTH', nodeId: bob.nodeId, pub: mallory.edPubB64,
            sig: mallory.sign(`aura-relay-v1|${bob.nodeId}|${nonce}`) });
  await ws.next(is('AUTH_FAIL'));
  await ws.closedPromise;
  // Bob's public key, but Mallory can't produce Bob's signature.
  ws = await connect();
  ({ nonce } = await ws.next(is('CHALLENGE')));
  ws.send({ action: 'AUTH', nodeId: bob.nodeId, pub: bob.edPubB64,
            sig: mallory.sign(`aura-relay-v1|${bob.nodeId}|${nonce}`) });
  await ws.next(is('AUTH_FAIL'));
});

test('a signature for one challenge cannot be replayed on another connection', async () => {
  const bob = await checkIn(makeNode());
  const first = await connect();
  const { nonce } = await first.next(is('CHALLENGE'));
  const sig = bob.sign(`aura-relay-v1|${bob.nodeId}|${nonce}`);
  const second = await connect();
  await second.next(is('CHALLENGE'));
  second.send({ action: 'AUTH', nodeId: bob.nodeId, pub: bob.edPubB64, sig });
  await second.next(is('AUTH_FAIL'));
  first.close();
});

test('only the addressed recipient can ack a ticket', async () => {
  const alice = await checkIn(makeNode());
  const bob = await checkIn(makeNode());
  const mallory = await checkIn(makeNode());
  const a = await authed(alice);
  const b = await authed(bob);
  const m = await authed(mallory);
  a.send({ action: 'RELAY', to: bob.nodeId, ref: 'r1', payload: '{}' });
  const inbound = await b.next(is('INBOUND'));
  m.send({ action: 'ACK', ticket: inbound.ticket, response: { t: 'ack', id: 'r1' } });
  await sleep(150);
  assert.equal(a.inbox.filter(is('RESULT')).length, 0, 'a third party\'s ack must not reach the sender');
  b.send({ action: 'ACK', ticket: inbound.ticket, response: { t: 'ack', id: 'r1' } });
  await a.next(is('RESULT'));
  a.close(); b.close(); m.close();
});

test('an unacked online delivery times out back to the sender', async () => {
  const alice = await checkIn(makeNode());
  const bob = await checkIn(makeNode());
  const a = await authed(alice);
  const b = await authed(bob);
  a.send({ action: 'RELAY', to: bob.nodeId, ref: 'slow', payload: '{}' });
  await b.next(is('INBOUND'));
  const nack = await a.next(is('NACK'), COMMIT_TIMEOUT_MS + 1000);
  assert.deepEqual([nack.ref, nack.error], ['slow', 'TIMEOUT']);
  a.close(); b.close();
});

test('buffered frames are delivered when the recipient connects, and acks still route', async () => {
  const alice = await checkIn(makeNode());
  const carol = await checkIn(makeNode());
  const a = await authed(alice);
  a.send({ action: 'RELAY', to: carol.nodeId, ref: 'later', payload: '{"id":"later"}' });
  await a.next(is('QUEUED'));
  const c = await authed(carol);
  const inbound = await c.next(is('INBOUND'));
  assert.equal(inbound.from, alice.nodeId);
  c.send({ action: 'ACK', ticket: inbound.ticket, response: { t: 'ack', id: 'later' } });
  const result = await a.next(is('RESULT'));
  assert.equal(result.ref, 'later');
  a.close(); c.close();
});

test('a frame the recipient dropped without acking is redelivered on reconnect', async () => {
  const alice = await checkIn(makeNode());
  const bob = await checkIn(makeNode());
  const a = await authed(alice);
  let b = await authed(bob);
  a.send({ action: 'RELAY', to: bob.nodeId, ref: 'retry', payload: '{"id":"retry"}' });
  await b.next(is('INBOUND'));
  b.close();
  await b.closedPromise;
  await sleep(100);
  b = await authed(bob);
  const again = await b.next(is('INBOUND'));
  assert.equal(JSON.parse(again.payload).id, 'retry');
  a.close(); b.close();
});

test('pending /wait is woken when a frame is buffered for the node', async () => {
  const alice = await checkIn(makeNode());
  const dave = await checkIn(makeNode());
  const waiting = request('GET', `/wait/${dave.nodeId}`, { headers: drainHeaders(dave) });
  await sleep(150);
  const a = await authed(alice);
  a.send({ action: 'RELAY', to: dave.nodeId, ref: 'wake', payload: '{}' });
  const res = await waiting;
  assert.deepEqual(res.json, { wake: true });
  a.close();
});

test('malformed frames close only that connection', async () => {
  for (const bad of [
    maskedFrame(0x1, Buffer.from('{"action":"AUTH"}'), { mask: false }),     // unmasked
    maskedFrame(0x1, Buffer.from('{"a":'), { fin: false }),                    // fragmented
    maskedFrame(0x2, Buffer.from([1, 2, 3])),                                  // binary
    Buffer.from([0x81, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]), // 2^64-1 length
  ]) {
    const ws = await connect();
    await ws.next(is('CHALLENGE'));
    ws.raw(bad);
    await ws.closedPromise;
  }
  assertServerAlive();
  assert.equal((await request('GET', '/health')).status, 200);
});

test('non-JSON and unknown messages after auth are ignored, not fatal', async () => {
  const alice = await checkIn(makeNode());
  const ws = await authed(alice);
  ws.raw(maskedFrame(0x1, Buffer.from('not json')));
  ws.send({ action: 'NOPE' });
  ws.send({ action: 'RELAY', to: 'zz', ref: 'bad-to', payload: '{}' });
  const nack = await ws.next(is('NACK'));
  assert.deepEqual([nack.ref, nack.error], ['bad-to', 'BAD_REQUEST']);
  assert.equal(ws.closed, false);
  assertServerAlive();
  ws.close();
});

// ── HTTP endpoints touched by the fixes ───────────────────────────────────

test('signal queue caps each source IP regardless of the claimed sender', async () => {
  const target = 'c'.repeat(64);
  const codes = [];
  for (let i = 0; i < 10; i++) {
    codes.push((await request('POST', `/signal/${target}`, { body: JSON.stringify({ from: `spoof${i}` }) })).status);
  }
  assert.deepEqual(codes, [200, 200, 200, 200, 200, 200, 200, 200, 429, 429]);
});

test('drained signals come back as the original payload strings', async () => {
  const node = await checkIn(makeNode());
  await request('POST', `/signal/${node.nodeId}`, { body: '{"type":"x"}' });
  const res = await request('GET', `/signal/${node.nodeId}`, { headers: drainHeaders(node) });
  assert.deepEqual(res.json, { payloads: ['{"type":"x"}'] });
});

test('/find is not a presence oracle: unknown and registered nodes look identical', async () => {
  // Registered node: 200 with CANDIDATE_COUNT candidates (1 real + dummies).
  const node = await checkIn(makeNode());
  const known = await request('GET', `/find/${node.nodeId}`);
  assert.equal(known.status, 200, JSON.stringify(known.json));
  assert.equal(known.json.candidates.length, 10);

  // Unknown (well-formed) node: ALSO 200 with the same count of candidates (all dummies),
  // so the server operator / anyone without the node's signing key can't tell it isn't online.
  const unknownId = crypto.randomBytes(32).toString('hex');
  const unknown = await request('GET', `/find/${unknownId}`);
  assert.equal(unknown.status, 200, JSON.stringify(unknown.json));
  assert.equal(unknown.json.candidates.length, 10);

  // Malformed nodeId is still rejected (format check, not a presence signal).
  const bad = await request('GET', '/find/not-a-valid-node-id');
  assert.equal(bad.status, 400);
});

test('turn credentials are disabled when no shared secret is configured', async () => {
  const node = await checkIn(makeNode());
  const res = await request('GET', `/turn-credentials/${node.nodeId}`, { headers: drainHeaders(node) });
  assert.equal(res.status, 404);
});

test('prekey bundles require and return the publisher\'s signing key', async () => {
  const node = await checkIn(makeNode());
  const spk = { id: 's1', pub: 'AAAA', sig: 'BBBB' };
  const missing = await request('POST', `/prekeys/${node.nodeId}`,
    { headers: drainHeaders(node), body: JSON.stringify({ spk, opks: [] }) });
  assert.equal(missing.status, 400);
  const ok = await request('POST', `/prekeys/${node.nodeId}`,
    { headers: drainHeaders(node), body: JSON.stringify({ spk, opks: [{ id: 'o1', pub: 'C', sig: 'D' }], signPub: node.signPub }) });
  assert.equal(ok.status, 200);
  const got = await request('GET', `/prekeys/${node.nodeId}`);
  assert.equal(got.json.signPub, node.signPub);
  assert.equal(got.json.opk.id, 'o1');
});
