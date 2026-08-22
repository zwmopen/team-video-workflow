#!/usr/bin/env node
import { createHash } from "node:crypto";
import { promises as fs, createReadStream } from "node:fs";
import os from "node:os";
import path from "node:path";

const MAX_ZIP_BYTES = 0xffffffff;
const MAX_FILES = 10_000;

function usage() {
  return "用法：node scripts/send-public-work.mjs --endpoint https://relay.example.com --token <会话令牌> --recipient <设备ID> --path <作品文件夹或ZIP>";
}

function args(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (!value.startsWith("--")) throw new Error(usage());
    const key = value.slice(2);
    if (!key || index + 1 >= argv.length) throw new Error(usage());
    values[key] = argv[++index];
  }
  return values;
}

function u16(value) {
  const result = Buffer.allocUnsafe(2);
  result.writeUInt16LE(value, 0);
  return result;
}

function u32(value) {
  const result = Buffer.allocUnsafe(4);
  result.writeUInt32LE(value >>> 0, 0);
  return result;
}

const crcTable = Array.from({ length: 256 }, (_, seed) => {
  let value = seed;
  for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (0xedb88320 & -(value & 1));
  return value >>> 0;
});

async function fileStats(file) {
  const hash = createHash("sha256");
  let crc = 0xffffffff;
  for await (const chunk of createReadStream(file)) {
    hash.update(chunk);
    for (const byte of chunk) {
      crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    }
  }
  const stat = await fs.stat(file);
  if (stat.size > 0xffffffff) throw new Error(`文件超过 4GB：${file}`);
  return { size: stat.size, crc: (crc ^ 0xffffffff) >>> 0, sha256: hash.digest("hex") };
}

async function listFiles(root) {
  const result = [];
  async function visit(directory, logicalRoot) {
    const entries = (await fs.readdir(directory, { withFileTypes: true }))
      .sort((left, right) => left.name.localeCompare(right.name, "zh-CN"));
    for (const entry of entries) {
      const absolute = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(absolute, path.posix.join(logicalRoot, entry.name));
      } else if (entry.isFile()) {
        const logical = path.posix.join(logicalRoot, entry.name);
        if (Buffer.byteLength(logical, "utf8") > 65535) throw new Error(`路径过长：${logical}`);
        result.push({ absolute, logical });
        if (result.length > MAX_FILES) throw new Error(`作品文件超过 ${MAX_FILES} 个`);
      }
    }
  }
  await visit(root, path.basename(root));
  if (!result.length) throw new Error("作品文件夹为空");
  return result;
}

async function writeZip(handle, buffer) {
  await handle.write(buffer);
}

async function buildStoredZip(source, destination) {
  const entries = await listFiles(source);
  const handle = await fs.open(destination, "w");
  const central = [];
  let offset = 0;
  try {
    for (const item of entries) {
      const metadata = await fileStats(item.absolute);
      const name = Buffer.from(item.logical, "utf8");
      if (offset > MAX_ZIP_BYTES || offset + 30 + name.length + metadata.size > MAX_ZIP_BYTES) {
        throw new Error("作品 ZIP 超过 4GB");
      }
      const localOffset = offset;
      const local = Buffer.concat([
        u32(0x04034b50), u16(20), u16(0x0800), u16(0), u16(0), u16(0),
        u32(metadata.crc), u32(metadata.size), u32(metadata.size),
        u16(name.length), u16(0), name,
      ]);
      await writeZip(handle, local);
      offset += local.length;
      for await (const chunk of createReadStream(item.absolute)) {
        await writeZip(handle, chunk);
        offset += chunk.length;
      }
      central.push({ name, metadata, localOffset });
    }
    const centralOffset = offset;
    for (const item of central) {
      const header = Buffer.concat([
        u32(0x02014b50), u16(20), u16(20), u16(0x0800), u16(0), u16(0), u16(0),
        u32(item.metadata.crc), u32(item.metadata.size), u32(item.metadata.size),
        u16(item.name.length), u16(0), u16(0), u16(0), u16(0), u32(0),
        u32(item.localOffset), item.name,
      ]);
      await writeZip(handle, header);
      offset += header.length;
    }
    const centralSize = offset - centralOffset;
    if (central.length > 65535 || centralOffset > MAX_ZIP_BYTES || centralSize > MAX_ZIP_BYTES) {
      throw new Error("作品 ZIP 目录超过格式限制");
    }
    await writeZip(handle, Buffer.concat([
      u32(0x06054b50), u16(0), u16(0), u16(central.length), u16(central.length),
      u32(centralSize), u32(centralOffset), u16(0),
    ]));
  } finally {
    await handle.close();
  }
}

function mimeFor(file) {
  const extension = path.extname(file).toLowerCase();
  if (extension === ".zip") return "application/zip";
  if ([".jpg", ".jpeg"].includes(extension)) return "image/jpeg";
  if (extension === ".png") return "image/png";
  if ([".mp4"].includes(extension)) return "video/mp4";
  return "application/octet-stream";
}

async function requestJSON(url, method, token, body) {
  const response = await fetch(url, {
    method,
    headers: { Accept: "application/json", Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let value;
  try { value = text ? JSON.parse(text) : {}; } catch { value = { message: text }; }
  if (!response.ok) throw new Error(value.message || value.code || `远程服务返回 ${response.status}`);
  return value;
}

async function main() {
  const options = args(process.argv.slice(2));
  const endpoint = String(options.endpoint || "").replace(/\/+$/, "");
  const token = String(options.token || "");
  const recipient = String(options.recipient || "");
  const source = path.resolve(String(options.path || ""));
  if (!/^https:\/\/[^/]+$/i.test(endpoint) || !token || !/^[A-Za-z0-9_-]{8,128}$/.test(recipient)) {
    throw new Error(usage());
  }
  const sourceStat = await fs.stat(source);
  let archive = source;
  let temporaryRoot;
  if (sourceStat.isDirectory()) {
    temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), "album-remote-"));
    archive = path.join(temporaryRoot, `album-folder-${path.basename(source)}.zip`);
    await buildStoredZip(source, archive);
  }
  try {
    const metadata = await fileStats(archive);
    const created = await requestJSON(`${endpoint}/v1/transfers`, "POST", token, {
      mode: "plain",
      recipientDeviceId: recipient,
      objects: [{ index: 0, bytes: metadata.size, sha256: metadata.sha256,
        name: path.basename(archive), mime: mimeFor(archive) }],
    });
    const uploadURL = `${endpoint}${created.uploads[0].path}`;
    const uploadResponse = await fetch(uploadURL, {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/octet-stream",
        "Content-Length": String(metadata.size) },
      body: createReadStream(archive),
      duplex: "half",
    });
    if (!uploadResponse.ok) throw new Error(`对象上传失败：HTTP ${uploadResponse.status}`);
    const committed = await requestJSON(`${endpoint}/v1/transfers/${created.transferId}/commit`, "POST", token, {});
    console.log(JSON.stringify({ ok: true, mode: "plain", transferId: created.transferId,
      status: committed.status, bytes: metadata.size, sha256: metadata.sha256,
      recipientDeviceId: recipient }, null, 2));
  } finally {
    if (temporaryRoot) await fs.rm(temporaryRoot, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
