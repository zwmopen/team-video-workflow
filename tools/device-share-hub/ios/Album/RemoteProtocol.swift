import Foundation
import Security

enum RemoteProtocolError: LocalizedError {
    case unsupportedValue
    case invalidSignature
    case invalidKey

    var errorDescription: String? {
        switch self {
        case .unsupportedValue: return "远程身份数据格式不受支持。"
        case .invalidSignature: return "远程身份签名格式无效。"
        case .invalidKey: return "远程身份公钥格式无效。"
        }
    }
}

enum RemoteProtocol {
    static func canonicalJSON(_ value: Any) throws -> String {
        if value is NSNull { return "null" }
        if let value = value as? String { return jsonString(value) }
        if let value = value as? Bool { return value ? "true" : "false" }
        if let value = value as? NSNumber { return value.stringValue }
        if let value = value as? [String: Any] {
            let pieces = try value.keys.sorted().map { key in
                guard let item = value[key] else { throw RemoteProtocolError.unsupportedValue }
                return "\(jsonString(key)):\(try canonicalJSON(item))"
            }
            return "{\(pieces.joined(separator: ","))}"
        }
        if let value = value as? [Any] {
            return "[\(try value.map { try canonicalJSON($0) }.joined(separator: ","))]"
        }
        throw RemoteProtocolError.unsupportedValue
    }

    static func canonicalData(_ value: Any) throws -> Data {
        Data(try canonicalJSON(value).utf8)
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func publicJWK(_ publicKey: SecKey) throws -> [String: String] {
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?, raw.count == 65,
              raw.first == 0x04 else { throw RemoteProtocolError.invalidKey }
        return [
            "kty": "EC",
            "crv": "P-256",
            "x": base64URL(raw.subdata(in: 1..<33)),
            "y": base64URL(raw.subdata(in: 33..<65))
        ]
    }

    static func derSignatureToRaw(_ der: Data) throws -> Data {
        let bytes = [UInt8](der)
        var index = 0
        guard readByte(bytes, &index) == 0x30 else { throw RemoteProtocolError.invalidSignature }
        let sequenceLength = try readLength(bytes, &index)
        guard sequenceLength == bytes.count - index else { throw RemoteProtocolError.invalidSignature }
        let r = try readInteger(bytes, &index)
        let s = try readInteger(bytes, &index)
        guard index == bytes.count else { throw RemoteProtocolError.invalidSignature }
        return Data(normalizeInteger(r) + normalizeInteger(s))
    }

    private static func jsonString(_ value: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: [value], options: [])
        let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? "\"\""
        return String(text.dropFirst().dropLast())
    }

    private static func readByte(_ bytes: [UInt8], _ index: inout Int) -> UInt8? {
        guard index < bytes.count else { return nil }
        defer { index += 1 }
        return bytes[index]
    }

    private static func readLength(_ bytes: [UInt8], _ index: inout Int) throws -> Int {
        guard let first = readByte(bytes, &index) else { throw RemoteProtocolError.invalidSignature }
        if first & 0x80 == 0 { return Int(first) }
        let count = Int(first & 0x7f)
        guard count > 0, count <= 2 else { throw RemoteProtocolError.invalidSignature }
        var result = 0
        for _ in 0..<count {
            guard let value = readByte(bytes, &index) else { throw RemoteProtocolError.invalidSignature }
            result = (result << 8) | Int(value)
        }
        return result
    }

    private static func readInteger(_ bytes: [UInt8], _ index: inout Int) throws -> [UInt8] {
        guard readByte(bytes, &index) == 0x02 else { throw RemoteProtocolError.invalidSignature }
        let length = try readLength(bytes, &index)
        guard length > 0, index + length <= bytes.count else { throw RemoteProtocolError.invalidSignature }
        defer { index += length }
        return Array(bytes[index..<(index + length)])
    }

    private static func normalizeInteger(_ value: [UInt8]) -> [UInt8] {
        var value = value
        while value.count > 1, value.first == 0 { value.removeFirst() }
        if value.count >= 32 { return Array(value.suffix(32)) }
        return Array(repeating: 0, count: 32 - value.count) + value
    }
}
