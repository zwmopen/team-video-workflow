import Foundation

enum LiveEnginePreference: String, CaseIterable {
    case zhipuFirst = "zhipu_first"
    case localOnly = "local_only"
    case flowFirst = "flow_first"

    enum Route { case local, zhipu, flow }
    var title: String {
        switch self {
        case .zhipuFirst: return "智谱优先 · 待接入"
        case .localOnly: return "仅本地"
        case .flowFirst: return "Google Flow · 待接入"
        }
    }
    static var preferred: Self {
        get {
            let raw = UserDefaults.standard.string(forKey: "live.preferred_engine.v1") ?? ""
            return Self(rawValue: raw) ?? .zhipuFirst
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "live.preferred_engine.v1") }
    }
    static var summary: String { preferred.title + " / 当前本地" }

    func resolve(aiRequested: Bool, uploadAuthorized: Bool, zhipuReady: Bool, flowReady: Bool) -> Route {
        guard aiRequested && uploadAuthorized else { return .local }
        if self == .zhipuFirst && zhipuReady { return .zhipu }
        if self == .flowFirst && flowReady { return .flow }
        return .local
    }
}
