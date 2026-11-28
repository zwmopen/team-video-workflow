import Foundation

public struct AlbumWorkLabels: Hashable {
    private static let dimensions = [
        ("generationEngine", "出图引擎"), ("productionChannel", "制作渠道"),
        ("seasons", "季节属性"), ("flowType", "流量定位"),
        ("contentType", "内容类型"), ("applicableMonths", "适用月份")
    ]
    private static let files = ["metadata.json", "manifest.json", "作品标签.json", "source-provenance.json"]
    private let groups: [String: [String]]
    private let producedAt: String?

    public init(projection: [String: Any] = [:]) {
        var collected: [String: [String]] = [:]
        for (key, _) in Self.dimensions {
            collected[key] = Self.values(projection[key], key: key)
        }
        groups = collected
        producedAt = Self.values(projection["producedAt"], key: "producedAt").first
    }

    static func read(folder: URL) -> AlbumWorkLabels {
        var projection: [String: Any] = [:]
        for name in files {
            let file = folder.appendingPathComponent(name)
            guard let size = (try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                  size <= 512 * 1024, let data = try? Data(contentsOf: file),
                  let doc = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            let canonical = doc["canonical"] as? [String: Any] ?? [:]
            let production = canonical["production"] as? [String: Any] ?? [:]
            let classification = canonical["classification"] as? [String: Any] ?? [:]
            let alternatives: [String: [Any?]] = [
                "generationEngine": [production["engineId"], production["generationEngine"], doc["generationEngine"]],
                "productionChannel": [production["channelId"], production["productionChannel"], doc["productionChannel"]],
                "seasons": [classification["seasons"], classification["season"], doc["seasons"], doc["season"]],
                "applicableMonths": [classification["applicableMonths"], doc["applicableMonths"]],
                "flowType": [classification["flowType"], doc["flowType"]],
                "contentType": [classification["contentType"], doc["contentType"]],
                "producedAt": [production["generatedAt"], production["producedAt"], doc["producedAt"],
                               doc["completedAt"], doc["completedAtUtc"]]
            ]
            for (key, candidates) in alternatives where projection[key] == nil {
                if let value = candidates.compactMap({ $0 }).first(where: { !values($0, key: key).isEmpty }) {
                    projection[key] = value
                }
            }
        }
        return AlbumWorkLabels(projection: projection)
    }

    private static func values(_ raw: Any?, key: String) -> [String] {
        guard let raw = raw else { return [] }
        let input = raw as? [Any] ?? [raw]
        var result: [String] = []
        for item in input {
            if key == "applicableMonths" {
                let text = (item as? String ?? String(describing: item)).replacingOccurrences(of: "月", with: "")
                if let month = Int(text), (1...12).contains(month) {
                    let label = "\(month)月"
                    if !result.contains(label) { result.append(label) }
                }
                continue
            }
            guard let text = item as? String else { continue }
            var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !value.isEmpty, value.count <= 80, !value.contains("://"), !value.contains("="),
                  value.rangeOfCharacter(from: .newlines) == nil,
                  !value.lowercased().hasPrefix("sk-"), !value.lowercased().hasPrefix("bearer ") else { continue }
            if key == "producedAt", value.range(of: #"^\d{4}-\d{2}-\d{2}($|[T ])"#, options: .regularExpression) == nil { continue }
            if key == "generationEngine" {
                value = ["magpie-http": "Magpie", "trueapi-image-direct": "True API",
                         "codex-native-imagegen": "Codex 原生"][value] ?? value
            }
            if key == "productionChannel", value == "Codex native" { value = "Codex" }
            if key == "flowType" {
                value = ["精准流量团建": "精准流量", "泛流量游戏攻略": "泛流量"][value] ?? value
            }
            if !result.contains(value) { result.append(value) }
        }
        return result
    }

    var core: [String] { Self.dimensions.flatMap { groups[$0.0] ?? [] } }

    func detail(imageCount: Int) -> String {
        var lines = Self.dimensions.map { key, title -> String in
            let values = groups[key] ?? []
            return title + "　" + (values.isEmpty ? "未分类" : values.joined(separator: "、"))
        }
        if let date = producedAt { lines.append("制作时间　" + date) }
        lines.append("图片数量　\(imageCount)")
        return lines.joined(separator: "\n\n")
    }
}
