# -*- coding: utf-8 -*-
"""手机相册「使用次数」回读同步 + 局域网手机在线探测。

════════════════════════════════════════════════════════════════════════
为什么需要「回读同步」
════════════════════════════════════════════════════════════════════════
电脑端记录使用次数的路径有两条，且只有一条会回到电脑：

  ① 手机在「在线相册」里点平台按钮
     → POST /api/online/use-work → 电脑端作品标签.json 的 useCount +1。
     这条是通的。

  ② 作品被投送到手机后，用户在手机「本地相册」里分享到各平台
     → 手机端 WorkLibrary 只把本地 shareCount +1，电脑端一无所知。
     于是出现：手机上明明已经发了 2 次，电脑端仍然显示「已发送0次」，
     两端次数长期漂移，阶段目录判定（待首发 / 已发1次 / 已发2次）跟着失真。

本模块补的就是 ②：把手机 `/v2/works` 里的 shareCount 回写到电脑端的
`作品标签.json` 与 `manifest.json`，让两端次数一致。

════════════════════════════════════════════════════════════════════════
铁律（用户已拍板：「只补次数不搬文件」）
════════════════════════════════════════════════════════════════════════
1. **只增不减**：仅在「手机 shareCount > 电脑 useCount」时写入；
   手机端次数更小（例如手机被恢复出设备、换了另一台手机）绝不回退电脑端，
   避免把已经发好的作品误判回「待首发」被重复分发。
2. **只动数字，不动文件**：全程只写 `作品标签.json` / `manifest.json` 两个
   元数据文件，绝不 move / rmtree / 重命名任何作品目录。
3. **解析失败一律跳过**：原 JSON 不是合法 dict 时直接跳过并报告，绝不覆盖写坏。
4. **可审计**：每次写入都追加到 `_portfolio_move_logs/phone_count_sync_YYYYMM.csv`。
5. **dryRun 优先**：接口默认支持 dryRun，先看报告再落盘。

════════════════════════════════════════════════════════════════════════
为什么用扫描 /24 而不是收广播（在线状态面板）
════════════════════════════════════════════════════════════════════════
电脑端要回答「现在有哪几台手机在线、是哪台、版本多少」，正常做法是收手机发
的 UDP 广播。但广播只覆盖首次发现，手机切后台/省电模式下不一定会持续广播，
而且跨网段（有线/无线混合）广播过不来。

电脑端主动扫 /24 探 45833 端口则同时满足：不依赖手机端行为、跨网段可用、
拿到的是「此刻真的能被 TCP 连上」的硬证据。命中后再请求 `GET /v2/info`
补齐设备名/型号/版本/作品数，就是一个可直接渲染的在线面板。
"""

import json
import os
import re
import socket
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# 手机相册 OnlineService 的监听端口（0.0.0.0:45833）
PHONE_ALBUM_PORT = 45833

# 扫描参数：254 个地址 / 96 并发 / 0.5s 超时 → 最坏约 1.5s 出结果
SCAN_WORKERS = 96
SCAN_TIMEOUT = 0.5
INFO_TIMEOUT = 1.5
WORKS_TIMEOUT = 3.0

# 探测结果缓存时间（秒）：面板刷新不至于每次都全量扫
DISCOVER_TTL = 30.0


# ────────────────────────────────────────────────────────────────────────
# 本机网段推导
# ────────────────────────────────────────────────────────────────────────
def local_ipv4_addresses():
    """本机所有非回环、非链路本地 IPv4，用来推导要扫哪些 /24 网段。"""
    ips = set()

    # 默认路由出口 IP（UDP connect 不会真的发包，只是让内核选源地址）
    for probe in ("8.8.8.8", "114.114.114.114", "223.5.5.5"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.2)
            s.connect((probe, 53))
            ips.add(s.getsockname()[0])
            s.close()
        except Exception:
            pass

    # 主机名解析：多网卡（有线 + 无线）时能额外拿到几个
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass

    return sorted(
        ip for ip in ips
        if ip and not ip.startswith("127.") and not ip.startswith("169.254.")
    )


def candidate_subnets(ips=None, prefix_len=24):
    """把本机 IP 列表折算成待扫描的 /24 网段前缀（去重、保序）。

    返回形如 ["192.168.1", "192.168.31"]，调用方各自补 .1~.254。
    """
    if ips is None:
        ips = local_ipv4_addresses()
    out = []
    for ip in ips:
        parts = ip.split(".")
        if len(parts) != 4:
            continue
        subnet = ".".join(parts[:3]) if prefix_len == 24 else ".".join(parts[:2])
        if subnet not in out:
            out.append(subnet)
    return out


