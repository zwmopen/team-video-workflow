import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PORT = Number(process.env.DEVICE_SHARE_PORT || 45832);
const DEFAULT_HOST = process.env.DEVICE_SHARE_HOST || '0.0.0.0';
const MAX_JSON_BYTES = 2 * 1024 * 1024;
const MAX_FILES = 100;
const ONLINE_WINDOW_MS = 12_000;
const TASK_TTL_MS = 48 * 60 * 60 * 1000;

function nowIso() {
  return new Date().toISOString();
}

function randomId(prefix = '') {
  return `${prefix}${Date.now().toString(36)}-${crypto.randomBytes(8).toString('hex')}`;
}

function safeFileName(input, fallback = 'file') {
  const decoded = String(input || fallback).normalize('NFKC');
  const cleaned = decoded
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '')
    .trim()
    .slice(0, 180);
  return cleaned || fallback;
}

function isLoopback(address = '') {
  return address === '127.0.0.1' || address === '::1' || address.endsWith('127.0.0.1');
}

function getLanUrls(port) {
  const urls = [`http://127.0.0.1:${port}`];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) {
        urls.push(`http://${entry.address}:${port}`);
      }
    }
  }
  return [...new Set(urls)];
}

async function readJson(req, limit = MAX_JSON_BYTES) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw Object.assign(new Error('请求体过大'), { statusCode: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw Object.assign(new Error('JSON 格式无效'), { statusCode: 400 });
  }
}

function sendJson(res, statusCode, payload) {
  const body = Buffer.from(JSON.stringify(payload));
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
  });
  res.end(body);
}

function sendText(res, statusCode, text, contentType = 'text/plain; charset=utf-8') {
  const body = Buffer.from(text);
  res.writeHead(statusCode, {
    'content-type': contentType,
    'content-length': body.length,
    'cache-control': 'no-store',
  });
  res.end(body);
}

async function atomicWriteJson(filePath, value) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  const tmp = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  await fsp.writeFile(tmp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  await fsp.rename(tmp, filePath);
}

function mimeForStatic(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
  }[ext] || 'application/octet-stream';
}

