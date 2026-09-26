# DSH-102 — iPhone 在线相册全库串图（image_name_index 同名冲突）

**症状**: 2026-09-24 用户反馈：iPhone 上点「在线相册 → 杭州分类 → 江浙沪小众秘境 Top9🍁」详情页，路径/标题/文案都对，但首图缩略图显示阳澄湖（其余图位也错位）。Android 端正常。
**根因链**:
1. 库内 393 套作品大量共用 `P1_封面.png` / `P1.png` 这类同名标识图。
2. 服务端 `image_name_index()` 按文件名裸名建索引，**同名只保留首个命中**（online_gallery_service.py 旧实现，DSH-101 修复 404 时未触及）。
3. iOS 客户端走 `?path=<裸文件名>` 旧契约，永远拿第一个被扫到的作品对应的图。
4. DSH-101 用 `resolve_image_path()` 兜底修 404，反而把同名首命中问题一起带到新契约，演变为 DSH-102。
**修复**:
- 服务端 `/image`：`?id + ?file` 同时存在时，优先用 `work_id` 查 work 真实路径 + `basename(file)` 拼候选；旧 `resolve_image_path` 仅作兜底。
- iOS：`loadImage(path:workId:isThumbnail:maxPixel:completion:)` 加 `workId` 参数；有 `workId` ⇒ `?id+?file`；无 ⇒ 旧 `?path=` 兜底。
- iOS `ContentView.swift` 6 处 caller 全部传 `entry.id`。
**验证（闸门先改后判）**:
- `tests/test_ios_online_gallery_client.py` B5/B6/B7 改前 FAIL → 改后 PASS（7/7）。
- `tests/parity_ios_android.py` C25 改前 FAIL → 改后 PASS（28/28）。
- curl `?id=<秘境 Top9 work_id>&file=P1_封面.png`：改前 sha256=a8077f8a52a5d46b（错图）/ 改后 sha256=cefc78b909831e86（✅ 真图）。
- iOS 升 0.8.38/110 → **0.8.39/111**。

# Bug、根因与回归账本

只记录脱敏、可复现、可复用的结论。新增问题必须补齐现象、根因、修复、证据和回归要求，不能只贴原始日志。

> **账本积压说明（2026-09-20 记录）**：本文件最新条目此前停在 DSH-075（Android 0.8.12），
> 而实际版本已推进到 0.8.40，中间多轮修复未按本文件格式补记。DSH-076 起恢复记录，
> 中间缺口未回填（不冒充完整），建议后续按 `git log` 回溯补齐。当前最新条目为 **DSH-101**。

## DSH-119 电脑端「停止在线相册服务」可能误杀别的进程 + 仓库里两个 C++ 测试是空跑的假闸门（2026-09-27）

### 119-A 「停止」按端口找 PID 就杀，而端口是用户能改的

**现象**：电脑客户端 V4.3.30 把在线相册服务收进「设置」时，第一版
`online_service::Stop()` 只做「按端口找到监听中的 PID → TerminateProcess」。

**根因**：端口是可配置的（存在 `HKCU\Software\ZWMLabs\DeviceShareHub\OnlineGallery`，
`LoadConfig()` 会把它读出来）。如果用户把端口改成了一个别的程序正在用的端口，
点一下「停止」就会把那个程序的进程直接杀掉 —— **而且没有任何提示**。

**修复**：`Stop()` 在结束进程之前先 `Probe(port)` 一次，用 `/api/online/status` 的
字段（`totalWorks` / `libraryRoot`）确认这个端口上跑的确实是自家的服务；
不是就直接报错退出，一个进程都不动。顺带补上 `*pid == 0` 的保护。

**证据**：`ParseStatusJson()` 里那条「响应里没有在线相册的字段 ⇒ 判定为不可达」
就是给这个用途准备的，不是顺手写的。

**回归要求**：这一段属于系统操作（要真的起进程），不在 CI 单测覆盖范围内，靠代码评审守住。
改这里时必须确认「端口上是别的服务 ⇒ 不做任何操作」这个分支还在。

### 119-B 仓库里已有的两个 C++ 测试，在 Release 下完全没在测

**现象**：`tests/content_store_tests.cpp` 和 `tests/send_to_integration_tests.cpp`
的判据全部用 `assert()` 写。

**根因**：CI 是按 Release 构建的（`cmake --build --config Release`），
Release 定义了 `NDEBUG`，`assert()` 会被预处理器**整段编译掉**。
这两个测试可执行起来永远是「编译通过 → 打印 passed → 退出码 0」。

**危害**：不是「测得不严」，是**一条都没在跑**。任何回归都能过。

**本轮做法**：新增的 `tests/online_service_tests.cpp` 不用 `assert`，
改用自定义 `CHECK()`：失败时打印行号、累加失败数、`wmain` 返回非零。
⚠️ 现有那两个测试本轮**没有改动**（不在本轮范围，也避免一次动太多），
但记账在此 —— 下一轮应一并换成 `CHECK`。

**回归要求**：把 `online_service_tests.cpp` 里任意一条 `CHECK(...)` 换回 `assert(...)`，
再把对应实现改坏，`ctest` 就不该红；一旦不红，说明又退回假闸门了。

## DSH-101 服务端 `/api/online/image` use-work 移走作品后取图 404（服务端 fix，2026-09-24）

> **用户现场反馈**：K60 装 0.8.55/166（DSH-100 build）→ 用户点同一作品平台按钮 → 服务端返回 `HTTP 404 下载 已发送0次（抖音小红书可发）/20260920_CodexAPI-马岭古道徒步攻略/产出素材/P2.png 失败`。
> K60 logcat（12:29:08）完整堆栈：
> ```
> W OnlineGalleryClient: downloadWorkImages HTTP 404 | 20260920_CodexAPI-马岭古道徒步攻略/已发送0次（抖音小红书可发）/20260920_CodexAPI-马岭古道徒步攻略/产出素材/P2.png
> W OnlineGalleryClient: downloadWorkImages failed: Exception: HTTP 404 下载 ...
>   at com.zwm.gallery.OnlineGalleryClient.lambda$downloadWorkImages$47(OnlineGalleryClient.java:1325)
> ```

**现象**：K60 装 DSH-100 build（0.8.55/166）→ 点平台按钮 → DSH-099 双写 100% 工作（logcat 抓到 9 条 OnlineGalleryClient TAG + DiagnosticLog 命中）→ 但下载依然失败（ENOENT 没了，新出现 HTTP 404）。

**根因**：`handleOnlineWorkUse` 单击流程：
1. 客户端先调 `/api/online/use-work`（记录使用 + 把作品从「已发送0次」移到「_已发送1次」）
2. 紧接着调 `/api/online/image?id=X&file=Y`（下载原图）

老服务端 image endpoint 逻辑（`scripts/online_gallery_service.py:1614-1625` 改前）：
```python
if work_id and file_name:
    target_work = self.scanner.get_work(work_id)
    if not target_work:
        self.send_error(404, "Work not found")        # ← 这条死路径
        return
    img_path = os.path.join(target_work["path"], file_name)  # ← 拼错
    if not os.path.isfile(img_path):
        resolved = self.scanner.resolve_image_path(file_name)  # ← 兜底但路径已错
        if resolved:
            img_path = resolved
```

- `target_work["path"]` 在 use-work 后被 update 成 `_已发送1次（微信公众号可发）/20260920_CodexAPI-马岭古道徒步攻略`（新分类）
- `file_name` 是客户端拿到的原下发值：`已发送0次（抖音小红书可发）/20260920_CodexAPI-马岭古道徒步攻略/产出素材/P2.png`（含旧分类前缀）
- `os.path.join(new_path, old_file)` → `_已发送1次/.../已发送0次/.../产出素材/P2.png`——**两个分类前缀撞车，根本不存在**

更糟的是：客户端的 file_name 永远是**客户端列表接口拿到的原值**（即 list 时这条作品还在「已发送0次」时的下发值），list 拉完→ 点按钮 → use-work 移走 → download 用旧 file_name + 新 path，**结构上必坏**。

**修复**（服务端 fix，**不动客户端代码**）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `scripts/online_gallery_service.py:1607-1640` `/api/online/image` endpoint | 删除 `get_work` + `os.path.join(path, file_name)` + `get_work 失败 404` 三段，改成 `img_path = self.scanner.resolve_image_path(file_name) or ""` —— 内部 `os.path.join(self.root, raw)` 正确处理成品库根相对路径 |
| 2 | `scripts/test_online_gallery_service.py` `TestSubdirImages` | 加 `test_dsh101_get_work_miss_falls_back_to_image_name_index` 闸门：模拟 use-work 移走 → resolve_image_path 必须用 file_name 直接命中 |
| 3 | `CHANGELOG.md` 顶部 | 加 DSH-101 服务端 fix 条目（**不 bump 客户端版本号**，客户端代码未改） |

**证据**：
- **K60 logcat 12:29:08**：`downloadWorkImages HTTP 404 | 20260920_CodexAPI-马岭古道徒步攻略/已发送0次（抖音小红书可发）/20260920_CodexAPI-马岭古道徒步攻略/产出素材/P2.png`——path 含两个分类前缀（重叠）
- **服务端直测**：`python -m unittest test_online_gallery_service.TestSubdirImages` = **5/5 PASS**（DSH-101 + 原 4 个 subdir 测试全绿）
- **服务端 image API 直测**：`GET /api/online/image?id=X&file=_已发送1次（微信公众号可发）/20260920_CodexAPI-马岭古道徒步攻略/产出素材/P2.png` 返回 200 + 2.28MB
- **客户端实测 DSH-099 双写 100% 工作**：logcat 抓到 9 条 OnlineGalleryClient TAG（含 HTTP 404 错误详情 + 完整堆栈），DSH-099 双写架构有效

**回归要求**：
- K60 重插后点同一已移走作品的剩余平台按钮 → 期望下载成功
- iPhone 0.8.38/110 装好后同样测试 → 期望 NSLog 抓到 `loadImage` 路径上的成功日志（不再 failed）
- `test_online_gallery_service.py` **5/5 PASS**（含 DSH-101）

**与 DSH-100 协同**：
- DSH-100 修客户端写盘 ENOENT（`new File(targetDir, fileName)` 缺 mkdirs）
- DSH-101 修服务端取图 404（`os.path.join(path, file_name)` 重复拼接）
- **两个一起 = use-work → download 一条龙跑通**

**配套独立闸门**（`scripts/test_online_gallery_service.py` `TestSubdirImages` 5 项）：
- 原 4 项同 DSH-100（`test_subdir_images_work_is_visible` / `test_subdir_image_path_is_resolvable` / `test_toplevel_layout_unchanged` / `test_no_image_work_still_excluded`）
- **DSH-101 新增**：`test_dsh101_get_work_miss_falls_back_to_image_name_index`——`resolve_image_path` 拿 file_name 必须直接命中 use-work 移走后的真身

## DSH-100 Android downloadWorkImages 嵌套子目录 mkdirs 缺失 → FileNotFoundException ENOENT（Android 0.8.55/166，2026-09-24）

> **用户现场反馈**：「哦点击了''」→ 在 K60 上点平台按钮下载原图 → 触发 DSH-099 修复的 `downloadWorkImages` catch 路径 → 从 K60 logcat（12:10:00）抓到完整堆栈：
> ```
> W OnlineGalleryClient: downloadWorkImages failed: FileNotFoundException:
> /data/user/0/com.zwm.gallery/files/work-library/online/<workId>/已发送0次（抖音小红书可发）/20260917_CodexAPI-AUTUMN-C-评3-赞2-中秋手工团建/产出素材/P1.png:
> open failed: ENOENT (No such file or directory)
>   at com.zwm.gallery.OnlineGalleryClient.lambda$downloadWorkImages$47(OnlineGalleryClient.java:1322)
> ```

**现象**：K60 装 0.8.54/165（DSH-099 build）后用户点平台按钮下载原图 → **依然"下载图片失败"**（失败原因不再是 catch 静默，而是真 ENOENT）。

**根因**：服务端 `_collect_images` 两级策略（`scripts/online_gallery_service.py:1058-1090`）—— 当作品根目录无图 → 回退 `产出素材/` 子目录 → 返回**成品库根相对路径**：
```
已发送0次（抖音小红书可发）/20260917_CodexAPI-AUTUMN-C-评3-赞2-中秋手工团建/产出素材/P1.png
```
iOS 走 `?path=` 走 `resolve_image_path()` 原生解析（含解析兜底 `line 1621`），Android 走 `?id=..&file=` 后 `new File(targetDir, fileName)`，**中间的 `已发送0次（抖音小红书可发）/20260917_.../` 子目录从未 mkdirs** → FileOutputStream 打开嵌套路径直接 ENOENT。

**典型 iOS/Android 不对称 BUG**：
- iOS：发现得早（line 932 注释明说 + `resolve_image_path` 兜底）
- Android：从来没有这个细节——`new File(parent, child)` 把含 `/` 的 child 当单一文件名，**Linux fs 只认路径不认抽象逻辑**

**DSH-099 价值体现**：DSH-099 之前用户只会看到"下载图片失败: 网络超时" toast；DSH-099 之后**8 分钟定位到精确行号（line 1322）+ fileName + 完整堆栈 + Caused by**。DSH-099 修了"看不见 BUG"；DSH-100 修了"看得到的 BUG"。

**修复**（Android 0.8.55/166）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `OnlineGalleryClient.java:1303-1307` `downloadWorkImages` | `new File(targetDir, fileName)` 后立即加 `java.io.File parent = localFile.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();` 递归创建所有中间子目录 |
| 2 | `tests/test_android_online_gallery_client.py` | 加 A8 闸门：`localFile.getParentFile()` + `parent.mkdirs` + `targetDir.mkdirs` 三处必须存在 |
| 3 | `android/app/build.gradle.kts:13-14` | `versionCode 165 → 166`, `versionName 0.8.54 → 0.8.55` |
| 4 | `CHANGELOG.md` 顶部 | 加 DSH-100 条目 |

**证据**：
- **K60 logcat 12:10:00 完整堆栈**：`downloadWorkImages failed: FileNotFoundException ... open failed: ENOENT` + `at OnlineGalleryClient.lambda$downloadWorkImages$47(OnlineGalleryClient.java:1322)` + `Caused by: android.system.ErrnoException: open failed: ENOENT`
- **服务端 `_collect_images` line 1058-1090 两级策略摸底**：根目录有图 → 裸文件名；根目录无图 → 回落 `产出素材/` → 返回成品库根相对路径
- **服务端 line 932 注释**："images 值也可能是「成品库根相对路径」（图在 产出素材/ 的作品）"——iOS 早考虑到了，Android 没跟进
- **闸门验证纪律**：HEAD 旧版跑 `test_android_online_gallery_client.py` = **A8 FAIL**（`parent.mkdirs=False`），整体 **7/8 FAIL**；工作区 = **A8 PASS**，整体 **8/8 PASS**

**回归要求**：
- K60 装 0.8.55 后触发同一作品的平台按钮，下载应该成功；图片落到 `/data/user/0/com.zwm.gallery/files/work-library/online/<workId>/已发送0次（抖音小红书可发）/20260917_.../产出素材/P1.png`（中间目录由 mkdirs 自动创建）
- DiagnosticLog 应该没 `download_work_failed` / `download_work_http_error` 事件
- `tests/test_android_online_gallery_client.py` **8/8 PASS**（A1~A8）

**配套独立闸门**（`tests/test_android_online_gallery_client.py` 8 项，DSH-100 增 A8）：
- A1~A7 同 DSH-098 / DSH-099
- **A8** `downloadWorkImages` 嵌套子目录 mkdirs（修 FileNotFoundException ENOENT）

## DSH-099 Android downloadWorkImages（原图下载）错误路径补双写（Android 0.8.54/165，2026-09-24）

> **用户反馈**：2026-09-24 现场 K60 点平台按钮下载原图，弹 "下载图片失败..data/user..." toast（`MainActivity.java:4444`），用户明确说"下载图片失败..data/user..."。
>
> **DSH-098 漏的另一半**：DSH-098 修了 `downloadThumb`（缩略图）+ `loadFullImage` + `loadThumbnail` catch 的 Log.w(TAG) + DiagnosticLog 双写，但**漏了 `downloadWorkImages`（原图下载）catch**——属于"修了一半"。这条路径 catch 块只 `callback.onError`，静默失败，根因拿不到，违背用户硬约束"所有开发都要 debug 回传"。

**现象**：用户点平台按钮下载原图（如小红书 / 抖音 / 微信一键分享的"先同步到手机"流程）→ 弹 "下载图片失败: XXX, 文案已在剪贴板" toast → 失败原因不明，logcat 没有 Log.w，DiagnosticLog 也没有事件 → 离线取证不了。

**根因**：`OnlineGalleryClient.downloadWorkImages` 旧 catch 块：
```java
} catch (Exception e) {
    mainHandler.post(() -> callback.onError(e));  // ← 静默失败
}
```
完全没有 Log.w 也没有 DiagnosticLog.write，**任何失败都丢根因**。HTTP 错误分支也只是 `throw new Exception(...)` 没留证。

**修复**（Android 0.8.54/165）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `OnlineGalleryClient.java:1279-1360` `downloadWorkImages` | 加 `final String[] currentFileName = { null };` 追踪循环中文件名 |
| 2 | 同上 HTTP code != 200 分支 | 加 `Log.w(TAG, "downloadWorkImages HTTP " + httpCode + " | " + workId + "/" + fileName);` + `DiagnosticLog.write(context, "download_work_http_error", workId + "/" + fileName + " \| HTTP " + httpCode);` 双写 |
| 3 | 同上 catch 总出口 | 加 `Log.w(TAG, "downloadWorkImages failed: ...", e)`（含堆栈）+ `DiagnosticLog.write(context, "download_work_failed", workId + "/" + fname + " \| " + e.getClass().getSimpleName() + ": " + e.getMessage());` 双写 |
| 4 | `tests/test_android_online_gallery_client.py` | 加 A7 闸门 + 修 extract_func_body signature 错配（用完整签名避免抓到 wrapper）|
| 5 | `android/app/build.gradle.kts:13-14` | `versionCode 164 → 165`, `versionName 0.8.53 → 0.8.54` |
| 6 | `CHANGELOG.md` 顶部 | 加 DSH-099 条目 |

**证据**：
- **闸门验证纪律**：HEAD 旧版（DSH-099 没改）跑 `test_android_online_gallery_client.py` = **A7 FAIL**（`Log.w(TAG 处数=0 DiagnosticLog 处数=0 currentFileName=False`，整体 6/7 FAIL）；工作区新版 = **A7 PASS**，整体 **7/7 PASS**
- **抓取函数探针验证**：修闸门前 `extract_func_body(src, "public void downloadWorkImages(")` 抓到 wrapper（496 字符，3 参 Callback 版，无 Log.w）；修后用完整签名 `public void downloadWorkImages(String workId, List<String> fileNames, DownloadProgressCallback callback)` 抓到真身（4175 字符，含 2 处 Log.w(TAG) + 2 处 DiagnosticLog.write）

**回归要求**：
- K60 装 0.8.54 后触发"下载图片失败"，DiagnosticLog 落盘 `/Android/data/com.zwm.gallery/files/diagnostic.log`（512KB rotate）
- 日志里有 `event=download_work_failed` 或 `event=download_work_http_error`，含 workId / fileName / HTTP code / 堆栈
- `tests/test_android_online_gallery_client.py` **7/7 PASS**（含 A7）
- 任何后续新增的 catch 块必须同样加 DiagnosticLog 双写（铁律）；新加的函数也必须用完整签名才能被闸门抓到（避免 wrapper 误匹配）

**配套独立闸门**（`tests/test_android_online_gallery_client.py` 7 项，DSH-099 增 A7）：
- A1~A6 同 DSH-098
- **A7** downloadWorkImages 错误路径 Log.w(TAG) ≥ 2 + DiagnosticLog ≥ 2 + currentFileName 变量存在 + 0 处裸字符串

## DSH-099 Android + iOS 在线相册错误路径补双写（Android 0.8.54/165 + iOS 0.8.38/110，2026-09-24）

> **用户反馈**（两轮）：
> ① Android K60 弹"下载图片失败..data/user..." → 定位 `MainActivity.java:4444` toast → `OnlineGalleryClient.downloadWorkImages` catch 块只 `callback.onError`，**完全没有 Log.w + DiagnosticLog 双写**，违背"debug 回传"硬约束。
> ② 「苹果和电脑版你会同步做的对吧」→ iOS 端 `OnlineGalleryClient.loadImage` / `fetchCategories` / `fetchWorks` 三个 catch 块也是静默失败，加上 `loadImage` 磁盘缓存 `try? data.write` 吞错，**四类静默失败一次扫干净**。
>
> **DSH-098 漏的另一半**：DSH-098 修了 `downloadThumb`（缩略图）+ `loadFullImage` + `loadThumbnail` catch 的 Log.w(TAG) + DiagnosticLog 双写，但**漏了 `downloadWorkImages`（原图下载）catch**——属于"修了一半"。iOS 端原本就没双写，三个 catch 块全静默，与 iOS 端"在线分享"路径（`loadImage` → UIActivityViewController）直接相关。

**现象**：
- Android：用户点平台按钮下载原图 → 弹 "下载图片失败: XXX, 文案已在剪贴板" → logcat 无 Log.w / DiagnosticLog 无事件 → 离线取证不了
- iOS：用户点平台按钮分享在线作品 → `loadImage(isThumbnail: false)` 拿不到图 → `UIActivityViewController` 拿到 nil → 静默失败 → Console 没 NSLog → 复现不到根因

**根因**：
- Android `OnlineGalleryClient.downloadWorkImages` 旧 catch 块：`mainHandler.post(() -> callback.onError(e))` 静默
- iOS `OnlineGalleryClient.loadImage` line 435 guard else：`DispatchQueue.main.async { completion(nil) }` 静默；line 441 `try? data.write` 吞错
- iOS `fetchCategories` line 351 / `fetchWorks` line 401 catch：只 `.failure(error)`，无 NSLog

**修复**（Android 0.8.54/165 + iOS 0.8.38/110）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `OnlineGalleryClient.java:1279-1360` Android `downloadWorkImages` | 加 `final String[] currentFileName = { null };` 追踪循环中文件名 + HTTP 错误分支 1 处 Log.w(TAG) + 1 处 DiagnosticLog.write + 总 catch 出口 1 处 Log.w(TAG, ..., e) + 1 处 DiagnosticLog.write |
| 2 | `OnlineGalleryClient.swift:434-444` iOS `loadImage` | 失败分支加 `NSLog("[OnlineGalleryClient] loadImage failed: path=%@ isThumbnail=%d", path, isThumbnail ? 1 : 0)` + 磁盘缓存 `try? data.write` 改 `do { try data.write } catch { NSLog("[OnlineGalleryClient] loadImage disk write failed: path=%@ error=%@", path, error.localizedDescription) }` |
| 3 | `OnlineGalleryClient.swift:351` iOS `fetchCategories` catch | 加 `NSLog("[OnlineGalleryClient] fetchCategories failed: %@", error.localizedDescription)` |
| 4 | `OnlineGalleryClient.swift:401` iOS `fetchWorks` catch | 加 `NSLog("[OnlineGalleryClient] fetchWorks failed category=%@ query=%@ error=%@", category ?? "<nil>", query ?? "<nil>", error.localizedDescription)` |
| 5 | `tests/test_android_online_gallery_client.py` | 加 A7 闸门 + 修 extract_func_body signature 错配 |
| 6 | `tests/test_ios_online_gallery_client.py` | 新建 4 项 iOS 闸门（B1~B4）|
| 7 | `android/app/build.gradle.kts:13-14` | `versionCode 164 → 165`, `versionName 0.8.53 → 0.8.54` |
| 8 | `ios/project.yml:31-32` | `CURRENT_PROJECT_VERSION 109 → 110`, `MARKETING_VERSION 0.8.37 → 0.8.38` |
| 9 | `CHANGELOG.md` 顶部 | 加 DSH-099 Android + iOS 条目 |

**证据**：
- **Android 闸门**：HEAD 旧版 `test_android_online_gallery_client.py` = **A7 FAIL**（整体 6/7 FAIL）；工作区 = **7/7 PASS**
- **iOS 闸门**：HEAD 旧版 `test_ios_online_gallery_client.py` = **4/4 FAIL**（B1 NSLog=0, B2 含 try?, B3 NSLog=0, B4 NSLog=0）；工作区 = **4/4 PASS**
- **Windows 端不动**：`grep -r "downloadWorkImages\|fetchFullImage\|api/online/image" tools/device-share-hub/windows-native/` = **0 命中**，MEMORY 印证"电脑端不参加在线相册 = 架构级断点"

**回归要求**：
- Android K60 装 0.8.54 后触发"下载图片失败"，DiagnosticLog 落盘 `/Android/data/com.zwm.gallery/files/diagnostic.log`，含 `event=download_work_failed` 或 `event=download_work_http_error`，含 workId / fileName / HTTP code / 堆栈
- iOS 0.8.38 装机后触发"图片加载失败"，Console.app / XCode device log 可看 `[OnlineGalleryClient] loadImage failed path=...` / `loadImage disk write failed ...` / `fetchCategories failed ...` / `fetchWorks failed category=... query=...` 四类错误日志
- `tests/test_android_online_gallery_client.py` **7/7 PASS**（含 A7）+ `tests/test_ios_online_gallery_client.py` **4/4 PASS**
- 任何后续新增的 catch 块必须同样加 Log.w/NSLog + DiagnosticLog/Console 双写（铁律）；新加的函数也必须用完整签名才能被闸门抓到（避免 wrapper 误匹配）

**配套独立闸门**：
- Android `tests/test_android_online_gallery_client.py` 7 项（A1~A7），DSH-099 增 A7
- iOS `tests/test_ios_online_gallery_client.py` 4 项（B1~B4），DSH-099 新增
- 跨端对等 `tests/parity_ios_android.py` 27 项（保持，未扩）

## DSH-098 Android 在线相册"获取在线相册失败"BUG（Android 0.8.53/164，2026-09-24）

> **用户口径**（2026-09-24 现场）：
> ① 「你那个看到 K60 日志吗，他获取在线相册失败好像是内存写入的 BUG」
> ② 「以后所有开发 你要注意我有很多类型设备，这些你也知道我目前多少账号多少设备，注意就行，开发，兼容，各种 BUG」（多设备/多账号兼容硬约束）
> ③ 「还有所有的开发都要有 deBUG 回传的对吧」（每个错误路径必须 Log.w + DiagnosticLog 双写）

**现象**：K60 上点在线相册模式，等几秒后报"获取在线相册失败"，部分缩略图区域空白。Logcat 没 FATAL，但 `OnlineGalleryClient` 静默失败。

**根因**：`OnlineGalleryClient.downloadThumb` 旧实现（`HEAD` 仍是 DSH-097 状态）用 `ByteArrayOutputStream` 累积 byte[]：
```java
ByteArrayOutputStream bos = new ByteArrayOutputStream();
byte[] buf = new byte[2048];
int n;
while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
byte[] imgBytes = bos.toByteArray();
bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
```
THUMB_MAX_BYTES = 512KB × N 并发（在线相册首屏 30 个缩略图同时请求）→ 内存峰值 ≈ 15MB+ ；`BitmapFactory.decodeByteArray` 还要再开一份解码内存；不同设备（K60 / 红米 / OPPO / 各种分辨率）内存压力阈值不同，K60（中端 SoC，6GB RAM，后台多 app）首当其冲。同时全文件 `Log.w("OnlineGalleryClient", ...)` 裸字符串 4 处 + DiagnosticLog.write **0 处**，失败只能看 logcat（系统日志会被滚动覆盖，无法离线取证）。

**修复**（Android 0.8.53/164）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `OnlineGalleryClient.java:829-905` `downloadThumb` | `ByteArrayOutputStream` → 流式写盘（InputStream → 8KB 缓冲 → `FileOutputStream(tempFile)` 边读边写）+ `totalBytes > THUMB_MAX_BYTES` 拒绝 + `tempFile.renameTo(diskFile)` 原子落盘 |
| 2 | `OnlineGalleryClient.java:775-803` `loadThumbnail` caller | 改用 `decodeThumbFile(diskFile)` 读盘解码（不再持有大 byte[]）|
| 3 | `OnlineGalleryClient.java:813-892` `downloadThumb` 错误分支 | **6 处** Log.w(TAG) + **6 处** DiagnosticLog.write（http_error / fallback / oversize / rename_failed / empty + 总 catch 出口，含堆栈）|
| 4 | `OnlineGalleryClient.java:1029` `loadFullImage` catch | Log.w("OnlineGalleryClient",...) → `Log.w(TAG, ..., e)` + `DiagnosticLog.write(context, "full_image_failed", ...)` |
| 5 | `OnlineGalleryClient.java` 类顶部 | 加 `private static final String TAG = "OnlineGalleryClient";` |
| 6 | `android/app/build.gradle.kts:13-14` | `versionCode 163 → 164`, `versionName 0.8.52 → 0.8.53` |
| 7 | `tests/test_android_online_gallery_client.py` | 新增 6 项 Android 硬判据闸门（A1~A6）|
| 8 | `CHANGELOG.md` 顶部 | 加 DSH-098 条目 |

**证据**：
- **闸门验证纪律**：HEAD 旧版跑 `test_android_online_gallery_client.py` = **6/6 FAIL**（downloadThumb 函数 0 字符，A4 裸字符串残留 True，A6 全 0）；工作区新版 = **6/6 PASS**（exit=0）
- **Git HEAD 旧版摸底**（`git show HEAD:./.../OnlineGalleryClient.java`）：
  - `ByteArrayOutputStream` count = **9**（旧累积）
  - `DiagnosticLog.write` count = **0**（完全没用）
  - `Log.w("OnlineGalleryClient"` count = **4**（裸字符串）
  - `Log.w(TAG` count = **0**（TAG 常量没声明）
- **工作区新版摸底**：
  - `downloadThumb` 函数体 4170 字符 / `loadFullImage` 3417 字符
  - `downloadThumb` 内 `ByteArrayOutputStream` = **0** / `DiagnosticLog.write` = **6** / `Log.w(TAG` = **6** / `Log.w("OnlineGalleryClient"` = **0**
  - 全文件 `Log.w(TAG` = **9** / `DiagnosticLog.write` = **10**

**回归要求**：
- K60 / 红米 0.8.47 / OPPO / 各种分辨率设备都能加载在线相册缩略图（不再 OOM / GC 抖动）
- 服务端 `X-Thumb=fallback`（缩略图链路故障，降级发原图）时被 THUMB_MAX_BYTES 拒绝不爆内存
- `DiagnosticLog.write` 在 `/Android/data/com.zwm.gallery/files/diagnostic.log` 持续落盘（512KB rotate）
- 任何错误路径都同时有 Logcat（堆栈）+ DiagnosticLog（持久化）两条线
- `tests/test_android_online_gallery_client.py` **6/6 PASS**（CI 闸门）

**配套独立闸门**（`tests/test_android_online_gallery_client.py`，6 项）：
- A1 downloadThumb 流式写盘（去 BAOS + FileOutputStream(tempFile) + byte[8192] + renameTo）
- A2 downloadThumb THUMB_MAX_BYTES 大小阈值
- A3 downloadThumb 错误路径 Log.w(TAG) ≥ 6 + DiagnosticLog ≥ 6 + 裸字符串 = 0
- A4 loadFullImage Log.w(TAG) ≥ 1 + DiagnosticLog ≥ 1 + 裸字符串 = 0
- A5 类级 TAG 常量声明（必须字符串 = "OnlineGalleryClient"）
- A6 全文件 Log.w(TAG) ≥ 8 + DiagnosticLog ≥ 8 双写覆盖率

## DSH-097 Android 同步发版（Android 0.8.52/163，2026-09-24 同步 iOS 修复）

> **用户口径**：「安卓苹果一起升级一起改」「要是本地文件分发也需要那就一起」。
> **同步目标**：与 iOS `folderItem` 双模式可见 + `openFiles` 模式分发对齐；Android 端 `modeButton`（图标 `ic_file_folder`，`MainActivity.java:417`）原本在 `isOnlineMode` 时直接 `setVisibility(View.GONE)`，是同一处破缺对称的入口砍掉。

**现象**：Android 主界面顶部文件夹按钮在在线模式不可见（破缺 1:1 对称），iOS `folderItem` 也有同样问题（DSH-097 iOS 已修）。

**根因**：两端都按"在线模式不需要本地文件入口"的过度简化——但用户的真实需求是"本地/在线都要有这个界面"（modeButton 是统一入口，行为按模式分发）。

**修复**（Android 0.8.52/163）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `MainActivity.java:417-425` modeButton | 删 `if (isOnlineMode) { modeButton.setVisibility(View.GONE); }` |
| 2 | `MainActivity.java:418-428` modeButton.setOnClickListener | 加 `if (isOnlineMode) { showTrash(); }` 首行分支（与 iOS `openFiles` 模式分流对齐）|

**无需改动**（已对齐 iOS）：
- `MainActivity.java:2510-2518` `iconButton` 工厂函数（42dp + 浅绿 RGB(226,244,236) + 深绿 RGB(15,135,88) + cornerRadius 21）
- `MainActivity.java:2520-2524` `iconParams` 函数（42dp 宽高 + 3dp left margin）
- 顶部按钮顺序（folder → title → transfer → sourceModeButton → leftModeButton → rightModeButton → settings）

**证据**：
- 跑：`parity_ios_android.py`：**27/27 PASS**（保持，C22/C23/C24 是 iOS-only 源码判据）
- Android 编译：CI `gradle build` 唯一闸门

**回归要求**（**`adb` + uiautomator dump 可验证**）：
1. 红米安装 0.8.52/163 → 在线模式 → 顶部左文件夹按钮**可见**（不再是 GONE）
2. 在线模式点文件夹 → 跳到 `showTrash` 屏幕（_已发送1次 + _垃圾作品）
3. 本地模式点文件夹 → 仍 toggle fileMode（文件浏览 ↔ 作品分享）

---

## DSH-097 iOS 主界面 toolbar 38pt 半圆浅绿 + folderItem 双模式分发（iOS 0.8.37/109，2026-09-24 修复）

> **用户反馈**：
> - 「顶部最左侧好像还差个文件夹按钮」→ iOS `folderItem` 在线模式 `isHidden` 把入口砍了，对称性破缺
> - 「无论本地还是在线都要有这个 界面啊这些个按钮，分类具体看本地现在已有的」→ 强调本地/在线 1:1 对称
> - 「为何你画的顶部要做作品集和在线相册几个」→ 用户否决了我凭空加的"作品集/在线相册"标题（其实两端 `navigationItem.titleView = nil` / `headingText.setText("")` 都是空的）
>
> **我的反思**：① 之前 `toolbarButton` 34pt + `cornerRadius = 11` + `tintColor.withAlphaComponent(0.11)` 颜色不固定（深浅随系统 tint 变），跟 Android `ImageButton` 42dp 半圆浅绿 `RGB(226,244,236)` 视觉差异大；② `folderItem` 直接 `isHidden = isOnlineMode` 把在线模式的入口砍了；③ DSH-096 给重置/删除按钮加了橙/红高对比色，跟 Android 行动行浅灰不统一。三件事合并成 DSH-097。

**现象**：iOS 主界面 toolbarButton 视觉与 Android ImageButton 不一致（34pt vs 42dp、圆角 11 vs 19pt、颜色 tintColor.0.11 跟主题色变 vs 固定 RGB(226,244,236)）；在线模式点击不到 folderItem（直接隐藏）；重置/删除按钮颜色跟 Android 行动行不一样（橙红 vs 浅灰）；11 按钮居中问题未做（DSH-096 的 `navTitleLabel` + `modeSegmented` + `PlatformFlowView` 居中算法已回退）。

**根因**：toolbarButton 与 Android ImageButton 是两份独立设计，没显式对齐（注释"对齐"但不写死 RGB 值）。folderItem 在线模式按"在线相册看电脑端成品列表就够"的过度简化。PlatformFlowView 的居中算法被 DSH-095 v2 一并回退（用户不要 iOS 分段控件）。

**修复**（iOS 0.8.37/109，**Android 同步发版见 #313~#316**）：

