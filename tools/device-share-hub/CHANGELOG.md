# 变更记录

## Android 0.8.52 / 163 - 2026-09-24 - DSH-097 同步：Android `modeButton` 双模式可见 + 行为分流

> **用户口径**（与 iOS 一致）：「至于那个文件夹按钮，我说的在线模式也可以留……要是本地文件分发也需要那就一起」+「确定统一2的，那就安卓苹果一起升级一起改」。
> **我的反思**：Android `MainActivity.java` 的 `modeButton`（图标 `ic_file_folder`，line 417）原本在 `isOnlineMode` 时直接 `setVisibility(View.GONE)`，与 iOS `folderItem.isHidden = isOnlineMode` 是同一处破缺对称的入口砍掉。DSH-097 在 iOS 已把 `folderItem` 改成"双模式可见 + openFiles 模式分发"，Android 端必须同步。

- **① Android `modeButton` 双模式可见**（`MainActivity.java:417-425`）：
  - 删 `if (isOnlineMode) { modeButton.setVisibility(View.GONE); }`（与 iOS `folderItem.isHidden = false` 对齐）
  - 删完后 `titleRow.addView(modeButton, iconParams(false))` 始终执行
- **② Android `modeButton.setOnClickListener` 模式分发**（`MainActivity.java:418-428`）：
  - 在线模式：调 `showTrash()`（跳到 DSH-095 统一回收站入口 = `_已发送1次 + _垃圾作品`）
  - 本地模式 + fileMode：调 `showWorksMode()`（原有行为）
  - 本地模式 + !fileMode：调 `showFileMode()`（原有行为）
  - 与 iOS `openFiles` `if isOnlineMode { openTrash(); return }` 语义对齐
- **③ Android 尺寸/颜色已对齐**（`MainActivity.java:2510-2518` `iconButton` 工厂 + `:2520-2524` `iconParams`）：
  - 42dp × 42dp（与 iOS 38pt 视觉对齐，< 1dp 差）
  - cornerRadius 21（半圆）
  - 浅绿 `RGB(226,244,236)` + 深绿 `RGB(15,135,88)`
  - 顶部按钮顺序已对齐 iOS：folder → title → plane（传送）→ sourceModeButton（切换）→ leftModeButton（刷新）→ rightModeButton（回收站）→ settings（设置）
- **④ 版本号**：Android `0.8.51/162` → **`0.8.52/163`**（改客户端代码必须升版本号）
- **⑤ 闸门**：`tests/parity_ios_android.py` 由 27 项**不扩**（C22/C23/C24 是 iOS-only 源码判据，Android 改动已在既有判据范围内 — 验 `updateSourceModeButtonStyle` 双态颜色 + `showTrash` 入口已存在）
- **⑥ 本地校验**：`parity_ios_android.py`：**27/27 PASS**（保持）
- ⚠️ **待真机验证**（`adb` + 红米 0.8.51 已发版可滚动抓 dump）：
  - 顶部左文件夹按钮在两种模式都可见
  - 顶右 5 按钮（传送 / 切换 / 刷新 / 回收站 / 设置）顺序与 iOS 一致
  - 在线模式点文件夹 → 应跳到 `showTrash` 屏幕（_已发送1次 + _垃圾作品）
  - 本地模式点文件夹 → 仍 toggle fileMode（文件浏览 ↔ 作品分享）

## iOS 0.8.37 / 109 - 2026-09-24 - DSH-097：iOS 主界面 toolbar 38pt 半圆浅绿统一 + folderItem 双模式分发

> **用户原话**：「顶部最左侧好像还差个文件夹按钮」+「无论本地还是在线都要有这个 界面啊这些个按钮，分类具体看本地现在已有的」+「为何你画的顶部要做作品集和在线相册几个（选 A：都不要）」
> **我的反思**：之前 `toolbarButton` 34pt + `cornerRadius = 11` + `tintColor.withAlphaComponent(0.11)`（颜色不固定、深浅随系统 tint 变）跟 Android `ImageButton` 42dp 半圆浅绿 RGB(226,244,236) 视觉差异大；之前 `folderItem` 在线模式直接 `isHidden = isOnlineMode` 把入口砍了，违背「本地/在线 1:1 对称」需求。合并成 DSH-097。
> **设计稿**：左文件夹 + 右 5 按钮（传送 / 切换 / 刷新 / 回收站 / 设置） + 分类滚动条 + 预览卡片 + 11 按钮 + 重置/删除/复制路径；两种模式除 `modeButton` 颜色（本地浅绿 / 在线浅蓝）外完全对称。标题栏留空（与代码 `navigationItem.titleView = nil` / `headingText.setText("")` 一致）。

- **① iOS `toolbarButton` / `toolbarItem` 升级 38pt 半圆浅绿**（`ios/Album/ContentView.swift:169-184, 257-273`）：
  - 尺寸 34pt → **38pt**（与 Android ImageButton 42dp 视觉对齐，< 1pt 差）
  - 圆角 11 → **19**（半圆）
  - 背景 `tintColor.withAlphaComponent(0.11)` → **`RGB(226,244,236)`** 浅绿实色
  - 图标 `tintColor` → **`RGB(15,135,88)`** 深绿
- **② iOS `folderItem` 双模式可见**（`ContentView.swift:164-168`）：
  - `folderItem?.customView?.isHidden = isOnlineMode` → **`isHidden = false`**（两种模式都可见）
  - `accessibilityLabel` 按 `isOnlineMode` 分流（在线=回收站 / 本地=本地文件浏览）
- **③ iOS `openFiles` 模式分发**（`ContentView.swift:1212-1218`）：
  - 在线模式：调 `openTrash()`（复用 DSH-095 的统一回收站入口 = `_已发送1次 + _垃圾作品`）
  - 本地模式：保持原有 `LibraryFilesViewController` 入口
- **④ iOS 撤销 DSH-096 高对比**（`ContentView.swift:2140-2171`）：
  - `resetButton`：橙底橙字 `RGB(0.85,0.55,0.1)` + `RGB(1,0.96,0.88)` → **浅灰** `RGB(0.32,0.36,0.34)` + `RGB(0.93,0.94,0.93)`
  - `deleteButton`：红底红字 `RGB(0.8,0.25,0.25)` + `RGB(1,0.92,0.92)` → **浅灰** 同上
  - `copyPathButton`：原本就浅灰，现在三个按钮统一（与 Android 行动行三件套同款）
- **⑤ iOS `PlatformFlowView` 居中布局**（`ContentView.swift:1665-1730`）：
  - `measure(width:apply:)` 重写：每行累计 `xInLine` + 末行结算 `lineXOffset = max((width - (lineWidth - horizontalSpacing)) / 2, 0)`
  - 行末 + 末行统一 `apply`：每个 view 的 `frame.x = lineXOffset + item.xInLine`
  - 与 Android `FlowLayout.onLayout` 的 `rowXCenter` 同语义（行级居中）
- **⑥ 版本号**：iOS `0.8.36/108` → **`0.8.37/109`**（改客户端代码必须升版本号）
- **⑦ 闸门**：`tests/parity_ios_android.py` 由 24 项扩到 **27 项**（新增 C22 + C23 + C24）
  - **C22**：iOS `widthAnchor.constraint(equalToConstant: 38)` + `layer.cornerRadius = 19` + `red: 226/255, green: 244/255, blue: 236/255` 浅绿背景 + `red: 15/255, green: 135/255, blue: 88/255` 深绿图标，且浅绿字符串出现次数 ≥ 2（=`toolbarButton` + `toolbarItem` 都改）
  - **C23**：`lineXOffset` + `lineRow` + `xInLine` 三变量齐全 + `lineXOffset + item.xInLine` 应用 + 旧式左对齐 `view.frame = CGRect(x: x,` 已删
  - **C24**：`folderItem?.customView?.isHidden = false` + 旧 `isHidden = isOnlineMode` 已删 + `openFiles` 内 `if isOnlineMode {\n            openTrash()\n            return` 模式分发 + `LibraryFilesViewController(rootURL: root, currentURL: root)` 入口保留
- **⑧ 本地校验**：`parity_ios_android.py`：**27/27 PASS**（24 旧 + 3 新）
- ⚠️ **待真机验证**（iPhone `dvt screenshot` 无 tap 能力 + WDA 不可用，必须装机手动点）：
  - 顶部左文件夹按钮在两种模式都可见
  - 顶部右 5 按钮（传送 / 切换 / 刷新 / 回收站 / 设置）都是 38pt 半圆浅绿
  - 切换按钮：本地模式浅绿+手机图标 / 在线模式浅蓝+电脑图标
  - 11 个版本按钮（数字爆款 / 分天动线 / 三箭头体 / 杂志长条 / 时间轴体 / 红书种草 / 红书大纲 / 货架明细 / 包院私享 / 案例背书 / 抖音无营销）**居中**布局（不再左对齐）
  - 重置 / 删除 / 复制路径三个按钮统一浅灰（不再有橙红高对比）
  - folderItem 本地模式 → `LibraryFilesViewController`，在线模式 → `TrashView`

## iOS 0.8.36 / 108 - 2026-09-23 - DSH-095 v2（iOS HIG native 重做）：TrashView 走 navigationItem.titleView + rightBarButtonItems（DSH-095 v1 的 tableHeaderView 装 UIStackView 是 Android inline header 的硬搬，iOS HIG 不这么做）

> **用户原话**：「iOS 的回收站太不符合直觉了你就不能参照安卓开发吗」
> **我的反思**：v1 把 segmented + 按钮塞进 `tableHeaderView`（高度 56→108）是 iOS 反直觉的；
> iOS HIG 是 `navigationItem.titleView`（标题即控制）+ `rightBarButtonItems`（右上按钮）。
> v2 改成纯 iOS-native，不再有 tableHeaderView 108 高度的卡顿、滚动跳变。

- **① iOS TrashView**（`ios/Album/TrashView.swift`）：
  - `viewDidLoad`：删 `tableHeaderView = UIView() + UIStackView(segmented + btn)`，改用
    `navigationItem.titleView = segmented`（标题栏正中，标准 iOS 模式）
    + `navigationItem.rightBarButtonItems = [「💻 电脑回收站」, 「清空」]`（从右到左）
  - `viewDidLayoutSubviews`：删 108 高度的 frame 计算（不再有自定义 header）
  - `render()`：`navigationItem.rightBarButtonItems` 全遍历按 totalCount 控制可用性（之前只控单 button）
  - 删 `makeOnlineRecycleEntryButton()`（蓝底浅蓝边按钮，v1 inline header 专用；v2 用 UIBarButtonItem 取代）
  - 按钮文案简化：「💻 打开电脑端回收站（_已发送1次 + _垃圾作品）」→「💻 电脑回收站」（更紧凑不挤标题栏）
- **② Android 不动**：v2 仅 iOS HIG 重做，Android `buildOnlineRecycleEntry` 行为不变（trash2.png 已实测验过）
- **③ 版本号**：iOS `0.8.35/107` → **`0.8.36/108`**（改客户端代码必须升版本号）
- **④ 闸门**：`tests/parity_ios_android.py` 由 22 项扩到 **24 项**（新增 C18 + A5）
  - **C18**：`tv = TrashView.swift` 判 `navigationItem.titleView = segmented` + `rightBarButtonItems` 含 `openOnlineRecycle`，且不再含 `tableHeaderView = header` 和 `let header = UIView()`
  - **A5**：复跑 A4 的判据（再抽 `rightModeButton.setOnClickListener(v -> {` lambda 体），证明 v2 没动 Android
- **⑤ 本地校验**：`parity_ios_android.py`：**24/24**；`verify-work-card-ui.mjs`：**21/21**
- ⚠️ **待真机验证**：iOS DSH-095 v2 装机后需要手动点回收站（pymobiledevice3 `dvt` 无 tap + `wda` 不可用）
  - 期望：标题栏正中 segmented 已删除/已标记垃圾，右上「💻 电脑回收站」+「清空」两个按钮（从右到左）
  - 点「💻 电脑回收站」→ 跳到电脑端回收站（双 Tab）

## Android 0.8.51 / 162 + iOS 0.8.35 / 107 - 2026-09-23 - 回收站统一入口：本地/在线模式都进同一屏（DSH-095）

> **用户口径（原话）**：「手机端我再说一次哈，回收站是本地相册和在线相册共用的，
> 顶多再回收站里面你显示是在线还是本地，就现在的本地相册界面点击回收站那个界面可以，
> 在线相册点击居然没进去这个界面」

**背景**：右上「回收站」按钮之前在两端都按 `isOnlineMode` 分流到不同屏幕（手机本地回收站 / 电脑端 OnlineRecycle），用户要求**两模式都进同一屏**，里面再放一个按钮跳原电脑端回收站。

- **① Android rightModeButton**（`MainActivity.java:471-477`）：删 `else if (isOnlineMode)` 分支，
  统一调 `showTrash()`。`showTrash()`（`:807`）已处理 `if (isOnlineMode) { enteredTrashFromOnline = true; }`，
  `showOnlineRecycle`（`:3187`）会把它复位；状态机闭合。
- **② Android renderWorksCards 顶部**（`MainActivity.java:909-915`）：在
  `worksContainer.removeAllViews()` 之后插入 `buildOnlineRecycleEntry()`（浅蓝底蓝字按钮
  「💻 打开电脑端回收站（_已发送1次 + _垃圾作品）」）。**仅在 `showingTrash && enteredTrashFromOnline`
  时插入** —— 本地模式直接进 trash 时不出现，避免冗余入口。
- **③ Android buildOnlineRecycleEntry**（`MainActivity.java:3305-3322`，与 recycleLocalTrashEntry 镜像）。
- **④ iOS openTrash**（`ContentView.swift:1194-1199`）：删 `if isOnlineMode` 分支，统一 push
  `TrashViewController`。
- **⑤ iOS TrashView**（`TrashView.swift`）：header 从单 segmented 改为 `UIStackView` 装 segmented + 按钮；
  `viewDidLayoutSubviews` 高度 56 → 108；新增 `makeOnlineRecycleEntryButton`（蓝底浅蓝边、圆角 14）+ 
  `@objc openOnlineRecycle` push `OnlineRecycleViewController`。
- **闸门**：`tests/parity_ios_android.py` 由 20 项扩到 **22 项**（新增 **C17** iOS openTrash 不再按
  isOnlineMode 分支 + **A4** Android rightModeButton 不再调 OnlineRecycle）。
  - C17 / A4 都先用 regex 抽函数体 / lambda 体，再用 `git show HEAD:` 验证改前 FAIL。
  - 跑：**22/22**；`verify-work-card-ui.mjs`：**21/21**。
- **本地校验**：Android `javac` 全源集编译 **exit=0**；iOS 仍只能靠 CI 的 `xcodebuild`。
- ⚠️ **待真机验收**：在线模式点回收站图标 → 进入的是本地回收站同一屏（顶部多一个「💻 打开电脑端回收站」按钮），
  本地模式点回收站图标 → 行为不变（顶部没有这个按钮）。

## Android 0.8.51 / 162 + iOS 0.8.35 / 107 - 2026-09-23 - 老三家文案前端化：版本改名 + 重排 + 按钮完整字数（DSH-094）

> **用户口径（原话）**：「他们是一样的文案是平级的，只是前面三个版本是我规定写在文档里面在前……
> 手机那边就是跟随 `文案.txt` 识别」「按钮要完整字数，不要限制 4 字，然后按钮自适应换行」。

**背景**：手机端按钮顺序一直由「客户端自己排」决定，不是由文案决定 —— 用户要的是**文件说了算**。

- **① 版本改名（老三家）**：`原生种草` → **红书种草**、`决策矩阵` → **红书大纲**、
  `抖音避坑` → **抖音无营销**。守卫真源 `copy_formatter.py` 的 `VERSION_FAMILY` 增 3 键
  （旧键保留做向后兼容）；两处 `vname_clean` 去掉 `[:4]` 截断。
- **② 存量 386 套 `文案.txt` 改名 + 重排**：把这三块按「红书种草 → 红书大纲 → 抖音无营销」
  移到**最前**，其余 8 版保持原相对顺序。**11 版平级、不做 rank**。
  - ⚠️ **`文案.txt` 换行风格并不统一**：实测全库 **490 套裸 LF / 184 套 CRLF / 1 套混合**。
    按 CRLF 统一写回会污染 490 套（伪全量 diff）⇒ 改成了**逐文件探测 + 原字符串切片重组**。
  - 独立复核 4/4：前三位正确 **386/386**、换行零变化、前后缀零变化、块正文零丢失。
- **③ 两端去 4 字截断**：`friendlyLabelForMarker` 里的 `substring(0, 4)` / `prefix(4)` 删除。
  最长的版本名是 5 字「抖音无营销」，原本两端都会砍成「抖音无营」。
- **④ Android 按钮渲染**：`styleNeumorphicButton` 的 `setMaxLines(1)` → `2`、纵向 padding 0 → dp(6)、
  平台行按钮高度 `dp(36)` → `WRAP_CONTENT`（单行仍由 `setMinHeight(dp(36))` 兜底，外观零变化）。
- **⑤ Android 顺序对齐 iOS（关键）**：`enrichPlatformSuite` 原本把「抖音」那一版**摘出来追加到末尾**，
  末尾还有 `result.sort(getButtonRank)` ⇒ 已排在文案最前的抖音版会被甩到整行最后。
  改为多版本时**原样返回**解析结果。iOS 本来就没有这段（`if !multiItems.isEmpty { return multiItems }`）
  ⇒ 这次是**安卓向 iOS 对齐**。
- **闸门**：`tests/parity_ios_android.py` 由 17 项扩到 **20 项**（新增 C14 不截断 / C15 顺序=文案顺序 /
  C16 允许折行且 iOS 行高同源）；`scripts/verify-work-card-ui.mjs` 修到当前结构（原本断言的
  `platformRow1` 自 DSH-091 C3 起就不存在，长期为红）并扩到 **21 项**。
  **⚠️ 这两条守卫此前从未被 CI 调用** —— 等于没有守卫。已一并接进 `windows-portable` 的步骤里。
- **本地校验**（Windows 上无 Swift 编译器，iOS 语法仍只能靠 CI 的 `xcodebuild`）：
  `javac` 全源集编译 **exit=0 / 127 个 class**；`PlatformCopyParserTest` **18/18 通过**
  （含新增的 2 条）；`parity_ios_android.py` **20/20**；`verify-work-card-ui.mjs` **21 项**；
  另 4 个 `verify-*.mjs` 全 PASS；`test_online_gallery_service` **27/27 通过**。
- ⚠️ **待真机验证**：按钮顺序（应为 红书种草/红书大纲/抖音无营销 打头）与「抖音无营销」不被截断。
- ⚠️ **手机端本地已导入的作品不会自动跟改**：本地卡的 `文案.txt` 是**手机侧副本**；
  在线模式（服务端 5 秒缓存 TTL）会自动跟随，本地卡需重导或删除后重下。

## Android 0.8.49 / 160 + iOS 0.8.34 / 106 - 2026-09-23 - 安卓自己也有三处不合理，一并修（DSH-093 续）

> **用户追问**：「安卓有时候会不会有不合理的」—— 对，别把安卓当圣旨。

- **A1 在线分页首屏 25、步长 30 —— 两个数不搭。** 首屏 25 却每次 +30，
  统一为 **30 / +30**（iOS 也同步）。
- **A2 本地卡会显示自相矛盾的内容。** 状态行判 `localUsedCount > 0`，
  明细行却判 `work.used` —— 两个条件不一样。结果是会出现：
  `📱 手机本地 · 9 图 · 未使用` 下面挂着 `✓ 小红书 0 · 抖音 0` 和「N 小时后自动删除」。
  改为同一个口径。
- **A3「🗑️ 垃圾样本」前缀：本地回收站有、在线卡没有** —— 安卓自己两处格式不一致，已统一。
- **C13（iOS 侧）**：iOS 有 `.xhs3` 枚举也会生成「短文精选版」按钮，但计数只判了
  `.xhs`/`.xhs2` ⇒ 点第三个小红书槽位分享后 **iOS 不计次数**，而安卓把 XHS/XHS_2/XHS_3
  都算进去 ⇒ 两端数字会逐步分叉。已补 `.xhs3`。
- **闸门**：`parity_ios_android.py` 扩到 **17 项**（新增 C13 + A1/A2/A3 反向项）。
  ⚠️ A3 初版判据「数 🗑️ 垃圾样本 出现次数 ≥2」**改前改后都 PASS**（那个 emoji 在 badge 里
  也有）⇒ 等于没有闸门。改成查在线卡那句 `append` 的精确形态后，
  用 `git show HEAD:` 的改动前代码验证：**改前 False / 改后 True**。
- ⚠️ 安卓本次改动未经 Gradle 本地编译，**依赖 CI 的 android-build**。

## iOS 0.8.33 / build 105 - 2026-09-23 - 称呼与口径统一（以安卓为准）+ 补两处看不见的元信息（DSH-093）

> **用户要求（原话）**：「所有的这个平台，最好的统一这个称呼啊，这些细节什么的……很多问题一下子看不出来的你可以站在这个上帝视角看一下」

- **这是「上帝视角」扫出来的隐性不一致 —— 编译得过、跑得起来、但数字/叫法对不上**：
  1. **同一个本地作品，安卓和苹果显示的「已使用 N 次」不是同一个数。**
     Android 用 `xhsShareCount + douyinShareCount`，iOS 用 `shareCount`。
     后果很具体：卡片会出现「已使用 3 次 / ✓ 小红书 1 · 抖音 1」这种自相矛盾的显示，
     而且同一作品在两台手机上数字不一样 —— 用户只会以为是数据坏了。
     ⇒ iOS 改为与安卓同口径（总数 = 明细之和）。
  2. **在线卡片少了整整两行。** Android 在线卡会拼「记录：X、Y」（分发去向）和
     「垃圾备注：…」，iOS 从来没有渲染过这两行 ⇒ 同一作品在电脑上能看到、手机上没有。
  3. **「垃圾备注」在三处叫三个名字。** Android 叫「垃圾备注：」，iOS 在线回收站和本地
     回收站都叫「备注：」⇒ 统一为「垃圾备注：」。
  4. **本地卡缺自动清理倒计时。** Android 有「N 分钟后自动删除 / 即将自动删除」，
     iOS 没有 ⇒ 补齐，文案逐字一致（iOS 用 `deleteScheduledAtMs` 推算剩余时间）。
- **方法**：不靠肉眼 diff，而是把两端「卡片元信息的拼接代码」逐行对照
  （Android `MainActivity:1072` 本地 / `:3333` 在线 ⇄ iOS `WorkCell.configure` /
  `configureOnline`），把每一行的存在与否当成一项契约。
- **闸门**：`tests/parity_ios_android.py` 由 9 项扩到 **13 项**（新增 C9~C12）。
  加完先跑 **4 项 FAIL** → 改完 **13/13 PASS**。
- ⚠️ **待真机验证**：设备未连 USB，本次四项均无截图证据。

## iOS 0.8.32 / build 103 - 2026-09-22 - 全量对等补齐：预览三出口 / 在线列表分页 / 回收站分页（DSH-092）

> **用户要求（原话）**：「安卓更新得会比较完善一点……不是哪一件，而是全部挨个补充，把它完善掉」

- **审计方法（可复用）**：写 `_audit_ui_strings.py`，抽取两端「用户可点的 UI 文案」
  （Android `setText/setTitle/Toast/AlertDialog` ⇄ iOS `setTitle/UIAlertAction/UIMenu/showToast`）
  做集合差集，拿到 82 条候选，再逐条人工判定是「安卓独有模块」还是「iOS 真缺口」。
  最终确认 3 个真缺口 + 1 条误报（C8 判据写死了文案，实际 iOS 有等价实现）。
- **修复**（`ios/Album/ContentView.swift`、`ios/Album/OnlineRecycleView.swift`）：
  1. **C5 文案预览补齐三出口**：Android 长按文案弹出的是「关闭 / 复制全文 / 前往使用」三按钮
     对话框，iOS 上一轮只做了「完成」。现补齐：副标题 `【版本名】 共 N 字`
     （字数口径与 Android 一致 —— `utf16.count`，不是 Swift 的 `count`）、
     正文可选中、**复制全文**（不分享不计次数）、**前往使用**（复制 + 唤起分享）。
  2. **C6 在线列表分页**：Android 首屏 30 条 + 「加载更多作品 (已显示 X / N 套)」；
     iOS 原先一次性全渲染 —— 成品库 400+ 套时 `sizeForItemAt` 会把 400 份文案全解析一遍。
     现加 `onlinePageLimit`（首屏 30，每次 +30）与页脚按钮，切分类/改搜索词自动回到首屏。
  3. **C7 在线回收站分页**：同上，`recyclePageLimit`，多出一行「加载更多」。
     ⚠️ 顺带堵一个**必崩**的坑：「加载更多」行若允许左滑，`works[indexPath.row]` 直接越界 ——
     左滑回调已加 `guard !isLoadMoreRow`。
- **闸门**：`tests/parity_ios_android.py` 从 5 项扩到 **9 项**。
  新增 C5/C6/C7/C8 后先确认 **4 项 FAIL**，改完 **9/9 全 PASS**（exit 0）。
  ⚠️ C8 是**判据写死文案**导致的误报：iOS 用的是「继续发布全部 / 继续发布」，语义同一回事。
  已把判据改成查能力（`work.shareCount > 0` 两处拦截 + 确认按钮），不再查逐字文案。
- ⚠️ **待真机验证**：iOS 设备未通过 USB 连接，本次三项均无截图 / 录屏证据。

## iOS 0.8.31 / build 102 - 2026-09-22 - 补齐三处 iOS 缺口：长按预览文案 / 顶部搜索 / 平台使用明细（DSH-091）

> **用户要求（原话）**：「还是展开吧，展开方便我去看」「在这边长按没有弹出那个文案的预览界面」「苹果顶部搜索框加了吗」「安卓和苹果功能要不缺」

