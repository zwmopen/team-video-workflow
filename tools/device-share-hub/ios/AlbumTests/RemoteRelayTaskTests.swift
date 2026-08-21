import XCTest
@testable import Album

final class RemoteRelayTaskTests: XCTestCase {
    func testParsesReadyTaskForCurrentDevice() throws {
        let task = makeTask()
        let parsed = try RemoteRelayTask.parse(
            task, expectedRecipientId: "device_phone_1", nowMs: 1_000
        )

        XCTAssertEqual(parsed.transferId, "transfer_123456")
        XCTAssertEqual(parsed.objectCount, 1)
        XCTAssertEqual(parsed.totalCipherBytes, 12)
        XCTAssertFalse(parsed.expired(at: 1_000))
        XCTAssertTrue(parsed.expired(at: 2_000))
    }

    func testRejectsWrongRecipientDuplicateIndexAndBadHash() {
        XCTAssertThrowsError(try RemoteRelayTask.parse(
            makeTask(recipient: "device_other_1"),
            expectedRecipientId: "device_phone_1", nowMs: 1_000
        ))

        var duplicate = makeTask()
        duplicate["objects"] = [
            object(index: 0, hash: String(repeating: "a", count: 64)),
            object(index: 0, hash: String(repeating: "b", count: 64))
        ]
        duplicate["totalCipherBytes"] = 24
        XCTAssertThrowsError(try RemoteRelayTask.parse(
            duplicate, expectedRecipientId: "device_phone_1", nowMs: 1_000
        ))

        var badHash = makeTask()
        badHash["objects"] = [object(index: 0, hash: "not-a-hash")]
        XCTAssertThrowsError(try RemoteRelayTask.parse(
            badHash, expectedRecipientId: "device_phone_1", nowMs: 1_000
        ))
    }

    func testRejectsUploadingTask() {
        XCTAssertThrowsError(try RemoteRelayTask.parse(
            makeTask(status: "uploading"),
            expectedRecipientId: "device_phone_1", nowMs: 1_000
        ))
    }

    func testParsesPlainPublicObjectMetadata() throws {
        var task = makeTask()
        task["mode"] = "plain"
        task["totalBytes"] = 12
        task.removeValue(forKey: "totalCipherBytes")
        task["objects"] = [
            ["index": 0, "bytes": 12,
             "sha256": String(repeating: "a", count: 64),
             "name": "album-folder-作品集[泛].zip", "mime": "application/zip"]
        ]
        let parsed = try RemoteRelayTask.parse(task, expectedRecipientId: "device_phone_1", nowMs: 1_000)
        XCTAssertEqual(parsed.mode, "plain")
        XCTAssertEqual(parsed.objects.first?.name, "album-folder-作品集[泛].zip")
        XCTAssertEqual(parsed.objects.first?.bytes, 12)
    }

    private func makeTask(sender: String = "device_sender_1",
                          recipient: String = "device_phone_1",
                          status: String = "ready") -> [String: Any] {
        [
            "transferId": "transfer_123456",
            "senderDeviceId": sender,
            "recipientDeviceId": recipient,
            "status": status,
            "expiresAt": 2_000,
            "totalCipherBytes": 12,
            "objects": [object(index: 0, hash: String(repeating: "a", count: 64))]
        ]
    }

    private func object(index: Int, hash: String) -> [String: Any] {
        ["index": index, "cipherBytes": 12, "cipherSha256": hash]
    }
}