# ────────────────────────────────────────────────────────────────────────
# TCP 端口探测 + /v2/info / /v2/works 读取
# ────────────────────────────────────────────────────────────────────────
def probe_port(ip, port=PHONE_ALBUM_PORT, timeout=SCAN_TIMEOUT):
    """TCP 连得上就算命中。这是「此刻在线」最硬的证据。"""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except Exception:
        return False


def http_get_json(host, port, path, timeout=INFO_TIMEOUT):
    """GET 一个 JSON 接口。返回 (ok, data_or_None, err_str)。

    【必须绕开系统代理】Windows 上常见 HTTP_PROXY/HTTPS_PROXY 指向本机代理，
    urllib 默认会把这些局域网请求也丢给代理，结果要么超时、要么回一个 502
    Bad Gateway，看起来像「手机不在线」。所以这里显式用一个空 ProxyHandler
    的 opener，保证 192.168.x.x 一定走直连。
    """
    url = f"http://{host}:{port}{path}"
    opener = _direct_opener()
    try:
        with opener.open(url, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
        return True, json.loads(raw), ""
    except urllib.error.HTTPError as e:
        return False, None, f"HTTP {e.code}"
    except Exception as e:
        return False, None, str(e)


_DIRECT_OPENER = None


def _direct_opener():
    """一个「不走任何代理」的 urllib opener（懒加载单例）。"""
    global _DIRECT_OPENER
    if _DIRECT_OPENER is None:
        _DIRECT_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return _DIRECT_OPENER


def normalize_host(host, default_port=PHONE_ALBUM_PORT):
    """把 "192.168.1.200" / "192.168.1.200:45833" / "http://ip:port" 统一成 (ip, port)。"""
    host = (host or "").strip()
    if host.startswith("http://"):
        host = host[len("http://"):]
    elif host.startswith("https://"):
        host = host[len("https://"):]
    host = host.split("/")[0]
    if ":" in host:
        ip, _, port_str = host.rpartition(":")
        try:
            return ip.strip(), int(port_str)
        except ValueError:
            return host.strip(), default_port
    return host, default_port


def fetch_phone_works(host, port=PHONE_ALBUM_PORT, timeout=WORKS_TIMEOUT):
    """读手机本地库的作品列表。返回 (ok, list, err)。"""
    ok, data, err = http_get_json(host, port, "/v2/works", timeout=timeout)
    if not ok:
        return False, [], err
    if not isinstance(data, list):
        return False, [], "手机返回的作品列表格式不是数组"
    return True, data, ""


def discover_phones(port=PHONE_ALBUM_PORT, timeout=SCAN_TIMEOUT,
                    workers=SCAN_WORKERS, ips=None, subnets=None, enrich=True):
    """扫本机所在 /24 网段，找出所有在 45833 上应答的手机相册。

    enrich=True 时对每个命中地址再拉一次 /v2/info，补齐设备名/型号/版本/作品数，
    并用 packageName 校验确实是我方相册应用（避免别的服务恰好占用 45833）。
    """
    own_ips = set(ips if ips is not None else local_ipv4_addresses())
    prefixes = subnets if subnets is not None else candidate_subnets(list(own_ips), 24)

    targets = []
    for prefix in prefixes:
        for last in range(1, 255):
            ip = f"{prefix}.{last}"
            if ip in own_ips:
                continue
            targets.append(ip)

    if not targets:
        return []

    hits = []
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(targets)))) as pool:
        for ip, alive in zip(targets, pool.map(lambda x: probe_port(x, port, timeout), targets)):
            if alive:
                hits.append(ip)

    if not hits:
        return []

    now = time.time()

    def _enrich(ip):
        rec = {
            "ip": ip,
            "port": port,
            "online": True,
            "lastSeen": time.strftime("%Y-%m-%d %H:%M:%S"),
            "lastSeenTs": now,
            "transport": "wifi",
            "name": ip,
            "model": "",
            "deviceId": "",
            "appVersion": "",
            "versionCode": 0,
            "network": "",
            "workCount": -1,
            "state": "",
            "protocol": 0,
            "verified": False,
            "error": "",
        }
        if not enrich:
            return rec

        ok, info, err = http_get_json(ip, port, "/v2/info", INFO_TIMEOUT)
        if not ok or not isinstance(info, dict):
            rec["error"] = err or "info 解析失败"
            return rec

        pkg = str(info.get("packageName", ""))
        rec.update({
            "name": info.get("name") or ip,
            "model": info.get("model", ""),
            "deviceId": info.get("deviceId", ""),
            "appVersion": info.get("appVersion", ""),
            "versionCode": info.get("versionCode", 0) or 0,
            "network": info.get("network", ""),
            "workCount": info.get("workCount", -1),
            "state": info.get("state", ""),
            "protocol": info.get("protocol", 0) or 0,
            "autoReceiveEnabled": bool(info.get("autoReceiveEnabled", False)),
            "workCounts": info.get("workCounts") or {},
            # 只认自家相册包名；不是则标出来但仍展示（面板要能看见「非本产品占用」）
            "verified": pkg == "com.zwm.gallery",
            "packageName": pkg,
        })
        return rec

    with ThreadPoolExecutor(max_workers=max(1, min(len(hits), 32))) as pool:
        devices = list(pool.map(_enrich, hits))

    devices.sort(key=lambda d: _ip_sort_key(d["ip"]))
    return devices