- **缺口盘点（源码级核实，全部 Android 有 / iOS 无）**：
  1. **长按平台按钮 → 文案预览**：Android `MainActivity.java:1174/3700` 有
     `btn.setOnLongClickListener`；iOS 全仓库仅 2 处长按（模式图标配地址、详情页图片多选），
     平台按钮上没有任何手势 ⇒ 用户长按无反应。
  2. **顶部作品搜索框**：iOS 全仓库无 `UISearchBar` / `searchBar`。
  3. **本地卡片平台使用明细**：Android 有 `\n✓ 小红书 X · 抖音 Y`（MainActivity:1082），
     iOS 只有「已使用 N 次」总数，没有分平台明细。
- **修复**（`ios/Album/ContentView.swift`）：
  1. 新增 `CopyPreviewViewController`；`WorkCell` 加 `onCopyPreview` 回调 +
     `platformLongPress(_:)` 手势（按 `button.tag` 定位，MULTI 下多版本同名不会串）；
     在线 / 本地两条分支都接线。**零副作用**：不写剪贴板、不调 recordUse、不移库。
  2. 顶部加 `UISearchBar`，本地 / 在线列表共用 `searchQuery`，按作品名与合集名不区分大小写过滤。
  3. 本地卡片已使用时追加 `\n✓ 小红书 X · 抖音 Y`，与 Android 逐字对齐。
  4. **平台按钮换行全展开（C3）**：删掉 `platformScroll`（单行横滚），新增
     `PlatformFlowView` —— 把 Android `FlowLayout.onMeasure/onLayout` 的数学原样搬过来
     （横向/纵向间距 dp(8)、行高 dp(36)）。卡片高度在 `sizeForItemAt` 里按行数动态补偿：
     1 行 = 212，每多一行 +44；本地卡多一行明细再 +14。宽度故意收窄 1pt ⇒ 宁可高估留白，
     不低估裁切。**V4.5 的 11 个版本现在一眼看全，不用左右滑。**
  5. **C4 生效修复**：`WorkCell.detail` 从未设置 `numberOfLines`（UILabel 默认 1），
     第 3 条加的第二行其实被截断 —— 加了等于没加。现已设 0。
- **闸门**：新建 `tests/parity_ios_android.py`（源码级三端对等契约，5 项）。
  强化后改前 **2 项 FAIL**（C3 三项全 false；C4「附加行=True / 多行生效=False」）
  → 改后 **5/5 全部 PASS**（exit 0）。
  ⚠️ 强化前 C4 是**假 PASS** —— 只查「✓ 小红书」这串文本存在，没查它能不能显示出来。
- ⚠️ **待真机验证**：本次四项均无真机截图/录屏证据（iOS 设备未通过 USB 连接）。

## iOS 0.8.30 / build 101 - 2026-09-22 - 在线分享到小红书不再「一堆一样的图」（DSH-090）

> **用户要求（原话）**：「苹果点击分享到小红书，分享了一堆一样图片，但是预览看到的是不同的图片」

- **根因**：三端在线多图分享的**载体**本应一致，iOS 在线分支是唯一异类——
  本地相册传 `NSURL`、Android 传 `Uri`，唯独 iOS 在线把 `[UIImage]`（内存位图数组）
  直接交给 `UIActivityViewController`。位图经 pasteboard 交给小红书 share extension 时
  会被读成同一张 ⇒ 分享串图；而预览走自持 UIImage 逐张加载、不经 pasteboard ⇒ 预览正常。
- **修复**（`ios/Album/ContentView.swift`，只动在线分享一条路径）：
  1. `downloadAllImages` 返回 `[URL]`：原图下载后写入
     `NSTemporaryDirectory()/online-share-<UUID>/`，文件名带 `序号_` 前缀保序且不覆盖；
  2. 分享载体改为 `urls.map { $0 as NSURL }`，与本地相册 `prepareShare` 逐字同机制；
  3. 分享面板关闭后清理临时目录；
  4. 修正假文案「✅ 原图已全部同步到手机」（代码里根本没有存相册动作）→「✅ 已准备 N 张原图…」。
- **服务端清白证明**：520 件作品 `images` 数组**完全重复 0 / basename 重复 0**；
  逐张请求 `/api/online/image?thumb=0` 算 md5，抽查 5 件（6/6/9/9/10 图）**互不相同**。
- **闸门**：`audit_three_end_parity.py` 新增第 12 项契约「在线多图分享·载体」。
  修前 **exit 1**（`AND=FILE_URI / iOS=UIIMAGE`），修后 **exit 0**（两端均 `FILE_URI`），前 11 项修前后均一致。
- iOS `MARKETING_VERSION 0.8.30` / `CURRENT_PROJECT_VERSION 101`；Android 本版**未改动**。
  iOS 本机无法编译，**须由 CI `ios-altstore-build` 验证**；装手机后要用 ≥5 图的作品实际分享到小红书复验。

## 0.8.48 - 2026-09-21 - 卡片元信息三端统一（iOS 样式 + 日期后缀）与 iOS 按钮尺寸对齐安卓

> **用户要求（原话）**：「如果是在线相册，那么他们要复制的路径都是电脑文件夹所在的路径，删除也是
> 删除电脑文件夹里的内容……除非是本地相册，那操作的就是手机上的。」「卡片的原信息，你看本地的可以，
> 你可以按苹果的那个来。你可以在后面加上那个日期，没毛病。」「引用次数的话，你可以看 iOS 那个
> 已使用多少次。反正尽量对齐。」「iOS 这边的按钮太小了。按道理来说，这些按钮应该是自适应的，
> 就和安卓那边一样那种效果。」

Android `versionName 0.8.48` / `versionCode 159`；iOS 对应 `MARKETING_VERSION 0.8.29` /
`CURRENT_PROJECT_VERSION 100`。**注意：本版未 push，未触发 CI；装上手机需另行发版。**

**① 卡片元信息两端各写一套（在线「N 张图片 · 电脑」vs「💻 电脑在线 · N 图 · 未使用」）**

统一为 **iOS 口径 + 日期后缀**：
- 在线卡片：`💻 电脑在线 · N 图 · 未使用` / `💻 电脑在线 · N 图 · 已使用 N 次`，末尾追加 ` · MM-dd HH:mm`。
- 本地卡片：`📱 手机本地 · N 图 · 未使用` / `📱 手机本地 · N 图 · 已使用 N 次`，末尾追加 ` · MM-dd HH:mm`。
- 日期解析两端同源：Android 复用既有 `extractTimestampBadge`；iOS 新增
  `WorkCell.cardDateSuffix(_:_:)`（正则与 Android 逐字相同）；取不到就省略（不显示空占位）。
- Android 在线元信息有**两个渲染器**（在线相册主列表 + 在线回收站条目），本版**两处同时改**，
  避免只改一处造成新的不一致。

**② 使用次数文案统一**

Android 平台按钮的无障碍文案由「已点击 N 次」改为「已使用 N 次」，与 iOS
`OnlineRecycleView` 既有「已使用 N 次」口径一致。

**③ iOS 按钮尺寸对齐安卓（用户报「按钮太小」）**

安卓平台/操作按钮为 `dp(36)` 高、`sp(12.5)` 字、左右 `dp(15)` 内边距；iOS 是 **28pt 高**、
`12.5` 字、左右 `10` 内边距 —— 明显偏小。本版把 iOS 五处按钮（平台/版本按钮、重置换、删除、
复制路径、空壳占位）统一为 **36pt 高 + 13pt 字 + 左右 15 内边距**，平台按钮行高 28→36、
行间距 6→8（对齐安卓 `dp(8)`），卡片固定高度随之 196→212。

**④ 闸门：先加契约项、后改实现**

`_parity_audit/audit_three_end_parity.py` 契约项 **7 → 11**，新增
`卡片元信息·在线前缀` / `卡片元信息·本地前缀` / `卡片元信息·使用次数文案` / `卡片日期·解析格式`。
留痕：**改实现前 FAIL（3 项不一致，exit 1）→ 改实现后 PASS（0 项，exit 0）**
（`_parity_audit/_before.txt` / `_after2.txt`）。

**⑤ 行尾与规格零漂移**：`MainActivity.java` 纯 LF（0 CRLF，Δ+1079B）；`ContentView.swift`
纯 CRLF（1813=1813，Δ+1714B）；两端均无 BOM。改写走字节级脚本
`_patch_card_meta_parity.py`（锚点强制唯一，命中数不符即中止），未用会把 CRLF 归一成 LF 的文本编辑。

**未做（已明确留给用户拍板）**：iOS 平台按钮仍是**单行横向滚动**，安卓是 `FlowLayout` 自动换行。
改成换行需把卡片高度从固定值改为**自适应**（`estimatedItemSize` + 自绘 flow 容器），属结构性改动，
且本机无 macOS 无法编译验证，故单独留待下一次并由 CI 验证。

## 0.8.47 - 2026-09-21 - 三端功能对等补齐：iOS 补上「空壳挡住分发」与「在线镜像区彻底删除」

> **用户要求**：「不管 iOS 还是安卓，双端的功能一定要差不多。不能只测试一个不测试另一个，
> 或者出现一个有按钮、另一个没有按钮的情况。……这块你可以再检查一下。」
> 「电脑客户端需要和手机端联动……也就是所谓开发是『三端同步』。」

本版做的是**能力集合对齐**（布局代码可以不同，有/没有不行）。Android `versionName 0.8.47` /
`versionCode 158`；iOS 对应 `MARKETING_VERSION 0.8.28` / `CURRENT_PROJECT_VERSION 99`。

**① iOS 没有「空壳作品」守卫，空壳也能点能发（DSH-088 G1）**

Android 早就有一道守卫：剥掉协议标记与 `[\s\u2800]` 后**实质字数 < 30** 即判空壳 ——
在线卡上把整排平台按钮换成**置灰占位按钮**「⚠️ 文案缺失（空壳作品，不可分发）」，
分享入口也拦。iOS 侧**两处都没有**：平台按钮照铺，`shareOnline` 只判 `isEmpty`。
本版在 `PlatformCopyParser.swift` 补 `copySubstance(_:)` / `isCopySubstanceMissing(_:)`
（正则与阈值与 Android 逐字相同），在 `configureOnlineButtons` 补 `makeCopyMissingButton()`，
在 `shareOnline` 补拦截提示「⚠️ 该作品文案缺失（空壳作品），已阻止分发」。

**② iOS「本地回收站·在线作品镜像区」左滑没反应，记录删不掉（DSH-088 G2）**

Android `onlineTrashCard` 有「恢复 + 彻底删除（二次确认）」；iOS `TrashView` 该区域左滑
直接 `return nil`，**只能点行恢复，没有彻底删除入口**。本版补上左滑 `彻底删除`
与确认弹窗（标题「彻底删除」/ 正文「彻底删除后无法恢复，确定删除？」→ `deletePermanently`），
页脚同步补「左滑可彻底删除该记录」。

**③ 空壳判定的标记正则两端写了两个版本（DSH-088 G3）**

Android 用 `<<<[^>]*>>>`，而真库里存在把结束标记写坏成 `<<<DOUYIN_END>>`（只有两个 `>`）的文案，
该正则完全匹配不到 ⇒ 两端对同一份文本会算出不同的「实质字数」。
两端统一为 `<<+[^<>]*>>+`。**取证**：全库 642 份 `文案.txt` 用新旧正则各算一遍，
`跨过 30 字判线的 = 0 份` —— 不误伤、不放过。


## 0.8.46 - 2026-09-21 - 手机端点文案按钮不再把 `<<<…>>>` 协议标记复制进剪贴板

> **用户反馈**：「手机端目前点击那个按钮会复制很多其他标识符分割符号吗？你看看这个苹果，安卓。」
> 「手机在线相册点击那个重置按钮会失败，BUG。」

本版修掉三个手机端问题（Android `versionName 0.8.46` / `versionCode 157`；
iOS 对应 `MARKETING_VERSION 0.8.27` / `CURRENT_PROJECT_VERSION 98`）。

**① 点平台按钮，剪贴板里塞满协议标记（DSH-085）**

三条泄漏路径，用真库 `文案.txt` 逐份驱动真代码测得：

| 路径 | 成因 | 命中 |
| :-- | :-- | --: |
| `parse()` 提前返回 | 不含 `COPY_FORMAT:2/3` 头也不含该平台固定标记时，**整篇原文**当结果返回；V4.5 `MULTI` 正是这形态 | 80 份 |
| `enrichPlatformSuite` 兜底 | 多版本作品仍合成「规避营销版/种草版/大纲方案版」，「种草版」兜底直接取原文 | 111 份 |
| 「伪协议」文案 | 有 `<<<COPY_FORMAT:3>>>` 头、正文用 `【小红书自然种草版】` 分节、无平台标记 ⇒ 落「发布」兜底 | 46 份 |

另有 **9 份**把结束标记写坏成 `<<<DOUYIN_END>>`（**只有两个 `>`**），会被块正则当成正文吞进来。
⇒ 标记正则统一改成 `<<+[^<>]*>>+`（原先要求正好三个 `>`，对它**完全不动**）；
抽出正文后再净化一道；按钮文案改为 `openShare(work, platform, copyText)` **直传**，
不再让消费端按平台回查（10 个版本的 `platform` 都是 `GENERAL`，回查必错）。

**② iOS 修完①仍然漏（DSH-087）**

`parseAvailablePlatforms()` 是**本地卡与在线卡共用**的出口（`ContentView:1282` / `ContentView:1474`），
它有 4 个构造 `copyText` 的位置：Android 4 个**全部**过净化，**iOS 4 个一处都没过** —— 两端移植不对齐。
本版把 iOS 四个出口补齐，并新增**出口对齐检查器**（不翻译、不编译，直接解析两端真源构造函数），
修前副本 FAIL（iOS 4 处违规）→ 修后 PASS（两端各 10 处 0 违规）。

**③ 在线「重置」按钮点下去像失败（DSH-086）**

两个独立原因：`list_stage_works()` 的 **5 秒 `_stage_cache`** 未被 `scan(force=True)` 清掉
⇒ 手机端重置后立刻重拉仍是 `useCount=1`；且重置**只写计数、不把作品从 `_已发送1次` 移回 `已发送0次`**
⇒ 作品永远挂「已使用」Tab、主页「全部」里消失。现在 `scan(force=True)` 连带清阶段缓存，
并新增 `_move_work_to_stage0()` 回迁（3 次重试 + `copytree` 兜底 + 写移动日志），
**仅当作品确实在「_已发送1次」时才回迁**（垃圾库/已在待发区的不动）。

**测试**

- Android：`PlatformCopyParserTest` 新增 5 个用例（含畸形标记、伪协议、多版本/自定义块出口）；
- iOS：`PlatformCopyParserTests` 新增 3 个用例（与 Android 用例名一一对应）；
- Java 独立验证工程：断言 26 → **32 项**，真库 **642 份** `文案.txt` ⇒ **32 PASS / 0 FAIL、脏 0**；
  `--break-guard` 错误版本 **4 FAIL**（证明闸门真会响，不是常绿）。

## 0.8.45 - 2026-09-21 - 带输入框的弹窗不再被误触关掉（丢掉已输入的备注）

> **用户反馈**：「我点击删除并备注之后的弹窗，就应该只有三个地方可以点击才对——
> 对话框、输入框、按钮；之外的界面点击该无效。现在点到外面就关了，
> 我输入的文字就消失了，很烦。」

**现象**：在「删除并备注」弹窗里打完垃圾原因，手指磕到弹窗外的界面，
弹窗直接关闭，**已输入内容全部丢失且没有任何提示**。

**根因**：Android `AlertDialog` 默认 `setCanceledOnTouchOutside(true)`。
全项目 **19 处** `AlertDialog` 里，只有 1 处（下载进度弹窗）设了防误关，
其余 **18 处全都可被点击背景关闭** —— 其中 5 处带输入框，关一次就是一次输入全丢。

**改动**：给其中 **5 处带输入框的弹窗**加 `setCanceledOnTouchOutside(false)`：

| 方法 | 弹窗 | 写入什么 |
| :-- | :-- | :-- |
| `promptRemarkThenMoveToTrash` | 垃圾备注（随作品写入元数据） | 本地作品删除时的备注 |
| `promptRemarkLocalTrash` | 垃圾备注（写入手机本地元数据） | 本地回收站备注 |
| `promptRemarkOnlineRecycle` | 垃圾备注（写入作品元数据） | 在线回收站备注 |
| `promptRemarkThenDeleteOnlineWork` | 垃圾备注（随作品写入元数据） | 在线作品删除时的备注 |
| `showEditPcIpDialog` | 设置电脑在线相册 IP | 电脑地址 |

**只禁「点背景」，保留返回键**（不用 `setCancelable(false)`）——
确保弹窗永远有退路，不会卡死。

**新增守卫用例**：`android/app/src/test/java/com/zwm/gallery/InputDialogProtectionTest.java` ——
静态扫描 `MainActivity`，凡是「含 `setView(` 且读取 `getText()`」的弹窗，
必须有 `setCanceledOnTouchOutside(false)`；只做展示的自定义布局（图片预览、进度条）不读 `getText()`，不受约束。
写在 CI 里真实执行（`:app:testDebugUnitTest`），以后新增弹窗忘了加保护就会红。

**验证**：本机跑不了 Gradle，使用逐字复刻判据的脚本做了**三态自证**：

| 状态 | 期望 | 实测 |
| :-- | :-- | :-- |
| 当前代码（已修） | 0 处违例 | 0 处 ✔ |
| 漏掉 1 处保护 | 1 处违例，能定位行号 | 第 1513 行 ✔ |
| 5 处保护全漏 | 5 处违例 | 5 处 ✔ |

> **自证过程中抓出守卫自身的两个 bug**（已修，值得记一笔）：
> ① 块边界初版画在 `.create();` 处——而保护语句天然写在它之后，
>   导致已保护的弹窗被判成未保护，守卫变成常亮噪声；
> ② `Matcher.start()` 返回的是绝对下标，被我当成相对偏移（`end + start()`），
>   块一路吞到文件尾 → 任何弹窗都能踩到某个保护语句 → **守卫永远不响**。
> 这正是本项目「装了闸门必须验证闸门本身」的现身说法。

**未修（需用户拍板）**：另外 13 处无输入框的确认弹窗（如「清空回收站」、
「重置为未发布」）点背景仍会关闭。这些丢不了内容，且部分场景下「点击空白处即取消」
是用户习惯的行为，暂不改。

## CI（非客户端发版）- 2026-09-21 - 修掉 iOS 单测「假闸门」：长期只编译、从未真正执行

> **来源**：核对 iOS 0.8.25 的 CI 绿灯（run `35561484171`，`ios-altstore-build` success）时，
> 没有直接采信绿灯，而是去翻 job 日志，发现它命中的是
> `No bootable iPhone simulator on this runner; compiling the app and test bundle.`
> + `build-for-testing` —— **只编译 app 与 test bundle，从未执行 XCTest**。

### 根因：不是 runner 没模拟器，是探测脚本自己崩了

```bash
simulator_id=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
...
')
```

YAML 的 block scalar 会给**每一行**加缩进，这段 Python 到达解释器时每行都带前导空格，
CPython 直接抛 `IndentationError: unexpected indent`（本机实测：exit 1、stdout 为空）。
命令替换拿到空串 ⇒ `simulator_id` 为空 ⇒ 永远走 `else` 分支 ⇒ 永远 `build-for-testing`。
次因是过滤条件要求 `d.get("isAvailable")`，该字段在部分 Xcode 版本上不存在，同样会漏选。

**结论**：此前所有 iOS 单测（含本轮新增的 3 个 MULTI 用例）**只被编译过，从未跑过**；
`ios-altstore-build` 的绿灯一直只证明「Swift 能编译通过」。
（不影响 0.8.25 的功能改动本身，但**任何「测试通过」的说法此前都不成立**。）

### 修法

| 项 | 改前 | 改后 |
| :-- | :-- | :-- |
| 选择逻辑 | YAML 内联 `python3 -c` | 独立脚本 `scripts/pick_ios_simulator.py` |
| 设备过滤 | 要求 `isAvailable`（字段可能缺失 ⇒ 漏选） | 只看 `availabilityError` |
| 无可用设备 | 静默降级 `build-for-testing` | 取最新可用 iOS runtime，`simctl create` + `boot` 现造一个 |
| 造不出来 | 静默通过 | `::warning::` + step summary 明写「NOT executed」 |
| 跑过之后 | 无痕 | summary 写入 `Executed N test`；没有该行就再告警一次 |
| 闸门自证 | 无 | `scripts/test_pick_ios_simulator.py`（9 例），CI 新增 `Verify iOS simulator picker` 步骤 |

输出契约（stdout 一行）：`device:<udid>` 复用 / `create:<runtime>\t<device-type>` 现造 / 空 = 没戏。

### 验证

- 本机 **9/9 通过**；期间**刻意还原成错误版本**跑过一轮确认测试会 FAIL
  （iPhone-8 排序压过 iPhone-16、`devices` 非 dict 时崩溃 —— 两条都是真实捕获，非事后编造）。
- `python3 -c '<indented>'` ⇒ `IndentationError` 已在本机复现（exit 1、stdout 空）。
- workflow 的 bash 片段经 `bash -n` 语法检查，并用真实字符串验证过 `case` 拆分
  （`create:<runtime>\t<type>` 能正确拆成两个变量）。
- **待 CI 复核**：新 run 的 `ios-altstore-build` 日志里应出现 `Executed N test`。

### 待复核项（不扩大结论）

- 本修复只保证「有机会真跑」；**iOS 0.8.25 的 11 版本按钮 / 全量发图仍需真机验收**。

## CI（非客户端发版）- 2026-09-21 - 修掉 iOS 单测「假闸门」：长期只编译、从未真正执行

> **来源**：核对 iOS 0.8.25 的 CI 绿灯（run `35561484171`，`ios-altstore-build` success）时，
> 没有直接采信绿灯，而是去翻 job 日志，发现它命中的是
> `No bootable iPhone simulator on this runner; compiling the app and test bundle.`
> + `build-for-testing` —— **只编译 app 与 test bundle，从未执行 XCTest**。

### 根因：不是 runner 没模拟器，是探测脚本自己崩了

```bash
simulator_id=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
...
')
```

YAML 的 block scalar 会给**每一行**加缩进，这段 Python 到达解释器时每行都带前导空格，
CPython 直接抛 `IndentationError: unexpected indent`（本机实测：exit 1、stdout 为空）。
命令替换拿到空串 ⇒ `simulator_id` 为空 ⇒ 永远走 `else` 分支 ⇒ 永远 `build-for-testing`。
次因是过滤条件要求 `d.get("isAvailable")`，该字段在部分 Xcode 版本上不存在，同样会漏选。

**结论**：此前所有 iOS 单测（含本轮新增的 3 个 MULTI 用例）**只被编译过，从未跑过**；
`ios-altstore-build` 的绿灯一直只证明「Swift 能编译通过」。
（并不影响 0.8.25 的功能改动本身，但**任何「测试通过」的说法此前都不成立**。）

### 修法

| 项 | 改前 | 改后 |
| :-- | :-- | :-- |
| 选择逻辑 | YAML 内联 `python3 -c` | 独立脚本 `scripts/pick_ios_simulator.py` |
| 设备过滤 | 要求 `isAvailable`（字段可能缺失 ⇒ 漏选） | 只看 `availabilityError` |
| 无可用设备 | 静默降级 `build-for-testing` | 取最新可用 iOS runtime，`simctl create` + `boot` 现造一个 |
| 造不出来 | 静默通过 | `::warning::` + step summary 明写「NOT executed」 |
| 跑过之后 | 无痕 | summary 写入 `Executed N test`；没有该行就再告警一次 |
| 闸门自证 | 无 | `scripts/test_pick_ios_simulator.py`（9 例），CI 新增 `Verify iOS simulator picker` 步骤 |

输出契约（stdout 一行）：`device:<udid>` 复用 / `create:<runtime>\t<device-type>` 现造 / 空 = 没戏。

### 验证

- 本机 **9/9 通过**；期间**刻意还原成错误版本**跑过一轮确认测试会 FAIL
  （iPhone-8 排序压过 iPhone-16、`devices` 非 dict 时崩溃 —— 两条都是真实捕获，非事后编造）。
- `python3 -c '<indented>'` ⇒ `IndentationError` 已在本机复现（exit 1、stdout 空）。
- workflow 的 bash 片段经 `bash -n` 语法检查，并用真实字符串验证过 `case` 拆分
  （`create:<runtime>\t<type>` 能正确拆成两个变量）。
- **待 CI 复核**：新 run 的 `ios-altstore-build` 日志里应出现 `Executed N test`。

### 待复核项（不扩大结论）

- 本修复只保证「有机会真跑」；**iOS 0.8.25 的 11 版本按钮 / 全量发图仍需真机验收**。

## iOS 0.8.25 / build 96 - 2026-09-21 - 在线相册两处真机缺陷：**文案解析没跟上安卓**（11 个版本塌成 1 个「乱码」按钮）+ **只发第一张图**

> **来源**：0.8.24 装到真机后，用户在 iOS 在线相册里点文案按钮实测反馈三句话：
> ① 「文案同步过去了，但是**乱码**，没有分隔开」；
> ② 「界面**没有其他版本按钮**」；
> ③ 「点击发布居然去其他 APP 发现**只有一张图**，没有像安卓一样全部拉取发送」。
>
> 三句对应两个根因（文案解析落后 / 只发首图），都在本轮修掉。

### 缺陷 1：`PlatformCopyParser.swift` 落后于 Android —— 文案塌成一个「乱码」按钮

**现象**：在线作品卡片只出现**一个**按钮（兜底文案「发布」），点它复制到的是
**含全部 `<<<VERSION_START:…>>>` 标记的整段原文**，即用户看到的「乱码、没分隔」。

**根因**：Android `PlatformCopyParser.java` 早已支持三套协议 ——
`<<<COPY_FORMAT:MULTI>>>` + `<<<VERSION_START:名>>>` 多版本、`COPY_FORMAT:2/3` 固定平台、
以及任意自定义标记，并且有 **7 个平台**；而 iOS 只认 `COPY_FORMAT:2/3` 与
`DOUYIN/XHS/XHS_2` **三个平台**。V4.5 的 11 版本文案头部是 `<<<COPY_FORMAT:MULTI>>>`
⇒ iOS 判定「不是协议格式」⇒ 落进兜底分支返回 `[发布, 整段原文]`。
**不是漏了一个版本，是一个都没认出来。**