| # | 位置 | 改动 |
|---|---|---|
| 1 | `ContentView.swift:169-184` toolbarButton | 38pt + cornerRadius 19 + RGB(226,244,236) 浅绿背景 + RGB(15,135,88) 深绿图标 |
| 2 | `ContentView.swift:257-273` toolbarItem | 同 #1 贴 |
| 3 | `ContentView.swift:164-168` updateFolderItemVisibility | `isHidden = isOnlineMode` → `isHidden = false` + accessibilityLabel 模式分流 |
| 4 | `ContentView.swift:1212-1218` openFiles | 加 `if isOnlineMode { openTrash(); return }`（在线模式复用 DSH-095 统一入口）|
| 5 | `ContentView.swift:2140-2157` configureResetButton / DeleteButton | 撤销 DSH-096 橙红高对比 → 浅灰 RGB(0.32,0.36,0.34) + RGB(0.93,0.94,0.93) |
| 6 | `ContentView.swift:2161-2171` configureCopyPathButton | 保持浅灰（与 #5 统一）|
| 7 | `ContentView.swift:1665-1730` PlatformFlowView.measure | 重写居中算法：lineRow tuple + lineXOffset 末行结算 |

**证据**：
- 闸门 `tests/parity_ios_android.py` 由 24 项扩到 **27 项**（新增 C22 + C23 + C24）：
  - **C22**：iOS `widthAnchor.constraint(equalToConstant: 38)` + `layer.cornerRadius = 19` + `red: 226/255, green: 244/255, blue: 236/255` 浅绿背景 + `red: 15/255, green: 135/255, blue: 88/255` 深绿图标，且浅绿字符串出现次数 ≥ 2（=`toolbarButton` + `toolbarItem` 都改）
  - **C23**：`lineXOffset` + `lineRow` + `xInLine` 三变量齐全 + `lineXOffset + item.xInLine` 应用 + 旧式左对齐 `view.frame = CGRect(x: x,` 已删
  - **C24**：`folderItem?.customView?.isHidden = false` + 旧 `isHidden = isOnlineMode` 已删 + `openFiles` 内 `if isOnlineMode {\n            openTrash()\n            return` 模式分发 + `LibraryFilesViewController(rootURL: root, currentURL: root)` 入口保留
- 跑：**27/27 PASS**（24 旧 + 3 新）
- iOS 编译：CI `xcodebuild` 唯一闸门

**回归要求**（**用户需手动点，pymobiledevice3 `dvt` 无 tap**）：
1. iPhone 打开相册 App（0.8.37/109 待装）→ 本地模式 → 验：顶部左文件夹按钮可见 / 顶右 5 按钮 38pt 半圆浅绿 / 切换按钮本地浅绿+手机 / 11 按钮居中 / 重置/删除/复制路径三件套浅灰
2. 切到在线模式 → 验：文件夹按钮**仍可见**（不再隐藏）/ 切换按钮变浅蓝+电脑 / 顶右 5 按钮全浅蓝 / 11 按钮仍居中
3. 在线模式点文件夹 → 应跳到 `TrashView`（DSH-095 统一入口：_已发送1次 + _垃圾作品）
4. 本地模式点文件夹 → 应跳到 `LibraryFilesViewController`（本地文件浏览）

**Android 同步发版**：见 #313~#316（modeButton 双模式可见 + 顶部按钮顺序对齐 iOS + 38dp 半圆浅绿统一）

---

## DSH-095 v2 iOS HIG native 重做（iOS 0.8.36/108，2026-09-23 修复）

> **用户吐槽**：「iOS 的回收站太不符合直觉了你就不能参照安卓开发吗」
> **我的反思**：DSH-095 v1 把 segmented + 「💻 打开电脑端回收站」按钮塞进
> `tableHeaderView` + `UIStackView`（高度 56→108），那是 **Android inline header 的硬搬**，
> iOS HIG 完全不这么做。**iOS 标准是 `navigationItem.titleView`（标题即控制）+
> `rightBarButtonItems`（右上按钮）**。

**现象**：iOS TrashView 屏顶部 108 高度的 inline header（segmented + 蓝底长按钮"💻 打开电脑端回收站（_已发送1次 + _垃圾作品）"），滚动时随 table 一起滚，**完全不是 iOS 直觉**（iOS 用户期望标题栏固定 + segmented 在标题正中 + 按钮在右上）。

**根因**：v1 走的是「tableHeaderView 装 UIStackView」方案，是直接照搬 Android 的 `MainActivity:909-915` `renderWorksCards 顶部插入 buildOnlineRecycleEntry()`。Android 的 inline header 在 `LinearLayout` 里跑得自然，但 iOS 的 `UITableViewController.tableHeaderView` 是**滚动头部**（随 cell 一起滚）+ **强制 108 高度**（卡顿、滚动跳变）。

**修复**（iOS 0.8.36/108，**Android 不动**）：
- `viewDidLoad`：
  - 删 `tableHeaderView = UIView() + UIStackView(segmented + btn)`
  - `navigationItem.titleView = segmented`（标题栏正中，iOS HIG「标题即控制」标准模式）
  - `navigationItem.rightBarButtonItems = [「💻 电脑回收站」, 「清空」]`（从右到左：电脑回收站最右、清空其左）
- `viewDidLayoutSubviews`：删 108 高度的 frame 计算（不再有自定义 header）
- `render()`：`navigationItem.rightBarButtonItems` 全遍历按 `totalCount > 0` 控制可用性（v1 只控单 button，v2 两个按钮一起判）
- 删 `makeOnlineRecycleEntryButton()`（蓝底浅蓝边、圆角 14 的 UIButton，v1 inline header 专用；v2 用 `UIBarButtonItem` 取代）
- 按钮文案简化：**「💻 打开电脑端回收站（_已发送1次 + _垃圾作品）」→「💻 电脑回收站」**（标题栏不挤）
- accessibilityLabel 保留完整描述（VoiceOver 友好）

**证据**：
- 闸门 `tests/parity_ios_android.py` 由 22 项扩到 **24 项**（新增 C18 + A5）：
  - **C18**：从 `TrashView.swift` 全文判 `navigationItem.titleView = segmented` + `navigationItem.rightBarButtonItems` 含 `openOnlineRecycle`，且 `tableHeaderView = header` / `let header = UIView()` 不再出现
  - **A5**：复跑 A4 判据（再抽 `rightModeButton.setOnClickListener(v -> {` lambda 体），证明 v2 没动 Android 路径
- 跑：**24/24 PASS**；`verify-work-card-ui.mjs`：**21/21**
- iOS 编译：CI `xcodebuild` 唯一闸门

**回归要求**（**用户需手动点，pymobiledevice3 `dvt` 无 tap**）：
1. iPhone 打开相册 App（0.8.36/108 已装）→ 在线模式 → 点回收站图标（顶部 5 个图标第 4 个）
2. 期望看到：**标题栏正中 = segmented（已删除 / 已标记垃圾）**，**右上 = 「💻 电脑回收站」+「清空」** 两个按钮（从右到左）
3. 点「💻 电脑回收站」→ 跳到电脑端回收站（双 Tab `_已发送1次` + `_垃圾作品`）
4. 退出 → 切本地模式 → 再点回收站 → **行为不变**（顶部 segmented + 右上两个按钮，`enteredTrashFromOnline` 不影响 navigationItem）

## DSH-095 回收站统一入口：本地/在线模式都进同一屏（Android 0.8.51/162 + iOS 0.8.35/107）

> **用户口径（原话）**：「手机端我再说一次哈，回收站是本地相册和在线相册共用的，
> 顶多再回收站里面你显示是在线还是本地，就现在的本地相册界面点击回收站那个界面可以，
> **在线相册点击居然没进去这个界面**」

**现象**：右上「回收站」按钮在线模式下点开进了**另一个屏幕**（电脑端 OnlineRecycle，
`_已发送1次` + `_垃圾作品` 双 Tab），而不是和本地模式一样的手机本地回收站 + 标记删除列表。
**用户期望**：两端都进同一屏幕，里面可以显示来源（在线 / 本地），电脑端回收站另放按钮。

**根因**：
- Android `MainActivity.java:471` `rightModeButton` 的 OnClickListener 在 `else if (showingTrash)` 之后
  还有 `else if (isOnlineMode) { toast("在线回收站"); showOnlineRecycle("sent"); }` ⇒ 在线模式分流。
- iOS `ContentView.swift:1194` `openTrash` 同样按 `isOnlineMode` 分流到两个不同的 `UIViewController`。
- 反向入口（本地回收站 → 电脑端回收站）本来就有 `recycleLocalTrashEntry` 按钮，
  但「电脑端 → 本地回收站」是新的。

**修复**（合并 commit 同 DSH-094 + DSH-095）：
1. **Android rightModeButton**（`MainActivity.java:471-477`）：删 `else if (isOnlineMode)` 分支，
   统一 `showTrash()`（同本地模式那条路径）；第 800-820 行 `showTrash` 已处理
   `if (isOnlineMode) { enteredTrashFromOnline = true; }`，第 3187 行 `showOnlineRecycle` 会把它复位。
2. **Android renderWorksCards 顶部**（`MainActivity.java:909-915`）：在 `worksContainer.removeAllViews()`
   之后插入 `buildOnlineRecycleEntry()` —— 一个浅蓝底按钮「💻 打开电脑端回收站
   （_已发送1次 + _垃圾作品）」。仅在 `showingTrash && enteredTrashFromOnline` 时插入。
3. **Android buildOnlineRecycleEntry**（`MainActivity.java:3305-3322`，与 recycleLocalTrashEntry 镜像）。
4. **iOS openTrash**（`ContentView.swift:1194-1199`）：删 `if isOnlineMode { ... OnlineRecycleViewController ... } else { ... TrashViewController ... }` 分支，统一 push `TrashViewController`。
5. **iOS TrashView**（`TrashView.swift`）：header 从「单 segmented」改成 `UIStackView` 装 segmented + 按钮；
   `viewDidLayoutSubviews` 高度 56 → 108；新增 `makeOnlineRecycleEntryButton`（蓝底浅蓝边、圆角 14、
   "💻 打开电脑端回收站（_已发送1次 + _垃圾作品）"）+ `@objc openOnlineRecycle` push `OnlineRecycleViewController`。

**证据**：
- 闸门 `tests/parity_ios_android.py` 由 20 项扩到 **22 项**（新增 C17 + A4）：
  - **C17**：从 `ContentView.swift` 抽 `openTrash` 函数体（regex `@objc private func openTrash() { ... @objc`），判据
    `not ("if isOnlineMode" in body) and not ("OnlineRecycleViewController" in body) and "TrashViewController" in body`。
    改前判据会 FAIL（lambda 内确实含两个字符串）→ 改后 PASS。
  - **A4**：从 `MainActivity.java` 抽 `rightModeButton.setOnClickListener(v -> { ... });` 的 lambda 体，判据
    `not ("isOnlineMode" in body) and not ("showOnlineRecycle(" in body) and "showTrash()" in body`。
    改前 FAIL → 改后 PASS。
- `parity_ios_android.py`：**22/22 通过**；`verify-work-card-ui.mjs`：**21/21 通过**。
- ⚠️ Android 用 JDK22 `javac` 全源集编译 **exit=0**；iOS 仍只能靠 CI 的 `xcodebuild`。

**回归要求**：
1. **在线模式**下点右上「回收站」图标，应进入**手机本地回收站屏幕**
   （与本地模式点回收站看到的是同一屏）；屏幕上若有条目带 `enteredTrashFromOnline` 标记的
   在线作品（`OnlineWorkLifecycle` 标记删除），仍按本地回收站的样式渲染。
2. 顶部出现「💻 打开电脑端回收站（_已发送1次 + _垃圾作品）」按钮；点它跳原
   `OnlineRecycleViewController`（双 Tab + 服务器侧列表）—— 即 **原 OnlineRecycle 仍可达**。
3. **本地模式**下点右上「回收站」：行为不变（没有顶部电脑端按钮，因为 `enteredTrashFromOnline=false`）。
4. iOS `TrashView` 顶部除了 segmented（已删除 / 已标记垃圾）外，还应有「💻 打开电脑端回收站」按钮。
5. 两端的「回收站」按钮点击后的屏幕**完全相同**（除了「电脑端回收站」入口只在从在线模式进入时出现）。

## DSH-094 老三家文案前端化：版本改名 + 重排 + 按钮完整字数（Android 0.8.51/162 + iOS 0.8.35/107）

> **用户口径（原话）**：「他们是一样的文案是平级的，只是前面三个版本是我规定写在文档里面在前……
> 手机那边就是跟随 `文案.txt` 识别」「按钮要完整字数，不要限制 4 字，然后按钮自适应换行」。

**现象**：手机端按钮顺序一直由「客户端自己排」决定（Android `enrichPlatformSuite` 把抖音摘出追加末尾 +
`result.sort(getButtonRank)`），不是由文案决定；5 字的「抖音无营销」被两端截成「抖音无营」；
按钮被强制 `setMaxLines(1)`，长名字放不下就切。

**根因**：
- 守卫真源 `copy_formatter.py` 的 `VERSION_FAMILY` 缺三个新键（旧键保留做向后兼容）。
- `vname_clean` 在两端分别 `substring(0, 4)` / `String(prefix(4))` 截断。
- Android `styleNeumorphicButton` 里 `setMaxLines(1)` + 按钮高度固定 `dp(36)`。
- Android `enrichPlatformSuite` 多版本分支的「抖音摘出 + sort」是历史排版逻辑，反向作用到了「让文案说了算」的口径上。

**修复**：
1. **版本改名（老三家）**：`原生种草 → 红书种草`、`决策矩阵 → 红书大纲`、`抖音避坑 → 抖音无营销`。
   `copy_formatter.py` 的 `VERSION_FAMILY` 增 3 键（旧键保留向后兼容）；两处 `vname_clean` 删 `[:4]` 截断。
2. **存量 386 套 `文案.txt` 改名 + 重排**：把这三块按「红书种草 → 红书大纲 → 抖音无营销」移到**最前**，
   其余 8 版保持原相对顺序。**11 版平级、不做 rank**。
   - ⚠️ `文案.txt` 换行风格并不统一：实测全库 490 套裸 LF / 184 套 CRLF / 1 套混合。
     按 CRLF 统一写回会污染 490 套（伪全量 diff）⇒ 改成了**逐文件探测 + 原字符串切片重组**。
   - 独立复核 4/4：前三位正确 386/386、换行零变化、前后缀零变化、块正文零丢失。
3. **两端去 4 字截断**：`friendlyLabelForMarker` 里的 `substring(0, 4)` / `prefix(4)` 删除。
   最长版本名 5 字「抖音无营销」，原本两端都会砍成「抖音无营」。
4. **Android 按钮渲染**：`styleNeumorphicButton` 的 `setMaxLines(1)` → `2`、纵向 padding 0 → dp(6)、
   平台行按钮高度 `dp(36)` → `WRAP_CONTENT`（单行仍由 `setMinHeight(dp(36))` 兜底，外观零变化）。
5. **Android 顺序对齐 iOS（关键）**：`enrichPlatformSuite` 原本把「抖音」那一版摘出来追加到末尾，
   末尾还有 `result.sort(getButtonRank)` ⇒ 已排在文案最前的抖音版会被甩到整行最后。
   改为多版本时**原样返回**解析结果（`return new ArrayList<>(rawPlatforms);`）。
   iOS 本来就没有这段（`if !multiItems.isEmpty { return multiItems }`）⇒ 这次是**安卓向 iOS 对齐**。

**证据**：
- 闸门 `tests/parity_ios_android.py` 由 17 项扩到 20 项（新增 C14 不截断 / C15 顺序=文案顺序 /
  C16 允许折行且 iOS 行高同源）。`scripts/verify-work-card-ui.mjs` 修到当前结构（原本断言的
  `platformRow1` 自 DSH-091 C3 起就不存在，长期为红）并扩到 **21 项**。
- **⚠️ 这两条守卫此前从未被 CI 调用** —— 等于没有守卫。已一并接进 `windows-portable` 的步骤里
  （`.github/workflows/device-share-hub.yml` 的两个新 step）。
- 本地校验：Android `javac` 全源集编译 exit=0 / 127 个 class；`PlatformCopyParserTest` 18/18 通过
  （含新增的 2 条）；`parity_ios_android.py` 22/22（含 DSH-095 的 C17/A4）；
  `verify-work-card-ui.mjs` 21 项；另 4 个 `verify-*.mjs` 全 PASS；
  `test_online_gallery_service` 27/27 通过。
- iOS 端无 Xcode 工具链，**语法仍只能靠 CI 的 `xcodebuild`**。

**回归要求**：
1. 任意一个有完整 11 版 `文案.txt` 的本地作品：按钮顺序应是文案块先后顺序（红书种草/红书大纲/抖音无营销打头）。
2. 「抖音无营销」按钮不被截成「抖音无营」。
3. 5 字 + 2 行按钮在两端的实际渲染高度一致（iOS `platformRow.rowHeight` 用 `WorkCell.platformRowHeight`）。
4. 切到在线相册（5 秒 TTL）后顺序也跟着改。

> **用户追问**：「安卓有时候会不会有不合理的」⇒ 是的。**别把安卓当基准就无脑照抄，
> 基准自己也会错。** 本条目前半是「iOS 对齐安卓」，后半是「修安卓自己」。

### 一、iOS 对齐安卓（0.8.33 / 105 → 0.8.34 / 106）

**现象（用户原话）**：「所有的这个平台，最好的统一这个称呼啊，这些细节什么的……
很多问题一下子看不出来的你可以站在这个上帝视角看一下」

**根因（四条，全部「编译得过、跑得起来、但对不上」）**：
1. **计数口径分叉（最隐蔽的一条）**：Android `MainActivity:1070` 本地卡
   `localUsedCount = xhsShareCount + douyinShareCount`；iOS `WorkCell.configure` 直接用
   `work.shareCount`。两个字段语义不同（`shareCount` 还包含不分平台的发布），
   于是同一作品两端数字不一样，且卡片会出现「已使用 3 次 / ✓ 小红书 1 · 抖音 1」
   这种总数与明细自相矛盾的显示。**用户只会以为数据坏了**，不会想到是两端算法不同。
2. **在线卡少两行**：Android 在线卡（`MainActivity:3340`）额外拼 `记录：`（dispatchedTo）
   与 `垃圾备注：`；iOS `configureOnline` 从来没渲染过这两行。
3. **「垃圾备注」三种叫法**：Android 叫「垃圾备注：」，iOS `OnlineRecycleView:209` 与
   `TrashView:172` 都叫「备注：」。
4. **本地卡缺自动清理倒计时**：Android `deleteCountdown`（`:1463`）有
   「N 分钟后自动删除 / N 小时 M 分钟后自动删除 / 即将自动删除」，iOS 没有。
5. **C13 小红书第三槽位漏计**：iOS `PlatformCopyParser` 有 `.xhs3` 且会生成「短文精选版」
   按钮，但 `WorkLibrary:289` 只判 `.xhs / .xhs2` ⇒ 点它分享后 iOS 不计次数，
   而 Android 把 `XHS / XHS_2 / XHS_3` 全算进 `xhsShareCount` ⇒ **两端数字逐步分叉**。
   （这条是在把显示口径改成 `xhs+douyin` 之后才暴露的 —— 改显示反而暴露了写入的洞。）

**修复**：
- C9 iOS 本地卡改用 `let localUsed = work.xhsShareCount + work.douyinShareCount`，
  判定「是否使用」也用这个和（与明细行相加一致）。
- C10 iOS 在线卡补 `\n记录：` + `dispatchedTo.joined(separator: "、")`。
- C11 三处统一叫「垃圾备注：」；在线卡再补 ` · 🗑️ 垃圾样本` 前缀（与安卓 A3 同步）。
- C12 iOS 新增 `WorkCell.deleteCountdownText(deleteScheduledAtMs:)`，基于 iOS 自己的
  `deleteScheduledAtMs` 推算剩余时间，文案与 Android 逐字一致；没设清理计划返回空串，整行不追加。
- C13 `WorkLibrary` 补 `.xhs3`。

### 二、安卓自己的三处不合理（0.8.49 / 160）

| # | 问题 | 为什么不合理 |
|---|---|---|
| A1 | 在线分页**首屏 25、步长 30** | 两个数不搭：首屏 25 却每次 +30。统一 30 / +30 |
| A2 | 本地卡状态行判 `localUsedCount > 0`、**明细行判 `work.used`** | 两个条件不一样 ⇒ 会出现「未使用」下面挂着「✓ 小红书 0 · 抖音 0」+ 自动删除倒计时 |
| A3 | 「🗑️ 垃圾样本」前缀：**本地回收站有、在线卡没有** | 安卓自己两处格式不一致 |

**证据**：闸门 `tests/parity_ios_android.py` 由 9 项 → 13 项 → **17 项**（新增 C13 + A1/A2/A3）。
- 每项都先用**改动前的代码**验证过会 FAIL：`git show HEAD:<path>` 取出旧文件跑同样判据，
  A1/A2/A3 全为 False。
- ⚠️ **A3 初版判据无效**：写成「数 `🗑️ 垃圾样本` 出现次数 ≥2」，但那个 emoji 在 badge 里
  也有，改前就已经是 2 ⇒ **改前改后都 PASS，等于没有闸门**。改成查在线卡那句
  `detail.append(" · 🗑️ 垃圾样本\n垃圾备注：")` 的精确形态后，验证改前 False / 改后 True。
- `ContentView.swift` 纯 CRLF 字节级改写：CRLF 2253 → 2282，**孤立 LF 恒为 0**，花括号 +5/+5。
- `WorkLibrary.swift` 纯 CRLF：CRLF 801 → 803，孤立 LF 恒为 0。
- `MainActivity.java` / `TrashView.swift` / `OnlineRecycleView.swift` 均为纯 LF，直接 Edit。

**回归要求**：
1. 找一个「小红书 1 次 + 抖音 1 次」的本地作品：两端都应显示「已使用 2 次」，
   且明细行相加等于总数。
2. 点「短文精选版」（第三个小红书槽位）分享一次：两端的小红书计数都应 +1。
3. 在线作品若已分发过：卡片出现「记录：小红书、抖音」一行。
4. 被标记垃圾的作品：卡片/回收站都显示「🗑️ 垃圾样本 / 垃圾备注：…」。
5. 安卓在线相册首屏应为 30 套（原来 25），点「加载更多」+30。
6. 不存在「未使用」却挂着平台明细行和自动删除倒计时的卡片。

**现象（用户原话）**：「所有的这个平台，最好的统一这个称呼啊，这些细节什么的……
很多问题一下子看不出来的你可以站在这个上帝视角看一下」

**根因（四条，全部「编译得过、跑得起来、但对不上」）**：
1. **计数口径分叉（最隐蔽的一条）**：Android `MainActivity:1070` 本地卡
   `localUsedCount = xhsShareCount + douyinShareCount`；iOS `WorkCell.configure` 直接用
   `work.shareCount`。两个字段语义不同（`shareCount` 还包含不分平台的发布），
   于是同一作品两端数字不一样，且卡片会出现「已使用 3 次 / ✓ 小红书 1 · 抖音 1」
   这种总数与明细自相矛盾的显示。**用户只会以为数据坏了**，不会想到是两端算法不同。
2. **在线卡少两行**：Android 在线卡（`MainActivity:3340`）额外拼 `记录：`（dispatchedTo）
   与 `垃圾备注：`；iOS `configureOnline` 从来没渲染过这两行。
3. **「垃圾备注」三种叫法**：Android 叫「垃圾备注：」，iOS `OnlineRecycleView:209` 与
   `TrashView:172` 都叫「备注：」。
4. **本地卡缺自动清理倒计时**：Android `deleteCountdown`（`:1463`）有
   「N 分钟后自动删除 / N 小时 M 分钟后自动删除 / 即将自动删除」，iOS 没有。

**修复**（版本 0.8.33 / build 105）：
- C9 iOS 本地卡改用 `let localUsed = work.xhsShareCount + work.douyinShareCount`，
  判定「是否使用」也改用这个和（与下面明细行相加一致）。
- C10 iOS 在线卡补 `\n记录：` + `dispatchedTo.joined(separator: "、")`。
- C11 三处统一叫「垃圾备注：」（在线卡新增 + 两个回收站改名）。
- C12 iOS 新增 `WorkCell.deleteCountdownText(deleteScheduledAtMs:)`，基于 iOS 自己的
  `deleteScheduledAtMs` 推算剩余时间，文案与 Android 逐字一致；没设清理计划返回空串，
  整行不追加（不留空行）。

**证据**：闸门 `tests/parity_ios_android.py` 由 9 项扩到 **13 项**（C9~C12）：
加完先跑 = `❌ 4/13`（exit 1）→ 改完 = `✅ 13/13`（exit 0）。
`ContentView.swift` 纯 CRLF 字节级改写：CRLF 2253 → 2282，**孤立 LF 恒为 0**，花括号 **+5/+5** 平衡。
`TrashView.swift` / `OnlineRecycleView.swift` 均为**纯 LF**，直接 Edit。

**回归要求**：
1. 找一个「小红书 1 次 + 抖音 1 次」的本地作品：两端都应显示「已使用 2 次」，
   且明细行「✓ 小红书 1 · 抖音 1」相加等于总数 —— **不能出现总数 3、明细 1+1**。
2. 在线作品若已分发过：卡片应出现「记录：小红书、抖音」一行（与电脑端一致）。
3. 被标记为垃圾的作品：卡片/回收站显示的都是「垃圾备注：…」，不再有「备注：」。
4. 设置了自动清理的作品：卡片显示「N 分钟后自动删除」等，与安卓同一套说法。

## DSH-092 iOS 全量对等补齐：文案预览三出口 / 在线列表分页 / 回收站分页（2026-09-22 修复）

**现象**：用户要求「安卓比较完善，iOS 全部挨个补齐」。此前只补齐了点名的那几件，
属于「补丁式」，没有系统性对账。

**审计方法（可复用，脚本 `_audit_ui_strings.py`）**：
功能是靠按钮/菜单/对话框暴露给用户的。抽取两端「用户可点的 UI 文案」做集合差集：
- Android 侧：`setText("…")` / `setTitle("…")` / `Toast.makeText(…,"…")` /
  `setPositive|Negative|NeutralButton("…")` / `compactButton("…")`
- iOS 侧：`setTitle("…", for:)` / `UIAlertAction(title:)` / `UIAction(title:)` /
  `UIMenu(title:)` / `showToast("…")` / `accessibilityLabel`

得到 **Android 114 条 / iOS 51 条**，差集 **82 条**。逐条人工判定后：
大部分属于安卓独有模块（`TransferActivity` P2P 传送、`ShareImportActivity` 接收分享、
`UpdateChecker` 自更新 —— iOS 走 AltStore，本来就没有），真缺口 3 个，另有 1 条是判据误报。

**修复**（版本 0.8.32 / build 103）：
1. **C5 文案预览只有「完成」一个出口**（Android 是三按钮对话框）。
   - Android `MainActivity:4520` 副标题 `【版本名】 共 N 字`，`charCount = copyText.length()`
     （Java = UTF-16 码元数）。iOS 若用 Swift `String.count` 会算成**字形数**，
     带 emoji 的版本名/正文会对不上 ⇒ 必须用 `bodyText.utf16.count`。
   - 补齐「复制全文」（只写剪贴板，零副作用）与「前往使用」（复制 + 关闭 + 唤起分享）。
2. **C6 在线列表一次性全渲染**（Android 首屏 30 条 + 加载更多）。
   成品库 400+ 套时，`sizeForItemAt` 会对每条都跑一次 `parseAvailablePlatforms`，
   首屏解析 400 份文案。加 `onlinePageLimit`（首屏 30，每次 +30）+ 页脚
   `LoadMoreFooterView`；切分类、改搜索词、重新拉数据都 `resetOnlinePaging()` 回首屏，
   否则「翻到第 5 页再切分类」会直接显示第 5 页的 30 条。
3. **C7 在线回收站分页**（Android `onlineRecyclePageLimit`）。同上。
   ⚠️ **必崩坑**：多出来的「加载更多」行若允许左滑，`works[indexPath.row]` 越界崩溃 ——
   `trailingSwipeActionsConfigurationForRowAt` 已加 `guard !isLoadMoreRow(indexPath.row)`。

**证据**：闸门 `tests/parity_ios_android.py` 由 5 项扩到 **9 项**（新增 C5/C6/C7/C8）：
- 加契约后先跑 = `❌ 4/9 未达标`（exit 1）
- 改完 = `✅ 9/9 全部通过`（exit 0）
- 服务端 `python -m unittest test_online_gallery_service` 仍 **OK**（27 项 0 败）
- 字节级改写自检（ContentView.swift 纯 CRLF）：CRLF 2083 → 2250，**孤立 LF 恒为 0**，
  花括号 **+24 / +24** 平衡。`OnlineRecycleView.swift` 是**纯 LF**，可直接 Edit。

**回归要求**：
1. 长按平台按钮 → 弹窗应显示 `【版本名】 共 N 字` + 两个按钮；点「复制全文」只复制不分享；
   点「前往使用」才复制并唤起分享面板（**使用次数才 +1**）。
2. 在线相册首屏只出 30 套，底部有「加载更多作品 (已显示 30 / N 套)」，点一次 +30；
   切换分类或输入搜索词后回到首屏 30 套。
3. 在线回收站同样分页；**左滑「加载更多」那一行不能崩、也不能弹出操作菜单**。

## DSH-091 iOS 三处功能缺口：长按无文案预览 / 无顶部搜索 / 卡片缺平台使用明细（2026-09-22 修复）

**现象**（用户 2026-09-22 现场反馈，三条均为"安卓有、苹果没有"）：
1. 长按平台按钮不弹出文案预览界面。
2. 苹果顶部没有作品搜索框。
3. 卡片底部元信息只有「已使用 N 次」总数，看不到小红书 / 抖音分别发过几次。

**根因**（源码级核实，不是猜测）：
- Android 在 `MainActivity.java:1174` 与 `:3700` 给平台按钮挂了 `setOnLongClickListener`；
  iOS 全仓库只有两处长按——模式图标（配置服务器地址，`ContentView.swift:98`）与
  详情页图片多选（`WorkDetailView.swift:62`）——**平台按钮上从来没有手势**。
- iOS 全仓库无 `UISearchBar` / `searchBar`，搜索能力从未实现。
- 元信息分支不对等：Android `MainActivity.java:1082` 在本地作品已使用时追加
  `\n✓ 小红书 X · 抖音 Y`，iOS 的 `WorkCell.configure` 只拼到「已使用 N 次」为止。

**修复**（`ios/Album/ContentView.swift`，版本 0.8.31 / build 102）：
1. 新增 `CopyPreviewViewController`（只读 UITextView + 完成按钮，包在 `UINavigationController`
   里以 `pageSheet` 弹出）；`WorkCell` 新增 `onCopyPreview` 回调与
   `platformLongPress(_:)` 手势处理器。**定位用 `button.tag` 而非平台名**——MULTI 协议下
   11 个版本里有多个 `platform` 同为 `.general`，按名回查必然串到第一条。
   在线 / 本地两条分支均已接线。**零副作用**：不写剪贴板、不调 `recordUse`、不移库，
   与点按分享严格区分（点按才有副作用）。
2. 顶部插入 `UISearchBar`，声明 `UISearchBarDelegate`；`filteredWorks` 与
   `filteredOnlineWorks` 共用 `searchQuery`，按作品名 / 合集名 `localizedCaseInsensitiveContains`
   过滤；`collectionView` 顶部约束改挂到 `workSearchBar.bottomAnchor`。
3. 本地卡片 `shareCount > 0` 时追加 `\n✓ 小红书 X · 抖音 Y`，与 Android 逐字一致。
4. **C3 平台按钮换行全展开**（用户原话「还是展开吧，展开方便我去看」）：
   删除 `platformScroll`（单行横滚）与横向 `UIStackView`，新增 `PlatformFlowView`
   ——把 Android `FlowLayout.onMeasure/onLayout` 的数学原样移植
   （`horizontalSpacing = verticalSpacing = dp(8)`，行高 `dp(36)`）。
   卡片高度改由 `sizeForItemAt` 按行数动态补偿：1 行 = 212，每多一行 +44；
   本地卡多一行明细再 +14。**行数预估宽度故意收窄 1pt** ⇒ 宁可高估（留白）不低估（裁切）。
   「预估行数」与「实际渲染行数」共用同一套数学与同一组常量
   （`WorkCell.platformButtonPadding/platformSpacing/platformRowHeight`），避免两处漂移。
5. **C4 生效修复**：`WorkCell.detail` 从未设置 `numberOfLines`，UILabel 默认 1 行
   ⇒ 第 3 条追加的第二行被截断，**加了等于没加**。现已 `detail.numberOfLines = 0`。

**证据**：
- 闸门 `tests/parity_ios_android.py`（新建，源码级三端对等契约 5 项）。
  ⚠️ 初版 C3/C4 是**弱判据**：C3 只查类名字符串、C4 只查「✓ 小红书」文本存在，
  导致 C4 出现**假 PASS**（文本在但显示不出来）。已强化为：
  C3 同时查 `class PlatformFlowView` + `platformScroll` 已消失 + 行高动态
  （`platformRowCount(labels:` 与 `cardBaseHeight`）；C4 同时查附加行 + `detail.numberOfLines = 0`。
  强化后改前 `❌ 2/5`（C3 flow=False/无横滚=False/行高动态=False；C4 附加行=True/多行生效=False）
  → 改后 `✅ 5/5 全部通过`（exit 0）。
- 字节级改写自检：CRLF 计数 1842 → 1916 → **2083**，**孤立 LF 始终为 0**，
  花括号增量 **+27 / +27 平衡**（`ContentView.swift` 是纯 CRLF，禁用 Edit，
  全部走字节替换脚本 `_patch_ios_c1c4.py` / `_patch_ios_c1_wire.py` /
  `_patch_ios_c2.py` / `_patch_ios_c3.py`）。

**回归要求**：
1. 长按任一平台按钮 → 弹出该版本文案；确认**剪贴板未被写入、使用次数未增加**。
2. 顶部搜索框输入关键词 → 本地 / 在线列表都应过滤；清空后恢复。
3. 本地已使用作品 → 卡片**真的显示两行**（`✓ 小红书 X · 抖音 Y` 可见，非截断）。
4. V4.5 作品（11 个版本）→ 按钮应**换行铺开、一眼看全**，不再需要左右滑；
   卡片高度随行数变高，按钮不被裁切。
5. ⚠️ **真机证据缺失**：iOS 设备未通过 USB 连接（`pymobiledevice3` 报 Device is not connected），
   本次四项改动均无截图 / 录屏证据，需装机后人工验收。

## DSH-090 在线作品分享到小红书「一堆一样的图」：iOS 把 `[UIImage]` 直接丢给分享面板（2026-09-22 修复）

- **现象（用户原话）**：
  「苹果点击分享到小红书，分享了一堆一样图片，但是预览看到的是不同的图片」
  （同一作品预览 9 张各不相同，分享出去到小红书却变成 N 张相同的图）。

- **根因（iOS 独有，服务端与 Android 均无罪）**：
  三端「在线多图分享」的**载体**本应一致，但 iOS 在线分支是唯一的异类：
  | 端 / 出口 | 传给分享面板的东西 | 结果 |
  |---|---|---|
  | iOS **本地**相册 `WorkLibrary.prepareShare` | `selected.map { $0 as NSURL }`（文件 URL） | ✅ 正常 |
  | **Android** `MainActivity.launchOnlineShare` | `ACTION_SEND_MULTIPLE` + `putParcelableArrayListExtra(EXTRA_STREAM, uris)`（Uri） | ✅ 正常 |
  | iOS **在线** `ContentView.shareOnline` | `[UIImage]`（内存位图数组） | ❌ 串图 |

  传内存 `UIImage` 数组时，iOS 把 N 张图塞进 pasteboard，小红书/抖音的 share extension
  对多图 item 处理有缺陷，会全部读成同一张；而**预览**走的是自持的 `UIImage` 逐张加载，
  不经 pasteboard ⇒ 预览正常、分享串图，现象自洽。
  另：`showToast("✅ 原图已全部同步到手机")` 是**假文案**——代码里根本没有存相册的动作
  （全仓 0 处 `UIImageWriteToSavedPhotosAlbum` / `PHPhotoLibrary`）。

