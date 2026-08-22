import XCTest
@testable import Album

final class RemoteRelayClientTests: XCTestCase {
    func testOnlyHTTPSRelayEndpointsAreAccepted() throws {
        XCTAssertEqual(
            try RemoteRelayClient.normalizeEndpoint("https://relay.example///"),
            "https://relay.example"
        )
    }

    func testLocalHTTPAndEmptyEndpointsAreRejected() {
        for endpoint in ["", "http://relay.example", "relay.example",
                         "https://relay.example/v1", "https://relay.example?workspace=one"] {
            XCTAssertThrowsError(try RemoteRelayClient.normalizeEndpoint(endpoint))
        }
    }
}
