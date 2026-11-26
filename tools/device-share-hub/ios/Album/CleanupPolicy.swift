import Foundation

enum CleanupPreferences {
    static let moveHoursKey = "album.cleanup.moveHours.v1"
    static let deleteHoursKey = "album.cleanup.deleteHours.v1"
    static let migrationKey = "album.cleanup.manualDefaultsMigrated.v1"
    static let manual = -1
    static let defaultHours = manual
    static let maximumHours = 720

    static func registerDefaults() {
        let defaults = UserDefaults.standard
        if !defaults.bool(forKey: migrationKey) {
            defaults.set(manual, forKey: moveHoursKey)
            defaults.set(manual, forKey: deleteHoursKey)
            defaults.set(true, forKey: migrationKey)
        }
        defaults.register(defaults: [
            moveHoursKey: defaultHours,
            deleteHoursKey: defaultHours
        ])
    }

    static var moveHours: Int {
        return clamped(UserDefaults.standard.integer(forKey: moveHoursKey))
    }

    static var deleteHours: Int {
        let move = moveHours
        let delete = clamped(UserDefaults.standard.integer(forKey: deleteHoursKey))
        return move >= 0 && delete >= 0 ? max(move, delete) : delete
    }

    @discardableResult
    static func save(moveHours: Int, deleteHours: Int) -> Bool {
        guard isAllowed(moveHours), isAllowed(deleteHours),
              !(moveHours >= 0 && deleteHours >= 0 && deleteHours < moveHours) else { return false }
        UserDefaults.standard.set(moveHours, forKey: moveHoursKey)
        UserDefaults.standard.set(deleteHours, forKey: deleteHoursKey)
        return true
    }

    private static func clamped(_ value: Int) -> Int {
        if value == manual { return manual }
        return min(maximumHours, max(0, value))
    }

    private static func isAllowed(_ value: Int) -> Bool {
        return value == manual || (0...maximumHours).contains(value)
    }
}

enum CleanupPolicy {
    static let beijingTimeZone = TimeZone(identifier: "Asia/Shanghai")!

    static func isDue(anchorMilliseconds: Double?, hours: Int, now: Date) -> Bool {
        guard hours >= 0, let anchor = anchorMilliseconds, anchor > 0 else { return false }
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