- **修复**（`ios/Album/ContentView.swift`，只改在线分享一条路径）：
  1. `downloadAllImages` 返回 `[URL]` 而非 `[UIImage]`：原图下载后写入
     `NSTemporaryDirectory()/online-share-<UUID>/`，文件名带 `序号_` 前缀保证顺序与不覆盖；
  2. 分享载体改为 `urls.map { $0 as NSURL }`，与本地相册 `prepareShare` 逐字同机制；
  3. 分享面板关闭后清理临时目录（无论是否真的分享出去）；
  4. 假 toast 文案改为「✅ 已准备 N 张原图，正在唤起分享…」。

- **证据**：
  - **服务端清白**：`GET /api/online/works` 520 件，`images` 数组**完全重复 0 件、
    basename 重复 0 件**；再逐张请求 `/api/online/image?path=…&thumb=0` 算 md5，
    抽查 5 件（6/6/9/9/10 图）**内容互不相同** ⇒ 串图不在服务端。
  - **闸门留痕**（先加契约后改实现）：`_parity_audit/_gate_before.txt`
    契约 **12 项，不一致 1 项，exit 1**：`在线多图分享·载体 AND=<FILE_URI> iOS=<UIIMAGE>`；
    修后 `_gate_after.txt` **不一致 0 项，exit 0**，`AND=<FILE_URI> iOS=<FILE_URI>`。
    前 11 项修前后均一致 ⇒ 历史修复未漂移。

- **回归要求**：
  1. 新增契约项「在线多图分享·载体」已并入 `audit_three_end_parity.py`（第 12 项），
     以后任一端再改回传内存位图，闸门必须 FAIL。
  2. iOS 改动本机无法编译（无 macOS），**必须由 CI `ios-altstore-build` 验证后**才算完成；
     装到手机后要用**多图作品（≥5 张）实际分享到小红书**复验，不能只看预览。
  3. 顺带复查：同一套图被多件作品复用（实测「溧阳天目湖」与「赞324在杭州」前 6 张 md5 完全相同），
     属素材复用，不是串图，别混为一谈。

## DSH-089 卡片元信息与按钮尺寸两端各写一套；「只有 3 个按钮」被误当成渲染缺陷（2026-09-21 定位 + 部分修复）

- **现象（用户原话）**：
  「你看在线相册那个界面，文案的小按钮只显示了 3 个。我看到好多这个作品，其实都只有 3 个按钮……
  那边的文案依旧没有补全。」「iOS 这边的按钮太小了……应该是自适应的，就和安卓那边一样那种效果。」
  「卡片的原信息，你看本地的可以，你可以按苹果的那个来。你可以在后面加上那个日期。」

- **根因（分两条，必须区分开）**：

  **① 「只有 3 个按钮」= 磁盘上的文案本身就只有 3 个版本，不是客户端渲染漏了。**
  实测全库 389 件（`GET http://127.0.0.1:45835/api/online/works`，只读）协议头分布：
  `<<<COPY_FORMAT:3>>>` **160 件** / 无协议头 **170 件** / `<<<COPY_FORMAT:MULTI>>>` **59 件**；
  按版本块数分：**0 个 308 件、3 个 25 件、11 个 56 件**。其中 138 件是旧三平台协议
  （`<<<XHS_START>>>` / `<<<XHS_2_START>>>` / `<<<DOUYIN_START>>>`）⇒ 解析出来正好
  **种草版 / 大纲方案版 / 规避营销版 = 3 个按钮**。iOS 真机截图同帧可验：
  「评371-赞1.2万-假期去哪儿玩❓…」卡面正好这三个按钮。
  服务端**原样读磁盘 txt、不过 formatter**，所以按钮数少的根因在**产线文案**，不在两端解析器。
  → 要「补全按钮」必须回产线给这些作品补 V4.5 全系 11 版本；**属写成品库，未获授权前一律不动。**

  **② 卡片元信息/按钮尺寸两端各写一套**（真正的对等缺口）：
  在线元信息 Android = `N 张图片 · 电脑 · <日期>`，iOS = `💻 电脑在线 · N 图 · 未使用`；
  本地元信息 Android = `N 张图片`，iOS = `N 图`；
  按钮 Android = `dp(36)` 高/左右 `dp(15)` 内边距，iOS = **28pt**/左右 `10` ⇒ iOS 观感明显偏小。

- **修复**：见 CHANGELOG 0.8.48（①②③ 三条）。本地/在线的措辞判据以**用户口径**为准：
  在线模式 = 操作电脑，本地模式 = 操作手机；元信息统一 iOS 样式并追加 ` · MM-dd HH:mm` 日期后缀；
  次数统一「已使用 N 次」。Android 在线元信息**两个渲染器同时改**。

- **证据**：
  - 闸门留痕：`_parity_audit/_before.txt`（契约 11 项，**不一致 3 项，exit 1**）→
    `_parity_audit/_after2.txt`（**不一致 0 项，exit 0**）。
  - 结构自检：`MainActivity.java` 括号 `(`4370/`)`4372（差 **-2**，与改动前既有缺口一致 ⇒
    本次增量 **+8/+8 成对**），`{}` 708/708、`[]` 63/63 全配平。
  - 行尾：Android 纯 LF（0 CRLF，229779→230858 B）；iOS 纯 CRLF（1813=1813，87741→89455 B）；无 BOM。

- **回归要求**：
  1. 改两端同名能力，**先往 `audit_three_end_parity.py` 加契约项并确认 FAIL**，再改实现，最后 PASS；
     不许只改实现再补闸门（那样拿不到「闸门会响」的证明）。
  2. `MainActivity.java` 的括号判据只看**增量成对**，不要用绝对值（存在既有 -2 缺口）。
  3. 判「按钮少」必须先查磁盘 `文案.txt` 的协议头，**别先怀疑客户端解析**；
     服务端不过 formatter，客户端 `enrichPlatformSuite()` 也不合成文本。
  4. iOS 改动本机无法编译（无 macOS），必须由 CI `ios-altstore-build` 验证后才能算完成。

## DSH-088 三端功能对等缺口：iOS 少「空壳守卫」与「在线镜像区彻底删除」，空壳判定正则两端还写了两个版本（2026-09-21 修复）

- **现象**（用户原话）：
  「不管 iOS 还是安卓，双端的功能一定要差不多。不能只测试一个不测试另一个，或者出现一个有按钮、
  另一个没有按钮的情况。……这块你可以再检查一下。」「至于电脑客户端，需要和手机端联动……
  也就是所谓开发是『三端同步』。」
  即：**同一功能集合必须三端齐**，允许布局代码不同，不允许「一端有按钮、另一端没有」。
- **环境**：`tools/device-share-hub` 三端真源 —— Android `MainActivity.java`、
  iOS `Album/{ContentView,TrashView,PlatformCopyParser}.swift`、
  电脑端 `scripts/online_gallery_service.py`（端口 45835）。
- **根因（逐项机读核查后只发现三处，全在「能力集合」层面）**：
  1. **G1｜iOS 完全没有「空壳作品」守卫**。Android 早有
     `isCopySubstanceMissing()`（剥协议标记与 `[\s\u2800]` 后实质字数 < 30）：
     在线卡渲染时把平台按钮整排换成**置灰占位按钮**，分享入口 `openShare` 也拦。
     iOS 侧 **两处都没有**：`configureOnlineButtons` 照常铺平台按钮，
     `shareOnline` 只判 `textToCopy.isEmpty` ⇒ **空壳作品在 iPhone 上仍可点、仍可分发**。
  2. **G2｜iOS「本地回收站·在线作品镜像区」左滑直接 `return nil`**。Android `onlineTrashCard`
     提供「恢复 + 彻底删除（带二次确认）」，iOS 该区域**只能点行恢复**，
     **没有彻底删除入口** ⇒ 一条错误镜像记录在 iPhone 上永远删不掉。
  3. **G3｜空壳判定的标记正则两端写了两个版本**。Android 用 `<<<[^>]*>>>`，
     而真库里存在把结束标记写坏成 `<<<DOUYIN_END>>`（只有两个 `>`）的文案，
     该正则**完全匹配不到** ⇒ 两端对同一份文本会得出不同的「实质字数」。
- **证据**：
  1. 新增**三端对等核查器** `_parity_audit/audit_three_end_parity.py`，分三段输出：
     【A】按函数体花括号配对抽**按钮/操作清单**；【B】**电脑端路由 vs 两端调用集合**；
     【C】**对等契约逐字一致性闸门**（7 项字面量必须两端逐字相同）。
  2. **闸门双向 A/B（本次补做的关键一步）**：
     * 用 `_parity_audit/pre_fix/` 的修前副本跑（`--and-main` / `--ios-parser` /
       `--ios-content` / `--ios-trash` 四路覆盖）：
       **7 项契约全不一致 → FAIL，退出码 1**；
     * 跑真源：**7 项全一致 → PASS，退出码 0**。
     即闸门**确实会响**，不是常亮绿灯。
  3. **正则变更不误伤取证** `_parity_audit/substance_ab.py`：对全库 **642 份** `文案.txt`
     分别用新旧正则计算「实质字数」，**跨过 30 字判线的 = 0 份** ⇒ 修 G3 不会把任何
     合格作品误判为空壳，也不会放过任何空壳。
  4. **接口面（B 段）结论**：PC 提供 16 条路由；两端调用集合**完全一致**（11 个接口）；
     仅 PC 单方的 4 条（`authorized-devices` / `delete` / `phones` / `sync-phone-counts`）
     属管理面与工作台扫描，**不是客户端功能缺口**。此前一度以为「iOS 只调 2 条」是 grep
     模式过窄的假象（`api/online` 宽松匹配后纠正）。
- **修复**（iOS 0.8.28 / build 99；Android 0.8.47 / versionCode 158）：
  - `PlatformCopyParser.swift`（+1175 B）：新增 `copySubstance(_:)` / `isCopySubstanceMissing(_:)`，
    正则与阈值与 Android 逐字一致。
  - `ContentView.swift`（+1851 B）：`configureOnlineButtons` 空壳时改铺
    `makeCopyMissingButton()`（`⚠️ 文案缺失（空壳作品，不可分发）`、红色、`isEnabled=false`、`alpha=0.55`）；
    `shareOnline` 增加 `isCopySubstanceMissing` 拦截（`⚠️ 该作品文案缺失（空壳作品），已阻止分发`）。
  - `TrashView.swift`（+1433 B）：在线镜像区左滑返回 `[purge]`（`彻底删除`），
    新增 `confirmPurgeOnlineTrash(_:)`（弹窗 `彻底删除` / `彻底删除后无法恢复，确定删除？` →
    `OnlineWorkLifecycle.deletePermanently`），页脚同步补「左滑可彻底删除该记录」。
  - `MainActivity.java`（+334 B，仅 G3）：`copySubstance()` 正则改为 `<<+[^<>]*>>+`。
- **回归要求**：
  1. 任何改动手机端 UI 或解析器的提交，必须跑**三端对等核查器**并确认
     【C】段 `不一致 0 项`、退出码 0；
  2. 新增/修改任何**两端同名能力**（按钮文案、阈值、正则、弹窗文案）必须**先改闸门的契约项、
     再加实现**，否则等于没有闸门；
  3. 闸门自身每次改动都要做**修前副本 A/B**（FAIL→PASS），防止退化成常亮绿灯；
  4. 改动 `PlatformCopyParser` 仍须叠加 **DSH-087 的出口对齐检查器** 与 **Java 验证工程 32 项**。

## DSH-087 iOS `parseAvailablePlatforms` 四个文案出口整批漏净化：修完 DSH-085 后，iOS 点按钮仍会带标记（2026-09-21 修复）

- **现象**：DSH-085 只修了 `parse()` 与 `enrichPlatformSuite` 两条路径；**iPhone 上点文案按钮仍然可能
  拿到带 `<<<COPY_FORMAT:3>>>` 的文本**。用户在真机上的原始主诉正是「手机在线相册点那个按钮会复制
  很多其他标识符分割符号」。
- **环境**：iOS `Album/PlatformCopyParser.swift::parseAvailablePlatforms`；调用方**同时**是
  本地卡（`ContentView.swift:1282` `CopyParserCache.platforms(for:)`，读磁盘 txt）与
  在线卡（`ContentView.swift:1474` `configureOnlineButtons(_:)`）。
- **根因**：同一函数在两端**移植不对齐**。该函数有 4 个构造 `copyText` 的出口：
  ①非协议文本兜底「发布」②`<<<VERSION_START:…>>>` 块正文 ③自定义标记块正文 ④伪协议「发布」兜底。
  Android 4 个出口**全部**过 `stripProtocolMarkers(...)`；**iOS 4 个出口一处都没过**
  ⇒ 畸形标记（`<<<X_END>>`，只有两个 `>`）与伪协议头会原样进剪贴板。
- **证据**：
  1. 新增**出口对齐检查器** `_parser_exit_parity/check_parser_exit_parity.py`：不翻译、不编译，直接按
     花括号作用域解析真源文件里的 `new AvailableItem(` / `AvailableCopyPlatform(` 构造点。
     **修前副本 FAIL：iOS 10 个出口中 4 个违规、Android 10 个出口 0 违规**；
     **修后 PASS：两端各 10 个出口 0 违规**（`--swift pre_fix/PlatformCopyParser.swift` 可复现修前状态）。
  2. Java 验证工程断言由 26 项扩到 **32 项**（新增「畸形标记块出口」「自定义标记块出口」「多版本块内畸形标记」），
     真库 **642 份** `文案.txt` 驱动真代码：**32 PASS / 0 FAIL、脏 0**；
     错误版本 `--break-guard`：**4 FAIL**（闸门确实会响）。
  3. 真机侧核对：iPhone 12（iOS 26.6，USB）装机版本为 **0.8.25 / build 96**（不含任何本轮修复）；
     本次改动落在 **0.8.27 / build 98**，须重新构建侧载后才能做真机验收。
- **修复**（iOS 0.8.27 / 98；Android **无需改动**——原本已对齐）：
  四个出口全部包一层 `strippingProtocolMarkers(...)`；两处兜底的 `text.trimmed` 改为
  `strippingProtocolMarkers(text).trimmingCharacters(in: .whitespacesAndNewlines)`。
  同步新增 3 个 XCTest + 2 个 JUnit 用例，两端用例名**一一对应**：
  `testPseudoProtocolFallbackNeverLeaksHeader` /
  `testMalformedMarkerIsStrippedFromAvailablePlatforms` /
  `testCustomAndMultiBlockCopyNeverLeakMarkers`（JUnit 侧为 `customAndMultiBlockCopyNeverLeakMarkers`）。
- **回归要求**：
  1. 任何改动 `PlatformCopyParser` 的提交，必须跑**出口对齐检查器**（应 PASS）与 **Java 验证工程**
     （应 32 PASS / 0 FAIL / 脏 0），并在**错误版本**下确认会 FAIL；
  2. 新增任何 `copyText` 出口，必须同时给 Android 与 iOS 补净化 + 同名对应用例；
  3. iOS 侧 Swift 单测必须在 Xcode 跑（本机无 Swift 编译器 ⇒ 检查器只能证明**结构对齐**，
     不能替代单测与真机截图）。

## DSH-086 在线相册「重置」按钮点下去像失败：5 秒缓存没清 + 作品从未移回待发区（2026-09-21 修复）

- **现象**（用户原话）：「手机在线相册点击那个重置按钮会失败，BUG。」
  实际服务端返回 `HTTP 200 {"ok":true,"useCount":0}`、磁盘计数也确实归零，
  但用户看到的仍是「次数还是 1、重置按钮还在」，主观判定为失败。
- **环境**：电脑端 `online_gallery_service.py`（端口 45835）+ Android `MainActivity.confirmResetOnlineWork`；
  iOS `ContentView.confirmResetOnlineWork` 同一接口。
- **根因（两个独立原因，用三次采样把它们分开坐实）**：
  1. **5 秒阶段缓存未被作废**：`list_stage_works()` 有 `_stage_cache`（5 秒，回收站两个 Tab 用），
     而 `reset-work` 末尾只调 `self.scanner.scan(force=True)`；`scan(force)` 只清
     `_cached_works` / `_last_scan_time`，**不清 `_stage_cache`** ⇒ 手机端重置后立刻重拉
     `/api/online/recycle`，拿到旧值 `useCount=1`。
  2. **作品从未回迁（主因）**：重置只写 `作品标签.json` / `manifest.json` 的计数，
     **不把作品目录从 `_已发送1次（微信公众号可发）` 移回 `已发送0次（抖音小红书可发）`**
     ⇒ 作品永远挂在「已使用」Tab，主页「全部」（只扫「已发送0次」）里也找不到它，
     与弹窗提示语「重置为待首发状态」不符。
- **证据**（探针 `_probe_reset_work4.py`，测试后完整还原）：
  `判重置前物理位置 = _已发送1次`；`POST reset -> moved=true`；
  **不等待、不带 refresh** 查 `/api/online/recycle?tab=sent` ⇒ 该作品**已不在**列表（修复前：仍在，`useCount=1`）；
  查 `/api/online/works` ⇒ 作品**已回到**待发区（修复前：找不到）；磁盘 `newPath` 位于「已发送0次」、原路径已消失、`作品标签.useCount=0`；
  还原后 `totalWorks=386` / `recycle sent total=558` / `useCount>0=558` 与基线**逐项一致**。
- **修复**：
  1. `scan(force=True)` 连带 `self._stage_cache.clear()`（单点修复，覆盖所有变更入口）；
  2. 新增 `WorkScanner.invalidate_stage_cache()`，`reset-work` 里显式再调一次；
  3. 新增 `OnlineGalleryHandler._move_work_to_stage0()`（镜像既有 `_move_work_to_stage1`：
     3 次重试 + `copytree` 兜底 + 写 `_portfolio_move_logs/delete_move_log_YYYYMM.csv`），
     **仅当作品确实位于「_已发送1次」时才回迁**（垃圾库 / 已在待发区的不动，避免误挪）；
  4. 返回体追加 `moved` / `newPath`，保留 `ok` / `useCount` / `message` 兼容旧客户端；
     回迁失败不致命 —— 仍返回「计数已归零」，并在 `message` 里写明原因。
- **回归要求**：
  1. 任何会改变阶段库内容的入口（重置 / 删除 / 使用）改完磁盘后都必须作废阶段缓存；
  2. 服务端改动必须走 `start_online_gallery_service.ps1 -Restart` 重启，并核对
     `/api/online/status` 的 `code.staleCode === false`（改完不重启会继续跑旧代码且零报错）；
  3. 三态实测（正常 / 故障 / 恢复）必须在**运行中的服务**上做，且测试后逐文件还原并复核库计数。

## DSH-085 手机端点平台按钮，剪贴板里塞满 `<<<…>>>` 协议标记（Android 0.8.46 / iOS 0.8.26 修复）

- **现象**（用户原话）：「手机端目前点击那个按钮会复制很多其他标识符分割符号吗？你看看这个苹果，安卓。」
  实测确认为真：V4.5 多版本作品点按钮后，剪贴板里是**含全部 `<<<VERSION_START:名>>>` / `<<<VERSION_END>>>`** 的整份原文。
- **环境**：Android `PlatformCopyParser.java` + `MainActivity.enrichPlatformSuite` + `ShareActivity`；
  iOS `PlatformCopyParser.swift` + `WorkLibrary.swift`。
- **根因（三条泄漏路径，用真库 641 份 `文案.txt` 逐份驱动真代码测得）**：
  1. **`parse()` 提前返回**：只要文本不含 `COPY_FORMAT:2/3` 头、也不含该平台固定标记，
     就无条件把**整篇原文**当该平台文案返回。V4.5 多版本（`<<<COPY_FORMAT:MULTI>>>`）正是这种形态
     ⇒ 点任何平台按钮后整份原文进剪贴板，实测 **80 份**命中（Android + iOS 同源）。
  2. **兜底按钮未剥标记**：`enrichPlatformSuite` 在多版本作品上仍会合成
     「规避营销版 / 种草版 / 大纲方案版」三个旧版兜底按钮，其中**「种草版」的兜底内容取整篇原文**
     （「大纲方案版」只剥了一半：`_START/_END` 剥了，`MULTI` 头与 `VERSION_START` 块还在）。
  3. **「伪协议」文案**：Codex 产线产出「有 `<<<COPY_FORMAT:3>>>` 头、正文却用 `【小红书自然种草版】`
     之类中文标题分节、没有任何 `<<<XHS_START>>>` 标记」的文案 ⇒ 落到「发布」兜底分支
     `copyText = text.trim()`，把 `<<<COPY_FORMAT:3>>>` 原样带出，实测 **46 份**命中。
     （另有 **9 份**把结束标记写坏成 `<<<DOUYIN_END>>`——**只有两个 `>`**，被块正则当成正文吞进来。）
- **修复**：
  1. `parse()` 的提前返回加闸门：只有**完全不含任何协议标记**的旧版纯文案才原样返回；
  2. 新增 `hasAnyProtocolMarker` / `hasMultiVersionBlocks` / `stripProtocolMarkers` 三个静态工具（可单测）；
  3. 标记正则改成 `<<+[^<>]*>>+`（开头 2+ 个 `<`、结尾 2+ 个 `>`）——**容忍上面那 9 份畸形标记**；
     第一版写成 `<<<[^>]*>>>`（要求正好三个 `>`）时对 `<<<DOUYIN_END>>` 完全不动，脏作品仍是 9 份；
  4. 所有取出的正文（`parse` / 多版本块 / 自定义标记块）与两处「发布」兜底一律再过一道 `stripProtocolMarkers`；
  5. 多版本作品不再合成那 3 个旧版兜底按钮（保留各版本自身按钮 + 「抖音避坑」）；
  6. `MainActivity.openShare(work, platform, copyText)` 新增重载 + `ShareActivity.EXTRA_COPY_TEXT`：
     把按钮已抽好的文案直传过去，从构造上保证「按钮标签 == 剪贴板内容」
     （多版本下 10 个版本的 platform 都是 GENERAL，`ShareActivity` 自己无法判断用户点的是哪一版）。
- **证据（闸门有效性自证）**：`PlatformCopyParser.java` 是纯 Java、无 Android 依赖，
  用 JDK 22 单独编译它 + 一个镜像 `enrichPlatformSuite` 的验证工程，跑真库 641 份：
  **脏作品 142 → 0**；逻辑断言 **26/26 PASS**。
  再把实现换回错误版本（`multiVersion` 恒 false + 兜底不剥标记）复跑：
  **4 项断言 FAIL、脏作品 176** ⇒ 证明闸门真的会响，不是「绿色等于有效」。
  另核对改动前后括号平衡：`{}` 703/703 → 708/708，`()` 4338/4340 → 4362/4364
  （`()` 的 2 个缺口是改动前就存在的字符串字面量，非本次引入）。
- **回归要求**：
  1. 解析器改动必须同时改 Android(`PlatformCopyParserTest`) 与 iOS(`PlatformCopyParserTests`)，两端 1:1 对齐；
  2. iOS 侧本机无 Swift 编译器，逻辑一致性靠同源移植 + 括号/结构核对，**最终必须以 Xcode 跑通单测为准**；
  3. 出现新的「伪协议」或畸形标记形态时，先补进 `malformedEndMarkerIsStrippedFromExtractedCopy`
     与 `pseudoProtocolCopyNeverLeaksHeader` 两个用例，再改实现；
  4. **落盘侧仍应治本**：Codex 产线产出的「伪协议」文案与畸形结束标记，属于落盘口缺陷，
     客户端净化只是兜底，不应作为「磁盘数据可以不规范」的理由。
- **已知未做**：Android 0.8.46 / iOS 0.8.26 只改源码与升版本号，**尚未构建、未推送**
  （用户此前指示安卓自动推 APK 先不动；且本轮不做 git 操作）。

## DSH-084 带输入框的弹窗被误触关闭，已输入的备注全丢（2026-09-21 修复）

- **现象**（用户原话）：「我点击删除并备注之后的弹窗，就应该只有三个地方可以点击才对——
  对话框、输入框、按钮；之外的界面点击应该无效。现在点到外面就关了，我输入的文字就消失了，很烦。」
  即：在「删除并备注」弹窗里打完垃圾原因，磕到弹窗外的界面，弹窗直接关闭，
  **已输入内容全部丢失且没有任何提示。**
- **环境**：Android 端 `MainActivity.java`；在线相册删除（`promptRemarkThenDeleteOnlineWork`）
  与本地删除（`promptRemarkThenMoveToTrash`）两条路径都会经过该弹窗；在线回收站备注、本地回收站备注、
  设置电脑 IP 三个弹窗同样受影响。iOS 端是标准模态 `UIAlertController(.alert)`，点外部不会关闭，**不受影响**。
- **根因**：Android `AlertDialog` 默认 `setCanceledOnTouchOutside(true)`。
  全项目 **19 处** `AlertDialog` 中只有 1 处（下载进度弹窗）设了防误关，其余18 处全部可被点击背景关闭；
  其中 **5 处带输入框**，关一次就丢一次输入。
- **修复**：
  1. 给 5 处带输入框的弹窗加 `setCanceledOnTouchOutside(false)`；
  2. **只禁点背景、保留返回键**（不用 `setCancelable(false)`）——保证弹窗永远有退路；
  3. 新增守卫用例 `InputDialogProtectionTest`：静态扫描 `MainActivity`，
     「含 `setView(` 且读 `getText()`」的弹窗必须有保护；只展示不读输入的布局不受约束；
  4. 该用例在 CI 里真实执行（`:app:testDebugUnitTest`），不是“文件在但从不跑”。
- **证据**：本机无 Gradle 环境，用逐字复刻判据的脚本做**三态自证**：
  当前代码 0 处违例 / 漏掉 1 处→精确报出「第 1513 行 垃圾备注（随作品写入元数据）」 / 5 处全漏→ 5 处全报。
- **自证过程中发现并修掉了守卫自身的两个 bug**（重点）：
  1. 块边界初版画在 `.create();` —— 保护语句天然写在它之后，导致**已保护被判成未保护**（常亮噪声）；
  2. `Matcher.start()` / `re.Match.start()` 返回的是**绝对下标**，被我当成相对偏移用成 `end + start()`，
     块一路吞到文件尾 → 任何弹窗都能踩到某个保护语句 → **守卫永远不响（假闸门）**。
- **回归要求**：
  1. 今后新增带输入框的 `AlertDialog`，必须同步加 `setCanceledOnTouchOutside(false)`，否则 `InputDialogProtectionTest` 会红；
  2. **不得用 `setCancelable(false)` 代替**——那会连返回键一起禁掉，弹窗卡死时没有退路；
  3. 改守卫用例后，必须重跑三态自证（已修 / 漏 1 处 / 全漏），**不能只看“当前全绿”**；
  4. 本机无法跑 Gradle 时，可用逐字复刻判据的脚本先自证，但 CI 里的真实用例才是权威。
- **未修（待拍板）**：另外 13 处无输入框的确认弹窗点背景仍会关闭。丧不了内容，
  且部分场景下「点空白处即取消」是用户习惯行为，暂不改。

## DSH-083 iOS 单测在 CI 里长期「只编译、从不执行」（假闸门，2026-09-21 修复）

- **现象**：`ios-altstore-build` job 长期绿灯，日志里却是
  `No bootable iPhone simulator on this runner; compiling the app and test bundle.` +
  `build-for-testing`。`build-for-testing` **只编译 app 与 test bundle，不执行 XCTest**，
  于是「iOS 单测通过」这句话此前从未被证明过 —— 包括 0.8.25 新增的 3 个 MULTI 用例。
  是在核对 run `35561484171` 绿灯含金量时发现的（没有采信绿灯，去翻了日志）。
- **环境**：GitHub Actions `macos-15`（`macos-15-arm64` / macOS 15.7.9 / Xcode 16.4 / iOS 18.5 SDK）；
  `.github/workflows/device-share-hub.yml` 的 `ios-altstore-build` → `Build unsigned iPhone app` 步骤。
- **根因（主因是脚本自己崩，不是环境缺模拟器）**：
  1. 模拟器探测写成了 YAML block scalar 里的 `python3 -c '<多行代码>'`。
     **block scalar 会给每一行加缩进**，Python 收到的是整体带前导空格的源码，
     CPython 直接 `IndentationError: unexpected indent`（本机实测：exit 1、stdout 为空）。
     命令替换因此拿到空串 ⇒ `simulator_id` 恒空 ⇒ 恒走 `else` ⇒ 恒 `build-for-testing`。
  2. 次因：过滤条件要求 `d.get("isAvailable")`，该字段在部分 Xcode 版本不存在，即便有设备也会漏选。
- **修复**：
  1. 选择逻辑迁出 YAML，落成 `tools/device-share-hub/scripts/pick_ios_simulator.py`，
     输出契约 `device:<udid>` / `create:<runtime>\t<device-type>` / 空；
  2. 过滤只看 `availabilityError`，不再依赖 `isAvailable`；
  3. 没有现成设备时，取**最新可用** iOS runtime 与它支持的 iPhone 型号，`simctl create` + `boot`；
  4. 仍然造不出来就 `::warning::` + step summary 明写「NOT executed」，不再静默；
  5. 测完把 `Executed N test` 写进 summary；**日志里没有该行就再告警一次**（防「跑了 0 个用例」）；
  6. CI 新增 `Verify iOS simulator picker` 步骤跑 `test_pick_ios_simulator.py`。
- **证据**：
  - 本机复现根因：`python3 -c '<带缩进代码>'` ⇒ `IndentationError: unexpected indent`，exit 1、stdout 空。
  - `test_pick_ios_simulator.py` **9/9 通过**；其间**刻意还原成错误版本**跑过一轮确认会 FAIL
    （型号按字符串排序导致 iPhone-8 压过 iPhone-16；`devices` 非 dict 时抛 `AttributeError`）。
  - workflow 的 bash 片段过 `bash -n`，并用真实字符串验证 `case` 能正确拆出 runtime / device type。
  - 待 CI 复核：新 run 的 `ios-altstore-build` 日志应出现 `Executed N test`。
- **回归要求（必须保留）**：
  1. **禁止在 YAML block scalar 里写多行 `python3 -c`** —— 缩进会被当成 Python 缩进。
     需要逻辑就落成脚本文件，并给它配测试。
  2. **任何「检测/探测/守卫」类代码，交付前必须自证它会响**：本条目就是「静默失败被绿灯掩盖」的实例。
  3. **降级分支必须显式发声**（`::warning::` + summary），不得静默走「看起来也成功」的路径。
  4. 判断「测试跑过没有」要有**可 grep 的硬证据**（`Executed N test`），不能只看 job conclusion。

## DSH-082 iOS 多版本文案塌成一个「乱码」按钮 + 在线分享只带第一张图（iOS 0.8.24 及以前；0.8.25 修复）

- **现象**（iPhone 真机、0.8.24 在线相册实测，用户原话三条，均当场复现）：
  1. 点文案按钮后「文案同步过去了，但是**乱码**，没有分隔开」；
  2. 「界面**没有其他版本按钮**」——整张卡片只出现一个按钮；
  3. 「点击发布居然去其他 APP 发现**只有一张图**，没有像安卓一样全部拉取发送」。
- **环境**：iPhone 13,2 / iOS 26.6；`相册` 0.8.24（build 95，AltStore 侧载）；
  iOS 在线相册模式（读电脑端「已发送0次（抖音小红书可发）」）；
  相关文件 `ios/Album/PlatformCopyParser.swift`、`ios/Album/ContentView.swift`
  （`shareOnline` / `WorkCell`）、`ios/Album/WorkLibrary.swift`。
- **根因（两条，共同点是「iOS 没跟上 Android」）**：
  1. **文案解析器协议落后（粒度错位型缺陷）**：Android `PlatformCopyParser.java` 支持
     `<<<COPY_FORMAT:MULTI>>>` + `<<<VERSION_START:名>>>` 多版本块（V4.5 全系 11 版）、
     `COPY_FORMAT:2/3` 固定平台、任意自定义标记，共 **7 个平台**；
     iOS `PlatformCopyParser.swift` 只认 `COPY_FORMAT:2/3` 与 `DOUYIN/XHS/XHS_2` **三个平台**。
     V4.5 文案头部是 `MULTI` ⇒ `isProtocol == false` ⇒ 落进兜底分支
     `[(.xhs, "发布", 整段原文)]` ⇒ **只有一个按钮，且正文含全部 `<<<VERSION_START:…>>>` 标记**
     （即用户看到的「乱码」）。**不是漏掉某一版，是整个新格式类别穿透了守卫。**
  2. **只发首图**：`shareOnline` 的注释写着「异步下载第一张或全部图片供分享」，
     实现却是 `entry.images.first` + `UIActivityViewController(activityItems: [img])`；
     而 Android `handleOnlineWorkUse` → `downloadWorkImages` 是**全部拉取 + 进度弹窗**后才分享。
- **修复（客户端，iOS 0.8.25 / build 96）**：
  1. 解析器重写为 Android 行为的 1:1 移植（`MULTI` 头、`VERSION_START/END` 逐版本块、
     7 平台枚举、`friendlyLabelForMarker` 版本短标签、通用动态标记扫描并跳过已知固定平台），
     **保留**原 `parse()` 的三段式语义与 `ok` / `missing` / `unreadable` 状态，旧用例不受影响；
  2. **堵掉「点哪个版本都一样」的次级坑**：MULTI 的 11 个版本里 10 个 `platform == .general`，
     若分享时按 platform 回查文案会**永远命中第一条** ⇒ 回调契约由「传平台」改为
     **直接传被点的那一条 `AvailableCopyPlatform`**，`WorkLibrary` 新增
     `prepareShare(_:images:platform:copyText:)` 重载承接显式文案；
  3. 卡片平台按钮区由「3 个写死按钮 + `fillEqually`」改为**动态创建 + 横向滚动**
     （数量不设上限；卡片高度 172 → 196，按钮再多也不增高），操作按钮独立成固定行；
  4. `shareOnline` 改走 `downloadAllImages`：按电脑端给出的顺序**依次拉取全部原图**，
     进度弹窗显示「正在下载：<文件名>（n/N 张）」，全部到齐再唤起分享；空文案直接拦住。
- **证据**：
  - **本地算法级闸门**（本机 Windows 无 Swift 工具链，故做等价移植验证）：把新解析逻辑移植为
    Python 跑 Android 既有测试向量，四项闸门全部 PASS ——
    ① 旧实现在 MULTI 上**确实**退化成「1 个按钮 + 含 `<<<VERSION_START:` 的整段原文」（缺陷复现）；
    ② 新实现出 11 个按钮；③ 按钮正文不含任何 `<<<` 标记；④「抖音避坑」正确归到 `.douyin`。
    另有 5 组向量做新老行为对照（Format2 / Format3 / 扩展平台+自定义标记 / MULTI / 旧版纯文案）。
  - 单测新增 3 例（`ios/AlbumTests/PlatformCopyParserTests.swift`），与 Android 同名向量逐条对齐。
  - **待复核项（不扩大结论）**：Swift 编译与 XCTest 结果以 CI `ios-altstore-build` job 为准；
    真机验收（11 个版本按钮是否逐个出现、点发布是否带入全部图片）**尚未执行**。
- **回归要求（必须保留）**：
  1. **文案协议是两端契约**：任何一端新增解析标记，必须同时改另一端；
     改任一端解析器前，先跑对端的同名测试向量。
  2. **多版本文案的按钮正文必须由解析结果携带，禁止按 platform 回查** ——
     归一化到 `.general` 的版本会全部撞成第一条。
  3. **`<<<` 标记不得出现在用户可见正文里**；任何解析分支的返回值都要断言这一点。
  4. **在线分享必须拉全部图片**；「只发第一张」在真机上表现为「另一个 App 里只有一张图」，
     极易被误判成平台限制而不是本端缺陷。

## DSH-079 iOS 信标缺版本扩展字段，电脑端把「泛流量篇数」当版本号显示（iOS 0.8.21 及以前；0.8.22 修复）

- **现象**（Windows 面板实机截图，非推理）：
  1. 苹果12 卡片永远显示 `相册 v0.8.3 (iOS 最新版 · build 11)`，而手机真机对账是 `v0.8.21 / build 92`；
  2. 同机 `笔记` 里 `手机储备 15 个（分类未上报）` —— 而安卓机同位置能显示「精准 x · 泛 y · 未分类 z · 总计 n」；
  3. 面板解析出的 `versionCode` 恰好等于该机 **泛流量作品数 11**（`device-presence.json` 实锤）。
