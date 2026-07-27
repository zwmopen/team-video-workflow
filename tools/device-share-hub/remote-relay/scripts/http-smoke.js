import assert from "node:assert/strict";
import { canonicalize } from "../src/relay-core.js";

const baseUrl = process.env.RELAY_BASE_URL || "http://127.0.0.1:8791";

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

async function request(path, { method = "GET", workspaceId, token, body, bytes } = {}) {
  const headers = {};
  if (workspaceId) headers["X-Workspace-Id"] = workspaceId;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (bytes !== undefined) headers["Content-Length"] = String(bytes.byteLength);
  return fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : bytes,
  });
}

async function createSession(certificate, certificateSignature, identity) {
  let response = await request("/v1/challenges", {
    method: "POST",
    body: { workspaceId: certificate.workspaceId, deviceId: certificate.deviceId },
  });
  assert.equal(response.status, 201);
  const challenge = await response.json();
  response = await request("/v1/sessions", {
    method: "POST",
    body: {
      certificate,
      certificateSignature,
      challengeId: challenge.challengeId,
      challengeSignature: await sign(identity.pair.privateKey, challenge),
    },
  });
  assert.equal(response.status, 201);
  return (await response.json()).token;
}

const health = await request("/v1/health");
assert.equal(health.status, 200);
assert.equal((await health.json()).protocol, 1);

const admin = await generateIdentity();
const member = await generateIdentity();
const now = Date.now();
const adminCertificate = {
  version: 1,
  workspaceId: await workspaceIdFor(admin.publicKey),
  deviceId: "device_admin_smoke",
  deviceName: "素材投送中控 CI",
  role: "admin",
  signingPublicKey: admin.publicKey,
  agreementPublicKey: admin.publicKey,
  serial: 1,
  issuedAt: now,
  expiresAt: now + 24 * 60 * 60 * 1000,
};
const adminCertificateSignature = await sign(admin.pair.privateKey, adminCertificate);
let response = await request("/v1/workspaces/register", {
  method: "POST",
  body: {
    certificate: adminCertificate,
    certificateSignature: adminCertificateSignature,
  },
});
assert.equal(response.status, 201);

const memberCertificate = {
  ...adminCertificate,
  deviceId: "device_member_smoke",
  deviceName: "远程接收测试手机",
  role: "member",
  signingPublicKey: member.publicKey,
  agreementPublicKey: member.publicKey,
};
const memberCertificateSignature = await sign(admin.pair.privateKey, memberCertificate);
const memberToken = await createSession(memberCertificate, memberCertificateSignature, member);
const adminToken = await createSession(adminCertificate, adminCertificateSignature, admin);

const ciphertext = crypto.getRandomValues(new Uint8Array(64));
const cipherSha256 = Buffer.from(
  await crypto.subtle.digest("SHA-256", ciphertext),
).toString("hex");
response = await request("/v1/transfers", {
  method: "POST",
  workspaceId: adminCertificate.workspaceId,
  token: adminToken,
  body: {
    recipientDeviceId: memberCertificate.deviceId,
    encryptedKeyPackage: {
      algorithm: "P256-HKDF-SHA256-A256GCM",
      keyContext: base64Url(crypto.getRandomValues(new Uint8Array(16))),
      value: "ci-opaque-key-package",
    },
    objects: [{ index: 0, cipherBytes: ciphertext.byteLength, cipherSha256 }],
  },
});
assert.equal(response.status, 201);
const transferId = (await response.json()).transferId;

response = await request(`/v1/transfers/${transferId}/objects/0`, {
  method: "PUT",
  workspaceId: adminCertificate.workspaceId,
  token: adminToken,
  bytes: ciphertext,
});
assert.equal(response.status, 200);
response = await request(`/v1/transfers/${transferId}/commit`, {
  method: "POST",
  workspaceId: adminCertificate.workspaceId,
  token: adminToken,
  body: {},
});
assert.equal(response.status, 200);

response = await request(`/v1/transfers/${transferId}/objects/0`, {
  workspaceId: adminCertificate.workspaceId,
  token: memberToken,
});
assert.equal(response.status, 200);
assert.deepEqual(new Uint8Array(await response.arrayBuffer()), ciphertext);

response = await request(`/v1/transfers/${transferId}/ack`, {
  method: "POST",
  workspaceId: adminCertificate.workspaceId,
  token: memberToken,
  body: {},
});
assert.equal(response.status, 200);
assert.equal((await response.json()).ciphertextDeleted, true);

response = await request(`/v1/transfers/${transferId}/objects/0`, {
  workspaceId: adminCertificate.workspaceId,
  token: memberToken,
});
assert.equal(response.status, 404);

console.log("Remote relay HTTP/DO/R2 smoke test passed");
