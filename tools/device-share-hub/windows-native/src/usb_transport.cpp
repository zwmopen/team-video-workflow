#include "usb_transport.h"

#include <windows.h>
#include <PortableDeviceApi.h>
#include <PortableDevice.h>
#include <PortableDeviceTypes.h>
#include <propvarutil.h>
#include <wincrypt.h>
#include <setupapi.h>
#include <initguid.h>
#include <devpkey.h>
#include <usbiodef.h>

#include <algorithm>
#include <fstream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <thread>

namespace {
template <typename T>
class ComPtr {
public:
    ComPtr() = default;
    ~ComPtr() { reset(); }
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;
    ComPtr(ComPtr&& other) noexcept : value_(other.value_) { other.value_ = nullptr; }
    ComPtr& operator=(ComPtr&& other) noexcept {
        if (this != &other) {
            reset();
            value_ = other.value_;
            other.value_ = nullptr;
        }
        return *this;
    }
    T** put() { reset(); return &value_; }
    T* get() const { return value_; }
    T* operator->() const { return value_; }
    explicit operator bool() const { return value_ != nullptr; }
    void reset(T* value = nullptr) { if (value_) value_->Release(); value_ = value; }
private:
    T* value_ = nullptr;
};

void* gModule = nullptr;
UsbLogCallback gLogger;
std::filesystem::path gBridgePath;

std::string WideToUtf8(const std::wstring& value);

std::wstring ErrorText(HRESULT result) {
    wchar_t* raw = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                   FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, static_cast<DWORD>(result),
                   0, reinterpret_cast<wchar_t*>(&raw), 0, nullptr);
    std::wstring text = raw ? raw : L"";
    if (raw) LocalFree(raw);
    while (!text.empty() && (text.back() == L'\r' || text.back() == L'\n')) text.pop_back();
    return text.empty() ? L"错误 " + std::to_wstring(static_cast<unsigned long>(result)) : text;
}

void Check(HRESULT result, const wchar_t* action) {
    if (FAILED(result)) {
        std::wstring message = std::wstring(action) + L"：" + ErrorText(result);
        throw std::runtime_error(WideToUtf8(message));
    }
}

std::string WideToUtf8(const std::wstring& value) {
    if (value.empty()) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                                     nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<size_t>(length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                        result.data(), length, nullptr, nullptr);
    return result;
}

std::wstring Utf8ToWide(const std::string& value) {
    if (value.empty()) return {};
    int length = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), length);
    return result;
}

std::wstring Base64UrlDecode(std::string value) {
    std::replace(value.begin(), value.end(), '-', '+');
    std::replace(value.begin(), value.end(), '_', '/');
    while (value.size() % 4) value.push_back('=');
    DWORD size = 0;
    if (!CryptStringToBinaryA(value.c_str(), static_cast<DWORD>(value.size()),
                              CRYPT_STRING_BASE64, nullptr, &size, nullptr, nullptr)) return L"";
    std::string output(size, '\0');
    if (!CryptStringToBinaryA(value.c_str(), static_cast<DWORD>(value.size()),
                              CRYPT_STRING_BASE64, reinterpret_cast<BYTE*>(output.data()),
                              &size, nullptr, nullptr)) return L"";
    output.resize(size);
    return Utf8ToWide(output);
}

std::vector<std::string> Split(const std::string& value, char delimiter) {
    std::vector<std::string> result;
    size_t start = 0;
    while (start <= value.size()) {
        size_t end = value.find(delimiter, start);
        if (end == std::string::npos) end = value.size();
        result.push_back(value.substr(start, end - start));
        if (end == value.size()) break;
        start = end + 1;
    }
    return result;
}

std::wstring QuoteArgument(const std::wstring& value) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (wchar_t character : value) {
        if (character == L'\\') {
            slashes++;
        } else if (character == L'"') {
            result.append(slashes * 2 + 1, L'\\');
            result.push_back(L'"');
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            slashes = 0;
            result.push_back(character);
        }
    }
    result.append(slashes * 2, L'\\');
    result.push_back(L'"');
    return result;
}

