#pragma once

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

enum class UsbTransportKind {
    PortableDevice,
    AppleFileSharing,
    DetectedOnly,
};

struct UsbPeer {
    std::wstring id;
    std::wstring name;
    std::wstring model;
    std::wstring locator;
    UsbTransportKind kind = UsbTransportKind::DetectedOnly;
    bool ready = false;
    std::wstring hint;
};

using UsbStatusCallback = std::function<void(const std::wstring&)>;
using UsbProgressCallback = std::function<void(uint64_t, uint64_t)>;
using UsbLogCallback = std::function<void(const std::wstring&, const std::wstring&)>;

void ConfigureUsbTransport(void* moduleHandle, UsbLogCallback logger);
std::vector<UsbPeer> EnumerateUsbPeers();
void SendItemsOverUsb(
    const UsbPeer& peer,
    const std::vector<std::filesystem::path>& items,
    std::atomic<bool>& cancelRequested,
    const UsbStatusCallback& onStatus,
    const UsbProgressCallback& onProgress);