- **环境**：iPhone 13,2 / iOS 26.6；`相册` 0.8.21（build 92，AltStore 侧载）；Windows「文件分发工作台（稳定版）」`src/server.js`
  `parseDevicePresenceBeacon`；协议见 `docs/PROTOCOL.md`「发现」。
- **根因（协议字段错位，两端各写各的）**：
  - 安卓 `OnlineService.sendBeacon()` 在第 9~11 位发 `base64url(appVersion)|versionCode|base64url(updateCapability)`，
    作品计数追加在 12~14 位；
  - iOS `IncomingTransferService.beaconData()` 当时**没有**发这三个字段，把作品计数直接接在第 9 位，
    于是电脑端按 `appVersion/versionCode/updateCapability` 解码：
    `parts[9]=base64url("0")` 解不出 → `appVersion` 空；`parts[10]=泛流量数` → 被当成 `versionCode`；
    `parts[11]=未分类数` → 被当成更新能力；真正的 `workCounts` 因 `parts.length < 15` 整块丢弃；
  - 前端 `app.js` 又对 iOS 写死 `` `相册 v${appVersion || "0.8.3"} (iOS 最新版 · build ${versionCode || 11})` ``，
    把空值兜成假值，于是「两个 bug 互相掩护」，肉眼完全看不出是协议错位。
- **修复（客户端，iOS 0.8.22 / build 93）**：
  1. `beaconData()` 补齐第 9~11 位（`IncomingTransferService.appVersion` / `appVersionCode` / `updateCapability`），
     作品计数自动落到电脑端期望的 12~14 位；
  2. `updateCapability` 用 `ipa-altstore-v1`（**不能**复用安卓的 `apk-push-v1` ——
     电脑端用该值判定「能否直接推送 APK 安装」，iOS 只能 AltStore 侧载，混用会导致误判）；
  3. 顺手把 `/v2/info` 也补上 `versionCode` / `updateCapability`（原先 iOS 的 `/v2/info` 缺 `versionCode`，
     服务端 `GET /api/online/phones` 只能显示 0）；
  4. 版本号单一真源仍是 `ios/project.yml`（`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`），
     Swift 侧统一经 `IncomingTransferService.appVersion/appVersionCode` 读取，不再各处复制 `Bundle.main` 取值。
- **证据**：`device-presence.json` iOS 条目 `appVersion:"" / versionCode:11 / workCounts:null`
  ↔ 该机泛流量作品数恰为 11；修复后同一字段应变成 `0.8.22 / 93` 且 `workCounts` 齐全。
- **回归要求**：任何「手机 → 电脑」的发现信标字段增删，必须**三端同时改并跑一次真机对账**
  （面板卡片显示 vs 手机 `设置 → 关于`），且**必须验证 `workCounts` 仍能被电脑端解析**——
  只测「版本号显示对了」会漏掉字段错位这类连带伤害。

## DSH-080 iOS 缺单播探测，电脑换网段后一直「拉取在线相册失败」（iOS 0.8.21 及以前；0.8.22 修复）

- **现象**：电脑 Wi-Fi 换网段（`192.168.0.107` → `192.168.1.27`）后，iPhone 切换「电脑在线」弹
  `拉取在线相册失败：<错误>`＋`正在自动搜索局域网内的电脑在线相册…`，长时间搜不到；
  同一时刻安卓机**手点一下在线相册就能读**。
- **环境**：`online_gallery_service.py`（端口 45835）服务侧正常（`code.staleCode=false`、`0.0.0.0:45835` 监听）；
  白名单 `~/.device-share-hub-authorized-devices.json` 的 `last_seen_ip` 停在旧网段
  ⇒ 换网段后**没有任何设备成功连上过**（`markBaseUrlGood` 才会登记）。
- **根因（两端发现通路不对等）**：
  - 安卓：**发** `ZWMDS2_GALLERY_DISCOVER` 单播/广播探测（`OnlineGalleryClient.java`），
    电脑端信标循环收到含 `ZWMDS2` 的报文会立刻**单播回**一条 JSON 信标（含当前 `url`），
    所以电脑一换 IP，安卓 2 秒内自愈；安卓还会监听周期性广播；
  - iOS：既收不到广播（`com.apple.developer.networking.multicast` 需 Apple 审批，AltStore 侧载拿不到），
    `OnlineGalleryClient.applyBeaconUrl()` **零调用者**、`beaconPort = 45832` 声明了却从未使用（死代码），
    唯一兜底是整段 /24 单播扫描（20 秒预算、48 并发）⇒ 电脑换 IP 后体验是「长时间连不上」；
    而 `resolveBaseUrl()` 优先复用旧的 `manual/custom/lastGood` 地址，用户看到的就是反复失败。
- **修复（客户端，iOS 0.8.22 / build 93）**：新增快轨 `LanDiscovery.probeBeacon()`：
  1. 用 BSD UDP socket 发广播探测（**发送不需要 multicast 权限**），目标为全局广播 + 各网段定向广播；
  2. 从**临时端口**收发，电脑端回信是**单播**到该端口 ⇒ 收单播同样不需要权限；
  3. 只认返回体里的服务标识 `DeviceShareHub-OnlineGallery`，避免误认其它 45832 占用者；
  4. 命中后 `setCustomBaseUrl(url)`；`ContentView.tryAutoDiscoverPc()` 改为**先快轨、失败再跑慢轨** /24 扫描；
  5. 网段枚举收敛为 `LanDiscovery.localBroadcastTargets()`，供文件传送信标与在线相册探测共用（原先两处各写一份）。
- **证据**：借 adb 安卓机当**外部探针**验证「手机→电脑」通路：
  `printf 'GET /api/online/status HTTP/1.0

' | toybox nc -w 6 192.168.1.27 45835` → `HTTP/1.0 200 OK`
  （安卓无 curl/wget，`toybox nc` 可用）；服务端 `GET /api/online/phones` 也能读到该 iPhone
  `192.168.1.154:45833 / appVersion 0.8.21` ⇒ 网络、防火墙、服务全通，问题只在 iOS 的选路。
- **回归要求**：
  1. 换网段（或改电脑 IP）后，手机端必须能在 **≤3 秒**内读到在线相册，不允许长期停在「拉取失败」；
  2. 「USB 数据线已连接」与在线相册**无关**（在线相册走 Wi-Fi HTTP 45835），
     排查时不要把它当作变量；
  3. 白名单**不是门禁**：读接口无鉴权，`device-register` 只是「下载即用」的辅助登记，
     「拉取失败」不要往 403/权限方向排查。

## DSH-081 列表明明已经显示 401 套作品，却仍弹阻塞式「拉取在线相册失败」——`hadData` 采样时机错位（iOS 0.8.22；0.8.23 修复）

- **现象**（iPhone 真机截图，非推理）：冷启动相册 App 后，界面**已经正常渲染出在线作品列表**
  （`全部 401 / 中秋 18 / 国庆 18 / 游戏 71`，卡片齐全、可读），却同时弹出一个**阻塞式**弹窗
  「操作没有完成 / 拉取在线相册失败：Could not connect to the server. / 正在自动搜索局域网内的电脑在线相册…」，
  必须点「知道了」才消失，且 10 秒后复截仍在。
- **环境**：iPhone 13,2 / iOS 26.6；`相册` 0.8.22（build 93，Sideloadly 侧载）；
  电脑 IP 已由 `192.168.0.107` 换到 `192.168.1.27`（与 DSH-080 同场景）；服务端 `/api/online/status` 健康。
- **根因（本地快照异步后到 vs. 失败判定提前采样）**：
  - `ContentView.loadOnlineData()` 在**发起请求前**算 `let hadData = !onlineWorks.isEmpty`，
    而本地快照是 `loadOnlineSnapshot()` **异步后到**的（读取磁盘 → 解析 JSON → 填 `onlineWorks` → `renderOnlineUI()`）；
  - 冷启动时序：请求发出（此刻 `onlineWorks` 还是空）→ 快照落地并渲染出 401 套 → 请求失败回包，
    此时 `hadData` 早已被固化为 `false` ⇒ 走「完全没有数据」分支，弹出阻塞弹窗；
  - 换句话说：**弹窗的触发条件与屏幕上真实有没有内容脱钩**。判定用的是「请求发出那一刻」的快照，
    却用来描述「回包那一刻」的界面状态 —— 判据与被判对象不同步。
  - 附带问题：即使确实没有任何数据（首次安装 / 快照损坏），也是**先弹阻塞弹窗再自愈**，
    于是电脑换网段这一个「本该 1 秒自愈」的常见场景，用户第一眼看到的永远是「拉取失败」。
- **修复（客户端，iOS 0.8.23 / build 94）**：
  1. 删除请求前的 `hadData` 采样，改为在 `group.notify`（**回包时刻**）直接用 `!self.onlineWorks.isEmpty`
     判定 —— 快照只要已经落地就会被正确算作「已有数据」，只发轻量 toast、保留列表、不弹窗；
  2. 无数据分支改为**先非阻塞、后升级**：先 `showToast("⚠️ 正在搜索电脑在线相册…")`，
     再 `tryAutoDiscoverPc(alertOnFailure:)`；**只有自愈也失败**（快轨 2.5s 无应答 + 慢轨 /24 扫描也没找到），
     才把原来的阻塞弹窗放出来；
  3. `tryAutoDiscoverPc` / `finishAutoDiscover` 增加 `alertOnFailure: String? = nil` 参数：
     自愈因 15 秒冷却或在途（`autoDiscovering`）**压根没跑起来**时，也如实弹窗，
     避免把错误静默吞掉（这是「守卫必须真的会响」的同一条原则）。
- **证据**：
  - 修复前真机截图 `_iphone_now.png` / `_iphone_after10s.png`：列表 401 套正常显示 + 阻塞弹窗并存；
  - 客端自愈其实**已经成功**：电脑端 `GET /api/devices/live-state` 显示 iPhone
    `appVersion 0.8.22 / versionCode 93 / workCounts {traffic:11, uncategorized:4, total:15}`，
    说明弹窗只是残留提示，数据通路是通的 —— 因此**不能**把该弹窗当成「连不上」的证据；
  - 版本字段对齐（DSH-079）也在同一时刻被证实：`versionCode` 已从错位的 `11` 变成真实的 `93`，
    而 `traffic` 仍是 `11`，两个数各归其位。
- **回归要求**：
  1. **「有没有数据」这类判定必须在消费它的同一时刻采样**：不要把「请求发出前」的状态缓存下来，
     用于「请求返回后」的分支判断 —— 凡是「异步填充 + 同步读取」共存的字段都要按这条查一遍；
  2. 冷启动 + 电脑换网段的组合场景，用户**不应看到任何阻塞弹窗**；允许出现一条自动消失的 toast；
     阻塞弹窗只保留「所有发现手段都失败」这一种情形；
  3. 验证必须看**真机屏幕**（`pymobiledevice3 developer dvt screenshot` 可免越狱截屏），
     不能只看日志或接口 —— 本 bug 的全部症状只存在于 UI 层。

### DSH-081 · 0.8.24 补漏：修对判定时机之后，「有缓存数据」这条支路把自愈一起关掉了

- **现象**（真机实测，非推理）：0.8.23 冷启动时**不再弹窗**（0.8.23 的修复生效），
  但屏幕停在**旧列表**，30 秒后 App 缓存的电脑地址**纹丝不动**：
  `customPcServerUrl` 仍是旧网段 `http://192.168.0.107:45835`。
  ⇒ 「看得见的旧列表 + 静默卡死」，**比原来的阻塞弹窗更糟**：
  用户以为一切正常，其实列表是陈的，点「发布 / 删除」都会失败。
- **复现方法（可复用，无需改服务端、不影响在线的其它手机）**：
  用 AFC 把 App 偏好里的 `customPcServerUrl` 改成旧网段地址再冷启动即可 ——
  `apps pull <bid> Library/Preferences/<bid>.plist` → 改字段 → `apps push` 回设备 →
  `developer dvt launch --kill-existing <bid> --userspace`；
  判据看两处：**屏幕**（有无弹窗）与**回读的 plist**（地址有没有被自愈改写）。
- **根因（两个判断被耦合）**：失败分支里 `tryAutoDiscoverPc()` **只写在「完全没有数据」那一支**；
  「有快照」那一支只发 toast。而 0.8.22 能自愈，恰恰是靠 `hadData` 被异步快照误判成 `false`、
  走错了分支才顺手跑了一次自愈。0.8.23 把误判修对，**同时把自愈也关掉了**。
  ⇒ 铁律：**有没有缓存数据只决定「用 toast 还是用弹窗」，绝不能决定「要不要去找新地址」。**
- **修复（iOS 0.8.24 / build 95）**：失败即在「有数据」「无数据」两支都调 `tryAutoDiscoverPc()`
  （仍受 15 秒冷却保护，不会刷屏）；`alertOnFailure:` 只在无数据支路传，保证弹窗语义不变。
- **证据**：0.8.23 下表（实测）——冷启动后无弹窗、`customPcServerUrl` 仍为旧网段、30s 未改写；
  0.8.24 应能在数秒内把它改写成 `http://192.168.1.27:45835` 并把列表刷成最新。
- **回归要求**：
  1. 「缓存地址失效」这一场景必须**同时断言两件事**：屏幕不弹阻塞弹窗 **且** 缓存地址被自愈改写。
     只断言前者，就会漏掉本条目这种「静默卡死」；
  2. 凡是「有数据走 A 分支、无数据走 B 分支」的降级逻辑，都要逐支检查
     **「自愈 / 重试 / 兜底」这类补救动作是不是被漏写在某一支里**；
  3. 修一个误判时，必须回头看**该误判此前顺带触发过哪些正确行为** —— 修好了误判却丢掉了副作用，
     净效果可能是退步。
- **验收结果（2026-09-21 真机实测，0.8.24 / build 95，iPhone13,2 / iOS 26.6）——本条目关闭**：
  两个判据都过：
  ① **屏幕**：冷启动 t+4s / t+12s / t+35s 三帧截图**只有列表（401 套）、零弹窗**；
  ② **缓存**：38 秒后回读 plist，`customPcServerUrl` 由 `http://192.168.0.107:45835`
  **被自愈改写为 `http://192.168.1.27:45835`**。
  旁证：服务端 `/api/online/phones` 读到 `192.168.1.154 | iPhone13,2 | 0.8.24 | 95`；
  云端更新源已发布 iOS 0.8.24 / build 95（sha256 与 CI 产物逐字一致）。
- **回归脚本（可复用，已脚本化）**：`_v0824_verify.py`（`probe` / `run` 两模式），
  一条命令跑完「杀 App → 写旧网段 → 冷启动 → 三帧截图 → 回读 plist」，
  并对**屏幕 + 缓存两处判据同时下结论** —— 只断言屏幕就会漏掉本条目这种静默卡死。

## DSH-077 空壳守卫被自己的靶子骗过：整段口径放行「标记齐全但正文是模板骨架」的作品（服务端，无客户端改动）

- **现象**：
  1. 在线作品全量 384 套里，**9 套**（全部位于 `已发送0次（抖音小红书可发）`）在手机端可点出
     「规避营销版 / 种草版 / 大纲方案版」等按钮，但点下去拿到的**不是文案，而是产线的填写说明**：
     `抖音短平快口播脚本，痛点切入+亮点+留资号召` / `[小红书种草正文，带两日详细行程排期、亮点提炼与真实避坑，拒绝空话]` /
     `HR方案决策版大纲，包含方案名称、适用对象、预算参考、决策亮点与服务保障`。
     **骨架可以一路走到「发布」这一步，是本条目最严重的地方。**
  2. 这些作品在 `/api/online/works` 里 `copyMissing=false`、`hasCopyText=true`，**看起来完全健康**。
- **环境**：`online_gallery_service.py`（端口 45835）；判定阈值 `MIN_COPY_DISTRIBUTABLE = 30`；
  手机端 `PlatformCopyParser.parseAvailablePlatforms` + `MainActivity.isCopySubstanceMissing`。
- **根因（与 DSH-076 的 `staleCode` 同源，是第二例「假闸门」）**：
  - 守卫的判定对象是**整段 `copyText`**：`copy_substance_len(copy_text) >= 30`。
    这批骨架整段实质 **107 字** ≥ 30 → 直接放行。
  - 但手机端是**按单个平台槽位**独立生成按钮的（`<<<MK_START>>> … <<<MK_END>>>` 逐块解析），
    用户实际消费的粒度是**单个槽位**，而不是整段。
  - **闸门测的粒度与用户消费的粒度不在同一层级** → 闸门形同虚设。
    而该守卫的注释明写它存在的目的正是「杜绝**标记齐全但正文全空**」——
    它被自己针对的失效模式精确命中。
  - 手机端的 `copyMissing` 是**本地算的**（`isCopySubstanceMissing(work.copyText)`），不读服务端字段，
    且粒度同样是「整段」，所以两侧一起看不见这个问题。
- **修复（`scripts/online_gallery_service.py`，纯服务端）**：
  1. 新增 `MIN_PLATFORM_SUBSTANCE = 30`、`_PLATFORM_BLOCK_RE`、`_PLACEHOLDER_SLOT_RE`、
     `audit_platform_slots()`、`sanitize_platform_copy()`：**下发前按槽位**剔除
     「命中占位语特征」或「实质字数 < 30」的块；
  2. **用按 span 删除、不用按块重建** —— 这样 `<<<COPY_FORMAT:n>>>` 头部与
     `<<<VERSION_START:名>>>` 这类本函数不解析的块能原样保留，不会误伤；
  3. `copyMissing` 口径升级为「净化后无真实槽位 ∨ 整段实质 < 30」；
  4. **只净化下发载荷，绝不修改磁盘原始文件**；`searchBlob` 仍用未净化原文，
     保证被剔除的作品**照样能被关键词搜到**；
  5. `/api/online/status` 新增 `slotGuard` 块，计数**按每轮扫描重置**，`slotsDropped > 0` 视为响铃。
  - **手机端零改动**：净化后整段实质自然 < 30，手机端既有 `isCopySubstanceMissing` 会整块置灰标红。
- **证据（重启服务后线上全量逐条对照，非推理）**：
  - `/api/online/status` → `slotGuard: {scanWorksAffected: 25, scanSlotsDropped: 58}`
    （58 里多数是既有的 **0 字空槽位**，手机端本来就不出按钮；9 套骨架才是本次的行为变更）；
  - `code.staleCode = false`，`scriptShaRunning == scriptShaOnDisk == b23f0283a7fb`（确认跑的是新代码）；
  - 逐条对照脚本 `_verify_slot_guard_effect.py`：**9 套从「2 个占位按钮」→ 整块置灰**，
    与 `/status` 的 `worksAffected` 完全对齐；**真实作品误伤 0 套**；
    1 套混合作品（真 XHS + 占位 XHS_2）只剔占位槽位、保留真实槽位，未整块置灰；
  - 阈值安全性核查：全量 554 个槽位里，**没有任何一个真实槽位落在 1–29 字区间**
    （最短的真实槽位都 ≥ 30 字；< 30 的只有 0 字空槽与占位骨架），故「< 30 即剔除」不会误伤。
- **回归要求**：
  1. `test_online_gallery_service.TestPlatformSlotGuard`（6 项）必须全绿，
     其中「守卫必须接线进载荷」一项用于防「只声明不调用」型假闸门；
  2. **新增守卫必须同时提交「还原成错误实现会 FAIL」的证明**：
     把 `sanitize_platform_copy` 还原为原样返回后，核心 3 条断言必须 FAIL
     （已实测 3/3 FAIL）。只有绿色、没有失败证明 = 不能证明测试在守东西；
  3. **新增判定必须用真实磁盘文件跑端到端**（`WorkScanner._inspect_work_dir`），
     只测 helper 会重蹈 `staleCode` 的覆辙；
  4. 上游产线（网页CDP 出图链路）需补一道「骨架不得落入可分发目录」的检查 ——
     本修复是**下游兜底**，不是根因消除。

## DSH-078 在线相册测试套件长期红灯：用例断言已被替换的旧规则 + 用例间互相污染（服务端测试）

- **现象**：`python -m unittest test_online_gallery_service` 长期 **10 项 2 败**，
  长期无人处理；红灯成为常态，等于没有测试。
- **根因（两个，且都有同一味道：测试与真实行为脱节）**：
  1. `test_two_uses_protection_rule` 断言「首次使用**不移动**、`remainingUses == 1`」，
     而**现行铁律**（`use-work` 处理器注释标注为「核心业务铁律」）是
     「只要手机端使用过一次，电脑后台就**物理移入** `_已发送1次（微信公众号可发）`」，
     故正确返回是 `moved=True`、`remainingUses=0`。**实现没坏，是用例还停在旧规则。**
  2. 该用例把**共享扫描根**里的样例作品真的挪走了（`setUpClass` 建的 `work1_dir`），
     于是按字母序后跑的 `test_works_search_and_filter` 搜不到作品 → 误报 0 条。
     **用例互相污染 → 结果依赖执行顺序。**
- **修复（`scripts/test_online_gallery_service.py`）**：
  1. 该用例自带独立作品目录（唯一 ID），改用 `addCleanup` 复原：
     删除自建目录 + 清扫可能落地的 `_已发送1次` / `_垃圾作品` / `_不合格成品合集` 中的副本，并 `scan(force=True)`；
  2. 断言改为现行规则：首次使用 `moved=True`、`remainingUses=0`、stage0 里目录已不存在、
     标签文件随作品一起搬走且 `useCount=1`；
  3. 第二次使用：`useCount=2`；`_move_work_to_stage1` 对已在目标位置的目录是**幂等**的
     （返回「作品已位于…」，`moved=True` 表示「已就位」而非「又挪一次」），
     故断言改为**真正的业务不变量：不得产生重复目录**（`_已发送1次` 下该作品目录数 == 1）。
- **证据**：`HEAD` 基线复跑 10 项 2 败（用 `git show HEAD:` 取旧文件到临时目录，非破坏性）；
  修复后 **16 项 0 败**。
- **回归要求**：
  1. 测试套件必须**全绿**；出现红灯即刻处理，不允许「红着红着就习惯了」；
  2. **业务规则变更时必须同步改断言**，并在用例 docstring 里写清「旧规则是什么、为什么变」——
     否则下一个人只会看到一条莫名其妙一直红的用例；
  3. **任何会改动共享 fixture 的用例必须自带清理**（`addCleanup`），
     套件必须做到**单跑与全跑结果一致**。

## DSH-076 在线回收站长期卡「正在读取」＋ 缩略图链路静默降级（Android 0.8.40 / versionCode 151；iOS 0.8.17 / build 88）

- **现象**：
  1. 手机端进入「在线相册 → 回收站」，界面长时间停在「正在读取电脑在线回收站…」，卡片迟迟不渲染；
  2. `logcat` 中 `OnlineGalleryClient: loadThumbnail failed ... timeout` 持续刷屏；
  3. 电脑端 `/api/online/image` 的 `thumb=0` 与 `thumb=1` **没有区别**，返回的都是原图。
- **环境**：Windows 中控（Python 3.11.4 + `pythonw` 常驻，端口 45835）＋ Android 0.8.39 真机（Redmi 13C，同一 Wi-Fi）；作品库 383 套，单张作品图最大 3–4 MB PNG。
- **根因**（一条主因 + 两个独立缺陷）：
  1. **主因｜服务进程跑着旧代码**：`online_gallery_service.py` 在 14:44 被改过并加入了缩略图链路，但常驻进程启动于 06:58 —— 进程里根本没有那段代码，`thumb=1` 参数被**静默忽略**并回落成原图，全程零报错、零日志。手机端于是为几百张卡片拉 GB 级原图，4 线程 + 8s 超时 ⇒ 大面积超时 + 原图解码 GC 风暴 ⇒ UI 冻结。
     铁证：`%TEMP%\gallery_thumb_cache` 目录**不存在**（而该目录在 import 时就会被创建）⇒ 进程里没有缩略图代码；进程 PID 启动时刻 06:58:54 vs 脚本 mtime 14:44。
  2. **独立缺陷 A｜iOS 图片契约缺失**：`/api/online/image` 只认 `id`+`file` 参数，而 iOS 端一直用 `?path=` ⇒ iOS 在线图片**一直 HTTP 400**。
  3. **独立缺陷 B｜iOS 版本号双源**：`ios/project.yml` 里版本号有**两处**（`settings.base` 与 `info.properties` 硬编码），升版本只改前者时 xcodegen 用后者覆盖生成的 Info.plist ⇒ IPA 版本与 CI 断言不一致 ⇒ iOS 产物**发不出去**，而 `android-build` 却是绿的，极易误判为「iOS 环境抖动」。
- **修复**：
  1. **缩略图链路整形（服务端）**：复活「只声明从未使用」的内存缓存（`THUMB_CACHE` 死代码）为带 LRU 淘汰的真实缓存；新增同图并发生成单飞锁；三级取图「内存 → 磁盘 → PIL」；Pillow 缺失/生成失败时打印明确日志并在响应头下发 `X-Thumb: fallback`（**杜绝静默降级**）；`/api/online/status` 新增 `thumbnail` 健康块。
  2. **补齐 iOS 旧契约**：`/api/online/image` 支持 `path` 的三种形态（绝对路径 / 相对成品库路径 / 裸文件名经索引解析），并做目录穿越防护（越界一律 404）。
  3. **客户端调度整形（Android）**：缩略图改用独立 8 线程池；新增磁盘缓存与在途请求去重；按 384px 目标边长降采样解码；新增失败重试一次（900ms）；
     在线列表缩略图由「每卡片前 4 张立即发请求」（382 作品 ⇒ 1500+ 请求齐压线程池）改为**整页调度**（首发预算 18 张 + 260ms/6 张渐进放行）。
  4. **代码新鲜度自检（防复发闸门）**：`/api/online/status` 新增 `code` 块，用「启动时脚本内容 SHA」对比「实时读盘脚本内容 SHA」，不一致即 `staleCode=true` 并提示重启服务；开机自启脚本由「直连 pythonw」改为「调用启动器 `-Restart`」，杜绝新旧进程并存。
  5. **iOS 版本号回到单一源**：`info.properties` 改为引用 `"$(MARKETING_VERSION)"` / `"$(CURRENT_PROJECT_VERSION)"`。
  6. **缩略图磁盘缓存加上限**：磁盘缓存以「路径+mtime」为 key、旧条目永不失效，此前无任何上限；现加 `MAX_DISK_ENTRIES = 2000`，超限按 mtime 淘汰最旧的一批、只留 90%，且低频触发（每 200 次写盘才真扫一次目录）。
- **证据**（修前 → 修后）：
  1. 单张「缩略图」体积 2,678.7 KB → **37.1 KB**；400 张总耗时（并发 8）10.79 s → **0.53 s**；P95 0.164 s → **0.021 s**；吞吐 37 → **754 张/s**。
  2. 真机（Redmi 13C / 0.8.39）一次进页取图 **1500+ → 231 次**，`loadThumbnail failed` 刷屏 → **0**，「正在读取」约 5.8 s 消失。
  3. CI run `35515813427`（源码提交 `95e5d47`）：`android-build` ✅ / `ios-altstore-build` ✅ / `windows-portable` ✅ / `publish-gallery-updates` ✅；`album-iOS-v0.8.17-altstore.ipa`（21.3 MB）已随 `v0.8.39` release 正常发布；线上 `latest.json` 已刷新。
  4. 仅 `remote-relay-check` ❌（长期已知红点，与本次改动无关）。
  5. 服务端自带单测 9 项 2 败（`test_two_uses_protection_rule` / `test_works_search_and_filter`），已用 `git archive HEAD` 取原版测试确认**同为失败**，属历史遗留。
- **回归要求**：
  1. 手机端进入在线回收站必须在数秒内渲染出卡片，「正在读取」不得长时间停留；
  2. 一次进页取图次数应稳定在数百次量级，不得回到 1500+；
  3. `/api/online/status` 的 `code.staleCode` 必须为 `false`（为 true 即说明服务在跑旧代码，需重启）；
  4. 改完服务端脚本后**必须重启服务**，重启后 `staleCode` 应回到 `false`；
  5. `git checkout` / `git pull` 造成的纯行尾符变化**不得**触发 `staleCode`（已单测锁定）；
  6. 发布前核对 EXE/APK/IPA、源码提交、Release、Android 更新索引与 SHA-256 一致。

## DSH-075 跨平台已用作品临时浮动置顶与【重置】误触回滚（Android 0.8.12 / iOS 0.8.5）

- **现象**：
  1. 用户在手机端（Android/iOS）从 20+ 套作品中挑选某篇发送/使用后，过会儿想回看复盘分析该篇作品时，该卡片因变灰且沉底（自然排序），需要滑动到很长列表的深处才能重新找到，操作极不方便；
  2. 用户在误触点击了发布、或者临时改变主意想明天再发时，该作品已经记录为已发送（shareCount > 0）并排期了 1 小时自动移入回收站，缺乏撤销与回滚机制。
- **根因**：
  1. 列表排序只依赖自然名称升序（Natural Sort），未赋予已使用作品（shareCount > 0）以置顶权重，导致刚操作过的焦点作品与未发作品混杂或滑离视口；
  2. 既有状态流只有“准备分享 -> 计数自增 -> 安排回收”的正向单向流动，缺少重置分享状态（Reset Share）的逆向回滚事务，导致用户无法取消排期或恢复初始绿色未用状态。
- **修复**：
  1. **双端已发作品临时浮动置顶（Floating Top Retention）**：
     - Android（`WorkLibrary.java`）与 iOS（`WorkScanner.swift`）：当 `shareCount > 0` 时优先浮动置顶至列表顶部，卡片标题附加 `📌 ` 标识；多个已用作品按最近使用时间倒序排列（新用在最顶），未用作品维持自然名称升序；
  2. **卡片新增【重置】状态按钮（Reset Share Action）**：
     - Android（`MainActivity.java`）与 iOS（`ContentView.swift`）：在已分享卡片的【删除】按钮左侧动态显示深灰拟态【重置】按钮；
     - 单击弹窗确认后，调用 `resetShare`：清空 `shareCount`、各平台计数、`used = false`、清除 `deleteScheduledAtMs` 自动回收排期，并移回普通列表排序；
  3. **版本升级**：Android 升级至 `0.8.12 (build 123)`，iOS 升级至 `0.8.5 (build 76)`。
- **回归要求**：
  1. 分享后作品立即加上 `📌 ` 并浮动置顶到第一位，多篇置顶按使用时间倒序；
  2. 点击【重置】确认后，作品瞬间回绿、计数归零、移出置顶归位回下方常规列表；
  3. 取消自动删除排期后，后台定时清理不会将其误删移至回收站。

## DSH-074 Android 删除卡片闪现回魂修复与 20+ 作品滑动卡顿根除（Android 0.8.11 / versionCode 122）

- **现象**：
  1. 用户在手机端点击删除作品移到回收站后，卡片虽然瞬间消失，但很快又在列表中“闪现回魂”，片刻之后才再次消失；
  2. 手机端作品数量超过 20 套时，主界面滑动出现掉帧、卡顿。
- **根因**：
  1. **异步搬移空窗期扫描回魂**：`optimisticRemoveWorks` 虽在主线程瞬间移除卡片，但后台单线程 `worker` 执行物理挪迁及 SAF 移动需数百毫秒；在此期间若触发了生命周期刷新、广播刷新或分类筛选，`library.listActive()` 仍会读出未完成搬移的物理文件，并将旧作品重新回填渲染（Flash back），待物理搬迁完毕的下一次刷新才真正抹除；
  2. **并发解码抢占 UI 线程**：`previewStrip` 对每个卡片无节制抢先解码 8 张缩略图（25 套作品即并发排队 200 个解码任务），且 `THUMBNAIL_EXECUTOR` 为普通线程优先级，密集的解码完成事件狂轰主线程 Looper，与用户的滚动和触摸事件抢占 CPU，引发 GC 抖动与卡顿。
- **修复**：
  1. **内存黑名单强制隔离（Pending Trash Barrier）**：
     - 在 `MainActivity` 引入线程安全的 `pendingTrashIds` 集合；
     - 删除触发瞬时立即登记目标 ID，在 `renderWorks`、`refreshWorks`、`applyCategoryFilter` 等全链路渲染与数据入口处进行黑名单强制拦截过滤，无论何时发生后台扫描或数据刷新，已删卡片绝对不再重返列表；后台物理移动彻底完成后自动释放；
  2. **缩略图低优先级调度与视口感知裁剪**：
     - `THUMBNAIL_EXECUTOR` 设置 `Thread.MIN_PRIORITY`，子线程执行时主动声明 `Process.THREAD_PRIORITY_BACKGROUND`，内核级让出 CPU 给主线程；
     - 单卡片瞬时并发预加载缩减至 4 张（横向视口满屏容纳极限），超出部分按需滚动加载；并在解码前核验视图 Tag 标签，已被划走的复用视图立即跳过解码；
  3. **版本号升级**：升级至 Android `v0.8.11`（`versionCode: 122`）。
- **回归要求**：
  1. 删除作品移到回收站后，卡片在任何生命周期或刷新触发下均绝对不许闪现回魂；
  2. 20+ 套作品长列表上下滑动丝滑流畅无卡顿；
  3. 编译打包与发布验证通过。

## DSH-073 Android 相册 App 回收站秒删性能与乐观 UI 彻底重构（Android 0.8.10 / versionCode 121）

- **现象**：
  在手机端相册列表点击作品卡片“删除”按钮并确认移到回收站时，界面无任何即时视觉反馈，卡顿冻结近 5 秒后才刷新并弹出提示，操作响应极其迟钝，体验严重不佳。
- **根因**：
  1. **UI 线程零即时响应**：原 `moveSelectedToTrash` 逻辑将所有任务提交给单线程 `worker` 执行后，主线程没有任何先验（Optimistic）视图变更，用户误以为点击无效；
  2. **物理搬迁串行阻塞**：`moveToTrash` 和 `ExternalTrashManager.moveTrashedSource` 涉及多张高清大图跨目录移动与 SAF（Storage Access Framework）IPC 跨进程调用，物理 I/O 耗时达数百毫秒至数秒；
  3. **最严重瓶颈——全量维护扫描与重绘**：后台搬迁完毕后，在主线程同步调用了 `refreshWorks()`，而 `refreshWorks()` 内部无差别同步调用了 `CleanupCoordinator.run(this)`（全盘遍历扫描过期缓存、读取全量 active/trash properties 元数据、执行全量 maintain 扫描，并对所有作品重新执行 SAF 目录存在性检测），并粗暴清空 `worksContainer.removeAllViews()` 将页面上全部卡片 View 彻底销毁重建，触发所有缩略图重新异步解码与布局重排！
  4. 回收站单项恢复（`restore`）与清空（`clearTrash`）同样存在调用 `refreshWorks()` 的全盘阻塞机制。
- **修复**：
  1. **瞬时乐观 UI（Optimistic UI）**：
     - 为每个卡片 View 绑定对应作品 ID（`card.setTag(work.id)`）；
     - 新增 `optimisticRemoveWorks(Set<String> ids, String toastMessage)`：
       - 主线程下一帧（< 16ms）瞬间从内存活跃集 `renderedWorks` 中剔除目标 ID；
       - 通过容器关联查找卡片，调用 `worksContainer.removeViewAt(i)` 触发 Android 原生 `LayoutTransition` 180ms 丝滑折叠与渐隐过渡；
       - 列表为空时瞬间切换至空态占位提示；
       - 瞬间更新顶部分类 Tab 徽标数字（`updateCategoryCounts`）与标题栏计数；
       - 弹出即时轻量 Toast（如“已移到回收站”）；
  2. **物理文件与 SAF 搬迁完全后台异步解耦**：
     - 后台 `worker` 静默完成文件挪移与属性写入；
     - 静默调用 `OnlineService.publishWorkInventory(this, active)` 更新在线心跳与广播，**彻底剔除成功路径上的 `refreshWorks()` 与 `CleanupCoordinator.run(this)`**；
     - 仅在物理移动发生严重异常时，主线程弹出错误 Toast 并由下一次页面自然生命周期进行对账；
  3. **回收站与恢复操作全链路对齐秒级响应**：
     - `restore(String id)`：回收站界面瞬间乐观移除恢复项并刷新徽标，后台异步还原属性并静默发布心跳；
     - `clearTrash()`：确认后瞬间清空容器并切换为空态提示，后台静默批量删除物理文件与 SAF 源；
  4. **版本递增**：升级至 Android `v0.8.10`（`versionCode: 121`）。