def _ip_sort_key(ip):
    try:
        return tuple(int(p) for p in ip.split("."))
    except Exception:
        return (999, 999, 999, 999)


# ────────────────────────────────────────────────────────────────────────
# 结果缓存（面板 / 顺手回读都要用，避免每次请求都全量扫）
# ────────────────────────────────────────────────────────────────────────
class DiscoverCache:
    """带 TTL 的手机发现缓存。线程安全（用一把最朴素的锁）。"""

    def __init__(self, ttl=DISCOVER_TTL):
        self.ttl = ttl
        self._lock = threading.Lock()
        self._at = 0.0
        self._devices = []

    def get(self, force=False, **kwargs):
        now = time.time()
        with self._lock:
            if not force and self._devices and (now - self._at) < self.ttl:
                return self._devices
        devices = discover_phones(**kwargs)
        with self._lock:
            self._at = time.time()
            self._devices = devices
        return devices

    def peek(self):
        """不触发扫描，拿上一次结果（用于把「上次见到」与「本次扫到」合并展示）。"""
        with self._lock:
            return list(self._devices), self._at


# ────────────────────────────────────────────────────────────────────────
# 次数回读同步
# ────────────────────────────────────────────────────────────────────────
def _safe_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


# 名字归一化：抹平「同一作品在两端叫法不同」的常见差异
#   20260903_083549_桐庐夏日...方案_7张图            （手机端）
#   20260903_083549_桐庐夏日...方案_7张图[转]         （电脑端）
#   20260825_153330____COPY_FORMAT_2___xxx           （电脑端带流水线标记）
_ALBUM_NOISE_RE = re.compile(
    r"(\[转\]|【转】|\(转\)|（转）|_*COPY_FORMAT_\d+_*|<{0,3}COPY_FORMAT:\d+>{0,3})"
)


def normalize_work_name(name):
    """把作品名归一化成可比较的键；失败返回空串。"""
    s = (name or "").strip()
    if not s:
        return ""
    s = _ALBUM_NOISE_RE.sub("", s)
    s = s.replace("［", "[").replace("］", "]")
    s = re.sub(r"[\s_\-]+", "", s)
    return s.lower()


