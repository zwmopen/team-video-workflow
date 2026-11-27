/**
 * Modular extensions for WorkspaceRelayCore:
 * 1. Multi-recipient shared-blob batch transfers (`POST /v1/transfers/batch`, `PUT /v1/batches/:id/objects/:i`, `POST /v1/batches/:id/commit`) with R2 refcounting.
 * 2. Traffic & R2 cost monitoring (`GET /v1/stats`, `POST /v1/stats/channel`).
 * 3. Authenticated Remote Online Album (`PUT/GET /v1/catalog`, `/v1/catalog/thumbs/:key`, `/v1/catalog/pull`, `/v1/catalog/pulls/...`, `/v1/catalog/use-work`, `/v1/catalog/events`).
 */

export const MAX_BATCH_RECIPIENTS = 32;
export const CATALOG_PULL_TTL_MS = 10 * 60 * 1000;
export const MAX_CATALOG_THUMBS = 1_200;
export const R2_FREE_STORAGE_BYTES = 10 * 1024 * 1024 * 1024;
export const R2_FREE_CLASS_A_OPS = 1_000_000;
export const R2_FREE_CLASS_B_OPS = 10_000_000;

/** @param {string} rangeHeader @param {number} totalSize */
export function parseByteRange(rangeHeader, totalSize) {
  if (!rangeHeader || !Number.isSafeInteger(totalSize) || totalSize <= 0) return null;
  const m = /^bytes=(\d*)-(\d*)$/i.exec(rangeHeader.trim());
  if (!m) return null;
  let start = m[1] === "" ? null : Number(m[1]);
  let end = m[2] === "" ? null : Number(m[2]);
  if (start === null && end === null) return null;
  if (start === null) {
    const suffixLen = Number(end);
    if (!Number.isSafeInteger(suffixLen) || suffixLen <= 0) return null;
    start = Math.max(0, totalSize - suffixLen);
    end = totalSize - 1;
  } else {
    if (!Number.isSafeInteger(start) || start < 0 || start >= totalSize) return { invalid: true };
    if (end === null || end >= totalSize) end = totalSize - 1;
    if (!Number.isSafeInteger(end) || end < start) return { invalid: true };
  }
  return { offset: start, length: end - start + 1, start, end };
}

/** @param {Response} response @param {{ REMOTE_OBJECTS: R2Bucket }} env @param {Request=} request */
export async function materializeR2WithRange(response, env, request) {
  const key = response.headers.get("X-Relay-R2-Key");
  if (!key) return response;
  const sha = response.headers.get("X-Object-Sha256") || response.headers.get("X-Cipher-Sha256") || "";
  const contentType = response.headers.get("X-Relay-Content-Type") || "application/octet-stream";
  const catRev = response.headers.get("X-Catalog-Revision");
  const catUpdated = response.headers.get("X-Catalog-Updated-At");
  const extraHeaders = {};
  if (catRev) extraHeaders["X-Catalog-Revision"] = catRev;
  if (catUpdated) extraHeaders["X-Catalog-Updated-At"] = catUpdated;

  const rangeHeader = request?.headers?.get("Range");
  if (rangeHeader) {
    const head = await env.REMOTE_OBJECTS.head(key);
    if (!head) {
      return new Response(JSON.stringify({ ok: false, code: "object_expired", message: "远程临时文件已过期" }), {
        status: 410,
        headers: { "Content-Type": "application/json; charset=utf-8" },
      });
    }
    const parsed = parseByteRange(rangeHeader, head.size);
    if (parsed && "invalid" in parsed && parsed.invalid) {
      return new Response("Range Not Satisfiable", {
        status: 416,
        headers: { "Content-Range": `bytes */${head.size}` },
      });
    }
    if (parsed && !("invalid" in parsed)) {
      const part = await env.REMOTE_OBJECTS.get(key, {
        range: { offset: parsed.offset, length: parsed.length },
      });
      if (!part) {
        return new Response(JSON.stringify({ ok: false, code: "object_expired", message: "远程临时文件已过期" }), {
          status: 410,
          headers: { "Content-Type": "application/json; charset=utf-8" },
        });
      }
      return new Response(part.body, {
        status: 206,
        headers: {
          "Content-Type": contentType,
          "Content-Length": String(parsed.length),
          "Content-Range": `bytes ${parsed.start}-${parsed.end}/${head.size}`,
          "Accept-Ranges": "bytes",
          "X-Object-Sha256": sha,
          "X-Cipher-Sha256": sha,
          "Cache-Control": "private, no-store",
          ...extraHeaders,
        },
      });
    }
  }

  const object = await env.REMOTE_OBJECTS.get(key);
  if (!object) {
    return new Response(JSON.stringify({ ok: false, code: "object_expired", message: "远程临时文件已过期" }), {
      status: 410,
      headers: { "Content-Type": "application/json; charset=utf-8" },
    });
  }
  return new Response(object.body, {
    status: 200,
    headers: {
      "Content-Type": contentType,
      "Content-Length": String(object.size),
      "Accept-Ranges": "bytes",
      "X-Object-Sha256": sha,
      "X-Cipher-Sha256": sha,
      "Cache-Control": "private, no-store",
      ...extraHeaders,
    },
  });
}

