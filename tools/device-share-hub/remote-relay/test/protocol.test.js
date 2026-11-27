import assert from "node:assert/strict";
import test from "node:test";
import { canonicalize, validCertificate, WorkspaceRelayCore } from "../src/relay-core.js";
import { materializeR2WithRange } from "../src/extensions.js";

function base64Url(bytes) {
  return Buffer.from(bytes).toString("base64url");
}

async function generateIdentity() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicKey = await crypto.subtle.exportKey("jwk", pair.publicKey);
  delete publicKey.key_ops;
  delete publicKey.ext;
  return { pair, publicKey };
}

async function sign(privateKey, value) {
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    new TextEncoder().encode(canonicalize(value)),
  );
  return base64Url(signature);
}

async function workspaceIdFor(publicKey) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(canonicalize(publicKey)),
  );
  return `ws_${Buffer.from(digest).toString("hex").slice(0, 32)}`;
}

class MemoryStorage {
  constructor() {
    this.values = new Map();
    this.alarm = null;
  }
  async get(key) {
    return this.values.get(key);
  }
  async put(key, value) {
    this.values.set(key, structuredClone(value));
  }
  async delete(key) {
    for (const item of Array.isArray(key) ? key : [key]) this.values.delete(item);
  }
  async list({ prefix }) {
    return new Map([...this.values].filter(([key]) => key.startsWith(prefix)));
  }
  async getAlarm() {
    return this.alarm;
  }
  async setAlarm(value) {
    this.alarm = value;
  }
}

class MemoryBucket {
  constructor() {
    this.values = new Map();
  }
  async put(key, body, options) {
    const bytes = new Uint8Array(await new Response(body).arrayBuffer());
    this.values.set(key, { bytes, customMetadata: options?.customMetadata || {} });
  }
  async head(key) {
    const value = this.values.get(key);
    return value ? { size: value.bytes.byteLength, customMetadata: value.customMetadata } : null;
  }
  async get(key, options) {
    const value = this.values.get(key);
    if (!value) return null;
    if (options?.range) {
      const offset = Number(options.range.offset || 0);
      const length = Number(options.range.length ?? value.bytes.byteLength - offset);
      const slice = value.bytes.slice(offset, offset + length);
      return {
        body: slice,
        size: slice.byteLength,
        customMetadata: value.customMetadata,
      };
    }
    return {
      body: value.bytes,
      size: value.bytes.byteLength,
      customMetadata: value.customMetadata,
    };
  }
  async delete(key) {
    for (const item of Array.isArray(key) ? key : [key]) this.values.delete(item);
  }
}

function createRelay() {
  const storage = new MemoryStorage();
  const bucket = new MemoryBucket();
  const context = {
    storage,
    acceptWebSocket() {},
    getWebSockets() {
      return [];
    },
  };
  return { relay: new WorkspaceRelayCore(context, { REMOTE_OBJECTS: bucket }), storage, bucket };
}

async function jsonRequest(path, body, token = null, method = "POST") {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  return new Request(`https://relay.test${path}`, {
    method,
    headers,
    body: method === "GET" ? undefined : JSON.stringify(body),
  });
}

async function bootstrap() {
  const { relay, storage, bucket } = createRelay();
  const admin = await generateIdentity();
  const member = await generateIdentity();
  const now = Date.now();
  const adminCertificate = {
    version: 1,
    workspaceId: await workspaceIdFor(admin.publicKey),
    deviceId: "device_admin_01",
    deviceName: "素材投送中控",
    role: "admin",
    signingPublicKey: admin.publicKey,
    agreementPublicKey: admin.publicKey,
    serial: 1,
    issuedAt: now,
    expiresAt: now + 365 * 24 * 60 * 60 * 1000,
  };
  const adminSignature = await sign(admin.pair.privateKey, adminCertificate);
  let response = await relay.fetch(
    await jsonRequest("/v1/workspaces/register", {
      certificate: adminCertificate,
      certificateSignature: adminSignature,
    }),
  );
  assert.equal(response.status, 201);

  const memberCertificate = {
    ...adminCertificate,
    deviceId: "device_member_01",
    deviceName: "Redmi K60",
    role: "member",
    signingPublicKey: member.publicKey,
    agreementPublicKey: member.publicKey,
    serial: 1,
  };
  const memberCertificateSignature = await sign(admin.pair.privateKey, memberCertificate);
  response = await relay.fetch(
    await jsonRequest("/v1/challenges", {
      workspaceId: memberCertificate.workspaceId,
      deviceId: memberCertificate.deviceId,
    }),
  );
  assert.equal(response.status, 201);
  const challenge = await response.json();
  const challengeSignature = await sign(member.pair.privateKey, challenge);
  response = await relay.fetch(
    await jsonRequest("/v1/sessions", {
      certificate: memberCertificate,
      certificateSignature: memberCertificateSignature,
      challengeId: challenge.challengeId,
      challengeSignature,
    }),
  );
  assert.equal(response.status, 201);
  const session = await response.json();

  return {
    relay,
    storage,
    bucket,
    admin,
    member,
    adminCertificate,
    memberCertificate,
    token: session.token,
  };
}

