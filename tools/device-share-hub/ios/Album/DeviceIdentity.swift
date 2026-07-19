import Foundation
import UIKit

enum DeviceIdentity {
    private static let idKey = "album.deviceId.v1"
    private static let nameKey = "album.deviceName.v1"

    static var id: String {
        if let stored = UserDefaults.standard.string(forKey: idKey), !stored.isEmpty { return stored }
        let created = "ios-\(UUID().uuidString.lowercased())"
        UserDefaults.standard.set(created, forKey: idKey)
        return created
    }

    static var name: String {
        let stored = UserDefaults.standard.string(forKey: nameKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return stored?.isEmpty == false ? stored! : "我的 iPhone"
    }

    static func saveName(_ value: String) throws {
        let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { throw DeviceIdentityError.emptyName }
        guard cleaned.count <= 30 else { throw DeviceIdentityError.nameTooLong }
        UserDefaults.standard.set(cleaned, forKey: nameKey)
    }

    static var model: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let identifier = mirror.children.reduce(into: "") { result, child in
            guard let value = child.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
        return identifier.isEmpty ? UIDevice.current.model : identifier
    }
}

enum DeviceIdentityError: LocalizedError {
    case emptyName
    case nameTooLong

    var errorDescription: String? {
        switch self {
        case .emptyName: return "手机名称不能为空。"
        case .nameTooLong: return "手机名称最多 30 个字。"
        }
    }
}
