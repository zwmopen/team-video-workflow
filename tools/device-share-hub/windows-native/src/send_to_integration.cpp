#include "send_to_integration.h"

#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>

#include <algorithm>
#include <cwchar>
#include <cwctype>
#include <fstream>
#include <map>
#include <set>
#include <sstream>

namespace send_to {
namespace {

constexpr wchar_t kMenuFolderName[] = L"相册在线设备";
constexpr wchar_t kMarkerName[] = L".zwm-device-share-hub";
constexpr wchar_t kArgumentName[] = L"--send-to-device-id";

std::optional<std::filesystem::path> SendToRoot() {
    PWSTR raw = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_SendTo, KF_FLAG_CREATE, nullptr, &raw)) || !raw) return std::nullopt;
    std::filesystem::path result(raw);
    CoTaskMemFree(raw);
    return result;
}

std::filesystem::path MenuFolder() {
    auto root = SendToRoot();
    return root ? *root / kMenuFolderName : std::filesystem::path();
}

bool IsOwnedFolder(const std::filesystem::path& folder) {
    return !folder.empty() && std::filesystem::is_regular_file(folder / kMarkerName);
}

std::wstring SanitizeFileName(std::wstring value) {
    constexpr wchar_t invalid[] = L"<>:\"/\\|?*";
    for (wchar_t& character : value) {
        if (character < 32 || std::wcschr(invalid, character)) character = L' ';
    }
    while (!value.empty() && (value.back() == L' ' || value.back() == L'.')) value.pop_back();
    if (value.empty()) value = L"在线设备";
    if (value.size() > 72) value.resize(72);
    return value;
}

bool CreateShortcut(const std::filesystem::path& shortcut,
                    const std::filesystem::path& executable,
                    const std::wstring& encodedId,
                    std::wstring* error) {
    IShellLinkW* link = nullptr;
    HRESULT status = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&link));
    if (FAILED(status) || !link) {
        if (error) *error = L"无法创建 Windows 发送到快捷方式";
        return false;
    }
    std::wstring arguments = std::wstring(kArgumentName) + L" " + encodedId;
    link->SetPath(executable.c_str());
    link->SetArguments(arguments.c_str());
    link->SetDescription(L"通过素材投送中控发送到这台在线设备");
    link->SetIconLocation(executable.c_str(), 0);
    IPersistFile* persist = nullptr;
    status = link->QueryInterface(IID_PPV_ARGS(&persist));
    if (SUCCEEDED(status) && persist) status = persist->Save(shortcut.c_str(), TRUE);
    if (persist) persist->Release();
    link->Release();
    if (FAILED(status)) {
        if (error) *error = L"Windows 无法保存发送到快捷方式";
        return false;
    }
    return true;
}

}  // namespace

std::wstring EncodeDeviceId(const std::wstring& value) {
    static constexpr wchar_t digits[] = L"0123456789abcdef";
    std::wstring result;
    result.reserve(value.size() * sizeof(wchar_t) * 2);
    for (wchar_t character : value) {
        unsigned int code = static_cast<unsigned int>(character);
        for (int shift = static_cast<int>(sizeof(wchar_t) * 8) - 4; shift >= 0; shift -= 4) {
            result.push_back(digits[(code >> shift) & 0xF]);
        }
    }
    return result;
}

std::optional<std::wstring> DecodeDeviceId(const std::wstring& value) {
    const size_t width = sizeof(wchar_t) * 2;
    if (value.empty() || value.size() % width != 0) return std::nullopt;
    std::wstring result;
    for (size_t offset = 0; offset < value.size(); offset += width) {
        unsigned int code = 0;
        for (size_t index = 0; index < width; ++index) {
            wchar_t character = static_cast<wchar_t>(std::towlower(value[offset + index]));
            int digit = character >= L'0' && character <= L'9' ? character - L'0'
                      : character >= L'a' && character <= L'f' ? character - L'a' + 10 : -1;
            if (digit < 0) return std::nullopt;
            code = (code << 4) | static_cast<unsigned int>(digit);
        }
        result.push_back(static_cast<wchar_t>(code));
    }
    return result;
}

ParseResult ParseArguments(const std::vector<std::wstring>& arguments) {
    ParseResult result;
    auto marker = std::find(arguments.begin(), arguments.end(), kArgumentName);
    if (marker == arguments.end()) return result;
    result.requested = true;
    if (++marker == arguments.end()) {
        result.error = L"发送到菜单缺少目标设备";
        return result;
    }
    auto deviceId = DecodeDeviceId(*marker);
    if (!deviceId || deviceId->empty()) {
        result.error = L"发送到菜单中的设备标识无效";
        return result;
    }
    Invocation invocation;
    invocation.deviceId = *deviceId;
    for (++marker; marker != arguments.end(); ++marker) {
        std::filesystem::path path(*marker);
        std::error_code ignored;
        if (std::filesystem::is_regular_file(path, ignored) || std::filesystem::is_directory(path, ignored)) {
            invocation.paths.push_back(std::move(path));
        }
    }
    if (invocation.paths.empty()) {
        result.error = L"没有找到可以发送的文件或文件夹";
        return result;
    }
    if (invocation.paths.size() > 100) {
        result.error = L"单次最多发送 100 个顶层文件或文件夹";
        return result;
    }
    result.invocation = std::move(invocation);
    return result;
}