**修法**：把 iOS 解析器重写为 Android 行为的 1:1 移植 —— `MULTI` 头、
`VERSION_START/END` 逐版本块、7 平台枚举（新增短文精选版 / 公众号版 / HR决策版 / 参考文案）、
`friendlyLabelForMarker` 版本短标签、通用动态标记扫描（跳过已知固定平台）。
原有 `parse()` 的三段式语义与 `ok` / `missing` / `unreadable` 状态**保持不变**。

**顺带修掉一个「点哪个版本都一样」的坑**：MULTI 的 11 个版本里有 10 个 `platform` 都是
`.general`，若分享时按 platform 回查文案会**永远命中第一条**。因此
`onShare` / `onOnlineShare` 的回调契约由「传平台」改为**直接传被点的那一条
`AvailableCopyPlatform`**；`WorkLibrary` 新增 `prepareShare(..., copyText:)` 重载承接显式文案。

### 缺陷 2：卡片只写死三个平台按钮 —— 11 个版本也只显示得下 3 个

**现象**：即使解析正确，旧布局也只能显示前 3 个版本。

**修法**：`WorkCell` 的平台按钮区由「3 个写死按钮 + `fillEqually`」改为
**按解析结果动态创建 + 横向滚动**（`platformScroll` + 内容自适应 `platformRow`），
按钮数量不设上限；「重置 / 删除 / 复制路径」独立成固定操作行。卡片高度 172 → 196
（按钮再多也不增高，靠横向滚动）。与 Android `FlowLayout` 的动态渲染对齐。

### 缺陷 3：`shareOnline` 只发第一张图

**现象**：点发布跳到小红书 / 抖音，**只有一张图**。

**根因**：函数里的注释写着「异步下载第一张或全部图片供分享」，
但实现只取 `entry.images.first` 就端出 `UIActivityViewController(activityItems: [img])`。

**修法**：新增 `downloadAllImages(paths:onProgress:completion:)`，
**按电脑端给出的顺序依次拉取全部原图**，期间用进度弹窗显示
「正在下载：<文件名>（n/N 张）」，全部到齐后再唤起分享 ——
与 Android `handleOnlineWorkUse` / `downloadWorkImages` 的流程对齐。
空文案时**直接拦住并提示**，不再拿空文案去分享。

### 验证

- **本地（算法级，Windows 上无法编译 Swift）**：把新解析逻辑等价移植成 Python，
  跑 Android 既有测试向量 + V4.5 真实形状，**四项闸门全部通过**：
  ① 旧实现在 MULTI 上确实退化成「1 个按钮 + 含标记的整段原文」（缺陷已复现）；
  ② 新实现出 11 个按钮；③ 按钮正文不含任何 `<<<` 标记；④「抖音避坑」正确归到抖音平台。
  另有 11 项新老行为差异对照（Format2/3、扩展平台、自定义标记、旧版纯文案）。
- **单测**：`ios/AlbumTests/PlatformCopyParserTests.swift` 新增 3 个用例
  （扩展平台与自定义标记 5 按钮、MULTI 11 版本、多版本文案互不串台），
  与 Android `PlatformCopyParserTest` 的同名向量逐条对齐。
- **待办复核项**：Swift 编译与 XCTest 结果以 CI `ios-altstore-build` job 为准；
  **真机验收尚未执行**（需 iPhone 在线 + Sideloadly 侧载 0.8.25 后复测
  「11 个版本按钮是否逐个出现」「点发布是否带入全部图片」）。

## iOS 0.8.24 / build 95 - 2026-09-21 - 补上 0.8.23 的漏：**有缓存数据时也必须自愈**（否则从「弹窗」变成「静默卡死」）

> **来源**：0.8.23 装到真机后，用「把 `customPcServerUrl` 写成旧网段地址再冷启动」复现原始故障场景时实测发现：
> 屏幕**不再弹窗**（0.8.23 的修复生效），但 **30 秒后缓存地址纹丝不动**，一直停在
> `http://192.168.0.107:45835` —— 即「看得见的旧列表 + 静默卡死」，**比原来的弹窗更糟**
> （用户以为正常，其实列表是陈的，点发布/删除都会失败）。

**根因（把「提示方式」与「要不要自愈」耦合在了一起）**：`loadOnlineData()` 的失败分支里，
`tryAutoDiscoverPc()` 只写在「完全没有数据」那一支；「有快照」那一支只发 toast。
而 0.8.22 之所以能自愈，恰恰是靠 `hadData` 误判成 false 走错了分支 —— 0.8.23 把误判修对之后，
**顺带把自愈也一起关掉了**。
⇒ 铁律：**有没有缓存数据只决定「用 toast 还是用弹窗」，绝不能决定「要不要去找新地址」。**

**修法**：失败即在两个分支都调 `tryAutoDiscoverPc()`（仍受 15 秒冷却保护，不会刷屏）。

**实测验证（真机，两步对照）**：

| 版本 | 冷启动（缓存地址＝旧网段 192.168.0.107）| 结果 |
| :-- | :-- | :-- |
| 0.8.23 | 无弹窗、显示旧列表；30s 后 `customPcServerUrl` 仍为旧地址 | ❌ **不自愈（静默卡死）** |
| 0.8.24 | 同上场景 | ✅ **已自愈**：38s 后地址被改写为 `http://192.168.1.27:45835`；三帧截图（t+4/12/35s）均无弹窗 |

**真机验收（2026-09-21，0.8.24 / build 95，iPhone13,2 / iOS 26.6）**：

- **判据 A（屏幕）**：冷启动后 t+4s / t+12s / t+35s 三帧真机截图**只有列表（401 套）、零弹窗**
  —— 0.8.23 换来的「不再弹窗」没有回退；
- **判据 B（缓存）**：同一时刻回读 `Library/Preferences/com.zwm.album.TXA6HP98BX.plist`，
  `customPcServerUrl` **由旧网段 `http://192.168.0.107:45835` 被自愈改写为 `http://192.168.1.27:45835`**
  —— 静默卡死消失，这才是 0.8.24 相对 0.8.23 的净收益；
- **旁证**：服务端 `GET /api/online/phones` 同时读到 `192.168.1.154 | iPhone13,2 | 0.8.24 | 95`（App 确实连上了电脑）；
  云端更新源 `gallery-updates/latest.json` 已发布 iOS `0.8.24 / build 95`
  （sha256 `993544a4…`，与 CI 产物逐字一致），notes 指向源码提交 `d55ae5f8`；
- **复现方式已脚本化**：`_v0824_verify.py`（`probe` / `run` 两模式），把
  「杀 App → 写旧网段地址 → 冷启动 → 多帧截图 → 回读 plist」固化成一条命令，**两处判据同时断言**。

## iOS 0.8.23 / build 94 - 2026-09-21 - 消除换网段后的假弹窗：数据判定改为回包时刻采样 + 自愈失败才提示

> **bug 来源**：0.8.22 装到真机后的**下一眼**就暴露了——iPhone 上列表**已经好好显示着 401 套作品**
> （`全部 401 / 中秋 18 / 国庆 18 / 游戏 71`），却同时弹着阻塞式
> `拉取在线相册失败：Could not connect to the server. / 正在自动搜索局域网内的电脑在线相册…`，
> 点「知道了」才消失。即 0.8.22 治好了「搜不到电脑」，但没治好「明明能用却报错」。

**根因（DSH-081，判定时机错位）**：`loadOnlineData()` 在**发请求前**算
`let hadData = !onlineWorks.isEmpty`，而本地快照是 `loadOnlineSnapshot()` **异步后到**的。
冷启动时序是「请求先生效（`onlineWorks` 还空）→ 快照落地并渲染 → 请求失败回包」，
`hadData` 已被固化成 `false` ⇒ 走「完全没有数据」分支弹窗。
**用来描述回包时刻界面的判据，采样自请求发出时刻** —— 两者不同步，于是弹窗与屏幕内容脱钩。

**修法（iOS 侧）**：

- 判定改为在 `group.notify`（**回包时刻**）直接读 `!self.onlineWorks.isEmpty`；有数据时只发轻量 toast；
- 无数据分支改为**先非阻塞、后升级**：先 `showToast("⚠️ 正在搜索电脑在线相册…")`，
  再自愈；**只有快轨（2.5s）+ 慢轨（/24 扫描）都失败**才放出原来的阻塞弹窗；
- `tryAutoDiscoverPc` / `finishAutoDiscover` 新增 `alertOnFailure:` 形参：
  自愈因 15 秒冷却或在途而**没跑起来**时照样弹窗，不静默吞错。

**对账证据**：修复前真机截图（列表 401 套与阻塞弹窗并存）；同时电脑端
`GET /api/devices/live-state` 读到 iPhone `appVersion 0.8.22 / versionCode 93 / traffic 11`，
证明通路**本来就是通的**，弹窗只是残留 —— 也顺带证实 DSH-079 的字段错位已归位。

## iOS 0.8.22 / build 93 - 2026-09-21 - 换网段秒级自愈：补齐单播探测 + 修正信标版本字段错位

> **bug 来源**：真机现场——电脑 Wi-Fi 换网段（`192.168.0.107` → `192.168.1.27`）后，
> iPhone 切「电脑在线」一直弹 `拉取在线相册失败：<错误> 正在自动搜索局域网内的电脑在线相册…`，
> 而安卓机手点一下就能读。Windows 面板同时把 iPhone 显示成 `相册 v0.8.3 (iOS 最新版 · build 11)`
>（真机对账是 `0.8.21 / build 92`）。

**两个根因，同源于「iOS 与安卓的发现协议没对齐」**：

1. **信标字段错位（DSH-079）**：安卓在信标第 9~11 位发
   `base64url(appVersion)|versionCode|base64url(updateCapability)`，作品计数放 12~14 位；
   iOS 当时没发这三个字段，作品计数直接接在第 9 位 ⇒ 电脑端把**泛流量篇数**当成了 `versionCode`
   （所以显示 build 11，恰好等于该机 11 篇泛流量），`workCounts` 也整块丢失。
   前端又对 iOS 写死 `0.8.3 / build 11` 的 fallback，两个 bug 互相掩护。
2. **缺单播探测（DSH-080）**：`applyBeaconUrl()` 零调用者、`beaconPort = 45832` 声明未用（死代码），
   iOS 只能靠 20 秒的 /24 单播扫描兜底，而 `resolveBaseUrl()` 又优先复用旧地址 ⇒ 换 IP 后长时间失败。

**修法（iOS 侧，最小改动）**：

- `IncomingTransferService.beaconData()` 补齐第 9~11 位，作品计数自动落回 12~14 位；
  `updateCapability` 用 `ipa-altstore-v1`（不复用安卓的 `apk-push-v1`，避免电脑端误判成「可推 APK」）；
- 版本读取统一为 `IncomingTransferService.appVersion/appVersionCode`（单一真源＝`ios/project.yml`），
  `/v2/info` 同时补上 `versionCode` / `updateCapability`；
- 新增快轨 `LanDiscovery.probeBeacon()`：BSD UDP socket **发**广播探测（发送不需要 multicast 权限），
  从临时端口收发，电脑端**单播**回一条含当前 `url` 的 JSON 信标 ⇒ 秒级拿到电脑当前地址；
  只认 `DeviceShareHub-OnlineGallery` 标识，避免误认其它 45832 占用者；
- `ContentView.tryAutoDiscoverPc()` 改为**先快轨、失败再跑慢轨** /24 扫描；
- 网段枚举收敛到 `LanDiscovery.localBroadcastTargets()`，文件传送信标与在线相册探测共用一份实现。

**自检**：本机 `node scripts/verify-auto-mobile-update.mjs` / `verify-p2p-invariants.mjs` /
`verify-removed-surfaces.mjs` 全绿；服务端 `python -m unittest test_online_gallery_service` 全绿（16 项）。
Swift 编译由 CI `ios-altstore-build`（macos-15）实机编译把关。

**回归要求**：换网段后手机端 ≤3 秒内读到在线相册；面板卡片版本号须等于手机 `设置 → 关于` 的真实值；
信标扩字段后必须验证 `workCounts` 仍可解析（只测版本号会漏掉字段错位）。

## iOS 0.8.20 / versionCode 91 - 2026-09-21 - Swift 编译错误修复（CI 暴露）

> **bug 来源**：上次发 iOS 0.8.19 时用 tree-sitter-swift 自检通过（语法层合法），
> 但 swiftc 编译器拒绝了 OnlineListCache.swift L115-116 的两行：
> - `optional chain has no effect, expression already produces '[FileAttributeKey : Any]?'`
> - `reference to member 'modificationDate' cannot be resolved without a contextual type`
> - `cannot convert value of type '[FileAttributeKey : Any]' to expected argument type 'Date'`

**根因**：把 `try? ...?.` 这种已经在 `try?` 后又加 `?` 的冗余 optional chain + dict 字面量 `[.modificationDate]`
当成了 Swift 5.x 的隐式 dict literal —— 实际上 `[FileAttributeKey: Any]?` 不能直接用 `[.modificationDate]`
这种形式做 partial key access，也不能在 optional chain 后直接 `as? Date`。

**修法**：先把 attrs 解到 `if let` 里，再做 `attrs[FileAttributeKey.modificationDate] as? Date`，
让编译器能解析出完整类型链。语义不变：attrs 拿不到就当作「没 mtime，跳过过期判断」。

**自检**：下次 iOS 自检必须用 swiftc 而不是 tree-sitter（最低成本：装个 Swift Docker 镜像或
`swift package init` 在 mac runner 上跑 build）。本机只跑 `node --check` 跳过。

## Android 0.8.43 / versionCode 154 - 2026-09-21 - 体感加速 polish：状态栏位置错位 + auto-discover 不打断 snapshot

> **bug 来源**：0.8.42 真机验证时（杀 PC 服务 + 冷启 App）发现两个连带的 design bug：
> 1. 状态栏文字「💻 电脑在线相册 (url) · 共 N 套（本地快照 · X 分钟前）」在 UI 上看不到
>    —— uiautomator dump 里完全找不到这个 TextView，要滚到列表最底部才看到。
> 2. PC 不可达时 `tryAutoDiscoverPc()` 连续两次 `setText("正在自动搜索…" → "暂未搜索到…")`，
>    把 `primeOnlineWorksInBackground()` 设的 snapshot 状态条**覆盖**了。
>    用户感受：「明明 App 里有 388 套作品秒开，但顶部只看到『暂未搜索到电脑在线相册』」。

**根因**：
1. `MainActivity.java` 的 layout 拓扑把 `statusText` `addView` 到了**滚动 root 的末尾**
   （`root.addView(worksContainer)` → `root.addView(statusText)` → `root.addView(footerNote)`），
   所以 statusText 被作品列表推到了屏幕外。
   而真正的 sticky 顶栏是 `frozenLayout`（`titleRow` + `searchBar` + `categorySelector` + `contentFrame`），
   statusText 根本不在那个 sticky 容器里。
2. `tryAutoDiscoverPc()` 是「连接失败就自动搜索局域网」，逻辑没问题，
   但它 setText 时**没考虑**用户已经能从本地 snapshot 秒开，
   应该静默而不是抢顶部状态条。

**修法（两处）**：
1. **statusText 改挂 frozenLayout**：从 `root.addView(statusText, ...)` 删掉，
   改为 `frozenLayout.addView(statusText, ...)`（在 `titleRow` 下面、`searchBar` 上面），
   做真正的 sticky 顶部状态栏。margin 调到 `(dp(12), dp(4), dp(12), dp(4))`。
2. **tryAutoDiscoverPc 加 snapshot 守护**：
   - 进入函数第一行判 `if (onlineListFromSnapshot && !onlineWorks.isEmpty() && snapshotAgeText() != null) return;`
     —— 已有 snapshot 就别打断用户。
   - 即便要走搜索，失败时不再覆盖 statusText：
     - 有 snapshot → `toast("⚠️ 暂时连不上电脑（本地快照 · X 分钟前 仍可秒开）")`
     - 没 snapshot → 保持原 setText「暂未搜索到电脑在线相册…」

**自检**：javalang 解析 OK；本次不发版前必须跑真机「杀 45835 + 冷启 App」测试，
  验证 uiautomator dump 在 y < 600 区域能看到 `💻 电脑在线相册... (本地快照 ...)`。

## Android 0.8.42 / versionCode 153 - 2026-09-21 - 体感加速 bugfix：snapshot 路径下分类条空了

> **bug 来源**：上一轮 0.8.41 部署后，用户实测发现杀 PC 服务 + 冷启动 App 后，
> 作品列表正常（snapshot 生效），但**分类条整个空白、状态栏看不到「本地快照」字样**。
> 上一轮的 release 跑在真机上时才暴露（模拟器看不出，因为模拟器总能正常连服务）。

**根因**：
1. `applyOnlineCategoryFilter()` 只筛作品 + 渲染卡片，**不重建分类条**；
   `updateOnlineCategoryCounts()` 只在「用户主动切模式 / 刷新」时被调用，
   snapshot 路径（`primeOnlineDataFromSnapshot`）里**完全没调**，
   导致分类条一直是空的。
2. `updateOnlineCategoryCounts()` 在 `catResult == null` 时连「全部 N」之外的
   派生分类按钮都不画——双重 bug：哪怕有了 works snapshot，没有 cats snapshot 也只显示「全部 N」。

**修法（两处）**：
1. `primeOnlineDataFromSnapshot()` 在 `applyOnlineCategoryFilter` 之前显式
   `updateOnlineCategoryCounts(lastCategoriesResult, onlineWorks)`；
2. `updateOnlineCategoryCounts()` 新增 **catResult 缺失时的 fallback**：
   从 `onlineWorks` 的 `destination` 字段派生目的地计数，生成可点的分类按钮。
   即使首次冷启动 + 网络不通 + 无 cats 快照，用户至少能看到「全部 N / 安吉 / 莫干山 / 千岛湖 …」并点击筛选。

自检：javalang 解析 OK（211818 B）。

---

## iOS 0.8.19 / versionCode 90 - 2026-09-21 - 与 Android 0.8.41 对齐（顶栏 + 体感）

> **新 IPA 已发布到 gallery-updates**，iPhone App 内更新即可。

把 Android 0.8.41 这一波体感加速在 iOS 上的 4 处遗漏补齐：

1. **顶栏新增「刷新作品」按钮** —— 顶栏顺序：传送文件 → 来源模式 → **刷新作品** → 回收站 → 设置（与 Android `titleRow` 排列 1:1 对齐）
2. **loadOnlineData 改为并行请求** —— 分类与列表同时发出，列表先到先渲染；旧实现是串行白白多一个网络往返
3. **失败不清屏（与 Android 一致）** —— 已有快照/旧数据时，刷新失败不发错误、不 auto-discover，只弹一条轻量 toast 保留用户看到的内容
4. **模式按钮 accessibilityLabel 改用 Android 同款文案** —— "当前：手机本地作品 (点击切换到电脑在线)" / "当前：电脑在线作品 (点击切换到手机本地)"（iOS 旧版用的是 "为..."、"..." 与 Android 不一致）

self_check：tree-sitter 解析 ContentView.swift，1 处 ERROR 为 `as? T ?? default` 的已知误报（与第二十一节注释一致）。

---

## iOS 0.8.18 / versionCode 89 - 2026-09-20 - 在线相册本地快照秒开（对齐 Android）

> **新 IPA 已发布到 gallery-updates**，iPhone App 内更新即可。
> **服务端改动对已装机的旧版也立即生效**（URLSession 自动协商 gzip）。

把 Android `OnlineListCache` 的思路搬到 iOS：

- 新增 `OnlineListCache.swift`（Documents/online_cache/，原子写 + 7 天保质期，只缓存全量列表 JSON Data，解析失败自动清盘）
- `OnlineGalleryClient.fetchWorks` 成功后，仅「全量列表」请求落快照（带分类/关键词的子集**不**落，避免下次秒开看到残缺列表）
- `OnlineGalleryClient.fetchCategories` 成功后落快照
- `ContentView.viewDidLoad` 在 `loadOnlineData()` 之前先调 `primeOnlineDataFromSnapshot()`：
  存在快照就把 UI 直接铺满，再让后台静默拉最新；
  网络失败时也保留上次的内容（电脑关机也能看到）

自检：tree-sitter 解析三个 Swift 文件，3 处 ERROR 均为 `as? T ?? default` 的已知误报（第二十一节注释一致）。

---

## Android 0.8.41 / versionCode 152 - 2026-09-20 - 在线相册体感加速三件套

> **新 APK 已发布到 gallery-updates**，手机 App 内更新即可装上。
> **服务端改动对已装机的旧版也立即生效**（HttpURLConnection 自动协商 gzip），
> 重启电脑端 `start_online_gallery_service.ps1 -Restart` 即可，无需等 APK。

### 用户能直接感受到的体感

| 场景 | 之前 | 现在 |
| :-- | :-- | :-- |
| 点进「电脑在线相册」 | 长时间停在「正在连接电脑在线相册…」 | 状态栏只说「正在后台刷新…」，列表先到先渲染 |
| 杀进程冷启动再点进 | 同样的「正在连接」等待 | 本地快照先铺满屏幕，离线也能秒开 |
| 手机端总传输量 | 单次 2.75 MB | 单次 **420 KB（-85%）** |

### 改动内容

1. **服务端传输瘦身 + gzip**（`online_gallery_service.py`，所有客户端零改动即受益）
   - `/api/online/works` 与 `/api/online/recycle` 响应剥离 `searchBlob`（服务端关键词检索专用，41% 体积）与 `slotGuard`（诊断块，1.4% 体积），两端客户端均不解析
   - 按 `Accept-Encoding` 协商 `gzip`，实测 **2751.8 KB → 419.9 KB**；gzip 结果按 blake2b 内容摘要缓存（LRU 6 条），命中不再重复压缩
   - `/api/online/status` 新增 `wire` 健康块（`gzipCache` 命中率、`listOmitFields` 等），便于监控
2. **Android 本地快照秒开**
   - 新增 `OnlineListCache`：原样落盘服务端返回的全量列表 JSON（原子写、7 天保质期、解析失败自动清盘）
   - 仅「全量列表」请求落快照，带分类/关键词的子集**不**落快照（避免下次秒开看到残缺列表）
3. **Android 开机预热**（`primeOnlineWorksInBackground`，`onCreate` 即调）
   - 先读快照进内存，再后台静默拉最新列表 + 分类
   - 用户点进在线相册时大概率已经渲染好，**不再有阻塞感**
4. **Android `refreshOnlineWorks` 三点改造**
   - 分类与列表**两请求并行**（之前串行白白多一个网络往返）
   - 已有数据时状态栏只说「正在刷新…」，不再把「正在连接电脑在线相册…」摆在用户面前
   - 失败**不清屏**：有快照数据时只做软提示并保留列表，电脑关机时用户仍能看到上次内容
5. **列表/分类请求收紧超时**：连接 4 s / 读 12 s（原 15 s/15 s，电脑关机时最多白盯 30 秒）

### 自检（22/22 全绿，含还原-失败证明）

- `python -m unittest test_online_gallery_service` **22 项全绿**
- 包含还原-失败证明（第二十一节要求）：把 `slim_works_for_wire` 临时换回「不过滤」版本，端到端契约断言**会失败**，闸门有效
- Java 端 javalang 解析 3 个文件 OK
- 装机包 dex 内 `OnlineListCache` / `primeOnlineWorksInBackground` / `silentPrefetchOnlineWorks` / 「正在后台刷新」字符串均命中

### 兼容性

- **iOS 不需出新包**即可享受传输瘦身 + gzip 的服务端收益
- 旧 Android 版本也可享受服务端收益（自动协商 gzip，HttpURLConnection 透明解压）
- 本地快照机制是 Android-only 新增；iOS 后续若需要可参考 `OnlineListCache` 思路

---

## 服务端（无客户端改动）- 平台槽位守卫：拦住「标记齐全但正文是模板骨架」的作品

> **无需出新 APK**：改动全在 `scripts/online_gallery_service.py`，重启服务即生效。
> 手机端 `PlatformCopyParser` 的既有逻辑会自动消费净化后的载荷。

**现象**：384 套在线作品里，**9 套**（全部在 `已发送0次（抖音小红书可发）`）的「文案」其实是
**产线只落了骨架、没跑填充**的模板占位语。手机端每套会渲染 2 个可点击按钮，用户点「种草版」拿到的就是：

```
<<<DOUYIN_START>>>  抖音短平快口播脚本，痛点切入+亮点+留资号召        <<<DOUYIN_END>>>
<<<XHS_START>>>     小红书主标题 / [小红书种草正文，带两日详细行程排期…]  <<<XHS_END>>>
<<<XHS_2_START>>>   HR方案决策版大纲，包含方案名称、适用对象、预算参考…  <<<XHS_2_END>>>
```

**根因 —— 第二例「假闸门」**：`MIN_COPY_DISTRIBUTABLE` 的判定对象是**整段文案**（`copy_substance_len(copy_text) >= 30`），
而这批骨架整段实质 **107 字**，直接放行；然而手机端是按**单个平台槽位**独立出按钮的。
**闸门测的粒度（整段）与用户消费的粒度（单个槽位）不在同一层级** —— 与 `staleCode` 那次同源。
更讽刺的是：该守卫第 147 行注释明写它的目的正是「杜绝**标记齐全但正文全空**」，它被自己的靶子骗过去了。

**修复**：

- 新增 `MIN_PLATFORM_SUBSTANCE = 30` + `_PLACEHOLDER_SLOT_RE`（占位语特征）+ `sanitize_platform_copy()`：
  下发前按槽位剔除「**占位骨架** 或 **实质字数 < 30**」的块（**仅净化工下发的载荷，不动磁盘原始文件**）；
- `copyMissing` 口径升级：净化后已无真实槽位、或整段实质不足 30 字 → 判为缺失，
  手机端既有逻辑会整块置灰标红「⚠️ 文案缺失（空壳作品，不可分发）」；
- **按 span 删除而非按块重建**，原样保留 `<<<COPY_FORMAT:n>>>` 头部与 `<<<VERSION_START:名>>>` 等本函数不解析的块；
- `searchBlob` 仍用未净化原文 → 被剔除的作品**照样能被关键词搜到**，可检索性不因守卫而丢失；
- `/api/online/status` 新增 `slotGuard` 健康块（`scanWorksAffected` / `scanSlotsDropped` / `hint`），
  计数按**每轮扫描**重置；`slotsDropped > 0` 是**响铃**，表示文案骨架未填充、需回流产线重生成。

