import Foundation

/// Re-authenticates and publishes relay presence only after a profile was enrolled.
final class RemoteRelayPresence {
    private let queue = DispatchQueue(label: "com.zwm.album.remote-presence")
    private var timer: DispatchSourceTimer?
    private var session: RemoteRelayClient.Session?
    private var profileKey = ""
    private var retryAfter: TimeInterval = 0
    private var failureDelay: TimeInterval = 0

    func start() {
        queue.async { [weak self] in
            guard let self = self, self.timer == nil else { return }
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now(), repeating: .seconds(10), leeway: .seconds(2))
            timer.setEventHandler { [weak self] in self?.tick() }
            timer.resume()
            self.timer = timer
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.timer?.cancel()
            self?.timer = nil
            self?.session = nil
        }
    }

    private func tick() {
        guard let profile = RemoteRelayProfile.load() else {
            session = nil
            profileKey = ""
            retryAfter = 0
            failureDelay = 0
            return
        }
        let nextProfileKey = "\(profile.endpoint)\n\(profile.certificateSignature)\n"
            + (profile.certificate["deviceId"] as? String ?? "")
        if nextProfileKey != profileKey {
            profileKey = nextProfileKey
            session = nil
            retryAfter = 0
            failureDelay = 0
        }
        let now = Date().timeIntervalSince1970
        if now < retryAfter { return }
        do {
            var current = session
            if current == nil || current!.expired
                || current!.endpoint != profile.endpoint
                || current!.deviceId != (profile.certificate["deviceId"] as? String ?? "") {
                current = try RemoteRelayClient.createSession(
                    endpoint: profile.endpoint,
                    certificate: profile.certificate,
                    certificateSignature: profile.certificateSignature
                )
                session = current
            }
            if let current = current { try RemoteRelayClient.heartbeat(current) }
            retryAfter = 0
            failureDelay = 0
        } catch {
            session = nil
            failureDelay = min(5 * 60, failureDelay <= 0 ? 10 : failureDelay * 2)
            retryAfter = now + failureDelay
        }
    }
}