ParseResult ParseProcessCommandLine() {
    int count = 0;
    LPWSTR* raw = CommandLineToArgvW(GetCommandLineW(), &count);
    if (!raw) return {};
    std::vector<std::wstring> arguments;
    for (int index = 1; index < count; ++index) arguments.emplace_back(raw[index]);
    LocalFree(raw);
    return ParseArguments(arguments);
}

std::vector<wchar_t> Serialize(const Invocation& invocation) {
    size_t characters = invocation.deviceId.size() + 1;
    for (const auto& path : invocation.paths) characters += path.wstring().size() + 1;
    characters += 1;
    std::vector<wchar_t> payload;
    payload.reserve(characters);
    payload.insert(payload.end(), invocation.deviceId.begin(), invocation.deviceId.end());
    payload.push_back(L'\0');
    for (const auto& path : invocation.paths) {
        std::wstring value = path.wstring();
        payload.insert(payload.end(), value.begin(), value.end());
        payload.push_back(L'\0');
    }
    payload.push_back(L'\0');
    return payload;
}

std::optional<Invocation> Deserialize(const void* data, size_t bytes) {
    if (!data || bytes < 3 * sizeof(wchar_t) || bytes % sizeof(wchar_t) != 0) return std::nullopt;
    const auto* text = static_cast<const wchar_t*>(data);
    size_t count = bytes / sizeof(wchar_t);
    if (text[count - 1] != L'\0') return std::nullopt;
    size_t offset = 0;
    auto take = [&]() -> std::optional<std::wstring> {
        size_t start = offset;
        while (offset < count && text[offset] != L'\0') ++offset;
        if (offset >= count) return std::nullopt;
        std::wstring value(text + start, text + offset);
        ++offset;
        return value;
    };
    auto deviceId = take();
    if (!deviceId || deviceId->empty()) return std::nullopt;
    Invocation invocation;
    invocation.deviceId = *deviceId;
    while (offset < count) {
        auto path = take();
        if (!path) return std::nullopt;
        if (path->empty()) break;
        invocation.paths.emplace_back(*path);
        if (invocation.paths.size() > 100) return std::nullopt;
    }
    if (invocation.paths.empty()) return std::nullopt;
    return invocation;
}

bool SyncShortcuts(const std::vector<DeviceEntry>& devices,
                   const std::filesystem::path& executable,
                   std::wstring* error) {
    if (devices.empty()) {
        RemoveShortcuts();
        return true;
    }
    std::filesystem::path folder = MenuFolder();
    if (folder.empty()) {
        if (error) *error = L"找不到 Windows 发送到目录";
        return false;
    }
    std::error_code filesystemError;
    if (std::filesystem::exists(folder, filesystemError) && !IsOwnedFolder(folder)) {
        if (error) *error = L"发送到目录已有同名文件夹，为避免覆盖未修改它";
        return false;
    }
    std::filesystem::create_directories(folder, filesystemError);
    if (filesystemError) {
        if (error) *error = L"无法创建相册在线设备菜单";
        return false;
    }
    if (!IsOwnedFolder(folder)) {
        std::ofstream marker(folder / kMarkerName, std::ios::binary | std::ios::trunc);
        marker << "ZwmDeviceShareHub SendTo menu v1\n";
        marker.close();
    }

    std::set<std::wstring> desired;
    std::map<std::wstring, int> duplicateNames;
    struct ShortcutSpec { std::filesystem::path path; std::wstring encodedId; };
    std::vector<ShortcutSpec> specs;
    for (const auto& device : devices) {
        std::wstring base = SanitizeFileName(device.displayName);
        int duplicate = ++duplicateNames[base];
        if (duplicate > 1) base += L" (" + std::to_wstring(duplicate) + L")";
        std::wstring fileName = base + L".lnk";
        desired.insert(fileName);
        specs.push_back({folder / fileName, EncodeDeviceId(device.id)});
    }
    for (const auto& entry : std::filesystem::directory_iterator(folder, filesystemError)) {
        if (filesystemError) break;
        if (entry.path().extension() == L".lnk" && !desired.count(entry.path().filename().wstring())) {
            std::filesystem::remove(entry.path(), filesystemError);
            filesystemError.clear();
        }
    }
    for (const auto& spec : specs) {
        if (!CreateShortcut(spec.path, executable, spec.encodedId, error)) return false;
    }
    SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_PATHW, folder.c_str(), nullptr);
    return true;
}

void RemoveShortcuts() {
    std::filesystem::path folder = MenuFolder();
    if (!IsOwnedFolder(folder)) return;
    std::error_code ignored;
    for (const auto& entry : std::filesystem::directory_iterator(folder, ignored)) {
        if (entry.path().extension() == L".lnk") std::filesystem::remove(entry.path(), ignored);
        ignored.clear();
    }
    std::filesystem::remove(folder / kMarkerName, ignored);
    ignored.clear();
    std::filesystem::remove(folder, ignored);
    if (auto root = SendToRoot()) SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_PATHW, root->c_str(), nullptr);
}

}  // namespace send_to
