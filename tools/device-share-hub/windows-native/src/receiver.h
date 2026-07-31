#pragma once

#include <atomic>
#include <functional>
#include <filesystem>
#include <string>
#include <vector>

struct RelayIncomingFile {
    std::wstring name;
    std::filesystem::path path;
};

struct RelayIncomingTask {
    std::string messageId;
    std::string originId;
    std::string destinationId;
    std::string previousHopId;
    std::string contentKind;
    long long expiresAt = 0;
    int hopLimit = 0;
    std::vector<RelayIncomingFile> files;
};

void RunLanReceiver(std::atomic<bool>& running,
                    const std::function<void(const std::wstring&)>& status,
                    const std::function<void(const std::wstring&, const std::wstring&)>& log,
                    const std::string& ownDeviceId,
                    const std::function<bool(const RelayIncomingTask&, std::wstring&)>& relay,
                    const std::function<void(const std::string&)>& clipboard);
