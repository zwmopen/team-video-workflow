#include "content_store.h"

#include "winsqlite_compat.h"

#include <fstream>
#include <mutex>
#include <stdexcept>

namespace {
std::mutex gDatabaseMutex;

std::string WideToUtf8(const std::wstring& value) {
    if (value.empty()) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    if (length <= 0) throw std::runtime_error("无法转换数据库文本");
    std::string result(static_cast<size_t>(length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), length, nullptr, nullptr);
    return result;
}

std::wstring Utf8ToWide(const std::string& value) {
    if (value.empty()) return {};
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (length <= 0) return {};
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), result.data(), length);
    return result;
}

std::vector<std::string> Split(const std::string& value, char delimiter) {
    std::vector<std::string> fields;
    size_t start = 0;
    while (start <= value.size()) {
        size_t end = value.find(delimiter, start);
        if (end == std::string::npos) end = value.size();
        fields.push_back(value.substr(start, end - start));
        if (end == value.size()) break;
        start = end + 1;
    }
    return fields;
}

uint64_t ParseUnsigned(const std::string& value) {
    try {
        size_t consumed = 0;
        uint64_t result = std::stoull(value, &consumed);
        return consumed == value.size() ? result : 0;
    } catch (...) {
        return 0;
    }
}

class Database {
public:
    explicit Database(const std::filesystem::path& path) {
        int result = sqlite3_open16(path.c_str(), &handle_);
        if (result != SQLITE_OK) {
            std::string message = handle_ ? sqlite3_errmsg(handle_) : "无法打开数据库";
            if (handle_) sqlite3_close(handle_);
            handle_ = nullptr;
            throw std::runtime_error(message);
        }
        sqlite3_busy_timeout(handle_, 5000);
    }

    ~Database() { if (handle_) sqlite3_close(handle_); }
    sqlite3* Get() const { return handle_; }

    void Execute(const char* sql) const {
        char* error = nullptr;
        int result = sqlite3_exec(handle_, sql, nullptr, nullptr, &error);
        if (result != SQLITE_OK) {
            std::string message = error ? error : "数据库操作失败";
            sqlite3_free(error);
            throw std::runtime_error(message);
        }
    }

private:
    sqlite3* handle_ = nullptr;
};

class Statement {
public:
    Statement(sqlite3* database, const char* sql) {
        if (sqlite3_prepare_v2(database, sql, -1, &statement_, nullptr) != SQLITE_OK) {
            throw std::runtime_error(sqlite3_errmsg(database));
        }
    }
    ~Statement() { if (statement_) sqlite3_finalize(statement_); }
    sqlite3_stmt* Get() const { return statement_; }
    void Text(int index, const std::wstring& value) {
        if (sqlite3_bind_text16(statement_, index, value.c_str(), static_cast<int>(value.size() * sizeof(wchar_t)), SQLITE_TRANSIENT) != SQLITE_OK) {
            throw std::runtime_error("无法写入数据库文本");
        }
    }
    void Integer(int index, uint64_t value) {
        if (sqlite3_bind_int64(statement_, index, static_cast<sqlite3_int64>(value)) != SQLITE_OK) {
            throw std::runtime_error("无法写入数据库数字");
        }
    }
    void Run() {
        int result = sqlite3_step(statement_);
        if (result != SQLITE_DONE) throw std::runtime_error(sqlite3_errmsg(sqlite3_db_handle(statement_)));
        sqlite3_reset(statement_);
        sqlite3_clear_bindings(statement_);
    }
private:
    sqlite3_stmt* statement_ = nullptr;
};

std::wstring ColumnText(sqlite3_stmt* statement, int column) {
    const auto* text = static_cast<const wchar_t*>(sqlite3_column_text16(statement, column));
    return text ? text : L"";
}

void InsertTransfer(sqlite3* database,
                    const std::wstring& fingerprint,
                    const std::wstring& deviceId,
                    const std::wstring& timestamp,
                    const std::wstring& deviceName,
                    const std::wstring& sourceName,
                    const std::wstring& sourcePath,
                    const std::wstring& channel,
                    uint64_t files,
                    uint64_t images) {
    Statement item(database,
        "INSERT INTO content_items(fingerprint, display_name, current_path, state, created_at, updated_at) "
        "VALUES(?, ?, ?, 'ready', ?, ?) "
        "ON CONFLICT(fingerprint) DO UPDATE SET "
        "display_name=excluded.display_name, "
        "current_path=CASE WHEN excluded.current_path='' THEN content_items.current_path ELSE excluded.current_path END, "
        "updated_at=excluded.updated_at;");
    item.Text(1, fingerprint);
    item.Text(2, sourceName);
    item.Text(3, sourcePath);
    item.Text(4, timestamp);
    item.Text(5, timestamp);
    item.Run();

    Statement event(database,
        "INSERT INTO transfer_events(fingerprint, device_id, device_name, channel, transferred_at, source_name, source_path, file_count, image_count) "
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?);");
    event.Text(1, fingerprint);
    event.Text(2, deviceId);
    event.Text(3, deviceName);
    event.Text(4, channel);
    event.Text(5, timestamp);
    event.Text(6, sourceName);
    event.Text(7, sourcePath);
    event.Integer(8, files);
    event.Integer(9, images);
    event.Run();
}
}