def match_phone_works(computer_works, phone_works):
    """把手机作品与电脑作品配对。返回 (matched, unmatched_phone)。

    配对键优先级：
      1. 手机 folderName（投送时的相对路径首段） == 电脑作品 id（就是文件夹名）
      2. 手机 id                                   == 电脑作品 id
      3. 手机 name                                 == 电脑作品 id
      4. 归一化名字唯一命中（仅当电脑端与手机端各自都只有一个候选时才认，
         避免把「同名不同作品」错配成一对，那种错配会把别人的次数写串）
    允许一个电脑作品只被认领一次，避免同名互相抢。
    """
    index = {}
    for wid, work in (computer_works or {}).items():
        key = (wid or "").strip()
        if key:
            index.setdefault(key, work)

    # 归一化索引：只在「归一化键唯一」时可用，否则该键作废（歧义即放弃）
    norm_index = {}
    for wid, work in index.items():
        nk = normalize_work_name(wid)
        if not nk:
            continue
        norm_index.setdefault(nk, []).append(work)

    matched = []
    unmatched = []
    claimed = set()

    for pw in (phone_works or []):
        if not isinstance(pw, dict):
            continue
        candidates = [
            str(pw.get("folderName", "") or "").strip(),
            str(pw.get("id", "") or "").strip(),
            str(pw.get("name", "") or "").strip(),
        ]
        hit = None
        for key in candidates:
            if key and key in index and id(index[key]) not in claimed:
                hit = (key, index[key])
                break

        if hit is None:
            # 归一化兜底：两端都必须唯一，才敢认这一对
            nk = normalize_work_name(pw.get("name", ""))
            pool = [w for w in norm_index.get(nk, []) if id(w) not in claimed]
            if nk and len(norm_index.get(nk, [])) == 1 and len(pool) == 1:
                hit = (pool[0].get("id", ""), pool[0])

        if hit is None:
            unmatched.append(pw)
            continue
        claimed.add(id(hit[1]))
        matched.append((hit[0], hit[1], pw))

    return matched, unmatched


def sync_phone_counts(computer_works, phone_works, phone_label="",
                      dry_run=False, log_dir=None):
    """核心同步：把手机 shareCount 回写电脑元数据（只增不减、只动数字）。

    computer_works: {作品id: work_dict}，work_dict 至少含 path / useCount
    phone_works:    GET /v2/works 的原始数组
    返回可直接序列化成 JSON 的报告。
    """
    matched, unmatched = match_phone_works(computer_works, phone_works)

    applied = []
    already = []
    skipped = []

    for wid, work, pw in matched:
        path = work.get("path") or ""
        if not path or not os.path.isdir(path):
            skipped.append({"workId": wid, "reason": "电脑端作品目录不存在", "path": path})
            continue

        phone_count = _safe_int(pw.get("shareCount"), 0)
        pc_count = _safe_int(work.get("useCount"), 0)

        if phone_count <= pc_count:
            already.append({
                "workId": wid,
                "pcUseCount": pc_count,
                "phoneShareCount": phone_count,
                "reason": "手机次数未超过电脑，无需回写（只增不减）",
            })
            continue

        entry = {
            "workId": wid,
            "pcUseCount": pc_count,
            "phoneShareCount": phone_count,
            "phoneUsed": bool(pw.get("used", False)),
            "path": path,
        }

        if dry_run:
            entry["dryRun"] = True
            applied.append(entry)
            continue

        ok, prev, msg = write_count_back(
            path, phone_count,
            meta={
                "at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "host": phone_label,
                "source": "phone_share_count",
            },
        )
        entry["written"] = ok
        entry["prevUseCount"] = prev
        entry["message"] = msg
        if ok:
            applied.append(entry)
            # 同步更新内存里这份 work，避免同一轮内对同一 id 重复写
            work["useCount"] = phone_count
        else:
            entry["reason"] = msg
            skipped.append(entry)

    if applied and not dry_run and log_dir:
        _append_sync_log(log_dir, phone_label, applied)

    return {
        "phone": phone_label,
        "dryRun": bool(dry_run),
        "matchedCount": len(matched),
        "phoneWorkCount": len(phone_works or []),
        "appliedCount": len(applied),
        "applied": applied,
        "alreadyInSyncCount": len(already),
        "alreadyInSync": already,
        "skipped": skipped,
        "unmatchedPhoneCount": len(unmatched),
        "unmatchedPhoneWorks": [
            {
                "id": pw.get("id", ""),
                "name": pw.get("name", ""),
                "folderName": pw.get("folderName", ""),
                "shareCount": _safe_int(pw.get("shareCount"), 0),
            }
            for pw in unmatched[:200]
        ],
    }


