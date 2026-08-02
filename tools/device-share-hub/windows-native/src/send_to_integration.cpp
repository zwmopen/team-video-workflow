#include "send_to_integration.h"

#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>

#include <algorithm>
#include <cwchar>
#include <cwctype>
#include <fstream>
#include <iterator>
#include <map>
#include <set>
#include <sstream>

namespace send_to {
namespace {

constexpr wchar_t kMenuFolderName[] = L"相册在线设备";
constexpr wchar_t kMarkerName[] = L".zwm-device-share-hub";
constexpr wchar_t kArgumentName[] = L"--send-to-device-id";
constexpr wchar_t kPickerArgumentName[] = L"--send-to-picker";
constexpr wchar_t kPickerShortcutName[] = L"发送到相册设备.lnk";

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
                    const std::wstring& arguments,
                    std::wstring* error) {
    IShellLinkW* link = nullptr;
    HRESULT status = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&link));
    if (FAILED(status) || !link) {
        if (error) *error = L"无法创建 Windows 发送到快捷方式";
        return false;
    }
    link->SetPath(executable.c_str());
    link->SetArguments(arguments.c_str());
    link->SetDescription(L"通过素材投送中控选择在线设备并发送原文件");
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
    auto pickerMarker = std::find(arguments.begin(), arguments.end(), kPickerArgumentName);
    auto deviceMarker = std::find(arguments.begin(), arguments.end(), kArgumentName);
    if (pickerMarker == arguments.end() && deviceMarker == arguments.end()) return result;
    result.requested = true;
    Invocation invocation;
    auto pathStart = arguments.end();
    if (pickerMarker != arguments.end()) {
        pathStart = std::next(pickerMarker);
    } else {
        auto encodedId = std::next(deviceMarker);
        if (encodedId == arguments.end()) {
            result.error = L"发送到菜单缺少目标设备";
            return result;
        }
        auto deviceId = DecodeDeviceId(*encodedId);
        if (!deviceId || deviceId->empty()) {
            result.error = L"发送到菜单中的设备标识无效";
            return result;
        }
        invocation.deviceId = *deviceId;
        pathStart = std::next(encodedId);
    }
    for (auto pathArgument = pathStart; pathArgument != arguments.end(); ++pathArgument) {
        std::filesystem::path path(*pathArgument);
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
    if (!deviceId) return std::nullopt;
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
    (void)devices;
    auto root = SendToRoot();
    if (!root) {
        if (error) *error = L"找不到 Windows 发送到目录";
        return false;
    }
    if (!CreateShortcut(*root / kPickerShortcutName, executable, kPickerArgumentName, error)) return false;

    // Remove the obsolete subfolder design. Windows treats a SendTo subfolder as
    // a copy destination rather than a cascading menu, so only delete files owned
    // by this app and leave any unexpected payload untouched.
    std::filesystem::path legacyFolder = MenuFolder();
    if (IsOwnedFolder(legacyFolder)) {
        std::error_code ignored;
        for (const auto& entry : std::filesystem::directory_iterator(legacyFolder, ignored)) {
            if (entry.path().extension() == L".lnk") std::filesystem::remove(entry.path(), ignored);
            ignored.clear();
        }
        std::filesystem::remove(legacyFolder / kMarkerName, ignored);
        ignored.clear();
        std::filesystem::remove(legacyFolder, ignored);
    }
    SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_PATHW, root->c_str(), nullptr);
    return true;
}

void RemoveShortcuts() {
    if (auto root = SendToRoot()) {
        std::error_code ignored;
        std::filesystem::remove(*root / kPickerShortcutName, ignored);
    }
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
