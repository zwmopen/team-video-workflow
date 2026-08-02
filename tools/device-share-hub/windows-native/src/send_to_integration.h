#pragma once

#include <cstddef>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace send_to {

struct DeviceEntry {
    std::wstring id;
    std::wstring displayName;
};

struct Invocation {
    std::wstring deviceId;
    std::vector<std::filesystem::path> paths;
};

struct ParseResult {
    bool requested = false;
    std::optional<Invocation> invocation;
    std::wstring error;
};

constexpr unsigned long kCopyDataId = 0x5A574D53;

std::wstring EncodeDeviceId(const std::wstring& value);
std::optional<std::wstring> DecodeDeviceId(const std::wstring& value);
ParseResult ParseArguments(const std::vector<std::wstring>& arguments);
ParseResult ParseProcessCommandLine();
std::vector<wchar_t> Serialize(const Invocation& invocation);
std::optional<Invocation> Deserialize(const void* data, size_t bytes);

bool SyncShortcuts(const std::vector<DeviceEntry>& devices,
                   const std::filesystem::path& executable,
                   std::wstring* error = nullptr);
void RemoveShortcuts();

}  // namespace send_to
