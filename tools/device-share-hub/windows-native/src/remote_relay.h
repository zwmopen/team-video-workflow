#pragma once

#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace remote_relay {

struct EnrollmentInfo {
    std::string deviceId;
    std::string deviceName;
    std::string signingX;
    std::string signingY;
    std::string agreementX;
    std::string agreementY;
};

struct RelayDevice {
    std::string deviceId;
    std::string name;
    bool online = false;
    bool remoteAllowed = false;
    int workCount = -1;
    int conversionCount = -1;
    int trafficCount = -1;
    int uncategorizedCount = -1;
    std::string appVersion;
    int64_t appVersionCode = -1;
    std::string updateCapability;
};

using ProgressCallback = std::function<void(uintmax_t sent, uintmax_t total)>;

// Creates/reloads the Windows admin identity, registers the workspace, and
// returns a profile signed for one mobile device. Private key material never
// leaves the Windows user profile and is DPAPI-protected at rest.
bool BuildMobileProfile(const std::filesystem::path& stateDirectory,
                        const EnrollmentInfo& device,
                        std::string& profileJson,
                        std::string& error);

// Uploads a plain public transfer to the deployed relay. The receiver downloads
// and ACKs it; this function returns after the relay has accepted the task.
bool SendPlainTransfer(const std::filesystem::path& stateDirectory,
                       const std::string& recipientDeviceId,
                       const std::vector<std::filesystem::path>& files,
                       const ProgressCallback& progress,
                       std::string& transferId,
                       std::string& error);

// Tries the authenticated WebRTC DataChannel path. A false result is an
// expected transport outcome and must be followed by SendPlainTransfer.
bool TryP2PTransfer(const std::filesystem::path& stateDirectory,
                    const std::string& recipientDeviceId,
                    const std::vector<std::filesystem::path>& files,
                    const ProgressCallback& progress,
                    std::string& transferId,
                    std::string& error);

bool ListDevices(const std::filesystem::path& stateDirectory,
                 std::vector<RelayDevice>& devices,
                 std::string& error);

const char* Endpoint();

}  // namespace remote_relay
