#pragma once

#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace p2p_transport {

struct FileItem {
    std::filesystem::path path;
    std::string name;
    std::string mime;
    uint64_t bytes = 0;
    std::string sha256;
};

struct Signal {
    std::string key;
    std::string type;
    std::string data;
};

using SendSignal = std::function<void(const std::string& type, const std::string& data)>;
using PollSignals = std::function<std::vector<Signal>()>;
using ProgressCallback = std::function<void(uintmax_t sent, uintmax_t total)>;

bool Send(const std::string& transferId,
          const std::string& senderDeviceId,
          const std::string& recipientDeviceId,
          const std::string& contentKind,
          const std::vector<FileItem>& files,
          const SendSignal& sendSignal,
          const PollSignals& pollSignals,
          const ProgressCallback& progress,
          std::string& error);

}  // namespace p2p_transport