async function createAdminSession(state) {
  const challengeResponse = await state.relay.fetch(
    await jsonRequest("/v1/challenges", {
      workspaceId: state.adminCertificate.workspaceId,
      deviceId: state.adminCertificate.deviceId,
    }),
  );
  const challenge = await challengeResponse.json();
  const sessionResponse = await state.relay.fetch(
    await jsonRequest("/v1/sessions", {
      certificate: state.adminCertificate,
      certificateSignature: await sign(state.admin.pair.privateKey, state.adminCertificate),
      challengeId: challenge.challengeId,
      challengeSignature: await sign(state.admin.pair.privateKey, challenge),
    }),
  );
  assert.equal(sessionResponse.status, 201);
  return (await sessionResponse.json()).token;
}

test("canonical JSON sorts nested object keys deterministically", () => {
  assert.equal(
    canonicalize({ z: 1, a: { y: true, b: ["x", 2] } }),
    '{"a":{"b":["x",2],"y":true},"z":1}',
  );
});

test("certificate validation rejects private key material", async () => {
  const identity = await generateIdentity();
  const now = Date.now();
  assert.equal(
    validCertificate({
      version: 1,
      workspaceId: "workspace_test_01",
      deviceId: "device_admin_01",
      role: "admin",
      signingPublicKey: { ...identity.publicKey, d: "must-not-be-sent" },
      agreementPublicKey: identity.publicKey,
      serial: 1,
      issuedAt: now,
      expiresAt: now + 1000,
    }),
    false,
  );
});

test("workspace id is cryptographically bound to the admin key", async () => {
  const state = await bootstrap();
  const attacker = await generateIdentity();
  const certificate = {
    ...state.adminCertificate,
    signingPublicKey: attacker.publicKey,
    agreementPublicKey: attacker.publicKey,
  };
  const response = await state.relay.fetch(
    await jsonRequest("/v1/workspaces/register", {
      certificate,
      certificateSignature: await sign(attacker.pair.privateKey, certificate),
    }),
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).code, "invalid_workspace_id");
});

test("invalid challenge signature cannot create a session", async () => {
  const state = await bootstrap();
  const stranger = await generateIdentity();
  let response = await state.relay.fetch(
    await jsonRequest("/v1/challenges", {
      workspaceId: state.memberCertificate.workspaceId,
      deviceId: state.memberCertificate.deviceId,
    }),
  );
  const challenge = await response.json();
  response = await state.relay.fetch(
    await jsonRequest("/v1/sessions", {
      certificate: state.memberCertificate,
      certificateSignature: await sign(state.admin.pair.privateKey, state.memberCertificate),
      challengeId: challenge.challengeId,
      challengeSignature: await sign(stranger.pair.privateKey, challenge),
    }),
  );
  assert.equal(response.status, 403);
  assert.equal((await response.json()).code, "challenge_signature_invalid");
});

test("a self-signed replacement admin cannot create a session", async () => {
  const state = await bootstrap();
  const attacker = await generateIdentity();
  const certificate = {
    ...state.adminCertificate,
    signingPublicKey: attacker.publicKey,
    agreementPublicKey: attacker.publicKey,
  };
  let response = await state.relay.fetch(
    await jsonRequest("/v1/challenges", {
      workspaceId: certificate.workspaceId,
      deviceId: certificate.deviceId,
    }),
  );
  const challenge = await response.json();
  response = await state.relay.fetch(
    await jsonRequest("/v1/sessions", {
      certificate,
      certificateSignature: await sign(attacker.pair.privateKey, certificate),
      challengeId: challenge.challengeId,
      challengeSignature: await sign(attacker.pair.privateKey, challenge),
    }),
  );
  assert.equal(response.status, 403);
  assert.equal((await response.json()).code, "admin_identity_mismatch");
});