std::string RunBridge(const std::vector<std::wstring>& arguments) {
    if (gBridgePath.empty()) return {};
    std::wstring command = L"python " + QuoteArgument(gBridgePath.wstring());
    for (const auto& argument : arguments) command += L" " + QuoteArgument(argument);
    SECURITY_ATTRIBUTES security{sizeof(security), nullptr, TRUE};
    HANDLE readPipe = nullptr;
    HANDLE writePipe = nullptr;
    if (!CreatePipe(&readPipe, &writePipe, &security, 0)) return {};
    SetHandleInformation(readPipe, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW startup{sizeof(startup)};
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdOutput = writePipe;
    startup.hStdError = writePipe;
    PROCESS_INFORMATION process{};
    std::vector<wchar_t> writable(command.begin(), command.end());
    writable.push_back(L'\0');
    BOOL started = CreateProcessW(nullptr, writable.data(), nullptr, nullptr, TRUE,
                                  CREATE_NO_WINDOW, nullptr, nullptr, &startup, &process);
    CloseHandle(writePipe);
    if (!started) {
        CloseHandle(readPipe);
        return {};
    }
    std::string output;
    char buffer[4096];
    DWORD count = 0;
    while (ReadFile(readPipe, buffer, sizeof(buffer), &count, nullptr) && count > 0) {
        output.append(buffer, buffer + count);
    }
    WaitForSingleObject(process.hProcess, 120000);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    CloseHandle(readPipe);
    return output;
}

void ExtractBridge() {
    if (!gModule) return;
    HRSRC resource = FindResourceW(static_cast<HMODULE>(gModule), MAKEINTRESOURCEW(102), RT_RCDATA);
    if (!resource) return;
    HGLOBAL loaded = LoadResource(static_cast<HMODULE>(gModule), resource);
    DWORD size = SizeofResource(static_cast<HMODULE>(gModule), resource);
    const void* bytes = LockResource(loaded);
    if (!bytes || size == 0) return;
    wchar_t base[MAX_PATH]{};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", base, ARRAYSIZE(base));
    std::filesystem::path folder = (length ? std::filesystem::path(base)
                                           : std::filesystem::temp_directory_path())
        / L"ZwmDeviceShareHub";
    std::filesystem::create_directories(folder);
    gBridgePath = folder / L"usb_bridge.py";
    std::ofstream output(gBridgePath, std::ios::binary | std::ios::trunc);
    output.write(static_cast<const char*>(bytes), size);
}

std::wstring ManagerString(IPortableDeviceManager* manager, const std::wstring& id, bool model) {
    DWORD count = 0;
    HRESULT first = model
        ? manager->GetDeviceDescription(id.c_str(), nullptr, &count)
        : manager->GetDeviceFriendlyName(id.c_str(), nullptr, &count);
    if (first != HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER) || count == 0) return L"";
    std::vector<wchar_t> value(count);
    HRESULT second = model
        ? manager->GetDeviceDescription(id.c_str(), value.data(), &count)
        : manager->GetDeviceFriendlyName(id.c_str(), value.data(), &count);
    return SUCCEEDED(second) ? std::wstring(value.data()) : L"";
}

ComPtr<IPortableDevice> OpenPortableDevice(const std::wstring& id) {
    ComPtr<IPortableDeviceValues> client;
    Check(CoCreateInstance(CLSID_PortableDeviceValues, nullptr, CLSCTX_INPROC_SERVER,
                           IID_PPV_ARGS(client.put())), L"无法创建 USB 客户端信息");
    client->SetStringValue(WPD_CLIENT_NAME, L"相册投送中控");
    client->SetUnsignedIntegerValue(WPD_CLIENT_MAJOR_VERSION, 3);
    client->SetUnsignedIntegerValue(WPD_CLIENT_MINOR_VERSION, 8);
    client->SetUnsignedIntegerValue(WPD_CLIENT_REVISION, 0);
    client->SetUnsignedIntegerValue(WPD_CLIENT_SECURITY_QUALITY_OF_SERVICE,
                                    SECURITY_IMPERSONATION);
    ComPtr<IPortableDevice> device;
    Check(CoCreateInstance(CLSID_PortableDeviceFTM, nullptr, CLSCTX_INPROC_SERVER,
                           IID_PPV_ARGS(device.put())), L"无法打开 USB 设备");
    Check(device->Open(id.c_str(), client.get()), L"USB 设备未允许文件传输");
    return device;
}

