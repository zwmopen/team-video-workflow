import { DurableObject } from "cloudflare:workers";
import { WorkspaceRelayCore } from "./relay-core.js";
import { materializeR2WithRange } from "./extensions.js";

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

    let response = await forwardToWorkspace(env.WORKSPACES.getByName(workspaceId), request);
    response = await materializeR2WithRange(response, env, request);
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
  if (!["POST", "PUT", "PATCH"].includes(request.method.toUpperCase()) ||
      !request.headers.get("Content-Type")?.includes("application/json")) {
    return null;
  }
  try {
    const value = await request.clone().json();
    return value?.workspaceId || value?.certificate?.workspaceId || null;
  } catch {
    return null;
  }
}
