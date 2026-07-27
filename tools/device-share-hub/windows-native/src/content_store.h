#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <string>
#include <vector>

struct StoredTransferItem {
    std::wstring fingerprint;
    std::filesystem::path source;
    uint64_t files = 0;
    uint64_t images = 0;
};

class ContentStore {
public:
    explicit ContentStore(std::filesystem::path databasePath);

    void Initialize(const std::filesystem::path& legacyHistoryPath);
    std::map<std::wstring, std::wstring> PreviousTransfersForDevice(
        const std::wstring& deviceId, const std::wstring& deviceName) const;
    void RecordSuccessfulTransfers(
        const std::wstring& deviceId,
        const std::wstring& deviceName,
        const std::wstring& channel,
        const std::wstring& timestamp,
        const std::vector<StoredTransferItem>& items) const;

    std::wstring GetSetting(const std::wstring& key) const;
    void SetSetting(const std::wstring& key, const std::wstring& value) const;

    const std::filesystem::path& DatabasePath() const { return databasePath_; }

private:
    std::filesystem::path databasePath_;
};