std::vector<std::wstring> ChildIds(IPortableDeviceContent* content, const std::wstring& parent) {
    ComPtr<IEnumPortableDeviceObjectIDs> enumerator;
    Check(content->EnumObjects(0, parent.c_str(), nullptr, enumerator.put()), L"无法读取手机目录");
    std::vector<std::wstring> result;
    while (true) {
        PWSTR raw = nullptr;
        ULONG fetched = 0;
        HRESULT next = enumerator->Next(1, &raw, &fetched);
        if (next == S_FALSE || fetched == 0) break;
        Check(next, L"无法继续读取手机目录");
        result.emplace_back(raw);
        CoTaskMemFree(raw);
    }
    return result;
}

std::wstring ObjectName(IPortableDeviceProperties* properties, const std::wstring& id) {
    ComPtr<IPortableDeviceKeyCollection> keys;
    Check(CoCreateInstance(CLSID_PortableDeviceKeyCollection, nullptr, CLSCTX_INPROC_SERVER,
                           IID_PPV_ARGS(keys.put())), L"无法读取 USB 属性");
    keys->Add(WPD_OBJECT_NAME);
    keys->Add(WPD_OBJECT_ORIGINAL_FILE_NAME);
    ComPtr<IPortableDeviceValues> values;
    if (FAILED(properties->GetValues(id.c_str(), keys.get(), values.put()))) return L"";
    PWSTR raw = nullptr;
    if (SUCCEEDED(values->GetStringValue(WPD_OBJECT_ORIGINAL_FILE_NAME, &raw)) && raw) {
        std::wstring name(raw); CoTaskMemFree(raw); return name;
    }
    if (SUCCEEDED(values->GetStringValue(WPD_OBJECT_NAME, &raw)) && raw) {
        std::wstring name(raw); CoTaskMemFree(raw); return name;
    }
    return L"";
}

std::wstring FindChild(IPortableDeviceContent* content, IPortableDeviceProperties* properties,
                       const std::wstring& parent, const std::wstring& name) {
    for (const auto& id : ChildIds(content, parent)) {
        if (_wcsicmp(ObjectName(properties, id).c_str(), name.c_str()) == 0) return id;
    }
    return L"";
}

std::wstring FindRecursive(IPortableDeviceContent* content, IPortableDeviceProperties* properties,
                           const std::wstring& parent, const std::wstring& name, int depth) {
    if (depth < 0) return L"";
    for (const auto& id : ChildIds(content, parent)) {
        if (_wcsicmp(ObjectName(properties, id).c_str(), name.c_str()) == 0) return id;
        std::wstring nested = FindRecursive(content, properties, id, name, depth - 1);
        if (!nested.empty()) return nested;
    }
    return L"";
}

std::wstring CreateFolder(IPortableDeviceContent* content, const std::wstring& parent,
                          const std::wstring& name) {
    ComPtr<IPortableDeviceValues> values;
    Check(CoCreateInstance(CLSID_PortableDeviceValues, nullptr, CLSCTX_INPROC_SERVER,
                           IID_PPV_ARGS(values.put())), L"无法准备 USB 文件夹");
    values->SetStringValue(WPD_OBJECT_PARENT_ID, parent.c_str());
    values->SetStringValue(WPD_OBJECT_NAME, name.c_str());
    values->SetStringValue(WPD_OBJECT_ORIGINAL_FILE_NAME, name.c_str());
    values->SetGuidValue(WPD_OBJECT_CONTENT_TYPE, WPD_CONTENT_TYPE_FOLDER);
    values->SetGuidValue(WPD_OBJECT_FORMAT, WPD_OBJECT_FORMAT_PROPERTIES_ONLY);
    PWSTR raw = nullptr;
    Check(content->CreateObjectWithPropertiesOnly(values.get(), &raw),
          L"无法在手机上创建文件夹");
    std::wstring id(raw);
    CoTaskMemFree(raw);
    return id;
}

uint64_t TotalBytes(const std::vector<std::filesystem::path>& items) {
    uint64_t total = 0;
    for (const auto& item : items) {
        if (std::filesystem::is_regular_file(item)) total += std::filesystem::file_size(item);
        else for (const auto& child : std::filesystem::recursive_directory_iterator(
                     item, std::filesystem::directory_options::skip_permission_denied)) {
            if (child.is_regular_file()) total += child.file_size();
        }
    }
    return total;
}

