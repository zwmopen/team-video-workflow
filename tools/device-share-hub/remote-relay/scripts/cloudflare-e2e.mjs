#!/usr/bin/env node
import { createHash, randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { canonicalize } from "../src/relay-core.js";

const proxy = process.env.ALBUM_RELAY_PROXY || process.env.HTTPS_PROXY || process.env.HTTP_PROXY;
if (proxy) {
  const { ProxyAgent, setGlobalDispatcher } = createRequire(import.meta.url)("undici");
  setGlobalDispatcher(new ProxyAgent(proxy));
}

const endpoint = (process.env.ALBUM_RELAY_ENDPOINT ||
  "https://zwm-device-share-relay.zwmrpg.workers.dev").replace(/\/+$/, "");
let workspaceHeader = "";

function b64url(bytes) { return Buffer.from(bytes).toString("base64url"); }

async function identity() {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const publicKey = await crypto.subtle.exportKey("jwk", pair.publicKey);
  delete publicKey.key_ops;
  delete publicKey.ext;
  return { pair, publicKey };
}

async function sha256(value) {
  return Buffer.from(await crypto.subtle.digest("SHA-256", value)).toString("hex");
}

async function sign(privateKey, value) {
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, privateKey,
    new TextEncoder().encode(canonicalize(value)));
  return b64url(signature);
}

async function workspaceId(publicKey) {
  return `ws_${(await sha256(new TextEncoder().encode(canonicalize(publicKey)))).slice(0, 32)}`;
}