export function batchObjectKey(workspaceId, batchId, index) {
  return `${workspaceId}/blobs/${batchId}/${String(index).padStart(6, "0")}.bin`;
}

export function catalogManifestKey(workspaceId) {
  return `${workspaceId}/catalog/manifest.json`;
}

export function catalogThumbKey(workspaceId, thumbKey) {
  return `${workspaceId}/catalog/thumbs/${thumbKey}.jpg`;
}

export function catalogPullKey(workspaceId, pullId) {
  return `${workspaceId}/pulls/${pullId}.bin`;
}

export function safeThumbKey(value) {
  return typeof value === "string" && /^[A-Za-z0-9._-]{4,96}$/.test(value) ? value : null;
}

export function safeFileName(value) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > 260 || trimmed.includes("..") || trimmed.includes("\0") || /^[A-Za-z]:/.test(trimmed)) {
    return null;
  }
  return trimmed;
}

function todayKey(now = Date.now()) {
  return new Date(now).toISOString().slice(0, 10);
}

function monthKey(now = Date.now()) {
  return new Date(now).toISOString().slice(0, 7);
}

/** @param {DurableObjectState} ctx @param {Record<string, number>} delta */
export async function bumpStats(ctx, delta) {
  const now = Date.now();
  const dKey = `stats:day:${todayKey(now)}`;
  const mKey = `stats:month:${monthKey(now)}`;
  const day = /** @type {Record<string, number>} */ ((await ctx.storage.get(dKey)) || {});
  const month = /** @type {Record<string, number>} */ ((await ctx.storage.get(mKey)) || {});
  for (const [k, v] of Object.entries(delta)) {
    if (typeof v !== "number" || !Number.isFinite(v)) continue;
    day[k] = Math.max(0, (Number(day[k]) || 0) + v);
    month[k] = Math.max(0, (Number(month[k]) || 0) + v);
  }
  await Promise.all([ctx.storage.put(dKey, day), ctx.storage.put(mKey, month)]);
}

/** @param {DurableObjectState} ctx @param {number} objDelta @param {number} bytesDelta */
export async function adjustR2Active(ctx, objDelta, bytesDelta) {
  const cur = /** @type {{ objects?: number, bytes?: number }} */ (
    (await ctx.storage.get("stats:r2Active")) || { objects: 0, bytes: 0 }
  );
  const next = {
    objects: Math.max(0, (Number(cur.objects) || 0) + objDelta),
    bytes: Math.max(0, (Number(cur.bytes) || 0) + bytesDelta),
  };
  await ctx.storage.put("stats:r2Active", next);
}