ContentStore::ContentStore(std::filesystem::path databasePath) : databasePath_(std::move(databasePath)) {}

void ContentStore::Initialize(const std::filesystem::path& legacyHistoryPath) {
    std::lock_guard<std::mutex> lock(gDatabaseMutex);
    std::filesystem::create_directories(databasePath_.parent_path());
    Database database(databasePath_);
    database.Execute("PRAGMA journal_mode=WAL;");
    database.Execute("PRAGMA foreign_keys=ON;");
    database.Execute(
        "CREATE TABLE IF NOT EXISTS content_items("
        "fingerprint TEXT PRIMARY KEY, display_name TEXT NOT NULL, current_path TEXT NOT NULL DEFAULT '', "
        "state TEXT NOT NULL DEFAULT 'ready', created_at TEXT NOT NULL, updated_at TEXT NOT NULL);"
        "CREATE TABLE IF NOT EXISTS transfer_events("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL, device_id TEXT NOT NULL DEFAULT '', "
        "device_name TEXT NOT NULL, channel TEXT NOT NULL, transferred_at TEXT NOT NULL, source_name TEXT NOT NULL, "
        "source_path TEXT NOT NULL DEFAULT '', file_count INTEGER NOT NULL DEFAULT 0, image_count INTEGER NOT NULL DEFAULT 0);"
        "CREATE INDEX IF NOT EXISTS idx_transfer_device_fingerprint ON transfer_events(device_id, fingerprint);"
        "CREATE INDEX IF NOT EXISTS idx_transfer_name_fingerprint ON transfer_events(device_name, fingerprint);"
        "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);"
        "CREATE TABLE IF NOT EXISTS migrations(key TEXT PRIMARY KEY, completed_at TEXT NOT NULL);"
    );

    Statement migrated(database.Get(), "SELECT 1 FROM migrations WHERE key='legacy-transfer-history-v1';");
    if (sqlite3_step(migrated.Get()) == SQLITE_ROW) return;

    database.Execute("BEGIN IMMEDIATE;");
    try {
        std::ifstream input(legacyHistoryPath, std::ios::binary);
        std::string line;
        while (std::getline(input, line)) {
            auto fields = Split(line, '\t');
            if (fields.size() < 5) continue;
            InsertTransfer(database.Get(), Utf8ToWide(fields[0]), Utf8ToWide(fields[1]), Utf8ToWide(fields[2]),
                           Utf8ToWide(fields[3]), Utf8ToWide(fields[4]), L"",
                           fields.size() > 5 ? Utf8ToWide(fields[5]) : L"未知",
                           fields.size() > 6 ? ParseUnsigned(fields[6]) : 0,
                           fields.size() > 7 ? ParseUnsigned(fields[7]) : 0);
        }
        database.Execute("INSERT INTO migrations(key, completed_at) VALUES('legacy-transfer-history-v1', datetime('now')); COMMIT;");
    } catch (...) {
        database.Execute("ROLLBACK;");
        throw;
    }
}

std::map<std::wstring, std::wstring> ContentStore::PreviousTransfersForDevice(
        const std::wstring& deviceId, const std::wstring& deviceName) const {
    std::lock_guard<std::mutex> lock(gDatabaseMutex);
    Database database(databasePath_);
    Statement query(database.Get(),
        "SELECT fingerprint, transferred_at, device_name FROM transfer_events "
        "WHERE (device_id<>'' AND device_id=?) OR (device_name=? COLLATE NOCASE) "
        "ORDER BY id;");
    query.Text(1, deviceId);
    query.Text(2, deviceName);
    std::map<std::wstring, std::wstring> result;
    while (sqlite3_step(query.Get()) == SQLITE_ROW) {
        result[ColumnText(query.Get(), 0)] = ColumnText(query.Get(), 1) + L"|" + ColumnText(query.Get(), 2);
    }
    return result;
}

void ContentStore::RecordSuccessfulTransfers(
        const std::wstring& deviceId,
        const std::wstring& deviceName,
        const std::wstring& channel,
        const std::wstring& timestamp,
        const std::vector<StoredTransferItem>& items) const {
    std::lock_guard<std::mutex> lock(gDatabaseMutex);
    Database database(databasePath_);
    database.Execute("BEGIN IMMEDIATE;");
    try {
        for (const auto& item : items) {
            InsertTransfer(database.Get(), item.fingerprint, deviceId, timestamp, deviceName,
                           item.source.filename().wstring(), item.source.wstring(), channel, item.files, item.images);
        }
        database.Execute("COMMIT;");
    } catch (...) {
        database.Execute("ROLLBACK;");
        throw;
    }
}

std::wstring ContentStore::GetSetting(const std::wstring& key) const {
    std::lock_guard<std::mutex> lock(gDatabaseMutex);
    Database database(databasePath_);
    Statement query(database.Get(), "SELECT value FROM settings WHERE key=?;");
    query.Text(1, key);
    return sqlite3_step(query.Get()) == SQLITE_ROW ? ColumnText(query.Get(), 0) : L"";
}

void ContentStore::SetSetting(const std::wstring& key, const std::wstring& value) const {
    std::lock_guard<std::mutex> lock(gDatabaseMutex);
    Database database(databasePath_);
    Statement statement(database.Get(),
        "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value;");
    statement.Text(1, key);
    statement.Text(2, value);
    statement.Run();
}
