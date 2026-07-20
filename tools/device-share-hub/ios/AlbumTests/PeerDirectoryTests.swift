import XCTest
@testable import Album

final class PeerDirectoryTests: XCTestCase {
    func testReadsOptionalWorkCountFromExtendedBeacon() {
        let id = "peer-count-\(UUID().uuidString)"
        let packet = ["ZWMDS2_HERE", "2", id, "45833", encoded("测试手机"),
                      encoded("iPhone"), encoded("online"), "", "15"].joined(separator: "|")

        XCTAssertTrue(PeerDirectory.shared.remember(packet: packet, host: "127.0.0.1"))
        XCTAssertEqual(PeerDirectory.shared.peers().first(where: { $0.id == id })?.workCount, 15)
    }

    func testOldBeaconKeepsCountUnknown() {
        let id = "peer-old-\(UUID().uuidString)"
        let packet = ["ZWMDS2_HERE", "2", id, "45833", encoded("旧手机"),
                      encoded("iPhone"), encoded("online"), ""].joined(separator: "|")

        XCTAssertTrue(PeerDirectory.shared.remember(packet: packet, host: "127.0.0.1"))
        XCTAssertEqual(PeerDirectory.shared.peers().first(where: { $0.id == id })?.workCount, -1)
    }

    private func encoded(_ value: String) -> String {
        return Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
