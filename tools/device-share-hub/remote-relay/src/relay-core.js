const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;
const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const SESSION_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_TRANSFER_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_OBJECTS = 1_000;
const MAX_TRANSFER_BYTES = 20 * 1024 * 1024 * 1024;

/** @typedef {{ WORKSPACES: DurableObjectNamespace, REMOTE_OBJECTS: R2Bucket }} Env */

export default {
  /** @param {Request} request @param {Env} env */
  async fetch(request, env) {
    const requestId = crypto.randomUUID();
    const startedAt = Date.now();
    const url = new URL(request.url);
    if (url.pathname === "/v1/health") {
      return json({ ok: true, protocol: 1, service: "device-share-relay" });
    }

    const workspaceId =
      url.searchParams.get("workspaceId") ||
      request.headers.get("X-Workspace-Id") ||
      (await workspaceIdFromJson(request));
    if (!validId(workspaceId)) {
      return problem(400, "invalid_workspace", "工作区身份无效");
    }

    let response = await forwardToWorkspace(env.WORKSPACES.getByName(workspaceId), request);
    response = await materializeRelayResponse(response, env);
    console.log(JSON.stringify({ level: "info", event: "relay_request", requestId,
      method: request.method, path: url.pathname, status: response.status,
      durationMs: Date.now() - startedAt }));
    return response;
  },
};

async function forwardToWorkspace(workspace, request) {
  const contentLength = Number(request.headers.get("Content-Length"));
  if (!request.body || typeof FixedLengthStream === "undefined" || !Number.isSafeInteger(contentLength)) {
    return workspace.fetch(request);
  }
  const { readable, writable } = new FixedLengthStream(contentLength);
  const forwarded = new Request(request, { body: readable });
  const [, response] = await Promise.all([
    request.body.pipeTo(writable),
    workspace.fetch(forwarded),
  ]);
  return response;
}

async function materializeRelayResponse(response, env) {
  const key = response.headers.get("X-Relay-R2-Key");
  if (!key) return response;
  const object = await env.REMOTE_OBJECTS.get(key);
  if (!object) return problem(410, "object_expired", "远程临时文件已过期");
  return new Response(object.body, {
    status: 200,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(object.size),
      "X-Cipher-Sha256": response.headers.get("X-Cipher-Sha256") || "",
      "Cache-Control": "private, no-store",
    },
  });
}