def write_count_back(work_path, new_count, meta=None):
    """只把次数写进 作品标签.json 与 manifest.json，绝不动任何作品文件。

    返回 (ok, prev_count, message)。
    作品标签.json 是扫描器读 useCount 的主源（_inspect_work_dir 优先读
    distribution.useCount），manifest.json 同步镜像一份，保证其它流水线
    （分发引擎、质量门禁）读到的次数也一致。
    """
    meta = meta or {}
    prev = None
    wrote_any = False

    # ── 1) 作品标签.json（主源）───────────────────────────────────────
    tag_file = os.path.join(work_path, "作品标签.json")
    if os.path.exists(tag_file):
        try:
            with open(tag_file, "r", encoding="utf-8") as fp:
                tag_data = json.load(fp)
        except Exception as e:
            return False, None, f"作品标签.json 解析失败，已跳过（绝不覆盖）：{e}"
        if not isinstance(tag_data, dict):
            return False, None, "作品标签.json 顶层不是对象，已跳过"

        dist = tag_data.get("distribution")
        if not isinstance(dist, dict):
            dist = {}
        prev = _safe_int(dist.get("useCount"), None)
        dist["useCount"] = int(new_count)
        dist["status"] = f"已使用{int(new_count)}次"
        dist["phoneSyncAt"] = meta.get("at", time.strftime("%Y-%m-%d %H:%M:%S"))
        dist["phoneSyncFrom"] = meta.get("host", "")
        dist["phoneSyncSource"] = meta.get("source", "phone_share_count")
        tag_data["distribution"] = dist

        try:
            with open(tag_file, "w", encoding="utf-8") as fp:
                json.dump(tag_data, fp, ensure_ascii=False, indent=2)
            wrote_any = True
        except Exception as e:
            return False, prev, f"作品标签.json 写入失败：{e}"

    # ── 2) manifest.json（镜像）──────────────────────────────────────
    manifest_file = os.path.join(work_path, "manifest.json")
    if os.path.exists(manifest_file):
        try:
            with open(manifest_file, "r", encoding="utf-8") as fp:
                manifest = json.load(fp)
        except Exception as e:
            # 标签已写成功的话也算成功，只是 manifest 跳过
            return wrote_any, prev, f"manifest.json 解析失败，已跳过：{e}"
        if isinstance(manifest, dict):
            if prev is None:
                prev = _safe_int(manifest.get("useCount"), None)
            manifest["useCount"] = int(new_count)
            manifest["used"] = int(new_count) > 0
            mdist = manifest.get("distribution")
            if not isinstance(mdist, dict):
                mdist = {}
            mdist["useCount"] = int(new_count)
            mdist["status"] = f"已使用{int(new_count)}次"
            manifest["distribution"] = mdist
            manifest["phoneCountSync"] = {
                "at": meta.get("at", time.strftime("%Y-%m-%d %H:%M:%S")),
                "host": meta.get("host", ""),
                "source": meta.get("source", "phone_share_count"),
                "phoneShareCount": int(new_count),
                "prevUseCount": prev,
            }
            try:
                with open(manifest_file, "w", encoding="utf-8") as fp:
                    json.dump(manifest, fp, ensure_ascii=False, indent=2)
                wrote_any = True
            except Exception as e:
                return wrote_any, prev, f"manifest.json 写入失败：{e}"

    if not wrote_any:
        return False, prev, "作品目录内没有 作品标签.json / manifest.json，无法回写"

    return True, prev, f"已把使用次数回写为 {int(new_count)}（原 {prev}）"


def _append_sync_log(log_dir, phone_label, applied):
    """审计日志：谁把哪个作品的次数从几改成几。"""
    try:
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(
            log_dir, f"phone_count_sync_{time.strftime('%Y%m')}.csv")
        need_header = not os.path.exists(log_file)
        with open(log_file, "a", encoding="utf-8-sig") as fp:
            if need_header:
                fp.write("时间,手机,作品ID,原次数,回读次数,作品路径\n")
            ts = time.strftime("%Y-%m-%d %H:%M:%S")
            for item in applied:
                fp.write("{},{},{},{},{},{}\n".format(
                    ts,
                    str(phone_label).replace(",", " "),
                    str(item.get("workId", "")).replace(",", " "),
                    item.get("pcUseCount", ""),
                    item.get("phoneShareCount", ""),
                    str(item.get("path", "")).replace(",", " "),
                ))
    except Exception:
        pass


# ────────────────────────────────────────────────────────────────────────
# 自查
# ────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import sys

    print("本机 IPv4:", local_ipv4_addresses())
    print("待扫网段:", candidate_subnets())
    t0 = time.time()
    devs = discover_phones()
    print(f"扫描耗时 {time.time() - t0:.2f}s，命中 {len(devs)} 台：")
    for d in devs:
        print("  -", json.dumps(d, ensure_ascii=False))
    if len(sys.argv) > 1:
        host = sys.argv[1]
        ip, port = normalize_host(host)
        ok, works, err = fetch_phone_works(ip, port)
        print(f"\n{ip}:{port} /v2/works ok={ok} count={len(works)} err={err}")
