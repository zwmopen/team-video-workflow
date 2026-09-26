# 局域网更新中转（LAN Update Relay）

> 服务版本 1.1.0 起内置。代码全部在 `scripts/online_gallery_service.py`。
> 相关 DSH：**DSH-113**（建中转）· **DSH-114**（扫描缓存）· **DSH-115**（出网代理轮试）· **DSH-116**（缓存按 sha256 落名）。
> 契约闸门：**A16 / A17 / A18 / A19**（`tests/parity_ios_android.py`）。

---

## 一、它到底解决什么问题

一句话：**电脑能出网，手机不能。所以电脑替手机去 GitHub 取东西。**

这台电脑上跑着 Clash（代理端口 7897 / 7890 / 7891 / 7892），出网没问题。
但手机在局域网里**没有任何代理**，`raw.githubusercontent.com` 和 `github.com/releases`
在手机上基本就是不可达。

于是会出现这种看起来很荒唐的状态：

- 仓库里已经是 **Android 0.8.61（versionCode 172）**
- 手机却一直停在 **0.8.58（versionCode 169）**
- 手机上点「检查更新」，永远回答「已是最新」

**不是手机不想升，是它根本看不到新版本长什么样。**

中转就是为这个断点修的：电脑去 GitHub 把「发布清单」和「安装包」取回来，
再在局域网里原样端给手机。手机全程只跟 `192.168.0.x:45835` 说话，不需要出网。

---

## 二、链路长什么样

```
                        ┌──────────────── 电脑（有代理）────────────────┐
                        │                                              │
  GitHub                │   在线相册服务 :45835                         │
  ┌──────────────┐      │   ┌────────────────────────────────────┐     │
  │ latest.json  │──1──▶│   │ fetch_update_manifest()            │     │
  │ album-*.apk  │      │   │  出网代理轮试：                     │     │
  │ album-*.ipa  │──2──▶│   │  上次成功的 > env > 7897/7890/      │     │
  └──────────────┘      │   │  7891/7892 > 直连兜底              │     │
        ▲               │   └───────────────┬────────────────────┘     │
        │               │                   │ 3. 改写 URL、落缓存       │
   走代理 7897           │                   ▼                          │
   （或 7890/7891/7892） │   ┌────────────────────────────────────┐     │
                        │   │ /latest.json  /download/apk         │     │
                        │   │ /altstore.json  /download/ipa       │     │
                        │   └───────────────┬────────────────────┘     │
                        └───────────────────┼──────────────────────────┘
                                            │ 4. 局域网 WiFi（手机无需代理）
                                            ▼
                                   ┌──────────────────┐
                                   │ 手机 :45833      │
                                   │ Android / iOS    │
                                   └──────────────────┘
```

手机上 `UpdateChecker` 的逻辑（Android `UpdateChecker.java:162`）是**先问局域网、再问云端**：

1. 从 `SharedPreferences` 里读上次连过的电脑地址（`lastKnownPcServer`）
2. 拼 `<电脑地址>/latest.json`，超时给得很短（连接 1.5s / 读取 2s）
3. 拿到了就用局域网的（打上 `source = "lan"`）
4. 没拿到才回落到 `raw.githubusercontent.com`（也就是老的、在手机上基本不可达的那条路）

---

## 三、对外就三个接口

| 路径 | 返回什么 | 谁在用 |
|---|---|---|
| `GET /latest.json` | 发布清单，里面的下载地址已改写成局域网路径 | Android + iOS |
| `GET /altstore.json` | AltStore 源（iOS 专用格式） | iOS（AltStore / Sideloadly） |
| `GET /download/apk`<br>`GET /download/ipa` | 安装包的原始字节 | Android / iOS |

---

## 四、清单里改了什么、没改什么

这是最容易搞错的地方，单独列清楚。

| 字段 | 中转怎么处理 | 为什么 |
|---|---|---|
| `apk_url` | `https://github.com/...` → `/download/apk` | 让手机从局域网下载 |
| `url` | 同上 | 老客户端读这个字段 |
| `ios.ipa_url` | → `/download/ipa` | 同上 |
| `version_name` / `version_code` | **一个字节都不动** | 手机靠它判断要不要升 |
| `sha256` / `ios.sha256` | **一个字节都不动** | 手机下载完会拿它校验 |
| `source` | 置为 `lan-relay` | 便于一眼看出包是从哪来的 |
| `relayedBy` | 置为电脑的局域网 IP | 排障时知道是哪台电脑中转的 |

**为什么 sha256 绝对不能动？**
因为手机端 `UpdatePackageValidator` 下载完会立刻算一遍 sha256 和清单里的比对，
不一致就判「更新包校验失败」并**把包删掉**。中转是逐字节转发，
如果校验值被改写，等于人为制造一个必失败的安装包。

---

## 五、四条硬规则（每一条都是踩坑换来的）

### 规则 1：出网必须多候选轮试（DSH-115）