**实测效果（重启后线上全量 384 套，逐条对照）**：

| 项 | 结果 |
| :-- | :-- |
| 按钮集合发生变化的作品 | **10 套**（其中 9 套为真实变更，1 套为对照脚本读文件差异、服务端 `dropped=0` 未动） |
| 从「2 个占位按钮」→ 整块置灰 | **9 套**（即骨架作品，与 `/status` 的 `worksAffected` 对齐） |
| 真实作品被误伤 | **0 套**（`slotGuard.droppedCount = 0`，`copyText` 逐字节原样） |
| 混合作品（真 XHS + 占位 XHS_2） | 保留真实槽位、仅剔除占位槽位，**未整块置灰**（1 套） |

**顺手修掉「长期红灯」的既有测试漂移（16 项全绿）**：

- 基线 `HEAD` 为 **10 项 2 败**，且 2 败 **不是实现坏了，是用例还在断言已被替换掉的旧业务规则**：
  `test_two_uses_protection_rule` 断言「首次使用不移动、`remainingUses=1`」，
  而现行铁律是「**首次使用即物理移入 `_已发送1次（微信公众号可发）`**」，故 `remainingUses=0`、`moved=True`；
- 第二个失败是**用例互相污染**：上述用例把共享扫描根里的样例作品**真的挪走了**，
  按字母序后跑的 `test_works_search_and_filter` 于是搜不到作品而误报。已改为自带 `addCleanup` 复原；
- `_move_work_to_stage1` 对已在目标位置的目录是**幂等**的（返回「作品已位于…」），
  故第二次使用断言的是「**不得产生重复目录**」这一真正的不变量，而不是 `moved=False`；
- 结果：**10 项 2 败 → 16 项 0 败**。长期红的套件会训练人无视红色 —— 它本身就是一种坏闸门。

**新增测试（`TestPlatformSlotGuard`，6 项）**：骨架端到端判缺、真实文案零改动、混合只剔占位、
空槽位与零字节、**守卫必须接线进载荷（防「只声明不调用」型假闸门）**、剔除后仍可检索。
并附「**还原成错误实现**」证明：把 `sanitize_platform_copy` 还原为原样返回后，
3/3 条核心断言 **FAIL**（`copyMissing=False`、`droppedCount=0`、骨架槽位原样下发）—— 证明测试不是空转。

## Android 0.8.40 / iOS 0.8.17 - 在线回收站长期卡「正在读取…」根治 + 缩略图链路加固

> **版本说明**：Android 0.8.39（versionCode 150）已于 22:27 发布，但其构建**不含**客户端的
> 「缩略图失败重试一次」。为让更新机制能把该修复真正下发到手机，Android 升至
> **0.8.40 / versionCode 151**。iOS 本轮无客户端改动，保持 **0.8.17 / build 88**。

> 来源：手机端进入在线回收站长时间停在「正在读取电脑在线回收站…」，卡片迟迟不渲染；
> logcat 里 `OnlineGalleryClient: loadThumbnail failed ... timeout` 刷屏。

**真凶（服务端，物理铁证）**：电脑端服务进程跑的是**旧代码**（进程启动于 06:58，脚本最后修改于 14:44），
`thumb=1` 被静默忽略并回落成**原图**。实测同一张图 `thumb=0` 与 `thumb=1` 返回字节**逐字节相同**：

| 项 | 修复前 | 修复后 |
| :-- | :-- | :-- |
| 单张「缩略图」体积 | 2,678.7 KB（原图 PNG） | **37.1 KB**（320×320 JPEG） |
| 400 张总流量 | ≈ 1.0 GB | **12.1 MB** |
| 400 张总耗时（并发 8） | 10.79 s | **0.53 s** |
| P95 时延 | 0.164 s | **0.021 s** |
| 吞吐 | 37 张/s | **754 张/s** |

手机端每进一次在线回收站要为几百张卡片拉 GB 级原图，4 线程 + 8s 超时 → 大面积超时 + 原图解码 GC 风暴 → UI 冻结。

- **服务端（`online_gallery_service.py`）**：
  - 修复「**只声明从未使用**」的缩略图内存缓存（`THUMB_CACHE` 死代码），改为带 LRU 淘汰的真实缓存，
    并新增**同图并发生成单飞锁**，避免几十个请求同时跑 PIL；
  - 缩略图链路改为「内存 → 磁盘 → PIL 生成」三级，并新增 `THUMB_STATS` 命中计数；
  - **杜绝静默降级**：Pillow 缺失或生成失败时打印明确日志，并在响应头下发 `X-Thumb: fallback`，
    客户端可观测、运维可诊断；
  - `/api/online/status` 新增 `thumbnail` 健康块（`ok` / `pil` / `pilVersion` / 缓存目录与条数 / 命中统计 / `hint`），
    一条 `curl` 即可判断缩略图链路是否真的生效；
  - **补齐 iOS 旧契约**：`/api/online/image` 此前只认 `id`+`file`，iOS 一直用 `?path=` → **HTTP 400，图片全拉不到**。
    现支持 `path` 的三种形态（绝对路径 / 相对成品库路径 / 裸文件名经索引解析），并做**目录穿越防护**
    （越界路径一律 404）。
- **Android（`OnlineGalleryClient.java`）**：
  - 缩略图改用**独立 8 线程池**，不再与列表 / 分类 / 操作请求抢同一个 4 线程池；
  - 新增缩略图**磁盘缓存**（`online_thumbs`，二次进入在线列表几乎零流量），仅缓存体积合理的缩略图，
    不把降级原图写进缓存；
  - 新增**在途请求去重**：同一 `(workId, fileName)` 并发只发一次网络请求，结果广播给全部等待者；
  - 按目标边长（384px）**降采样解码**，服务端降级发原图时也不会打爆内存；
  - 新增**失败重试一次**（延迟 900ms）：冷缓存时服务端要现场跑 PIL 生成缩略图，几 MB 的大图在并发下
    可能超过 8s 读超时——本质是「客户端先放弃、服务端其实还在生成」，重试一次基本命中缓存。
    （真机实测：一次进页 231 次取图里曾出现 7 次此类超时，集中在两套 3–4 MB 大图作品上）
- **Android（`MainActivity.java`）**：
  - 在线列表缩略图改为**整页调度**：旧实现每张卡片前 4 张立刻发请求（382 作品 → 1500+ 请求同时压入线程池），
    现改为整页**首发预算 18 张**，其余入队按 260ms/6 张的节拍渐进放行 → UI 先出骨架再补图。
- **范围**：未改动任何在线回收站按钮结构、数据字段与文案；纯性能与可靠性加固。
- **防复发（代码新鲜度自检）**：本次真凶是「进程跑着旧代码」，所以补了两道闸：
  - `/api/online/status` 新增 `code` 块（`startedAt` / `scriptShaRunning` / `scriptShaOnDisk` /
    `scriptMtime` / `staleCode` / `hint`）——`staleCode=true` 时直接给出「请重启在线相册服务」的提示；
  - 开机自启脚本 `DeviceShareHub-OnlineGallery.vbs` 由「直连 pythonw」改为「调用启动器 `-Restart`」——
    旧写法在已有进程占着 45835 时仍会再起一个，两个进程并存正是静默事故的温床。
    （该文件在系统启动目录，不入库；原文件已备份到交付工作区。）
  - ⚠️ **该自检的初版实现是无效的，已二次修复（教训）**：初版写的是
    `SCRIPT_MTIME = os.path.getmtime(SCRIPT_PATH)`（导入时取一次快照）
    再去比 `SCRIPT_MTIME > PROCESS_STARTED_AT`——而脚本肯定早于进程存在，
    这个条件**在启动瞬间必然不成立**，`staleCode` 恒为 `False`。
    也就是说，这道"防复发闸门"本身是一道**安静失效的假闸门**，给的是虚假安全感。
    现改为「启动时的脚本内容 SHA」 vs 「实时读盘的脚本内容 SHA」做对比：
    内容不一致 ⇒ 磁盘上的代码已不是正在跑的那份 ⇒ `staleCode=true`。
    用内容哈希而非 mtime，mtime 会被 checkout / 复制 / 时区干扰，内容哈希不会。
    并补了**真实场景回归测试**（`TestCodeFreshness`，4 项）：测试必须**真的去改磁盘文件**再断言，
    否则又会漏掉——已用「还原成旧实现」的副本验证过该测试确实会 FAIL 并抓出此 bug。
- **服务端（缩略图磁盘缓存无上限）**：磁盘缓存 key 是「图片路径 + mtime」，图片一改就生成新条目、
  旧条目永不失效，而此前只有内存 LRU 有 500 条上限，磁盘缓存没有任何上限
  （现实体量 890 个文件 / 26.0 MB，图库长到万张级会累积到约 300 MB 且不自动回收）。
  现加 `MAX_DISK_ENTRIES = 2000` 上限，超限时按 mtime 删最旧的一批、只保留 90%；
  **低频触发**（每 200 次写盘才真扫一次目录，且只在超限时才排序）⇒ 不在取图热路径上引入抖动。
  `/api/online/status` 的 `thumbnail` 块新增 `diskCacheLimit`，`stats` 新增 `diskEvicted`。
- **iOS 版本号单一源修复（本轮 CI 抓出的真实缺陷）**：
  iOS 产物在 `ios/project.yml` 里有**两处**版本声明 —— `settings.base` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`
  与 `info.properties` 里**硬编码**的 `CFBundleShortVersionString` / `CFBundleVersion`。
  升版本只改前者时，xcodegen 会用后者覆盖生成的 Info.plist，打出的 IPA 版本与 CI 断言不一致
  → `Package AltStore IPA` 步骤断言失败、iOS 产物发不出去（而 android-build 是绿的，极易误判成「iOS 环境抖动」）。
  现改为引用构建设置（`"$(MARKETING_VERSION)"` / `"$(CURRENT_PROJECT_VERSION)"`），版本号回到**单一源**，
  这类「改一处忘一处」的失败从此不可能再发生。
- **CI 验证结论（本轮）**：run `35515813427`（源码提交 `95e5d47`）——
  `android-build` ✅ / `ios-altstore-build` ✅ / `windows-portable` ✅ / `publish-gallery-updates` ✅，
  仅 `remote-relay-check` ❌（长期已知红点，与本次改动无关）。
  iOS 版本单一源修复**验证通过**：`album-iOS-v0.8.17-altstore.ipa`（21.3 MB）已随 `v0.8.39` release 正常发布；
  线上 `latest.json` 已刷新为 Android `0.8.39` / versionCode `150`、iOS `0.8.17` / build `88`。
- **对照基线**：服务端自带单测 9 项中 2 项失败（`test_two_uses_protection_rule` / `test_works_search_and_filter`），
  已用 `git archive HEAD` 取原版测试确认**同为失败**，属历史遗留，与本次改动无关。
  新增的 `TestCodeFreshness` 4 项全绿。

## Android 0.8.38 / iOS 0.8.16 - 在线卡片副标文案去掉「真源」二字

> 来源：手机端在线相册卡片副标显示「8 张图片 · 电脑真源」，用户反馈「电脑真源」看着别扭，电脑就电脑。

- **Android**（`MainActivity.java`）：
  - 在线作品卡片副标 `N 张图片 · 电脑真源` → `N 张图片 · 电脑`（两处渲染器同步）；
  - 原图拉取状态文案 `准备连接电脑真源拉取 N 张原图…` → `准备连接电脑拉取 N 张原图…`。
- **iOS**（`OnlineRecycleView.swift`）：
  - 列表分组头 `已使用（N）· 电脑在线真源` → `已使用（N）· 电脑`；
  - 单元格副标 `💻 电脑真源 · N 张图片` → `💻 电脑 · N 张图片`。
- **范围**：纯文案，未触碰任何业务逻辑、按钮结构与数据字段。

## Android 0.8.37 / iOS 0.8.15 - 真机复现并修复「切换来源模式后刷新作品」FATAL 崩溃

> 来源：0.8.36 装机到红米13（23124RN87C）后，用 adb 真机端到端验证时在 logcat 抓到 FATAL 异常。

- **崩溃现象**：切换来源模式（电脑在线 ↔ 手机本地）后刷新作品，App 直接挂掉：
  ```
  FATAL EXCEPTION: main
  java.util.concurrent.RejectedExecutionException:
    Task ...MainActivity$$ExternalSyntheticLambda47 rejected from
    ThreadPoolExecutor@... [Terminated, pool size = 0, active threads = 0]
      at com.zwm.gallery.MainActivity.refreshWorks(MainActivity.java:697)
      at com.zwm.gallery.MainActivity.lambda$importSelectedTree$69(MainActivity.java:2056)
  ```
- **根因**：`onDestroy()` 里 `worker.shutdownNow()` 关掉线程池，但**已经排进主线程队列**的
  后台任务回调仍会执行 —— 典型是 `importSelectedTree` 扫完文件夹后在 `runOnUiThread` 里再调
  `refreshWorks()`，此时 `worker` 已终止，`worker.execute(...)` 在 UI 线程抛
  `RejectedExecutionException`，无人接管 → FATAL。
- **修复**：新增 `submitToWorker(Runnable)` 兜底提交 —— 前置判断
  `isFinishing() || isDestroyed() || worker.isShutdown()`，并 catch `RejectedExecutionException`
  记一条 `worker_task_rejected` 诊断日志后静默丢弃（不再让 UI 线程崩）。
  MainActivity 里 **9 处 `worker.execute(...)` 全部改为 `submitToWorker(...)`**，
  覆盖刷新作品、恢复、重置、清空回收站、移动回收站、导入文件夹、备份清理等所有后台入口。
- **验证**：修复前 `logcat` 稳定复现；修复后同路径反复切换模式/进出回收站，`logcat` 无
  `FATAL` / `AndroidRuntime.*com.zwm.gallery` 记录（过滤必须锚定自己包名，国产 ROM 系统进程会刷噪音）。
- **版本号**：Android versionCode 148 / versionName 0.8.37；iOS CURRENT_PROJECT_VERSION 86 / MARKETING_VERSION 0.8.15。

## Android 0.8.36 / iOS 0.8.14 - 本地 ↔ 在线「按钮与回收站细节」强制 1:1 对等

> 规则来源：本地相册与在线相册必须一样的按钮数量 —— 不能「你有『备注并删除』我没有、你有回收站细节我没有」。

- **本地作品删除补齐「备注并删除」（两端）**：
  - 修复前：本地作品删除只有「取消 / 移到回收站」两选项，而在线作品早有「取消 / 备注并删除 / 删除」三选项；
  - 现在：本地与在线**同一条对话框形态**，先填垃圾原因再删除，备注随作品写入手机本地元数据。
- **备注落盘链路（两端新增）**：
  - Android：`WorkLibrary.moveToTrash(id, trashedAtMs, garbageRemark)` 写入 `.meta` 的 `garbageRemark`；
    `WorkEntry.garbageRemark` + `WorkEntry.isGarbage()` 读回；
  - iOS：`WorkState.garbageRemark`（Codable）；`WorkLibrary.moveWorkToTrash(_:remark:)` 落盘；`TrashItem.garbageRemark` + `isGarbage` 读回；
  - 语义与在线版 `quality_tag.json` 的垃圾备注**逐字对齐**。
- **本地回收站补齐与在线回收站同规格的条目操作（3 项对 3 项）**：
  - 修复前：本地回收站条目只有「恢复」，在线回收站条目有「恢复 / 删除(或备注) / 复制路径」；
  - 现在：**本地回收站条目 = 恢复（点行/按钮）+ 备注 + 复制路径**，与在线回收站条目操作数严格一致；
  - 新增 `WorkLibrary.updateTrashRemark(...)`（两端）：本地回收站可直接补写/改写垃圾备注，传空串即清除标记；
  - 新增本地回收站条目的「复制路径」：复制**手机上**该作品（回收站内）文件夹的绝对路径。
- **「恢复」即撤销垃圾标记（与在线语义对齐）**：Android `WorkLibrary.restore` / `rollbackTrashMove`
  与 iOS `WorkLibrary.restore(_:)` 恢复时一并清除 `garbageRemark`。
- **iOS 在线回收站的返回按钮修复 + 入口与 Android 对齐**：
  - 修复前：在线回收站把「📱 本地回收站」放在 `navigationItem.leftBarButtonItem`，**顶掉了系统返回按钮**；
  - 现在：入口改为**列表底部按钮**（与 Android 页面底部同名入口同位置），系统返回按钮恢复可用。
- **本地回收站也有双 Tab（与在线回收站结构同构）**：
  - 修复前：在线回收站有「已使用 / 已标记垃圾」两枚 Tab 按钮，本地回收站一枚都没有（顶栏只有「清空」）；
  - 现在：本地回收站同样两枚 Tab —— **「已删除」/「已标记垃圾」**，判据与在线版垃圾标记同一套（垃圾备注是否为空）；
  - Android：`localTrashTabBar` + `localTrashDeletedTabButton` / `localTrashGarbageTabButton`，
    `selectLocalTrashTab` / `refreshLocalTrashTabStyles`，`renderWorksCards` 内按 Tab 过滤并刷新角标；
  - iOS：`TrashViewController` 挂 `UISegmentedControl`（沿用在线回收站的 `AppColors.groupedTableStyle` + 56pt header 方案），
    分区标题随 Tab 变为「📱 本地已删除 / 📱 本地已标记垃圾」；
  - 两个 Tab 的角标数字与各自列表 total 严格一致，不会出现「角标 N、列表 M」。
- **本地回收站条目亦展示垃圾备注**：已标记为垃圾样本的本地作品，卡片/行副标题直接显示
  `🗑️ 垃圾样本 / 备注：…`，与在线回收站垃圾 Tab 的展示形态一致。
- **版本号**：Android versionCode 147 / versionName 0.8.36；iOS CURRENT_PROJECT_VERSION 85 / MARKETING_VERSION 0.8.14。

## Android 0.8.34 / iOS 0.8.12 - 作品「复制路径」按钮 + iOS 在线相册发现修复

- **修复 iOS「读不到电脑在线相册」的根因（`LanDiscovery.swift`）**：
  - 根因：原实现把 `getifaddrs` 返回的**所有**非回环 IPv4 都拿去做 /24 单播扫描，
    其中包含蜂窝接口 `pdp_ip0`（运营商大内网 10.x / 172.x / 100.64.x）。
    手机同时开着 Wi-Fi 和蜂窝时候选地址翻倍到 500+ 个，而扫描总预算是 12 秒 ——
    一旦先扫到蜂窝那个 /24，预算是被 254 个不可达地址耗尽，**Wi-Fi 网段还没轮到**就跳出，
    `discover()` 返回 nil，表现就是「iPhone 读不到在线相册」。
  - 修复：按接口名只保留 `en*`（Wi-Fi / 以太网），剔除蜂窝 `pdp_ip*`、
    VPN/隧道 `utun*`/`ipsec*`、AirDrop P2P `awdl*`/`llw*`、网桥 `bridge*`；
    并按 192.168.x → 10.x → 172.16-31.x → 其它 排序，优先扫最可能的家庭/公司网段；
    扫描总预算 12s → 20s（单 /24 在 48 并发 + 1.6s 超时下约需 8~9s）。
  - 另记：iOS 侧 `applyBeaconUrl` 目前**没有任何调用点**，因此 `resolveBaseUrl()` 里
    「局域网信标地址」那一档在 iOS 上是死代码 —— iOS 完全依赖单播扫描。
    后续若要彻底稳定，需要让电脑端把信标**单播**发给 iPhone 的已知 IP
    （iOS 14 起收广播/组播需要 Apple 单独审批的 multicast 权限，AltStore 侧载拿不到）。
- **新增「复制路径」按钮，统一排在「删除」按钮之后**（顶栏顺序与卡片按钮顺序两端严格一致）：
  - **本地相册 / 本地回收站**：复制**手机上**该作品文件夹的绝对路径
    （Android 取 `WorkLibrary.WorkEntry.directory`，iOS 取 `WorkItem.folderURL.path`）；
  - **电脑在线相册**：复制**电脑成品库**中该作品文件夹的绝对路径
    （取服务端 `/api/online/works` 已下发的 `path` 字段，无需改后端）；
  - **在线回收站（已使用 / 已标记垃圾两个 Tab）**：同样复制电脑端文件夹路径。
- **每个作品路径独一无二**：路径含「已发送0次 / _已发送1次 / _垃圾作品」等阶段目录与作品文件夹名，
  可用于溯源、排查与人工核对。
- **iOS 交互对齐**：卡片上为「复制路径」按钮（紧跟「删除」）；在线回收站页为左滑动作，
  排在「删除 / 备注」之后。
- **路径缺失时明确提示**：复制空路径不再静默失败，Toast 提示「该作品没有可复制的文件夹路径」。
- **版本号**：Android versionCode 145 / versionName 0.8.34；iOS CURRENT_PROJECT_VERSION 83 / MARKETING_VERSION 0.8.12。

## Android 0.8.33 / iOS 0.8.11 - 在线回收站双 Tab（已使用 / 已标记垃圾）与垃圾备注

- **在线模式「回收站」错进本地回收站的 Bug 修复**：
  - 修复前：在线模式点顶栏「回收站」会走 `showTrash()`，读到的是**手机本地回收站**，电脑端「_已发送1次」与「_垃圾作品」两个阶段库在手机上完全不可见；
  - 现在：在线模式点「回收站」直接进入**在线回收站**，Android 用顶栏下方双 Tab 切换，iOS 用 `UISegmentedControl` 切换，两端功能一一对应。
- **双 Tab 与电脑端阶段库严格映射**：
  - `已使用` → 电脑端 `_已发送1次（微信公众号可发）`（点过平台按钮、useCount ≥ 1 的作品）；
  - `已标记垃圾` → 电脑端 `_垃圾作品（后续参考分析）`（手机端判定为垃圾的作品，永久保留、电脑端绝不自动清理）；
  - 两个 Tab 的角标数字取同一份 `counts`，与各自列表 `total` 严格一致，不会出现「角标 586、列表 546」这类对不上的情况。
- **「恢复」语义（两个 Tab 通用，按既定设计决策）**：
  - `已使用` Tab 的「恢复」= 移回「已发送0次（抖音小红书可发）」并把使用次数归零；
  - `已标记垃圾` Tab 的「恢复」= 移回「已发送0次」并**同时撤销垃圾标记**（摘除 `quality_tag.json` / `manifest.json` 的 garbage 块，恢复微信等全渠道可发）；
  - 恢复时优先读 `_portfolio_move_logs/delete_move_log_*.csv` 反查原路径，把嵌在「作品集_xxx[转]」里的作品**放回原作品集**，而不是抖到成品库根目录。
- **垃圾样本备注（可视化标注）**：
  - 垃圾 Tab 的作品卡片显示已有的 `garbageRemark`，并可现场补填/修改；
  - 走 `POST /api/online/remark-garbage`，同时写入 `quality_tag.json` 与 `manifest.json`。
- **本地回收站入口保留**：在线回收站页面底部（Android）/ 左上角（iOS）保留「📱 本地回收站」入口，避免在线模式再也进不去手机本地回收站。
- **iOS 与 Android 严格对齐**：顶栏按钮顺序不变（传送文件 → 来源模式 → 回收站 → 设置），进入在线回收站后右侧按钮统一为「刷新」，左滑动作与 Android 卡片按钮一一对应（已使用 Tab：删除；垃圾 Tab：备注）。
- **PC 服务端 `/api/online/reset-work` 重复归零代码合并**：原先 manifest 归零块被复制粘贴了两遍（各写一半字段），现合并为一份并补齐 `distribution.status = 待发手机`，避免扫描器读到非 0 次数。
- **回归测试**：`scripts/tests/test_online_recycle.py` 29 项断言全部通过（含合集恢复、缓存冷热对比、垃圾备注落盘回读、404 边界）。
- **版本号**：Android versionCode 144 / versionName 0.8.33；iOS CURRENT_PROJECT_VERSION 82 / MARKETING_VERSION 0.8.11。

## Android 0.8.23 - 中秋国庆时令专题置顶聚合、原画双阶段加载与单键动态同步分享

- **中秋与国庆节日时令专题置顶聚合（PC服务与客户端深度联动）**：
  - PC 服务端 `online_gallery_service.py`：智能扫描作品标题与文案，将“中秋”与“国庆/十一”作品独立提取为高优先级节日专题分类；在 `/api/online/categories` 中强制置顶：`【🌕 中秋】`（29套）与 `【🇨🇳 国庆】`（15套），彻底突破单一目的地限制，实现跨地域（西山岛、杭州、湖州、上海、通用等）节日笔记一键聚合；
  - 手机端 `MainActivity.java`：升级 `applyOnlineCategoryFilter`，自动剥离 Emoji 符号并穿透比对作品标题及 `copyText` 全文字段，点击顶部【🌕 中秋】或【🇨🇳 国庆】胶囊瞬间完成全量跨地域专题作品聚合呈现。
- **大图高清双阶段无缝加载（彻底告别模糊马赛克）**：
  - 服务端重写 `/api/online/image`：请求 `thumb=0` 时直接流式下发原始保真大图，绝不经过 Pillow 降采样；
  - 客户端双阶段渲染：点击卡片缩略图第 0 秒立即展示当前已缓存缩略图（零白屏等待），右上角显示 `⏳ 拉取原画中…`，后台异步拉取真实原图后平滑淡入替换，右上角变更为绿底 `✅ 100% 原画`，支持毛孔级清晰度全屏大图查看。
- **单键极速动态同步与无缝呼起分享（零手动搬运）**：
  - 修复 `ShareFileProvider.java` 中文/Emoji/空格文件夹被误报 `SecurityException` 拦截的致命 Bug，放行所有合规中文作品路径；
  - 点击卡片下方的绿色版本按钮（如【规避营销版】），手机端瞬间弹出液态微拟态动态进度条：`正在从电脑同步原图到手机… (x/y 张)`；
  - 原图全部下载落盘至手机私有缓存瞬间，文案自动复制入系统剪贴板，动态关闭进度弹窗并瞬间呼出原生系统分享面板（直达小红书/抖音发布），用户长按粘贴即可直接发布。
- **PC 服务 Windows 开机自启常驻后台**：
  - 落地启动脚本 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\DeviceShareHub-OnlineGallery.vbs`，通过 `pythonw.exe` 静默守护运行，0 黑框、不随开发终端启闭中断，保持 45835 端口局域网常驻在线。
