import { DurableObject } from "cloudflare:workers";
import { WorkspaceRelayCore } from "./relay-core.js";

export * from "./relay-core.js";

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

    let response = await env.WORKSPACES.getByName(workspaceId).fetch(request);
    response = await materializeRelayResponse(response, env);
    console.log(JSON.stringify({ level: "info", event: "relay_request", requestId,
      method: request.method, path: url.pathname, status: response.status,
      durationMs: Date.now() - startedAt }));
    return response;
  },
};

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

export class WorkspaceRelay extends DurableObject {
  /** @param {DurableObjectState} ctx @param {Env} env */
  constructor(ctx, env) {
    super(ctx, env);
    this.core = new WorkspaceRelayCore(ctx, env);
  }

  fetch(request) { return this.core.fetch(request); }
  alarm() { return this.core.alarm(); }
  webSocketMessage(socket, message) { return this.core.webSocketMessage(socket, message); }
  webSocketClose(socket) { return this.core.webSocketClose(socket); }
  webSocketError(socket) { return this.core.webSocketError(socket); }
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

function problem(status, code, message) {
  return json({ ok: false, code, message }, status);
}

function validId(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,128}$/.test(value);
}

async function workspaceIdFromJson(request) {
  if (request.method !== "POST" || !request.headers.get("Content-Type")?.includes("application/json")) {
    return null;
  }
  try {
    const value = await request.clone().json();
    return value?.workspaceId || value?.certificate?.workspaceId || null;
  } catch {
    return null;
  }
}