test("admin can disable remote access and invalidate existing device sessions", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  let p2pResponse = await state.relay.fetch(
    await jsonRequest("/v1/p2p/sessions", {
      recipientDeviceId: state.memberCertificate.deviceId,
    }, adminToken),
  );
  assert.equal(p2pResponse.status, 201);
  const p2pSessionId = (await p2pResponse.json()).p2p.sessionId;
  let response = await state.relay.fetch(
    await jsonRequest(
      `/v1/devices/${state.memberCertificate.deviceId}/remote`,
      { allowed: false },
      adminToken,
    ),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).remoteAllowed, false);

  response = await state.relay.fetch(
    new Request("https://relay.test/v1/devices", {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).code, "unauthorized");
  p2pResponse = await state.relay.fetch(
    new Request(`https://relay.test/v1/p2p/sessions/${p2pSessionId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    }),
  );
  assert.equal(p2pResponse.status, 200);
  assert.equal((await p2pResponse.json()).p2p.state, "failed");
});

test("heartbeat makes a device visible as online without a websocket", async () => {
  const state = await bootstrap();
  let response = await state.relay.fetch(
    await jsonRequest("/v1/presence", {
      workCount: 4,
      workCounts: { total: 4, conversion: 4, traffic: 0, uncategorized: 0 },
      appVersion: "0.6.46",
      versionCode: 84,
      updateCapability: "apk-push-v1",
    }, state.token),
  );
  assert.equal(response.status, 200);

  response = await state.relay.fetch(
    new Request("https://relay.test/v1/devices", {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  assert.equal(response.status, 200);
  const devices = (await response.json()).devices;
  assert.equal(
    devices.find((device) => device.deviceId === state.memberCertificate.deviceId)?.online,
    true,
  );
  const device = devices.find((item) => item.deviceId === state.memberCertificate.deviceId);
  assert.deepEqual(device.workCounts, {
    total: 4,
    conversion: 4,
    traffic: 0,
    uncategorized: 0,
  });
  assert.equal(device.workCount, 4);
  assert.equal(device.appVersion, "0.6.46");
  assert.equal(device.versionCode, 84);
  assert.equal(device.updateCapability, "apk-push-v1");
});

test("P2P signaling is authorized, ordered, and separate from relay file bytes", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  let response = await state.relay.fetch(
    await jsonRequest("/v1/p2p/sessions", {
      recipientDeviceId: state.memberCertificate.deviceId,
      protocol: "webrtc-datachannel-v1",
    }, adminToken),
  );
  assert.equal(response.status, 201);
  const p2p = (await response.json()).p2p;
  assert.equal(p2p.protocol, "webrtc-datachannel-v1");
  assert.equal(p2p.state, "offer-pending");

  response = await state.relay.fetch(
    await jsonRequest(`/v1/p2p/sessions/${p2p.sessionId}/signals`, {
      type: "offer",
      data: { type: "offer", sdp: "v=0\r\n..." },
    }, adminToken),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).state, "offer-sent");

  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/p2p/sessions/${p2p.sessionId}`, {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  assert.equal(response.status, 200);
  const memberView = (await response.json()).p2p;
  assert.equal(memberView.signals.length, 1);
  assert.equal(memberView.signals[0].fromDeviceId, state.adminCertificate.deviceId);

  response = await state.relay.fetch(
    await jsonRequest(`/v1/p2p/sessions/${p2p.sessionId}/signals`, {
      type: "answer",
      data: { type: "answer", sdp: "v=0\r\n..." },
    }, state.token),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).state, "answer-sent");

  response = await state.relay.fetch(
    await jsonRequest(`/v1/p2p/sessions/${p2p.sessionId}/close`, {}, state.token),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).state, "closed");
  assert.equal(state.bucket.values.size, 0);
});