Clash 的**混合端口会在 7890 / 7897 之间回跳**（2026-09-25 的 CDP 产线就因此停产过一次），
节点本身还会偶发掉线，表现为 `WinError 10054 远程主机强迫关闭了一个现有的连接`。

写死任何一个端口，都会在它跳走的那一刻让整条中转**静默失效**：
不报错，就是不给你包（`/download/apk` 只返回 110 字节的 `{"ok": false, ...}`）。

现在按序轮试：**上次成功的那个** → 环境变量 `DSH_UPDATE_PROXY` → `7897` → `7890` → `7891` → `7892` → 直连兜底。
第一个成功的赢，并把地址记进 `_UPDATE_PROXY_HINT`，下次优先用 —— 典型情况下零额外开销。

### 规则 2：安装包缓存必须按 sha256 落名，命中也要校验（DSH-116）★

**这是全文最重要的一条。** 详见下一节。

### 规则 3：清单缓存 300 秒

`UPDATE_MANIFEST_TTL = 300.0`。清单很轻，5 分钟取一次足够，
也避免每次手机问都去出网。想立刻刷新就重启服务。

### 规则 4：中转挂了，不能拖垮相册服务

`fetch_update_manifest()` 失败时**返回空 dict 并记账**，绝不抛异常。
相册该看图看图、该下作品下作品，只是这一轮不提供更新 —— 手机会自动回落到云端那条路。

---

## 六、DSH-116：为什么缓存名必须带内容指纹

### 现象（最阴的那类：两边都不报警）

中转看起来一切正常：

- `/api/online/status` 里 `downloads` 在涨
- `downloadFailures = 0`
- `lastError` 为空
- 手机也确实下到了 883308 字节的 APK

**但就是装不上。** Android 算完 sha256 跟清单对不上，判「更新包校验失败」并删包。
中转侧全绿，客户端侧永远失败，**没有任何一方报错**。

### 根因

缓存文件名只按版本号拼：`album-Android-0.8.61.apk`。

而 GitHub 会以**同一个 `version_name` 重发「重新构建过」的包** ——
CI 每跑一次就会 `gh release upload --clobber` 覆盖一遍，
重新构建出来的 APK **字节不同，体积却一模一样**（实测都是 883308）。

于是缓存命中旧字节，配的却是新清单里的 sha256：

| 来源 | sha256 |
|---|---|
| 清单 / GitHub `SHA256SUMS.txt` | `362908b0…` |
| 中转实际下发的字节 | `45635c6a…` ❌ |

更要命的是：**旧的命中分支读完文件直接 `return`，完全不校验**。
唯一的 sha256 校验写在「缓存不存在 → 重新下载」那条分支上，
而缓存**永远存在**，那行代码永远走不到。

### 实测证据：同一个 0.8.61，出现过三套不同字节

| 时间 | sha256 | 体积 | 说明 |
|---|---|---|---|
| 第一次发布 | `45635c6a…` | 883308 | 旧字节，被缓存住了 |
| 第二次发布 | `362908b0…` | 883308 | 清单更新了，缓存没更新 ⇒ 事故 |
| 第三次发布（CI `36246000789`） | `4c30c5c8…` | 883308 | 修复后，中转自动跟上 ✅ |

体积三次都一样 —— 因为是确定性重构建。
这证明「只按版本落名」在本仓库不是偶发事故，是**必然出事**的设计。

### 修法

1. `_update_cache_file(kind, version, sha)`：缓存名把 sha256 前 12 位编进去
   （`album-Android-0.8.61-4c30c5c84a50.apk`）
2. **命中分支也重算一遍 sha**，不一致就 `os.remove` 后重新下载
3. 新增两个可见指标：`shaMismatch`（错过几次）、`cachedSha`（现在端出去的是哪个包）

### 教训（通用，别只记这一处）

- **缓存名必须带内容指纹，不能只带版本号。**
- **「命中」分支和「冷路径」要同等校验**，只在冷路径校验等于没校验。
- 判「缓存能不能用」的唯一合法依据是**内容指纹**，不是文件名、不是体积、不是 mtime。

---

## 七、怎么一眼看出中转健康不健康

一条命令：

```bash
curl -s --noproxy '*' http://127.0.0.1:45835/api/online/status
```

看返回里的 `updateRelay` 和 `code` 两段：

| 字段 | 正常应该是什么 | 不对时说明什么 |
|---|---|---|
| `code.staleCode` | `false` | 是 `true` 说明**改了脚本没重启**，还在跑旧代码 |
| `cachedVersion` / `cachedVersionCode` | 与仓库 `build.gradle.kts` 一致 | 落后说明清单没刷新 |
| `cachedSha` | 与云端 `latest.json` 的 `sha256` 一致 | 不一致 = 手机装不上（DSH-116） |
| `shaMismatch` | `0` | 大于 0 说明缓存曾与清单对不上（已自动重下，但要留意） |
| `manifestFetches` | ≥ 1 | 是 0 说明**一次都没取成功过**，看 `proxy` |
| `manifestFailures` / `downloadFailures` | `0` | 大于 0 看 `lastError` |
| `proxy` | 当前**真正生效**的那个代理 | 是空说明还靠直连兜底 |
| `lastError` | `""` | 有内容就是最后一次失败原因 |