- **版本号**：Android versionCode 134 / versionName 0.8.23。


- **手机端电脑在线相册一键删除与两级二次弹窗确认**：
  - 手机端在线作品卡片装配危险红字拟态【删除】按钮 (`STYLE_DANGER_WHITE`)；
  - 点击后调出 AlertDialog 二次确认弹窗，动态根据作品状态精准区分文案：
    * 若 `useCount == 0`（未发布）：提示“该作品尚未发布。删除后将从电脑首发库移入「_垃圾作品（后续参考分析）」归档保存。确认删除？”；
    * 若 `useCount > 0`（已使用）：提示“该作品已使用 N 次。删除后将从电脑首发库物理移入「_已发送一次」归档。确认删除？”；
  - 用户确认后异步向 PC 发起 POST `/api/online/delete-work` 请求，根据返回结果实时 Toast 提示（如 `📦 已移入电脑「_已发送一次」` 或 `🗑️ 已移入电脑「垃圾作品库（供后续参考分析）」`），动态平滑移出卡片、更新顶部计数并自动重查刷新。
- **PC 端物理流转分流与审计追踪 (`online_gallery_service.py`)**：
  - 实现 POST `/api/online/delete-work`（兼容 `/api/online/delete`）：
    * 未使用作品：安全原子物理移动 (`shutil.move`) 至 `_垃圾作品（后续参考分析）`，供后续参考分析；
    * 已使用作品：安全原子物理移动 (`shutil.move`) 至 `_已发送1次（微信公众号可发）`；
    * 目标目录自动判别与创建，如遇同名文件夹自动附带时间戳规避覆盖冲突；
    * 审计日志：在 `_portfolio_move_logs/delete_move_log_YYYYMM.csv` 实时追加精确记录（时间、设备名、作品ID、原路径、目标路径、原使用次数、动作类型）；
    * 移动完成后即时触发 PC 端内存缓存重扫，保证两端状态绝对同频。
- **本地目录结构规范与扫描器规则双重封死**：
  - 本地作品库目录 `已发送1次（微信公众号可发）` 正式重命名为带下划线前缀的 `_已发送1次（微信公众号可发）`；
  - PC 服务扫描逻辑全面加固：所有带下划线前缀 `_` 的文件夹（以及 `IGNORED_NAMES` 中的黑名单项）在任何层级均被 100% 物理跳过，严禁流转后的作品回流进首发活跃池。
- **版本号**：Android versionCode 132 / versionName 0.8.21。

## Android 0.8.20 - 手机相册界面深度净化、去机器化标题与PC在线首发严格锁死

- **彻底消除阶段分类与重复分组（PC服务与客户端双保险）**：
  - PC 端 `online_gallery_service.py`：扫描范围彻底锁死为「已发送0次（抖音小红书可发）」与根目录直出合法成品，严格物理隔离「已发送1次」与「已发送2次」；`/api/online/categories` 彻底剔除“待首发”、“已发1次”、“已发2次”，仅返回纯净目的地（上海、杭州、千岛湖、莫干山、安吉等），总数精准锁定为 331 套首发成品；
  - 手机端 `MainActivity.java`：在 `updateOnlineCategoryCounts` 中增加端侧硬拦截，强制忽略“待首发”与“已发”等阶段名，顶部分类栏仅保留单个首位【全部 331】+ 纯净目的地胶囊，绝无重复“全部”。
- **卡片标题去机器化与自然方案名直显**：
  - 前后端双重递归剥离 `\d{8}_\d{6}` 时间戳、`网页CDP-`、`Codex-`、`CodexAPI-`、`COPY_FORMAT_x` 等机器流水线前缀与残留标记；
  - 卡片主标题清爽直显人类可读的核心方案名称（如【🔥秋天的第一场团建，员工直呼d】、【阳澄湖秋日2天1夜团建🦀稻田农庄抓蟹吃蟹】）；
  - 时间戳优雅收纳至卡片副标（如 `8 张图片 · 电脑真源 · 09-17 15:29`），标题单行截断采用标准省略号，信息主次分明。
- **在线模式界面纯净度与交互优化**：
  - 电脑在线模式下自动隐藏左侧本地“文件浏览”绿色文件夹图标 📁，避免用户困惑；
  - 缩略图横向首屏预载从 3 张提升至 4 张，滑动 20dp 即触发后续加载，彻底根除横向滑动露灰块；
  - 实装 `PREF_IS_ONLINE_MODE` 模式持久化，冷启动无感直接进入电脑在线相册，无需用户每次重新手动切换。
  - 底栏文案彻底清除“两次使用保护”等废弃字样，替换为自然清晰的交互指引。
- **版本号**：Android versionCode 131 / versionName 0.8.20。

## Android 0.8.18 - 在线与本地三版本文案按钮全面统一与PC服务无窗静默守护

- **文案三版本按钮全面规整统一（enrichPlatformSuite）**：
  - 彻底解决旧作品单文案时卡片只展示单个【发布】按钮的缺陷，将旧版单文案统一归并为【种草版】；
  - 无论本地模式还是电脑在线模式，全部卡片统一渲染标准三版本：`[ 规避营销版 ]` (排第1)、`[ 种草版 ]` (排第2)、`[ 大纲方案版 ]` (排第3)；已用作品动态追加 `[ 重置 ]`，两端体验 100% 对齐。
- **PC 在线相册服务无窗静默守护与刷屏消除**：
  - 重写 `OnlineGalleryHandler.log_message`，彻底静默所有图片缩略图高频拉取请求日志，杜绝控制台刷屏；
  - 移除“两次使用保护机制”等历史陈旧日志，仅保留纯净首发保障；
  - 启动脚本 `start_online_gallery_service.ps1` 升级为 `pythonw.exe` 无窗静默守护，彻底消除桌面黑框弹窗。
- **版本号**：Android versionCode 129 / versionName 0.8.18。


- **顶部常驻即时搜索栏（Search Bar）**：
  - 在相册顶部冻结区无缝嵌入圆角胶囊搜索框（38dp），白底带淡灰微边框，左侧放大镜矢量图标，中间即时输入框，右侧动态清空叉号按钮（`✕`）；
  - 支持按**目的地/文件夹名**（如“安吉”、“莫干山”、“团建游戏”）与**作品标题关键字**（如“慢下来”、“温泉”、“秋季”）双向即时过滤；
  - 支持空格多关键词交集筛选（如“安吉 温泉”同时匹配对应文件夹与标题）；
  - 大小写不敏感，首尾空格自动修剪。
- **跨文件夹搜索平滑回退与交互保护**：
  - 若在特定分类标签下（如“安吉”）搜索了其他文件夹的内容（如“莫干山”），系统自动平滑切回“全部”分类展示，杜绝用户陷入由于分类标签限制导致空白屏幕的困惑；
  - 搜索结果为 0 时提供大白话友好空态提示（`未找到匹配「...」的作品\n请尝试搜索其他标题或文件夹关键字`）；
  - 手机手势/物理返回键（Back）：有搜索词时优先一键清空搜索框并收起软键盘；
  - 滚动列表时自动收起软键盘防遮挡；
  - 文件浏览与回收站模式自动隐藏搜索栏。
- **版本号**：Android versionCode 125 / versionName 0.8.14。

## Android 0.8.13 - 待机300%发烫根除与按需双锁保活

- **待机 300% CPU 发烫递归死循环彻底根治**：
  - 查明并切断 `publishWorkInventory` -> `requestImmediateBeacon` -> `ACTION_REFRESH_STATUS` -> `onStartCommand` -> `runCleanup` 每秒数百次的死循环链路；
  - 剥离高频启动清理与状态去重，待机 CPU 从 257%~300% 暴跌至 0.0% ~ 1.0%，彻底消除发烫与电量消耗。
- **按需动态双锁保活机制**：
  - 动态申请 `PARTIAL_WAKE_LOCK` 与 `WIFI_MODE_FULL_HIGH_PERF`，传输时全速并发，结束即刻安全释放；
  - 实现黑屏、锁屏与休眠状态下纯 Wi-Fi 毫秒级稳定接收与解包落盘。
- **版本号**：Android versionCode 124 / versionName 0.8.13。

## Android 0.8.12 / iPhone 0.8.5 - 已用作品临时置顶复盘与卡片【重置】误触回滚

- **已发作品临时浮动置顶（Floating Top Retention）**：
  - 解决用户从 20+ 套作品中挑选发布某篇后，因卡片变灰沉底需要滑到很深处才能复盘的痛点；
  - 凡 `shareCount > 0` 的作品自动浮动置顶至列表顶部，卡片标题附加 `📌 ` 醒目标记；多套已用作品按最近使用时间倒序排列（最新使用的置于最前），未发作品维持自然名称升序。
- **卡片新增【重置】状态按钮（Reset Share Action）**：
  - 在已分享卡片的【删除】按钮左侧动态显示深灰拟态【重置】按钮；
  - 点击后弹窗二次确认防误触，确认后立即清空分享记录（`shareCount = 0`）、解除变灰恢复未发初始状态、取消 1 小时自动删除排期，并从置顶区自动归位回到普通列表；完美解决误触点击或改期明天再发的诉求。
- **双端架构完全对称对齐**：
  - Android：`WorkLibrary.java` 新增 `resetShare(id)` 与浮动置顶排序，`MainActivity.java` 动态注入 `[重置]` 按钮与即时刷新；
  - iOS：`WorkLibrary.swift` 新增 `resetShare(work)` 与状态保存，`WorkScanner.swift` 实现浮动置顶与时间排序，`ContentView.swift` 动态注入 `[重置]` 按钮；
  - 版本号：Android versionCode 123 / versionName 0.8.12；iPhone build 76 / marketing version 0.8.5。

## Android 0.8.3 / iPhone 0.8.3 - 文案与会话元数据选择隔离（源码候选）

- 修复 Android ZIP、SAF 和隐藏目录扫描在同时存在 `会话追踪.txt` 与真实文案时，按自然排序误选会话元数据并复制到剪贴板的问题。
- 文案选择优先级统一为 `文案.txt` → `小红书文案.txt` → `抖音文案.txt` → 其他非元数据 TXT；`会话追踪.txt`、`生产记录.txt`、`质量报告.txt`、标签/元数据/日志类 TXT 不再作为文案。
- 只有会话元数据而没有真实文案的目录不再识别为作品；旧目录仍保留其他名称 TXT 的兼容兜底。
- iOS 递归扫描同步采用相同规则，避免双端行为不一致。
- Android versionCode 114 / versionName 0.8.3；iPhone build 74 / marketing version 0.8.3。已发布至 `gallery-updates` 的 `v0.8.3`；实体手机覆盖安装与点击发布验收仍待设备在线。

## Android 0.7.9 / iPhone 0.7.9 - 作品卡片操作按钮流式自适应折行与双端防挤压布局

- **操作按钮原生流式自适应折行（FlowLayout）**：
  - **Android**：淘汰原单行 `LinearLayout(HORIZONTAL)`，引入原生零依赖流式折行容器 `FlowLayout`；
  - 动态计算卡片可用宽度与按钮尺寸，当 3 个文案版本（`[规避营销版]`、`[种草版]`、`[大纲方案版]`）与 `[删除]` 按钮总宽超出单行时，`[删除]` 按钮（及多余按钮）**自动换行至第二行**；
  - 设置 `horizontalSpacing = dp(8)` 与 `verticalSpacing = dp(8)`，彻底解决在窄屏或较大字体下删除按钮被挤出可视区域的问题。
- **iOS 2行网格式自适应平衡布局**：
  - **iOS**：重构 `WorkCell` 底部按钮结构为垂直容器 `platformContainer`，包含双行水平 `UIStackView`；
  - 当按钮数 `<= 3` 时，单行等宽紧凑展示；
  - 当按钮数 `> 3`（3 平台文案 + 1 删除）时，自动采用 2×2 对称网格排布（第一行 `[规避营销版]` + `[种草版]`，第二行 `[大纲方案版]` + `[删除]`），每行按钮各占 50% 宽度，文字清晰不缩小，触控防误触。
- **版本号**：
  - Android versionCode 110 / versionName 0.7.9；iPhone build 70 / marketing version 0.7.9。

## Android 0.7.8 / iPhone 0.7.8 - 顶部动态文件夹合集 Tab 栏与自适应截断（双端对齐）

- **顶部分类 Tab 栏全面升级为动态文件夹合集**：
  - 彻底移除原先硬编码的固定分类按钮（`全部 / 精准流量 / 泛流量 / 未分类`）；
  - 顶部 Tab 栏完全根据相册内实际存放作品的**文件夹合集名称**动态生成；
  - 第一项固定为 `全部 (总数)`，后续项按作品包含的实际合集名动态呈现并附带对应作品数；
  - 点击对应合集 Tab 即时按合集归属过滤展示该合集下的作品；
  - 当选中的合集作品被清空或删除时，平滑回退至“全部”视图。
- **合集名称自适应 5 字符截断规则**：
  - 规则严格对齐：若合集名长度 `<= 5` 字符（如 `安吉站`），完整显示；超过 5 字符截取前 5 字并追加 `...`（如 `作品集_100` 显示为 `作品集_1...`）；
  - Android 与 iOS 双端 100% 对齐格式化算法与单元测试。
- **横向平滑滚动防挤压容器**：
  - Android：`categoryBar` 升级包裹进无滚动条平滑 `HorizontalScrollView`，按钮采用自适应内容宽度的圆角胶囊设计；
  - iOS：原静态 `UISegmentedControl` 重构为基于 `UIScrollView` + `UIStackView` 的胶囊按钮组，多文件夹合集横向平滑随手滑动，杜绝界面挤压变形。
- **合集数据源解析链路对齐**：
  - Android `WorkArchiveImporter` 导入时完整保留 `plan.directory` 顶级文件夹至 `sourceRelativePath`；
  - Android `DocumentTreeImporter` 递归扫描层级时透传并在作品元数据中记录真实相对路径；
  - `WorkEntry.getFolderName()` 与 iOS `WorkItem.folderName` 双端统一从相对路径中精准解析顶级合集文件夹名。
- **版本号**：
  - Android versionCode 109 / versionName 0.7.8；iPhone build 69 / marketing version 0.7.8。

## Android 0.7.7 / iPhone 0.7.7 - 文案按钮命名彻底统一与健壮并发导入（已正式发布）

- **文案按钮命名双端彻底统一**：
  - 3 文案作品（最新精准转化）：并列展示 `[规避营销版]`、`[种草版]`、`[大纲方案版]`、`[删除]`。
  - 2 文案作品（旧版双平台文案）：统一展示为 `[规避营销版]`、`[种草版]`、`[删除]`，彻底移除“发抖音 / 发小红书”字样。
  - 1 文案作品（泛流量、小游戏、攻略等单文案）：兜底展示为 `[发布]`、`[删除]`。
- **Android 底层稳定性与并发修复**：
  - `WorkLibrary.java` 引入全局静态类级锁 `IMPORT_LOCK`，彻底杜绝 SAF 目录变更监听与后台网络接收线程并发写入同一作品时的死锁与冲突。
  - 导入暂存目录由固定路径改为纳米时间戳（`.import-{id}-{nanoTime}`），消除同 ID 作品暂存清理碰撞。
  - `importWork` 实现幂等复用：若目标作品在活动库已存在且元数据有效，直接返回现有条目，不再抛出 `IOException("作品已存在")` 导致整批次 500 报错。
  - `OnlineService.java` 任务提交异常全链路强制复位 `state = "online"`，彻底杜绝接收卡死在 `receiving` 状态。
- **三端实机验证 100% 闭环**：
  - **红米 13**：ADB 实机覆盖安装 `v0.7.6/107`，132 套作品完整在线（94 精准 / 34 泛流量），实机截图确认小游戏单文案卡片已渲染为 `[发布]`。
  - **小米 15**：USB 连线即时自动静默升级 `v0.7.6`，实机弹窗确认“当前已经是最新版本 0.7.6”。
  - **iPhone 12**：云端完成 AltStore 打包后，通过 Sideloadly 守护进程与本地加密缓存自动签名通道经 USB 秒级刷入；`pymobiledevice3` 底层回读与实机运行均确认为 `Version 0.7.7 Build 68`，卡片按钮实机显示为 `[规避营销版]` 与 `[种草版]`。
- **版本与云端发布**：
  - Android versionCode 108 / versionName 0.7.7；iPhone build 68 / marketing version 0.7.7。
  - GitHub Actions run `33783200715` 编译打包通过；正式发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.7.7>。
  - 线上 `latest.json` 与 `altstore.json` 已全部同步指向 0.7.7。

## Android 0.6.62 / iPhone 0.6.45 / Windows V4.3.29 - 作品卡片直接预览与横排操作（已正式发布）

- 移除作品卡片上的“预览”按钮，缩略图本身可点击；点击第几张就从第几张进入全屏大图，并可继续切换图片。
- Android 与 iPhone 的作品卡片底部统一为横排“发抖音 / 发小红书 / 删除”，删除始终可见并在确认后移入回收站。
- Android 全屏预览改为真正铺满屏幕并保留“上一张 / 下一张 / 关闭”；iPhone 继续使用全屏分页预览并从点击的缩略图开始。
- Android versionCode 100 / versionName 0.6.62；iPhone build 64 / marketing version 0.6.45；Windows V4.3.29。
- GitHub Actions run `33036449234` 的三端构建、P2P/ACK 静态门禁、远程中继检查、线上验证和发布任务全部成功；正式发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.62>。
- `latest.json`、`altstore.json` 以及为兼容旧安装保留的 `latest-beta.json`、`altstore-beta.json` 已统一指向 Android 0.6.62/versionCode 100 和 iPhone 0.6.45/build 64。
- Android APK SHA-256：`b2bc21687d0a126d696ce66f33668564d57ce70127ff76652b428ba417f5b3aa`；iPhone IPA SHA-256：`cae9c6739bed206703c9669a97930f4955738b2fbc67f912acdf413a16677ca2`。
- 当前机器的 AltServer 与 Sideloadly 后台服务在运行，但没有识别到实体手机；签名续期、覆盖安装、真实 P2P/HTTPS 回退和自动补货仍不能用云端构建替代。

## Android 0.6.61 / iPhone 0.6.44 - 接收任务重试与回执修复（源码候选）

- 修复电脑提交阶段遇到超时/HTTP 500 后，手机提前删除任务，电脑下一次重试被误报“任务不存在”的问题。
- Android 保留提交失败的任务、文件和断点清单，等待同一任务 ID 重试；任务闲置超时后才清理。
- Android 与 iPhone 都保存最近 128 个已完成任务回执；重复查询、创建或提交同一任务只补发完成回执，不会重复导入作品。
- iPhone 增加 `GET /v2/tasks/{taskId}` 状态接口，电脑可以在提交前确认断点和已完成状态。
- Android versionCode 99 / versionName 0.6.61；iPhone build 63 / marketing version 0.6.44。尚未云端构建、发布或进行实体手机验收。

## Android 0.6.60 / iPhone 0.6.43 - 作品操作模块重排（源码候选）

- Android 与 iPhone 作品列表统一为单列全宽操作模块，不再把作品压成两列卡片。
- 每个模块显示缩小后的标题、顶部横向缩略图带和底部“预览 / 发抖音 / 发小红书”三个紧凑按钮；缩略图可横向查看全部图片。
- 保留原有预览、系统分享、平台点击计数、灰色已点击状态和作品库逻辑；本轮未改变传输协议与权限。
- Android versionCode 98 / versionName 0.6.60；iPhone build 62 / marketing version 0.6.43。尚未云端构建或发布。

## Android 0.6.59 / iPhone 0.6.42 - 统一正式入口与 iOS build 检测

- Android versionCode 97 / versionName 0.6.59；iPhone build 61 / marketing version 0.6.42。
- Android、iPhone、Windows 继续只使用同一个正式更新入口，不再区分稳定版和测试版。
- iOS 更新检查同时比较 marketing version 和 build；即使版本名不变，build 递增也会提示更新，避免云端已发布但手机显示“已经是最新版本”。

## Android 0.6.58 - 自动接收开关（统一正式入口）

- 设置页新增“自动接收”开关，默认开启，升级不会改变已有接收习惯。
- 关闭后同时拒绝局域网 HTTP、P2P 和远程中继的新内容；正在接收的临时任务会停止并清理，不会写入半成品。
- 设备仍保持在线发现、版本上报和本机查看能力；重新打开后恢复接收。
- `/v2/info` 增加 `autoReceiveEnabled` 状态字段，便于电脑端识别手机当前是否允许投送。
- versionCode 96 / versionName 0.6.58；Android、iPhone 和 Windows 统一读取正式更新入口。
## 修复候选：Android 0.6.57 - Android 10 Keystore 不阻断局域网在线

- 修复 Redmi Note 8 / Android 10 实测的 `Unknown purpose: 64`：旧款 OEM Keystore 不支持远程中继 ECDH 密钥用途时，不能再阻断 `deviceInfo()`。
- 局域网 UDP 发现和本地 HTTP 接收现在会正常上报；仅将可选 Cloudflare 中继标记为不可用，等后续设备支持时再启用，不影响同 Wi‑Fi 传输。
- versionCode 95 / versionName 0.6.57；已在目标 Redmi Note 8（Android 10）覆盖安装并实测闭环：工作台 UDP 发现成功、HTTP 45833 `/v2/info` 成功、状态 `online`、原作品库存 `2` 正常上报；该机仅 `relayEnabled=false`，不影响局域网传输。

## 修复候选：Android 0.6.56 - Note 8 局域网发现与接收服务自恢复

- Android 接收端持有 Wi-Fi 多播锁，兼容部分旧款小米系统对局域网发现包的省电过滤；停止服务时释放锁，不改变作品数据和传输协议。
- 发现广播在系统未返回接口广播地址时按 IPv4 网段补算广播地址，并保留 `255.255.255.255` 兜底。
- HTTP 接收线程和 UDP 发现线程分别记录运行状态；任一线程瞬时失败后，下一次前台刷新可以只恢复缺失线程，不再因总服务仍标记运行而永久离线。
- versionCode 94 / versionName 0.6.56；本地源码修复候选，尚未发布 Beta，需构建与 Redmi Note 8 真机回归后再发布。

## Beta：Android 0.6.55 - 断点接收提交容错

- 修复自动补发 ZIP 已上传完成、提交阶段却因 `trash/<id>/meta.properties` 残留目录返回 500 的问题；作品库现在会安全跳过确认已孤立的回收目录，不删除目录、不影响正常作品。
- 断点发送端在任务大小与 SHA-256 一致时复用接收端原文件名，避免重试生成新时间戳文件名导致“断点任务的文件名不匹配”。
- Android versionCode 93；由 GitHub Actions run `32803292676` 构建通过，真实红米 13 仍需安装 0.6.55 后用同一断点任务验收落库与自动补发。

## 0.6.54 / iPhone 0.6.41 - 2026-08-24（已发布 Beta）

- Android 接收端在最终 commit 失败后清理临时任务并恢复在线，避免卡在“接收中”导致电脑端永不重试。
- iPhone 预览页增加确认删除按钮，修复预览计数；长按图片进入多选时避免普通点击抢占。
- Device Share Hub run `32688671241` 的三端构建、中继 E2E 和发布校验全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.54-beta.1>。
- 稳定版索引保持 Android 0.6.29 / iPhone 0.6.16；真实设备安装和自动补货仍需手机现场验收。

## Beta：Windows V4.3.29 / Android 0.6.53 / iPhone 0.6.40 - USB 失败自动降级

- USB 传送失败后会先清理临时目录，再自动继续走同一 Wi‑Fi、P2P 直传和 HTTPS 中继；用户不需要手动重发。用户主动取消时仍立即停止，不会偷偷改走另一条通道。
- 保留 USB 的事务性暂存与回滚，避免 USB 部分写入后产生重复作品；新增 USB 失败降级回归门禁。
- 三端版本提升为 Windows V4.3.29、Android 0.6.53/versionCode 91、iPhone 0.6.40/build 59；本轮需要由 GitHub Actions 云端构建并更新 Beta 索引。
- 真实 Android/iPhone 的 USB、同 Wi‑Fi、跨网 P2P、HTTPS 回退、作品库 ACK 和精准库存低于 5 自动补货仍必须现场验收。

## Beta：Windows V4.3.28 / Android 0.6.52 / iPhone 0.6.39 - 远程更新包与混合传输闭环

- 安卓 APK 更新包现在明确标记为 android-update；Cloudflare 中继、Windows 原生 P2P 和安卓接收端统一传递并校验该类型，只有 APK 完成签名/版本校验并写入更新缓存后才发送 ACK。
- Beta Windows 不再读取稳定版 latest.json，自动移动端更新改读 latest-beta.json；APK 不再走只复制文件的 USB 分支，避免误报“更新已送达”。
- iOS 明确拒绝安卓更新包，不会把 APK 当成普通作品导入；普通作品仍保持 USB、同 Wi-Fi、P2P 优先、HTTPS 中继兜底。
- 线上 Worker 已部署版本 `4feb3749-a8fd-4a20-a218-6cf5628b51fc`；真实线上 E2E 已覆盖健康检查、P2P 信令、普通作品 R2 闭环，以及 `android-update` P2P/收件箱/ACK 清理。
- Windows 直连 Wi-Fi 发生端口、连接或提交错误时，现在会清理未完成的局域网任务并自动尝试 P2P，再回退到 HTTPS 中继；不再因为“发现了 Wi-Fi”就放弃远程兜底。
- 本轮三端由 GitHub Actions 云端构建并发布 Beta；实体 Android/iPhone 的安装、P2P、HTTPS 回退和自动补货仍需现场验收。

## Beta：Windows V4.3.27 / Android 0.6.51 / iPhone 0.6.38 - 原生 P2P 数据面验收

