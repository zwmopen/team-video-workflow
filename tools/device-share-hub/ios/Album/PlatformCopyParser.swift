import Foundation

/// 平台标识。`rawValue` 同时是在线相册回调里使用的平台 code
/// （与 Android `PlatformCopyParser.Platform.code` 逐字一致）。
enum CopyPlatform: String, CaseIterable {
    case douyin
    case xhs
    case xhs2
    case xhs3
    case wechat
    case hr
    case general

    var displayName: String {
        switch self {
        case .douyin: return "规避营销版"
        case .xhs: return "种草版"
        case .xhs2: return "大纲方案版"
        case .xhs3: return "短文精选版"
        case .wechat: return "公众号版"
        case .hr: return "HR决策版"
        case .general: return "参考文案"
        }
    }

    /// 与 Android `Platform.shortLabel` 对齐。
    var shortLabel: String { displayName }

    /// 固定的 `<<<MARKER_START>>>` 标记名，与 Android `Platform.marker` 逐字一致。
    var marker: String {
        switch self {
        case .douyin: return "DOUYIN"
        case .xhs: return "XHS"
        case .xhs2: return "XHS_2"
        case .xhs3: return "XHS_3"
        case .wechat: return "WECHAT"
        case .hr: return "HR"
        case .general: return "GENERAL"
        }
    }

    /// 与 Android `Platform.code` 一致。
    var code: String { rawValue }

    var startMarker: String { "<<<\(marker)_START>>>" }
    var endMarker: String { "<<<\(marker)_END>>>" }
}

enum PlatformCopyStatus: Equatable {
    case ok
    case missing
    case unreadable
}

struct PlatformCopyResult: Equatable {
    let status: PlatformCopyStatus
    let text: String

    var isOK: Bool { status == .ok }
}

struct AvailableCopyPlatform: Equatable {
    let platform: CopyPlatform
    let buttonLabel: String
    let copyText: String
}

/// 文案协议解析器。
///
/// **本文件与 Android `PlatformCopyParser.java` 保持 1:1 行为对齐**，覆盖三种协议：
/// 1. `<<<COPY_FORMAT:MULTI>>>` + `<<<VERSION_START:四字版本名>>>…<<<VERSION_END>>>`
///    —— V4.5 全系多版本（11 版）走这条，**每个版本出一个按钮**；
/// 2. `<<<COPY_FORMAT:2/3>>>` + `<<<XHS_START>>>` / `<<<DOUYIN_START>>>` / `<<<XHS_2_START>>>` 等固定标记；
/// 3. 任意自定义标记 `<<<名字_START>>>…<<<名字_END>>>`。
///
/// 历史 bug（2026-09-21 修复）：此前只认识 `COPY_FORMAT:2/3` 与 3 个固定平台，
/// 遇到 MULTI 多版本文案会判定「不是协议格式」，落进兜底分支返回
/// **一个「发布」按钮 + 含全部 `<<<VERSION_START:…>>>` 标记的整段原文**，
/// 表现为「点文案按钮只有一段乱码、没有其他版本按钮」。
enum PlatformCopyParser {
    static let headerV2 = "<<<COPY_FORMAT:2>>>"
    static let headerV3 = "<<<COPY_FORMAT:3>>>"
    static let headerMulti = "<<<COPY_FORMAT:MULTI>>>"
    static let versionStartPrefix = "<<<VERSION_START:"

    /// 多版本块：`<<<VERSION_START:四字版本名>>>…<<<VERSION_END>>>`
    private static let versionBlockRegex = try? NSRegularExpression(
        pattern: "<<<VERSION_START:\\s*([^>\\r\\n]+?)\\s*>>>[\\r\\n]*(.*?)[\\r\\n]*<<<VERSION_END>>>",
        options: [.dotMatchesLineSeparators]
    )

    /// 动态标记块：`<<<任意标记_START>>>…<<<同名标记_END>>>`。
    /// 标记名用 `[^<>\r\n]+`（标记本身不可能含尖括号），
    /// 语义等价于 Android 的 `[A-Za-z0-9_\u4e00-\u9fa5]+` 且天然支持中文标记名。
    private static let genericBlockRegex = try? NSRegularExpression(
        pattern: "<<<([^<>\\r\\n]+)_START>>>[\\r\\n]*(.*?)[\\r\\n]*<<<\\1_END>>>",
        options: [.dotMatchesLineSeparators]
    )