- **证据**：
  1. 单元测试 `build-local.ps1 -AndroidOnly`（单测全绿，assembleRelease 编译打包生成 `app-release.apk`）；
  2. 通过 ADB 覆盖安装至华为 P30（`8KE0219924003568`），通过前台 live 截图确认 `v0.8.10 (121)` 正常启动运行；
  3. 现场实操验证：原移入回收站的 2 项作品在回收站中瞬间恢复成功，主相册作品数即刻恢复为 8 篇，回收站归零，分类角标即时递减/递增，卡片移除折叠流畅无感，耗时感官从 5000ms 降至 0ms。
- **回归要求**：
  1. 点击“删除”移到回收站、“恢复作品”以及“清空回收站”，必须在点击确认瞬间（<16ms）卡片消失，绝不许出现等待转圈或冻结；
  2. 乐观移除后分类角标数量和标题作品数必须即刻减 1，后台物理搬迁成功后在线 Beacon 作品计数保持准确；
  3. 单元测试与构建流水线保持 100% 绿灯。

## DSH-072 缩略图手势精准分流、卡片边框裁剪溢出根除与宽屏预加载优化（Android 0.8.8 / versionCode 119）

- **现象**：
  1. 用户在卡片小缩略图上左右滑动浏览图片时，容易误触发外层全屏左右切 Tab 手势；
  2. 横向滑动缩略图预览条时，图片滑动边缘突破白色圆角卡片边框，产生视觉溢出穿透；
  3. Redmi K60 等 1080p 宽屏设备上首屏卡片第 5、6 张图片出现未加载灰白方块。
- **根因**：
  1. `SpringScrollView.onInterceptTouchEvent` 对横向滑动统一拦截，未在 ACTION_DOWN 时根据触摸落点坐标检测子级 `HorizontalScrollView` 并做事件分流；
  2. `MainActivity.card()` 与 `previewStrip()` 未开启双向视图裁剪（`setClipChildren(false)`），导致子视图在平移滚动时穿透卡片圆角描边外框；
  3. 异步缩略图预加载阈值固定为 `index < 4`，少于宽屏设备一行能容纳的 5~6 张预览图。
- **修复**：
  1. `SpringScrollView` 增加 `findHorizontalScrollViewAt(View, int, int)` 递归检测，当触摸在缩略图预览条内时标记 `touchStartedInHorizontalChild = true`，父级绝对不拦截横向手势；触摸在标题、按钮或卡片留白时才触发顶部分类 Tab 秒级切换；
  2. `MainActivity.card()` 与 `previewStrip()` 严格开启 `setClipChildren(true)` 与 `setClipToPadding(true)`，双向裁剪锁死在卡片 14dp 内边距中，彻底阻绝视觉溢出；
  3. 预加载阈值由 `index < 4` 提升至 `index < 8`，首屏第 5、6 张小图瞬间显示，消除灰方块；
  4. 同步更新 `verify-work-card-ui.mjs` 与 `verify-p2p-invariants.mjs`，确保全套端到端校验脚本 100% 绿灯。
- **证据**：
  1. `build-local.ps1 -AndroidOnly`（单测 22 项、assembleRelease、lintRelease）通过，版本号 `0.8.8` / `119`；
  2. 5 项核心验证脚本（p2p-invariants, removed-surfaces, task-receipts, work-card-ui, auto-mobile-update）全部绿灯；
  3. Redmi K60 与 Redmi 13C 实机安装覆盖，截图实测滑动不切 Tab、图片绝不破框、灰白方块彻底消失。
- **回归要求**：
  1. 缩略图横滑必须保持原位平滑滚动，绝不能误切 Tab；卡片空白区域左右滑必须切换 Tab。
  2. 缩略图左右滑动边界必须严密限制在白色卡片边框内。
  3. 5 项 Node 检验脚本与 Android 单元测试必须保持 100% 通过。

## DSH-071 手机端虚假“接收失败”通知刷屏、传送界面设备发现主动广播与全屏分类左右滑手势升级（Android 0.8.7 / versionCode 118）

- **现象 1**：手机相册未发起传送时，系统通知栏频繁弹出“接收失败 Broken pipe”弹窗，传送界面历史记录充斥大量“接收失败 Broken pipe”垃圾日志。
- **根因 1**：PC 端 Electron `server.js` 探活定时器（`fastDeviceProbingTimer`）每 5 秒发起一次 500ms 超时的 TCP/HTTP 探针；探针超时关闭 socket 时，手机端 `HttpRequest.readLine` 在 0 字节 EOF 时抛出 `HttpError(400, "连接提前结束")`，`handleHttp` 将其捕获并无差别作为传输失败记录到 `OperationLog` 并弹出通知。
- **修复 1**：
  1. `HttpRequest.readLine` 识别 0 字节 EOF 作为客户端正常断开连接（返回 `null`），不抛出 HttpError。
  2. `handleHttp` 捕获异常时严格校验 `isIncomingTransferPath`，只有真实文件/任务传输请求失败才弹出通知并写入操作记录，完全屏蔽后台探测与无害连接断开。
  3. `OperationLog` 增加 `clear(context)`，并在 `TransferActivity` 增加“清空”按钮，一键抹除历史虚假记录。
- **现象 2**：传送界面无法发现同局域网设备（因部分 Wi-Fi 路由开启 AP 隔离阻断点对点广播），且界面缺少主动刷新触发按钮。
- **修复 2**：传送界面增加“🔄 刷新设备”按钮，主动在 UDP 45832 广播 `ZWMDS2_DISCOVER`，促使局域网内对端设备立即回送自身 Beacon。
- **现象 3**：用户左右滑动卡片时希望能切换顶部分类 Tab，同时手指在卡片底部的横向缩略图预览条上滑动时应只滚动缩略图，互不干扰。
- **修复 3**：
  1. `SpringScrollView` 增加横向滑动手势监听器 `SwipeListener`（`onSwipeLeft` / `onSwipeRight`），利用 `VelocityTracker` 与 `minSwipeDistance = dp(42)` 精确识别左右滑手势。
  2. 尊重子 `HorizontalScrollView`（预览图条）的 `requestDisallowInterceptTouchEvent`，滑动缩略图时只滚动图片条；滑动卡片其余区域（标题、按钮、留白）时触发分类 Tab 快速切换（内存级秒切，分类按钮自动居中平滑滚动）。
- **证据**：
  1. 单元测试 `gradle.bat :app:testDebugUnitTest` 22 项全过；
  2. 编译 Release APK 安装至红米 13C (`69PNFQUCT4XGKZRO`) 与红米 K60 (`e3b58850`)；
  3. 真机 live 验证：卡片区域左右滑动即刻切换分类（全部 -> 团建游戏 -> 象山）、分类 Tab 自动居中平滑对齐；缩略图区域左右滑动正常滚动图片；传送页一键清空日志、“🔄 刷新设备”广播发送正常、虚假接收失败通知彻底消除。
- **回归要求**：
  1. 缩略图横向滑动不能触发分类切换，卡片其他区域左右滑动必须秒级切换分类。
  2. 局域网探测断开不产生任何系统通知与操作记录。
  3. 传送界面“清空”按钮能即刻持久化清空历史记录，“🔄 刷新设备”按钮能主动触发组播与广播。

## DSH-070 移动端把会话追踪 TXT 当成可复制文案（Android/iPhone 0.8.3，修复候选）

- 现象：作品目录同时存在 `会话追踪.txt` 和 `小红书文案.txt` 时，手机点击文案卡片复制了母版 URL、分支 URL、账号等会话元数据。
- 根因：Android `WorkRules.chooseCaption` 与 iOS `WorkScanner` 只优先识别 `文案.txt`，找不到时对全部 TXT 做自然排序；`会话追踪.txt` 因而可能排在真实文案前。点击动作本身正常，错误发生在导入/扫描时。
- 修复：双端统一优先选择 `文案.txt`、`小红书文案.txt`、`抖音文案.txt`，明确排除会话追踪、生产记录、质量报告、标签、元数据和日志类 TXT；仅有元数据的目录不再成为作品。当前生产工作台的结构化记录继续使用 JSON，兼容旧目录不强制改名或删除。
- 证据：Android `testDebugUnitTest`、`assembleRelease`、`lintRelease` 已通过；新增 WorkRules、ZIP 导入和 iOS 扫描回归。真实 K60 覆盖安装与点击剪贴板尚未完成。
- 回归要求：
  1. 同时存在会话元数据与小红书文案时，Android ZIP/SAF/隐藏目录和 iOS 扫描都必须指向小红书文案。
  2. 只有会话元数据与图片时不得识别为可发布作品。
  3. 旧目录只有 `说明.txt`、编号 TXT 等非元数据时仍按兼容兜底识别；不删除现有用户作品。

## DSH-069 移动端提交重试误报“任务不存在”（Android 0.6.61 / iPhone 0.6.44，修复候选）

- 现象：文件已经上传完成，但提交请求遇到一次超时/HTTP 500，电脑用同一个 taskId 重试时，手机返回“任务不存在”；如果手机其实已经导入成功但 ACK 丢失，重试还可能无法补回确认。
- 根因：Android 的提交异常处理删除了 active task 和临时目录；Android/iOS 成功导入后立即移除内存任务，也没有可持久化的完成回执，因此同一任务的重试失去幂等依据。
- 修复：Android 提交失败只记录 `commitFailedAtMs` 并持久化 manifest，保留任务到闲置超时；两端保存最近 128 个完成回执，`GET /v2/tasks/{id}`、重复创建、重复提交都返回完成状态；Android 提交入口串行化。
- 证据：`scripts/verify-task-receipts.mjs`、`verify-p2p-invariants.mjs`、`verify-auto-mobile-update.mjs`、`verify-removed-surfaces.mjs` 和 `git diff --check` 已通过；尚未云端构建或真机验证。
- 回归要求：
  1. 上传完成后人为制造一次提交超时/500，下一次同 taskId commit 必须返回 200，不能返回“任务不存在”。
  2. 导入完成后丢弃首次 ACK，再次 GET/commit 必须返回完成回执，作品库只能增加一次。
  3. 失败提交任务在 30 分钟内可继续提交，超过闲置超时才清理；新任务创建、取消和自动接收开关行为不受影响。

## DSH-068 USB 传送失败没有自动降级到其他通道（Windows V4.3.28，修复候选）

- 现象：USB 连接短暂中断、手机 USB 文件服务拒绝或线缆异常时，电脑提示 USB 失败并直接结束，即使同一手机仍有 Wi‑Fi/P2P/远程中继可用，也要求用户手动重发。
- 根因：`UploadToDevice` 的 USB `catch` 为避免重复直接重新抛出；但 Android WPD 和 iPhone House Arrest 实现都先写入临时目录，失败时会清理暂存，成功后才改名/提交，因此没有必要阻断后续通道。
- 修复：USB 失败且不是用户取消时记录 `usb_upload_failed_fallback`，保留清理后的文件列表，继续使用统一的 Wi‑Fi → P2P → HTTPS 中继链路；用户取消仍立即终止。
- 证据：新增 `verify-p2p-invariants.mjs` 与 `verify-auto-mobile-update.mjs` 断言；云端 Windows 编译和三端发布需由本轮 Actions 完成。当前电脑没有连接手机，真实 USB 回退仍待现场验证。
- 回归要求：USB 成功只产生一次 USB 结果；USB 失败后的临时目录为空且只创建一次后续 Wi‑Fi/P2P/中继任务；P2P 失败后只创建一次 HTTPS 中继任务；用户取消不发生自动回退。

## DSH-066 生产工作区损坏成员记录导致远程设备列表 500（Worker，已修复并部署）

- 现象：当前电脑通过 HTTPS 代理已能访问 Worker，但 `/v1/devices` 返回 HTTP 500，Windows 远程在线轮询无法继续；云端新鲜测试工作区不复现。
- 根因：`listDevices` 假定 `member:` 存储记录始终带完整 `certificate`、签名公钥和协商公钥；历史中断登记或旧版本遗留的损坏记录会抛异常，整页列表失败。
- 修复：遍历成员时校验证书结构；坏记录只写 `invalid_member_skipped` 告警并跳过，保留有效成员的在线、库存、版本和远程权限数据。Worker 已部署版本 `fb0ba8fb-6f15-46f3-b31d-00cbeba36c59`。
- 证据：本地 13 项远程协议测试通过；云端 run `32596690436` 的 remote-relay check 和线上 Worker E2E 全部成功；本机 V4.3.26 通过 HTTPS 代理重启后不再产生新的 `remote_presence_poll_failed`。
- 回归要求：损坏成员不影响有效设备列表；有效手机上线后能被 Windows 轮询发现；远程发送、P2P/HTTPS 回退、ACK 和撤销仍按设备身份工作；后续需真实手机现场验收。

## DSH-067 Windows 远程中继直连 workers.dev TLS 不可达（Windows V4.3.26，已修复）

- 现象：本机直连 Cloudflare `workers.dev` 的 HTTPS 超时，但 Clash Verge 的本地 HTTP CONNECT 代理可正常访问；旧版中控持续记录“中继没有响应”。
- 根因：`RelayHttp` 固定使用 WinHTTP 自动代理；本机 WinHTTP 没有代理设置，未使用已经运行的 Clash 本地端口 `127.0.0.1:7897`。
- 修复：支持 `ZWM_DEVICE_SHARE_RELAY_PROXY` 和 `relay-proxy.txt`；未配置时保持系统自动代理，配置后使用 `WINHTTP_ACCESS_TYPE_NAMED_PROXY`，协议仍是 HTTPS，不降级 HTTP。
- 证据：本机 `curl` 直连失败、经 `127.0.0.1:7897` 的 Worker health 200；云端三端构建、远程中继检查和线上 E2E run `32596690436` 全部通过；V4.3.26 重启后未出现新的远程轮询失败。
- 回归要求：代理不可用时错误可重试、直连可用时不强制代理；HTTPS 证书校验不关闭；真实手机上线后验证设备发现、P2P 直连、HTTPS 回退和 ACK。

## DSH-065 移动端 P2P 完成后未释放 Cloudflare 信令会话（Android 0.6.49 / iPhone 0.6.36，已修复并发布 Beta）

- 现象：移动端 P2P 成功导入并发送 ACK，或失败/取消关闭 PeerConnection 后，认证的 Cloudflare 信令会话仍可能保持；下一轮在线轮询可能再次看到同一会话，造成无意义的重复协商或旧任务重接收。
- 根因：Android/iOS 的统一 `shutdown` 只停止 WebRTC/文件队列，没有同时关闭 `P2PTransport` 信令会话；成功路径和异常路径的资源边界不完整。
- 修复：Android `P2PTransferEngine.shutdown` 与 iOS `P2PTransferEngine.shutdown` 增加 `transport.close()`，让成功、失败、取消都释放信令会话；新增静态不变量断言，版本同步为 Android 0.6.49/versionCode 87、iPhone 0.6.36/build 55、Windows V4.3.25。
- 证据：本地 `verify-p2p-invariants.mjs`、`verify-auto-mobile-update.mjs`、`verify-removed-surfaces.mjs`、13 项远程协议测试和 `git diff --check` 通过；Device Share Hub run `32594831524` 的三端构建、remote-relay check 和线上 Worker E2E 全部成功；Beta `v0.6.49-beta.1` 已发布。
- 回归要求：
  1. P2P 成功写库并 ACK 后，信令会话、PeerConnection、临时缓存和活动引擎都释放，下一轮轮询不重复接收同一会话。
  2. P2P 失败、取消和 20 秒超时都关闭信令会话，并且 Windows 仍只创建一次 HTTPS 回退任务。
  3. Android/iPhone 真实设备跨网传送小作品，确认 DataChannel 成功不创建中继任务；制造直连失败时只创建一个 HTTPS 中继任务，落库后返回 ACK。

## DSH-064 iOS P2P ICE 候选在 SDP 回调边界丢失（iPhone 0.6.35，已修复并发布 Beta）

- 现象：iPhone 的信令轮询和 WebRTC 回调可能同时处理 ICE 候选；候选恰好在远端 SDP 回调排空队列的窗口到达时，可能既没有入队也没有立即提交，P2P 偶发建连失败并转入 HTTPS 中继。
- 根因：`pendingCandidates` 与 `remoteDescriptionSet` 没有共享同步边界；此前 Android 已有 `iceLock`，iOS 仍直接读写。
- 修复：iOS 使用 `NSLock` 将“标记远端 SDP 已就绪、复制并清空待处理候选”和“新候选入队/立即提交”串行化；提交候选放在解锁后执行，避免锁内调用 WebRTC。
- 证据：本地 P2P 不变量脚本、自动更新/库存脚本、隐私表面检查和 13 项中继协议测试通过；云端 run `32593184579` 的三端构建、remote-relay check 和线上 Worker E2E 全部成功；Beta `v0.6.48-beta.1` 已发布。实体 iPhone P2P 验收仍待连接。
- 回归要求：
  1. SDP 成功前后并发到达的 ICE 候选每个都最终提交一次。
  2. P2P 成功 ACK、失败关闭、HTTPS 回退和重复 transferId 去重不受锁影响。
  3. iPhone 真实设备跨网络发送小作品，确认 DataChannel 成功时不创建中继任务；制造直连失败时仍只创建一个 HTTPS 中继任务。

## DSH-063 Beta 已发布但手机更新按钮读不到 Beta 索引（Android/iOS 0.6.47/0.6.34，已修复）

- 现象：Beta 安装包和 Release 已发布，但 Android/iOS 更新器只读取稳定索引；测试机点击“检查更新”时看不到 Beta，iOS 也会复制稳定 AltStore 源。
- 根因：更新通道没有持久化选择，客户端端点固定为 `latest.json`/`altstore.json`，发布 Beta 不会改变稳定入口。
- 修复：设置新增稳定版/测试版通道；Android 测试版读取 `latest-beta.json`，iPhone 测试版使用 `altstore-beta.json`；默认保持稳定版，测试版不污染稳定索引。
- 证据：Android/iOS/Windows 云构建和 remote-relay/Worker E2E run `32590760825` 全部成功；两个公开索引已 API/Raw 复核为 Android 0.6.47/versionCode 85、iPhone 0.6.34/build 53。
- 回归要求：稳定通道仍读稳定索引；测试通道能发现 Beta；Android 下载前校验 SHA-256；iPhone 复制 Beta 源后由 AltStore 更新；切回稳定版后不显示 Beta。真实手机点击和安装仍需现场验收。

## DSH-062 远程在线被旧 Wi-Fi 路由遮蔽（Windows 4.3.23，已修复）

- 现象：同一台手机先被局域网发现、后通过中继在线时，远程心跳刷新了整体 `lastSeen`；发送路径只看非空 IP，可能继续连接已经失效的局域网地址，无法进入 P2P/HTTPS 回退。
- 根因：局域网探测和远程中继共用一份设备快照与时间戳；任一来源直接替换另一来源，无法表达“Wi‑Fi 已过期但远程仍在线”。
- 修复：增加独立 `wifiLastSeen`；局域网 UDP/主动探测与中继记录改为字段合并；发送只把 35 秒内的 Wi‑Fi 观察当作直连，过期时自动使用远程通道。
- 证据：本地源码回归门禁、13 项远程协议测试、隐私表面检查通过；run `32589239907` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.3` 已发布。实体手机仍需现场验证。
- 回归要求：局域网在线优先 Wi‑Fi；局域网过期但中继在线时走 P2P/HTTPS；中继离线且 Wi‑Fi 过期时不发送；两种来源轮询交错时保留库存、凭证和通道状态。

## DSH-061 远程缺失库存覆盖局域网完整库存（Windows 4.3.21）

- 现象：同一台手机同时通过局域网和远程中继在线时，远程心跳没有分类库存字段；RelayLoop 把 `-1`/空版本覆盖了局域网已经拿到的精准库存，自动补货可能漏发。
- 根因：远程设备合并逻辑把“字段缺失”当成了“新值未知”，没有按字段保留更完整的本地来源。
- 修复：只有远程字段带合法值时才覆盖对应本地字段；远程缺字段时保留局域网库存、版本和更新能力。新增源级回归断言。
- 证据：Device Share Hub run `32587368303` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.2` 已发布；实体设备自动补货仍待现场验收。
- 回归要求：局域网精准 4、远程字段缺失时仍按 4 判断；远程精准 5 时不补；旧手机和新手机字段切换不抹掉已有完整库存。

## DSH-060 远程中继在线但没有精准库存，自动补货漏掉远程设备（Windows 4.3.20，已修复）

- 现象：手机通过 Cloudflare 远程心跳在线时，电脑只能看到在线/设备名；远程设备没有局域网 IP，因此右键发送、自动更新候选和精准低于 5 自动补货都被旧的 Wi-Fi/USB 判断排除。
- 根因：手机远程心跳原来发送空 JSON；Durable Object 丢弃心跳内容；Windows RelayLoop 只合并在线状态，且多个发送入口各自重复实现局域网判断。
- 修复：Android/iPhone 心跳上报并持久化合法库存与版本字段；Windows 合并远程库存并统一使用 USB/Wi-Fi/远程三路可用判断。缺失分类库存仍按未知处理，不使用总数替代精准流量。
- 证据：remote-relay 协议测试新增库存字段断言并通过；Device Share Hub run `32586026767` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过；Beta `v0.6.46-beta.1` 已发布。实体手机跨网传送、落库、ACK 和精准低于 5 自动补货仍待现场验收。
- 回归要求：远程设备上报精准 4 时自动补 1 个精准作品；上报精准 5 时不补；只上报总数或旧版没有分类字段时不补；P2P 失败仍回退 HTTPS 中继。

## DSH-059 自动补货只扫作品库第一层导致精准源为空（Windows 4.3.19）

- 现象：当前电脑的精准作品实际位于 `成品库\\微信公众号\\作品集_048[转]` 等子目录；自动补货函数只遍历 `library_path` 第一层，因此找不到任何精准源，即使设备精准库存低于 5 也不会发送。
- 根因：`PickAutoRestockSource` 使用 `directory_iterator`，没有覆盖生产库按平台分桶的目录结构。
- 修复：改用 `recursive_directory_iterator`，继续只接受目录名包含 `[转]` 或 `【转】` 且同时包含图文内容的作品文件夹；泛流量目录仍不可作为替代源。
- 证据：现场库扫描确认存在 `微信公众号\\作品集_048[转]` 等有效精准目录；源码回归门禁新增递归扫描断言；Device Share Hub run `32583765953` 和 Beta `v0.6.45-beta.3` 已通过发布验证；实体设备发送仍待连接。
- 回归要求：递归目录下精准库存低于阈值时可进入自动补货；精准库存未知或达到阈值时不发送；只有泛流量目录时不发送。

## DSH-058 自动补货默认关闭导致自动分发不触发（Windows 4.3.18）

- 现象：当前电脑内容数据库只有目录设置，没有 `auto_restock_enabled`；虽然设备精准库存低于阈值，自动补货逻辑仍因严格要求值为 `1` 而直接返回。
- 根因：设置表没有默认迁移，首次使用或旧数据库升级后，UI 与自动分发逻辑没有共同的默认值。
- 修复：ContentStore 初始化用 `INSERT OR IGNORE` 写入自动更新开启、自动补货开启、精准阈值 5；用户已经明确保存的 `0` 或自定义阈值不会覆盖。当前电脑数据库已同步并留有临时备份。
- 证据：`verify-auto-mobile-update.mjs` 已增加默认配置断言；本地数据库读取确认三个设置值为 `1/1/5`；Device Share Hub run `32581609531` 的 Windows 构建通过，Beta `v0.6.45-beta.2` 已发布；真实手机自动补货仍待连接验收。
- 回归要求：新库默认触发自动分发；用户关闭自动补货后不再触发；精准低于 5 才进入补货，泛流量不能替代精准库存。

## DSH-057 自动手机更新失败后永久抑制重试（Windows 4.3.17）

- 现象：Windows 自动更新在传输开始前就把 auto_mobile_update_sent_<deviceId> 写入持久化设置；如果手机离线、HTTP 接收器超时或传输失败，下一次上线仍会被这个版本记录拦截，无法再次推送。
- 根因：把“尝试发送”当成了“已送达”，且失败路径没有清除持久化标记；现场旧版 Android 设备广播在线但接收端口无响应时会触发这一风险。
- 修复：UploadToDevice 返回明确成功值；自动更新仅在成功返回后写入 auto_mobile_update_delivered_<deviceId>。失败只清除本次内存占位并记录 auto_mobile_update_failed_retryable，保留下一轮重试。
- 同轮增强：Android/iPhone 在线信标追加可选精准、泛、未分类库存字段；Windows 解析尾字段，/v2/info 短时不可用时仍可区分“未知”与“精准为 0”，不把总数冒充精准库存。
- 证据：源级回归脚本 tools/device-share-hub/scripts/verify-auto-mobile-update.mjs 通过；Device Share Hub run `32579462284` 的 Android、iOS、Windows、remote-relay check 和已部署 Worker live E2E 全部通过；Beta `v0.6.45-beta.1` 已发布并完成三包哈希核对。实体设备回归仍待现场执行。
- 回归要求：
  1. 更新传输失败后，同一设备/版本下一次上线仍可再次触发；成功传输后才抑制重复推送。
  2. Android/iPhone 旧版不带尾字段时，Windows 保持未知，不回退到总作品数；新版带尾字段时精准库存按 conversion 读取。
  3. 普通作品传送、P2P/HTTPS 中继、更新包校验和用户确认安装流程不受影响。

## DSH-056 P2P ACK 丢失后的中继回退重复入库（Android 0.6.44 / iPhone 0.6.31）

- 现象：P2P 已经把作品写入手机作品库，但 ACK 在回电脑途中丢失时，Windows 会按设计切换到 HTTPS 中继；Android 原来的中继收件路径没有先检查持久化的 `remoteImportedTransfers`，会再次下载并写入同一批作品。
- 根因：Android 只有 P2P 导入路径检查 `wasRemoteImported`，普通中继路径只在导入后写入标记，没有把“已导入、只补 ACK”作为重试分支。
- 修复：Android 在中继下载前检查持久化 transfer 标记；已导入任务只调用 ACK、清理临时缓存和内存占位，不再创建作品。成功 ACK 后同步移除 `remoteInboxTasks`；Android/iOS 的重复 P2P 完成路径也会释放活动引擎/缓存。
- 证据：本地隐私回归、remote-relay check/typecheck、13 项协议测试和源码不变量检查通过；Device Share Hub run `32575362864`、线上 Worker E2E job `97036844830`、Repository quality `32575362946` 和 Secret scan `32575362919` 全部通过；Beta `v0.6.44-beta.1` 已发布并完成三包哈希核对。真实手机回退实传仍待现场验收。
- 回归要求：
  1. P2P 导入成功但 ACK 丢失后，HTTPS 回退最多补 ACK 一次，作品库不增加重复作品。
  2. 普通中继首次接收仍按“下载 → 校验 → 写库 → ACK”顺序执行，任何失败都保留重试机会且不提前 ACK。
  3. Android/iOS 重复 transferId 不留下活动 P2P 引擎、临时缓存或永久收件占位。

## DSH-055 Windows 中继失败后遗留未完成任务（Windows 4.3.16）

- 现象：Windows 已创建远程中继任务后，如果上传、提交、进度读取或本地文件哈希阶段失败，原实现直接返回失败，R2 临时对象和收件箱任务要等 TTL 才清理；同一个失败 `transferId` 也可能继续干扰后续重试。
- 根因：`SendPlainTransfer` 的异常路径没有统一回收已创建的 transfer；正常提交失败与本地异常没有共享取消闭包。
- 修复：记录当前活动 `transferId`，所有上传/提交/进度/哈希异常先 best-effort 调用 `/cancel`，随后清空输出 ID；`RelayHttp` 构造异常也保持在捕获范围内，避免把失败任务留在云端。
- 证据：Device Share Hub run `32571763937` 的 Windows、Android、iOS、remote-relay 和正式 Worker E2E 全部通过；Beta `v0.6.43-beta.1` 已发布。真实设备断网/中断回归仍待现场执行。
- 回归要求：
  1. 任务创建后任一上传、提交、进度或哈希失败都发送一次取消，并清理 R2/收件箱状态。
  2. 失败返回不保留可误用的 `transferId`，下一次重试可以创建独立任务。
  3. 正常 P2P 成功、P2P 回退 HTTPS、局域网/USB 传送和成功 ACK 不受取消清理影响。

## DSH-054 Android P2P ICE 候选在 SDP 回调竞态中丢失（Android 0.6.42）

- 现象：信令轮询收到 ICE 候选时，如果远端 Description 成功回调正在排空待处理队列，候选可能在排空之后才写入旧队列；该候选不再被提交给 PeerConnection，直连可能失败并回退 HTTPS 中继。
- 根因：poller 与 WebRTC 回调线程分别读写 ArrayList pendingIce，remoteDescriptionSet 的判断和队列排空不是一个原子操作。
- 修复：增加 iceLock；远端 Description 成功时在锁内设置状态、复制并清空队列；新候选在锁内判断状态，未就绪则入队，就绪则在锁外立即提交。
- 证据：源码并发审计；Android 云构建与同一提交的 iOS/Windows/remote-relay run 32565416952 已通过，真实设备跨网操作仍待补齐。
- 回归要求：
  1. Description 成功前后并发到达的 ICE 候选都必须最终提交一次。
  2. P2P 连接失败、取消、ACK 成功和 DataChannel 关闭不重复回调或留下活动引擎。
  3. HTTPS 中继回退、局域网/USB 传送、作品库写入和重复 transferId 去重不受影响。

## DSH-053 Android P2P 共享状态缺少跨线程可见性（Android 0.6.41）

- 现象：P2P 引擎同时由 WebRTC 回调、信令轮询、文件处理队列和连接超时任务访问；在 Java 内存模型下，某个线程可能看不到最新的 `channel`/`finished` 状态，出现活连接误触发超时、已结束连接继续处理或重复清理。
- 根因：这些字段是普通非 `volatile` 引用/布尔值，跨线程读写没有明确的 happens-before 关系。
- 修复：将 `peer`、`channel`、`finished` 和 `remoteDescriptionSet` 声明为 `volatile`；保持已有失败回退、ACK 刷新和活动 map 清理逻辑不变。
- 证据：本地源码审计与远程中继测试；需以同一提交的 Android/iOS/Windows 云构建和真实设备 P2P/回退操作补齐最终证据。
- 回归要求：
  1. 正常 P2P 建连后，20 秒超时任务不会因读取旧 `channel` 状态误触发。
  2. 失败、取消、ACK 成功和 DataChannel 关闭并发发生时，不重复调用监听器或留下活动引擎。
  3. HTTPS 中继回退、局域网/USB 传送、作品库写入和重复 `transferId` 去重不受影响。

## DSH-052 Android Manifest 残留媒体读取权限（Android 0.6.40）

- 现象：自动截图采集、截图观察器、悬浮窗和自动剪切板链路已经删除，但 Manifest 仍声明 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`，与当前隐私边界不一致，可能继续触发厂商权限/风险提示。
- 根因：旧截图/媒体导入阶段的权限声明没有随功能删除一起清理；源码中当前没有对应的媒体读取调用。
- 修复：删除两项 Android 13+ 媒体读取权限；保留仅用于 Android 10 隐藏作品兼容导入的 `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` 声明和用户主动 SAF 文件夹授权。三端版本同步提升。
- 证据：本地 Manifest 与源码静态检查；随后以同一提交的 GitHub Actions Android/iOS/Windows/remote-relay 构建、Release 包 Manifest、权限列表和真实设备安全扫描作为最终证据。
- 回归要求：
  1. Release 合并 Manifest 不包含 `READ_MEDIA_IMAGES`、`READ_MEDIA_VISUAL_USER_SELECTED`、`SYSTEM_ALERT_WINDOW`，也没有截图观察器、悬浮窗组件或自动剪切板读取。
  2. Android 10 用户主动选择作品文件夹后，隐藏作品兼容导入、普通文件/图片接收和清理逻辑不回归；Android 11+ SAF 文件夹流程不回归。
  3. Android/iOS/Windows 云构建、更新索引、Beta 资产和桌面包哈希一致；真实设备安装后复测厂商安全扫描。

## DSH-051 Android P2P 启动失败会残留活动引擎（Android 0.6.39）

- 现象：Android 创建 P2P PeerConnection 失败时，`accept()` 仍返回已经结束的引擎，`OnlineService` 将它放进 `p2pEngines`；同一会话后续轮询会被“已存在”条件跳过，无法重试。
- 根因：Android `accept()` 与 iOS 已有的“启动后若 finished 则返回 nil”保护不一致；取消路径也可能在执行队列已关闭后再次提交任务。
- 修复：Android `accept()` 在启动同步失败时返回 `null`，调用方不缓存空引擎；取消提交增加已关闭队列保护，失败会话由下一轮收件轮询重新接管。
- 证据：GitHub Actions run `32558264352` 的 Android、iOS、Windows、remote-relay 全部通过；安全扫描 run `32558264343`、质量检查 run `32558264394` 通过；Beta 发布页为 `v0.6.39-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. 模拟 PeerConnection 创建失败时，Android 不把已结束引擎放入 `p2pEngines`，下一次轮询可以再次尝试同一会话。
  2. 停止接收服务、P2P 失败和 ACK 后延迟关闭都不得因重复 `cancel` 抛异常。
  3. 正常 P2P、20 秒建连超时、HTTPS 中继回退、局域网/USB 传送和重复 `transferId` 去重保持不变。

## DSH-050 移动端 P2P 收件建连等待无界与 WebRTC 回调阻塞（Android 0.6.38 / iPhone 0.6.25）