- Windows 原生 libdatachannel 新增双 PeerConnection loopback：实际发送 256 KiB+137 字节二进制分片，接收端校验帧顺序和完整字节后回 ACK；云端 CTest 已通过并报告 100% tests passed。
- 修复 Windows P2P 发送端和接收端 DataChannel 生命周期：清理前阻断 SDP/ICE/状态回调，释放 PeerConnection；避免发送返回后回调触碰已释放状态。
- 本轮 Device Share Hub run `32601626368` 的 Android、iOS、Windows、remote-relay check 和线上 Worker E2E 全部通过；PR 分支验证完成，合并 main 后才会触发 Beta 发布索引。
- 真实 Android/iPhone 的 USB、同 Wi-Fi、跨网 P2P、HTTPS 回退、作品库落库/ACK、精准低于 5 自动补货仍需手机在线现场验收，不能用云端 loopback 代替真机证据。

## Beta：Windows V4.3.26 / Android 0.6.50 / iPhone 0.6.37 - HTTPS 代理与远程设备列表容错（已发布）

- Windows 远程中继新增可选 `ZWM_DEVICE_SHARE_RELAY_PROXY` 环境变量和 `%LOCALAPPDATA%\ZwmDeviceShareHub\relay-proxy.txt` 配置；只通过 HTTPS CONNECT 代理访问 Cloudflare，不降级到明文 HTTP。修复远程模块 User-Agent 仍写 V4.3.22 的版本不一致。
- 修复 Worker `/v1/devices` 遇到历史损坏成员记录时整页返回 500；现在记录告警并跳过损坏项，其他有效设备仍能继续在线和自动分发。
- 三端版本同步为 Android 0.6.50/versionCode 88、iPhone 0.6.37/build 56、Windows V4.3.26；Device Share Hub run `32596690436` 的三端构建、remote-relay check 和线上 Worker E2E 全部通过。Worker 最新部署版本为 `fb0ba8fb-6f15-46f3-b31d-00cbeba36c59`。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.50-beta.1>；Android SHA-256：`f007889e30526059438df53d816e5a6d8f348b01371e1da74958a96fe3f19b64`；iPhone IPA SHA-256：`6cfaa0f7e7db5bf544cdbf172f663d06e9f6c0b06b1ffe59dae9568615dfe2e0`；Windows SHA-256：`b7319e0b72d049ea641fca5f944401b6c7fbdc2b8a104efb84128dfccd8c646`。
- Beta `latest-beta.json` 与 AltStore Beta 源已同步并复核；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。三端包已同步到 `C:\Users\z\Desktop`，桌面中控当前运行 V4.3.26、TCP 45833/UDP 45834。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待现场设备验收。

## Beta：Windows V4.3.25 / Android 0.6.49 / iPhone 0.6.36 - P2P 信令会话清理修复（已发布）

- 修复 Android/iPhone P2P 传输完成、失败或取消后只关闭 WebRTC PeerConnection、未释放 Cloudflare 信令会话的问题；现在会关闭信令会话，避免下一轮在线轮询重复接收旧会话并重新协商。
- 三端版本同步为 Android 0.6.49/versionCode 87、iPhone 0.6.36/build 55、Windows V4.3.25；Device Share Hub run `32594831524` 的 Android、iOS、Windows、remote-relay check 和线上 Worker E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.49-beta.1>；Android SHA-256：`06fdbfa2ce1a55eccd00b958fe1723fbab4c798c74d39b3d0c7cd30959e0b515`；iPhone IPA SHA-256：`4687f0fa1163c428e06610ce10fc8edcb367530274288f6c9467b7f76e24edca`；Windows SHA-256：`50c3693612ccdf256d52f04b23bf9ae71a5aa3207b402e083128199a8e3724f0`。
- Beta `latest-beta.json` 与 AltStore Beta 源已同步并以 Raw URL 复核；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。三端包已同步到 `C:\Users\z\Desktop`，桌面中控当前运行 V4.3.25、TCP 45833/UDP 45834。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待现场设备验收。

## Beta：Windows V4.3.24 / Android 0.6.48 / iPhone 0.6.35 - iOS P2P ICE 与版本元数据修复（已发布）

- 修复 iOS P2P 信令轮询与 WebRTC 回调并发读写 ICE 候选队列的竞态；新增 P2P ICE、ACK 延迟刷新、会话回收、HTTPS 回退和库写入顺序静态门禁。
- 统一 iOS `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` 与 `Info.plist`：0.6.35/build 54；Android 为 0.6.48/versionCode 86；Windows 为 V4.3.24。
- Device Share Hub run `32593184579` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.48-beta.1>。
- Android SHA-256：`501f1bed085a1b37b13deb89a2bc5879b07a01c1ec42c6108a59bb49ce53d15d`；iPhone IPA SHA-256：`2ad5da8a18d7b62565812d6d9c81cdb164f204605354a4686c8db2244e06921b`；Windows SHA-256：`7f622d23ce8c66dfd80df4445549bbc758bae155ef99309d4d9d65bb71cd1776`。
- Beta `latest-beta.json` 和 AltStore Beta 源已同步；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.23 / Android 0.6.47 / iPhone 0.6.34 - 可选测试版更新通道（已发布）

- Android 和 iPhone 设置新增“更新通道”：默认稳定版，切换到测试版后分别读取 `latest-beta.json` 和 `altstore-beta.json`，不会把 Beta 包推给稳定用户。
- Android 测试版更新会继续使用 HTTPS 下载、SHA-256 校验和系统安装确认；iPhone 会复制当前通道对应的 AltStore 源，仍由 AltStore/AltServer 完成签名更新。
- Device Share Hub run `32590760825` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.47-beta.1>。
- Android SHA-256：`ad3e248c6d988b9c832c4ed5a7b3272b7e13f62d7ad33769b37f76faffc9e465`；iPhone IPA SHA-256：`6e2e34a9ec46c4e013a6beeaefd2913d9c685c5674c6f06e47e12c0601e11a88`；Windows SHA-256：`0dcc312aadd7b883a79b0436e6a98f5dbe4ffeb8c14e5b21f14b0ff2b5d7d4cb`。
- Beta `latest-beta.json` 和 AltStore Beta 源已同步；稳定 `latest.json` 保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35。真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.23 / Android 0.6.46 / iPhone 0.6.33 - 混合通道状态合并修复（已发布）

- 修复同一台手机同时被局域网探测和 Cloudflare 中继发现时，局域网记录会覆盖远程在线状态、凭证和库存的问题。
- 为 Wi‑Fi 路由保存独立的最近观察时间；远程心跳不再把旧 IP 伪装成当前 Wi‑Fi 路由，远程设备会正确进入 P2P 优先、HTTPS 中继兜底路径。
- 发送前只把 35 秒内真实观察到的 Wi‑Fi 当作直连；旧地址失效时不再卡在错误 Wi‑Fi 路径。新增源码回归门禁，并同步更新 Windows 网络 User-Agent。
- Device Share Hub run `32589239907` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.3>。
- Windows SHA-256：`3a560cb55968173324b80c545ab7315e79f8625e3e1227dc339972ba70ff7b51`；Android SHA-256：`4278644080854a55380f4ad5c160935270fd7d04d316f925cccb58168745b955`；iPhone IPA SHA-256：`3d011d2331b70b6a3f023f1045814a41253202c71c0df36cac24012bd9efc5ba`。
- 三端包已同步到 `C:\Users\z\Desktop`；稳定 `latest.json` 和 AltStore Beta 源不切换，真实手机 USB/Wi-Fi/P2P/HTTPS、落库、ACK 和自动补货仍待连接验收。

## Beta：Windows V4.3.22 / Android 0.6.46 / iPhone 0.6.33 - 远程库存合并边界修复（已发布）

- 修复同一台手机同时被局域网和远程中继发现时，缺失的远程库存字段覆盖本地完整库存的问题；远程字段只有在实际携带值时才合并，精准自动补货不再因心跳缺字段偶发漏发。
- Device Share Hub run `32587368303` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.2>。
- Windows SHA-256：`8888a99131fb7174a5d5730f5cf2a9cbcec1c9d570b9d920d76dbe9a58ac5da1`；Android/iPhone 沿用 Beta1 已核对的 SHA-256；稳定 `latest.json` 和 AltStore Beta 源不变，实体手机验收仍待连接。

## Beta：Windows V4.3.21 / Android 0.6.46 / iPhone 0.6.33 - 远程库存心跳与自动补货闭环（已发布）

- 修复远程中继只上报在线状态、不上报手机精准库存的问题；Android/iPhone 每 10 秒心跳同步总数、精准/泛/未分类库存和版本信息，Durable Object 校验后提供给 Windows。
- Windows 远程设备现在合并库存并统一进入 USB/Wi-Fi/远程三路可用判断；右键“发送到”、自动更新候选和精准流量低于阈值的自动补货都不再漏掉无局域网 IP 的远程手机在线设备。
- 旧手机没有分类库存字段时继续按“未知”处理，不会拿总作品数冒充精准流量；Device Share Hub run `32586026767` 的 Windows、Android、iOS、remote-relay check 和线上 Worker E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.46-beta.1>；Android SHA-256：`30a0d3ca3376e00f1f6c3a0563e71804a7a80bd6aaddad34d2290fc8b27f902c`；iPhone IPA SHA-256：`d1953c1e8dc5b0ff6851d62c8eecbc3bba663b51341e01bffac8ace70b9e4f6f`；Windows SHA-256：`4429a9a7f9f295fa68989ab1d76b191638c651e01f2ac496f5aca79243524977`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源内容提交为 `4578bce603ea5476252f25bc8a74c2ef719e30b5`，稳定 `latest.json` 仍保持 Android 0.6.29/versionCode 67、iPhone 0.6.16/build 35；实体手机验收仍待连接。

## Beta：Windows V4.3.20 / Android 0.6.45 / iPhone 0.6.32 - 自动补货递归发现修复（已发布）

- 修复自动补货只扫描作品库第一层的问题；现在会递归发现实际生产目录下的 `作品集_xxx[转]` / `作品集_xxx【转】` 精准作品文件夹，仍不会拿泛流量目录替代精准库存。
- 新增源级回归门禁，要求自动补货使用递归扫描并保留精准标签判断；Device Share Hub run `32583765953` 的 Windows、Android、iOS、remote-relay check 和 live E2E 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.3>；Android SHA-256：`e19f189b276a239059a5d884f5dd12129d61ef174cc848bd489922ed0ca05784`；iPhone IPA SHA-256：`302e9bd46d9dfd5fe558adec096891224681a97315465ed4cae167addc89bb04`；Windows SHA-256：`69305cec244bb37f928bba13cf86868cceb120a56fa191bf4693ff752ba2ef50`。
- 三端包已同步到 `C:\Users\z\Desktop`；稳定 `latest.json` 和 AltStore Beta 的 iOS 语义版本不变，真实手机验收仍待连接。

## Beta：Windows V4.3.19 / Android 0.6.45 / iPhone 0.6.32 - 自动分发默认配置修复（已发布）

- 新建或升级 Windows 内容数据库时，自动手机更新、精准流量自动补货和阈值 5 会自动写入默认值；用户明确关闭或修改阈值后不会被覆盖。
- 当前电脑数据库已同步为 `auto_mobile_update_enabled=1`、`auto_restock_enabled=1`、`auto_restock_threshold=5`，原数据库备份保存在临时目录；手机上线后会直接进入自动分发验收。
- Device Share Hub run `32581609531` 的 Windows、Android、iOS、remote-relay check 和 live E2E 全部通过；Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.2>。
- Android SHA-256：`4e91dff5c7e926e8ba668c2322542d45584055345c6471c76ae09deb1ca06d2b`；iPhone IPA SHA-256：`19e1ab04cc83ac0f0df573c03c52dd6167a267d776af3408013f0b75cd01a52d`；Windows SHA-256：`bc570acacda62b490a3f73503d72fa3808e7e64d7111f19f20b7a00a2118c2f9`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已指向 Beta 0.6.45 的 iOS 包，源提交 `83de151d181976cb72e7389790acfaa12cb2eec5`。稳定 `latest.json` 和 Android/iOS 稳定版本不变；实体手机回归仍待连接。

## Beta：Windows V4.3.18 / Android 0.6.45 / iPhone 0.6.32 - 2026-08-22

- 修复 Windows 手机自动更新在传输开始前就写入“已发送”状态、失败后永久不重试的问题；现在只在传输成功后写入已送达记录，失败会记录可重试状态。
- Android/iPhone 在线信标新增向后兼容的精准、泛、未分类库存尾字段；Windows 可在 /v2/info 暂时不可用时继续按精准流量判断自动补货。
- 新增自动更新重试和库存信标源级回归门禁；Device Share Hub run `32579462284` 的 Android、iOS、Windows、remote-relay check 和 live E2E 均通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.45-beta.1>；Android SHA-256：`220ea5a469e37ee051840ecbe541705f9267888a3d8c55c6e14aaecc90102aa0`；iPhone IPA SHA-256：`21106f8ca543bfcb940dd15fa7fada8735e3a075a5364b5026acf9833ee569f8`；Windows SHA-256：`d2d02e0e1cf313cf8e42efd3aa4cd58a8a2af437e8f56eee39b8606081b74e35`。
- 三端云端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS 0.6.32/build 51，源提交 `a0591f1401365e4ae252882083ea9eda51082f8e`。稳定 `latest.json` 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35；实体手机回归仍待连接。

## Beta：Windows V4.3.17 / Android 0.6.44 / iPhone 0.6.31 - 2026-08-22

- 修复 P2P 已写入作品库但 ACK 丢失后，Windows 回退 HTTPS 中继造成 Android 重复入库的问题；已导入的 transfer 现在只补发 ACK，不会再次下载或创建作品。
- Android 成功 ACK 后清除远程收件占位并清理重试缓存；iOS 重复 P2P 完成路径释放活动引擎。
- 版本递增为 Windows 4.3.17、Android versionCode 82 / versionName 0.6.44、iPhone 0.6.31/build 50；Device Share Hub run `32575362864`、Repository quality `32575362946`、Secret scan `32575362919` 和线上 Worker E2E job `97036844830` 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.44-beta.1>；Android SHA-256：`0fe499cf1040d12c28ba9ef8c5aa8e7f2d214475d6de7c88e985f27d4430381d`；iPhone IPA SHA-256：`1db6768df098beb2c8924d5b7fb0b923d81ff53678b840bc297787fe1cd25c92`；Windows SHA-256：`f89106a50bd4d73295a098e803382648680c3fbd90d642d70499a0c736a4af52`。
- 三端包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS 0.6.31/build 50，源提交 `f46cca7fd98504ea4c195e89a75a6697a80d197a`。稳定 `latest.json` 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。

## Beta：Windows V4.3.16 / Android 0.6.43 / iPhone 0.6.30 - 2026-08-22

- 修复 Windows 远程中继任务在上传/提交失败后没有立即取消的问题；失败时清理 R2 临时对象和收件箱任务，避免孤立任务等待 TTL。
- 三端版本递增为 Windows 4.3.16、Android versionCode 81 / versionName 0.6.43、iPhone 0.6.30/build 49；Device Share Hub run 32571763937、Repository quality run 32571763918、Secret scan run 32571763916 全部通过，正式 Worker E2E job 97028121778 通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.43-beta.1>；Android SHA-256：15131AC4163FDD0111B0BC9497868D50E1B82E8E8F5352BA1845501B4828FBF9；iPhone IPA SHA-256：5E8DE6EF4FA435D19B361B889B791B9B8DED80989AF4E6A7CA08DD15587214E5；Windows SHA-256：AE8893BFA3F2B2072CCCB44413848E9DFB341985702014B7A818320AFA912F11。
- 三端新云构建包已同步到 C:\Users\z\Desktop；AltStore Beta 源已更新为 iOS 0.6.30/build 49，源提交 6131dcf4faea6f39c0b49261b471f05079a1c3d6。稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。

## Beta：Windows V4.3.15 / Android 0.6.42 / iPhone 0.6.29 - 2026-08-22

- 修复 Android P2P ICE 候选入队与远端 Description 回调同时发生时的竞态；候选入队和排空现在由同一把锁保护，避免候选丢失后不必要地回退中继。
- 三端版本同步提升为 Windows 4.3.15、Android versionCode 80 / versionName 0.6.42、iPhone 0.6.29/build 48；GitHub Actions Device Share Hub run 32565416952、Repository quality run 32565417059、Secret scan run 32565416950 已通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.42-beta.1>，AltStore Beta 源已更新；Android SHA-256：B53D470F0D30115CA493BF162F9E196F6277962B9E4EF00E712B6FA6DCBC6955；iPhone IPA SHA-256：716730F02C949338BB923FD28485F053D4856897D4795088EAE35496AE362017；Windows SHA-256：DD9E256A4A6B41C2084719E2B82CF5FB09858351827DD2BB3798DAD3D1A8784C。
- 三端云构建包已同步到 C:\Users\z\Desktop；稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35。真实 Android/iPhone/Windows 设备验收仍待连接，未使用本地 Android Gradle、Xcode 或 Windows 构建替代证据。
- 工程门禁新增正式 Cloudflare Worker 线上 E2E；最新 run 32568228480 的 `remote-relay-live-e2e` job 97019921822 已验证中继控制面、P2P 信令、R2 临时对象和 ACK 删除，仍不替代实体手机跨网实传验收。
- 校正 `docs/REMOTE_PROTOCOL_V1.md` 的过期状态，明确当前是公开作品 `plain` 链路、三端 P2P 数据面已接入、Cloudflare 已部署，且真机跨网验收仍是未完成项。
- 新增云端隐私回归门禁：Android/iOS 构建前检查截图监听、悬浮窗、后台读剪切板和相册读取入口，APK 产物再检查已删除的高风险权限，防止旧功能回归。
- 修复 Windows 远程中继在任务已创建后上传/提交失败时未立即取消任务的问题；现在会清理 R2 临时对象、收件箱任务和本次失败的 transferId，避免孤立任务等待 TTL。

## Beta：Windows V4.3.14 / Android 0.6.41 / iPhone 0.6.28 - 2026-08-22

- 修复 Android P2P 引擎跨 WebRTC 回调、信令轮询、文件队列和超时线程读取状态时的可见性问题；peer、channel、finished 和远端描述状态现在使用 volatile，避免活连接被误判为超时或结束后继续清理。
- GitHub Actions Device Share Hub run 32562005856 已通过；Repository quality run 32562005857、Secret scan run 32562005864 已通过，remote-relay 测试 13/13 通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.41-beta.1>，已包含 Android 0.6.41、iPhone 0.6.28 和 Windows 4.3.14 三端资产；AltStore Beta 源内容提交为 e58a7b76cb0ecb96b7de05e519f89acf6fc27b41。
- Android SHA-256：e9bd39eb83b1bdc516c4824fdb4a451cdad97dc31d741d16fc0a9f8de9989cb5；iPhone IPA SHA-256：b03f6fa4ba9830b88f059fc1a5fe41f07d4b24d8e852ec6049c7e77e4a28deed；Windows SHA-256：a65a325f248e397cba5eacc8057ebffe03227c8cdb3296720353d9d9c11b5bd0。
- 三端云构建包已同步到 C:\Users\z\Desktop；稳定 latest.json 仍保持 Android 0.6.29 / versionCode 67、iPhone 0.6.16 / build 35，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备当前仍未连接；云构建、协议测试和发布资产不能替代真机安全扫描、P2P 成功与 HTTPS 中继回退验收。

## Beta：Windows V4.3.13 / Android 0.6.40 / iPhone 0.6.27 - 2026-08-22