    /// 匹配任意协议标记。刻意写成 `&lt;&lt;+[^&lt;&gt;]*&gt;&gt;+`（开头 2+ 个 `&lt;`、结尾 2+ 个 `&gt;`）：
    /// 磁盘上确实存在**畸形标记**（Codex 产线把 `&lt;&lt;&lt;DOUYIN_END&gt;&gt;&gt;` 写成 `&lt;&lt;&lt;DOUYIN_END&gt;&gt;`，
    /// 实测 9 份），要求正好三个 `&gt;` 的正则识别不了。与 Android `ANY_MARKER_PATTERN` 对齐。
    private static let markerRegex = try? NSRegularExpression(pattern: "<<+[^<>]*>>+")

    /// 已知固定平台，动态标记扫描时要跳过，避免同一块被重复出按钮。
    private static let knownMarkers: Set<String> = ["DOUYIN", "XHS", "XHS_2", "XHS_3", "WECHAT", "HR"]

    /// 空壳作品判定用的「实质文本」：剥掉全部 `<<<…>>>` 协议标记与空白（含 U+2800 盲文空格）。
    ///
    /// **与 Android `MainActivity.copySubstance` 逐字对齐**：正则同为 `<<+[^<>]*>>+`，
    /// 连**畸形标记**（如实测 9 份里只有两个 `>` 的 `<<<DOUYIN_END>>`）也一并剥掉。
    /// 取证（2026-09-21）：全库 642 份 `文案.txt` 用新旧正则各算一次实质字数，
    /// 跨过 30 字判线的 = **0 份** ⇒ 收紧正则不误伤。
    static func copySubstance(_ text: String?) -> String {
        guard let text else { return "" }
        let noMarkers = text.replacingOccurrences(of: "<<+[^<>]*>>+", with: "",
                                                 options: .regularExpression)
        return noMarkers.replacingOccurrences(of: "[\\s\\u{2800}]", with: "",
                                             options: .regularExpression)
    }

    /// 空壳作品：实质字数不足 30 字即视为文案缺失（阈值与 Android 一致）。
    static func isCopySubstanceMissing(_ text: String?) -> Bool {
        copySubstance(text).utf16.count < 30
    }

    static func parseAvailablePlatforms(_ source: String?) -> [AvailableCopyPlatform] {
        guard let raw = source else { return [] }
        let text = stripBOM(raw)
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }

        let isProtocol = text.contains(headerV2) || text.contains(headerV3)
            || text.contains(headerMulti) || text.contains(versionStartPrefix)
            || text.contains("_START>>>")
        guard isProtocol else {
            // 旧版纯文案（无任何标记）→ 单个「复制文案」按钮。
            // 【2026-09-21 DSH-087】兜底也必须剥净 `<<<…>>>`：畸形标记
            // （如只有两个 `>` 的 `<<<X_END>>`）会让 `isProtocol` 判false，
            // 原文连同畸形标记一起进剪贴板。与 Android 同函数同位置对齐。
            return [AvailableCopyPlatform(platform: .xhs,
                                          buttonLabel: "发布",
                                          copyText: strippingProtocolMarkers(text)
                                              .trimmingCharacters(in: .whitespacesAndNewlines))]
        }

        // ---- 优先级 1：多版本语法，每个版本独立出一个按钮 ----
        var multiItems: [AvailableCopyPlatform] = []
        for (name, body) in versionBlocks(in: text) {
            // 【2026-09-21 DSH-087】块正文里可能残留畸形/嵌套标记（实测 9 份），
            // 取正文后再净化一道，与 Android `parseAvailablePlatforms` 对齐。
            let content = strippingProtocolMarkers(stripOuterLineBreaks(body))
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            let label = friendlyLabelForMarker(name)
            let platform: CopyPlatform = (label.contains("抖音") || label == "抖音避坑") ? .douyin : .general
            multiItems.append(AvailableCopyPlatform(platform: platform, buttonLabel: label, copyText: content))
        }
        if !multiItems.isEmpty { return multiItems }

        // ---- 优先级 2：固定平台标记 ----
        var items: [AvailableCopyPlatform] = []

        if let douyinRes = okText(text, platform: .douyin) {
            items.append(AvailableCopyPlatform(platform: .douyin, buttonLabel: "规避营销版", copyText: douyinRes))
        }
        if let xhsRes = okText(text, platform: .xhs) {
            items.append(AvailableCopyPlatform(platform: .xhs, buttonLabel: "种草版", copyText: xhsRes))
        }
        if let xhs2Res = okText(text, platform: .xhs2) {
            items.append(AvailableCopyPlatform(platform: .xhs2, buttonLabel: "大纲方案版", copyText: xhs2Res))
        }
        if let xhs3Res = okText(text, platform: .xhs3) {
            items.append(AvailableCopyPlatform(platform: .xhs3, buttonLabel: CopyPlatform.xhs3.displayName, copyText: xhs3Res))
        }
        if let wechatRes = okText(text, platform: .wechat) {
            items.append(AvailableCopyPlatform(platform: .wechat, buttonLabel: CopyPlatform.wechat.displayName, copyText: wechatRes))
        }
        if let hrRes = okText(text, platform: .hr) {
            items.append(AvailableCopyPlatform(platform: .hr, buttonLabel: CopyPlatform.hr.displayName, copyText: hrRes))
        }