void WritePortableFile(IPortableDeviceContent* content, const std::wstring& parent,
                       const std::filesystem::path& source, uint64_t total, uint64_t& completed,
                       std::atomic<bool>& cancel, const UsbProgressCallback& progress) {
    ComPtr<IPortableDeviceValues> values;
    Check(CoCreateInstance(CLSID_PortableDeviceValues, nullptr, CLSCTX_INPROC_SERVER,
                           IID_PPV_ARGS(values.put())), L"无法准备 USB 文件");
    std::wstring name = source.filename().wstring();
    values->SetStringValue(WPD_OBJECT_PARENT_ID, parent.c_str());
    values->SetStringValue(WPD_OBJECT_NAME, name.c_str());
    values->SetStringValue(WPD_OBJECT_ORIGINAL_FILE_NAME, name.c_str());
    values->SetGuidValue(WPD_OBJECT_CONTENT_TYPE, WPD_CONTENT_TYPE_GENERIC_FILE);
    values->SetGuidValue(WPD_OBJECT_FORMAT, WPD_OBJECT_FORMAT_UNSPECIFIED);
    values->SetUnsignedLargeIntegerValue(WPD_OBJECT_SIZE, std::filesystem::file_size(source));
    ComPtr<IStream> stream;
    DWORD optimal = 256 * 1024;
    Check(content->CreateObjectWithPropertiesAndData(
              values.get(), stream.put(), &optimal, nullptr), L"手机拒绝接收文件");
    std::ifstream input(source, std::ios::binary);
    std::vector<char> buffer(std::max<DWORD>(64 * 1024, optimal));
    while (input) {
        if (cancel) throw std::runtime_error("传送已取消");
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        ULONG count = static_cast<ULONG>(input.gcount());
        if (!count) break;
        ULONG written = 0;
        Check(stream->Write(buffer.data(), count, &written), L"USB 写入失败");
        if (written != count) throw std::runtime_error("USB 写入不完整");
        completed += written;
        if (progress) progress(completed, total);
    }
    Check(stream->Commit(STGC_DEFAULT), L"手机没有保存完整文件");
}

void WritePortableItem(IPortableDeviceContent* content, IPortableDeviceProperties* properties,
                       const std::wstring& parent, const std::filesystem::path& source,
                       uint64_t total, uint64_t& completed, std::atomic<bool>& cancel,
                       const UsbProgressCallback& progress) {
    if (std::filesystem::is_regular_file(source)) {
        WritePortableFile(content, parent, source, total, completed, cancel, progress);
        return;
    }
    std::wstring folder = CreateFolder(content, parent, source.filename().wstring());
    std::vector<std::filesystem::path> children;
    for (const auto& child : std::filesystem::directory_iterator(source)) children.push_back(child.path());
    std::sort(children.begin(), children.end());
    for (const auto& child : children) {
        WritePortableItem(content, properties, folder, child, total, completed, cancel, progress);
    }
}

void SendPortable(const UsbPeer& peer, const std::vector<std::filesystem::path>& items,
                  std::atomic<bool>& cancel, const UsbStatusCallback& status,
                  const UsbProgressCallback& progress) {
    ComPtr<IPortableDevice> device = OpenPortableDevice(peer.locator);
    ComPtr<IPortableDeviceContent> content;
    Check(device->Content(content.put()), L"无法访问手机存储");
    ComPtr<IPortableDeviceProperties> properties;
    Check(content->Properties(properties.put()), L"无法读取手机存储属性");
    std::wstring download = FindRecursive(content.get(), properties.get(),
                                          WPD_DEVICE_OBJECT_ID, L"Download", 3);
    if (download.empty()) download = FindRecursive(content.get(), properties.get(),
                                                   WPD_DEVICE_OBJECT_ID, L"下载", 3);
    if (download.empty()) throw std::runtime_error("手机没有开放 Download；请把 USB 用途切换为“文件传输”");
    std::wstring lark = FindChild(content.get(), properties.get(), download, L"Lark");
    if (lark.empty()) lark = CreateFolder(content.get(), download, L"Lark");
    for (const auto& item : items) {
        if (!FindChild(content.get(), properties.get(), lark, item.filename().wstring()).empty()) {
            throw std::runtime_error("手机 Lark 中已有同名项目");
        }
    }
    uint64_t total = TotalBytes(items);
    uint64_t completed = 0;
    if (status) status(L"正在通过 USB 传给“" + peer.name + L"”…");
    for (const auto& item : items) {
        WritePortableItem(content.get(), properties.get(), lark, item, total, completed, cancel, progress);
    }
    if (progress) progress(total, total);
}