- 现象：发送端创建会话后没有真正建立 DataChannel 时，Android/iOS 收件端可能长期等待；Android 直接在 WebRTC 数据回调里执行文件写入和 SHA-256 校验，大文件可能阻塞回调线程。
- 根因：移动端收件端缺少独立的建连超时；Android 没有把 WebRTC 回调收到的 ByteBuffer 与后续文件 I/O 解耦。
- 修复：两端增加 20 秒建连超时，超时关闭 P2P 并回退 HTTPS 中继；Android 立即复制 WebRTC 所有权的数据帧，再交给串行队列执行写入、大小校验、SHA-256 和作品库导入。
- 证据：GitHub Actions run `32556181728` 的 Android、iOS、Windows、remote-relay 全部通过；安全扫描 run `32556181727`、质量检查 run `32556181732` 通过；Beta 发布页为 `v0.6.38-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. 发送端不发起 DataChannel 时，Android/iOS 最迟 20 秒结束 P2P，并由 Windows 只创建一个 HTTPS 中继任务。
  2. 大文件连续分片传输时，WebRTC 回调线程不执行阻塞文件 I/O；manifest、大小、SHA-256、作品库写入和 ACK 顺序保持不变。
  3. 正常慢速 P2P、局域网/USB 传送、重复 `transferId` 去重和失败会话关闭不受影响。

## DSH-049 移动端 P2P 失败未及时关闭信令会话（Android 0.6.37 / iPhone 0.6.24）

- 现象：Android/iOS P2P 接收失败时会清理本地引擎，但 Cloudflare 控制面会话仍保持开放，直到 2 分钟 TTL 才回收；发送端虽然会回退 HTTPS，中间会留下脏会话。
- 根因：移动端 `SignalTransport` 只有快照和发信令接口，失败回调没有 `/close` 动作；同步关闭还可能阻塞 WebRTC 失败处理线程。
- 修复：Android/iOS 为 `SignalTransport` 增加异步 `close`；P2P 失败路径立即排队关闭对应会话，保留本地清理和 HTTPS 回退，不阻塞失败回调。
- 证据：GitHub Actions run `32553627094` 的 Android、iOS、Windows、remote-relay、质量检查和安全检查全部通过；Beta 发布页为 `v0.6.37-beta.1`。实体手机业务回归仍未完成。
- 回归要求：
  1. Android/iOS 在 manifest、数据帧、校验或作品库写入失败后，控制面会话进入 `closed`，不能只等 TTL。
  2. 关闭请求不得阻塞 WebRTC 失败回调；HTTPS 回退仍只创建一个任务。
  3. 成功 ACK、局域网/USB 传送和正常慢速 P2P 不受影响。

## DSH-048 P2P 异常回退留下开放信令会话（Windows 4.3.9）

- 现象：Windows 已创建 P2P 会话后，如果文件校验、信令或 DataChannel 发送路径抛出异常，外层回退逻辑会继续创建 HTTPS 中继任务，但原 P2P 会话只能等 2 分钟 TTL 才清理。
- 根因：正常返回路径有 `/close`，异常直接跳到外层 `catch`，没有覆盖“会话已创建、传输尚未正常返回”的范围。
- 修复：在 `TryP2PTransfer` 创建会话后建立统一 `closeSession` 清理闭包；成功、P2P 返回失败和内部异常都先关闭会话，再把失败交给上层 HTTPS 中继回退。
- 证据：GitHub Actions run `32551407594` 的 Windows portable、Android、iOS、remote-relay、validate 和两项 secret scan 全部通过；Beta Windows 资产 SHA-256 为 `544a823df94ce0232e98a96e5cc4a1b1ad7398508ff3dcf049ab93b6b12df169`。
- 回归要求：
  1. 文件哈希、信令和 DataChannel 任一阶段抛异常时，控制面会话都进入 `closed`，不能只等 TTL。
  2. P2P 失败后只创建一个 HTTPS 中继任务，不能因为旧会话残留产生重复发送。
  3. 正常 P2P ACK 和已有局域网/USB 传送不受清理闭包影响。

## DSH-047 P2P 成功尾部 ACK 竞态与背压无限等待（Windows 4.3.9 / Android 0.6.36 / iPhone 0.6.23）

- 现象：手机导入成功后立即关闭 DataChannel，电脑可能在收到 ACK 前看到通道关闭并重复走 HTTPS 中继；iPhone ZIP 导入成功后 P2P 缓存目录可能残留；Windows 发送侧背压长期不下降时没有硬性回退边界。
- 根因：`sendData`/`DataChannel.send` 只把 ACK 放入 SCTP 队列，紧接着关闭 peer 不能证明字节已经发出；成功路径没有统一清理 iOS 缓存；背压循环只看缓冲量，没有停滞计时。
- 修复：Android/iOS ACK 后等待 500ms 再关闭；iOS 成功路径删除临时目录；Windows 记录背压下降时间，连续 20 秒无进展即返回失败，由上层自动切 HTTPS 中继。
- 证据：同一提交的 GitHub Actions Android/iOS/Windows 构建、remote-relay、validate 和 secret scan 已通过；当前没有连接实体手机，因此实体传输回归仍未完成。
- 回归要求：
  1. 小 ZIP 成功直传后电脑只收到一次成功 ACK，不创建中继重复任务。
  2. iPhone 成功导入 ZIP 后，P2P 缓存目录在 ACK 刷新窗口后消失。
  3. 模拟接收端不读数据时，Windows 最迟 20 秒退出 P2P 并自动进入 HTTPS 中继。
  4. 正常慢速传输只要缓冲持续下降，不得被 20 秒停滞保护误切换。

## DSH-046 远程传送只有信令没有文件数据面（Windows 4.3.8 / Android 0.6.35 / iPhone 0.6.22）

- 现象：Cloudflare 的 P2P 会话可以创建并交换 SDP/ICE，但此前没有真正的 DataChannel 文件收发；如果误把信令成功当成文件成功，会出现“已直连但手机没有作品”。
- 根因：远程中继控制面和 WebRTC 信令已先完成，三端原生 DataChannel 没有接入同一套 manifest、分片、完整性校验和 ACK 协议。
- 修复：Windows 接入 libdatachannel 发起 `album-transfer-v1`；Android 接入 Maven Central WebRTC SDK；iPhone 接入固定版本 WebRTC XCFramework。发送端按 48 KiB 分片，接收端落缓存、校验大小/SHA-256、写入现有作品库后才 ACK。
- 回退：建连 20 秒超时、ICE/DataChannel 失败、数据帧越界、哈希不一致或作品库写入失败均返回失败，由 Windows 自动创建现有 `mode: plain` HTTPS 中继任务；P2P 失败不能记作已送达。
- 证据：本地协议检查通过，远程 Worker 13 项测试通过；三端正式结论必须等待同一提交的 GitHub Actions Windows/Android/iOS 构建。暂无实体 Android/iPhone 跨网络业务证据。
- 回归要求：
  1. 同一 Wi-Fi 与不同网络各发送一个小 ZIP，手机只能在写库成功后收到 ACK。
  2. 接收端断开、篡改分片或制造错误 SHA-256 时，P2P 失败并自动转 HTTPS 中继，作品不重复。
  3. 发送端不能因为 `libdatachannel` 的缓冲返回值为 false 就误判失败；必须等待缓冲并保持分片顺序。
  4. 停止 Android/iOS 接收服务时，P2P 临时文件和轮询线程都要清理，旧局域网/USB 传送不受影响。

## DSH-045 手机端自动截图/剪切板与作品卡片入口收口（Android 0.6.24 / iPhone 0.6.11）

- 现象：手机端不需要自动截图、自动剪切板同步；旧入口和权限让安全扫描产生风险提示，作品卡片的两个纵向按钮也占用过多空间。
- 修复：两端删除自动截图采集、截图中转和自动读取/同步系统剪切板；卡片统一为“预览 / 发抖音 / 发小红书”，预览支持多图翻页。
- 保留边界：用户主动点“复制文案”或“复制诊断信息”时才写入系统剪切板；普通文件/图片传输、导入和系统分享不变。
- 回归要求：云端检查无 `ClipboardBridge`/自动监听、无截图观察器、无 `SYSTEM_ALERT_WINDOW`/`NSPhotoLibraryUsageDescription`；Android/iOS 构建、Release 包结构和两端更新索引一致，最后补真实手机安全扫描与多图点击验收。

## DSH-044 手机端剪切板/截图模块触发权限与风险提示（Android 0.6.23 修复候选）

- 现象：手机端出现悬浮剪切板、自动发送到主设备和截图相关入口；用户怀疑系统持续读取剪切板或悬浮窗导致病毒/隐私风险提示。
- 根因：`OnlineService` 启动和发现设备时维护悬浮窗，读取/写回系统剪切板并注册 `MediaStore` 截图观察器；Manifest 声明 `SYSTEM_ALERT_WINDOW`，还存在截图接收器和剪切板同步接口。
- 修复：删除整套剪切板、截图、悬浮窗和中继代码、设置入口、组件及 `SYSTEM_ALERT_WINDOW`；普通文件/图片传输保留。用户主动“复制并分享”和“复制诊断信息”仍是明确动作，不属于后台自动读取。
- 证据：源码搜索确认后台自动路径已移除；需以 Android 单测、Release/Lint、合并 Manifest 和真机安全扫描结果完成最终验收。
- 回归要求：
  1. Release Manifest 不包含 `SYSTEM_ALERT_WINDOW`、`ClipboardActivity` 或 `ScreenshotSendReceiver`。
  2. 服务启动、设备发现、作品刷新均不访问系统剪切板、不注册截图观察器。
  3. 普通文件/图片传输、作品导入、更新包接收和显式复制分享仍正常。
  4. 真实设备安装后重新执行厂商安全扫描；不能仅凭源码或构建通过宣称“病毒提示已彻底消失”。

## DSH-043 大文件失败后从头传输（Android 0.6.21 修复）

- 现象：电脑向手机发送大文件时网络中断，下一次重试会重新创建任务并从 0 字节开始；重复失败会重复消耗时间和流量。
- 根因：电脑端失败分支直接调用 `/cancel`，手机随即删除 `.receiving` 临时文件；协议没有任务状态查询、已收字节和偏移上传字段。
- 修复：电脑端为同一内容复用稳定 `taskId`，失败后保留可恢复账本；Android 接收端落盘 `task.json`，提供 `GET /v2/tasks/{taskId}`，按 `X-File-Offset` 追加剩余数据，并对合并结果做 SHA-256 校验后再提交。旧接收端继续兼容从头传输。
- 证据：Python 传输技能 32 项单测、语法检查通过；Android Gradle 9.4.1 单测通过，新增偏移范围和断点续传回归。VIVO 真机安装 0.6.21 后仍需补受控中断/续传证据。
- 回归要求：
  1. 大文件上传到中途断开后，手机任务和 `.receiving` 文件保留。
  2. 重试必须先读取 `receivedBytes`，上传请求的 `Content-Length` 等于完整文件大小减去偏移。
  3. 偏移、总长度或 SHA-256 不匹配时拒绝续传，不得提交半份文件。
  4. 完整提交后只能出现一份目标文件；明确取消任务后才清理断点。

## DSH-042 批量发送、传送队列与暗色模式（V4.3.0 修复）

- 现象：DSH-041 遗留的 8 项未修复体验问题中，用户要求一次性完成剩余全部功能。
- 根因：前期迭代聚焦核心传送功能，批量操作、队列管理和视觉个性化未纳入优先级。
- 修复（commit `39eb5f6`，V4.3.0）：
  1. 设备列表从单选改为多选（`LBS_MULTIPLESEL`），用户可点选多台设备。
  2. 新增 `GetSelectedDevices()` 收集所有选中设备，`SendSelectedLibraryItem` 和 `ChooseAndSend` 均支持批量发送。
  3. 批量发送在后台线程顺序执行，状态栏显示"批量传送 N/M：正在发送到XXX"。
  4. 新增"全选设备"按钮，一键选中所有在线设备。
  5. 设备列表刷新时通过 `selectedIds` 集合保持多选状态，不会因刷新丢失选择。
  6. 系统托盘通知（`ShowTrayBalloon`）在 V4.2.2 已实现，传送完成/失败/最小化时弹出气泡。
  7. 素材库搜索和文件大小显示在 V4.2.2 已实现。
  8. 开机自启在 V4.2.2 已实现。
  9. SHA-256 异步进度在 V4.2.2 已实现。
  10. 暗色模式：新增 `ThemeColors` 结构体和亮/暗两套配色，`WM_CTLCOLOR*` 处理静态/列表/编辑控件着色，偏好持久化到内容数据库。
- 证据：commit `39eb5f6` 已推送至 `origin/main`，CI 构建待完成。
- 回归要求：
  1. 多选设备后点"传送选中素材"，每台设备顺序收到文件。
  2. "全选设备"按钮选中所有设备。
  3. 设备列表刷新后已选设备保持选中。
  4. 暗色模式切换后窗口背景、列表、按钮、文字颜色全部正确。
  5. 暗色模式偏好重启后保持。
  6. 单选单发场景不受影响。

## DSH-041 Windows 用户体验问题批次（V4.2.2 修复）

- 现象：用户日常使用角度审查发现 13 项体验问题，其中高影响 5 项、中低影响 8 项。
- 根因：开发阶段只关注技术功能实现（设备发现、文件传送、协议），未在提交前执行用户角度审视清单，导致交互时效、信息可读性和容错体验被忽略。
- 修复（commit `6b86766`，V4.2.2）：
  1. `DEVICE_RETENTION_SECONDS` 从 600 降为 90，离线设备 90 秒后移除，不再让用户对着已断连设备操作。
  2. 右键"发送到"无设备时等待从 20 秒降为 8 秒，超时后提示"请确认手机相册已打开或允许后台接收后重试"。
  3. HTTP 409 错误从技术码改为"手机上还有上一批素材没处理完，请在手机端确认后再试"。
  4. 拖文件到空白区域从 MessageBox 弹窗改为状态栏温和提示"请把文件拖到右侧某台在线手机的卡片上"。
  5. Device 结构体新增 `lastSentTime` 字段，`RecordSuccessfulTransfers` 传送成功时写入时间戳，`DrawDeviceItem` 在设备卡片副标题显示"刚刚传过 / X分钟前传过 / X小时前传过"。
  6. AGENTS.md 新增"从用户角度审视"强制清单，覆盖交互时效、操作步骤、信息可读性、容错恢复和日常体验 5 个维度。
- 证据：commit `6b86766` 已推送至 `origin/main`，CI 构建待完成。
- 回归要求：
  1. 设备离线后 90 秒内从列表消失。
  2. 右键无设备时 8 秒内给出提示。
  3. 409 错误显示人话提示。
  4. 拖文件到空白区域不弹窗，状态栏显示引导文字。
  5. 传送成功后设备卡片副标题显示相对时间。
  6. 每次改代码前必须过一遍"从用户角度审视"清单。
- 未修复项：已在 V4.3.0（DSH-042）中全部修复。

## DSH-040 Windows 热点/非 /24 子网下设备发现失败

- 现象：手机与电脑同处一个 WiFi（或手机开热点、电脑连热点），手机之间能互相发现，但 Windows 中控扫描不到任何在线设备，设备列表和“发送到”菜单均为空。
- 根因：
  1. `ActiveProbeTargets()` 硬编码按 `/24` 子网扫描，当实际子网掩码不是 `/24`（如手机热点通常是 `/28` 或 `/30`，企业网络可能是 `/16`）时，目标 IP 范围计算错误，遗漏真实设备 IP。
  2. 原逻辑要求网卡必须存在默认网关才参与扫描，手机热点模式下电脑侧可能没有传统网关或网关检测失败，导致 0 个扫描目标。
  3. WSL、VMware、Hyper-V 等虚拟网卡的地址也被纳入扫描范围，浪费探测时间且可能命中无关地址。
  4. 未查询 ARP 表，遗漏了系统已知的邻居设备（手机刚通信过但不在子网扫描范围内的 IP）。
  5. `ProbeDeviceHost()` 连接超时仅 400ms、收发超时仅 800ms，热点中转链路延迟较高时无法在超时内完成握手。
- 修复（commit `a981d1f`，V4.2.1 重建）：
  1. `ActiveProbeTargets()` 改用 `OnLinkPrefixLength` 计算实际子网范围，`/24` 以下按 `/24` 扫描、`/24` 以上按实际掩码扫描；移除网关依赖，所有 Up 状态的物理网卡私有地址段均参与扫描。
  2. 新增 `IsVirtualAdapter()` 过滤 WSL/VMware/Hyper-V/VirtualBox/TAP/VPN 等虚拟网卡，`DiscoveryTargets()` 和 `ActiveProbeTargets()` 均调用。
  3. 新增 `AddArpTableTargets()` 调用 `GetIpNetTable2` 查询 ARP 表中 Reachable/Stale/Delay/Probe 状态的邻居 IP，补充到主动探测目标列表。
  4. `ProbeDeviceHost()` 连接超时从 400ms 提升到 800ms，收发超时从 800ms 提升到 1500ms。
  5. `DiscoveryTargets()` 同步增加虚拟网卡过滤。
- 证据：CI 构建 `windows-portable` job 通过，产物 `素材投送中控-Windows-V4.2.1.exe`（852480 字节）已替换旧版（848896 字节）并成功启动（PID=16212）。commit `a981d1f` 已推送至 `origin/main`。
- 回归要求：
  1. 标准 `/24` WiFi 子网下设备发现数量不减少（与 DSH-038 修复前持平或更好）。
  2. 手机开热点、电脑连热点的 `/28` 或 `/30` 子网下能发现手机设备。
  3. WSL/VMware 虚拟网卡不参与扫描，不产生无关探测。
  4. ARP 表中有但子网扫描范围外的设备 IP 能被探测到。
  5. 热点中转高延迟环境下 800ms 连接超时 + 1500ms 收发超时能完成握手。
  6. “发送到 → 相册在线设备” 右键菜单在有设备时正常弹出设备列表。

## DSH-039 Windows“发送到”子文件夹误触发文件复制

- 现象：用户右键选择“发送到 → 相册在线设备”后，Windows 显示正在把所选内容复制到另一个文件夹，没有弹出设备选择；重复操作会持续占用系统盘空间。
- 根因：Windows 的 `SendTo` 目录不支持用普通子文件夹实现级联设备菜单；该子文件夹本身会被 Shell 当作复制目标。
- 修复：V4.2.1 只在 `SendTo` 根目录创建“发送到相册设备.lnk”，参数不预选设备；中控收到原路径后弹出实时在线设备列表，选定后调用既有上传协议。旧目录只清理自有 marker/快捷方式，未知内容不自动删除。
- 实体证据：误复制副本为 45,646,452 字节，与原件 SHA-256 `751BE9E72B019D180AFFD374B0778097183BD140DA84C1B31A0B2E20A126C42D` 一致；副本已移入回收站，原件仍在。V4.2.1 根快捷方式已验证不再把文件写入 `SendTo`；90 字节测试文件通过当前 USB Xiaomi 15 发送、手机回读，源与回读 SHA-256 均为 `D2EB3651251EE5BE612C596B2231FCCA181C321C3194ACD8B385E3E6F83A8EFC`。电脑端真实完成状态已显示 100% 和成功结果；新版浮层改为短文字提示，不保留常驻进度小窗。
- 回归：右键入口不得是目录；点击必须先展示设备选择；取消不得发送；没有设备应扫描并提示；成功后 `SendTo` 不得出现用户文件；文件和文件夹传送临时缓存必须按既有规则清理。
- 二次候选检查：选择菜单不能阻塞 `WM_COPYDATA` 的 5 秒转交窗口；主进程必须先确认接收请求，再异步弹设备菜单，防止用户仍在选择时辅助进程误报失败。
- 三次实体反馈：右键纯发送不得调用 `CheckTransferHistory`，否则大文件夹会长时间停在“正在核对是否传送过”且尚未创建传送任务。新版跳过历史指纹筛选，立即进入 USB/V2 发送，并在主窗口显示进度、结束时弹结果。

## DSH-038 手机在线但 Windows 新启动后设备列表和“发送到”菜单为空

- 现象：同一局域网内至少一台手机接收端在线，共享传送技能通过主动探测可以找到，Windows V4.2.0 首轮候选只依赖 UDP 时却记录 `online=0`，资源管理器中不生成“相册在线设备”。
- 根因：部分路由器会抑制 Wi‑Fi 广播回复；首轮主动探测又漏传 `GAA_FLAG_INCLUDE_GATEWAYS`，导致要求默认网关的筛选拿不到网关数据并生成 0 个目标。补齐目标后，WinHTTP 多地址并发在实体局域网仍出现漏检。
- 修复：保留 UDP 即时发现，同时读取默认网关所在私有 `/24`，使用带 400ms 连接截止时间的原生局域网 socket 并发请求 `/v2/info`；只接受 protocol 2，每 15 秒刷新并把 35 秒内确认的设备同步到“发送到”菜单。没有依赖 Python、ADB、手工 IP 或管理员权限。
- 证据：修复前共享技能真实发现在线手机而 Windows 日志为 `send_to_synced online=0`；最终候选记录 `targets=253 devices=1`，创建真实设备 `1号｜公司｜红米13.lnk`。合成接收端验证资源管理器参数、单实例 IPC、35 字节文件传送和 commit，源/接收 SHA-256 均为 `FA795428874E4CAFE1860A706827964F3C214E6302658A4CEF6F8DBE9B3F7C1B`；合成端停止超过 35 秒后快捷方式自动移除，真实在线设备保留。
- 回归要求：广播正常时不得产生重复设备；广播受限时主动探测应在一次周期内补齐；不扫描无默认网关的 WSL/VMware 链路；离线设备从快捷菜单移除，但 USB 仍可用时保留。

## DSH-037 USB 连接后电脑能看到手机、手机看不到电脑

- 现象：手机连接数据线后，Windows 中控能枚举手机，Android 传送页却没有电脑。
- 环境证据：连接时手机与电脑处于不同 IPv4 子网，Windows 仅有手机 USB/调试设备，没有 RNDIS/NCM USB 网卡。Android 0.6.13 的设备发现因此没有任何可达电脑地址。
- 根因：MTP 是 Windows 主机主动访问手机存储，不会让手机 App 获得一个可写的 Windows 文件目标；手机端原界面又把所有可达通道固定标成 WiFi，没有解释或选择 USB 网络共享。
- 修复：0.6.14 传送页增加自动/USB/Wi‑Fi 选择、USB 网络共享设置入口与通道标签；发现逻辑按远端地址匹配本机接口子网，并把 `rndis/usb/ncm/tether` 接口识别为 USB。真正传送继续使用 V2 HTTP、进度、SHA-256 和 commit 回执。
- 安全边界：不使用 ADB、Root、手动 IP、配对码或自定义 Windows 驱动。普通 MTP 保持电脑→手机；手机→电脑使用用户明确开启的系统 USB 网络共享。
- 实体证据：同一手机覆盖 0.6.14/code 52 后，USB 网络共享建立 `rndis0` 与 Windows Remote NDIS 同子网；手机列表显示电脑 `USB`。48 字节测试文件分别完成手机→电脑、电脑→手机 commit，两个接收端 SHA-256 均为源哈希，测试副本已清理。
- 回归：连接数据线但未开 USB 网络共享时，选择 USB 应显示可行动说明；开启后 Windows 出现 USB 网卡，手机列表显示电脑 `USB`；双向分别传一个测试文件并核对字节与 SHA-256，关闭共享后 USB 设备自然离线，Wi‑Fi 设备不应被误标。

## DSH-036 Android 下载后自动安装页被系统拦截

- 最终产品取舍（0.6.14）：用户明确不要“允许来自此来源”授权，接受由系统下载器和系统通知完成安装。0.6.14 因此删除应用自有下载服务、私有 APK 安装 Activity 与 `REQUEST_INSTALL_PACKAGES`，恢复 DownloadManager；应用只在系统完成广播后按任务 ID 与 SHA-256 核对。
- 兼容边界：此前 Redmi Note 8 的 MIUI/迅雷内核曾对 GitHub 下载返回状态 700，所以系统下载不承诺在所有厂商网络下一次成功；失败时由系统界面重试或改用公开 APK。该边界是用户知情后的权限优先取舍，不再通过增加安装来源权限规避。

- 现象：应用自动下载并验证更新包后尝试直接进入安装器，但部分 Android/MIUI 设备禁止由下载完成广播自动拉起界面，用户只看到下载完成却没有安装页。
- 根因：普通应用的后台 Activity 启动受到 Android 与厂商系统限制；即使进程可见性判断通过，下载完成广播也不等于一次用户授权的界面跳转。
- 深层兼容根因：真机公网回归进一步发现下载接收器声明为 `exported=false`，MIUI 把系统下载服务视为外部 UID，直接拒绝投递受保护的 `DOWNLOAD_COMPLETE` 广播；因此文件即使下完，应用也无法进入校验和安装提醒。
- 下载根因：接收器修好后，Redmi Note 8 的 MIUI 系统下载器仍把 GitHub 请求交给迅雷内核，日志返回 `ResponseCode=150 / status=700` 并取消任务；不是 APK 内容或系统安装器解析失败，而是系统下载阶段没有获得完整文件。
- 修复：打开应用仍自动检查，但发现新版先由用户确认“下载更新”；确认后由应用前台下载服务执行 HTTPS 下载、有限跳转、断点续传和常驻进度。校验完成后在首页弹出版本、APK 文件名和大小，并由用户点击“安装”。应用不在首页时保留通知，下次返回再次提示；不再强行自动启动安装器。
- 首轮实体证据：已安装修正版 0.6.11 的 Redmi Note 8 从公网发现 0.6.12，确认后没有再出现 MIUI/迅雷提示；应用通知持续显示进度，约 24 秒后完成下载。随后 MIUI `PackageManager` 对可解析、v2 签名有效的 APK 返回空新版签名字段，校验器误报“安装包没有签名”；同时移动网络访问 raw 更新索引多次超时。
- 二次修复：0.6.13 改用 GitHub Release API 并精确读取同一 Release 的 `SHA256SUMS.txt`；签名校验同时请求新旧字段，新版字段为空时回退 `PackageInfo.signatures`。设备随后从 ADB 断开，二次真机校验与点击安装页待重新连接，不提前写为通过。
- 回归要求：拒绝下载不得创建任务；确认后只能出现系统下载任务和系统通知；正式 APK 不得包含 `REQUEST_INSTALL_PACKAGES`、应用自有下载服务或安装 Activity；文件名必须带 `.apk`；后台文件传送不得因更新流程修改而停止。

## DSH-035 Android 分类遮字、下拉无状态及厂商开关变形

- 现象：Redmi Note 8 首页文件夹入口和其余操作挤在右侧；分类选中白块盖住黑字；下拉只有阻尼位移，没有释放阈值、旋转加载和完成反馈；设置中的系统 Switch 在 MIUI 上滑块超过轨道，视觉像被截断；自动发送截图也没有目标设备选择入口。
- 根因：标题行先放了占满剩余宽度的标题再放模式按钮；分类指示块绘制层级高于文字且宽度未扣除内边距；滚动容器只暴露完成回调；Android 原生 Switch 会继承厂商尺寸和绘制规则；截图目标偏好已有协议字段但设置页没有可操作入口。
- 修复：模式入口提前到标题行左侧；分类文字层提升并按可用内宽四等分；下拉状态机暴露拉动、触发、刷新与复位；开关使用应用自行绘制的完整胶囊和 180ms 动画；主设备选择只列可信设备并显示在线、离线、未选择。
- 证据：Android 21 个测试套件共 65 项单元测试、Release 构建和 Release Lint 通过；Redmi Note 8 实际覆盖安装 0.6.8/code 46，首页、分类切换、完整下拉刷新、设置开关和无在线设备的主设备选择弹窗均完成 ADB 操作与截图/录屏检查。
- 回归要求：在 320/360dp、系统大字体、横竖屏和至少两个厂商系统检查开关轨道完整；拉动不足阈值必须复位，超过阈值只刷新一次并显示真实结果；分类滑块不得遮住任何标签；自动发送启用但无目标时必须要求选择，不能静默假开启。

## DSH-034 红米应用内下载后提示“解析包错误”

- 现象：红米 Note 上从 Android 0.6.3 的应用内更新入口下载新版后，系统安装器提示解析包错误；云端原 0.6.3 APK 的大小、包名、最低系统版本和 v2 签名本身可解析。
- 根因判断：故障更符合下载副本未完整写入、厂商下载器没有保留 `.apk` 扩展名，或下载完成通知在应用完成校验前先把半成品交给安装器；当前没有连接故障手机取得系统安装器错误码，因此不把推断写成已证实的唯一根因。
- 修复：目标文件名统一为纯英文 `album-Android-<version>.apk`；系统下载完成后先核对 SHA-256，再复制到应用更新目录并检查最小大小、APK 可解析性、包名、版本号、版本码和签名证书。验证完成前不发布可安装通知，验证失败删除下载任务并允许重下；打开安装器失败时提供系统下载列表兼容入口。
- 自动证据：0.6.4/code 42 的 64 项 Android 单元测试、Release 构建与 Release Lint 全部通过；候选 APK 可被 Android build-tools 解析，包名 `com.zwm.gallery`、versionCode 42、versionName 0.6.4，v2 签名证书与既有升级链一致。
- 实体回归：红米先安装公开 0.6.3，通过启动自动更新完整走到 0.6.4；下载中途断网后恢复，核对只有一个英文 `.apk`、校验完成后才出现安装入口、覆盖安装保留作品和设置。若仍失败，保存应用诊断中的下载/校验阶段和系统安装器返回原因。

## DSH-033 长剪切撑满悬浮窗并截断固定常用语

- 现象：最新剪切是一大段文字时，内容按钮按完整高度展开，把下方固定常用语挤出悬浮窗口；固定话术自己的内层滚动区域拿不到足够高度，用户下滑也无法到达。
- 根因：最新剪切位于滚动区域之外且没有最大行数，固定话术又单独嵌套一个 ScrollView，父子高度和触摸手势互相竞争。
- 修复：长剪切默认显示 3 行并省略，独立“展开/收起”按钮固定在最新剪切标题行；最新剪切与固定话术改为同一个 ScrollView，普通 ClipboardActivity 同步移除双层嵌套滚动。新内容到达时自动收起。
- 自动证据：新增短文本、3 行、4 行、141 字符折叠边界，以及悬浮球临时关闭截止时间和溢出保护测试；Android 62 项单元测试、Release 构建与 Release Lint 已全部通过。
- 实体回归：分别使用 1 行、3 行、4 行、超长单段和大量换行文本，在默认尺寸与最小悬浮窗尺寸检查省略号、展开、收起、复制，以及下滑到最后一条固定话术。
- 悬浮球实体回归：静止长按不足 5 秒应正常打开，满 5 秒弹出时长；拖动超过阈值不得误弹。分别验证 30 秒、5 分钟、1 天到期恢复，永久关闭后从设置重新开启。

## DSH-032 悬浮剪切板重连漏同步、并发分叉与更新包重复下载

- 现象：手机离线后以原 IP 重新上线可能收不到离线期间修改的话术；点击固定话术只复制不即时同步；接收线程与悬浮窗同时写入时偶发覆盖；旧版 App 在新版 APK 已下载但尚未安装时再次打开，可能重复下载同一版本。
- 根因：设备重现只比较展示字段，忽略上次在线时间；剪切点击没有进入最新值同步链；存储锁绑定单个临时对象而不是共享目录；相同毫秒的冲突缺少确定次序；下载完成后过早清空版本状态。
- 修复：把超过 15 秒后重新出现视为重连并主动补齐；复制话术立即保存和广播；同一进程内所有剪切板存储实例使用共享锁，等时间戳按删除状态和内容确定收敛；剪切板只保留最新物理记录；DownloadManager 的进行中和已完成状态持续跟踪到覆盖安装后的首次启动。
- 自动证据：新增同时间戳双端收敛、最新值物理压缩、跨实例 40 次并发写入和下载状态回归；Android 58 项单元测试、Release 编译与 Release Lint 全部通过。
- 实体回归：两台手机至少离线 20 秒后重新上线，核对话术补齐；在其他 App 复制文字后点悬浮圆点，核对顶部最新值；点固定话术，核对另一台在线手机立即更新；下载 0.6.2 后未安装前重开旧 App，系统下载列表只能有一个同版本 APK。

## DSH-031 普通文件传送误触发作品分享

- 现象：平铺图片到达 Android 后会直接打开发布分享页，用户原本只想像文件夹作品一样存到手机。
- 根因：V2 任务没有表达“落盘”与“直接分享”的意图，接收端只要看到图片就自动分享。
- 修复：任务新增缺省为 `false` 的 `autoShare`；普通文件、文件夹和截图始终落入真实接收文件夹，只有 Android 系统分享目标收到图片和文字时明确设置为 `true`。
- 证据：Android 0.6.0 单元测试、Debug/Release 编译和 Lint；真机系统分享与多设备接收待设备重新连接后逐台补验。
- 回归要求：旧客户端不带字段时只能落盘；ZIP/文件夹、纯图片、截图不得自动分享；图片加文字的系统分享可进入分享准备。

## DSH-030 Windows SDK 缺少 SQLite 头文件及本地 R2 下载瞬态失败

- 现象：Windows 云构建最初找不到 `winsqlite3.h`；同批远程 HTTP 闭环在 R2 已上传并提交后，立即下载偶发返回一次 HTTP 500。
- 根因：部分 Windows SDK 包含系统 `winsqlite3.lib/dll`，但不安装开发头文件；Wrangler 本地 R2/DO 运行时启动后的首次跨绑定下载存在瞬态失败，正式协议与纯测试不受影响。
- 修复：项目仅声明实际使用的少量系统 SQLite C 接口，继续链接 Windows 自带运行库，不额外捆绑 DLL；HTTP 烟雾测试只对下载阶段的 500 做 5 次、每次 200ms 的有限重试，其他状态不重试。
- 证据：第二备用 run `30269352290` 的 Windows 构建、SQLite 测试和真实 Worker/DO/R2 HTTP 闭环通过。
- 回归要求：不得因缺头文件改为下载来源不明的 SQLite DLL；远程重试必须有次数和时间上限，持续 500 仍需失败并输出 Worker 日志。

## DSH-018 Durable Object 升级后纯测试通过、真实 R2 下载返回 500

- 现象：0.1.1 的语法、类型、10 项纯协议测试和 Wrangler dry-run 都通过，但第二备用 CI run `30265632265` 在真实启动 Worker 后，接收端下载 R2 对象返回 HTTP 500；同一 run 的三端应用构建不受影响。
- 根因：为接入当前 `getByName()` 路由时仍导出旧式普通 Durable Object 类，Node 测试无法直接加载 `cloudflare:workers`，导致运行时入口与可测试核心没有正确分层。
- 修复：`src/index.js` 只保留 Cloudflare 运行时包装类并正式继承 `DurableObject`；协议状态机移入 `src/relay-core.js`，Node 测试直接使用核心类；CI 的 HTTP 失败会自动输出 Worker 日志。
- 证据：本地 10 项协议测试、类型检查与 dry-run 通过；第二备用 CI run `30266289195` 实际启动 Worker、Durable Object 和 R2，完成上传、下载、ACK 删除，远程任务成功；Windows、Android、iPhone 同 run 全部成功。
- 回归要求：不得为了让 Node 直接导入入口而移除 `DurableObject` 基类；每次修改远程运行时必须同时保留纯协议测试和 Linux Worker/DO/R2 HTTP 闭环。

## DSH-015 系统文件管理器删除后仍显示私有“幽灵作品”

- 现象：外部 `Download/Lark` 已删除作品，首页仍可预览、复制并分享；外部回收站为空，App 回收站仍有旧副本。
- 根因：SAF 导入为保证分享稳定会保存 App 私有图片副本，旧逻辑只增量导入、不核对来源是否仍存在；MIUI 文件服务还可能短暂返回空目录或保留失效索引行，使整批扫描中断。
- 修复：每次刷新核对来源文档 ID；单项失效不阻断整批；第一次缺失只记录，至少 2 秒后再次查询仍缺失才移除私有副本；没有外部来源 ID 的网络接收作品不参与删除。
- 证据：红米 K60 外部目录实际 24 个作品、0 个回收项；升级前私有库 26/2，覆盖后日志为 `sourceRemoved=2 trashRemoved=2`，界面恢复 24/0。
- 回归要求：模拟文件服务首次返回空列表时必须保留有效作品；手动删来源后再次刷新必须移除对应私有活动项和回收项。

## DSH-016 重复拖入同一素材无法识别

- 现象：Windows 每次拖入都会新建任务，无法知道同一内容是否已传给同一设备。
- 根因：没有成功传送历史和顶层内容指纹。
- 修复：文件用 SHA-256；文件夹用排序后的相对路径、文件大小和逐文件 SHA-256 生成指纹。只有接收端 commit 或 USB 校验完成后才按设备写入本地历史。
- 回归要求：重命名但内容完全相同的文件夹也应提醒；文件数相同但内容不同的文件夹不得误判；用户明确选择重传时必须允许发送。

## DSH-017 匿名 USB 接口误合并到另一台 Wi‑Fi 安卓手机

- 现象：电脑同时看到一台局域网安卓和另一台只开启充电/调试接口的 USB 安卓时，USB 状态被挂到局域网设备卡片上。
- 根因：匿名 USB 接口没有可用设备名，旧合并逻辑在列表里只有一台局域网安卓时直接猜测两者是同一设备。
- 修复：匿名接口不再按在线数量合并；MTP 开启后，以 USB 厂商和设备硬件标识将同一实体的 WPD 与底层接口合并。
- 证据：K60 仅开放调试接口、红米 9A 同时通过 Wi‑Fi 在线时复现误合并；开启 MTP 后 Windows 又暴露有名底层接口与匿名可写 WPD。最终 K60 实体日志从 `usb_discovery devices=2` 收敛为 `devices=1`，界面只显示一张 `Redmi K60` 卡片。
- 回归要求：多台安卓在线时不得按数量猜身份；无法确认身份时宁可分开显示；同一 Android 开启 MTP 后只能显示一张真实设备卡。

## DSH-001 Android 作品重复一倍

- 现象：实体目录 11 个作品，界面显示 22 个；
- 根因：SAF 与旧系统隐藏目录兼容通道同时发现同一真实目录，历史迁移并发又可能重复导入；
- 修复：按规范化真实来源路径跨通道去重，迁移进程内串行；
- 证据：Redmi Note 8 连续冷启动稳定显示 11；
- 回归：保留“双通道同源”和“并发初始化”自动测试。

## DSH-002 HarmonyOS 隐藏作品漏扫或全盘误扫

- 现象：带点号目录最初被忽略；放开后又把大量非作品目录计入扫描；
- 根因：把“隐藏”直接等同于“无效”，或把递归经过目录数误当作品数；
- 修复：仅忽略明确系统目录和回收站；作品必须由同一目录直接包含图片与 TXT 判定；界面数字只统计作品；
- 证据：实体 Huawei 目录的隐藏与普通作品合计按实际数量显示；
- 回归：覆盖点号作品、系统隐藏目录、空目录和深层聚合目录。

## DSH-003 回收站只改数据库、没有移动真实文件

- 现象：App 里清空后，文件管理器仍看到原作品文件夹；
- 根因：早期实现只删除应用索引，没有操作用户授权目录中的真实文件；
- 修复：在授权根目录建立应用回收站，移动真实作品目录；恢复反向移动；清空删除回收站真实内容；
- 回归：每次操作后同时检查 App 列表和系统文件管理器，失败必须给出明确原因。

## DSH-004 Windows 文件夹 ZIP 出现空文件或同名目录

- 现象：接收后出现空文件和带 `(1)` 的同名目录；
- 根因：Windows ZIP 目录项可能使用反斜杠，接收端只按 `/` 判断目录；
- 修复：目录项同时接受 `/` 与 `\`，仍拒绝绝对路径和 `..`；
- 回归：保留 Windows 反斜杠 ZIP、越界路径和同名冲突用例。

## DSH-005 iPhone 只看到 `.Trash` 或漏掉深层作品

- 现象：选中总目录后只显示垃圾目录，真实作品位于“作品包 → 单个作品”更深层；
- 根因：扫描只看一层，或没有正确使用 security-scoped URL；
- 修复：授权期间有限递归；同目录含图片与 TXT 才是作品；忽略 `.Trash` 与应用回收站；遇到作品后停止继续向下；
- 回归：覆盖两层/三层作品、空包、`.Trash`、权限撤销和 iOS 12 Documents 分支。

## DSH-006 手机互相发现一闪即逝

- 现象：Android 短暂看到 iPhone 后消失；iPhone 看不到 Windows；
- 根因：部分路由器不在无线客户端之间转发 `255.255.255.255`，9 秒离线窗口也过短；
- 修复：同时发送全局广播和按 IPv4/掩码计算的子网定向广播；在线保留时间改为 15 秒；进入传送页主动探测；
- 证据：Android 间隔 20 秒两次采样都同时显示 Windows 与 iPhone，iPhone 也记录到两端；
- 回归：至少等待超过一个 TTL 后再次检查，不能只截“刚进入页面”的一瞬间。

## DSH-007 Windows 把自己显示成可发送设备

- 现象：电脑设备列表出现本机；
- 根因：发现包没有稳定本机 ID 过滤，或多网卡地址造成自广播被当成远端；
- 修复：生成稳定 Windows deviceId，并同时按 ID 过滤自身；
- 回归：有 Wi-Fi、USB 虚拟网卡和 169.254 地址时仍不显示自己。

## DSH-008 Android → iPhone 最终 timeout，iPhone 无反应

- 现象：Android 显示传送超时；iPhone 没有接收通知，也不返回 HTTP；TCP 地址和设备发现正常；
- 根因：`NWListener` 连接回调里创建的 HTTP 请求读取器只被弱引用，回调结束后提前释放，连接存在但永远没人读完请求并响应；
- 修复：读取器在开始时自持有，响应发送完成或连接失败后再释放；iPhone 前台接收时保持唤醒；
- 证据：前台 32 秒后 `/v2/info` 立即响应；任务创建、SHA-256 上传和取消为 201/200/200；Windows 正式文件提交成功；
- 回归：每次改 iOS 网络层都要包含“连接后延迟发送”“完整上传”“取消”和“前台持续 30 秒”检查。

## DSH-009 iPhone 侧载安装失败或看似密码错误

- 现象：安装停在最后、设备列表为空、账号输入后弹出不同错误；
- 根因：USB 信任、Apple 驱动、iTunes/iCloud、代理、账号验证、签名和 IPA 结构属于不同层，不能统一归为密码问题；
- 修复：固定按 USB → iTunes → 侧载工具 → 账号 → 签名安装 → App 功能分层诊断；IPA 内部路径使用 ASCII；
- 回归：CI 解包验证 IPA 的 `.app` 路径、可执行文件、版本和显示名；安装成功不能代替功能测试。

## DSH-010 构建额度与账号切换

- 现象：主账号 Actions 额度不足，或切到备用账号后主仓库 push 报 Repository not found；
- 根因：私有 Actions 额度按仓库所有者计算；Git HTTPS 凭据跟随当前 GitHub CLI 活跃账号；
- 修复：主仓库保持唯一真源，把同一提交推送到备用私有构建仓库运行；操作前显式切账号并配置 Git，完成后切回主账号；
- 回归：比较主源码、备用构建仓库的 commit SHA；发布前确认当前账号和推送目标；凭据只存系统密钥库。

## DSH-011 更新索引已推送但 raw 暂时仍是旧版

- 现象：GitHub API 已显示新提交，`raw.githubusercontent.com` 短时间仍返回旧 `latest.json`；
- 根因：Raw CDN 缓存传播延迟；
- 修复：等待后重新读取应用实际使用的公开 raw 地址，不能只验证 Git 提交或 API；
- 回归：正式发布必须记录 raw 返回的 `version_name`、`version_code` 和 APK SHA-256。

## DSH-012 移除启动通知权限时误删旧机型存储依赖

- 现象：第一轮 0.4.2 云构建中，iPhone 与 Windows 成功，Android 在 Java 编译阶段找不到 `Manifest` 和 `PackageManager`；
- 根因：删除 Android 首次启动的通知权限申请函数时，同时删除了仍被 Android 10 / HarmonyOS 隐藏目录兼容代码使用的两个导入；
- 修复：恢复存储兼容所需导入，只移除通知权限申请调用和方法；声音通知权限改由设置开关按需申请；
- 证据：备用构建 run `29693667340` 的 Android 单元测试、编译、Lint 与 APK 上传全部成功；
- 回归：精简权限代码后必须全局检查同名类型的其他用途，并同时跑旧系统兼容分支的完整编译。

## DSH-013 iPhone 6 固定目录没有可用的文件入口

- 现象：iPhone 6 能启动“相册”，但系统“文件”App 已被删除，固定作品库扫描为 0，设置里只能重新扫描，不能选择外部总文件夹；
- 根因：iOS 12 不具备当前外部文件夹持久授权路径，旧实现又假定系统“文件”App 一定存在；第三方文件管理器仍受沙盒限制，不能从根本上补齐入口；
- 修复：同一客户端在 iOS 12 自动使用固定作品库，并在空作品页、设置页增加文件/ZIP 导入；多选素材自动归组，标准 Deflate ZIP 由 App 自行解压；较新系统继续保留外部文件夹选择；
- 回归：覆盖系统“文件”App 不存在、导入取消、多选图片+TXT、存储式 ZIP、Deflate ZIP、重名目录和越界路径。

## DSH-014 iPhone 6 设置页没有“选择作品文件夹”

- 现象：iPhone 12 可以通过系统选择器设置外部总文件夹，iPhone 6 只显示固定目录和重新扫描，用户无法在已接收的多个文件夹之间切换扫描根目录。
- 根因：iOS 12 没有 iOS 13 的外部文件夹持久授权能力，旧兼容分支因此把“固定 Documents”错误地等同于“不需要选择入口”。
- 修复：同一 IPA 内按系统能力切换；iOS 13+ 保留系统外部文件夹选择器，iOS 12 增加 App 内文件夹选择器，递归列出相册接收目录及其子目录，并持久保存所选相对路径。接收和扫描都落到当前所选根目录。
- 回归：iOS 12 覆盖选择全部接收内容、选择一级/多级子目录、原目录被移除后的安全回退；iOS 13+ 继续覆盖 security-scoped bookmark 恢复。

## DSH-015 点击更新跳转网页

- 现象：Android 和 iPhone 的“检查版本更新”发现新版后打开 GitHub 页面，用户还要再次找安装包，缺少下载进度与校验。
- 根因：更新检查只读取发布页地址，没有消费 `latest.json` 中的直接 APK 地址与 SHA-256，也没有区分 Android 可调用系统安装器和 iPhone 侧载必须重新签名的系统边界。
- 修复：Android 直接在 App 内下载 APK、显示进度、校验 SHA-256，再调用系统安装器；iPhone 不再跳网页，明确提示通过电脑侧载覆盖更新。
- 回归：覆盖无新版、网络失败、取消下载、错误 SHA-256、未知来源权限未开、下载后系统安装器打开，以及 iPhone 不打开浏览器。

## DSH-016 重名回收站里的旧作品重复出现在首页

- 现象：iPhone 6 实体首页显示 4 个作品，其中两项与正常作品同名；App Documents 中同时存在“相册回收站”“相册回收站 (1)”和“相册回收站 (2)”。
- 根因：文件接收遇到同名目录会追加数字避免覆盖；扫描器只排除了精确名称“相册回收站”，仍会递归数字重名副本。
- 修复：Android 与 iPhone 同时识别“相册回收站”“_相册回收站”及其“ (数字)”副本；文件夹选择列表也不再提供这些回收站副本。
- 回归：覆盖原名、旧版下划线名、数字重名副本、非数字括号和名称相似的普通目录，避免扩大排除范围。

## DSH-017 旧 iPhone 大文件夹长时间 Wi-Fi 传送中断

- 现象：iPhone 6 接收约 264 MB 单 ZIP，上传到 50% 时设备退到桌面，前台接收服务停止并重置连接；任务取消后没有业务文件落地。
- 根因：iPhone 接收服务按产品边界只在 App 前台运行；旧设备和较慢 Wi-Fi 让单连接持续数分钟，离开前台就会中断。
- 处理：普通传送继续使用局域网协议；旧 iPhone 连接 USB 且大目录达到 64 MiB 时，共享技能优先使用 App 文件共享，先写隐藏暂存区，核对文件数与总字节，再原子改名。没有 USB 时继续要求相册保持前台并保留自动取消。
- 证据：实体大目录样本在电脑和手机均为 129 个文件、263,885,655 字节，App 识别 14 个作品；共享技能 Wi-Fi 小文件提交返回 received=1，随后清理测试文件。
- 回归：大目录测试必须覆盖前台持续、主动离开 App、失败取消、隐藏暂存清理、同名目标拒绝覆盖和最终数量/字节核对。

## DSH-018 Android 主应用可分享、应用分身卡住

- 现象：从相册分享给小红书主应用正常；在红米 K60、VIVO 的应用分身中，目标可能不出现、进入后卡住或点击无反应；系统图库分享给同一分身正常。
- 根因：旧版图片来自 `com.zwm.gallery.files` 应用私有内容提供器。标准临时 URI 授权在主用户内有效，但部分厂商的分身运行在隔离用户空间，跨空间解析和转发应用私有 URI 不稳定；系统图库使用系统媒体库 URI，厂商已覆盖该通道。
- 处理：Android 10+ 把原图字节复制到系统 MediaStore 的短期分享区，发布完成后分享系统媒体 URI；同时给当前可解析的目标补充显式只读授权。准备失败时保留普通分享兜底并明确提示。
- 安全边界：不伪装包名、不自动点击、不读取分身账号；临时副本下一自然日清理，删除目标严格限制为应用自己记录的 `media` URI。
- 实体证据：红米 K60 同签名覆盖 0.4.7 后，从相册选择 10 张图分享至小米分身小红书；系统实际进入 `u999` 的图片编辑页，10 张缩略图完整加载。随后返回，未执行发布或保存草稿；首页作品数和回收站未改变。
- 回归：主应用与分身分别覆盖单图、多图、返回、再次分享；核对文案复制、图片数、分享次数、次日临时副本清理。VIVO 分身继续沿用同一公共实现，不制作设备专包。

## DSH-019 手动回收与滚动时工具栏消失

- 现象：作品很多时顶部常用按钮滚出屏幕；用户也无法在不打开分享的情况下主动把某个作品移入回收站。
- 处理：首页使用冻结工具栏结构，只有作品内容区滚动；长按作品进入批量选择状态，所有卡片显示复选框，可勾选多个，右下角红色垃圾桶统一移动真实来源文件夹和应用私有副本，保留分享次数。
- 失败保护：真实文件夹尚未移动时会回滚应用状态；系统已经完成移动时绝不把应用记录贸然退回原列表。
- 回归：长按、复选框多选/反选、返回键取消、批量部分失败、确认/取消、文件夹权限失效、恢复、清空和 7 天边界都要覆盖。
- 实体证据：红米 K60 的 0.4.8 连续勾选两项后显示“已选 2 个”，所有卡片均显示复选框；按返回取消后首页仍为 11、回收站仍为 2，没有移动用户文件。

## DSH-020 iOS 营销版本已更新但内部 build 没更新

- 现象：首次 0.4.7 云产物的 `CFBundleShortVersionString` 为 0.4.7，但 `CFBundleVersion` 仍为 17。
- 根因：XcodeGen 配置同时存在 `CURRENT_PROJECT_VERSION` 和显式 `Info.plist` 属性，后者仍硬编码旧值并覆盖构建设置。
- 修复：显式属性同步为 18，IPA 结构检查同时断言营销版本和 build，避免只核对用户可见版本。
- 回归：每次 iOS 升级都解包 IPA 核对两个字段及最低 iOS 版本。

## DSH-021 iPhone 覆盖安装停在 Installing 0%

- 现象：Sideloadly 已完成 Apple ID 会话、设备注册与签名，进入 `Installing...` 后长期保持 0%；目标 iPhone 上的旧相册仍在前台提供 `/v2/info`。
- 根因：本次实体表现为目标 App 占前台时安装服务没有继续替换，而不是密码、签名、USB 信任或安装包错误。
- 处理：先核对 Sideloadly 选中的最终 bundle id 与设备现有 App 完全一致；保持设备解锁，把旧 App 退回桌面。无人工触控条件时，可用已配对的 `pymobiledevice3` 挂载开发镜像并只发送一次系统 Home 键。不得卸载 App 或清除容器来绕过停滞。
- 实体证据：iPhone 12 的旧 App 退出前台后，Sideloadly 立即从 0% 完成到 100%；设备应用清单为 0.4.7 build 18，启动后仍扫描出原有 23 个作品。
- 回归：每次覆盖前记录旧版本、最终 bundle id 和作品数；安装后再次核对三项。若 bundle id 被 Sideloadly改写，必须确认与该设备既有最终标识一致再继续。

## DSH-022 旧 Android 在 Wi-Fi 重连后互相发现失效

- 现象：Redmi 9A / Android 11 上，手机与电脑曾经互相不可见；但 App 进程和 HTTP 接收端口仍在，电脑直接读取设备状态正常。重开 App 后会暂时恢复。
- 根因：UDP 发现循环只有最外层一次异常捕获。Wi-Fi 切换期间向发现请求方回复时出现临时 `ENETUNREACH`，异常会让整个发现线程永久退出，而 HTTP 线程不受影响。
- 修复：把一次 UDP socket 生命周期变成可恢复会话；运行中发生网络异常时等待短暂退避并重新绑定端口，正常停止服务时不重试。诊断分别记录重试和恢复。
- 自动回归：模拟首个发现会话抛出临时网络错误，断言同一服务进入第二个会话；模拟服务已停止，断言关闭 socket 不产生重试。
- 实体证据：同一正式升级链覆盖 Redmi 9A 后，原目录授权、作品数和接收设置保留；应用进程不重启，Wi-Fi 断开再恢复后，电脑重新发现手机，手机同时看到 Windows 与 iPhone；超过设备过期时间再次检查仍在线。

## DSH-023 VIVO 分身冷启动提前返回导致误报失败

- 现象：选择 VIVO 应用分身后，系统选择器很快退回相册；分身小红书仍在后台缓慢加载，旧逻辑会显示超时，用户稍后手动进入却能看到图片。
- 根因：OriginOS 的分身路由先返回调用方，再冷启动隔离用户中的目标进程；“系统选择器返回”既不等于失败，也不等于目标已经打开。
- 修复：系统媒体写入完成并可读取后再分享；记录用户选择的目标包，目标过早返回时继续观察前台应用，只有目标真实打开才增加分享次数，否则提示重试。
- 实体证据：VIVO 0.5.0 诊断先记录 `share_target_returned_early`，随后记录 `outcome=deferred_target_opened`；测试后删除精确测试作品的临时分享计数字段，其他数据未改动。
- 回归：主应用、分身热启动、分身冷启动分别测试；不得仅用 `onActivityResult` 判定成功，也不得用固定等待直接伪造成功。

## DSH-024 作品图片预览比例失真及旧 iPhone 内存压力

- 现象：近方形缩略图不能直观看出 3:4 作品构图；直接解码所有原图在 iPhone 6 多图作品中可能造成明显卡顿或内存压力。
- 修复：Android 与 iPhone 统一两列 3:4 预览；点图时再加载较大预览。iPhone 使用 ImageIO 按目标像素降采样，列表不保留完整原图位图。作品直接目录中的 TXT、ZIP、JSON、PDF 等非图片附件继续显示类型、文件名和大小，点按使用系统预览能力。
- 交互：长按进入多选，底部仅显示垃圾桶、三点连线分享、小飞机三个图标；图片回收站按作品和日期保存 7 天并可恢复。
- 回归：单图也必须保持半列宽，不能拉伸占满整行；多图滚动、复用、选择边框、恢复和第 7 天清理都要覆盖。

## DSH-025 Android 正式包误用 Debug 构建及全面屏顶部过近

- 现象：0.5.0 公布的 APK 清单包含 `debuggable=true`，部分厂商安全引擎可能结合安装包权限和联网行为给出通用灰色风险提示；水滴屏/Android 15 边到边系统的冻结工具栏也可能靠状态栏过近，42dp 图标不够容易点击。
- 根因：工作流一直上传 `assembleDebug` 产物；界面只使用固定顶部间距，没有消费新系统的状态栏、导航栏和显示挖孔安全区。
- 修复：0.5.1 改为同一证书签名的 `assembleRelease`，显式关闭调试；所有 Android 页面在 Android 15+ 消费系统栏与挖孔 Insets，首页按钮扩大为 48dp 并调整窄屏间距。
- 兼容边界：不能直接更换签名证书，否则旧版无法覆盖升级并可能导致用户状态丢失；应用内直接更新仍需 Android 的安装包请求权限，系统最终确认不会被绕过。
- 本机证据：单元测试、Release 编译和 Release Lint 成功；APK 为 0.5.1/code 28，v2 签名验证通过，证书摘要与旧版一致，合并清单未出现 `debuggable=true`。
- 回归：同签名覆盖安装后核对目录授权、作品数、分享次数、回收站和更新入口；在水滴屏实体检查四个顶部按钮点击；再次观察厂商安全提示，不把本机检查替代真机结果。

## DSH-026 Release 包关闭 Debug 后厂商风险提示仍存在

- 现象：0.5.2 已确认是非 Debug 的 Release APK，但出现过风险提示的 Android 手机仍给出同类通用安全提示。
- 证据：0.5.2 清单没有 `debuggable=true`，但仍包含 `android.permission.REQUEST_INSTALL_PACKAGES`；该权限只服务于旧版 App 内下载后直接调用安装器。
- 单一假设：厂商安全引擎把“联网 + 请求安装 APK”作为风险信号之一。仅关闭 Debug 不足以消除提示；不能在同一轮同时换签名，否则既破坏覆盖升级，也无法判断究竟哪个变量生效。
- 处理：0.5.3 删除安装包请求权限和直接安装流程，改由 Android DownloadManager 下载；用户点系统完成通知安装。后台保留 SHA-256 核对，不一致时删除下载。
- 自动证据：单元测试、Release 编译和 Release Lint成功；本机构建为 0.5.3/code 30，v2 签名证书与旧版一致，APK 权限清单不含 `REQUEST_INSTALL_PACKAGES`，也没有 Debug 标志。CI 同时断言这两个条件。
- 回归：在此前出现提示的实体手机覆盖安装 0.5.3，核对应用数据、系统下载、完成通知、安装确认、哈希失败提示和厂商安全扫描结果。没有实体结果前，不把权限清单变化等同于风险提示已经消失。

## DSH-027 华为分享已打开但次数没有增加

- 现象：HarmonyOS / EMUI 设备把作品图片交给目标应用后，返回相册仍没有“已打开分享 N 次”，次日整理也缺少这次本机记录。
- 根因：部分系统选择器会先回调 `onActivityResult`，稍后才发送 `Intent.EXTRA_CHOSEN_COMPONENT`。旧逻辑在目标回调到达前立即按取消结束并注销接收器，真实目标选择因此漏记。
- 修复：把目标回调和 Activity 结果交给同一状态判定器；结果先到时等待 1.2 秒，晚到的目标回调仍记为打开分享，超时且确实没有回调才按取消。正常 Android 与 VIVO 分身的快速返回等待路径保持不变。
- 自动证据：4 项时序测试分别覆盖晚到回调、回调缺失、快速目标返回和长时间目标返回；Android 全量 37 项单元测试、Release 编译和 Release Lint 成功。
- 实体状态：Huawei P30 局域网上报 Android 10、相册 0.5.2、27 个作品；本轮电脑没有 USB/ADB 枚举，0.5.4 覆盖安装与真实分享操作需要设备重新以 USB 调试连接后复核。
- 回归：华为普通分享、系统选择器取消、VIVO 分身热/冷启动、小米主应用与分身都要检查；取消分享不能加次数，晚到回调不能重复加次数。

## DSH-028 已回收作品以另一扫描副本重现并造成重复分发

- 现象：Redmi Note 8 上已经分享、甚至手动移入回收站的作品仍以未分享状态出现在首页，用户再次进入同一小红书账号后形成重复内容。
- 实机证据：旧版 0.5.0 的当前列表有 16 项且全部没有分享字段；回收站 19 项中有正常次数记录。内容哈希确认其中 2 个当前项与回收站项的图片集合完全一致，另有 2 个当前作品内部存在字节完全相同的重复图片。
- 根因一：旧逻辑只有目标应用回调成功才调用 `markShared`，厂商选择器、取消或冷启动回调不稳定时，用户已经点过入口但作品仍保持未分享。
- 根因二：Android 10 同一隐藏目录可通过 SAF 和旧存储兼容通道得到不同内部编号。旧元数据缺少稳定的跨通道来源关联，回收一份后另一份仍能留在当前列表。
- 修复：0.5.5 在 ShareActivity 首次创建时立即原子记账，后续系统回调只做诊断；再次分享先确认。作品库增加“文案 + 去重后的图片 SHA-256 集合”内容指纹，只在当前项与回收站项完全一致时保留回收状态，并去除应用私有副本内的重复图片。
- 数据边界：不按名称猜测，不改用户原图片和文案；覆盖升级前先备份应用私有数据。0.5.5 当时暂不处理旧日期；0.5.6 起改用 DSH-029 的北京时间迁移与同日 1 小时宽限，避免历史回收站长期堆积。
- 实体证据：Redmi Note 8 同签名覆盖后版本为 0.5.5/code 32，名称与 Lark 授权保留；当前作品从 16 收敛为 14，回收站保持 19，应用私有作品内重复哈希组从 2 组降为 0。临时自检作品点击一次后 `shareCount=1`，模拟满 1 小时后作品、MediaStore 跟踪项和临时目录均被清理，最终恢复 14/19。
- 回归：取消系统分享面板仍只记一次；旋转/恢复 Activity 不得二次记账；跨通道同内容但不同编号只保留回收状态；相同图片但文案不同不能误合并；旧记录按 DSH-029 的跨日到期与同日宽限处理。

## DSH-029 文件模式像新页面、旧回收站长期不清与 ZIP 图标误导

- 现象一：Android 点文件夹图标后进入独立 `FileBrowserActivity`，标题、状态区和返回逻辑全部变化，用户感知为跳进另一套应用；五个顶部入口同时挤压标题和数量。
- 现象二：旧 VIVO 与 iPhone 的昨天作品和回收站仍按自然日/7 天模型保留；仅把新分享改成小时级，会让历史堆积永远没有精确锚点。
- 现象三：早期 ZIP 图标轮廓接近垃圾桶，文件模式只有图片/文件夹较容易辨认。
- 修复：Android 把文件浏览直接嵌入 `MainActivity`，同一冻结顶栏只替换下方内容；返回先退目录、再切回作品。标题、数量、圆形按钮、图标和间距重新收紧，并给五个纯图标入口增加短文字反馈。文件类型改用系统文件管理器式图标，ZIP 为文档加拉链。
- 迁移：Android 与 iPhone 按北京时间把旧日期迁移为精确毫秒。昨天及更早锚定到旧日零点并按当前规则处理；当天锚定升级时刻，完整保留 1 小时；iPhone 无状态旧回收站从升级时刻保留 1 小时。迁移写回状态且幂等。
- 实体证据：VIVO Y36t 从 0.5.4/code 31 同签名覆盖后，Lark 授权、设备名和 13 个当前作品保留；旧回收站 15→0，真实 `Download/Lark/相册回收站` 为空；同日 1 次分享作品获得升级时刻锚点，没有立即删除。0.5.6 Release 覆盖后仍为 13 个作品，`run-as` 明确拒绝并报告非 Debug。
- 界面证据：VIVO 实机确认五个入口、标题与数量同排完整显示；模式切换前后顶部坐标和 Activity 均不变，文件根目录显示 15 项，ZIP 显示拉链文件图标；返回键原地回到 13 个作品。
- 回归：320/360dp、大字体、水滴屏、模式往返、两级目录返回、全部文件类型、同日/跨日旧状态、孤立回收站、迁移幂等、覆盖安装数据保持。iPhone 清理结果必须在新 IPA 覆盖后单独记录，不能用 Android 实体结果代替。

## DSH-030 iOS 新版本编译成功但旧打包断言拦截

- 现象：0.6.6 源码已通过 Xcode 真机 SDK 编译，工作流仍在“Package AltStore IPA”失败并未上传 IPA。
- 根因：工作流把 IPA 名称、营销版本和 build 固定写成 0.6.5/build 24；工程升级到 0.6.6/build 25 后，旧断言把正常新包判为失败。
- 修复：同步更新 IPA 名称、Info.plist 版本断言、校验文件和上传资产名为 0.6.6/build 25。
- 回归：每次提升 iOS 版本时同时检索工作流中的旧版本字符串；最终 IPA 必须解包实读 `CFBundleShortVersionString` 和 `CFBundleVersion`，不能只以 Xcode 编译成功收口。

## DSH-031 Android 断点接收提交被孤立回收目录阻塞

- 现象：作品 ZIP 已完整上传，提交阶段却返回 `trash/<id>/meta.properties: open failed (ENOENT)`，导致自动补发重试后仍无法落库。
- 根因：回收站清理或断点导入在目录元数据检查后移动了 `meta.properties`；作品库遍历直接读取已不存在的文件，把可跳过的孤立目录当成致命错误。
- 修复：遍历作品目录时对元数据文件做二次存在性确认；仅忽略确认已经成为孤立目录的 `FileNotFoundException`，真实元数据读取失败继续抛出。不会删除目录或用户作品。
- 回归：覆盖孤立回收目录重启作品库、正常作品保留、断点文件名复用和大小/SHA-256 不匹配拒绝；安装新 APK 后再用同一任务验收提交与自动补发。
# BUG_LEDGER

## DSH-104 在线相册分类置顶缺失（useCount > 0 作品不置顶）

**报告时间**：2026-09-24 15:35
**报告端**：用户（华为 Android）
**真凶端**：服务端 `scripts/online_gallery_service.py` `api/online/works?category=` 路由

### 症状
华为端在线相册点开分类，已用过（`useCount > 0`）的作品没置顶，要往下翻才能找到。

### 根因
服务端 `filtered.append(w)` 完直接返回，**没排序**——`useCount > 0` 的作品按磁盘扫描顺序混在中间。

### 修复
`filtered` 后加：
```python
filtered.sort(key=lambda w: (-(w.get('useCount') or 0),
                            w.get('used') or False,
                            w.get('index') or 0))