test("sender outbox and recipient inbox expose committed transfers", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const ciphertext = crypto.getRandomValues(new Uint8Array(16));
  const cipherSha256 = Buffer.from(
    await crypto.subtle.digest("SHA-256", ciphertext),
  ).toString("hex");

  let response = await state.relay.fetch(
    await jsonRequest(
      "/v1/transfers",
      {
        recipientDeviceId: state.memberCertificate.deviceId,
        encryptedKeyPackage: { algorithm: "P256-HKDF-SHA256-A256GCM", value: "opaque" },
        objects: [{ index: 0, cipherBytes: ciphertext.byteLength, cipherSha256 }],
      },
      adminToken,
    ),
  );
  const transferId = (await response.json()).transferId;
  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}/objects/0`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Content-Length": String(ciphertext.byteLength),
      },
      body: ciphertext,
      duplex: "half",
    }),
  );
  assert.equal(response.status, 200);
  response = await state.relay.fetch(
    await jsonRequest(`/v1/transfers/${transferId}/commit`, {}, adminToken),
  );
  assert.equal(response.status, 200);

  response = await state.relay.fetch(
    new Request("https://relay.test/v1/inbox", {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).transfers.map((item) => item.transferId), [transferId]);

  response = await state.relay.fetch(
    new Request("https://relay.test/v1/outbox", {
      headers: { Authorization: `Bearer ${adminToken}` },
    }),
  );
  assert.equal(response.status, 200);
  const outbox = await response.json();
  assert.deepEqual(outbox.transfers.map((item) => item.transferId), [transferId]);
  assert.equal(outbox.transfers[0].uploadedObjectCount, 1);
  assert.equal(outbox.transfers[0].uploadedCipherBytes, ciphertext.byteLength);
  assert.equal(outbox.transfers[0].nextObjectIndex, null);
});

test("plain public work completes upload, download and acknowledgement without a key package", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const workPackage = new TextEncoder().encode("public-work-package");
  const sha256 = Buffer.from(await crypto.subtle.digest("SHA-256", workPackage)).toString("hex");
  let response = await state.relay.fetch(
    await jsonRequest(
      "/v1/transfers",
      {
        mode: "plain",
        recipientDeviceId: state.memberCertificate.deviceId,
        objects: [{
          index: 0,
          bytes: workPackage.byteLength,
          sha256,
          name: "album-folder-作品集[泛].zip",
          mime: "application/zip",
        }],
      },
      adminToken,
    ),
  );
  assert.equal(response.status, 201);
  const transferId = (await response.json()).transferId;
  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}/objects/0`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Content-Length": String(workPackage.byteLength),
      },
      body: workPackage,
      duplex: "half",
    }),
  );
  assert.equal(response.status, 200);
  response = await state.relay.fetch(
    await jsonRequest(`/v1/transfers/${transferId}/commit`, {}, adminToken),
  );
  assert.equal(response.status, 200);

  response = await state.relay.fetch(
    new Request("https://relay.test/v1/inbox", {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  const inbox = await response.json();
  assert.equal(inbox.transfers[0].mode, "plain");
  assert.equal(inbox.transfers[0].contentKind, "work");
  assert.equal(inbox.transfers[0].objects[0].name, "album-folder-作品集[泛].zip");
  assert.equal(inbox.transfers[0].objects[0].bytes, workPackage.byteLength);

  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}/objects/0`, {
      headers: { Authorization: `Bearer ${state.token}` },
    }),
  );
  // Durable Object returns a private R2 materialization marker; the outer
  // Worker turns it into the 200-byte download response.
  assert.equal(response.status, 204);
  assert.equal(response.headers.get("X-Object-Sha256"), sha256);
  const stored = await state.bucket.get(`${state.memberCertificate.workspaceId}/${transferId}/000000.cipher`);
  assert.deepEqual(stored.body, workPackage);

  response = await state.relay.fetch(
    await jsonRequest(`/v1/transfers/${transferId}/ack`, {}, state.token),
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).objectsDeleted, true);
  assert.equal(state.bucket.values.size, 0);
});

test("android update transfers require one public APK object", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const response = await state.relay.fetch(
    await jsonRequest(
      "/v1/transfers",
      {
        mode: "plain",
        contentKind: "android-update",
        recipientDeviceId: state.memberCertificate.deviceId,
        objects: [{
          index: 0,
          bytes: 12,
          sha256: "a".repeat(64),
          name: "not-an-apk.jpg",
          mime: "image/jpeg",
        }],
      },
      adminToken,
    ),
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).code, "invalid_update_payload");
});

test("transfer status exposes the next object and repeated upload is idempotent", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const first = crypto.getRandomValues(new Uint8Array(16));
  const second = crypto.getRandomValues(new Uint8Array(16));
  const digest = async (value) => Buffer.from(await crypto.subtle.digest("SHA-256", value)).toString("hex");
  let response = await state.relay.fetch(
    await jsonRequest(
      "/v1/transfers",
      {
        recipientDeviceId: state.memberCertificate.deviceId,
        encryptedKeyPackage: { algorithm: "P256-HKDF-SHA256-A256GCM", value: "opaque" },
        objects: [
          { index: 0, cipherBytes: first.byteLength, cipherSha256: await digest(first) },
          { index: 1, cipherBytes: second.byteLength, cipherSha256: await digest(second) },
        ],
      },
      adminToken,
    ),
  );
  const transferId = (await response.json()).transferId;
  const upload = async (index, value) => state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}/objects/${index}`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Content-Length": String(value.byteLength),
      },
      body: value,
      duplex: "half",
    }),
  );

  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    }),
  );
  let snapshot = (await response.json()).transfer;
  assert.equal(snapshot.uploadedObjectCount, 0);
  assert.equal(snapshot.nextObjectIndex, 0);

  assert.equal((await upload(0, first)).status, 200);
  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    }),
  );
  snapshot = (await response.json()).transfer;
  assert.equal(snapshot.uploadedObjectCount, 1);
  assert.equal(snapshot.nextObjectIndex, 1);
  const retry = await upload(0, first);
  assert.equal(retry.status, 200);
  assert.equal((await retry.json()).reused, true);
  assert.equal(state.bucket.values.size, 1);
});