export async function createDeviceShareServer(options = {}) {
  const host = options.host ?? DEFAULT_HOST;
  const requestedPort = options.port ?? DEFAULT_PORT;
  const dataDir = path.resolve(options.dataDir || process.env.DEVICE_SHARE_DATA || path.join(__dirname, '..', '.device-share-data'));
  const publicDir = path.resolve(options.publicDir || path.join(__dirname, 'public'));
  const tasksDir = path.join(dataDir, 'tasks');
  const configPath = path.join(dataDir, 'config.json');

  await fsp.mkdir(tasksDir, { recursive: true });

  let config;
  try {
    config = JSON.parse(await fsp.readFile(configPath, 'utf8'));
  } catch {
    config = {
      token: crypto.randomBytes(24).toString('base64url'),
      serverName: os.hostname() || '素材投送中控',
      createdAt: nowIso(),
    };
    await atomicWriteJson(configPath, config);
  }

  const devices = new Map();
  const tasks = new Map();

  async function loadTasks() {
    const names = await fsp.readdir(tasksDir, { withFileTypes: true }).catch(() => []);
    for (const entry of names) {
      if (!entry.isDirectory()) continue;
      const taskPath = path.join(tasksDir, entry.name, 'task.json');
      try {
        const task = JSON.parse(await fsp.readFile(taskPath, 'utf8'));
        tasks.set(task.id, task);
      } catch {
        // Ignore incomplete task directories. Cleanup removes them later.
      }
    }
  }
  await loadTasks();

  function taskDir(taskId) {
    return path.join(tasksDir, taskId);
  }

  async function saveTask(task) {
    task.updatedAt = nowIso();
    tasks.set(task.id, task);
    await atomicWriteJson(path.join(taskDir(task.id), 'task.json'), task);
  }

  function authorize(req, url) {
    const header = req.headers.authorization || '';
    const bearer = header.startsWith('Bearer ') ? header.slice(7) : '';
    const queryToken = url.searchParams.get('token') || '';
    return crypto.timingSafeEqual(
      Buffer.from((bearer || queryToken).padEnd(config.token.length, '\0')),
      Buffer.from(config.token.padEnd(Math.max(config.token.length, (bearer || queryToken).length), '\0')),
    ) && (bearer || queryToken).length === config.token.length;
  }

  function publicTask(task) {
    return {
      id: task.id,
      deviceId: task.deviceId,
      text: task.text || '',
      status: task.status,
      error: task.error || null,
      createdAt: task.createdAt,
      updatedAt: task.updatedAt,
      files: task.files.map(({ name, mime, size, sha256, uploaded }) => ({ name, mime, size, sha256, uploaded })),
    };
  }

  async function cleanup() {
    const cutoff = Date.now() - TASK_TTL_MS;
    for (const [id, task] of tasks) {
      const time = Date.parse(task.updatedAt || task.createdAt || 0);
      if (Number.isFinite(time) && time < cutoff && ['shared', 'failed', 'cancelled'].includes(task.status)) {
        tasks.delete(id);
        await fsp.rm(taskDir(id), { recursive: true, force: true });
      }
    }
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://localhost');
    const method = req.method || 'GET';

    try {
      if (method === 'GET' && url.pathname === '/api/local/config') {
        if (!isLoopback(req.socket.remoteAddress)) {
          return sendJson(res, 403, { error: '此接口仅允许电脑本机访问' });
        }
        const port = server.address()?.port || requestedPort;
        return sendJson(res, 200, {
          token: config.token,
          serverName: config.serverName,
          urls: getLanUrls(port),
        });
      }

      if (url.pathname.startsWith('/api/') && !authorize(req, url)) {
        return sendJson(res, 401, { error: '配对令牌无效' });
      }

      if (method === 'GET' && url.pathname === '/api/devices') {
        const now = Date.now();
        const list = [...devices.values()]
          .map((device) => ({
            ...device,
            online: now - device.lastSeenMs <= ONLINE_WINDOW_MS,
          }))
          .sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name, 'zh-CN'));
        return sendJson(res, 200, { devices: list });
      }

      if (method === 'POST' && url.pathname === '/api/device/heartbeat') {
        const body = await readJson(req);
        const deviceId = String(body.deviceId || '').trim();
        if (!deviceId) return sendJson(res, 400, { error: '缺少 deviceId' });
        const existing = devices.get(deviceId) || {};
        devices.set(deviceId, {
          ...existing,
          deviceId,
          name: String(body.name || existing.name || 'Android 设备').slice(0, 80),
          model: String(body.model || '').slice(0, 120),
          androidVersion: String(body.androidVersion || '').slice(0, 40),
          appVersion: String(body.appVersion || '').slice(0, 40),
          lastSeen: nowIso(),
          lastSeenMs: Date.now(),
          currentTaskId: body.currentTaskId || null,
        });
        return sendJson(res, 200, { ok: true, serverTime: nowIso() });
      }

      if (method === 'GET' && url.pathname === '/api/tasks') {
        const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') || 30)));
        const list = [...tasks.values()]
          .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
          .slice(0, limit)
          .map(publicTask);
        return sendJson(res, 200, { tasks: list });
      }

      if (method === 'POST' && url.pathname === '/api/tasks') {
        const body = await readJson(req);
        const deviceId = String(body.deviceId || '').trim();
        const files = Array.isArray(body.files) ? body.files : [];
        if (!deviceId) return sendJson(res, 400, { error: '请选择接收设备' });
        if (!files.length) return sendJson(res, 400, { error: '至少需要一个文件' });
        if (files.length > MAX_FILES) return sendJson(res, 400, { error: `单次最多 ${MAX_FILES} 个文件` });

        const id = randomId('task-');
        const task = {
          id,
          deviceId,
          text: String(body.text || '').slice(0, 50_000),
          status: 'uploading',
          createdAt: nowIso(),
          updatedAt: nowIso(),
          files: files.map((file, index) => ({
            index,
            name: safeFileName(file.name, `file-${index + 1}`),
            mime: String(file.mime || 'application/octet-stream').slice(0, 120),
            expectedSize: Number(file.size || 0),
            size: 0,
            sha256: null,
            uploaded: false,
            storedName: `${String(index).padStart(3, '0')}-${safeFileName(file.name, `file-${index + 1}`)}`,
          })),
        };
        await fsp.mkdir(path.join(taskDir(id), 'files'), { recursive: true });
        await saveTask(task);
        return sendJson(res, 201, { task: publicTask(task) });
      }

      const uploadMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)\/files\/(\d+)$/);
      if (method === 'PUT' && uploadMatch) {
        const [, taskId, indexText] = uploadMatch;
        const task = tasks.get(taskId);
        const index = Number(indexText);
        if (!task) return sendJson(res, 404, { error: '任务不存在' });
        if (task.status !== 'uploading') return sendJson(res, 409, { error: '任务已结束上传' });
        const fileMeta = task.files[index];
        if (!fileMeta) return sendJson(res, 404, { error: '文件序号不存在' });

        const finalPath = path.join(taskDir(taskId), 'files', fileMeta.storedName);
        const tempPath = `${finalPath}.uploading`;
        const hash = crypto.createHash('sha256');
        let size = 0;
        const out = fs.createWriteStream(tempPath, { flags: 'w' });
        req.on('data', (chunk) => {
          size += chunk.length;
          hash.update(chunk);
        });
        await new Promise((resolve, reject) => {
          req.pipe(out);
          req.on('aborted', () => reject(new Error('上传中断')));
          req.on('error', reject);
          out.on('error', reject);
          out.on('finish', resolve);
        });
        if (fileMeta.expectedSize > 0 && size !== fileMeta.expectedSize) {
          await fsp.rm(tempPath, { force: true });
          return sendJson(res, 400, { error: `文件大小不一致：期望 ${fileMeta.expectedSize}，实际 ${size}` });
        }
        await fsp.rename(tempPath, finalPath);
        fileMeta.size = size;
        fileMeta.sha256 = hash.digest('hex');
        fileMeta.uploaded = true;
        await saveTask(task);
        return sendJson(res, 200, { ok: true, size, sha256: fileMeta.sha256 });
      }

      const commitMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)\/commit$/);
      if (method === 'POST' && commitMatch) {
        const task = tasks.get(commitMatch[1]);
        if (!task) return sendJson(res, 404, { error: '任务不存在' });
        if (!task.files.every((file) => file.uploaded)) {
          return sendJson(res, 409, { error: '仍有文件未上传完成' });
        }
        task.status = 'queued';
        task.error = null;
        await saveTask(task);
        return sendJson(res, 200, { task: publicTask(task) });
      }

      const retryMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)\/retry$/);
      if (method === 'POST' && retryMatch) {
        const task = tasks.get(retryMatch[1]);
        if (!task) return sendJson(res, 404, { error: '任务不存在' });
        task.status = 'queued';
        task.error = null;
        await saveTask(task);
        return sendJson(res, 200, { task: publicTask(task) });
      }

      if (method === 'GET' && url.pathname === '/api/device/tasks/next') {
        const deviceId = String(url.searchParams.get('deviceId') || '');
        if (!deviceId) return sendJson(res, 400, { error: '缺少 deviceId' });
        const staleDeliveryCutoff = Date.now() - 5 * 60 * 1000;
        const task = [...tasks.values()]
          .filter((item) => item.deviceId === deviceId && (
            item.status === 'queued' ||
            (item.status === 'delivering' && Date.parse(item.deliveryStartedAt || 0) < staleDeliveryCutoff)
          ))
          .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))[0];
        if (!task) return sendJson(res, 200, { task: null });
        task.status = 'delivering';
        task.deliveryStartedAt = nowIso();
        await saveTask(task);
        return sendJson(res, 200, {
          task: {
            id: task.id,
            text: task.text || '',
            files: task.files.map((file) => ({
              index: file.index,
              name: file.name,
              mime: file.mime,
              size: file.size,
              sha256: file.sha256,
              downloadPath: `/api/device/tasks/${encodeURIComponent(task.id)}/files/${file.index}`,
            })),
          },
        });
      }

      const downloadMatch = url.pathname.match(/^\/api\/device\/tasks\/([^/]+)\/files\/(\d+)$/);
      if (method === 'GET' && downloadMatch) {
        const [, taskId, indexText] = downloadMatch;
        const deviceId = String(url.searchParams.get('deviceId') || '');
        const task = tasks.get(taskId);
        const fileMeta = task?.files?.[Number(indexText)];
        if (!task || !fileMeta || task.deviceId !== deviceId) {
          return sendJson(res, 404, { error: '文件不存在' });
        }
        const filePath = path.join(taskDir(taskId), 'files', fileMeta.storedName);
        const stat = await fsp.stat(filePath);
        res.writeHead(200, {
          'content-type': fileMeta.mime || 'application/octet-stream',
          'content-length': stat.size,
          'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(fileMeta.name)}`,
          'x-content-sha256': fileMeta.sha256 || '',
          'cache-control': 'no-store',
        });
        fs.createReadStream(filePath).pipe(res);
        return;
      }

      const statusMatch = url.pathname.match(/^\/api\/device\/tasks\/([^/]+)\/status$/);
      if (method === 'POST' && statusMatch) {
        const task = tasks.get(statusMatch[1]);
        const body = await readJson(req);
        if (!task || task.deviceId !== String(body.deviceId || '')) {
          return sendJson(res, 404, { error: '任务不存在' });
        }
        const allowed = new Set(['downloading', 'ready', 'shared', 'failed']);
        const status = String(body.status || '');
        if (!allowed.has(status)) return sendJson(res, 400, { error: '状态无效' });
        task.status = status;
        task.error = body.error ? String(body.error).slice(0, 2000) : null;
        await saveTask(task);
        return sendJson(res, 200, { ok: true });
      }

      if (method === 'GET' && !url.pathname.startsWith('/api/')) {
        const requestPath = url.pathname === '/' ? '/index.html' : url.pathname;
        const decoded = decodeURIComponent(requestPath);
        const candidate = path.resolve(publicDir, `.${decoded}`);
        if (!candidate.startsWith(publicDir + path.sep) && candidate !== path.join(publicDir, 'index.html')) {
          return sendText(res, 403, 'Forbidden');
        }
        try {
          const stat = await fsp.stat(candidate);
          if (!stat.isFile()) throw new Error('not file');
          res.writeHead(200, {
            'content-type': mimeForStatic(candidate),
            'content-length': stat.size,
            'cache-control': 'no-cache',
          });
          fs.createReadStream(candidate).pipe(res);
          return;
        } catch {
          return sendText(res, 404, 'Not Found');
        }
      }

      return sendJson(res, 404, { error: '接口不存在' });
    } catch (error) {
      const statusCode = Number(error.statusCode || 500);
      console.error(error);
      if (!res.headersSent) sendJson(res, statusCode, { error: statusCode >= 500 ? '服务器内部错误' : error.message });
      else res.destroy(error);
    }
  });

  const cleanupTimer = setInterval(() => cleanup().catch(console.error), 60 * 60 * 1000);
  cleanupTimer.unref();

  return {
    server,
    config,
    dataDir,
    async start() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(requestedPort, host, () => {
          server.off('error', reject);
          resolve();
        });
      });
      const actualPort = server.address().port;
      return { port: actualPort, urls: getLanUrls(actualPort), token: config.token };
    },
    async close() {
      clearInterval(cleanupTimer);
      if (!server.listening) return;
      await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    },
  };
}

async function main() {
  const app = await createDeviceShareServer();
  const info = await app.start();
  console.log('\n素材投送中控已启动');
  console.log(`电脑面板：${info.urls[0]}`);
  console.log('手机配对地址：');
  for (const url of info.urls.slice(1)) console.log(`  ${url}`);
  console.log(`配对令牌：${info.token}`);
  console.log('\n保持此窗口运行。按 Ctrl+C 停止。\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