std::vector<UsbPeer> EnumeratePortable() {
    std::vector<UsbPeer> result;
    ComPtr<IPortableDeviceManager> manager;
    if (FAILED(CoCreateInstance(CLSID_PortableDeviceManager, nullptr, CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(manager.put())))) return result;
    DWORD count = 0;
    manager->GetDevices(nullptr, &count);
    if (!count) return result;
    std::vector<PWSTR> ids(count, nullptr);
    if (FAILED(manager->GetDevices(ids.data(), &count))) return result;
    for (DWORD index = 0; index < count; ++index) {
        if (!ids[index]) continue;
        UsbPeer peer;
        peer.locator = ids[index];
        peer.id = L"wpd:" + peer.locator;
        peer.name = ManagerString(manager.get(), peer.locator, false);
        peer.model = ManagerString(manager.get(), peer.locator, true);
        const bool hasDescription = !peer.name.empty() || !peer.model.empty();
        peer.kind = UsbTransportKind::PortableDevice;
        try {
            auto opened = OpenPortableDevice(peer.locator);
            peer.ready = true;
            peer.hint = L"USB 文件传输";
        } catch (const std::exception& error) {
            peer.ready = false;
            peer.hint = L"已连接；请在手机上选择“文件传输”";
            if (gLogger) {
                gLogger(L"usb_wpd_open_failed",
                        (peer.name.empty() ? L"未命名设备" : peer.name)
                        + L" error=" + Utf8ToWide(error.what()));
            }
        }
        if (!hasDescription && !peer.ready) {
            CoTaskMemFree(ids[index]);
            continue;
        }
        if (peer.name.empty()) peer.name = L"USB 手机";
        result.push_back(peer);
        CoTaskMemFree(ids[index]);
    }
    return result;
}

std::vector<UsbPeer> EnumerateConnectedAndroidUsb() {
    std::vector<UsbPeer> result;
    HDEVINFO devices = SetupDiGetClassDevsW(
        &GUID_DEVINTERFACE_USB_DEVICE, nullptr, nullptr,
        DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (devices == INVALID_HANDLE_VALUE) return result;
    for (DWORD index = 0;; ++index) {
        SP_DEVINFO_DATA info{sizeof(info)};
        if (!SetupDiEnumDeviceInfo(devices, index, &info)) break;
        wchar_t hardware[2048]{};
        if (!SetupDiGetDeviceRegistryPropertyW(
                devices, &info, SPDRP_HARDWAREID, nullptr,
                reinterpret_cast<PBYTE>(hardware), sizeof(hardware), nullptr)) continue;
        std::wstring normalized = hardware;
        std::transform(normalized.begin(), normalized.end(), normalized.begin(), towupper);
        static const wchar_t* phoneVendors[] = {
            L"VID_18D1", L"VID_2717", L"VID_2D95", L"VID_12D1",
            L"VID_0BB4", L"VID_22D9", L"VID_2A70", L"VID_04E8"
        };
        bool phone = false;
        for (const auto* vendor : phoneVendors) {
            if (normalized.find(vendor) != std::wstring::npos) { phone = true; break; }
        }
        if (!phone) continue;
        wchar_t instance[2048]{};
        if (!SetupDiGetDeviceInstanceIdW(devices, &info, instance, ARRAYSIZE(instance), nullptr)) continue;
        UsbPeer peer;
        peer.id = L"usbraw:" + std::wstring(instance);
        peer.locator = instance;
        // A charge/debug-only interface usually exposes a driver description such as
        // "Android Composite ADB Interface". That is implementation detail, not a
        // useful device name. Keep the card human-readable until MTP can provide the
        // phone's actual name.
        wchar_t reportedName[512]{};
        DEVPROPTYPE propertyType = 0;
        SetupDiGetDevicePropertyW(
            devices, &info, &DEVPKEY_Device_BusReportedDeviceDesc,
            &propertyType, reinterpret_cast<PBYTE>(reportedName),
            sizeof(reportedName), nullptr, 0);
        peer.name = reportedName[0] ? reportedName : L"安卓手机";
        peer.model = L"USB 已连接";
        peer.kind = UsbTransportKind::DetectedOnly;
        peer.ready = false;
        peer.hint = L"请把手机 USB 用途切换为“文件传输”";
        result.push_back(peer);
    }
    SetupDiDestroyDeviceInfoList(devices);
    return result;
}

std::vector<UsbPeer> EnumerateApple() {
    std::vector<UsbPeer> result;
    std::string output = RunBridge({L"list"});
    std::istringstream lines(output);
    std::string line;
    while (std::getline(lines, line)) {
        auto fields = Split(line, '\t');
        if (fields.size() < 5 || fields[0] != "DEVICE") continue;
        UsbPeer peer;
        peer.locator = Base64UrlDecode(fields[1]);
        peer.id = L"apple:" + peer.locator;
        peer.name = Base64UrlDecode(fields[2]);
        peer.model = Base64UrlDecode(fields[3]);
        peer.ready = fields[4] == "1";
        peer.kind = UsbTransportKind::AppleFileSharing;
        peer.hint = peer.ready ? L"iPhone USB 文件共享" : L"请在 iPhone 上信任这台电脑";
        result.push_back(peer);
    }
    return result;
}
}  // namespace

