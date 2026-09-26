import Foundation
import Security

enum RemoteIdentity {
    private static let signingTag = Data("com.zwm.album.remote.signing.v1".utf8)
    private static let agreementTag = Data("com.zwm.album.remote.agreement.v1".utf8)

    static func ensure() throws {
        _ = try privateKey(for: signingTag)
        _ = try privateKey(for: agreementTag)
    }

    static func publicKeys() throws -> [String: Any] {
        let signing = try publicKey(for: signingTag)
        let agreement = try publicKey(for: agreementTag)
        return [
            "signingPublicKey": try RemoteProtocol.publicJWK(signing),
            "agreementPublicKey": try RemoteProtocol.publicJWK(agreement)
        ]
    }

    static func sign(_ value: Any) throws -> String {
        let key = try privateKey(for: signingTag)
        var error: Unmanaged<CFError>?
        let data = try RemoteProtocol.canonicalData(value)
        guard let signature = SecKeyCreateSignature(
            key,
            .ecdsaSignatureMessageX962SHA256,
            data as CFData,
            &error
        ) as Data? else {
            throw (error?.takeRetainedValue() as Error?) ?? RemoteProtocolError.invalidSignature
        }
        return RemoteProtocol.base64URL(try RemoteProtocol.derSignatureToRaw(signature))
    }

    private static func privateKey(for tag: Data) throws -> SecKey {
        if let existing = loadKey(tag: tag, privateOnly: true) { return existing }
        var error: Unmanaged<CFError>?
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag
            ]
        ]
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw (error?.takeRetainedValue() as Error?) ?? RemoteProtocolError.invalidKey
        }
        return key
    }

    private static func publicKey(for tag: Data) throws -> SecKey {
        guard let key = SecKeyCopyPublicKey(try privateKey(for: tag)) else {
            throw RemoteProtocolError.invalidKey
        }
        return key
    }

    private static func loadKey(tag: Data, privateOnly: Bool) -> SecKey? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: tag,
            kSecReturnRef as String: true
        ]
        if privateOnly { query[kSecAttrKeyClass as String] = kSecAttrKeyClassPrivate }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        // 【DSH-118】此处原本是 `result as! SecKey` 强制解包：
        // SecItemCopyMatching 返回成功只代表**查到了匹配项**，不代表它一定是 SecKey
        // （设备上若存在同 applicationTag 的证书 / 通用密码项就会命中别的类型），
        // 此时 as! 直接崩溃，而且只在特定机型 + 特定历史数据下才复现，极难排查。
        // 改成先确认 CF 类型再转换：类型不对就当没取到，走重新生成密钥的分支。
        guard let key = result else { return nil }
        guard CFGetTypeID(key) == SecKeyGetTypeID() else {
            print("[RemoteIdentity] 钥匙串同 tag 的项不是 SecKey（typeID=\(CFGetTypeID(key))），忽略")
            return nil
        }
        // 上面已确认过 CF 类型，这里的强转不会再有崩溃风险
        return (key as! SecKey)
    }
}
