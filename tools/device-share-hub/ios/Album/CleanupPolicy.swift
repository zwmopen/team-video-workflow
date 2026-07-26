import Foundation

enum CleanupPreferences {
    static let moveHoursKey = "album.cleanup.moveHours.v1"
    static let deleteHoursKey = "album.cleanup.deleteHours.v1"
    static let defaultHours = 1
    static let maximumHours = 10

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            moveHoursKey: defaultHours,
            deleteHoursKey: defaultHours
        ])
    }

    static var moveHours: Int {
        return clamped(UserDefaults.standard.integer(forKey: moveHoursKey))
    }

    static var deleteHours: Int {
        return max(moveHours, clamped(UserDefaults.standard.integer(forKey: deleteHoursKey)))
    }

    @discardableResult
    static func save(moveHours: Int, deleteHours: Int) -> Bool {
        guard (1...maximumHours).contains(moveHours),
              (moveHours...maximumHours).contains(deleteHours) else { return false }
        UserDefaults.standard.set(moveHours, forKey: moveHoursKey)
        UserDefaults.standard.set(deleteHours, forKey: deleteHoursKey)
        return true
    }

    private static func clamped(_ value: Int) -> Int {
        return min(maximumHours, max(1, value))
    }
}

enum CleanupPolicy {
    static let beijingTimeZone = TimeZone(identifier: "Asia/Shanghai")!

    static func isDue(anchorMilliseconds: Double?, hours: Int, now: Date) -> Bool {
        guard let anchor = anchorMilliseconds, anchor > 0 else { return false }
        return now.timeIntervalSince1970 * 1000 >= anchor + Double(hours) * 3_600_000
    }

    static func legacyAnchor(dateText: String, now: Date) -> Double? {
        guard let legacyDate = dayFormatter.date(from: dateText) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = beijingTimeZone
        let today = calendar.startOfDay(for: now)
        let anchor = legacyDate < today ? legacyDate : now
        return anchor.timeIntervalSince1970 * 1000
    }

    static var dayFormatter: DateFormatter {
        let value = DateFormatter()
        value.calendar = Calendar(identifier: .gregorian)
        value.locale = Locale(identifier: "en_US_POSIX")
        value.timeZone = beijingTimeZone
        value.dateFormat = "yyyy-MM-dd"
        return value
    }
}