test("ciphertext is deleted after recipient acknowledgement", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);

  const ciphertext = crypto.getRandomValues(new Uint8Array(32));
  const digest = await crypto.subtle.digest("SHA-256", ciphertext);
  const cipherSha256 = Buffer.from(digest).toString("hex");
  let response = await state.relay.fetch(
    await jsonRequest(
      "/v1/transfers",
      {
        recipientDeviceId: state.memberCertificate.deviceId,
      encryptedKeyPackage: { algorithm: "P256-HKDF-SHA256-A256GCM", value: "opaque" },
        objects: [{ index: 0, cipherBytes: 32, cipherSha256 }],
      },
      adminToken,
    ),
  );
  assert.equal(response.status, 201);
  const transferId = (await response.json()).transferId;

  response = await state.relay.fetch(
    new Request(`https://relay.test/v1/transfers/${transferId}/objects/0`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${adminToken}`, "Content-Length": "32" },
      body: ciphertext,
      duplex: "half",
    }),
  );
  assert.equal(response.status, 200);

  response = await state.relay.fetch(
    await jsonRequest(`/v1/transfers/${transferId}/commit`, {}, adminToken),
  );
  assert.equal(response.status, 200);
  assert.equal(state.bucket.values.size, 1);

  response = await state.relay.fetch(
    await jsonRequest(`/v1/transfers/${transferId}/ack`, {}, state.token),
  );
  assert.equal(response.status, 200);
  assert.equal(state.bucket.values.size, 0);
  assert.equal((await response.json()).ciphertextDeleted, true);
});

test("expired transfers are removed by the alarm", async () => {
  const state = await bootstrap();
  const transfer = {
    version: 1,
    transferId: "transfer_expired_01",
    workspaceId: state.memberCertificate.workspaceId,
    senderDeviceId: state.adminCertificate.deviceId,
    recipientDeviceId: state.memberCertificate.deviceId,
    encryptedKeyPackage: {},
    objects: [{ index: 0, cipherBytes: 16, cipherSha256: "0".repeat(64) }],
    totalCipherBytes: 16,
    status: "ready",
    createdAt: Date.now() - 10_000,
    expiresAt: Date.now() - 1,
  };
  await state.storage.put(`transfer:${transfer.transferId}`, transfer);
  state.bucket.values.set(
    `${transfer.workspaceId}/${transfer.transferId}/000000.cipher`,
    { bytes: new Uint8Array(16), customMetadata: {} },
  );
  await state.relay.alarm();
  assert.equal(state.bucket.values.size, 0);
  assert.equal((await state.storage.get(`transfer:${transfer.transferId}`)).status, "expired");
});

test("batch multi-recipient transfer reuses one R2 blob and deletes only after all recipients ACK", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const workspaceId = state.adminCertificate.workspaceId;
  const member2 = await generateIdentity();
  const now = Date.now();
  const member2Cert = {
    version: 1,
    workspaceId,
    deviceId: "android_member_02",
    deviceName: "测试接收手机 2",
    role: "member",
    signingPublicKey: member2.publicKey,
    agreementPublicKey: member2.publicKey,
    serial: 2,
    issuedAt: now,
    expiresAt: now + 365 * 24 * 60 * 60 * 1000,
  };
  const member2CertSig = await sign(state.admin.pair.privateKey, member2Cert);
  let res = await state.relay.fetch(await jsonRequest("/v1/challenges", {
    workspaceId,
    deviceId: member2Cert.deviceId,
  }));
  assert.equal(res.status, 201);
  const challenge = await res.json();
  res = await state.relay.fetch(await jsonRequest("/v1/sessions", {
    certificate: member2Cert,
    certificateSignature: member2CertSig,
    challengeId: challenge.challengeId,
    challengeSignature: await sign(member2.pair.privateKey, challenge),
  }));
  assert.equal(res.status, 201);
  const member2Token = (await res.json()).token;

  const payload = crypto.getRandomValues(new Uint8Array(64));
  const sha = Buffer.from(await crypto.subtle.digest("SHA-256", payload)).toString("hex");
  res = await state.relay.fetch(await jsonRequest("/v1/transfers/batch", {
    mode: "plain",
    contentKind: "work",
    recipientDeviceIds: [state.memberCertificate.deviceId, member2Cert.deviceId],
    objects: [{ index: 0, bytes: 64, sha256: sha, name: "hd.jpg", mime: "image/jpeg" }],
  }, adminToken));
  assert.equal(res.status, 201);
  const batch = await res.json();
  assert.equal(batch.recipients.length, 2);

  res = await state.relay.fetch(new Request(`https://relay.test${batch.uploads[0].path}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${adminToken}`, "Content-Length": "64" },
    body: payload,
    duplex: "half",
  }));
  assert.equal(res.status, 200);
  assert.equal(state.bucket.values.size, 1);

  res = await state.relay.fetch(await jsonRequest(`/v1/batches/${batch.batchId}/commit`, {}, adminToken));
  assert.equal(res.status, 200);

  const t1 = batch.recipients.find((r) => r.recipientDeviceId === state.memberCertificate.deviceId).transferId;
  const t2 = batch.recipients.find((r) => r.recipientDeviceId === member2Cert.deviceId).transferId;

  const dlReq1 = new Request(`https://relay.test/v1/transfers/${t1}/objects/0`, {
    headers: { Authorization: `Bearer ${state.token}`, Range: "bytes=16-47" },
  });
  let dlRes1 = await state.relay.fetch(dlReq1);
  dlRes1 = await materializeR2WithRange(dlRes1, { REMOTE_OBJECTS: state.bucket }, dlReq1);
  assert.equal(dlRes1.status, 206);
  assert.equal(dlRes1.headers.get("Content-Range"), "bytes 16-47/64");
  const slice = new Uint8Array(await dlRes1.arrayBuffer());
  assert.deepEqual(slice, payload.slice(16, 48));

  // First recipient ACKs -> shared R2 object MUST remain for recipient 2!
  res = await state.relay.fetch(await jsonRequest(`/v1/transfers/${t1}/ack`, {}, state.token));
  assert.equal(res.status, 200);
  assert.equal(state.bucket.values.size, 1);

  // Second recipient downloads full object and ACKs -> now R2 object is deleted!
  let dlRes2 = await state.relay.fetch(new Request(`https://relay.test/v1/transfers/${t2}/objects/0`, {
    headers: { Authorization: `Bearer ${member2Token}` },
  }));
  dlRes2 = await materializeR2WithRange(dlRes2, { REMOTE_OBJECTS: state.bucket });
  assert.equal(dlRes2.status, 200);
  assert.deepEqual(new Uint8Array(await dlRes2.arrayBuffer()), payload);

  res = await state.relay.fetch(await jsonRequest(`/v1/transfers/${t2}/ack`, {}, member2Token));
  assert.equal(res.status, 200);
  assert.equal(state.bucket.values.size, 0);

  res = await state.relay.fetch(await jsonRequest("/v1/stats/channel", {
    channel: "lan",
    bytes: 2048,
    objects: 2,
    transfers: 1,
  }, adminToken));
  assert.equal(res.status, 200);

  res = await state.relay.fetch(new Request("https://relay.test/v1/stats", {
    headers: { Authorization: `Bearer ${adminToken}` },
  }));
  assert.equal(res.status, 200);
  const stats = await res.json();
  assert.equal(stats.r2.activeBytes, 0);
  assert.equal(stats.today.lanBytes, 2048);
  assert.equal(stats.today.r2Bytes, 64);
  assert.equal(stats.r2.isEstimate, true);
});