```
三键稳定排序：主键 `useCount` 降序，次键 `used`，末键 `index`。

### 闸门
- `tests/parity_ios_android.py` C26：断言三行 sort 代码必须存在
- 改前：29/29 FAIL（验过）
- 改后：29/29 PASS（验过）

### 重启
服务端 PID 11840 → 30072。

### 客户端
Android + iOS 零改动（早已用 `?id+?file` 新契约 DSH-099/102）。
iOS 版本保持 0.8.39/111 不变，无需重装机。

### 限制
库里 `useCount` 当前全 0（phone sync 没收到），肉眼验证需等下次用户点平台按钮后数据回流。

# BUG_LEDGER

## DSH-107 Android 顶部 statusText 显示电脑 IP + 端口技术字段

**报告时间**：2026-09-24 18:02
**报告端**：用户（华为 Android + iPhone 装机对照）
**真凶端**：Android `MainActivity.java:2906`

### 症状
用户反馈："安卓顶部那个已连接电脑XXXXX就别显示了"——电脑 IP + 端口 45835 这种技术字段不该堆在主屏顶部。

### 根因
原 statusText.setText 拼出了 `💻 已连接电脑在线相册 (http://192.168.x.x:45835) · 共 N 套`，把局域网 IP + 端口号暴露给用户。

### 修复
- Android `MainActivity.java:2906`：`statusText.setText("已同步电脑在线作品 · 共 " + onlineWorks.size() + " 套")`，去除电脑 IP/端口字段。
- 保留作品数（用户感知数据量）。

### 闸门
- `tests/parity_ios_android.py` A6：检测 statusText.setText(...) 调用里没有 "已连接电脑在线相册" 字符串
- 改前：30/30 FAIL（验过）
- 改后：30/30 PASS（验过）

### iOS 端
无此字段（之前就没显示），DSH-107 不需要新增 iOS 代码。

### 版本
Android 升 0.8.55/166 → **0.8.56/167**。

# BUG_LEDGER

## DSH-105 iOS 重置按钮仅在 useCount > 0 时显示（与 Android 不一致）

**报告时间**：2026-09-24 15:43
**报告端**：用户（iPhone）
**真凶端**：iOS `ContentView.swift:1990`（在线）+ `ContentView.swift:2159`（本地）

### 症状
iOS 客户端已使用一次的作品看不到"重置"按钮，要手动调代码才能看到。

### 根因
iOS ContentView.swift 两处都有 `if entry.useCount > 0` / `if work.shareCount > 0` 守卫，重置按钮仅在用过时才显示。
而 Android `MainActivity.java:1201` 重置按钮**无条件**添加到 platformRow，两端不一致。

### 修复
去掉 iOS 两处守卫，重置按钮常驻可见，与 Android 对齐。

### 闸门
- `tests/test_ios_online_gallery_client.py` 新增 B8
- 改前：8/8 FAIL（验过）
- 改后：8/8 PASS（验过）

### 版本
iOS 升 0.8.39/111 → **0.8.40/112**。
Android 零改动。

### 客户端行为
未使用的卡片点重置 → 弹「该作品尚未使用」toast；
已使用 → 弹原确认框（沿用 onlineResetTapped / resetTapped 既有逻辑）。


## DSH-108 — 三端底部三按钮顺序统一 + Android 搜索框位置（2026-09-24）

**症状**:
1. iOS 底部三个按钮的顺序用户认为是「重置 → 复制 → 删除」，但代码里实际是「重置 → 删除 → 复制」。
2. Android 端底部三按钮（重置 / 复制 / 删除）都堆在 platformRow 里，没有单独拆出一行；与 iOS「三按钮固定在底部」布局不一致。
3. Android 端搜索框当前在「按钮之上」位置，用户要求改为「分类按钮之下」，与 iOS「分类文件夹之下才是搜索框」对齐。

**根因**:
- iOS `ContentView.swift` 两处 `actionRow.addArrangedSubview` 调用顺序按开发时的字母序排：reset → delete → copyPath（d < r 之前被想成 d < c 错了），实际用户体验上「复制」应该在「删除」之前。
- Android `MainActivity.java` `onlineWorkCard()` 内所有按钮（平台按钮 + 重置 + 复制 + 删除）都堆在 `platformRow`（FlowLayout）里，没有「底部操作行」概念。
- Android `MainActivity.java` `buildUi()` 内 `frozenLayout.addView` 顺序是 `titleRow → statusText → searchBar → ...`，搜索框排在分类按钮之前。

**修复**:

### iOS 端
- `ContentView.swift:1991-1993`（在线模式）`actionRow.addArrangedSubview` 顺序改为 reset → copyPath → delete。
- `ContentView.swift:2161-2163`（本地模式）同上。
- **iOS 升 0.8.40/112 → 0.8.41/113**。

### Android 端
- `MainActivity.java` `onlineWorkCard()`（line 3635 起）：
  - 新建 `bottomActionRow`（LinearLayout.HORIZONTAL，CENTER_VERTICAL | END）。
  - 把 reset / copyPathButton / delete 三按钮从 `platformRow`（FlowLayout）拆出，单独放到 `bottomActionRow`。
  - 顺序 = reset → copyPath → delete。
  - 重置按钮 DSH-108 同步去掉 useCount>0 守卫（与 DSH-105 iOS 对齐），常驻可见。
- `MainActivity.java` `buildUi()`（line 685 起）`frozenLayout.addView` 顺序调整：
  - 原顺序：titleRow → statusText → searchBar → recycleTabs → categorySelector → contentFrame（搜索框在分类按钮之上）。
  - 新顺序：titleRow → statusText → recycleTabs → categorySelector → searchBar → contentFrame（搜索框在分类按钮之下）。
- **Android 升 0.8.56/167 → 0.8.57/168**。

### 闸门
- `tests/test_ios_online_gallery_client.py` 新增 **B9**（iOS 两处顺序 + 旧顺序消失）。
- `tests/parity_ios_android.py` 新增 **A7**（Android 缩窗到 onlineWorkCard 函数体内，验证 bottomActionRow 拆出 + 顺序 + platformRow 不再混 reset/delete）+ **A8**（搜索框位置缩窗到 buildUi 的 frozenLayout 装配片段，验证 searchBar.addView 在 categorySelector.addView 之后）。
- **改前**：A7 = 1/31 FAIL（验过）/ A8 = 1/32 FAIL（验过）/ B9 = 1/9 FAIL（验过）。
- **改后**：A7 = 31/31 PASS（验过）/ A8 = 32/32 PASS（验过）/ B9 = 9/9 PASS（验过）。

### 验证状态
- [ ] iPhone 真机视觉验证（底部三按钮顺序 + 真机无回归）—— 待装机。
- [ ] 华为 USB 真机视觉验证 —— 待装机。
- [ ] VIVO 无线真机视觉验证 —— 待装机。
- [ ] 红米无线真机视觉验证 —— 待装机。
- [ ] Android 搜索框位置截图比对（按钮之下 + 分类按钮可见）—— 待装机。
- [ ] CI 编译闸门（iOS .ipa / Android .apk）—— 待 push。

## DSH-109 — 在线相册排序机制 + 实时看图机制（用户新需求）（2026-09-24）

**用户诉求**:
1. 实时看图：只要在电脑端添加了图片，手机端刷新就能看到最新的。
2. 排序机制：放在搜索框的右侧；默认按时间排序，最新的排在最底；筛选支持名称 / 大小。

**实时看图机制**（已存在，DSH-109 复用）:
- 服务端 `online_gallery_service.py` `scan(force=True)` 触发强制重扫磁盘。手机端点刷新带 `?refresh=1`，5 秒内可见磁盘新增。
- 三门槛：① 文件夹在 `DEFAULT_LIBRARY_ROOT`（成品库，非 `.`/`_` 开头）；② 至少 1 张图（顶层优先 → 回落 `产出素材/`）；③ 有 `文案.txt`。
- 服务端顺手做 `_maybe_background_phone_sync()` 回读本地分享次数，多端 useCount 自动同步。

**排序机制（DSH-109 新增）**:

### 服务端（`scripts/online_gallery_service.py`）
- 新增 `SORT_KEYS = ("default", "time_asc", "time_desc", "name_asc", "name_desc", "size_desc", "size_asc")`
- 新增 `ReverseStr` 包装类（让字符串按字典序反向参与比较）
- 新增 `_dir_size_bytes()` 递归工具函数（os.scandir + 容错，单文件 stat 失败跳过）
- work dict 增加 `"sizeBytes": _dir_size_bytes(dir_path)`
- `/api/online/works` 接 `?sort=<key>` 参数（白名单校验，不在白名单 fallback `default`）
- 排序逻辑：DSH-104 一级 useCount desc（已用置顶）+ DSH-109 二级 sort_key（同 useCount 内的相对顺序）

### Android（`MainActivity.java` + `OnlineGalleryClient.java`）
- `PREF_ONLINE_SORT_KEY = "online_sort_key"` + `DEFAULT_ONLINE_SORT = "time_asc"`（SharedPreferences 持久化）
- 搜索框（`searchBar` LinearLayout）右侧新增 `sortKeyButton`（`STYLE_MUTED_GRAY` 38pt 半圆浅绿，按 `STYLE_MUTED_GRAY` 与 iOS 对齐）
- `SORT_MENU` 常量：5 个排序键 + 中文标签（默认（最新在底）/ 名称升降 / 大小升降）
- `showSortKeyMenu()` 用 `androidx.appcompat.widget.PopupMenu` 弹菜单
- `applySortKeyChange()`：写 prefs → 更新按钮文案 → 调 `applyOnlineCategoryFilter` 重渲染
- `OnlineGalleryClient.fetchWorks(category, query, sortKey, callback)` 新增 sortKey 参数（保留旧 3 参数签名向下兼容）
- MainActivity 两处 `onlineClient.fetchWorks` 调用点都改成 4 参数版（带 `currentSortKey`）

### 闸门（`tests/parity_ios_android.py`）
- **A9**：9 项硬判据（btn 存在 / SORT_MENU / PREF_ONLINE_SORT_KEY / fetchWorks 调用点 / 客户端 4 参数签名 / URL `.append("sort=")` / 服务端 SORT_KEYS / `?sort=` / `sizeBytes` 字段）
- 改前：1/33 FAIL（验过）
- 改后：33/33 PASS（验过）

### 验证状态
- [ ] Android 真机视觉验证（排序按钮存在 + PopupMenu 可弹 + 切换排序生效）—— 待 push + 装机。
- [ ] 服务端 `?sort=time_asc` / `?sort=name_desc` 实测响应顺序正确 —— 待 push。
- [ ] CI 编译闸门（iOS / Android）—— 待 push。
- [ ] iOS 端 DSH-110 同步 —— 用户口径本次先做 Android，iOS 暂未做。

### 版本
- **Android 升 0.8.57/168 → 0.8.58/169**。
- iOS 零改动。

## DSH-110 — 在线相册一键启动 + 永远在线 + 文件监听自动刷新（2026-09-25）

**用户诉求**:
1. 「怎么样才能用上在线相册？有一键启动方式吗？是点击桌面上的『文件分发』应用吗？还是怎么的？」
2. 「你认为如何才算合理？应该要怎么做才能保存那个在线相册，让它一直在线？」
3. 「新增的作品，无论是手动移动文件夹进去，还是添加什么东西，手机端都能自动刷新，那边就能看到最新的。」

**调研结论**:
- Android 桌面 app 名为「相册」（不是「文件分发」），iOS 为「Album」。
- 桌面「文件分发工作台（稳定版/测试版）」= 工作台 Electron app，**不是**相册入口。
- 服务端只有 `start_online_gallery_service.ps1` 手动启动，**无开机自启**。
- 服务端无文件监听，**手机端不点刷新看不到新文件**。
- 桌面**无**「在线相册」快捷方式入口。

**3 件套一并交付**：

### A. 桌面一键启动入口（DSH-110 已部署到 `C:\Users\z\Desktop\`）
- `在线相册.lnk`：双击 → 启动服务（pythonw.exe 无黑框）。
- `在线相册-状态.lnk`：双击 → 健康检查（IP / 端口 / 总作品数 / 文件监听状态 🟢🟡❌）。
- `在线相册-开机自启.lnk`：双击 → 注册 Windows 启动文件夹开机自启。
- 创建脚本：`scripts/create-desktop-shortcuts.py`（系统 Python 3.11 + pywin32）。
- 3 个 .cmd：`online_gallery.cmd` / `online_gallery-status.cmd` / `online_gallery-autostart.cmd`（去 emoji 让 COM 兼容）。
- **踩坑**：emoji 在桌面 .lnk 名 + emoji 在 .cmd 文件名都会让 `WScript.Shell.Save()` 报 `0x80070057` 参数错误，必须用纯 ASCII/中文。

### B. 开机自启（`scripts/install-autostart.ps1`）
- 在 Windows「启动」文件夹（`shell:startup`）创建 `DSH-OnlineGallery-AutoStart.lnk`。
- PowerShell `-WindowStyle Hidden` 跑 `restart_online_gallery.ps1`（无黑框）。
- 优点：零第三方依赖（NSSM 不用），NT 内核权限不用，用户态 pythonw.exe 足够。
- 卸载：`install-autostart.ps1 -Uninstall`。

### C. 服务端文件监听 watchdog（`scripts/online_gallery_service.py`）
- **设计**：stdlib 轮询（无 watchdog 第三方依赖），60 秒/次对比 (path, mtime) 集合。
- `WorkScanner` 新增：
  - `_walk_root_paths()`：遍历 `DEFAULT_LIBRARY_ROOT` 下 (path, mtime)，跳过 `._` 开头。
  - `_poll_diff()`：diff prev vs curr，新增 / 删除 / mtime 变化 → `scan(force=True)`。
  - `_start_watchdog_loop()`：daemon 线程，60s/次，stop event 优雅退出。
  - `watchdog_status()`：暴露 active / intervalSec / lastPollAt / lastChangeAt / trackedPaths。
- `/api/online/status` 响应新增 `watchdog` 字段（手机端 statusText badge 用）。
- **修复老 bug**：`scan()` 之前没 `return results`，调用方 `len(scanner.scan(force=True))` 实际拿到 None（只是之前没人 print 出错就过去了）。DSH-110 修复时顺手补上。

### 闸门 A10（7 项硬判据，DSH-110）
- 改前 1/34 FAIL（验过：watchdogMethods 强制 False）
- 改后 34/34 PASS（验过）

### 单元测试 + 真实启动验证
```
## 单元测试（临时目录）
初次 paths=3
[DSH-110 watchdog] 检测到变更 → force scan: +0 / -0 / ~1   # 新增文件
新增后 _poll_diff → result=True
[DSH-110 watchdog] 检测到变更 → force scan: +0 / -0 / ~1   # 删除文件
删除后 _poll_diff → result=True
无变化 _poll_diff → result=False   # 不变不触发（稳）

## 真实启动（端口 45836）
watchdog.active = True
watchdog.intervalSec = 60.0
watchdog.rootDir = D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）
totalWorks = 472
```

### 版本
- 服务端无版本号概念，**Android / iOS 客户端零改动，不升版本号**。

## DSH-109 fix — 排序 PopupMenu 当前选中项无视觉标记

**症状**：DSH-109 加了排序按钮 + 5 种排序 PopupMenu，但 PopupMenu 弹出时当前选中的排序没有打勾标记，用户只能通过排序按钮本身的文字间接判断当前排序。

**修法**：在 `showSortKeyMenu()` 里给 `currentSortKey` 对应项 `setCheckable(true) + setChecked(true)`。

**闸门 A11**：
- `setCheckable\s*\(\s*true\s*\)` 在 showSortKeyMenu 函数内
- `setChecked\s*\(\s*true\s*\)` 同上
- setCheckable 在 setChecked 之前调用（Android 必须先 setCheckable 才能 setChecked 生效）

**版本**：Android 0.8.58/169 → 0.8.59/170

## DSH-109 fix2 — 排序菜单缺「按时间（新→旧）」

**症状**：排序菜单里名称、大小都是成对的（A→Z / Z→A、大→小 / 小→大），唯独时间只有「默认（最新在底）」一个方向，用户没法让最新作品排最前面。

**根因**：服务端 `SORT_KEYS` 早就支持 `time_desc`，是 Android 客户端 `SORT_MENU` 只列了 5 项、漏了 `time_desc`。

**修法**：菜单补 `time_desc`「按时间（新→旧）」，`time_asc` 标签改为「按时间（旧→新）」保持对称；`sortKeyLabel()` fallback 从 `SORT_MENU[0][1]` 改为查 `DEFAULT_ONLINE_SORT`（第一项换成 time_desc 后原写法会返回错标签）。

**闸门 A12**：time_desc 在菜单 + 6 键齐全 + fallback 不指第一项 + fallback 走默认。

**版本**：Android 0.8.59/170 → 0.8.60/171

## DSH-111 — 手机不会自动看到电脑新增的作品

**症状**：服务端 watchdog（DSH-110）已经让作品数据实时正确，但客户端只有「下拉刷新」和
「进页面拉一次」两条路。用户坐那儿不动，电脑上放新作品，手机永远停在旧列表。

**修法**：两端各加一排 30 秒定时器，先问 `/api/online/status` 拿
`totalWorks + watchdog.lastChangeAt` 做指纹，**指纹没变就完全不动 UI**（零打扰），
变了才调真正的刷新。配四道守卫：滚动中 / 搜索中 / 最近 8 秒动过 / 退后台，都不刷。

**闸门 A13**（两端一起查，吸取 A9 只查 Android 漏了 iOS 的教训）。

### ⚠️ 这个改动里抓到的「假闸门」（重要方法论）
第一版 A13 判据写成 `searchInput.isFocused() in and_main`、
`uiHandler.postDelayed in and_main`。做闸门自检时把真守卫代码删掉，
**A13 照样 PASS 37/37** —— 因为 `searchInput.isFocused()` 在滚动监听里也有一处，
`uiHandler.postDelayed` 在缩略图排水逻辑里有 5 处。判据查的是整个文件的短子串，形同虚设。

修法：**用花括号配平取出方法体，只在方法体内查**（`_method_body(src, signature)`）。
改完后同样的删除操作 → A13 正确报 FAIL（`and搜索守卫=False`、`ios滑动守卫=False`）。

> 教训：判据粒度 = 用户消费的粒度。查短子串 = 没闸门。
> 每次加了自动刷新/守卫类实现，必须做**源码级删除**自检（不是只把判据变量改成 False）。

**版本**：Android 0.8.60/171 → 0.8.61/172；iOS 0.8.41/113 → 0.8.42/114

## DSH-112 — 苹果端从来没有排序按钮（DSH-109 只做了安卓）

**症状**：安卓有排序按钮 + 6 种排序，苹果端 `Album/` 下 0 处 sortKey/sortBy，
整个功能从没做过。用户：「你那个苹果的按钮还是没有」。

**根因（重要）**：A9 闸门**只查 Android 端**。
对等契约里最危险的失效方式 —— 单端通过冒充两端通过，绿灯掩盖了整整半边缺失。

**修法**：
- iOS 补排序按钮（UIAlertController actionSheet，因部署目标 iOS 12 不能用 UIMenu）
- iPad 必设 popoverPresentationController（不设会崩）
- A9 闸门改为两端都查，新增 6 项 iOS 判据

### 自检教训（第二次踩同类坑）
第一版探针把 `onlineSortButton` 改成 `onlineSortButtonZZZ` 来模拟「iOS 没做」，
结果 A9 **照样 PASS** —— 因为 `onlineSortButtonZZZ` **包含** `onlineSortButton` 子串，
探针根本没删干净。改成不含原串的名字（`dummySortBtn`）后才正确报 FAIL。

> 探针本身也要自检：确认残留计数为 0，再信它的 FAIL/PASS。

**附带**：DSH-110 开机自启 .lnk 曾指向没人生成的 `_autostart_wrapper.ps1`，
已删 wrapper 并重新指向 `restart_online_gallery.ps1`。

**版本**：iOS 0.8.42/114 → 0.8.43/115

## DSH-112 fix2 — 开机自启 .ps1 因缺 BOM 完全无法执行

**症状**：`install-autostart.ps1` 执行即报「表达式或语句中包含意外的标记」，
而且因为子进程的 stderr 被吞，一开始看不出是编码问题。

**根因**：三个 DSH-110 新增的 .ps1 存成 **UTF-8 无 BOM**。PowerShell 5.1 默认按 ANSI/GBK
读取无 BOM 的脚本，中文与 emoji 变成乱码。乱码若只出现在 `#` 注释里还能侥幸运行
（`start_online_gallery_service.ps1` 正是如此，所以它能启动服务），
一旦落在 `Write-Host "✅ ..."` 这类**字符串**里就是语法错误 —— 脚本整个废掉。

**修法**：补 UTF-8 BOM（`EF BB BF`），与仓库既有脚本 `build-local.ps1`、
`copy-usb-apk.ps1` 的写法对齐。

**闸门 A14**：校验这三个 .ps1 前 3 字节。剥 BOM 自检 → 正确 FAIL。

> 教训：Windows 下给 PowerShell 5.1 写**含中文**的 .ps1，必须带 BOM。
> 只含注释也可能在某天被人往字符串里加中文后突然挂掉。


## DSH-112 fix3 — 开机自启双入口 + restart 未传 -Restart（2026-09-25）

| 项 | 内容 |
|---|---|
| 现象 | ①`restart_online_gallery.ps1` 注释称传 `-Restart` 但代码没传；②启动目录有 .vbs + .lnk 两份自启 |
| 危害 | 都是静默故障：服务继续跑旧代码（历史 thumb=1 回落发原图、手机在线回收站卡死）；`-Uninstall` 卸载不干净 |
| 根因 | ①漏传参数；②2026-09-20 的 .vbs 遗留入口未被 DSH-110 的安装器接管 |
| 修法 | ①补 `-Restart` 并写清理由；②install-autostart 注册/卸载两条路径都清 legacy .vbs；本机 .vbs 送回收站 |
| 闸门 | A15（3 判据，源码级删除自检 FAIL×2，还原 PASS） |
| 教训 | 裸子串判据会造**假闸门** —— `-Restart` 在注释里出现 3 次，删实现仍 PASS；必须查完整调用语句 |



## DSH-113 — 局域网更新中转（2026-09-25）

| 项 | 内容 |
|---|---|
| 现象 | CI 全绿、包已发布，但真机停在 0.8.58/169；用户体感「新功能手机上没有」 |
| 根因 | 手机先问电脑要 `/latest.json`（404，服务端没这路由），回落 raw.githubusercontent.com 又超时（本机测 10s、http_code=000），手机无代理 ⇒ 永远拿不到清单 |
| 修法 | 服务端新增 `/latest.json` + `/download/apk` / `/download/ipa`：走 7897 代理代取，URL 改写局域网，版本号与 sha256 原样保留；按版本磁盘缓存 |
| 效果 | 客户端零改动；Android 下次回前台即升到 0.8.61。端到端实测 GET 到的 APK sha256 与 GitHub 原包一致 |
| 自创判据事故 | 曾用「包必须 >1MB」判成功，把 0.86MB 的真 APK 判废 ⇒ **判据一律用清单自带的 sha256，与手机端同口径** |
| 闸门 | A16（10 判据；删路由/换假 sha/去代理 三项自检均正确 FAIL） |
| 遗留 | iOS 无 LAN 更新通道（0 处代码），iPhone 仍走电脑装机 |



## DSH-114 — 扫描缓存 TTL < 单次扫描耗时 ⇒ 缓存命中率恒为 0（2026-09-25）

| 项 | 内容 |
|---|---|
| 现象 | 手机连接/刷新固定干等 8~11 秒；同接口间隔 7 秒连打三次无一命中缓存 |
| 根因 | 缓存判据写死 `< 5.0` 秒，而一次全量扫描要 5.2~10 秒 ⇒ 缓存写完即过期，命中率恒为 0 |
| 铁证 | 改前 `scan()` 紧接着 `scan()` = 5.244s；改后 = 0.0000s |
| 修法 | TTL 5.0 → `SCAN_CACHE_TTL = 30.0`；新鲜度交给 DSH-110 watchdog（60s 轮询 + force 失效），放宽 TTL 不会变旧 |
| 效果 | 同测法 0.025 / 0.015 / 7.85 秒（改前 11.42 / 10.63 / 7.95） |
| 闸门 | A17（3 判据，两项源码级自检均 FAIL） |
| 回归 | 服务端 29 项单测全过 |
| 类型 | **静默故障**——不报错，只是永远慢 |



## DSH-115 — 中转出网代理写死 7897 导致静默失效（2026-09-26）

| 项 | 内容 |
|---|---|
| 现象 | `/download/apk` 返回 110 字节报错体（WinError 10054），手机拿到过不了校验 |
| 根因 | 代理写死 127.0.0.1:7897；Clash 混合端口会 7890/7897 回跳，节点也偶发掉线 |
| 同类 | 2026-09-25 CDP 产线因「守护停在 7890、端口跳到 7897」整条停产 |
| 修法 | 多候选轮试：上次成功 > 显式 env > 7897/7890/7891/7892 > 直连；新增 `_update_fetch()`，清单+安装包两处都走它 |
| 闸门 | A18（6 判据，三项源码级回退均 FAIL） |
| 教训 | 变异串要带 `\r\n`（纯 CRLF 文件），否则自检脚本自己先 assert 失败 |
| 类型 | **静默故障**——不报错，就是不给包 |



## DSH-116 — 更新中转的安装包缓存只按版本落名（同版本重发 ⇒ 手机校验必失败）（2026-09-26）

| 项 | 内容 |
|---|---|
| 现象 | 中转 `downloads` 在涨、`downloadFailures=0`、无报错；手机下到 883308 字节却判「更新包校验失败」并删包，永远装不上 |
| 根因 | 缓存名只按 version_name 拼；GitHub 以同一 version_name 重发重新构建过的包（**字节不同、体积相同**）；命中分支读完直接 return，从不校验 sha |
| 证据 | 清单/SHA256SUMS 声明 `362908b0…`，中转实际下发 `45635c6a…` |
| 修法 | `_update_cache_file()` 把 sha 编进缓存名；命中分支也重算 sha，不一致即删重下；`shaMismatch` / `cachedSha` 进健康快照 |
| 闸门 | A19（8 判据，四处源码级回退全部 FAIL，还原逐字节相同） |
| 教训 | ①缓存名必须带**内容指纹**，不能只带版本号；②「命中」分支同样要校验，别只校验冷路径；③自检变异串不能用空串 |
| 类型 | **静默故障** —— 中转全绿，客户端永远装不上 |


---

## DSH-117 — 服务端「持锁再扫描」自死锁 + 单目录异常连坐整轮（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | ①服务刚起、缓存还没建时，手机点开任意作品详情 ⇒ 整个 45835 端口永久卡死（不报错、不超时、线程数只增不减）；②根目录直出的成品会「整套消失」而全程零报错 |
| 根因 | ①`WorkScanner._lock` 是 `threading.Lock()`（非重入），而 `get_work()` 已持锁时内部会再调 `scan()`，`scan()` 开头又要拿同一把锁；②根目录扫描循环只有**外层**一个 `try`，任一作品目录读炸就把整轮结果全部吞掉，而 stage0 分支因为有内层 try 安然无恙 |
| 同类 | 同批次还修了：`send_json` 序列化异常整条 500；`status` 心跳每次全量扫描；`refresh=1` 无限速；监听队列只有 5；`sync-phone-counts` 任意 host（SSRF） |
| 修法 | `threading.RLock()`；每作品独立 try + `_scan_error()` 留痕；三级序列化降级；新增 `snapshot_count()` / `scan_throttled()`；`_LanHTTPServer` 设 `daemon_threads` + 队列 128；`_normalize_sync_host()` 私网白名单 |
| 闸门 | A20 / A21 / A22（18 判据，变异回退全部 FAIL 且逐字节还原） |
| 教训 | 「同一个类里既有方法 A 持锁调 B、B 又自己加锁」是自死锁的固定形状，看到 `Lock` 就要问一句 Caller 有没有锁；「一个 try 包整个循环」等于把单点故障放大成全量故障 |
| 类型 | **静默故障** —— 不报错，只是永久卡死 / 整批消失 |



## DSH-118 — 两端健壮性与口径统一批次（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | Android：8/9 设备一点分享就闪退；冷启动/设置页刷新时前台服务被系统杀掉；下载中断留下的半张图被当成成品复用。iOS：特定机型读取钥匙串崩溃；传送/下载界面无限转圈；分享失败查不到原因；关掉传送页后仍在后台扫局域网 |
| 根因 | Android：`RELATIVE_PATH`/`IS_PENDING`/`VOLUME_EXTERNAL_PRIMARY` 是 API 29+ 而 `minSdk=26`（运行时 `NoSuchFieldError`，编译期不报错）；两个 action 漏 `startForeground()`；原图直写最终文件。iOS：`as! SecKey` 强转；`semaphore.wait()` 无超时；`try? write` 静默；target-action Timer retain self |
| 口径差 | 图片回收站目录名 iOS `.图片回收站` vs Android `.image-trash`；字数 iOS `.count` vs Android UTF-16 码元；iOS 多一个名不副实的「一键直接发布」入口 |
| 修法 | 见 CHANGELOG 同条目；一句话概括：**该加超时的加超时、该加版本分支的加版本分支、该原子写的原子写、两头叫法不一样的统一到 Android 口径（读取仍兼容旧名）** |
| 闸门 | A23 / A24（14 判据）+ C8 判据修正（按形状判定），变异回退 **13/13** FAIL 且逐字节还原 |
| 教训 | ①**否定判据必须只看代码行** —— 修复说明里必然要写出旧按钮名字，整文件扫字符串会被自己的注释判死；②判「存在某个 API」容易判假绿（别处本来就有），必须正反两面都判；③`DiagnosticLog` 必须提供无 Context 重载，否则所有静态工具类的失败路径天然零留痕 |
| 事故 | 变异自检脚本把「断言」写在「还原」**之前**，断言抛错时源文件被留在变异态（`semaphore.wait()` 无超时版本已悄悄落盘）—— 已修正为 **还原必须放在 `finally` 里** |
| 类型 | 混合：崩溃 + 静默故障 + 口径不一致 |


## DSH-120 — Windows 便携版发布：取地址 KeyError + 中文文件名被 artifact 静默截断（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | ①`publish-gallery-updates` job 直接红：`KeyError: 'browser_download_url'`，整个发版链断；②Release 上出现名字残缺的附件 `-Windows-V4.3.30.exe`，中文前缀「文件收发中控」整段丢失 |
| 根因 | ①`gh release view --json assets` 走 **gh 模板字段**，asset 里叫 `url`；`browser_download_url` 是 **REST API** 的字段名 —— 两套 API 面字段名不同。②`windows-portable` 在 **Windows runner** 上传 artifact、`publish` 在 **Linux runner** 下载，非 ASCII 文件名在这一跳被截断（GitHub artifact 服务对非 ASCII 文件名不可靠） |
| 危害 | ①是明红，好查；②是**静默故障** —— 文件照样上传、sha256 照样对、日志照样打印完整中文名，只有 Release 页面上那个附件名是残缺的 |
| 修法 | ①取 `url` 字段；拿不到兜底拼标准下载地址；最后必须 curl 到 200 才往下走。②发布前统一改 ASCII 名 `DeviceShareHub-Windows-V{版本}.exe`，改名后断言 sha256 不变；`使用说明.md` 按 `USAGE-Windows.md` 落一份 |
| 自愈 | 每次发布删掉本 tag 下「`.exe` 结尾且名字 ≠ 本次发布名」的旧附件，历史残缺附件自动清掉 |
| 闸门 | `_verify_workflow_dsh120.py`（YAML 块标量完整性 + `bash -n` + 4 段内嵌 python 单独跑 + 4 条逻辑回归），**含一条「旧写法确实 KeyError」证明修复是真修而非假绿** |
| 教训 | ①**gh 有两套 API 面，字段名不一样**：`--json` 模板字段 vs REST API。看到 `browser_download_url` 要先确认走的是哪一套（`UpdateChecker.java` 那份是对的，因为它走 REST）。②**跨 OS 的 artifact 中转不可信非 ASCII 文件名** —— 构建产物中文名在本地和 CI 日志里都正常，只有落 Release 时才发现断了；凡是「经中转的非 ASCII 名」，发布前一律自己定 ASCII 名并对内容做 sha 断言。③拿不到下载地址要**直接红**，不许静默写空 URL 进 `latest.json` —— 空 URL 会让手机端「有更新但下不动」，比 CI 红难查得多。④Windows 上 PATH 里的 `bash` 会先命中 `C:\Windows\System32\bash.exe`（WSL 启动器，被沙箱拦），跑 `bash -n` 必须用 Git 的绝对路径 |
| 类型 | 混合：①明红（CI 断链）；②**静默故障**（附件名残缺，全程零报错） |


## DSH-120 — 补充：两套 API 面混用导致第二轮红（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | 第一轮改用 `url` 字段后取地址成功了，改名与上传也都成功（Release 上已出现 `DeviceShareHub-Windows-V4.3.30.exe`），但**清理残留附件时 404**，job 挂掉 |
| 根因 | **混用了 gh 的两套 API 面**：`gh release view --json assets` 走 GraphQL ⇒ `id` 是 `RA_...` 节点 ID；REST 删除 `releases/assets/<id>` 要数字 ID |
| 实测 | `gh api -X DELETE .../assets/RA_kwDOTcht984jPLCm` ⇒ `404 Not Found`（与 CI 日志逐字一致）；换成 `.../assets/591179942` ⇒ `204` 成功 |
| 修法 | 只走 REST 一套：`assets_json=$(gh api repos/{repo}/releases/tags/{tag})`，id 是数字、地址字段叫 `browser_download_url`；删除与取 URL 共用同一份清单 |
| 顺序 | 清单必须在 upload 之后取（闸门加了行号先后断言） |
| 教训 | 「同一个工具的两套 API 面字段名不同」只是表象，**ID 形态也不同**（`RA_...` 节点 ID vs 数字 ID）。混用必错。判「有没有在用 GraphQL 面」比判「有没有用错字段名」更能防住这一类 |

## DSH-120-B — C++ 测试里 20 条判据一直在空跑：Release 下 assert 被编译掉（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | `content_store_tests`（8 条）+ `send_to_integration_tests`（12 条）用 `assert(...)` 做判据，CI 每次报 `4/4 passed`，看起来一切正常 |
| 根因 | CI 编 **Release**（预定义 `NDEBUG`）⇒ `assert` 被预处理器整段编译掉 ⇒ 20 条判据一条没执行，测试只是「跑一遍业务逻辑然后 `return 0`」 |
| 危害 | **假绿**。比没闸门更危险 —— 没闸门你知道要补，假闸门给的是假的安心。DSH-119 已记账，本条是修复 |
| 修法 | 抽 `tests/test_check.h` 提供 `CHECK` / `CHECK_MSG` / `Finish()`（普通函数，不受 `NDEBUG` 影响）；三个测试文件改用 CHECK；收尾 `return dsh_test::Finish(...)` 把失败翻译成退出码 |
| 防复发 | 新增闸门 `scripts/check-cpp-test-assertions.py`（已挂 CI）：①禁用 `assert` ②禁用 `<cassert>` ③必须有失败返回非 0 的出口 ④断言数 ≥ 3 ⑤每个 `tests/*.cpp` 都必须被 `add_executable` + `add_test` 注册 |
| 变异自检 | 5 项退回坏版本 ⇒ **5/5 全部 FAIL** 且逐字节还原 |
| 意外收获 | 变异自检当场抓出**闸门自己的假绿**：扫 CMakeLists 时没跳过 `#` 注释行，把 `add_test(...)` 注释掉后正则照样匹配得到 ⇒ 闸门不响。已加 `strip_cmake_comments()` |
| 教训 | ①**用正则扫配置文件判「有没有某行」之前必须先把注释摘干净** —— 注释掉的配置等于没有，不摘就是假绿。②「测试文件存在」≠「闸门在跑」，要同时确认三件事：断言没被编译掉 / 失败会返回非 0 / 测试被 CMake 注册。③**变异自检的价值不只在于验证被测代码，更能抓出闸门本身的缺陷** —— 这次假绿就是它抓出来的，靠肉眼 review 大概率溜过去 |
| 类型 | **假绿闸门**（静默故障，全程零报错） |


## DSH-121 — 电脑端永远收不到更新提示：拿手机端版本号跟电脑端版本号比（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | 用户：「我一直觉得这个电脑客户端需要升级」。而客户端的「检查更新」永远回「当前已是最新版本」 |
| 根因 | `CheckForUpdates()` 读**主仓库** team-video-workflow 的手工 Release tag 再跟 `APP_VERSION` 比：①主仓库 Release 靠人手发，CI 不更新（实测停在 V4.3.29，代码已是 V4.3.30）；②一旦 tag 不是 Windows 版本（如手机端 v0.8.63），`4.3.30 > 0.8.63` ⇒ 永远判「已是最新」 |
| 危害 | **静默故障** —— 用户从此再也收不到电脑端更新提示，全程零报错。且主仓库 Release 一旦忘了发，就永远停在旧版 |
| 修法 | 真源换成发布仓库 `latest.json` 的 `windows` 段（CI 每次发版自动写）；判定逻辑抽到 `src/update_check.{h,cpp}`（纯逻辑可单测）；原 GitHub Release 检查保留为兜底，清单不可比时把原因一起报出来 |
| 关键判据 | **口径错配守卫**：当前版本比清单新且主版本号不同 ⇒ 拒绝比较（而不是静默说「已是最新」）；且主版本真升级（4.3.30→5.0.0）不能被守卫误伤 |
| 验证 | 三层：①`_verify_update_check_logic.py` 用开关翻转模拟「去掉判据」，5 项变异全部让对应断言失效；②静态检查确认 C++ 里有对应实现；③CI 的 ctest 真编译真运行 |
| 意外收获 1 | **新闸门当场抓到未注册的测试** —— `update_check_tests.cpp` 建好还没注册时 P5 直接红，且挂在昂贵的 vcpkg 构建**之前**。这就是 P5 存在的意义 |
| 意外收获 2 | **闸门自己先红了一次**：脚本打印中文在 Windows runner 上 `UnicodeEncodeError`（默认 stdout 是 cp1252）。加兜底后又新增 P7 扫全仓，抓出另外 3 个脚本同款风险 |
| 意外收获 3 | 写测试时发现的**真 BUG**：`ParseWindowsSection` 没有括号深度感知 ⇒ `{"ios": {"windows": {...}}, "windows": {...}}` 会取到 ios 内部那个，拿到别人的版本号且零报错。已改为只认顶层（depth==1）的键 |
| 意外收获 4 | `content_store_tests` 缺 `/utf-8` ⇒ 里面的 `L"D:\素材库"` 被 MSVC 按 GBK 解释，宽字符串是错的，但测试只断言「存取相等」，两边都错照样过。已给所有测试 target 补 `/utf-8` |
| 教训 | ①**陷阱数据要能真正区分好坏**：`\"windows\"` 在 JSON 值里转义成 `\ " w i n d o w s \ "`，`windows` 后面紧跟的是反斜杠，压根匹配不到 ⇒ 那个「陷阱」是假的、断言白过；`a}b{c` 左右括号抵消，不带字符串感知的实现也能误打误撞配对成功 ⇒ 陷阱失效。**造陷阱时必须验证「坏实现确实会挂」**。②**否定判据只看代码行**（第三次栽在这上面了）：注释里必然会写出反例字符串，扫全文会被自己的说明文字判死 |
| 类型 | **静默故障**（更新检查永远说「已是最新」，全程零报错） |

## DSH-125 — 设置窗口固定 900px 高且不可缩放：笔记本上底部一截看不见也拉不回来（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | 设置窗口写死 `width=760, height=900`，样式只有 `WS_OVERLAPPED \| WS_CAPTION \| WS_SYSMENU \| WS_MINIMIZEBOX` —— 没有 `WS_THICKFRAME` ⇒ **不能缩放**。而内容底部（「关闭」按钮下沿）在 y=864 |
| 危害 | 在 **1366×768 / 1440×900** 这类笔记本上，900px 的窗口比屏幕工作区还高：底部的「常规与软件」整组（开机自动启动 / 暗色模式 / 打开诊断日志）和「关闭」按钮**掉到屏幕外**，而且窗口拉不大、也没有滚动条 ⇒ 功能其实都有，但根本点不到 |
| 为什么之前没人报 | 开发机是 1080p 及以上的大屏，900px 刚好放得下；只有笔记本上才暴露 |
| 修法 | ①初始尺寸按 `SPI_GETWORKAREA` 自适应（`height = clamp(工作区高 - 60, 480, 900)`）；②加 `WS_THICKFRAME \| WS_MAXIMIZEBOX \| WS_VSCROLL` 允许缩放与滚动；③内容高度常量化 `kSettingsContentBottom`，滚动时按「初始 y − 偏移量」整体平移所有子控件；④`WM_GETMINMAXINFO` 设最小 620×420，防止被拉成小条 |
| 关键设计 | 控件初始 y 在 `WM_CREATE` **全部建完之后**才用 `EnumChildWindows` 记录 —— 早一步会漏掉后面建的控件，滚动时那些控件就不动 |
| 附带好处 | 后面继续往设置里加分组（本轮的「在线手机」）**不用再回头算窗口高度**，改一个常量即可 |
| 类型 | **可用性缺陷**（静默：不报错、不崩溃，只是「点不到」） |

## DSH-122 — 客户端看不到「哪台手机在线」：服务端接口早就有了，客户端没入口（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | 用户想知道「现在有哪台手机在看电脑相册、各发了几次」，只能自己开命令行跑 `scripts/phone_sync.py`。客户端里**完全没有入口** |
| 根因 | 服务端 `online_gallery_service.py` 早就把能力挂成了两个接口：`GET /api/online/phones`（扫 /24 找在线手机）与 `POST /api/online/sync-phone-counts`（把手机本地分享次数回写到电脑）。但客户端源码里这两个字符串一次都没出现过 |
| 为什么必须收进客户端 | 「手机端发了 2 次、电脑端还显示已发送 0 次」这种两端漂移，用户**在任何一个界面上都看不出来**，只会觉得「目录判断怎么不准」 |
| 修法 | 新增 `src/phone_panel.{h,cpp}`：纯逻辑（JSON 解析 + 文案）与系统操作（本机 HTTP）分开；设置窗口加「在线手机」分组，含状态行、设备列表、刷新 / 预演同步 / 同步次数三个按钮 |
| 边界 | **只补次数不搬文件**、**只增不减**这些铁律仍由服务端把关，客户端不重新实现一遍 —— 避免出现两套口径 |
| 意外收获（真 BUG） | 用 Python 复刻解析核心做区分度验证时，抓出 `ObjectBody()` 里 `depth` 初值给 1、但循环又从 `{` 本身开始扫了一遍 ⇒ depth 变成 2，结尾的 `}` 永远回不到 0 ⇒ **所有响应都会被判成「不是合法 JSON」**。本机无 MSVC，这个 bug 只能靠 CI 编译后才暴露，复刻提前抓了出来 |
| 判据验证 | `_verify_phone_panel.py`：Python 逐行复刻解析核心，6 条陷阱逐个关掉对应守卫 ⇒ **6/6 全部 FAIL**，还原后回绿 |
| 类型 | **功能缺失**（不是故障，是根本没接） |

## DSH-123 — 桌面那三个在线相册入口要靠脚本手动生成，且依赖 pywin32（2026-09-27）

| 项 | 内容 |
|---|---|
| 现象 | `scripts/create-desktop-shortcuts.py` 要在桌面生成三个 .lnk（启动 / 状态 / 开机自启），得手动跑，而且依赖 `pywin32` |
| 影响 | 装了客户端之后，这三件事仍然「只有知道脚本的人才会用」 |
| 修法 | 新增 `src/desktop_shortcut.{h,cpp}`，用 `IShellLink` COM 直接生成，不依赖 Python / pywin32；设置窗口「常规与软件」里加「桌面快捷方式」按钮 |
| 细节 | ①「启动」那个必须 `SW_SHOWMINNOACTIVE=7`：它拉起的是后台服务，弹黑框会被用户当成崩溃顺手关掉，服务也就没了；②图标名与描述**一律不含 emoji**（DSH-110 教训：ANSI 通道会让 .lnk 生成失败或显示成问号，且不报错），并加了 `HasSurrogatePair()` 守卫；③只创建不删除 —— 删图标是用户在桌面上的操作，替他删属于越界；④写「当前用户桌面」而不是 Public（后者要管理员权限、会弹 UAC） |
| 静默失效预防 | 目标 .cmd 不在时，.lnk 照样建得出来，但双击会失败。创建结果里会逐条标注「⚠ 目标文件不在，双击会失败」，不等用户来问 |
| 类型 | **易用性缺口** |
