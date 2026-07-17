import test from 'node:test';
import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createDeviceShareServer } from '../server.mjs';

async function jsonFetch(url, token, options = {}) {
  const headers = new Headers(options.headers || {});
  if (token) headers.set('authorization', `Bearer ${token}`);
  if (options.body && typeof options.body === 'string') headers.set('content-type', 'application/json');
  const response = await fetch(url, { ...options, headers });
  const data = await response.json();
  return { response, data };
}

test('device heartbeat, upload, delivery and status lifecycle', async (t) => {
  const dataDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'device-share-test-'));
  const app = await createDeviceShareServer({ host: '127.0.0.1', port: 0, dataDir });
  const { port, token } = await app.start();
  t.after(async () => { await app.close(); await fsp.rm(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${port}`;

  let result = await jsonFetch(`${base}/api/device/heartbeat`, token, {
    method: 'POST', body: JSON.stringify({ deviceId: 'phone-1', name: '红米测试机', model: 'Redmi' }),
  });
  assert.equal(result.response.status, 200);

  result = await jsonFetch(`${base}/api/devices`, token);
  assert.equal(result.data.devices[0].name, '红米测试机');
  assert.equal(result.data.devices[0].online, true);

  const payload = Buffer.from('hello-image-data');
  result = await jsonFetch(`${base}/api/tasks`, token, {
    method: 'POST', body: JSON.stringify({ deviceId: 'phone-1', text: '测试文案', files: [{ name: 'a.jpg', mime: 'image/jpeg', size: payload.length }] }),
  });
  assert.equal(result.response.status, 201);
  const taskId = result.data.task.id;

  const upload = await fetch(`${base}/api/tasks/${taskId}/files/0`, {
    method: 'PUT', headers: { authorization: `Bearer ${token}`, 'content-type': 'image/jpeg' }, body: payload,
  });
  assert.equal(upload.status, 200);

  result = await jsonFetch(`${base}/api/tasks/${taskId}/commit`, token, { method: 'POST' });
  assert.equal(result.data.task.status, 'queued');

  result = await jsonFetch(`${base}/api/device/tasks/next?deviceId=phone-1`, token);
  assert.equal(result.data.task.id, taskId);
  assert.equal(result.data.task.text, '测试文案');

  const fileResponse = await fetch(`${base}${result.data.task.files[0].downloadPath}?deviceId=phone-1`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const downloaded = Buffer.from(await fileResponse.arrayBuffer());
  assert.deepEqual(downloaded, payload);
  assert.equal(fileResponse.headers.get('x-content-sha256'), crypto.createHash('sha256').update(payload).digest('hex'));

  result = await jsonFetch(`${base}/api/device/tasks/${taskId}/status`, token, {
    method: 'POST', body: JSON.stringify({ deviceId: 'phone-1', status: 'ready' }),
  });
  assert.equal(result.response.status, 200);

  result = await jsonFetch(`${base}/api/tasks`, token);
  assert.equal(result.data.tasks[0].status, 'ready');
});

test('protected endpoints reject invalid tokens', async (t) => {
  const dataDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'device-share-auth-'));
  const app = await createDeviceShareServer({ host: '127.0.0.1', port: 0, dataDir });
  const { port } = await app.start();
  t.after(async () => { await app.close(); await fsp.rm(dataDir, { recursive: true, force: true }); });
  const response = await fetch(`http://127.0.0.1:${port}/api/devices`, { headers: { authorization: 'Bearer wrong' } });
  assert.equal(response.status, 401);
});