void ConfigureUsbTransport(void* moduleHandle, UsbLogCallback logger) {
    gModule = moduleHandle;
    gLogger = std::move(logger);
    ExtractBridge();
}

std::vector<UsbPeer> EnumerateUsbPeers() {
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    std::vector<UsbPeer> result = EnumeratePortable();
    auto detected = EnumerateConnectedAndroidUsb();
    for (const auto& peer : detected) {
        bool alreadyRepresented = std::any_of(result.begin(), result.end(), [&](const UsbPeer& ready) {
            if (ready.kind != UsbTransportKind::PortableDevice) return false;
            if (_wcsicmp(ready.name.c_str(), peer.name.c_str()) == 0
                    || (!ready.model.empty() && _wcsicmp(ready.model.c_str(), peer.model.c_str()) == 0)) {
                return true;
            }
            std::wstring portableId = ready.locator;
            std::wstring rawId = peer.locator;
            std::transform(portableId.begin(), portableId.end(), portableId.begin(), towupper);
            std::transform(rawId.begin(), rawId.end(), rawId.begin(), towupper);
            const auto vendorAt = rawId.find(L"VID_");
            const auto serialAt = rawId.find_last_of(L"\\#");
            if (vendorAt == std::wstring::npos || serialAt == std::wstring::npos
                    || serialAt + 1 >= rawId.size()) return false;
            const std::wstring vendor = rawId.substr(vendorAt, 8);
            const std::wstring serial = rawId.substr(serialAt + 1);
            return serial.size() >= 4
                && portableId.find(vendor) != std::wstring::npos
                && portableId.find(serial) != std::wstring::npos;
        });
        if (!alreadyRepresented) result.push_back(peer);
    }
    auto apple = EnumerateApple();
    result.insert(result.end(), apple.begin(), apple.end());
    if (SUCCEEDED(initialized)) CoUninitialize();
    return result;
}

void SendItemsOverUsb(
        const UsbPeer& peer, const std::vector<std::filesystem::path>& items,
        std::atomic<bool>& cancelRequested, const UsbStatusCallback& onStatus,
        const UsbProgressCallback& onProgress) {
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    try {
        if (peer.kind == UsbTransportKind::PortableDevice) {
            SendPortable(peer, items, cancelRequested, onStatus, onProgress);
        } else if (peer.kind == UsbTransportKind::AppleFileSharing) {
            if (onStatus) onStatus(L"正在通过 USB 传给“" + peer.name + L"”…");
            std::vector<std::wstring> arguments{L"send", L"--serial", peer.locator};
            for (const auto& item : items) {
                arguments.push_back(L"--source");
                arguments.push_back(item.wstring());
            }
            std::string output = RunBridge(arguments);
            std::istringstream lines(output);
            std::string line;
            std::wstring error;
            while (std::getline(lines, line)) {
                auto fields = Split(line, '\t');
                if (fields.size() >= 3 && fields[0] == "PROGRESS" && onProgress) {
                    onProgress(std::stoull(fields[1]), std::stoull(fields[2]));
                } else if (fields.size() >= 2 && fields[0] == "ERROR") {
                    error = Base64UrlDecode(fields[1]);
                }
            }
            if (!error.empty()) throw std::runtime_error(WideToUtf8(error));
            if (output.find("RESULT\t") == std::string::npos) {
                throw std::runtime_error("iPhone USB 传送没有完成");
            }
        } else {
            throw std::runtime_error("USB 设备尚未开放文件传输");
        }
    } catch (...) {
        if (SUCCEEDED(initialized)) CoUninitialize();
        throw;
    }
    if (SUCCEEDED(initialized)) CoUninitialize();
}