        // ---- 优先级 3：任意自定义标记 ----
        for (marker, body) in genericBlocks(in: text) {
            let upper = marker.uppercased()
            if knownMarkers.contains(upper) { continue }
            // 【2026-09-21 DSH-087】同出口2，自定义标记块正文也要净化。
            let content = strippingProtocolMarkers(stripOuterLineBreaks(body))
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
            let label = friendlyLabelForMarker(marker)
            items.append(AvailableCopyPlatform(platform: .general, buttonLabel: label, copyText: content))
        }

        if items.isEmpty {
            // 【2026-09-21 DSH-087】Codex「伪协议」文案（有 `<<<COPY_FORMAT:3>>>` 头、
            // 正文用 `【小红书自然种草版】` 分节、无任何 `<<<XHS_START>>>`）会落到这里，
            // 原先直接 `text.trimmed` ⇒ 头标记进剪贴板（实测 46 份命中）。
            return [AvailableCopyPlatform(platform: .xhs,
                                          buttonLabel: "发布",
                                          copyText: strippingProtocolMarkers(text)
                                              .trimmingCharacters(in: .whitespacesAndNewlines))]
        }
        return items
    }

    static func parse(_ source: String?, platform: CopyPlatform) -> PlatformCopyResult {
        guard var value = source else { return PlatformCopyResult(status: .unreadable, text: "") }
        if value.first == "\u{FEFF}" { value.removeFirst() }
        // 【2026-09-21 修复】此前只要不含 `COPY_FORMAT:2/3` 头、也不含该平台固定标记，
        // 就把 `value` **整篇原文**当结果返回。V4.5 多版本（`<<<COPY_FORMAT:MULTI>>>`）
        // 正是这种形态 ⇒ 剪贴板里是含全部 `<<<VERSION_START:…>>>` 标记的整份原文
        // （与 Android `PlatformCopyParser.parse()` 同源缺陷）。
        // 现在只有「完全不含任何协议标记」的旧版纯文案才原样返回。
        let matchesPlatformBlock = value.contains(headerV2) || value.contains(headerV3)
            || value.contains(platform.startMarker)
        if !matchesPlatformBlock && !PlatformCopyParser.containsProtocolMarker(value) {
            return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? PlatformCopyResult(status: .unreadable, text: "")
                : PlatformCopyResult(status: .ok, text: value)
        }

        guard let start = value.range(of: platform.startMarker) else {
            // 兼容多版本语法：当按平台查找时，在 <<<VERSION_START:…>>> 块中智能匹配对应的风格版本
            let vblocks = versionBlocks(in: value)
            if !vblocks.isEmpty {
                for (vname, body) in vblocks {
                    let content = strippingProtocolMarkers(stripOuterLineBreaks(body))
                    guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
                    if matchesPlatformName(platform: platform, vname: vname) {
                        return PlatformCopyResult(status: .ok, text: content)
                    }
                }
                if platform == .general, let first = vblocks.first {
                    let content = strippingProtocolMarkers(stripOuterLineBreaks(first.1))
                    return PlatformCopyResult(status: .ok, text: content)
                }
            }

            let hasOther = CopyPlatform.allCases.contains { other in
                other != platform
                    && (value.contains(other.startMarker) || value.contains(other.endMarker))
            }
            if !hasOther && vblocks.isEmpty {
                return PlatformCopyResult(status: .unreadable, text: "")
            }
            return PlatformCopyResult(status: .missing, text: "")
        }
        let afterStart = start.upperBound
        guard let end = value.range(of: platform.endMarker, range: afterStart..<value.endIndex) else {
            return PlatformCopyResult(status: .unreadable, text: "")
        }
        let content = value[afterStart..<end.lowerBound]
            .trimmingCharacters(in: CharacterSet(charactersIn: "\r\n"))
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return PlatformCopyResult(status: .unreadable, text: "")
        }
        // 【2026-09-21 修复】磁盘上的标记可能畸形（如 `<<<DOUYIN_END>>` 只有两个 `>`），
        // 会被块正则当成正文吞进来；取出的正文再剥一道标记，剪贴板里永远不出现 `<<<…>>`。
        return PlatformCopyResult(status: .ok, text: PlatformCopyParser.strippingProtocolMarkers(String(content)))
    }

    /// 文本里是否含**任意** `<<<…>>>` 协议标记。
    ///
    /// 用来区分「旧版纯文案」（完全无标记，可按平台原样分发）与「协议文本但缺少该平台块」
    /// ——后者必须报缺失，绝不能把整篇原文塞进剪贴板。
    static func containsProtocolMarker(_ source: String) -> Bool {
        guard let re = markerRegex else { return false }
        return re.firstMatch(in: source, options: [],
                             range: NSRange(source.startIndex..<source.endIndex, in: source)) != nil
    }

    /// 剥掉全部 `<<<…>>>` 协议标记并收紧首尾换行（与 Android `stripProtocolMarkers` 对齐）。
    static func strippingProtocolMarkers(_ source: String) -> String {
        guard let re = markerRegex else { return source }
        var clean = re.stringByReplacingMatches(
            in: source, options: [],
            range: NSRange(source.startIndex..<source.endIndex, in: source), withTemplate: "")
        while let last = clean.last, last == "\r" || last == "\n" { clean.removeLast() }
        return clean
    }

    static func extractPlatformCopy(_ source: String?, platform: CopyPlatform) -> String {
        let result = parse(source, platform: platform)
        return result.isOK ? result.text : ""
    }

    /// 版本名 → 按钮短标签（与 Android `friendlyLabelForMarker` 对齐）。
    static func friendlyLabelForMarker(_ marker: String?) -> String {
        guard let raw = marker, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "参考文案"
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let upper = trimmed.uppercased()
        if upper == "XHS_3" { return "短文精选版" }
        if upper == "WECHAT" { return "公众号版" }
        if upper == "HR" { return "HR决策版" }
        if upper.hasPrefix("VERSION_") || upper.hasPrefix("V_"), let idx = upper.firstIndex(of: "_") {
            return "版本 " + String(upper[upper.index(after: idx)...])
        }
        // 【2026-09-23 用户口径】版本名**不再截断到 4 字**：按钮按完整名字显示
        // （最长为「抖音无营销」5 字），一行放不下时由 `PlatformFlowView` 自动折到下一行。
        // 与 Android `PlatformCopyParser.friendlyLabelForMarker` 对齐。
        return trimmed
    }

    // MARK: - 内部工具

    private static func okText(_ text: String, platform: CopyPlatform) -> String? {
        let result = parse(text, platform: platform)
        return result.isOK ? result.text : nil
    }

    private static func versionBlocks(in text: String) -> [(String, String)] {
        matches(in: text, regex: versionBlockRegex)
    }

    private static func genericBlocks(in text: String) -> [(String, String)] {
        matches(in: text, regex: genericBlockRegex)
    }

    /// 取正则的第 1、2 个捕获组（标记名 / 正文）。
    private static func matches(in text: String, regex: NSRegularExpression?) -> [(String, String)] {
        guard let regex else { return [] }
        let nsText = text as NSString
        let results = regex.matches(in: text, range: NSRange(location: 0, length: nsText.length))
        return results.compactMap { match in
            guard match.numberOfRanges >= 3,
                  let nameRange = Range(match.range(at: 1), in: text),
                  let bodyRange = Range(match.range(at: 2), in: text) else { return nil }
            return (String(text[nameRange]), String(text[bodyRange]))
        }
    }

    private static func stripBOM(_ value: String) -> String {
        value.first == "\u{FEFF}" ? String(value.dropFirst()) : value
    }

    private static func stripOuterLineBreaks(_ value: String) -> String {
        var sub = Substring(value)
        while let first = sub.first, first == "\r" || first == "\n" { sub = sub.dropFirst() }
        while let last = sub.last, last == "\r" || last == "\n" { sub = sub.dropLast() }
        return String(sub)
    }

    private static func matchesPlatformName(platform: CopyPlatform, vname: String) -> Bool {
        let lower = vname.lowercased()
        switch platform {
        case .douyin:
            return vname.contains("抖音") || vname.contains("避坑") || vname.contains("规避营销")
        case .xhs2:
            return vname.contains("大纲") || vname.contains("方案")
        case .xhs:
            return vname.contains("种草") || vname.contains("小红书") || vname.contains("原生")
        case .hr:
            return lower.contains("hr") || vname.contains("决策") || vname.contains("备用") || vname.contains("方案")
        case .wechat:
            return vname.contains("公众号") || vname.contains("微信")
        case .xhs3:
            return vname.contains("短文") || vname.contains("精选")
        case .general:
            return false
        }
    }

    /// DSH-142: 平台与版本别名族（跨端与多版本完全对齐）
    static let aliasFamilies: [Set<String>] = [
        // HR / 备用 / 方案族
        ["hr", "hr决策版", "决策版", "备用", "备用岗", "备用文案", "备用方案", "方案", "方案版"],
        // 小红书种草族
        ["xhs", "种草版", "种草", "小红书", "红书", "红书种草", "自然种草版", "小红书自然种草版", "发布"],
        // 小红书大纲方案族
        ["xhs2", "xhs_2", "大纲方案版", "红书大纲", "大纲版", "方案大纲"],
        // 抖音无营销族
        ["douyin", "规避营销版", "抖音", "抖音无营销", "抖音避坑", "无营销版", "避坑版"],
        // 微信公众号族
        ["wechat", "公众号版", "公众号", "微信", "微信公众号"],
        // 短文精选族
        ["xhs3", "xhs_3", "短文精选版", "短文版", "精选版"]
    ]

    /// 判断两个版本标签或别名是否等价
    static func isVersionTagMatched(_ a: String, _ b: String) -> Bool {
        let cleanA = a.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let cleanB = b.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if cleanA.isEmpty || cleanB.isEmpty { return false }
        if cleanA == cleanB { return true }
        if cleanA.count >= 2 && cleanB.count >= 2 {
            if cleanA.contains(cleanB) || cleanB.contains(cleanA) { return true }
        }
        for family in aliasFamilies {
            let inA = family.contains(where: { cleanA == $0 || (cleanA.count >= 2 && cleanA.contains($0)) })
            let inB = family.contains(where: { cleanB == $0 || (cleanB.count >= 2 && cleanB.contains($0)) })
            if inA && inB { return true }
        }
        return false
    }

    /// 从 dispatchedTo 记录字符串（如 "Google Pixel 7 (备用岗 @ 2026-09-30 14:06:16)"）中提取版本名
    static func extractVersionFromRecord(_ record: String) -> String {
        // 服务端写入格式固定为: "{device_name} ({version_tag} @ {now})"
        // 设备名本身可能包含括号（如 "红米13(微信) 1号"），因此从后向前查找最后一对括号
        var closeRange = record.range(of: ")", options: .backwards)
        var openRange: Range<String.Index>? = nil
        if let close = closeRange {
            openRange = record.range(of: "(", options: .backwards, range: record.startIndex..<close.lowerBound)
        }
        if openRange == nil || closeRange == nil {
            // 兼容中文全角括号 "（" 和 "）"
            if let closeZh = record.range(of: "）", options: .backwards) {
                closeRange = closeZh
                openRange = record.range(of: "（", options: .backwards, range: record.startIndex..<closeZh.lowerBound)
            }
        }
        guard let openParen = openRange, let closeParen = closeRange, openParen.upperBound <= closeParen.lowerBound else {
            return ""
        }
        let inside = String(record[openParen.upperBound..<closeParen.lowerBound])
        if let atIdx = inside.range(of: "@") {
            return String(inside[..<atIdx.lowerBound]).trimmingCharacters(in: .whitespaces)
        }
        return inside.trimmingCharacters(in: .whitespaces)
    }

    /// DSH-142: 全局对齐的判断某按钮是否已分发（支持多端别名模糊匹配）
    static func isPlatformOrVersionDispatched(
        buttonLabel: String,
        platformCode: String?,
        localDispatched: Set<String>,
        dispatchedVersions: [String],
        dispatchedTo: [String]
    ) -> Bool {
        // 1. 本地分发记录
        for local in localDispatched {
            if isVersionTagMatched(buttonLabel, local) { return true }
            if let code = platformCode, isVersionTagMatched(code, local) { return true }
        }
        // 2. 服务端返回的 dispatchedVersions
        for ver in dispatchedVersions {
            if isVersionTagMatched(buttonLabel, ver) { return true }
            if let code = platformCode, isVersionTagMatched(code, ver) { return true }
        }
        // 3. 服务端返回的 dispatchedTo（如 "设备名 (版本 @ 时间)"）
        for tag in dispatchedTo {
            let extracted = extractVersionFromRecord(tag)
            if !extracted.isEmpty {
                if isVersionTagMatched(buttonLabel, extracted) { return true }
                if let code = platformCode, isVersionTagMatched(code, extracted) { return true }
            } else {
                // 仅在未能解析出结构化版本时，才作为历史无括号脏数据做全字符串兜底匹配（防止设备名包含关键字导致误判）
                if tag.contains(buttonLabel) { return true }
                if let code = platformCode, tag.contains(code) { return true }
            }
        }
        return false
    }
}