- 清除 Android Manifest 中遗留的 `READ_MEDIA_IMAGES` 与 `READ_MEDIA_VISUAL_USER_SELECTED`；手机端没有自动截图采集、截图观察器或悬浮窗功能，不再为这条旧链路声明相册读取权限。
- 保留 Android 10 隐藏作品兼容通道所需的旧存储权限声明与 SAF 文件夹授权；它只在用户主动选择作品文件夹后用于兼容导入，不读取系统剪切板或自动扫描截图。
- Windows、Android、iPhone 版本同步提升为 `4.3.13`、`versionCode=78` / `versionName=0.6.40`、`0.6.27/build 46`；GitHub Actions Device Share Hub run `32559885341`、Secret scan `32559885315`、Repository quality `32559885563` 全部通过。
- Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.40-beta.1>；Android SHA-256：`f14d708707c8d1ed5be3ae81e0873f644ec5d30d2496592439a6898cba2d6faa`；iPhone IPA SHA-256：`98d671107d6ad686870fcebdd1f64a0198960e14276cd532ac2b6baeabbbd61f`；Windows SHA-256：`994a20ecb0ef01f533bb99e71dfacf1d73886c019127e986ef5a54d48cbcff23`。
- 桌面包已同步到 `C:\Users\z\Desktop`；AltStore Beta 源已更新为 iOS `0.6.27/build 46`，内容提交 `d64b3df036109a52fa62b1fc997cb938419d9b6e`。稳定 `latest.json` 仍保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`。
- 真实 Android/iPhone/Windows 设备仍需连接后复测安装、权限列表、安全扫描、作品收发、P2P 和 HTTPS 中继回退；云构建和发布不替代真机业务验收。

## Beta：Windows V4.3.12 / Android 0.6.39 / iPhone 0.6.26 - 2026-08-22

- 修复 Android P2P 引擎在 PeerConnection 创建瞬间失败后仍被放进活动 map 的问题；现在启动即失败会被丢弃，下一轮收件轮询可以正常重试，不会卡成“已处理中”。
- Android 取消已结束的 P2P 引擎时安全忽略重复清理，不再因为已关闭的执行队列抛出异常；iOS 原有的启动失败保护保持一致。
- 版本号已提升为 Windows `4.3.12`、Android `versionCode=77` / `versionName=0.6.39`、iPhone `0.6.26/build 45`。
- GitHub Actions run `32558264352` 已完成 Android、iOS、Windows 和 remote-relay 检查；安全扫描 run `32558264343`、质量检查 run `32558264394` 通过。Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.39-beta.1>。
- Android SHA-256：`ff6153918f9c8ac7793e9ce7a0c1284ed20e63243a94c8780d106a41e0bd3f23`；iPhone IPA SHA-256：`189cd17b4904dd97b8fa750559fcb6eeabb018a3274c1367967c2534339b8506`；Windows SHA-256：`ea87a27b79198e091a09e927c0a3369bc919bfd3846ac38210bd284a4d28bb13`。
- 桌面包已同步到 `C:\Users\z\Desktop`；Beta iOS 更新源已更新为 0.6.26/build 45。稳定 `latest.json` 保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建、协议测试或 Release 发布当作真机业务通过。

## Beta：Windows V4.3.11 / Android 0.6.38 / iPhone 0.6.25 - 2026-08-22

- 修复 Android/iOS P2P 收件端在发送端没有建立 DataChannel 时可能长期等待的问题；现在建连超过 20 秒会主动失败并进入 HTTPS 中继回退。
- Android 不再在 WebRTC 回调线程执行文件写入和 SHA-256 校验，先复制数据帧，再交给串行传输队列处理，降低大文件传送卡死和回调阻塞风险。
- 版本号已提升为 Windows `4.3.11`、Android `versionCode=76` / `versionName=0.6.38`、iPhone `0.6.25/build 44`。
- GitHub Actions run `32556181728` 已完成 Android、iOS、Windows 和 remote-relay 检查；安全扫描 run `32556181727`、质量检查 run `32556181732` 通过。Beta 发布页为 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.38-beta.1>。
- Android SHA-256：`9558e76b1fac6ae5bea98219a86645f594d783e6e324f3a07cc141fe09f7a368`；iPhone IPA SHA-256：`14920197a1811a144be995be9eb8f082245a0cd1d54df0a7f0799aa76b5f12e2`；Windows SHA-256：`22353fd7631fe32730205e00646ae5f91546789a749e0471c4e0489ec0c2876e`。
- 桌面包已同步到 `C:\Users\z\Desktop`；Beta iOS 更新源已更新为 0.6.25/build 44。稳定 `latest.json` 保持 Android `0.6.29` / versionCode `67`、iPhone `0.6.16` / build `35`，没有把 Beta 推入稳定通道。
- 真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建、协议测试或 Release 发布当作真机业务通过。

## Beta：Windows V4.3.10 / Android 0.6.37 / iPhone 0.6.24 - 2026-08-22

- 修复 Android/iOS P2P 接收端失败后只清本地引擎、没有立即关闭 Cloudflare 信令会话的问题；现在失败会异步关闭控制面会话，不阻塞 WebRTC 失败回调，避免等 2 分钟 TTL 才回收并让 HTTPS 回退更干净。
- 版本号已提升为 Windows `4.3.10`、Android `versionCode=75` / `versionName=0.6.37`、iPhone `0.6.24/build 43`。
- GitHub Actions run `32553627094` 已完成 Android、iOS、Windows、remote-relay、质量检查和安全检查；三端安装包已上传到 Beta 发布页 <https://github.com/zwmopen/gallery-updates/releases/tag/v0.6.37-beta.1>。
- Android SHA-256：`f13132b8311f333b07aba44d8527e644a16c474838f696f81d5a30721e2dc011`；iPhone IPA SHA-256：`44d768c2f401ba25e926bbec4b769094d4695a9732359fcfc986fd806bcdeb`；Windows SHA-256：`9eeefaf815fc841bfcf616029ece0c654e6278ba94a974690515a46186d71bf2`。
- 桌面包已同步到 `C:\Users\z\Desktop`；真实 Android/iPhone/Windows 设备互传验收仍待连接设备，不把云构建当作真机业务通过。

## Beta：Windows V4.3.9 / Android 0.6.36 / iPhone 0.6.23 - 2026-08-22

- 修复 P2P 成功 ACK 刚写入发送队列就关闭 DataChannel 的尾部竞态；移动端等待短暂刷新窗口后再关闭，避免电脑误判失败并重复走 HTTPS 中继。
- iPhone 成功导入后清理 P2P 临时缓存；Windows DataChannel 背压连续停滞 20 秒时主动失败，让既有 HTTPS 中继回退真正生效，不再无限卡住。
- 修复 Windows 在 P2P 会话已创建后发生文件校验、信令或传输异常时未关闭控制面会话的问题；现在所有异常路径都会先清理会话，再进入 HTTPS 中继回退，避免留下脏会话。
- Android `versionCode=74` / `versionName=0.6.36`；iPhone `0.6.23/build 42`；Windows `4.3.9`。同一次 GitHub Actions 三端云构建已通过，实体设备业务验收仍待连接手机。

## Beta：Windows V4.3.8 / Android 0.6.35 / iPhone 0.6.22 - 2026-08-22

- 把之前只有 SDP/ICE 信令的“P2P”补成真实 DataChannel 数据面：Windows 使用 libdatachannel，Android 使用 WebRTC SDK，iPhone 使用 WebRTC XCFramework。
- 传输顺序固定为 manifest → 48 KiB 二进制分片 → 完整性校验 → 作品库导入 → ACK；任何建连、断线或校验失败都会自动回退到现有 Cloudflare HTTPS 中继。
- P2P 信令仍只经过 Cloudflare，公开作品字节不进 Worker/R2；本阶段不增加应用层加密、截图、剪切板、悬浮窗或无障碍权限。
- Android `versionCode=73` / `versionName=0.6.35`；iPhone `0.6.22/build 41`；Windows `4.3.8`。必须以同一次 GitHub Actions 三端构建和实体手机业务验收作为交付边界。

## Beta：Windows V4.3.7 / Android 0.6.34 / iPhone 0.6.21 - 2026-08-21

- 补齐混合传输实际链路：Windows 首次发现手机时读取手机公钥，自动签发成员凭证并通过现有局域网下发；手机保存后每 10 秒登录 Cloudflare 中继并上报在线状态。
- Windows 原生面板新增远程中继发送兜底和远程设备在线轮询；USB/Wi-Fi 不可用时，自动创建普通公开任务、上传 R2、提交，手机写入作品库后 ACK 清理。
- 修复 Worker 路由要求工作区身份但移动端后续请求未带 `X-Workspace-Id` 的断链问题；Android/iOS 的心跳、收件箱、任务查询、对象下载和 ACK 全部补齐工作区头。
- 新增真实部署烟测 `remote-relay/scripts/cloudflare-e2e.mjs`，已实测健康检查、设备会话、在线心跳、R2 上传、提交、下载 SHA-256、ACK 与 R2 删除。
- Windows 原生面板 `4.3.7`；Android `versionName=0.6.34` / `versionCode=72`；iPhone `0.6.21` / build `40`。

## Beta：Android 0.6.33 / iPhone 0.6.20 - 2026-08-21

- 远程中继新增 `mode: plain` 普通公开作品传送：电脑上传普通 ZIP，手机按对象字节数与 SHA-256 校验后写入现有作品库，成功后才发送 ACK；失败不会 ACK，云端临时对象会继续保留等待重试或过期清理。
- Android 与 iPhone 都已接入远程对象流式下载、临时文件校验、作品库导入和 ACK；重复轮询与 ACK 失败恢复不会重复导入已确认的任务。
- 新增桌面发送脚本 `remote-relay/scripts/send-public-work.mjs`，支持作品文件夹自动打包或直接发送 ZIP。文件内容不做应用层加密；链路仍要求 HTTPS，认证凭证不写入仓库。
- Android `versionName=0.6.33` / `versionCode=71`；iPhone `0.6.20` / build `39`。
- 本地中继协议测试已覆盖“创建 → 上传 → 提交 → 收件箱 → 下载对象 → ACK 删除”。Cloudflare Worker、Durable Object 和 R2 已部署到 `zwm-device-share-relay.zwmrpg.workers.dev`；真实手机安装与异地网络实传仍需单独验收。
- Android 长按多选改为只更新已渲染列表，不再触发完整扫描；iOS 图片多选只刷新受影响的图片格，避免长按选择出现卡顿。

## Cloudflare 中继正式部署 - 2026-08-21

- Worker `zwm-device-share-relay` 已部署，Durable Object 使用 `WorkspaceRelay`，R2 暂存桶为 `zwm-device-share-relay`。
- Wrangler 实读部署版本已更新为 `b4fc48b0-9b3b-4e73-b828-d51abe59ba4c`，部署占比 100%；本机直连烟测受 DNS/连接异常影响，真实手机安装与异地网络实传仍需单独验收。
- 当前账号没有活动 Zone，暂使用 `workers.dev` 公网地址；本机网络对该域名的 DNS/连接异常只影响本机烟测，不改变 Cloudflare 部署状态。
- 混合传输当前是“局域网/USB 直传优先 + HTTPS 中继兜底”；WebRTC/QUIC 打洞仍是后续优化项，中继兜底已经接入 Windows 原生面板。

## Beta：Android 0.6.31 / iPhone 0.6.18 - 2026-08-21

- 在上一版远程身份、会话和心跳基础上，Android/iOS 开始轮询远程收件箱。
- 客户端只接受接收设备匹配、状态为 `ready`、对象序号不重复、密文大小与 SHA-256 格式有效的任务；过期、上传中、目标不匹配或字段损坏的任务会被忽略。
- 同一远程任务在客户端进程内去重，避免每 10 秒心跳重复触发；现有 USB/LAN V2 不变。
- 这一版仍未接入普通文件下载、作品库导入和 ACK，也未部署正式 Cloudflare 服务；后续已由 0.6.33 接续实现普通公开作品链路。
- Android `versionName=0.6.31` / `versionCode=69`；iPhone `0.6.18` / build `37`。

## Beta：Android 0.6.30 / iPhone 0.6.17 - 2026-08-21

- 这是高级远程传送第一阶段的可安装 Beta 测试包，不替换正式稳定版 `main`。
- 继续保留现有 USB、局域网 Wi-Fi、作品列表、精准流量/泛流量口径和隐私收口；本次增加远程中继控制面、设备身份、会话心跳和可续传任务基础。
- Android `versionName=0.6.30` / `versionCode=68`；iPhone `0.6.17` / build `36`。
- Beta 仍未完成真实跨网络收发验收，安装后重点测试启动、作品收发、更新检查和设备在线状态。

## 高级远程传送第一阶段（开发中）- 2026-08-21

- 远程中继任务状态增加对象级上传进度、已上传密文字节数和下一待传对象索引，客户端重启后可继续原任务。
- 同一任务对象重复上传时，服务端校验现有密文大小与哈希元数据后返回 `reused=true`，避免断线重试重复写入。
- Android 与 iOS 接收服务启动时各自在系统密钥存储生成远程签名/密钥协商用 P-256 密钥；私钥不进入日志、发现广播或网络请求。
- Android 与 iOS 新增 HTTPS 中继控制面客户端：用设备签名密钥完成挑战登录，使用短期 Bearer 会话发送在线心跳并读取收件箱/任务状态；收件箱为空和非法地址均有明确边界。
- Android 与 iOS 新增远程登记资料存储：只保存 HTTPS 地址、公开成员凭证和管理员签名；私钥继续留在 Keystore/Keychain，Bearer 会话令牌不落盘。
- Android 前台接收服务与 iOS 局域网接收服务已接入独立远程心跳调度器：未登记时不联网，登记后每 10 秒重新认证/发送心跳，失败只丢弃内存会话并等待下一轮重试。
- 修复远程地址边界：拒绝带路径、查询参数、片段或用户信息的地址，避免接口路径拼接错误；远程登录/心跳失败加入最长 5 分钟退避，并在凭证轮换时立即重新登录。
- 本阶段尚未发布手机包；三端设备凭证、在线会话和真正跨网络收发仍在后续开发与实机验收中。

## Android 0.6.29 / iPhone 0.6.16 更新闭环优化 - 2026-08-20

- Android 从后台回到前台且超过 6 小时未检查时，会静默检查并准备更新，不重复打扰用户。
- iPhone 检查到新版本时，可直接打开 AltStore，或复制更新源；不再把 AltStore 用户引导回旧的“连接电脑”提示。
- Android `0.6.29` / versionCode `67`；iPhone `0.6.16` / build `35`。

## Android 0.6.28 / iPhone 0.6.15 AltStore 自动更新源 - 2026-08-20

- iPhone AltStore 更新源已接入云端发布流水线：首次添加 `https://raw.githubusercontent.com/zwmopen/gallery-updates/main/altstore.json` 后，后续 IPA 发布会自动进入 AltStore 更新列表。Android `0.6.28` / versionCode `66`；iPhone `0.6.15` / build `34`。

## Android 0.6.27 / iPhone 0.6.14 分类名称统一 - 2026-08-20

- 手机端顶部分类按钮统一显示“精准流量”和“泛流量”；电脑端自动补货继续使用同一精准流量口径。
- 仅调整用户界面文案，内部 `conversion` 字段、`[转]`/`【转】` 目录识别和自动补货规则保持不变。
- Android `versionCode=65` / `versionName=0.6.27`；iPhone `0.6.14/build 33`。发布状态以云端构建、Release 和 `latest.json` 为准。

## Android 0.6.26 / iPhone 0.6.13 平台按钮即时状态 - 2026-08-20

- 点击“发抖音”或“发小红书”后，对应按钮立即变为灰色；灰色只表示该平台已经点击过，按钮仍可再次点击。
- Android 从分享页返回作品列表时重新读取已保存的分享次数；iOS 按抖音/小红书各自的点击次数恢复按钮状态，分享准备失败时回滚即时状态。
- 修正云端发布步骤，同时上传 Android APK 与 iOS IPA，并同步 `latest.json` 的两端版本，确保苹果端也能检测到本次更新。
- Android `versionCode=64` / `versionName=0.6.26`；iPhone `0.6.13/build 32`。云端 Actions、Release、`latest.json` 与真实手机安装仍分别核验。

## Android 0.6.25 / iPhone 0.6.12 按钮层级优化 - 2026-08-19

- 作品卡片内的“预览 / 发抖音 / 发小红书”改为纵向紧凑布局，按钮按内容自适应宽度，不再横向等分铺满。
- “预览”改为轻量描边按钮，两个平台入口保留绿色主按钮与分享次数无障碍说明；预览和分享行为不变。
- Android `versionCode=63` / `versionName=0.6.25`；iPhone `0.6.12/build 31`。发布状态以云端 Actions、Release、`latest.json` 和桌面包哈希为准。

## Android 0.6.24 / iPhone 0.6.11 隐私与作品预览收口 - 2026-08-18

- 两端删除自动截图采集、截图中转和自动读取/同步系统剪切板；iOS 删除 `ClipboardBridge`、接收接口和设置开关，Android 继续保留的复制动作都只在用户点击后发生。
- 作品卡片改为一行三个紧凑入口：“预览”“发抖音”“发小红书”，整行仍保持可读性和无障碍说明；平台计数保留在作品信息中。
- Android 作品预览增加“上一张/下一张”；iPhone 作品预览改为左右分页滑动，并显示当前张数。
- Android `versionCode=62` / `versionName=0.6.24`；iPhone `0.6.11/build 30`。本节记录的是云端交付候选，发布状态以 Actions、Release 和 `latest.json` 的一致性核验为准。

## Android 0.6.23 移除剪切板、截图与悬浮窗模块 - 2026-08-18

- 移除手机端共享剪切板、悬浮剪切板、截图监听、截图自动发送和截图中转整套功能；删除对应界面、后台观察器、接收器、HTTP `/v2/clipboard` 接口及中继任务路径。
- 删除 `SYSTEM_ALERT_WINDOW` 悬浮窗权限；普通文件/图片传输仍保留所需的媒体访问权限。用户主动点击“复制并分享”或“复制诊断信息”时的明确复制动作不受影响。
- 该版本移除了会触发系统隐私/风险提示的自动剪切板读取、系统剪切板写回、悬浮窗和截图监测行为；是否仍被厂商安全扫描提示需在真实设备安装后复测。
- Android `versionCode=61` / `versionName=0.6.23`；当前为本地构建候选，未自动发布或安装。

## iPhone 0.6.9 分类库存统一 - 2026-08-18

- iPhone `/v2/info` 新增 `workCounts`，上报精准流量、泛流量和未分类数量，与 Android/Windows 使用同一字段口径。
- Windows 自动补货改为只看精准流量（`conversion`）库存；字段缺失时不自动补发，不再用总数替代。
- iOS GitHub Actions 的 IPA 名称、Artifact 名称和包内版本校验改为从 `project.yml` 动态读取，不再被旧版 0.6.8/build 27 断言卡住。

## Android 0.6.22 手动下载入口 - 2026-08-17

- “设置 → 软件说明”增加可点击的 `gallery-updates` 发布页地址，方便网络受限或不想使用应用内更新时手动下载 APK。
- 点击只打开系统浏览器，不自动安装、不绕过 Android 安全确认；没有可用浏览器时给出明确提示。
- 发布页地址与应用内更新检查共用同一地址常量，避免维护时出现两个不同下载入口。

## Android 0.6.21 大文件断点续传候选 - 2026-08-14

- 修复电脑发送大文件中途断线后必然从头传的问题：电脑为同一内容复用稳定任务 ID，手机保留 `.receiving` 临时文件和任务清单。
- 新增 `GET /v2/tasks/{taskId}` 状态查询；重试前读取手机已收字节，通过 `X-File-Offset` 只发送剩余部分。
- 手机对已有字节和本次续传内容合并计算 SHA-256，完整校验通过后才提交；明确取消任务才会清理断点。
- 未升级的旧手机没有状态接口时仍兼容从头传输；不会把旧协议误判成已支持断点。
- 已通过 Android 单元测试、Python 传输技能单元测试和语法检查；当前 VIVO 仍需安装 0.6.21 后执行真实中断/续传验收。

## 文件传输第一批稳定性修复 - 2026-08-14

- `device-folder-transfer` 的设备发现不再逐个记录局域网超时；改为记录扫描数量、成功数、失败数，并将 Python 日志按大小滚动，避免单次无设备扫描把日志撑到几十 MB。
- Android 接收端会每分钟清理超过 5 分钟没有活动且尚未上传完整的中断任务，删除临时文件并恢复为可接收状态；已收齐文件的提交处理不会被维护任务打断。
- Windows USB 传送先使用临时名称写入，全部完成后再改成正式名称；USB 已开始写入但未完成时停止，不再盲目切换 Wi-Fi，避免半份文件和重复传输。
- 补充 USB 多项目提交回滚：改名阶段中途取消或失败时，已改成正式名称的项目也会按对象 ID 清理，避免留下半批正式文件；普通 Python 发送在 UDP 已找到设备时也跳过无意义的整段局域网扫描。
- Python LAN 发送增加“设备 + 文件名 + SHA-256”成功指纹账本和并发占用；同一文件第二次发送会在创建手机任务前拒绝，避免手机产生第二份接收目录。
- 本轮已通过 Python 传输技能单测（27 项）和 Python 语法检查；缓存的 Gradle 已启动，但因缺少 Android SDK 未进入安卓测试；Windows 原生编译与真机传输验收仍需补验。

## USB 一键复制脚本 - 2026-08-14

- 新增 `scripts/copy-usb-apk.ps1`：自动寻找电脑端最新 APK、筛选已授权 USB Android 手机，并复制到手机的 `Download` 文件夹。
- 复制前读取手机上的同名文件 SHA-256；相同就跳过。复制采用临时文件、传输后校验、最后改名，避免留下不完整文件。
- 桌面提供 `相册_USB一键复制.cmd` 双击入口；脚本只复制文件，不调用 `adb install`，不修改手机应用版本和数据；多台手机时要求明确选择，避免误传。

## Windows V4.3.6 / Android 0.6.20 双通道更新候选 - 2026-08-14

- 手机启动和刷新作品库后，通过在线信标与 `/v2/info` 上报版本、版本码、总作品数、分类库存和更新能力。
- Windows 设备卡显示手机版本及精准/泛库存；版本信息缺失时不自动推送更新包。
- Windows 发送带版本号的 APK 后自动保存本地缓存；手机上线且版本较低时，自动复用现有 LAN V2 更新包投送链。
- 源码推送后由 GitHub Actions 云端构建并生成版本化 Android APK、SHA-256 和 `latest.json`；Windows 不再定时轮询公开索引。
- 仅在发现低版本手机在线且本地没有候选包时，Windows 按需取回已发布包作为 LAN V2 备用通道，电脑和手机使用同一版本。
- GitHub 仍是手机的首选更新通道，电脑局域网推送是 GitHub 不可用时的备用通道；手机端校验和用户确认安装边界不变。
- 本轮仍是源码候选，未宣称 Android 0.6.20 已发布或已完成 VIVO 真机升级验收。

## Android 0.6.19 immediate inventory beacon candidate - 2026-08-13

- After the phone refreshes its local library, the receiver requests one immediate status beacon; the regular beacon and Windows polling remain as fallbacks.
- Category-inventory-unknown protection, approval, threshold, retry, and de-duplication rules are unchanged.
- Source candidate only; not a public release. Complete Android tests, Release/Lint, and real-device verification before publishing.

## Android 0.6.18 旧版 Android 启动兼容候选 - 2026-08-13

- 修复 Android 10 在主界面恢复时拒绝普通后台 `startService`，导致 0.6.17 安装成功后立即崩溃、被误判为 APK 解析/安装失败的问题。
- 浮层刷新在 Android O+ 改走 `startForegroundService`，并将系统拒绝记录到诊断日志；不改包名、签名、作品数据或传输协议。
- 真实 Redmi Note 8（Android 10 / API 29）已覆盖安装并启动验证通过；0.6.18 尚未发布到公开更新通道。

## Windows V4.3.4 更新检测兜底 - 2026-08-08

- 实机手动检查遇到 GitHub 公共 API 限流后，自动改用公开 Release 最新页跳转读取版本标签，不再直接报错结束。
- 保持 V4.3.3 的独立设置中心、数据键和主界面收纳方式不变。

## Windows V4.3.3 独立设置中心 / iPhone 0.6.8 中文导航 - 2026-08-08

- Windows“设置”改为与主应用风格一致的独立小窗口，不再从右上角弹出临时菜单。
- 原始目录、归档目录、自动检测更新、手动检查更新、发送更新包、自动补货与阈值、开机自启、暗色模式、诊断日志和软件介绍统一收纳到设置中心。
- Windows 主界面删除重复的低频配置入口，只保留日常文件浏览、归档、发送和设备操作。
- iPhone 设置、回收站等二级页面的返回按钮统一为中文“返回”，版本升级到 0.6.8/build 27，应用标识和数据目录不变。

## Windows V4.3.2 通用设置 - 2026-08-07

- Windows 主窗口右上角新增“设置”入口，集中管理原始目录、GitHub 更新检查、补货与更新包设置及软件介绍。
- “素材库 / 发送根目录”统一提升为“文件库 / 原始目录（收发文件根目录）”；继续使用原有 `library_path` 键，旧配置无需迁移。
- 原始目录说明明确为通用文件收发目录，可发送和接收任意文件或文件夹，不再把产品用途限制为素材。
- 新增 GitHub Release 版本检查：支持手动检查和默认开启的 6 小时自动检查；发现新版本只打开公开发布页，由用户确认下载和替换。

## Android 0.6.16 / Windows V4.3.1 功能设置 - 2026-08-07

- Android 更新改回应用内 HTTPS 断点下载：下载完成后执行 SHA-256、APK 包名、版本号、版本码和签名校验，再由用户点击通知进入系统安装器，避免 MIUI/部分系统的 DownloadManager 失败。
- Android 接收端识别电脑投送的 APK 更新包：同样执行包名、版本码和签名校验，合格后进入应用私有更新目录并提示安装；普通素材接收路径不变。
- Windows 新增“功能设置”：可开启通用自动补货、设置低库存阈值（默认 7 个作品），按作品文件夹（帖子文本 + 图片）自动投送到低于阈值的在线设备。
- Windows 新增更新包投送入口，可把 APK/EXE/ZIP 发送到选中设备；Android APK 会进入校验安装流程，电脑端继续沿用现有 USB → Wi-Fi、进度、校验和完成反馈。
- Android 单元测试、Release 构建和 Release Lint 已通过；已用一台真实 Android 11 旧版手机完成 0.6.16 APK 的 LAN/V2 投送并收到 commit。Windows 真机/安装包仍需在有 Visual Studio 工具链后验收。

## Android 0.6.15 / iPhone 0.6.7 / Windows V4.2.1 - 2026-08-02

- 修复 Windows 将“发送到”下的子文件夹视为普通复制目的地、导致用户文件被误复制进系统 `SendTo` 目录的问题。
- 右键入口改为根目录单一快捷方式“发送到相册设备”；点击后由中控弹出在线设备选择菜单，再直接读取原文件或文件夹发送。
- 不建立长期备份；现有 ZIP/网络缓冲仍按传送任务结束清理。旧版自有子目录只清理标记文件和快捷方式，遇到未知内容绝不自动删除。
- 增加无预选设备的命令行解析、IPC 序列化回归测试；保留指定设备参数的向后兼容。
- 设备选择改在单实例 IPC 返回后异步弹出，避免用户选择期间第二进程 5 秒超时并误报失败。
- 右键入口改为纯发送：不再查询或拦截“是否传送过”，选择设备后立即显示主窗口进度条和状态，结束时弹出成功/失败结果。
- 右键发送增加不抢焦点的短文字提示：开始、传送状态和完成/失败结果会在屏幕右下角短暂显示，不保留常驻进度窗。
- 应用内选择设备后直接传送也使用同一组短文字提示，避免只有右键入口有反馈。
- 修复右键入口强制拉起主界面的问题：未运行时静默启动后台发现/接收进程，运行时保持原窗口状态，只显示设备选择菜单和短提示。
- “发送到相册设备”快捷方式不再随普通退出删除，右键可直接按需唤起隐藏后台实例；更新或卸载时由安装流程清理。
- 明确 Windows 自带“发送到 → 桌面”仍是本机复制；发送到手机/电脑设备应选择“发送到相册设备”。
- Android 接收端增加静默系统进度通知和完成结果通知；“声音提醒”只控制声音，不再把通知整体关闭。
- “设置素材目录”改为“设置发送根目录”，该目录同时作为电脑端发送浏览和接收落盘位置；左侧列表支持逐级展开文件夹并选择任意层级文件或文件夹发送。

## Android 0.6.14 / iPhone 0.6.7 / Windows V4.2.0 - 2026-08-02

- Windows 运行期间动态维护资源管理器“发送到 → 相册在线设备”子菜单，只显示当前可用的 USB/Wi‑Fi 设备。
- 增加默认局域网 `/24` 的原生并发 V2 探测兜底，解决路由器抑制 Wi‑Fi 广播回复时共享技能能找到手机、电脑版却显示空列表的问题。
- 右键选中的一个或多个文件、文件夹通过单实例 IPC 交给已打开的中控，直接复用现有传送进度、取消、通道选择、重复提醒和完成回执。
- 设备离线后自动移除菜单项；中控正常退出时清理自身菜单目录，不触碰用户的其他“发送到”快捷方式。
- 快捷方式参数使用编码后的设备 ID，文件路径仍由 Windows 原样传入；主进程会再次校验目标、路径、数量和在线状态。
- 新增参数编码、IPC 序列化和反序列化自动测试；右键入口不需要管理员权限或资源管理器扩展 DLL。

## Android 0.6.14 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 按用户确认恢复 Android 系统下载更新：打开应用自动检查，发现新版先提醒，点击“系统下载”后交由 DownloadManager 显示进度与完成通知。
- 用户从系统下载通知进入安装；相册不再声明 `REQUEST_INSTALL_PACKAGES`，也不再申请“允许来自此来源”。
- 删除应用自有 APK 下载服务、私有安装包、应用内安装 Activity 和主动拉起安装器的路径；后台文件传送保持不变。
- 系统下载完成后仍按任务 ID 与 SHA-256 后台核对，错误文件自动移除并在下次进入相册时提示。
- 更新包保持纯英文 `.apk` 文件名；本机 20 个测试套件共 66 项测试、Release 构建、Lint、清单、版本和 v2 签名检查通过。
- Android 传送页增加“自动 / USB / Wi‑Fi”选择并记忆。USB 模式提供系统 USB 网络共享入口；经 `rndis/usb/ncm/tether` 网卡发现的电脑显示为 `USB`。
- 手机→电脑的 USB 通道复用 V2 传送协议；普通 MTP 仍用于电脑→手机，不伪装成手机可主动访问的双向协议。

## Android 0.6.13 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 更新流程改为确认式：打开应用自动检查，发现新版先弹窗，用户点击后才开始下载。
- 下载完成并校验后不再强行启动可能被系统拦截的安装页，改为应用内“安装”按钮与常驻通知双入口。
- 安装提醒明确显示版本、纯英文 `.apk` 文件名和文件大小；点击“安装”后才交给 Android 系统安装器。
- 后台文件传送逻辑不受本次更新交互调整影响。
- 更新包改由相册自己的 HTTPS 下载服务获取，绕开 MIUI/迅雷下载内核访问 GitHub 时返回状态 700 的故障；下载进度常驻通知，网络中断保留 `.part` 并可续传。
- 下载仅接受 HTTPS 及有限 HTTPS 跳转；完成后严格核对 SHA-256、APK 解析、包名、版本号、版本码和签名，再保存为纯英文 `.apk`。
- 更新检查改读 GitHub Release API 和同一 Release 的校验清单，解决中国移动网络访问 `raw.githubusercontent.com` 连续超时。
- APK 签名校验同时读取 Android 新旧签名字段，兼容 Redmi/Android 10 能解析 v2 APK、但新版归档签名字段为空的厂商实现。
- 0.6.11 作为修复后的实体升级起点；0.6.12 真机下载已到校验阶段，最终版提升为 0.6.13。

## Android 0.6.8 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- Android 文件夹模式入口移到首页左侧，解决窄屏顶部操作挤在右侧的问题。
- 修复分类白色滑块覆盖文字，并校正内边距与四等分宽度；切换动画保留。
- 下拉刷新增加拉动、释放、加载中旋转进度和完成数量反馈。
- 设置开关改为应用独立绘制的完整 iOS 胶囊，统一绿色、灰色、白色滑块和过渡动画，不再受厂商 Switch 样式影响。
- 截图自动发送增加主设备选择行与在线状态；未选择目标时开启自动发送会立即弹出选择。
- APK 下载并验证通过后，应用在前台会自动打开系统安装页；后台场景继续使用完成通知作为系统兼容回退。
- Redmi Note 8 同签名覆盖安装、分类、下拉刷新、设置开关和主设备选择完成实体检查；Android 65 项单元测试、Release 构建与 Release Lint 通过。

## Android 0.6.7 / iPhone 0.6.7 / Windows V4.1.2 - 2026-08-01

- 双端首页删除顶部刷新按钮，作品和文件列表统一使用下拉刷新，减少重复入口；Android 回收站返回不受影响。

