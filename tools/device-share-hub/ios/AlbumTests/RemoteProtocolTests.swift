import Foundation
import Security
import XCTest
@testable import Album

final class RemoteProtocolTests: XCTestCase {
    func testCanonicalJSONSortsObjectsAndKeepsArrays() throws {
        let value: [String: Any] = [
            "z": 1,
            "a": ["b": true, "a": "x"],
            "items": [2, 1]
        ]
        XCTAssertEqual(
            try RemoteProtocol.canonicalJSON(value),
            "{\"a\":{\"a\":\"x\",\"b\":true},\"items\":[2,1],\"z\":1}"
        )
    }

    func testDERSignatureBecomesRaw64ByteSignature() throws {
        let key = try XCTUnwrap(SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256
        ] as CFDictionary, nil))
        let data = Data([1, 2, 3])
        let signature = try XCTUnwrap(SecKeyCreateSignature(
            key,
            .ecdsaSignatureMessageX962SHA256,
            data as CFData,
            nil
        ) as Data?)
        XCTAssertEqual(try RemoteProtocol.derSignatureToRaw(signature).count, 64)
    }
}
