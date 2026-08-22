import XCTest
@testable import Album

final class RemoteRelayProfileTests: XCTestCase {
    func testInvalidRelayProfileIsRejected() {
        XCTAssertThrowsError(try RemoteRelayProfile.save(
            endpoint: "http://relay.example",
            certificate: [:],
            certificateSignature: "signature"
        ))
    }
}