export class WorkspaceRelayCore {
  /** @param {DurableObjectState} ctx @param {Env} env */
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    try {
      const url = new URL(request.url);
      const method = request.method.toUpperCase();

      if (method === "POST" && url.pathname === "/v1/workspaces/register") {
        return await this.registerWorkspace(request);
      }
      if (method === "POST" && url.pathname === "/v1/challenges") {
        return await this.createChallenge(request);
      }
      if (method === "POST" && url.pathname === "/v1/sessions") {
        return await this.createSession(request);
      }

      const session = await this.authenticate(request);
      if (!session) {
        return problem(401, "unauthorized", "设备身份验证已失效，请回到已登记电脑附近重新授权");
      }

      if (method === "GET" && url.pathname === "/v1/socket") {
        return this.openSocket(request, session);
      }
      if (method === "GET" && url.pathname === "/v1/devices") {
        return this.listDevices(session);
      }
      if (method === "POST" && url.pathname === "/v1/presence") {
        await discardRequestBody(request);
        return await this.heartbeat(session);
      }
      if (method === "GET" && url.pathname === "/v1/inbox") {
        return await this.listTransfers(session, true);
      }
      if (method === "GET" && url.pathname === "/v1/outbox") {
        return await this.listTransfers(session, false);
      }
      const deviceMatch = url.pathname.match(
        /^\/v1\/devices\/([A-Za-z0-9_-]+)\/(remote|revoke)$/,
      );
      if (deviceMatch && method === "POST" && deviceMatch[2] === "remote") {
        return await this.setRemoteAllowed(request, session, deviceMatch[1]);
      }
      if (deviceMatch && method === "POST" && deviceMatch[2] === "revoke") {
        await discardRequestBody(request);
        return await this.revokeDevice(session, deviceMatch[1]);
      }
      if (method === "POST" && url.pathname === "/v1/transfers") {
        return await this.createTransfer(request, session);
      }

      const objectMatch = url.pathname.match(/^\/v1\/transfers\/([A-Za-z0-9_-]+)\/objects\/(\d+)$/);
      if (objectMatch && method === "PUT") {
        return await this.uploadObject(request, session, objectMatch[1], Number(objectMatch[2]));
      }
      if (objectMatch && method === "GET") {
        return await this.downloadObject(session, objectMatch[1], Number(objectMatch[2]));
      }

      const transferMatch = url.pathname.match(/^\/v1\/transfers\/([A-Za-z0-9_-]+)(?:\/(commit|ack|cancel))?$/);
      if (transferMatch && method === "GET" && !transferMatch[2]) {
        return await this.getTransfer(session, transferMatch[1]);
      }
      if (transferMatch && method === "POST" && transferMatch[2] === "commit") {
        await discardRequestBody(request);
        return await this.commitTransfer(session, transferMatch[1]);
      }
      if (transferMatch && method === "POST" && transferMatch[2] === "ack") {
        await discardRequestBody(request);
        return await this.ackTransfer(session, transferMatch[1]);
      }
      if (transferMatch && method === "POST" && transferMatch[2] === "cancel") {
        await discardRequestBody(request);
        return await this.cancelTransfer(session, transferMatch[1]);
      }

      return problem(404, "not_found", "远程服务没有这个入口");
    } catch (error) {
      console.error(JSON.stringify({ level: "error", event: "relay_request_failed", ...safeError(error) }));
      return problem(500, "internal_error", "远程服务暂时不可用，请稍后重试");
    }
  }

  async registerWorkspace(request) {
    const body = await readJson(request);
    const certificate = body?.certificate;
    if (!validCertificate(certificate) || certificate.role !== "admin") {
      return problem(400, "invalid_certificate", "电脑管理身份格式无效");
    }
    if (
      !(await verifyEs256(
        certificate.signingPublicKey,
        canonicalBytes(certificate),
        body.certificateSignature,
      ))
    ) {
      return problem(403, "invalid_signature", "电脑管理身份签名无效");
    }

    const existing = await this.getStored("workspace");
    const fingerprint = await publicKeyFingerprint(certificate.signingPublicKey);
    if (certificate.workspaceId !== workspaceIdForFingerprint(fingerprint)) {
      return problem(400, "invalid_workspace_id", "工作区身份必须由电脑管理公钥生成");
    }
    if (existing && existing.adminKeyFingerprint !== fingerprint) {
      return problem(409, "workspace_conflict", "这个工作区已经由另一台管理电脑建立");
    }

    const workspace = {
      workspaceId: certificate.workspaceId,
      adminDeviceId: certificate.deviceId,
      adminSigningPublicKey: certificate.signingPublicKey,
      adminAgreementPublicKey: certificate.agreementPublicKey,
      adminKeyFingerprint: fingerprint,
      createdAt: existing?.createdAt || Date.now(),
      certificateSerial: certificate.serial,
    };
    await this.ctx.storage.put("workspace", workspace);
    await this.ctx.storage.put(`member:${certificate.deviceId}`, {
      certificate,
      certificateSignature: body.certificateSignature,
      revokedAt: null,
      channels: { remote: true },
    });
    return json({ ok: true, workspaceId: certificate.workspaceId, adminKeyFingerprint: fingerprint }, 201);
  }

  async createChallenge(request) {
    const body = await readJson(request);
    if (!validId(body?.workspaceId) || !validId(body?.deviceId)) {
      return problem(400, "invalid_device", "设备身份无效");
    }
    const challengeId = randomId(18);
    const challenge = {
      version: 1,
      workspaceId: body.workspaceId,
      deviceId: body.deviceId,
      challengeId,
      nonce: randomId(32),
      expiresAt: Date.now() + CHALLENGE_TTL_MS,
    };
    await this.ctx.storage.put(`challenge:${challengeId}`, challenge);
    await this.scheduleCleanup(challenge.expiresAt);
    return json(challenge, 201);
  }

  async createSession(request) {
    const body = await readJson(request);
    const certificate = body?.certificate;
    const workspace = await this.getStored("workspace");
    if (!workspace) return problem(404, "workspace_missing", "设备组尚未在电脑上建立");
    if (!validCertificate(certificate) || certificate.workspaceId !== workspace.workspaceId) {
      return problem(400, "invalid_certificate", "设备凭证格式无效");
    }
    if (certificate.expiresAt <= Date.now() - MAX_CLOCK_SKEW_MS) {
      return problem(403, "certificate_expired", "设备凭证已过期");
    }

    if (
      certificate.role === "admin" &&
      (certificate.deviceId !== workspace.adminDeviceId ||
        (await publicKeyFingerprint(certificate.signingPublicKey)) !==
          workspace.adminKeyFingerprint)
    ) {
      return problem(403, "admin_identity_mismatch", "管理电脑身份与设备组根身份不一致");
    }
    const issuerKey = workspace.adminSigningPublicKey;
    if (
      !(await verifyEs256(
        issuerKey,
        canonicalBytes(certificate),
        body.certificateSignature,
      ))
    ) {
      return problem(403, "invalid_certificate_signature", "设备凭证不是由这台管理电脑签发");
    }

    const member = await this.getStored(`member:${certificate.deviceId}`);
    if (member?.revokedAt) {
      return problem(403, "device_revoked", "这台设备的传送权限已被电脑撤销");
    }
    if (member?.channels?.remote === false) {
      return problem(403, "remote_disabled", "这台设备的远程传送已关闭");
    }
    if (member && member.certificate.serial > certificate.serial) {
      return problem(403, "certificate_superseded", "设备凭证已被更新，请重新连接电脑");
    }

    const challenge = await this.getStored(`challenge:${body.challengeId}`);
    if (
      !challenge ||
      challenge.deviceId !== certificate.deviceId ||
      challenge.workspaceId !== certificate.workspaceId ||
      challenge.expiresAt < Date.now()
    ) {
      return problem(403, "challenge_expired", "身份验证请求已过期，请重试");
    }
    if (
      !(await verifyEs256(
        certificate.signingPublicKey,
        canonicalBytes(challenge),
        body.challengeSignature,
      ))
    ) {
      return problem(403, "challenge_signature_invalid", "设备未能证明自己的身份");
    }

    await this.ctx.storage.delete(`challenge:${body.challengeId}`);
    await this.ctx.storage.put(`member:${certificate.deviceId}`, {
      certificate,
      certificateSignature: body.certificateSignature,
      revokedAt: null,
      channels: member?.channels || { remote: true },
    });

    const token = randomId(32);
    const tokenHash = await sha256Hex(new TextEncoder().encode(token));
    const session = {
      deviceId: certificate.deviceId,
      workspaceId: certificate.workspaceId,
      role: certificate.role,
      tokenHash,
      expiresAt: Date.now() + SESSION_TTL_MS,
    };
    await this.ctx.storage.put(`session:${tokenHash}`, session);
    await this.scheduleCleanup(session.expiresAt);
    return json({ token, expiresAt: session.expiresAt, deviceId: session.deviceId }, 201);
  }

  async authenticate(request) {
    const authorization = request.headers.get("Authorization") || "";
    if (!authorization.startsWith("Bearer ")) return null;
    const token = authorization.slice(7);
    if (!token || token.length > 256) return null;
    const tokenHash = await sha256Hex(new TextEncoder().encode(token));
    const session = await this.getStored(`session:${tokenHash}`);
    if (!session) return null;
    if (session.expiresAt < Date.now()) {
      await this.ctx.storage.delete(`session:${tokenHash}`);
      return null;
    }
    const member = await this.getStored(`member:${session.deviceId}`);
    if (!member || member.revokedAt || member.channels?.remote === false) return null;
    return session;
  }

  openSocket(request, session) {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return problem(426, "websocket_required", "此入口需要远程在线连接");
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.serializeAttachment({ deviceId: session.deviceId, connectedAt: Date.now() });
    this.ctx.acceptWebSocket(server);
    server.send(JSON.stringify({ type: "ready", deviceId: session.deviceId }));
    this.broadcast({ type: "device-online", deviceId: session.deviceId }, session.deviceId);
    return new Response(null, { status: 101, webSocket: client });
  }

  async listDevices(session) {
    const members = await this.listStored("member:");
    const socketOnline = new Set(
      this.ctx.getWebSockets().map((socket) => socket.deserializeAttachment()?.deviceId).filter(Boolean),
    );
    const presence = await this.listStored("presence:");
    const now = Date.now();
    const devices = [];
    for (const value of members.values()) {
      devices.push({
        deviceId: value.certificate.deviceId,
        name: value.certificate.deviceName || "",
        role: value.certificate.role,
        online: socketOnline.has(value.certificate.deviceId) ||
          Number(presence.get(`presence:${value.certificate.deviceId}`)?.seenAt || 0) >= now - 20_000,
        revoked: Boolean(value.revokedAt),
        remoteAllowed: value.channels?.remote !== false,
        signingPublicKey: value.certificate.signingPublicKey,
        agreementPublicKey: value.certificate.agreementPublicKey,
        certificate: value.certificate,
        certificateSignature: value.certificateSignature,
      });
    }
    return json({ viewerDeviceId: session.deviceId, devices });
  }

  async heartbeat(session) {
    const seenAt = Date.now();
    await this.ctx.storage.put(`presence:${session.deviceId}`, { seenAt });
    await this.scheduleCleanup(seenAt + 60_000);
    return json({ ok: true, deviceId: session.deviceId, seenAt });
  }

  async listTransfers(session, inbox) {
    const transfers = await this.listStored("transfer:");
    const selected = [];
    for (const transfer of transfers.values()) {
      const matches = inbox
        ? transfer.recipientDeviceId === session.deviceId && transfer.status === "ready"
        : transfer.senderDeviceId === session.deviceId &&
          ["uploading", "ready", "completed", "cancelled", "expired"].includes(transfer.status);
      if (matches) selected.push(transfer);
    }
    selected.sort((left, right) => right.createdAt - left.createdAt);
    const visible = [];
    for (const transfer of selected.slice(0, 100)) {
      visible.push(await this.transferSnapshot(transfer));
    }
    return json({ transfers: visible, serverTime: Date.now() });
  }

  async setRemoteAllowed(request, session, deviceId) {
    if (session.role !== "admin") {
      return problem(403, "admin_required", "只有管理电脑可以更改设备权限");
    }
    const body = await readJson(request);
    if (typeof body?.allowed !== "boolean") {
      return problem(400, "invalid_remote_state", "远程传送开关状态无效");
    }
    const member = await this.getStored(`member:${deviceId}`);
    if (!member || member.revokedAt) {
      return problem(404, "device_missing", "设备未登记或已经撤销");
    }
    member.channels = { ...(member.channels || {}), remote: body.allowed };
    await this.ctx.storage.put(`member:${deviceId}`, member);
    this.sendToDevice(deviceId, {
      type: "remote-permission",
      allowed: body.allowed,
      message: body.allowed ? "电脑已开启远程传送" : "电脑已关闭远程传送",
    });
    if (!body.allowed) {
      await this.invalidateDeviceSessions(deviceId);
      this.closeDeviceSockets(deviceId, 4003, "remote-disabled");
    }
    return json({ ok: true, deviceId, remoteAllowed: body.allowed });
  }

  async revokeDevice(session, deviceId) {
    if (session.role !== "admin") {
      return problem(403, "admin_required", "只有管理电脑可以撤销设备");
    }
    if (deviceId === session.deviceId) {
      return problem(409, "cannot_revoke_admin", "不能从设备组中撤销当前管理电脑");
    }
    const member = await this.getStored(`member:${deviceId}`);
    if (!member) return problem(404, "device_missing", "设备尚未登记");
    member.revokedAt = member.revokedAt || Date.now();
    member.revocationVersion = Math.max(0, Number(member.revocationVersion) || 0) + 1;
    await this.ctx.storage.put(`member:${deviceId}`, member);
    this.sendToDevice(deviceId, {
      type: "device-revoked",
      message: "这台设备的传送权限已被电脑撤销",
    });
    await this.invalidateDeviceSessions(deviceId);
    this.closeDeviceSockets(deviceId, 4003, "device-revoked");
    this.broadcast({ type: "device-revoked", deviceId }, deviceId);
    return json({ ok: true, deviceId, revoked: true, revokedAt: member.revokedAt });
  }

  async createTransfer(request, session) {
    const body = await readJson(request);
    const recipient = await this.getStored(`member:${body?.recipientDeviceId}`);
    if (!recipient || recipient.revokedAt || recipient.channels?.remote === false) {
      return problem(409, "recipient_unavailable", "接收设备未登记、已撤销或关闭了远程传送");
    }
    const objects = normalizeObjects(body?.objects);
    if (!objects) {
      return problem(400, "invalid_objects", "文件清单无效或超过远程传送限制");
    }
    if (
      !body?.encryptedKeyPackage ||
      typeof body.encryptedKeyPackage !== "object" ||
      canonicalize(body.encryptedKeyPackage).length > 16_384
    ) {
      return problem(400, "missing_key_package", "缺少接收设备专用的密钥包");
    }

    const transferId = randomId(18);
    const now = Date.now();
    const requestedExpiry = Number(body.expiresAt) || now + MAX_TRANSFER_TTL_MS;
    const transfer = {
      version: 1,
      transferId,
      workspaceId: session.workspaceId,
      senderDeviceId: session.deviceId,
      recipientDeviceId: recipient.certificate.deviceId,
      encryptedKeyPackage: body.encryptedKeyPackage,
      objects,
      totalCipherBytes: objects.reduce((sum, item) => sum + item.cipherBytes, 0),
      status: "uploading",
      createdAt: now,
      expiresAt: Math.min(requestedExpiry, now + MAX_TRANSFER_TTL_MS),
    };
    await this.ctx.storage.put(`transfer:${transferId}`, transfer);
    await this.scheduleCleanup(transfer.expiresAt);
    return json(
      {
        transferId,
        expiresAt: transfer.expiresAt,
        uploads: objects.map((item) => ({
          index: item.index,
          path: `/v1/transfers/${transferId}/objects/${item.index}`,
        })),
      },
      201,
    );
  }

  async uploadObject(request, session, transferId, index) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (!transfer || transfer.senderDeviceId !== session.deviceId || transfer.status !== "uploading") {
      return problem(409, "transfer_not_uploading", "传送任务不存在或已停止上传");
    }
    const expected = transfer.objects.find((item) => item.index === index);
    if (!expected) return problem(404, "object_missing", "文件不在传送清单中");
    const contentLength = Number(request.headers.get("Content-Length"));
    if (!Number.isSafeInteger(contentLength) || contentLength !== expected.cipherBytes) {
      return problem(400, "size_mismatch", "密文大小与传送清单不一致");
    }
    const key = objectKey(transfer.workspaceId, transferId, index);
    const previous = await this.ctx.storage.get(uploadKey(transferId, index));
    if (
      previous &&
      previous.cipherBytes === expected.cipherBytes &&
      previous.cipherSha256 === expected.cipherSha256
    ) {
      const existing = await this.env.REMOTE_OBJECTS.head(key);
      if (
        existing &&
        existing.size === expected.cipherBytes &&
        existing.customMetadata?.cipherSha256 === expected.cipherSha256
      ) {
        return json({ ok: true, index, reused: true });
      }
    }
    const options = {
      customMetadata: {
        cipherSha256: expected.cipherSha256,
        senderDeviceId: session.deviceId,
        recipientDeviceId: transfer.recipientDeviceId,
      },
    };
    if (request.body && typeof FixedLengthStream !== "undefined") {
      // Decouple the inbound request stream from the R2 write and wait for both
      // sides. Newer workerd versions reject a request body that is still being
      // consumed after the Durable Object has returned its response.
      const { readable, writable } = new FixedLengthStream(contentLength);
      await Promise.all([
        request.body.pipeTo(writable),
        this.env.REMOTE_OBJECTS.put(key, readable, options),
      ]);
    } else {
      await this.env.REMOTE_OBJECTS.put(key, request.body, options);
    }
    await this.ctx.storage.put(uploadKey(transferId, index), {
      cipherBytes: expected.cipherBytes,
      cipherSha256: expected.cipherSha256,
      uploadedAt: Date.now(),
    });
    return json({ ok: true, index });
  }

  async commitTransfer(session, transferId) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (!transfer || transfer.senderDeviceId !== session.deviceId || transfer.status !== "uploading") {
      return problem(409, "transfer_not_uploading", "传送任务不存在或已提交");
    }
    const uploaded = await this.listStored(`upload:${transferId}:`);
    for (const expected of transfer.objects) {
      const stored = uploaded.get(uploadKey(transferId, expected.index));
      if (
        !stored ||
        stored.cipherBytes !== expected.cipherBytes ||
        stored.cipherSha256 !== expected.cipherSha256
      ) {
        return problem(409, "upload_incomplete", `第 ${expected.index + 1} 个文件尚未完整上传`);
      }
    }
    transfer.status = "ready";
    transfer.committedAt = Date.now();
    await this.ctx.storage.put(`transfer:${transferId}`, transfer);
    this.sendToDevice(transfer.recipientDeviceId, {
      type: "transfer-ready",
      transferId,
      senderDeviceId: transfer.senderDeviceId,
      objectCount: transfer.objects.length,
      totalCipherBytes: transfer.totalCipherBytes,
      expiresAt: transfer.expiresAt,
    });
    return json({ ok: true, status: transfer.status });
  }

  async getTransfer(session, transferId) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (!transfer || !canAccessTransfer(session, transfer)) {
      return problem(404, "transfer_missing", "远程传送任务不存在");
    }
    return json({ transfer: await this.transferSnapshot(transfer) });
  }

  async downloadObject(session, transferId, index) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (
      !transfer ||
      transfer.recipientDeviceId !== session.deviceId ||
      transfer.status !== "ready"
    ) {
      return problem(404, "object_unavailable", "远程文件尚不可下载");
    }
    const expected = transfer.objects.find((item) => item.index === index);
    if (!expected) return problem(404, "object_missing", "文件不在传送清单中");
    return new Response(null, {
      status: 204,
      headers: {
        "X-Relay-R2-Key": objectKey(transfer.workspaceId, transferId, index),
        "X-Cipher-Sha256": expected.cipherSha256,
      },
    });
  }

  async ackTransfer(session, transferId) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (!transfer || transfer.recipientDeviceId !== session.deviceId || transfer.status !== "ready") {
      return problem(409, "transfer_not_ready", "远程传送任务不存在或尚未就绪");
    }
    await this.deleteTransferObjects(transfer);
    transfer.status = "completed";
    transfer.completedAt = Date.now();
    await this.ctx.storage.put(`transfer:${transferId}`, transfer);
    this.sendToDevice(transfer.senderDeviceId, {
      type: "transfer-completed",
      transferId,
      recipientDeviceId: transfer.recipientDeviceId,
    });
    return json({ ok: true, status: transfer.status, ciphertextDeleted: true });
  }

  async cancelTransfer(session, transferId) {
    const transfer = await this.getStored(`transfer:${transferId}`);
    if (!transfer || !canAccessTransfer(session, transfer)) {
      return problem(404, "transfer_missing", "远程传送任务不存在");
    }
    await this.deleteTransferObjects(transfer);
    transfer.status = "cancelled";
    transfer.cancelledAt = Date.now();
    await this.ctx.storage.put(`transfer:${transferId}`, transfer);
    const other =
      session.deviceId === transfer.senderDeviceId
        ? transfer.recipientDeviceId
        : transfer.senderDeviceId;
    this.sendToDevice(other, { type: "transfer-cancelled", transferId });
    return json({ ok: true, status: transfer.status, ciphertextDeleted: true });
  }

  async alarm() {
    const now = Date.now();
    let nextExpiry = null;
    for (const prefix of ["challenge:", "session:"]) {
      const expiringValues = await this.listStored(prefix);
      for (const [key, value] of expiringValues) {
        if (value.expiresAt <= now) {
          await this.ctx.storage.delete(key);
        } else {
          nextExpiry =
            nextExpiry === null ? value.expiresAt : Math.min(nextExpiry, value.expiresAt);
        }
      }
    }
    const presence = await this.listStored("presence:");
    for (const [key, value] of presence) {
      if (Number(value.seenAt || 0) < now - 60_000) await this.ctx.storage.delete(key);
      else nextExpiry = nextExpiry === null ? value.seenAt + 60_000 : Math.min(nextExpiry, value.seenAt + 60_000);
    }

    const transfers = await this.listStored("transfer:");
    for (const [key, transfer] of transfers) {
      if (
        transfer.expiresAt <= now &&
        transfer.status !== "completed" &&
        transfer.status !== "cancelled" &&
        transfer.status !== "expired"
      ) {
        await this.deleteTransferObjects(transfer);
        transfer.status = "expired";
        transfer.expiredAt = now;
        await this.ctx.storage.put(key, transfer);
        this.sendToDevice(transfer.senderDeviceId, {
          type: "transfer-expired",
          transferId: transfer.transferId,
        });
        this.sendToDevice(transfer.recipientDeviceId, {
          type: "transfer-expired",
          transferId: transfer.transferId,
        });
      } else if (transfer.expiresAt > now) {
        nextExpiry = nextExpiry === null ? transfer.expiresAt : Math.min(nextExpiry, transfer.expiresAt);
      }
    }
    if (nextExpiry !== null) await this.ctx.storage.setAlarm(nextExpiry);
  }

  webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (message === "ping") {
      socket.send(JSON.stringify({ type: "pong", at: Date.now() }));
      return;
    }
    if (typeof message === "string" && message.length <= 4096) {
      try {
        const payload = JSON.parse(message);
        if (payload.type === "transfer-progress" && validId(payload.transferId)) {
          this.broadcast(
            {
              type: "transfer-progress",
              transferId: payload.transferId,
              deviceId: attachment?.deviceId,
              receivedCipherBytes: Math.max(0, Number(payload.receivedCipherBytes) || 0),
            },
            attachment?.deviceId,
          );
        }
      } catch {
        socket.send(JSON.stringify({ type: "error", code: "invalid_message" }));
      }
    }
  }

  webSocketClose(socket) {
    const deviceId = socket.deserializeAttachment()?.deviceId;
    if (deviceId) this.broadcast({ type: "device-offline", deviceId }, deviceId);
  }

  webSocketError(socket) {
    const deviceId = socket.deserializeAttachment()?.deviceId;
    if (deviceId) this.broadcast({ type: "device-offline", deviceId }, deviceId);
  }

  sendToDevice(deviceId, payload) {
    for (const socket of this.ctx.getWebSockets()) {
      if (socket.deserializeAttachment()?.deviceId === deviceId) {
        try {
          socket.send(JSON.stringify(payload));
        } catch {
          // A later close/error callback updates presence.
        }
      }
    }
  }

  broadcast(payload, exceptDeviceId = null) {
    for (const socket of this.ctx.getWebSockets()) {
      if (socket.deserializeAttachment()?.deviceId === exceptDeviceId) continue;
      try {
        socket.send(JSON.stringify(payload));
      } catch {
        // Ignore stale sockets; Cloudflare will deliver close/error callbacks.
      }
    }
  }

  async deleteTransferObjects(transfer) {
    await this.env.REMOTE_OBJECTS.delete(
      transfer.objects.map((item) =>
        objectKey(transfer.workspaceId, transfer.transferId, item.index),
      ),
    );
    const uploaded = await this.listStored(`upload:${transfer.transferId}:`);
    const keys = [...uploaded.keys()];
    for (let index = 0; index < keys.length; index += 128) {
      await this.ctx.storage.delete(keys.slice(index, index + 128));
    }
  }

  async transferSnapshot(transfer) {
    const uploaded = await this.listStored(`upload:${transfer.transferId}:`);
    const objects = transfer.objects.map((item) => {
      const stored = uploaded.get(uploadKey(transfer.transferId, item.index));
      return {
        ...item,
        uploaded: Boolean(stored),
        uploadedAt: stored?.uploadedAt || null,
      };
    });
    const uploadedObjects = objects.filter((item) => item.uploaded);
    return {
      ...transfer,
      objects,
      uploadedObjectCount: uploadedObjects.length,
      uploadedCipherBytes: uploadedObjects.reduce((sum, item) => sum + item.cipherBytes, 0),
      nextObjectIndex: objects.find((item) => !item.uploaded)?.index ?? null,
    };
  }

  async scheduleCleanup(expiresAt) {
    const current = await this.ctx.storage.getAlarm();
    if (current === null || expiresAt < current) await this.ctx.storage.setAlarm(expiresAt);
  }

  async invalidateDeviceSessions(deviceId) {
    const sessions = await this.listStored("session:");
    await Promise.all(
      [...sessions.entries()]
        .filter(([, value]) => value.deviceId === deviceId)
        .map(([key]) => this.ctx.storage.delete(key)),
    );
  }

  closeDeviceSockets(deviceId, code, reason) {
    for (const socket of this.ctx.getWebSockets()) {
      if (socket.deserializeAttachment()?.deviceId !== deviceId) continue;
      try {
        socket.close(code, reason);
      } catch {
        // The connection may already be closing.
      }
    }
  }

  /** @param {string} key @returns {Promise<any>} */
  async getStored(key) {
    return this.ctx.storage.get(key);
  }

  /** @param {string} prefix @returns {Promise<Map<string, any>>} */
  async listStored(prefix) {
    return this.ctx.storage.list({ prefix });
  }
}

