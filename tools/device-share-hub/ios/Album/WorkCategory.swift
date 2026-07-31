import Foundation

enum WorkCategory {
    static let all = "all"
    static let conversion = "conversion"
    static let traffic = "traffic"
    static let uncategorized = "uncategorized"

    /// 根据作品路径判断分类，逻辑与 Android WorkCategory 一致。
    /// 路径包含 [转] 或 【转】→ 转化帖；包含 [泛] 或 【泛】→ 泛流量帖；其余 → 未分类。
    static func from(path: String) -> String {
        if path.contains("[转]") || path.contains("【转】") { return conversion }
        if path.contains("[泛]") || path.contains("【泛】") { return traffic }
        return uncategorized
    }

    /// 所有可选分类，用于筛选栏。
    static let filters: [(id: String, label: String)] = [
        (all, "全部"),
        (conversion, "转化帖"),
        (traffic, "泛流量帖"),
        (uncategorized, "未分类")
    ]
}