## Android 0.6.6 / iPhone 0.6.6 / Windows V4.1.2 - 2026-08-01

- 双端首页移除重复的“作品 + 总数”，分类数量成为唯一总量入口；iPhone 增加文件浏览入口和双箭头刷新图标。
- Android 统一为 iPhone 式浅灰、白色内容面和绿色主色，增加分类滑块、内容过渡、边缘阻尼回弹及下拉刷新。
- iPhone 设置重排，移除早期工作方式和导入入口，补齐前台剪切板同步、截图识别、自动发送、主设备选择与主设备接收，并修复长说明拥挤。
- 软件说明与双端交互规则同步更新；iOS 平台限制在界面内明确说明。

## Android 0.6.5 / iPhone 0.6.5 / Windows V4.1.2 - 2026-08-01

- iPhone 首页补齐四个分类筛选：全部、转化、泛流量、未分类，并显示各分类作品数量；修复筛选后点卡片可能打开未筛选列表中同位置作品的问题。
- iPhone 分类栏使用紧凑的系统分段控件，在窄屏上缩短标签但保持完整分类语义；总数徽标始终显示全部作品数。
- Android 自动整理改成两条紧凑设置行，点开选择立刻、1/3/6/24 小时或自定义 0～720 小时，选择后即时保存，不再长期占用设置页。
- Android 设置开关改成标题与说明分层显示：标题保持正常字号，说明使用更小、更浅的文字并按内容自动增高，修复窄屏和大字体下说明被截断的问题。
- iPhone 自动整理同步相同的常用预设、自定义范围和即时保存交互；彻底删除时间仍不能早于移入回收站时间。
- Redmi Note 8 已从正式 0.6.4 同签名覆盖到 0.6.5 候选版，作品、目录和设备名保留；分类、设置排版、预设选择、130% 系统字体与恢复默认值完成实体操作。
- 三端自动构建与协议闭环最终全部通过；正式安装包、源码和校验清单已发布，Android 更新索引已切换至 0.6.5/code 43，公网 APK 重新下载校验及覆盖安装通过。

## Android 0.6.4 / iPhone 0.5.3 / Windows V4.1.2 - 2026-07-31

- Android 悬浮剪切板首次改为可用屏幕宽高各 50%，默认位于状态栏下方并水平居中；保持拖动、缩放和位置尺寸记忆，点悬浮窗外部收起面板且不吞掉原应用点击。
- 悬浮球长按 5 秒的暂停期限统一为 1 分钟、30 分钟和永久关闭；临时期限到期自动恢复。
- 三端剪切板消息增加来源、消息 ID 和跳数，接收端按一小时窗口去重并转发；Android 自动写入系统剪切板，iPhone 只在前台读写，Windows 轮询本机系统剪切板。
- Android 新截图可自动发送到指定主设备；没有启用自动目标时保留待确认记录，进入应用再选择设备。发送端截图识别、自动发送和接收端允许接收分别独立控制。
- V2 任务增加兼容旧客户端的中转元数据。Android、iPhone、Windows 可在可信局域网或热点拓扑中暂存并转发，队列一小时过期，按消息 ID 去重和跳数限制防止循环。
- Android 更新下载统一使用 `album-Android-<version>.apk`。下载完成后校验 SHA-256、APK 解析、包名、版本号和签名，再提供安装；损坏或不匹配的包删除并重下。
- Android 0.6.4/code 42 已通过本机单元测试、Release 构建和 Release Lint；三端实体设备链路与覆盖安装结果以连接设备后的逐机记录为准。

## Android 0.6.3 - 2026-07-30

- 修复最新剪切内容过长时撑满悬浮窗口、导致固定常用语无法下滑到达的问题。
- 长剪切默认折叠为 3 行摘要并显示“展开”；展开后可查看完整内容，顶部按钮随时切换为“收起”。新剪切到达时自动恢复折叠，避免继承上一条的展开状态。
- 悬浮窗和普通剪切板页面都改为单一纵向滚动区域，最新剪切、固定常用语和新增入口按同一滚动链排列，不再使用互相争抢手势的嵌套滚动视图。
- 悬浮球长按 5 秒弹出关闭时长，可选 30 秒、5 分钟、1 天或永久关闭；临时关闭到期自动恢复，永久关闭后可从设置重新开启。

## Android 0.6.2 - 2026-07-30

- 修复同一手机离线后以原网络地址重新上线时，不会重新补齐最新剪切板和固定常用语的问题。
- 点击固定常用语后立即把它写成最新剪切并同步在线手机，不再等下次打开悬浮窗；剪切板物理存储也只保留最新一条，避免历史和墓碑长期堆积。
- 悬浮面板改为可获取焦点，并在窗口稳定后再读取剪切板，提高 Android 10+ 跨应用读取成功率；点右上角新增时先收起悬浮窗，避免遮住编辑页。
- 剪切板文件读写改为跨实例统一串行，修复接收、编辑与悬浮窗并发写入可能互相覆盖的问题；同一时间戳的多机冲突使用确定规则收敛到同一版本。
- 更新下载会识别正在下载和已经校验完成的同版本 APK，避免重复下载；覆盖安装成功后自动移除本 App 跟踪的旧安装包，失效任务允许重新下载。

## Android 0.6.1 - 2026-07-30

- 移除手机主界面左下角的重复剪切板按钮，只保留默认开启的系统悬浮圆点入口。
- 悬浮圆点支持在整块屏幕内横向、纵向自由拖动，首次默认位于屏幕上方约四分之一处，并记住位置。
- 点击圆点直接打开带边框的悬浮剪切板，不再强制跳转普通页面；悬浮窗可拖动，右下角可拉伸大小，并持久保存位置和尺寸。界面顶部只显示最新一条剪切内容，下面显示固定常用语，右上角可新增。
- 从电脑端正式《前端私聊承接与拉群 SOP》写入 8 条当前推荐前端话术；只在缺失时初始化，用户修改或删除后不会被升级反复覆盖。
- 剪切内容只向当前在线手机同步最新一条，不灌入整套历史；固定常用语持续同步全部增删改，设备重新上线后自动补齐当前版本。

## Android 0.6.0 - 2026-07-29

- Android 系统分享面板新增“相册”目标，可把其他应用中的图片、文字和文件直接发到同一 Wi‑Fi 下的在线设备。
- 区分“普通作品/文件传送”和“图片＋文字直接分享”：普通传送始终按真实文件夹落盘，不再误触发发布分享；只有系统分享进来的图片＋文字会进入分享准备。
- 原“流量转化”入口升级为共享剪切板：左侧剪切板记录、右侧常用语，同一组已发现手机自动同步新增、修改和删除；悬浮“贴”按钮默认开启，可在设置关闭。
- 新增截图发送提醒，可指定主设备；新截图出现后以不遮挡操作的系统通知询问，点“发送”后显示发送进度和结果。
- 顶部分类显示“全部、转化帖、发流量帖、未分类”及各自数量；接收的平铺内容统一整理进真实文件夹，继续以用户授权文件夹为准自动对账。
- 保留每次打开自动检查并下载更新、明确 `.apk` 文件名、SHA-256 校验和系统安装确认；手机版不加入坚果云。

## Android 0.5.10 - 2026-07-29

- 修复部分 Android / HarmonyOS 手机通过系统下载更新后文件名没有 `.apk` 后缀的问题；现在明确保存为 `相册-Android-版本号.apk`，无需手动改名。
- 保留每次打开自动检查、发现新版自动下载、SHA-256 校验和系统安装确认流程。

## Android 0.5.9 - 2026-07-29

- 手机作品页新增“全部、转化帖、泛流量帖、未分类”四个顶部筛选，默认显示全部；作品按 `[转]`、`[泛]` 目录自动归类。
- 修复 HarmonyOS / EMUI 文件索引失效导致整库显示 `FileNotFoundException`、长按删除失效和回收站无法清空的问题；单条坏索引不再中断扫描，并以真实 Lark 目录兜底。
- 清空回收站会同时移除外部原文件夹和 App 记录，并兼容华为删除图片时即时生成的 `.hwbk` 备份文件。
- 已分享作品显示分享次数与自动删除倒计时；默认 1 小时，支持立刻、1/3/6/24 小时预设及 0～720 小时自定义。
- 每次启动可自动检查并下载更新；接收文件使用持久进度通知，完成时显示文件数和耗时，失败详情沉淀到操作记录。
- 首页增加“流量转化”入口，连接电脑端转化助手以浏览 SOP 和复制话术；坚果云同步继续只由电脑端负责，不进入手机版设置。
- 华为 P30（Android 10 / HarmonyOS 4）完成覆盖安装、27 个作品读取恢复、分类、长按删除、真实目录移动及回收站清空验证。

## Windows V4.1.1 - 2026-07-27

- 修复已发现设备 15 秒后被移除、点击刷新又先清空列表的回归；刷新改为保留已知设备并立即发送探测。
- 将局域网设备保留窗口延长到 10 分钟，兼容 Android/iOS 进入后台后广播被系统节流的情况。
- 单窗口比例改为素材区约 38%、设备区约 62%，设备卡片重新成为主操作区域。
- 标题说明重新明确“把任意文件或文件夹拖到设备卡片”，原拖放、设备备注、作品数、通道开关和传送能力均保留。

## Windows V4.1.0 - 2026-07-27

- 把素材库、素材目录、归档目录、在线设备和传送状态合并进同一个主窗口，移除独立素材库弹窗。
- 主界面改为清晰的左右工作流：左侧选择作品，右侧选择设备；“传送选中素材”成为唯一绿色主操作。
- 保留任意文件或文件夹传送、拖放、设备刷新、取消、诊断和安全归档能力；系统目录选择器与归档确认按需出现。
- 增加最小窗口尺寸，避免缩小时素材操作和设备区域互相挤压或被裁切。

## Windows V4.0.0 - 2026-07-27

- Windows 中控新增独立“素材库与安全归档”窗口，可设置任意素材目录、读取一级作品文件夹、刷新列表，并把选中项传给主窗口当前设备。
- 成功传送记录由 TSV 升级为 Windows 自带 SQLite 数据库；旧 TSV 自动且只迁移一次，保留原文件，并保留初始化失败时的旧记录降级路径。
- 数据库新增内容、传送、设置、归档事件与 `ready/archive_ready/archived` 状态；重复提醒继续沿用原内容指纹，不因改名而失效。
- 新增“已使用并归档”：压缩后检查 ZIP 结构、条目数和 SHA-256，拒绝覆盖同名包；成功后将原文件夹移入 Windows 回收站，不做不可恢复删除。
- Windows CI 新增 SQLite 迁移、幂等导入、记录查询、设置读写与归档状态测试，并把测试失败纳入 Windows 构建失败条件。
- 远程 HTTP 闭环对本地 R2 下载启动瞬态 500 增加有限重试；协议、类型检查和真实 Worker/DO/R2 闭环仍必须全部通过。

## 未发布 - 远程传送服务基础

- 新增独立 `remote-relay/` Cloudflare Worker：使用 Durable Object 保存设备组与真实在线会话，R2 只保存短期密文。
- 工作区 ID 与管理电脑公钥绑定；成员使用电脑签发凭证和设备签名挑战登录，伪造管理电脑不能替换根身份。
- 增加远程开关、设备撤销、会话立即失效、WebSocket 在线通知、密文上传/提交/下载/取消/确认和最长 24 小时过期清理。
- 中继包升级到 0.1.1，增加 HTTPS 在线心跳、收件箱/发件箱轮询和结构化请求日志；移动端即使不能长期保持 WebSocket，也能上报真实在线状态并拉取已提交任务。
- 云端清单不含文件名、用户路径、明文或明文密钥；接收成功 ACK 后立即删除密文。
- 协议测试增至 10 项，覆盖在线心跳与收发箱可见性；Cloudflare API 类型检查、依赖高危审计和 Wrangler 部署预检通过。第二备用 CI run `30266289195` 已实际启动 0.1.1 Worker，并跑通 Durable Object、R2 密文上传/下载与 ACK 删除；同一 run 的 Windows、Android、iPhone 构建全部成功。
- 同一 CI run 的 Windows、Android、iPhone 原有构建全部成功；本机 Cloudflare 运行时仍因 Windows `workerd` 访问冲突无法启动，Linux CI 是当前集成运行证据。
- 本阶段未部署云端、未接入三端客户端、未完成异网真机传送，正式版继续隐藏“远程”标签。

## Windows V3.9.1 - 2026-07-26

- 修复同一台 Redmi K60 因 WiFi 上报硬件型号、USB/MTP 上报市场名称而显示为两张卡的问题；合并后保留电脑备注和作品数，并在右侧同时显示 WiFi、USB。
- 同步兼容 Redmi 9A、Redmi 13 已知硬件型号别名；不恢复“只有一台安卓就猜测合并”的危险逻辑。
- 设备卡右侧改为独立通道标签，严格按 USB → WiFi → 远程排列，只显示当前真实可用的路线；未部署的远程服务不会显示。
- 增加设备通道诊断状态，记录 USB 可写、WiFi 地址与远程会话是否真实连通，便于处理多手机、MTP 名称差异和高 DPI 界面问题。
- 在 Redmi K60 实机完成 Windows → 手机 USB 和 WiFi 两条路线落盘测试；接收文件均位于 `Download/Lark`，电脑与手机 SHA-256 一致，测试文件验收后已删除。

## Android 0.5.8 / iPhone 0.5.2 / Windows V3.9 - 2026-07-26

- Windows 设备卡片改用安卓机器人与手机轮廓标识；电脑备注优先显示，下一行保留手机名称和原始型号。
- 卡片只显示当前真实可用的 USB、WiFi、远程通道，不再重复显示“在线”；传送期间显示对应通道和进度。
- 设备右键菜单新增“传送方式”，可分别允许或关闭 USB、WiFi、远程传送；设备首次登记默认允许，手动关闭后长期记住。
- 当前发送自动按 USB → WiFi 选择最快可用通道；后续远程服务接通后再追加 P2P 直连与加密中继兜底。
- Android 与 iPhone 在同一局域网发现电脑后持久记录电脑身份，为远程公钥登记与设备撤销保留升级入口。
- Android 与 iPhone 的设备选择列表显示 WiFi 通道，发送进度明确标注“WiFi 传送中/完成”；远程接通后同一位置显示“远程直连”或“远程中继”。
- 首次自动登记时，电脑提示设备已登记，手机提示“电脑已确认传送权限”；通道开关、传送中、完成与撤销均使用一致的短文字状态。

## Android 0.5.7 / Windows V3.8 - 2026-07-26

- Windows 新增内容指纹和按设备成功传送历史；重复拖入时显示上次时间并默认跳过，允许明确重传。
- Windows 新增 Android MTP 与 iPhone 文件共享 USB 通道；USB 可写时优先，失败自动回到 Wi‑Fi。只连接充电/调试接口时会显示待切换文件传输。
- 只检测到充电/调试接口时不再显示底层驱动名，统一使用“安卓手机”和“切换为文件传输”的中文提示。
- 无法从 USB 接口确认真实设备名时不再猜测并合并到唯一的 Wi‑Fi 安卓设备，避免多手机环境下把 USB 状态或发送目标关联错设备。
- 同一台 Android 开启 MTP 后，按 USB 厂商和设备硬件标识合并可传文件接口与底层接口，避免一台手机显示两张卡。
- 优先读取 USB 总线提供的真实机型名；Windows 设备路径格式不一致时仍可用真实名称将 MTP 与底层接口合并。
- 过滤 Windows 设备管理器残留的无名称且不可打开的便携设备条目；真实 MTP 打开失败时写入不含设备标识的诊断原因。
- 部分 MIUI 同时返回“有名称的底层 USB”和“无名称但可写的 MTP”；仅在两边都唯一时配对为一张真实设备卡，多设备或多匿名接口时不猜测。
- Android 以所选外部目录为来源真相，清除文件管理器已删除但私有库仍保留的幽灵作品和回收项；网络接收且无外部来源的内容不受影响。
- 厂商文件服务单次空列表不触发删除，缺失来源必须经过延时二次确认。
- 红米 K60 正式包覆盖到 0.5.7/code34，现有授权和数据保留；真实目录与首页均为 24 个作品，私有幽灵回收项从 2 个归零。

## Android 0.5.6 / iPhone 0.5.1 - 2026-07-26

- Android 的作品分发与文件浏览改为同一首页内原地切换：冻结顶栏、传送、刷新、回收站和设置位置不变，只替换下方内容；返回键先退出文件夹层级或切回作品，不再像进入另一套页面。
- 顶部标题、数量、圆形按钮和间距重新压缩；模式、传送、刷新、回收站和设置点击后增加简短文字反馈，兼顾纯图标的简洁与可理解性。
- 文件浏览改用接近系统文件管理器的文件夹、图片、文档、PDF、压缩包、视频、音频和普通文件图标；ZIP 使用“文档 + 拉链”造型，不再与垃圾桶混淆。
- Android 与 iPhone 将旧版自然日记录迁移为北京时间下的精确时间：昨天及更早的旧记录立即按当前规则整理；当天旧记录从升级时起保留完整 1 小时，避免猜测分享时刻。
- iPhone 的自动移入回收站与彻底删除统一为独立 1～10 小时设置，默认均为 1 小时；前台每分钟检查，重新打开应用立即检查。旧回收站中没有状态记录的文件夹从升级时起保留 1 小时后再删除。
- 保持 Android 包名、签名、iPhone 状态文件和目录授权兼容；覆盖升级不重置设备名称、作品、分享次数或所选文件夹。

## Android 0.5.5 - 2026-07-26

- “复制并分享”改为点击后立即、且只记一次，不再依赖不同厂商不稳定的系统分享目标回调；已经发起过的作品再次分享前会明确提醒。
- 修复 Android 10 同一隐藏作品被 SAF 与旧存储兼容通道分别导入的问题；升级时以“文案 + 图片内容指纹”识别当前列表与回收站中的同一作品，并清理应用私有副本内的重复图片，不修改用户原作品。
- 首页新增“小红书笔记 / 文件浏览”模式切换；文件模式可浏览已授权根目录、进入子文件夹、查看普通文件并交给系统打开。
- 默认在首次分享 1 小时后进入回收站并从文件管理中彻底删除；设置可分别填写 1～10 小时，彻底删除时间不得早于回收时间。
- MediaStore 分享副本、接收暂存和传送压缩临时文件统一在生成 1 小时后清理，不触碰作品原文件。
- 保持 `com.zwm.gallery`、原签名和原数据结构覆盖升级；旧作品不会因新清理规则追溯删除。

## Android 0.5.4 - 2026-07-23

- 修复部分 HarmonyOS / EMUI 系统选择器先返回页面、后送达目标应用回调时，图片已经分享但作品次数没有增加的问题；增加短暂回调宽限，不影响 VIVO 分身冷启动等待逻辑。
- 作品卡片从 190dp 收紧为 174dp，减小内边距和卡片间距；四角从明显投影改为 1dp 柔和阴影加暖灰细描边，已分享与多选状态继续保持清晰。
- 重写设置中的“软件说明”，按核心场景、作品工作流、跨设备传送和设计思路介绍当前完整能力，不再以权限限制作为产品介绍主体。
- 保持 `com.zwm.gallery`、原签名和现有数据结构，可直接覆盖升级；目录授权、作品、分享次数和回收站不会因本次升级重置。

## Android 0.5.3 - 2026-07-23

- 撤掉 `REQUEST_INSTALL_PACKAGES`，相册不再申请“安装其他应用”能力，也不再直接打开 APK 安装器。
- 设置里检查到新版后交给 Android 系统下载；下载完成时点系统通知，再由系统确认安装，不跳 GitHub 页面。
- 系统下载完成后仍按发布索引核对 SHA-256；校验不一致会删除下载文件，并在下次打开相册时提示重新下载。
- CI 新增正式 APK 权限回归：只要重新带入安装包请求权限或 Debug 标志，构建即失败。
- 继续沿用 `com.zwm.gallery`、原签名和数据结构，可覆盖升级并保留目录授权、作品、分享次数和回收站。

## Android 0.5.2 - 2026-07-23

- 修正 0.5.1 顶部作品数字被弹性标题区域推远的问题，数字现在紧跟在“作品”右侧。
- 传送、刷新、回收站和设置保留 48dp 点击范围，视觉改为真正圆形，并为每个圆形入口增加一致间距，减少拥挤感。
- 继续沿用同一包名、签名和数据结构，可覆盖升级并保留现有作品状态和回收站。

## Android 0.5.1 - 2026-07-23

- 顶部冻结工具栏增加 Android 15+ 状态栏、导航栏和水滴/刘海安全区适配；横屏时同时避开左右挖孔区域。
- 传送、刷新、回收站和设置扩大为 48dp 点击区域，并重新平衡标题、数字和顶部留白，兼顾 Redmi 9A 等较窄屏幕。
- 正式交付改为同签名 Release APK，关闭 `debuggable`；包名、签名和数据结构不变，可覆盖升级并保留目录授权、作品、分享次数和回收站。
- 本机已完成单元测试、Release 编译、Release Lint、APK 签名与清单核对；水滴屏实体手感和厂商安全提示等待连接真机复核。

## 0.5.0 / Windows V3.7 - 2026-07-23

- 手机作品卡片增加文件夹式内容预览：文案单独显示，图片按原作品常用 3:4 比例两列排列；TXT、ZIP、JSON、PDF 等其他文件显示文件名、类型和大小，并可交给系统预览。
- 多选后使用垃圾桶、三点连线分享、小飞机三个图标，可将所选图片移入作品内图片回收站、分享到其他应用或传送到同网络设备；图片回收站保留 7 天并支持恢复。
- Android 分身分享等待系统媒体写入完成，并识别 VIVO 分身冷启动后分享目标提前返回的情况；未真正打开目标时不增加分享次数。
- iPhone 图片缩略图和大图改为按设备尺寸降采样，降低 iPhone 6 打开多图作品时的内存压力；同一 IPA 继续兼容 iOS 12 与新系统。
- Windows 使用手机端同款应用图标，设备名前增加 Android / iPhone 标识，统一圆角设备卡和操作按钮，压缩底部状态区并继续隐藏本机设备。
- Android、iPhone 和 Windows 均保持原包名/签名/数据结构覆盖升级，作品、目录授权、分享次数和现有回收站不重置。

## Android 0.4.9 - 2026-07-22

- 修复部分旧 Android / MIUI 在 Wi‑Fi 短暂中断后，HTTP 接收仍正常但 UDP 设备发现线程永久退出的问题。
- 设备发现遇到临时网络错误后自动重建，并记录“正在恢复 / 已恢复”诊断状态，不再要求重启应用。
- 新增发现循环恢复单元测试；保持原包名、原签名和覆盖升级数据兼容。

## Android 0.4.8 - 2026-07-20

- 长按作品进入批量选择模式，所有作品卡片显示复选框，可继续勾选多个作品；右下角垃圾桶一次移动全部选中项。
- 顶部标题实时显示“已选 N 个”；取消最后一项或按返回键退出多选，不触发分享或删除。
- 批量移动逐项执行，成功项进入现有回收站，失败项保持选中并显示成功/失败数量，不用一次失败回滚已经安全完成的作品。

## 0.4.7 / Windows V3.6 - 2026-07-20

- Android 10+ 分享图片改走系统 MediaStore 临时分享区，并对可见分享目标补充显式读取授权，改善小米、VIVO 应用分身无法打开或卡住的问题；Android 8/9 保留原私有分享通道。
- 临时媒体副本只为跨空间分享使用，原图、作品状态和回收站不变；副本在下一自然日启动分享时清理。
- Android 首页顶部工具栏固定，不再随作品列表滚动；长按作品可选中并通过右下角垃圾桶移入现有回收站，真实文件夹移动失败时不伪装成功。
- Windows、Android、iPhone 和共享技能统一把已知数量显示为“设备名（作品数 25）”；旧客户端数量未知时只显示原设备名。
- Android 增加覆盖升级数据回归，验证作品、分享次数和回收站记录在同包名、同签名原地升级后保留。

## 0.4.6 / Windows V3.5 - 2026-07-20

- Android 与 iPhone 把当前扫描得到的作品数量加入局域网设备状态；只公布整数，不读取或发送作品名称、文案、图片和路径。
- Windows 每台手机卡片在在线状态后显示作品数，例如“在线 · 15”；旧版手机继续正常显示在线，但不显示未知数字。
- Android 与 iPhone 的互传设备列表同步显示对方作品数。
- 共享技能新增作品数查询，可回答“每台手机现在有多少作品”，也可按设备名称单独查询。
- 扩展发现协议和 `/v2/info`，保持旧客户端向后兼容。

## 0.4.5 - 2026-07-20

- 修复接收重名目录后生成的“相册回收站 (1)/(2)”被当成作品库继续递归扫描，造成旧作品重复显示的问题。
- Android 与 iPhone 使用同一判定规则：只排除真实回收站及系统生成的数字重名副本，不误伤“相册回收站教程”等普通文件夹。
- iPhone 6 实体安装继续保持 iOS 12 兼容，设置页保留 App 内“选择作品文件夹”。

## 0.4.4 - 2026-07-20

- Android 更新改为 App 内直接下载 APK，增加进度、取消、SHA-256 校验、安装权限引导和清晰诊断，不再跳转发布网页。
- iPhone 更新检查不再跳转网页，并明确侧载版需要电脑重新签名覆盖。
- iOS 12 增加 App 内“选择作品文件夹”，可从相册接收目录及其子目录中选择递归扫描根目录；iOS 13+ 保留系统外部文件夹选择器。
- 同一 Android 包和同一 IPA 继续覆盖 Redmi、Huawei、iPhone 6 和较新 iPhone，不按机型分叉。

## 0.4.3 - 2026-07-19

- iOS 12 固定作品库增加文件/ZIP 导入，支持标准 Deflate ZIP。
- Android 与 iPhone 增加默认关闭的声音通知和震动开关。
# Android 0.6.17 - 2026-08-10

- `/v2/info` 新增向后兼容的 `workCounts` 聚合分类库存；保留原 `workCount` 总数。
- 分类统计复用作品库现有 `category`，覆盖刷新、导入、接收提交和自动清理后的更新路径。
- 新增分类统计单元测试；旧客户端没有分类字段时保持“未知”，不将总数冒充分类数。