export function canonicalize(value) {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new TypeError("Canonical JSON does not allow non-finite numbers");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalize).join(",")}]`;
  }
  if (typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalize(value[key])}`).join(",")}}`;
  }
  throw new TypeError("Unsupported canonical JSON value");
}

export function validCertificate(value) {
  if (!value || typeof value !== "object") return false;
  return (
    value.version === 1 &&
    validId(value.workspaceId) &&
    validId(value.deviceId) &&
    (value.role === "admin" || value.role === "member") &&
    validPublicJwk(value.signingPublicKey) &&
    validPublicJwk(value.agreementPublicKey) &&
    Number.isSafeInteger(value.serial) &&
    value.serial >= 1 &&
    Number.isSafeInteger(value.issuedAt) &&
    Number.isSafeInteger(value.expiresAt) &&
    value.expiresAt > value.issuedAt &&
    value.issuedAt <= Date.now() + MAX_CLOCK_SKEW_MS
  );
}

function validPublicJwk(value) {
  return (
    value &&
    value.kty === "EC" &&
    value.crv === "P-256" &&
    typeof value.x === "string" &&
    typeof value.y === "string" &&
    !value.d
  );
}

function normalizeObjects(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > MAX_OBJECTS) return null;
  const indexes = new Set();
  let total = 0;
  const result = [];
  for (const raw of value) {
    const index = Number(raw?.index);
    const cipherBytes = Number(raw?.cipherBytes);
    const cipherSha256 = String(raw?.cipherSha256 || "").toLowerCase();
    if (
      !Number.isSafeInteger(index) ||
      index < 0 ||
      indexes.has(index) ||
      !Number.isSafeInteger(cipherBytes) ||
      cipherBytes < 16 ||
      !/^[a-f0-9]{64}$/.test(cipherSha256)
    ) {
      return null;
    }
    indexes.add(index);
    total += cipherBytes;
    if (total > MAX_TRANSFER_BYTES) return null;
    result.push({ index, cipherBytes, cipherSha256 });
  }
  result.sort((a, b) => a.index - b.index);
  return result;
}

async function verifyEs256(publicJwk, data, signature) {
  try {
    const key = await crypto.subtle.importKey(
      "jwk",
      { ...publicJwk, ext: true, key_ops: ["verify"] },
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      fromBase64Url(signature),
      data,
    );
  } catch {
    return false;
  }
}

async function publicKeyFingerprint(jwk) {
  return sha256Hex(canonicalBytes(jwk));
}

function workspaceIdForFingerprint(fingerprint) {
  return `ws_${fingerprint.slice(0, 32)}`;
}

function canonicalBytes(value) {
  return new TextEncoder().encode(canonicalize(value));
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", value);
  return [...new Uint8Array(digest)].map((item) => item.toString(16).padStart(2, "0")).join("");
}

function fromBase64Url(value) {
  if (typeof value !== "string" || value.length > 2048) throw new TypeError("Invalid base64url");
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function randomId(bytes) {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  let binary = "";
  for (const item of value) binary += String.fromCharCode(item);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function validId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,128}$/.test(value);
}

function objectKey(workspaceId, transferId, index) {
  return `${workspaceId}/${transferId}/${String(index).padStart(6, "0")}.cipher`;
}

function uploadKey(transferId, index) {
  return `upload:${transferId}:${String(index).padStart(6, "0")}`;
}

function canAccessTransfer(session, transfer) {
  return (
    session.workspaceId === transfer.workspaceId &&
    (session.deviceId === transfer.senderDeviceId ||
      session.deviceId === transfer.recipientDeviceId)
  );
}

async function workspaceIdFromJson(request) {
  if (!["POST", "PUT", "PATCH"].includes(request.method.toUpperCase())) return null;
  const contentType = request.headers.get("Content-Type") || "";
  if (!contentType.includes("application/json")) return null;
  try {
    const clone = request.clone();
    const value = await clone.json();
    return value?.workspaceId || value?.certificate?.workspaceId || null;
  } catch {
    return null;
  }
}

async function readJson(request) {
  const contentType = request.headers.get("Content-Type") || "";
  if (!contentType.includes("application/json")) return null;
  try {
    return await request.json();
  } catch {
    return null;
  }
}

async function discardRequestBody(request) {
  if (request.body && !request.body.locked) await request.body.cancel();
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function problem(status, code, message) {
  return json({ ok: false, code, message }, status);
}

function safeError(error) {
  return {
    name: error instanceof Error ? error.name : "Error",
    message: error instanceof Error ? error.message : String(error),
  };
}
