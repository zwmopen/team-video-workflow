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
        return result as! SecKey
    }
}