顺手验一下端到端（必须三个值都一样）：

```bash
# 1. 中转清单里的 sha256
curl -s --noproxy '*' http://127.0.0.1:45835/latest.json | python -c "import sys,json;print(json.load(sys.stdin)['sha256'])"

# 2. 中转实际下发的字节的 sha256
curl -s --noproxy '*' -o /tmp/a.apk http://127.0.0.1:45835/download/apk && sha256sum /tmp/a.apk

# 3. 云端清单里的 sha256（走代理）
curl -s -x http://127.0.0.1:7897 https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json | python -c "import sys,json;print(json.load(sys.stdin)['sha256'])"
```

---

## 八、排障手册

| 症状 | 最可能的原因 | 怎么确认 | 怎么修 |
|---|---|---|---|
| 手机一直说「已是最新」 | 手机没走局域网（连不上/没连过这台电脑） | 看中转 `manifestFetches` 有没有因为手机而增长 | 手机先连一次电脑在线相册，写进 `lastKnownPcServer` |
| 下载成功却提示「更新包校验失败」 | **DSH-116**：旧缓存顶着新清单 | 比对 `cachedSha` 和云端 sha256 | 已是修复版；老版本重启服务即可自愈 |
| `/download/apk` 只返回 110 字节 | 出网代理全挂（**DSH-115**） | 看 `updateRelay.lastError` | 检查 Clash 是否活着；代码已多候选轮试 |
| `staleCode = true` | 改了脚本没重启 | `/api/online/status` 直接写着 | `start_online_gallery_service.ps1 -Restart` |
| 手机连不上 `/latest.json` | 手机与电脑不在同一网段，或服务没起 | 手机浏览器打开 `http://<电脑IP>:45835/latest.json` | 检查 WiFi、检查服务在不在 |
| iPhone 永远不会自己升级 | **iOS 不能自装**（设计如此） | — | 只能电脑侧载：AltStore / Sideloadly |
| 安卓也不升，但中转是好的 | 工作台没在跑（自动推 APK 只存在于 Electron 主进程） | 看 4348 端口有没有监听 | 拉起工作台，或手机上手动打开一次相册 App |

⚠️ **`curl` 探本机端口必须加 `--noproxy '*'`**，否则会被注入的代理劫持，误判成服务挂了。

---

## 九、谁还在复用这套中转

- **手机端**（Android / iOS）：主要的消费者。
- **团建分发工作台**（`teambuilding-distribution-app-stable`，v0.19.77 起）：
  也读 `http://127.0.0.1:45835/latest.json` 拿最新安卓版，用来自动推 APK 给手机。
  ⛔ 注意：**localhost 调用一律禁用带代理的封装**（Node 的 `networkFetch` 会挂代理，把 localhost 劫持走）。

---

## 十、源码位置与闸门

| 东西 | 位置 |
|---|---|
| 出网代理轮试 | `_update_openers()` / `_update_fetch()` |
| 清单抓取与改写 | `fetch_update_manifest()` / `_rewrite_manifest_for_lan()` |
| 安装包代取与缓存 | `_download_upstream()` / `_update_cache_file()` / `_update_expected_sha()` |
| 健康快照 | `update_relay_health()` |
| 路由 | `if path in ("/latest.json", "/altstore.json")` / `if path.startswith("/download/")` |
| 闸门 | `tests/parity_ios_android.py` 的 **A16**（中转存在）/ **A17**（扫描 TTL）/ **A18**（代理轮试）/ **A19**（sha 缓存） |

改这块代码的规矩：
**先加/改契约项并确认它 FAIL，再改实现，最后 PASS。**
写完必须证明闸门真的会响 —— 把实现换成错误版本跑一遍，确认 FAIL（见 `MEMORY.md` 第二节）。

---

## 十一、验收清单（改完之后逐条打勾）

1. `python tests/parity_ios_android.py` → 全 PASS（当前 **43 项**）
2. `python -m unittest test_online_gallery_service` → 全过（当前 **29 项**）
3. 重启服务 → `/api/online/status` 里 `code.staleCode == false`
4. 第七节那三条 sha256 **互相对得上**
5. `/api/online/status` 里 `shaMismatch == 0`、`downloadFailures == 0`
6. 真机：手机打开相册 App → 弹出更新提示 → 下载 → **安装成功**（不是「校验失败」）
7. `git push` → 盯 CI `device-share-hub.yml` 全绿（含 `publish-gallery-updates`）

---

## 📝 变更记录

| 日期 | 执行者 | 记录 |
|---|---|---|
| 2026-09-26 | WorkBuddy | 新建：DSH-113~116 局域网更新中转完整说明 |