test("authenticated remote online album enforces device roles, serves thumbs/manifest, pulls HD original, and cleans R2 on ACK/alarm", async () => {
  const state = await bootstrap();
  const adminToken = await createAdminSession(state);
  const workspaceId = state.adminCertificate.workspaceId;

  let res = await state.relay.fetch(new Request("https://relay.test/v1/catalog", {
    method: "PUT",
    headers: { Authorization: `Bearer ${state.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ works: [] }),
  }));
  assert.equal(res.status, 403);

  res = await state.relay.fetch(new Request("https://relay.test/v1/catalog", {
    method: "PUT",
    headers: { Authorization: `Bearer ${adminToken}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      generatedAt: Date.now(),
      works: [{ workId: "w_01", title: "西溪团建", thumbKey: "thumb_w_01", files: ["01.jpg"] }],
    }),
  }));
  assert.equal(res.status, 200);

  const thumbBytes = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
  res = await state.relay.fetch(new Request("https://relay.test/v1/catalog/thumbs/thumb_w_01", {
    method: "PUT",
    headers: { Authorization: `Bearer ${adminToken}`, "Content-Length": String(thumbBytes.byteLength) },
    body: thumbBytes,
    duplex: "half",
  }));
  assert.equal(res.status, 200);

  let manRes = await state.relay.fetch(new Request("https://relay.test/v1/catalog", {
    headers: { Authorization: `Bearer ${state.token}` },
  }));
  manRes = await materializeR2WithRange(manRes, { REMOTE_OBJECTS: state.bucket });
  assert.equal(manRes.status, 200);
  const manifest = await manRes.json();
  assert.equal(manifest.works[0].workId, "w_01");

  let thRes = await state.relay.fetch(new Request("https://relay.test/v1/catalog/thumbs/thumb_w_01", {
    headers: { Authorization: `Bearer ${state.token}` },
  }));
  thRes = await materializeR2WithRange(thRes, { REMOTE_OBJECTS: state.bucket });
  assert.equal(thRes.status, 200);
  assert.deepEqual(new Uint8Array(await thRes.arrayBuffer()), thumbBytes);

  res = await state.relay.fetch(await jsonRequest("/v1/catalog/pull", {
    workId: "w_01",
    fileName: "01.jpg",
  }, state.token));
  assert.equal(res.status, 201);
  const { pull } = await res.json();

  res = await state.relay.fetch(new Request("https://relay.test/v1/catalog/pulls/pending", {
    headers: { Authorization: `Bearer ${adminToken}` },
  }));
  assert.equal((await res.json()).pulls.length, 1);

  const origBytes = crypto.getRandomValues(new Uint8Array(128));
  const origSha = Buffer.from(await crypto.subtle.digest("SHA-256", origBytes)).toString("hex");
  res = await state.relay.fetch(new Request(`https://relay.test/v1/catalog/pulls/${pull.pullId}/file`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${adminToken}`,
      "Content-Length": String(origBytes.byteLength),
      "X-Object-Sha256": origSha,
    },
    body: origBytes,
    duplex: "half",
  }));
  assert.equal(res.status, 200);

  let pullDl = await state.relay.fetch(new Request(`https://relay.test/v1/catalog/pulls/${pull.pullId}/file`, {
    headers: { Authorization: `Bearer ${state.token}` },
  }));
  pullDl = await materializeR2WithRange(pullDl, { REMOTE_OBJECTS: state.bucket });
  assert.equal(pullDl.status, 200);
  assert.equal(pullDl.headers.get("X-Object-Sha256"), origSha);
  assert.deepEqual(new Uint8Array(await pullDl.arrayBuffer()), origBytes);

  res = await state.relay.fetch(await jsonRequest(`/v1/catalog/pulls/${pull.pullId}/ack`, {}, state.token));
  assert.equal(res.status, 200);
  assert.equal(await state.bucket.head(`${workspaceId}/pulls/${pull.pullId}.bin`), null);

  res = await state.relay.fetch(await jsonRequest("/v1/catalog/use-work", { workId: "w_01" }, state.token));
  assert.equal(res.status, 200);
  res = await state.relay.fetch(new Request("https://relay.test/v1/catalog/events", {
    headers: { Authorization: `Bearer ${adminToken}` },
  }));
  assert.equal((await res.json()).events.length, 1);
});