/** @param {DurableObjectState} ctx */
export async function buildStatsPayload(ctx) {
  const now = Date.now();
  const day = /** @type {Record<string, number>} */ (
    (await ctx.storage.get(`stats:day:${todayKey(now)}`)) || {}
  );
  const month = /** @type {Record<string, number>} */ (
    (await ctx.storage.get(`stats:month:${monthKey(now)}`)) || {}
  );
  const r2Active = /** @type {{ objects?: number, bytes?: number }} */ (
    (await ctx.storage.get("stats:r2Active")) || { objects: 0, bytes: 0 }
  );
  const catalogMeta = /** @type {{ sizeBytes?: number } | null} */ (
    (await ctx.storage.get("catalog:meta")) || null
  );
  const activeBytes = Number(r2Active.bytes || 0) + Number(catalogMeta?.sizeBytes || 0);
  const activeObjects = Number(r2Active.objects || 0) + (catalogMeta ? 1 : 0);

  const classAOps = Number(month.classAOps || 0);
  const classBOps = Number(month.classBOps || 0);
  const storageRatio = activeBytes / R2_FREE_STORAGE_BYTES;
  const classARatio = classAOps / R2_FREE_CLASS_A_OPS;
  const classBRatio = classBOps / R2_FREE_CLASS_B_OPS;

  const billableStorageGb = Math.max(0, (activeBytes - R2_FREE_STORAGE_BYTES) / (1024 * 1024 * 1024));
  const billableClassAM = Math.max(0, (classAOps - R2_FREE_CLASS_A_OPS) / 1_000_000);
  const billableClassBM = Math.max(0, (classBOps - R2_FREE_CLASS_B_OPS) / 1_000_000);
  const estimatedR2Usd = Number(
    (billableStorageGb * 0.015 + billableClassAM * 4.5 + billableClassBM * 0.36).toFixed(4),
  );

  const warnings = [];
  if (storageRatio >= 0.8) warnings.push(`R2 当前存储已达免费额度 ${(storageRatio * 100).toFixed(1)}%`);
  if (classARatio >= 0.8) warnings.push(`R2 Class A 请求已达免费额度 ${(classARatio * 100).toFixed(1)}%`);
  if (classBRatio >= 0.8) warnings.push(`R2 Class B 请求已达免费额度 ${(classBRatio * 100).toFixed(1)}%`);

  return {
    dayKey: todayKey(now),
    monthKey: monthKey(now),
    today: {
      transfers: Number(day.transfers || 0),
      objects: Number(day.objects || 0),
      bytes: Number(day.bytes || 0),
      r2Bytes: Number(day.r2Bytes || 0),
      p2pBytes: Number(day.p2pBytes || 0),
      lanBytes: Number(day.lanBytes || 0),
      usbBytes: Number(day.usbBytes || 0),
    },
    month: {
      transfers: Number(month.transfers || 0),
      objects: Number(month.objects || 0),
      bytes: Number(month.bytes || 0),
      r2Bytes: Number(month.r2Bytes || 0),
      p2pBytes: Number(month.p2pBytes || 0),
      lanBytes: Number(month.lanBytes || 0),
      usbBytes: Number(month.usbBytes || 0),
      classAOpsEst: classAOps,
      classBOpsEst: classBOps,
      p2pAttempts: Number(month.p2pAttempts || 0),
      p2pConnected: Number(month.p2pConnected || 0),
      r2FallbackCount: Number(month.r2FallbackCount || 0),
    },
    r2: {
      activeObjects,
      activeBytes,
      egressFreeNote: "R2 出站下载流量免费（不按 GB 计费）",
      quotas: {
        storageLimitBytes: R2_FREE_STORAGE_BYTES,
        storageUsageRatio: Number(storageRatio.toFixed(6)),
        classALimit: R2_FREE_CLASS_A_OPS,
        classAUsageRatio: Number(classARatio.toFixed(6)),
        classBLimit: R2_FREE_CLASS_B_OPS,
        classBUsageRatio: Number(classBRatio.toFixed(6)),
      },
      estimatedCostUsd: estimatedR2Usd,
      isEstimate: true,
      billingNote: "预估费用仅供参考（不含 Workers/DO 超出免费额度部分），实际账单以 Cloudflare 控制台为准",
      warnings,
    },
  };
}