async function request(path, method = "GET", token, body, expected = null) {
  const headers = { Accept: "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (workspaceHeader) headers["X-Workspace-Id"] = workspaceHeader;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(`${endpoint}${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let value = {};
  try { value = text ? JSON.parse(text) : {}; } catch { value = { raw: text }; }
  if (!response.ok || (expected && response.status !== expected)) {
    throw new Error(`${method} ${path} HTTP ${response.status}: ${value.message || value.code || text}`);
  }
  return value;
}

async function sessionFor(device, certificate, certificateSignature) {
  const challenge = await request("/v1/challenges", "POST", null, {
    workspaceId: certificate.workspaceId, deviceId: certificate.deviceId,
  }, 201);
  const challengeSignature = await sign(device.pair.privateKey, challenge);
  return request("/v1/sessions", "POST", null, {
    certificate, certificateSignature,
    challengeId: challenge.challengeId, challengeSignature,
  }, 201);
}

const admin = await identity();
const member = await identity();
const suffix = `${Date.now()}-${randomUUID().slice(0, 8)}`.replace(/[^A-Za-z0-9_-]/g, "_");
const now = Date.now();
const workspace = await workspaceId(admin.publicKey);
workspaceHeader = workspace;
const adminCertificate = {
  version: 1, workspaceId: workspace, deviceId: `windows-smoke-${suffix}`,
  deviceName: "Cloudflare E2E 管理端", role: "admin",
  signingPublicKey: admin.publicKey, agreementPublicKey: admin.publicKey,
  serial: 1, issuedAt: now, expiresAt: now + 365 * 24 * 60 * 60 * 1000,
};
const adminSignature = await sign(admin.pair.privateKey, adminCertificate);
await request("/v1/health");
await request("/v1/workspaces/register", "POST", null, {
  certificate: adminCertificate, certificateSignature: adminSignature,
}, 201);

const memberCertificate = {
  ...adminCertificate, deviceId: `android-smoke-${suffix}`,
  deviceName: "Cloudflare E2E 接收端", role: "member",
  signingPublicKey: member.publicKey, agreementPublicKey: member.publicKey,
};
const memberSignature = await sign(admin.pair.privateKey, memberCertificate);
const adminSession = await sessionFor(admin, adminCertificate, adminSignature);
const memberSession = await sessionFor(member, memberCertificate, memberSignature);
await request("/v1/presence", "POST", memberSession.token, {});
const devices = await request("/v1/devices", "GET", adminSession.token);
if (!devices.devices.some((item) => item.deviceId === memberCertificate.deviceId && item.online)) {
  throw new Error("Cloudflare 中继没有把接收端标记为在线");
}

const p2p = (await request("/v1/p2p/sessions", "POST", adminSession.token, {
  recipientDeviceId: memberCertificate.deviceId,
  protocol: "webrtc-datachannel-v1",
}, 201)).p2p;
await request(`/v1/p2p/sessions/${p2p.sessionId}/signals`, "POST", adminSession.token, {
  type: "offer", data: { type: "offer", sdp: "v=0\\r\\ncloudflare-e2e" },
});
const memberP2P = await request(`/v1/p2p/sessions/${p2p.sessionId}`, "GET", memberSession.token);
if (memberP2P.p2p.signals?.[0]?.type !== "offer") {
  throw new Error("Cloudflare 直连协商没有把 offer 送到接收端");
}
await request(`/v1/p2p/sessions/${p2p.sessionId}/signals`, "POST", memberSession.token, {
  type: "answer", data: { type: "answer", sdp: "v=0\\r\\ncloudflare-e2e" },
});
const adminP2P = await request(`/v1/p2p/sessions/${p2p.sessionId}`, "GET", adminSession.token);
if (!adminP2P.p2p.signals?.some((signal) => signal.type === "answer")) {
  throw new Error("Cloudflare 直连协商没有把 answer 送回发起端");
}
await request(`/v1/p2p/sessions/${p2p.sessionId}/close`, "POST", adminSession.token, {});

const work = new TextEncoder().encode("cloudflare-device-share-e2e-public-work");
const workHash = await sha256(work);
const created = await request("/v1/transfers", "POST", adminSession.token, {
  mode: "plain", recipientDeviceId: memberCertificate.deviceId,
  objects: [{ index: 0, bytes: work.byteLength, sha256: workHash,
    name: "album-folder-cloudflare-e2e.zip", mime: "application/zip" }],
}, 201);
await fetch(`${endpoint}${created.uploads[0].path}`, {
  method: "PUT",
  headers: { Authorization: `Bearer ${adminSession.token}`,
    "X-Workspace-Id": workspaceHeader, "Content-Type": "application/octet-stream" },
  body: Buffer.from(work),
});
await request(`/v1/transfers/${created.transferId}/commit`, "POST", adminSession.token, {});
const inbox = await request("/v1/inbox", "GET", memberSession.token);
const task = inbox.transfers.find((item) => item.transferId === created.transferId);
if (!task) throw new Error("接收端收件箱没有出现已提交任务");
const download = await fetch(`${endpoint}/v1/transfers/${created.transferId}/objects/0`, {
  headers: { Authorization: `Bearer ${memberSession.token}`, "X-Workspace-Id": workspaceHeader },
});
if (!download.ok) throw new Error(`接收对象失败 HTTP ${download.status}`);
const received = new Uint8Array(await download.arrayBuffer());
if ((await sha256(received)) !== workHash || received.length !== work.length) {
  throw new Error("接收对象 SHA-256 或大小校验失败");
}
const ack = await request(`/v1/transfers/${created.transferId}/ack`, "POST", memberSession.token, {});
if (ack.status !== "completed" || ack.objectsDeleted !== true) throw new Error("ACK 没有完成清理");
const afterAck = await fetch(`${endpoint}/v1/transfers/${created.transferId}/objects/0`, {
  headers: { Authorization: `Bearer ${memberSession.token}`, "X-Workspace-Id": workspaceHeader },
});
if (afterAck.status === 200) throw new Error("ACK 后 R2 对象仍可下载");

console.log(JSON.stringify({
  ok: true, endpoint, protocol: "plain",
  workspaceId: workspace, transferId: created.transferId,
  stages: ["health", "workspace-register", "admin-session", "member-session",
    "presence", "p2p-offer", "p2p-answer", "p2p-close", "inbox", "r2-upload",
    "commit", "download-sha256", "ack", "r2-delete"],
}, null, 2));
