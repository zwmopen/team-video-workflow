#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <shellapi.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <objbase.h>
#include <iphlpapi.h>
#include <shobjidl.h>

#include "receiver.h"
#include "usb_transport.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iomanip>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <stdexcept>
#include <thread>
#include <vector>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "iphlpapi.lib")

namespace {
constexpr UINT WM_DEVICES_CHANGED = WM_APP + 1;
constexpr UINT WM_STATUS_CHANGED = WM_APP + 2;
constexpr UINT WM_PROGRESS_CHANGED = WM_APP + 3;
constexpr int IDC_RENAME_DEVICE = 201;
constexpr int IDC_CLEAR_DEVICE_REMARK = 202;
constexpr int IDC_TOGGLE_USB = 205;
constexpr int IDC_TOGGLE_WIFI = 206;
constexpr int IDC_TOGGLE_REMOTE = 207;
constexpr int IDC_REFRESH_DEVICES = 106;
constexpr int IDC_SEND_PICKER = 107;
constexpr int IDC_PICK_FILES = 203;
constexpr int IDC_PICK_FOLDER = 204;
constexpr int IDI_MAIN_ICON = 101;
constexpr int DISCOVERY_PORT = 45834;
constexpr wchar_t WINDOW_CLASS[] = L"ZwmDeviceShareHubWindow";
constexpr wchar_t PROMPT_CLASS[] = L"ZwmDeviceShareHubPrompt";

struct Device {
    std::wstring id;
    std::wstring name;
    std::wstring model;
    std::wstring ip;
    INTERNET_PORT port = 45833;
    std::wstring state;
    std::wstring taskId;
    int workCount = -1;
    bool usbReady = false;
    bool usbAllowed = true;
    bool wifiAllowed = true;
    bool remoteAllowed = true;
    bool remoteConnected = false;
    UsbPeer usbPeer;
    std::chrono::steady_clock::time_point lastSeen;
};

struct TransferFingerprint {
    std::filesystem::path source;
    std::wstring hash;
    uint64_t bytes = 0;
    uint64_t files = 0;
    uint64_t images = 0;
};

struct ChannelPreferences {
    bool usb = true;
    bool wifi = true;
    bool remote = true;
};

HWND gWindow = nullptr;
HWND gDeviceList = nullptr;
HWND gStatus = nullptr;
HWND gLogButton = nullptr;
HWND gRefreshButton = nullptr;
HWND gProgress = nullptr;
HWND gCancelButton = nullptr;
HWND gSendButton = nullptr;
HFONT gFont = nullptr;
HFONT gTitleFont = nullptr;
std::mutex gDeviceMutex;
std::mutex gLogMutex;
std::mutex gPreferenceMutex;
std::map<std::wstring, Device> gDevices;
std::map<std::wstring, std::wstring> gDeviceRemarks;
std::vector<Device> gDisplayedDevices;
std::atomic<bool> gRunning{true};
std::atomic<bool> gUploadInProgress{false};
std::atomic<bool> gCancelRequested{false};
std::atomic<bool> gRefreshRequested{false};
std::atomic<bool> gUsbRefreshRequested{false};
std::thread gDiscoveryThread;
std::thread gReceiverThread;
std::thread gUsbDiscoveryThread;
HANDLE gSingleInstance = nullptr;
std::filesystem::path gLogPath;
std::filesystem::path gRemarkPath;
std::filesystem::path gTransferHistoryPath;
std::filesystem::path gChannelPreferencePath;
std::map<std::wstring, UsbPeer> gUsbPeers;
std::map<std::wstring, ChannelPreferences> gChannelPreferences;

std::wstring Utf8ToWide(const std::string& value) {
    if (value.empty()) return {};
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (length <= 0) return L"";
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), result.data(), length);
    return result;
}

std::string WideToUtf8(const std::wstring& value) {
    if (value.empty()) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<size_t>(length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), length, nullptr, nullptr);
    return result;
}

std::vector<std::string> Split(const std::string& value, char delimiter) {
    std::vector<std::string> parts;
    size_t start = 0;
    while (start <= value.size()) {
        size_t end = value.find(delimiter, start);
        if (end == std::string::npos) end = value.size();
        parts.push_back(value.substr(start, end - start));
        if (end == value.size()) break;
        start = end + 1;
    }
    return parts;
}

std::string Base64UrlDecode(const std::string& input) {
    if (input.empty()) return {};
    std::string value = input;
    std::replace(value.begin(), value.end(), '-', '+');
    std::replace(value.begin(), value.end(), '_', '/');
    while (value.size() % 4 != 0) value.push_back('=');
    DWORD size = 0;
    if (!CryptStringToBinaryA(value.c_str(), static_cast<DWORD>(value.size()), CRYPT_STRING_BASE64, nullptr, &size, nullptr, nullptr)) return {};
    std::string output(size, '\0');
    if (!CryptStringToBinaryA(value.c_str(), static_cast<DWORD>(value.size()), CRYPT_STRING_BASE64,
                              reinterpret_cast<BYTE*>(output.data()), &size, nullptr, nullptr)) return {};
    output.resize(size);
    return output;
}

std::string Base64UrlEncode(const std::string& input) {
    if (input.empty()) return {};
    DWORD size = 0;
    if (!CryptBinaryToStringA(reinterpret_cast<const BYTE*>(input.data()), static_cast<DWORD>(input.size()),
                              CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, nullptr, &size)) return {};
    std::string output(size, '\0');
    if (!CryptBinaryToStringA(reinterpret_cast<const BYTE*>(input.data()), static_cast<DWORD>(input.size()),
                              CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, output.data(), &size)) return {};
    while (!output.empty() && (output.back() == '\0' || output.back() == '=')) output.pop_back();
    std::replace(output.begin(), output.end(), '+', '-');
    std::replace(output.begin(), output.end(), '/', '_');
    return output;
}

std::wstring ComputerName() {
    wchar_t value[MAX_COMPUTERNAME_LENGTH + 1]{}; DWORD size = ARRAYSIZE(value);
    return GetComputerNameW(value, &size) && size > 0 ? std::wstring(value, size) : L"我的电脑";
}

std::string WindowsDeviceId() {
    std::wstring name = ComputerName();
    return "windows-" + std::to_string(std::hash<std::wstring>{}(name));
}

std::string WindowsBeacon() {
    std::wstring name = ComputerName();
    return "ZWMDS2_HERE|2|" + WindowsDeviceId() + "|45833|" + Base64UrlEncode(WideToUtf8(name)) + "|"
        + Base64UrlEncode("Windows PC") + "|" + Base64UrlEncode("online") + "||-1";
}

std::wstring StateLabel(const std::wstring& state) {
    if (state == L"usb") return L"USB 可传";
    if (state == L"usb_pending") return L"待文件传输";
    if (state == L"receiving") return L"正在接收";
    if (state == L"ready") return L"等待手机分享";
    if (state == L"sharing") return L"已打开分享";
    return L"在线";
}

COLORREF StateColor(const std::wstring& state) {
    if (state == L"usb") return RGB(38, 112, 196);
    if (state == L"usb_pending") return RGB(184, 120, 37);
    if (state == L"receiving") return RGB(45, 122, 245);
    if (state == L"ready") return RGB(245, 160, 35);
    if (state == L"sharing") return RGB(124, 85, 220);
    return RGB(35, 170, 92);
}

void PostStatus(const std::wstring& text) {
    if (gWindow) PostMessageW(gWindow, WM_STATUS_CHANGED, 0, reinterpret_cast<LPARAM>(new std::wstring(text)));
}

void PostProgress(int percent, bool visible) {
    if (gWindow) PostMessageW(gWindow, WM_PROGRESS_CHANGED,
                              static_cast<WPARAM>(std::clamp(percent, 0, 100)), visible ? 1 : 0);
}

std::wstring NowStamp() {
    SYSTEMTIME local{};
    GetLocalTime(&local);
    wchar_t buffer[64]{};
    swprintf_s(buffer, L"%04u-%02u-%02u %02u:%02u:%02u.%03u",
               local.wYear, local.wMonth, local.wDay, local.wHour, local.wMinute, local.wSecond, local.wMilliseconds);
    return buffer;
}

std::filesystem::path DiagnosticLogPath() {
    wchar_t buffer[MAX_PATH]{};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, MAX_PATH);
    std::filesystem::path base = length > 0 ? std::filesystem::path(buffer) : std::filesystem::temp_directory_path();
    return base / L"ZwmDeviceShareHub" / L"diagnostics.log";
}

std::filesystem::path DeviceRemarkPath() {
    wchar_t buffer[MAX_PATH]{};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, MAX_PATH);
    std::filesystem::path base = length > 0 ? std::filesystem::path(buffer) : std::filesystem::temp_directory_path();
    return base / L"ZwmDeviceShareHub" / L"device-remarks.tsv";
}

std::filesystem::path TransferHistoryPath() {
    wchar_t buffer[MAX_PATH]{};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, MAX_PATH);
    std::filesystem::path base = length > 0 ? std::filesystem::path(buffer) : std::filesystem::temp_directory_path();
    return base / L"ZwmDeviceShareHub" / L"transfer-history.tsv";
}

std::filesystem::path ChannelPreferencePath() {
    wchar_t buffer[MAX_PATH]{};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, MAX_PATH);
    std::filesystem::path base = length > 0 ? std::filesystem::path(buffer) : std::filesystem::temp_directory_path();
    return base / L"ZwmDeviceShareHub" / L"device-channels.tsv";
}

void WriteDiagnosticLog(const std::wstring& event, const std::wstring& detail) {
    std::lock_guard<std::mutex> lock(gLogMutex);
    try {
        if (gLogPath.empty()) gLogPath = DiagnosticLogPath();
        std::filesystem::create_directories(gLogPath.parent_path());
        if (std::filesystem::exists(gLogPath) && std::filesystem::file_size(gLogPath) > 512ull * 1024ull) {
            std::filesystem::path oldPath = gLogPath;
            oldPath += L".old";
            std::error_code ignored;
            std::filesystem::remove(oldPath, ignored);
            std::filesystem::rename(gLogPath, oldPath, ignored);
        }
        std::ofstream output(gLogPath, std::ios::binary | std::ios::app);
        std::wstring line = NowStamp() + L" | " + event + L" | " + detail + L"\n";
        std::string bytes = WideToUtf8(line);
        output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    } catch (...) {
    }
}

void LoadDeviceRemarks() {
    try {
        if (gRemarkPath.empty()) gRemarkPath = DeviceRemarkPath();
        gDeviceRemarks.clear();
        std::ifstream input(gRemarkPath, std::ios::binary);
        std::string line;
        while (std::getline(input, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            size_t tab = line.find('\t');
            if (tab == std::string::npos) continue;
            std::wstring id = Utf8ToWide(line.substr(0, tab));
            std::wstring remark = Utf8ToWide(line.substr(tab + 1));
            if (!id.empty() && !remark.empty()) gDeviceRemarks[id] = remark;
        }
    } catch (...) {
        WriteDiagnosticLog(L"remarks_load_failed", L"ignored");
    }
}

void SaveDeviceRemarks() {
    try {
        if (gRemarkPath.empty()) gRemarkPath = DeviceRemarkPath();
        std::filesystem::create_directories(gRemarkPath.parent_path());
        std::ofstream output(gRemarkPath, std::ios::binary | std::ios::trunc);
        for (const auto& item : gDeviceRemarks) {
            output << WideToUtf8(item.first) << '\t' << WideToUtf8(item.second) << '\n';
        }
        WriteDiagnosticLog(L"remarks_saved", L"count=" + std::to_wstring(gDeviceRemarks.size()));
    } catch (...) {
        WriteDiagnosticLog(L"remarks_save_failed", L"ignored");
    }
}

std::wstring DisplayNameFor(const Device& device) {
    auto found = gDeviceRemarks.find(device.id);
    if (found != gDeviceRemarks.end() && !found->second.empty()) return found->second;
    return device.name;
}

struct PromptState {
    std::wstring title;
    std::wstring label;
    std::wstring value;
    std::wstring result;
    bool accepted = false;
    HWND edit = nullptr;
};

LRESULT CALLBACK PromptProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* state = reinterpret_cast<PromptState*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
        case WM_CREATE: {
            auto* create = reinterpret_cast<CREATESTRUCTW*>(lParam);
            state = reinterpret_cast<PromptState*>(create->lpCreateParams);
            SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(state));
            HFONT font = gFont ? gFont : reinterpret_cast<HFONT>(GetStockObject(DEFAULT_GUI_FONT));
            HWND label = CreateWindowW(L"STATIC", state->label.c_str(), WS_CHILD | WS_VISIBLE,
                                       18, 18, 316, 24, window, nullptr, nullptr, nullptr);
            SendMessageW(label, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            state->edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", state->value.c_str(),
                                          WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL,
                                          18, 48, 316, 28, window, reinterpret_cast<HMENU>(301), nullptr, nullptr);
            SendMessageW(state->edit, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            HWND ok = CreateWindowW(L"BUTTON", L"保存", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
                                    152, 92, 84, 32, window, reinterpret_cast<HMENU>(IDOK), nullptr, nullptr);
            HWND cancel = CreateWindowW(L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                        250, 92, 84, 32, window, reinterpret_cast<HMENU>(IDCANCEL), nullptr, nullptr);
            SendMessageW(ok, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(cancel, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(state->edit, EM_SETSEL, 0, -1);
            SetFocus(state->edit);
            return 0;
        }
        case WM_COMMAND:
            if (LOWORD(wParam) == IDOK && state) {
                int length = GetWindowTextLengthW(state->edit);
                std::wstring text(static_cast<size_t>(length) + 1, L'\0');
                GetWindowTextW(state->edit, text.data(), length + 1);
                text.resize(static_cast<size_t>(length));
                state->result = text;
                state->accepted = true;
                DestroyWindow(window);
                return 0;
            }
            if (LOWORD(wParam) == IDCANCEL) {
                DestroyWindow(window);
                return 0;
            }
            break;
        case WM_CLOSE:
            DestroyWindow(window);
            return 0;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

std::wstring Trim(const std::wstring& value) {
    size_t first = 0;
    while (first < value.size() && iswspace(value[first])) ++first;
    size_t last = value.size();
    while (last > first && iswspace(value[last - 1])) --last;
    return value.substr(first, last - first);
}

std::optional<std::wstring> PromptForText(HWND parent, const std::wstring& title,
                                          const std::wstring& label, const std::wstring& value) {
    HINSTANCE instance = reinterpret_cast<HINSTANCE>(GetModuleHandleW(nullptr));
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW cls{};
        cls.cbSize = sizeof(cls);
        cls.lpfnWndProc = PromptProc;
        cls.hInstance = instance;
        cls.hCursor = LoadCursorW(nullptr, IDC_IBEAM);
        cls.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
        cls.lpszClassName = PROMPT_CLASS;
        RegisterClassExW(&cls);
        registered = true;
    }

    PromptState state{title, label, value};
    RECT parentRect{};
    GetWindowRect(parent, &parentRect);
    int width = 370;
    int height = 172;
    int x = parentRect.left + ((parentRect.right - parentRect.left) - width) / 2;
    int y = parentRect.top + ((parentRect.bottom - parentRect.top) - height) / 2;
    EnableWindow(parent, FALSE);
    HWND prompt = CreateWindowExW(WS_EX_DLGMODALFRAME, PROMPT_CLASS, title.c_str(),
                                  WS_CAPTION | WS_POPUP | WS_SYSMENU,
                                  x, y, width, height, parent, nullptr, instance, &state);
    ShowWindow(prompt, SW_SHOW);
    UpdateWindow(prompt);

    MSG message{};
    while (IsWindow(prompt) && GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(prompt, &message)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    EnableWindow(parent, TRUE);
    SetForegroundWindow(parent);
    if (!state.accepted) return std::nullopt;
    return Trim(state.result);
}

std::wstring FormatBytes(uintmax_t bytes) {
    const wchar_t* units[] = {L"B", L"KB", L"MB", L"GB"};
    double value = static_cast<double>(bytes);
    int unit = 0;
    while (value >= 1024.0 && unit < 3) {
        value /= 1024.0;
        ++unit;
    }
    wchar_t buffer[64]{};
    if (unit == 0) swprintf_s(buffer, L"%llu %s", static_cast<unsigned long long>(bytes), units[unit]);
    else swprintf_s(buffer, L"%.1f %s", value, units[unit]);
    return buffer;
}

void WriteLe16(std::ostream& output, uint16_t value) {
    const char bytes[] = {static_cast<char>(value & 0xff), static_cast<char>((value >> 8) & 0xff)};
    output.write(bytes, sizeof(bytes));
}

void WriteLe32(std::ostream& output, uint32_t value) {
    const char bytes[] = {
        static_cast<char>(value & 0xff), static_cast<char>((value >> 8) & 0xff),
        static_cast<char>((value >> 16) & 0xff), static_cast<char>((value >> 24) & 0xff)};
    output.write(bytes, sizeof(bytes));
}

uint32_t Crc32File(const std::filesystem::path& file) {
    std::ifstream input(file, std::ios::binary);
    if (!input) throw std::runtime_error("无法读取文件夹中的文件");
    uint32_t crc = 0xffffffffu;
    std::vector<char> buffer(1024 * 1024);
    while (input) {
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        std::streamsize count = input.gcount();
        for (std::streamsize index = 0; index < count; ++index) {
            crc ^= static_cast<unsigned char>(buffer[static_cast<size_t>(index)]);
            for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
        }
    }
    return crc ^ 0xffffffffu;
}

struct ZipRecord {
    std::string name;
    uint32_t crc = 0;
    uint32_t size = 0;
    uint32_t offset = 0;
};

std::filesystem::path CreateFolderZip(const std::filesystem::path& folder, const std::wstring& taskId) {
    PostStatus(L"正在整理文件夹 “" + folder.filename().wstring() + L"”…");
    std::vector<std::filesystem::path> files;
    for (const auto& item : std::filesystem::recursive_directory_iterator(
             folder, std::filesystem::directory_options::skip_permission_denied)) {
        if (!item.is_regular_file()) continue;
        files.push_back(item.path());
        if (files.size() > 10000) throw std::runtime_error("文件夹中的文件超过 10000 个，请分批传送");
    }
    if (files.empty()) throw std::runtime_error("拖入的文件夹是空的");
    std::sort(files.begin(), files.end());

    std::filesystem::path archive = std::filesystem::temp_directory_path() / (L"album-folder-" + taskId + L".zip");
    std::ofstream output(archive, std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("无法创建文件夹传送包");
    std::vector<ZipRecord> records;
    for (const auto& file : files) {
        uintmax_t size64 = std::filesystem::file_size(file);
        if (size64 > 0xffffffffull) throw std::runtime_error("文件夹中有超过 4GB 的单个文件");
        std::filesystem::path relative = std::filesystem::relative(file, folder);
        std::wstring logicalWide = folder.filename().wstring() + L"/" + relative.generic_wstring();
        std::string logicalName = WideToUtf8(logicalWide);
        std::replace(logicalName.begin(), logicalName.end(), '\\', '/');
        if (logicalName.size() > 65535) throw std::runtime_error("文件夹中的路径过长");
        std::streampos offset = output.tellp();
        std::streamoff offsetValue = static_cast<std::streamoff>(offset);
        if (offsetValue < 0 || static_cast<uint64_t>(offsetValue) > 0xffffffffull) throw std::runtime_error("文件夹传送包超过 4GB");
        ZipRecord record{logicalName, Crc32File(file), static_cast<uint32_t>(size64), static_cast<uint32_t>(offsetValue)};
        WriteLe32(output, 0x04034b50u);
        WriteLe16(output, 20); WriteLe16(output, 0x0800); WriteLe16(output, 0);
        WriteLe16(output, 0); WriteLe16(output, 0);
        WriteLe32(output, record.crc); WriteLe32(output, record.size); WriteLe32(output, record.size);
        WriteLe16(output, static_cast<uint16_t>(record.name.size())); WriteLe16(output, 0);
        output.write(record.name.data(), static_cast<std::streamsize>(record.name.size()));
        std::ifstream input(file, std::ios::binary);
        std::vector<char> buffer(1024 * 1024);
        while (input) {
            input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            output.write(buffer.data(), input.gcount());
        }
        if (!output) throw std::runtime_error("创建文件夹传送包失败");
        records.push_back(record);
    }
    std::streampos centralOffsetPos = output.tellp();
    std::streamoff centralOffsetValue = static_cast<std::streamoff>(centralOffsetPos);
    if (centralOffsetValue < 0 || static_cast<uint64_t>(centralOffsetValue) > 0xffffffffull) throw std::runtime_error("文件夹传送包超过 4GB");
    uint32_t centralOffset = static_cast<uint32_t>(centralOffsetValue);
    for (const ZipRecord& record : records) {
        WriteLe32(output, 0x02014b50u);
        WriteLe16(output, 20); WriteLe16(output, 20); WriteLe16(output, 0x0800); WriteLe16(output, 0);
        WriteLe16(output, 0); WriteLe16(output, 0);
        WriteLe32(output, record.crc); WriteLe32(output, record.size); WriteLe32(output, record.size);
        WriteLe16(output, static_cast<uint16_t>(record.name.size())); WriteLe16(output, 0); WriteLe16(output, 0);
        WriteLe16(output, 0); WriteLe16(output, 0); WriteLe32(output, 0); WriteLe32(output, record.offset);
        output.write(record.name.data(), static_cast<std::streamsize>(record.name.size()));
    }
    std::streampos endPos = output.tellp();
    std::streamoff endValue = static_cast<std::streamoff>(endPos);
    if (records.size() > 65535 || endValue < 0 || static_cast<uint64_t>(endValue) > 0xffffffffull) {
        throw std::runtime_error("文件夹中的文件过多或总大小超过 4GB");
    }
    uint32_t centralSize = static_cast<uint32_t>(endValue) - centralOffset;
    WriteLe32(output, 0x06054b50u);
    WriteLe16(output, 0); WriteLe16(output, 0);
    WriteLe16(output, static_cast<uint16_t>(records.size()));
    WriteLe16(output, static_cast<uint16_t>(records.size()));
    WriteLe32(output, centralSize); WriteLe32(output, centralOffset); WriteLe16(output, 0);
    output.close();
    if (!output) throw std::runtime_error("保存文件夹传送包失败");
    WriteDiagnosticLog(L"folder_packed", folder.wstring() + L" files=" + std::to_wstring(records.size()));
    return archive;
}

std::wstring LastNetworkError(const std::wstring& fallback) {
    DWORD code = GetLastError();
    if (code == 0) return fallback;
    if (code == ERROR_WINHTTP_TIMEOUT || code == ERROR_WINHTTP_CANNOT_CONNECT) {
        return L"连接手机超时。请打开手机“相册”，确认仍连接同一 Wi‑Fi 后重试（"
                + std::to_wstring(code) + L"）";
    }
    wchar_t* message = nullptr;
    DWORD length = FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                                  nullptr, code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
                                  reinterpret_cast<LPWSTR>(&message), 0, nullptr);
    std::wstring result = fallback + L"（" + std::to_wstring(code);
    if (length > 0 && message) {
        std::wstring text(message, length);
        while (!text.empty() && (text.back() == L'\r' || text.back() == L'\n' || text.back() == L' ')) text.pop_back();
        result += L"：" + text;
    }
    result += L"）";
    if (message) LocalFree(message);
    return result;
}

std::string JsonEscape(const std::wstring& text) {
    std::string input = WideToUtf8(text);
    std::string output;
    output.reserve(input.size() + 32);
    for (unsigned char ch : input) {
        switch (ch) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\b': output += "\\b"; break;
            case '\f': output += "\\f"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (ch < 0x20) {
                    char buffer[7];
                    sprintf_s(buffer, "\\u%04x", ch);
                    output += buffer;
                } else {
                    output.push_back(static_cast<char>(ch));
                }
        }
    }
    return output;
}

std::string PercentEncodeUtf8(const std::wstring& value) {
    static constexpr char hex[] = "0123456789ABCDEF";
    std::string input = WideToUtf8(value);
    std::string output;
    output.reserve(input.size() * 3);
    for (unsigned char ch : input) {
        bool safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.' || ch == '~';
        if (safe) output.push_back(static_cast<char>(ch));
        else {
            output.push_back('%');
            output.push_back(hex[ch >> 4]);
            output.push_back(hex[ch & 15]);
        }
    }
    return output;
}

std::wstring MimeForPath(const std::filesystem::path& path) {
    std::wstring ext = path.extension().wstring();
    std::transform(ext.begin(), ext.end(), ext.begin(), [](wchar_t ch) { return static_cast<wchar_t>(std::towlower(ch)); });
    if (ext == L".jpg" || ext == L".jpeg") return L"image/jpeg";
    if (ext == L".png") return L"image/png";
    if (ext == L".webp") return L"image/webp";
    if (ext == L".gif") return L"image/gif";
    if (ext == L".heic" || ext == L".heif") return L"image/heic";
    if (ext == L".mp4") return L"video/mp4";
    if (ext == L".mov") return L"video/quicktime";
    if (ext == L".mkv") return L"video/x-matroska";
    return L"application/octet-stream";
}

std::wstring NewTaskId() {
    GUID guid{};
    CoCreateGuid(&guid);
    wchar_t buffer[64]{};
    swprintf_s(buffer, L"task-%08x%04x%04x%04x%012llx",
               guid.Data1, guid.Data2, guid.Data3,
               (guid.Data4[0] << 8) | guid.Data4[1],
               (static_cast<unsigned long long>(guid.Data4[2]) << 40) |
               (static_cast<unsigned long long>(guid.Data4[3]) << 32) |
               (static_cast<unsigned long long>(guid.Data4[4]) << 24) |
               (static_cast<unsigned long long>(guid.Data4[5]) << 16) |
               (static_cast<unsigned long long>(guid.Data4[6]) << 8) |
               static_cast<unsigned long long>(guid.Data4[7]));
    return buffer;
}

std::wstring Sha256File(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    DWORD objectLength = 0;
    DWORD hashLength = 0;
    DWORD received = 0;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) throw std::runtime_error("SHA-256 初始化失败");
    auto closeAlgorithm = [&]() { if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0); };
    if (BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectLength), sizeof(objectLength), &received, 0) != 0 ||
        BCryptGetProperty(algorithm, BCRYPT_HASH_LENGTH, reinterpret_cast<PUCHAR>(&hashLength), sizeof(hashLength), &received, 0) != 0) {
        closeAlgorithm();
        throw std::runtime_error("SHA-256 属性读取失败");
    }
    std::vector<UCHAR> object(objectLength);
    std::vector<UCHAR> digest(hashLength);
    if (BCryptCreateHash(algorithm, &hash, object.data(), objectLength, nullptr, 0, 0) != 0) {
        closeAlgorithm();
        throw std::runtime_error("SHA-256 创建失败");
    }
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        BCryptDestroyHash(hash);
        closeAlgorithm();
        throw std::runtime_error("无法读取文件");
    }
    std::vector<char> buffer(1024 * 1024);
    while (input) {
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        std::streamsize count = input.gcount();
        if (count > 0 && BCryptHashData(hash, reinterpret_cast<PUCHAR>(buffer.data()), static_cast<ULONG>(count), 0) != 0) {
            BCryptDestroyHash(hash);
            closeAlgorithm();
            throw std::runtime_error("SHA-256 计算失败");
        }
    }
    if (BCryptFinishHash(hash, digest.data(), hashLength, 0) != 0) {
        BCryptDestroyHash(hash);
        closeAlgorithm();
        throw std::runtime_error("SHA-256 完成失败");
    }
    BCryptDestroyHash(hash);
    closeAlgorithm();
    std::wostringstream out;
    out << std::hex << std::setfill(L'0');
    for (UCHAR byte : digest) out << std::setw(2) << static_cast<int>(byte);
    return out.str();
}

std::wstring HardwareFamily(const std::wstring& name, const std::wstring& model) {
    std::wstring value = name + L" " + model;
    std::transform(value.begin(), value.end(), value.begin(), towlower);
    value.erase(std::remove_if(value.begin(), value.end(), [](wchar_t character) {
        return !iswalnum(character);
    }), value.end());
    if (value.find(L"23013rk75c") != std::wstring::npos
        || value.find(L"redmik60") != std::wstring::npos
        || value.find(L"xiaomik60") != std::wstring::npos
        || value.find(L"mondrian") != std::wstring::npos) {
        return L"xiaomi-k60";
    }
    if (value.find(L"m2006c3lc") != std::wstring::npos
        || value.find(L"redmi9a") != std::wstring::npos
        || value.find(L"rmi9a") != std::wstring::npos
        || value.find(L"dandelion") != std::wstring::npos) {
        return L"xiaomi-9a";
    }
    if (value.find(L"23124rn87c") != std::wstring::npos
        || value.find(L"redmi13") != std::wstring::npos) {
        return L"xiaomi-redmi-13";
    }
    return L"";
}

void SaveChannelPreferencesUnlocked() {
    try {
        if (gChannelPreferencePath.empty()) gChannelPreferencePath = ChannelPreferencePath();
        std::filesystem::create_directories(gChannelPreferencePath.parent_path());
        std::ofstream output(gChannelPreferencePath, std::ios::binary | std::ios::trunc);
        for (const auto& item : gChannelPreferences) {
            output << WideToUtf8(item.first)
                   << '\t' << (item.second.usb ? "1" : "0")
                   << '\t' << (item.second.wifi ? "1" : "0")
                   << '\t' << (item.second.remote ? "1" : "0") << '\n';
        }
    } catch (...) {
        WriteDiagnosticLog(L"channel_preferences_save_failed", L"ignored");
    }
}

void LoadChannelPreferences() {
    std::lock_guard<std::mutex> lock(gPreferenceMutex);
    try {
        if (gChannelPreferencePath.empty()) gChannelPreferencePath = ChannelPreferencePath();
        gChannelPreferences.clear();
        std::ifstream input(gChannelPreferencePath, std::ios::binary);
        std::string line;
        while (std::getline(input, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            auto parts = Split(line, '\t');
            if (parts.size() < 4) continue;
            std::wstring id = Utf8ToWide(parts[0]);
            if (!id.empty()) {
                gChannelPreferences[id] = ChannelPreferences{
                    parts[1] != "0", parts[2] != "0", parts[3] != "0"
                };
            }
        }
    } catch (...) {
        WriteDiagnosticLog(L"channel_preferences_load_failed", L"ignored");
    }
}

ChannelPreferences ChannelsFor(const std::wstring& deviceId, bool* created = nullptr) {
    std::lock_guard<std::mutex> lock(gPreferenceMutex);
    auto found = gChannelPreferences.find(deviceId);
    if (found != gChannelPreferences.end()) {
        if (created) *created = false;
        return found->second;
    }
    if (!deviceId.empty()) {
        gChannelPreferences[deviceId] = ChannelPreferences{};
        SaveChannelPreferencesUnlocked();
        if (created) *created = true;
    } else if (created) {
        *created = false;
    }
    return ChannelPreferences{};
}

bool IsImagePath(const std::filesystem::path& path) {
    std::wstring extension = path.extension().wstring();
    std::transform(extension.begin(), extension.end(), extension.begin(), towlower);
    static const std::set<std::wstring> extensions = {
        L".jpg", L".jpeg", L".png", L".webp", L".gif", L".bmp", L".heic", L".heif"
    };
    return extensions.count(extension) > 0;
}

TransferFingerprint FingerprintItem(const std::filesystem::path& source) {
    TransferFingerprint result;
    result.source = source;
    if (std::filesystem::is_regular_file(source)) {
        result.hash = Sha256File(source);
        result.bytes = std::filesystem::file_size(source);
        result.files = 1;
        result.images = IsImagePath(source) ? 1 : 0;
        return result;
    }
    if (!std::filesystem::is_directory(source)) throw std::runtime_error("项目不存在");

    std::vector<std::filesystem::path> files;
    for (const auto& item : std::filesystem::recursive_directory_iterator(
             source, std::filesystem::directory_options::skip_permission_denied)) {
        if (item.is_regular_file()) files.push_back(item.path());
    }
    std::sort(files.begin(), files.end(), [&](const auto& left, const auto& right) {
        return std::filesystem::relative(left, source).generic_wstring()
             < std::filesystem::relative(right, source).generic_wstring();
    });

    std::filesystem::path manifest = std::filesystem::temp_directory_path()
        / (L"album-fingerprint-" + NewTaskId() + L".txt");
    try {
        std::ofstream output(manifest, std::ios::binary | std::ios::trunc);
        if (!output) throw std::runtime_error("无法建立内容指纹");
        for (const auto& file : files) {
            std::wstring relative = std::filesystem::relative(file, source).generic_wstring();
            uint64_t size = std::filesystem::file_size(file);
            std::wstring fileHash = Sha256File(file);
            std::string line = WideToUtf8(relative) + "\t" + std::to_string(size)
                + "\t" + WideToUtf8(fileHash) + "\n";
            output.write(line.data(), static_cast<std::streamsize>(line.size()));
            result.bytes += size;
            result.files++;
            if (IsImagePath(file)) result.images++;
        }
        output.close();
        result.hash = Sha256File(manifest);
    } catch (...) {
        std::error_code ignored;
        std::filesystem::remove(manifest, ignored);
        throw;
    }
    std::error_code ignored;
    std::filesystem::remove(manifest, ignored);
    return result;
}

std::wstring SafeHistoryField(std::wstring value) {
    std::replace(value.begin(), value.end(), L'\t', L' ');
    std::replace(value.begin(), value.end(), L'\r', L' ');
    std::replace(value.begin(), value.end(), L'\n', L' ');
    return value;
}

std::map<std::wstring, std::wstring> PreviousTransfersForDevice(
        const std::wstring& deviceId, const std::wstring& deviceName) {
    std::map<std::wstring, std::wstring> result;
    if (gTransferHistoryPath.empty()) gTransferHistoryPath = TransferHistoryPath();
    std::ifstream input(gTransferHistoryPath, std::ios::binary);
    std::string line;
    while (std::getline(input, line)) {
        auto fields = Split(line, '\t');
        if (fields.size() < 5) continue;
        if (Utf8ToWide(fields[1]) != deviceId
                && _wcsicmp(Utf8ToWide(fields[3]).c_str(), deviceName.c_str()) != 0) continue;
        result[Utf8ToWide(fields[0])] = Utf8ToWide(fields[2]) + L"|" + Utf8ToWide(fields[3]);
    }
    return result;
}

void RecordSuccessfulTransfers(
        const Device& device, const std::vector<TransferFingerprint>& fingerprints,
        const std::wstring& channel) {
    if (gTransferHistoryPath.empty()) gTransferHistoryPath = TransferHistoryPath();
    std::filesystem::create_directories(gTransferHistoryPath.parent_path());
    std::ofstream output(gTransferHistoryPath, std::ios::binary | std::ios::app);
    std::wstring stamp = NowStamp();
    for (const auto& item : fingerprints) {
        std::wstring line = item.hash + L"\t" + SafeHistoryField(device.id) + L"\t"
            + stamp + L"\t" + SafeHistoryField(device.name) + L"\t"
            + SafeHistoryField(item.source.filename().wstring()) + L"\t"
            + channel + L"\t" + std::to_wstring(item.files) + L"\t"
            + std::to_wstring(item.images) + L"\n";
        std::string bytes = WideToUtf8(line);
        output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    }
}

std::vector<TransferFingerprint> CheckTransferHistory(
        const Device& device, const std::vector<std::filesystem::path>& inputs) {
    std::vector<TransferFingerprint> fingerprints;
    PostStatus(L"正在核对是否传送过…");
    for (const auto& input : inputs) fingerprints.push_back(FingerprintItem(input));
    auto previous = PreviousTransfersForDevice(device.id, device.name);
    std::vector<size_t> duplicates;
    for (size_t index = 0; index < fingerprints.size(); ++index) {
        if (previous.count(fingerprints[index].hash)) duplicates.push_back(index);
    }
    if (duplicates.empty()) return fingerprints;

    std::wostringstream message;
    message << L"发现 " << duplicates.size() << L" 个项目以前已经传给“"
            << device.name << L"”。\n\n";
    size_t shown = 0;
    for (size_t index : duplicates) {
        if (shown++ >= 5) break;
        std::wstring detail = previous[fingerprints[index].hash];
        size_t separator = detail.find(L'|');
        message << L"• " << fingerprints[index].source.filename().wstring();
        if (separator != std::wstring::npos) message << L"（" << detail.substr(0, separator) << L"）";
        message << L"\n";
    }
    if (duplicates.size() > shown) message << L"• 以及另外 " << (duplicates.size() - shown) << L" 个\n";
    message << L"\n本次默认不再传送。是否仍然重新传送这些项目？";
    int choice = MessageBoxW(gWindow, message.str().c_str(), L"检测到重复传送",
                             MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2);
    if (choice == IDYES) return fingerprints;

    std::set<size_t> duplicateSet(duplicates.begin(), duplicates.end());
    std::vector<TransferFingerprint> retained;
    for (size_t index = 0; index < fingerprints.size(); ++index) {
        if (!duplicateSet.count(index)) retained.push_back(fingerprints[index]);
    }
    return retained;
}

class HttpClient {
public:
    HttpClient(const std::wstring& host, INTERNET_PORT port) {
        session_ = WinHttpOpen(L"ZwmDeviceShare/0.2", WINHTTP_ACCESS_TYPE_NO_PROXY,
                               WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session_) throw std::runtime_error(WideToUtf8(LastNetworkError(L"无法初始化网络连接")));
        WinHttpSetTimeouts(session_, 5000, 5000, 15000, 60000);
        DWORD connectRetries = 0;
        WinHttpSetOption(session_, WINHTTP_OPTION_CONNECT_RETRIES, &connectRetries, sizeof(connectRetries));
        connection_ = WinHttpConnect(session_, host.c_str(), port, 0);
        if (!connection_) {
            WinHttpCloseHandle(session_);
            throw std::runtime_error(WideToUtf8(LastNetworkError(L"无法连接手机")));
        }
    }

    ~HttpClient() {
        if (connection_) WinHttpCloseHandle(connection_);
        if (session_) WinHttpCloseHandle(session_);
    }

    void PostJson(const std::wstring& path, const std::string& json) {
        std::wstring headers = L"Content-Type: application/json; charset=utf-8\r\nExpect:\r\n";
        SendMemory(L"POST", path, headers, reinterpret_cast<const BYTE*>(json.data()), static_cast<DWORD>(json.size()));
    }

    void PostEmpty(const std::wstring& path) {
        SendMemory(L"POST", path, L"Content-Type: text/plain\r\n", nullptr, 0);
    }

    void PutFile(const std::wstring& path, const std::filesystem::path& file, const std::wstring& mime, const std::wstring& sha256,
                 const std::function<void(uintmax_t, uintmax_t)>& onProgress) {
        uintmax_t size64 = std::filesystem::file_size(file);
        if (size64 > 0xFFFFFFFFull) throw std::runtime_error("单个文件暂不支持超过 4GB");
        std::wstring encodedName = Utf8ToWide(PercentEncodeUtf8(file.filename().wstring()));
        std::wstring headers = L"Content-Type: application/octet-stream\r\n"
            L"Expect:\r\n"
            L"X-File-Name: " + encodedName + L"\r\n"
            L"X-File-Mime: " + mime + L"\r\n"
            L"X-File-Sha256: " + sha256 + L"\r\n";
        HINTERNET request = WinHttpOpenRequest(connection_, L"PUT", path.c_str(), nullptr,
                                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
        if (!request) throw std::runtime_error(WideToUtf8(LastNetworkError(L"无法创建上传请求")));
        BOOL ok = WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1L),
                                     WINHTTP_NO_REQUEST_DATA, 0, static_cast<DWORD>(size64), 0);
        if (!ok) {
            std::wstring error = LastNetworkError(L"手机拒绝建立上传连接");
            WinHttpCloseHandle(request);
            throw std::runtime_error(WideToUtf8(error));
        }
        std::ifstream input(file, std::ios::binary);
        if (!input) {
            WinHttpCloseHandle(request);
            throw std::runtime_error("无法打开本地文件");
        }
        std::vector<char> buffer(1024 * 1024);
        uintmax_t sent = 0;
        while (input) {
            input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            DWORD count = static_cast<DWORD>(input.gcount());
            if (count == 0) continue;
            DWORD written = 0;
            if (!WinHttpWriteData(request, buffer.data(), count, &written) || written != count) {
                std::wstring error = LastNetworkError(L"上传文件中断");
                WinHttpCloseHandle(request);
                throw std::runtime_error(WideToUtf8(error));
            }
            sent += written;
            if (onProgress) onProgress(sent, size64);
        }
        CheckResponse(request);
        WinHttpCloseHandle(request);
    }

private:
    HINTERNET session_ = nullptr;
    HINTERNET connection_ = nullptr;

    void SendMemory(const wchar_t* method, const std::wstring& path, const std::wstring& headers,
                    const BYTE* data, DWORD size) {
        HINTERNET request = WinHttpOpenRequest(connection_, method, path.c_str(), nullptr,
                                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
        if (!request) throw std::runtime_error(WideToUtf8(LastNetworkError(L"无法创建请求")));
        BOOL ok = WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1L),
                                     WINHTTP_NO_REQUEST_DATA, 0, size, 0);
        if (!ok) {
            std::wstring error = LastNetworkError(L"网络发送失败");
            WinHttpCloseHandle(request);
            throw std::runtime_error(WideToUtf8(error));
        }
        if (size > 0) {
            DWORD written = 0;
            if (!WinHttpWriteData(request, data, size, &written) || written != size) {
                std::wstring error = LastNetworkError(L"请求内容发送失败");
                WinHttpCloseHandle(request);
                throw std::runtime_error(WideToUtf8(error));
            }
        }
        CheckResponse(request);
        WinHttpCloseHandle(request);
    }

    static void CheckResponse(HINTERNET request) {
        if (!WinHttpReceiveResponse(request, nullptr)) throw std::runtime_error(WideToUtf8(LastNetworkError(L"没有收到手机响应")));
        DWORD status = 0;
        DWORD size = sizeof(status);
        if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                 WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX)) {
            throw std::runtime_error("无法读取手机响应");
        }
        if (status < 200 || status >= 300) {
            if (status == 409) throw std::runtime_error("手机上还有一批素材未处理");
            std::wstring body = ReadResponseText(request);
            std::wstring message = L"手机返回错误 " + std::to_wstring(status);
            if (!body.empty()) message += L"：" + body;
            throw std::runtime_error(WideToUtf8(message));
        }
    }

    static std::wstring ReadResponseText(HINTERNET request) {
        std::string body;
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0 && body.size() < 4096) {
            DWORD toRead = std::min<DWORD>(available, 4096 - static_cast<DWORD>(body.size()));
            std::string chunk(toRead, '\0');
            DWORD read = 0;
            if (!WinHttpReadData(request, chunk.data(), toRead, &read) || read == 0) break;
            chunk.resize(read);
            body += chunk;
        }
        std::wstring text = Utf8ToWide(body);
        text.erase(std::remove(text.begin(), text.end(), L'\r'), text.end());
        std::replace(text.begin(), text.end(), L'\n', L' ');
        if (text.size() > 180) text.resize(180);
        return text;
    }
};

void UploadToDevice(Device device, std::vector<std::filesystem::path> files, std::wstring caption) {
    std::wstring taskId = NewTaskId();
    gUploadInProgress = true;
    gCancelRequested = false;
    PostProgress(0, true);
    std::vector<std::filesystem::path> temporaryArchives;
    try {
        std::vector<TransferFingerprint> fingerprints = CheckTransferHistory(device, files);
        files.clear();
        for (const auto& item : fingerprints) files.push_back(item.source);
        if (files.empty()) {
            PostProgress(0, false);
            PostStatus(L"已跳过传送过的项目");
            gUploadInProgress = false;
            gCancelRequested = false;
            return;
        }

        if (device.usbReady && device.usbAllowed) {
            try {
                WriteDiagnosticLog(L"usb_upload_start",
                        device.name + L" items=" + std::to_wstring(files.size()));
                SendItemsOverUsb(device.usbPeer, files, gCancelRequested,
                    [&](const std::wstring& status) { PostStatus(status); },
                    [&](uint64_t sent, uint64_t total) {
                        int percent = total == 0 ? 100 : static_cast<int>(
                            std::min<uint64_t>(100, sent * 100 / total));
                        PostProgress(percent, true);
                    });
                RecordSuccessfulTransfers(device, fingerprints, L"USB");
                WriteDiagnosticLog(L"usb_upload_done", device.name);
                PostProgress(100, true);
                PostStatus(L"已通过 USB 传送到“" + device.name + L"”");
                gUploadInProgress = false;
                gCancelRequested = false;
                return;
            } catch (const std::exception& usbError) {
                WriteDiagnosticLog(L"usb_upload_failed", Utf8ToWide(usbError.what()));
                if (device.ip.empty() || !device.wifiAllowed) throw;
                PostStatus(L"USB 暂不可用，正在自动改用 Wi‑Fi…");
            }
        }

        if (device.ip.empty() || !device.wifiAllowed) {
            throw std::runtime_error("这台设备当前没有打开可用的传送方式，请右键设备检查 USB、WiFi 或远程传送");
        }

        for (size_t inputIndex = 0; inputIndex < files.size(); ++inputIndex) {
            auto& input = files[inputIndex];
            if (std::filesystem::is_directory(input)) {
                input = CreateFolderZip(input, taskId + L"-" + std::to_wstring(inputIndex));
                temporaryArchives.push_back(input);
            }
        }
        uintmax_t totalBytes = 0;
        for (const auto& file : files) totalBytes += std::filesystem::file_size(file);
        std::wstring displayName = DisplayNameFor(device);
        WriteDiagnosticLog(L"upload_start", device.name + L" display=" + displayName + L" ip=" + device.ip + L" files=" + std::to_wstring(files.size()) + L" bytes=" + std::to_wstring(totalBytes));
        PostStatus(L"正在连接 “" + device.name + L"”…");
        HttpClient client(device.ip, device.port);
        std::ostringstream json;
        json << "{\"taskId\":\"" << WideToUtf8(taskId) << "\",\"text\":\""
             << JsonEscape(caption) << "\",\"fileCount\":" << files.size() << "}";
        client.PostJson(L"/v2/tasks", json.str());
        uintmax_t completedBytes = 0;
        auto lastNotice = std::chrono::steady_clock::now() - std::chrono::seconds(1);
        auto uploadStart = std::chrono::steady_clock::now();
        for (size_t index = 0; index < files.size(); ++index) {
            if (gCancelRequested) throw std::runtime_error("传送已取消");
            std::wstring fileName = files[index].filename().wstring();
            WriteDiagnosticLog(L"file_start", fileName + L" size=" + std::to_wstring(std::filesystem::file_size(files[index])));
            PostStatus(L"正在传给 “" + device.name + L"”：第 " + std::to_wstring(index + 1) + L"/" + std::to_wstring(files.size()) + L" 个");
            std::wstring sha = Sha256File(files[index]);
            std::wstring route = L"/v2/tasks/" + taskId + L"/files/" + std::to_wstring(index);
            uintmax_t fileSize = std::filesystem::file_size(files[index]);
            client.PutFile(route, files[index], MimeForPath(files[index]), sha, [&](uintmax_t sent, uintmax_t) {
                auto now = std::chrono::steady_clock::now();
                if (now - lastNotice < std::chrono::milliseconds(450) && sent < fileSize) return;
                lastNotice = now;
                uintmax_t done = completedBytes + sent;
                int percent = totalBytes == 0 ? 100 : static_cast<int>(std::min<uintmax_t>(100, done * 100 / totalBytes));
                PostProgress(percent, true);
                double seconds = std::max(0.1, std::chrono::duration<double>(now - uploadStart).count());
                uintmax_t speed = static_cast<uintmax_t>(done / seconds);
                PostStatus(L"正在传给 “" + device.name + L"”：" + std::to_wstring(percent) + L"%（"
                           + FormatBytes(done) + L"/" + FormatBytes(totalBytes) + L"，" + FormatBytes(speed) + L"/s）");
                if (gCancelRequested) throw std::runtime_error("传送已取消");
            });
            completedBytes += fileSize;
            WriteDiagnosticLog(L"file_done", fileName + L" sha256=" + sha);
        }
        client.PostEmpty(L"/v2/tasks/" + taskId + L"/commit");
        RecordSuccessfulTransfers(device, fingerprints, L"Wi-Fi");
        WriteDiagnosticLog(L"upload_commit", taskId);
        PostProgress(100, true);
        PostStatus(L"已传送到 “" + device.name + L"” 的接收文件夹");
    } catch (const std::exception& error) {
        WriteDiagnosticLog(L"upload_failed", Utf8ToWide(error.what()));
        try {
            HttpClient cleanup(device.ip, device.port);
            cleanup.PostEmpty(L"/v2/tasks/" + taskId + L"/cancel");
            WriteDiagnosticLog(L"upload_cancel_sent", taskId);
        } catch (...) {
            WriteDiagnosticLog(L"upload_cancel_failed", taskId);
        }
        PostProgress(0, false);
        PostStatus(L"传送失败：" + Utf8ToWide(error.what()));
    }
    gUploadInProgress = false;
    gCancelRequested = false;
    for (const auto& archive : temporaryArchives) {
        std::error_code ignored;
        std::filesystem::remove(archive, ignored);
    }
    std::error_code ignored;
    std::filesystem::remove(std::filesystem::temp_directory_path() / (L"album-folder-" + taskId + L".zip"), ignored);
}

void RefreshDeviceList() {
    auto now = std::chrono::steady_clock::now();
    std::vector<Device> fresh;
    {
        std::lock_guard<std::mutex> lock(gDeviceMutex);
        for (auto it = gDevices.begin(); it != gDevices.end();) {
            if (now - it->second.lastSeen > std::chrono::seconds(15)) it = gDevices.erase(it);
            else {
                fresh.push_back(it->second);
                ++it;
            }
        }
        for (const auto& [usbId, peer] : gUsbPeers) {
            auto sameDevice = std::find_if(fresh.begin(), fresh.end(), [&](const Device& candidate) {
                bool sameName = !peer.name.empty() && _wcsicmp(candidate.name.c_str(), peer.name.c_str()) == 0;
                bool sameModel = !peer.model.empty() && !candidate.model.empty()
                    && _wcsicmp(candidate.model.c_str(), peer.model.c_str()) == 0;
                std::wstring candidateFamily = HardwareFamily(candidate.name, candidate.model);
                std::wstring usbFamily = HardwareFamily(peer.name, peer.model);
                bool sameHardwareFamily = !candidateFamily.empty() && candidateFamily == usbFamily;
                return sameName || sameModel || sameHardwareFamily;
            });
            if (sameDevice != fresh.end()) {
                sameDevice->usbReady = peer.ready;
                sameDevice->usbPeer = peer;
            } else {
                Device device;
                device.id = usbId;
                device.name = peer.name;
                device.model = peer.model;
                device.state = peer.ready ? L"usb" : L"usb_pending";
                device.usbReady = peer.ready;
                bool newlyRegistered = false;
                ChannelPreferences channels = ChannelsFor(device.id, &newlyRegistered);
                device.usbAllowed = channels.usb;
                device.wifiAllowed = channels.wifi;
                device.remoteAllowed = channels.remote;
                if (newlyRegistered) PostStatus(device.name + L" 已自动登记，传送权限已开启");
                device.usbPeer = peer;
                device.lastSeen = now;
                fresh.push_back(device);
            }
        }
    }
    std::sort(fresh.begin(), fresh.end(), [](const Device& left, const Device& right) {
        return DisplayNameFor(left) < DisplayNameFor(right);
    });
    int previous = static_cast<int>(SendMessageW(gDeviceList, LB_GETCURSEL, 0, 0));
    SendMessageW(gDeviceList, WM_SETREDRAW, FALSE, 0);
    SendMessageW(gDeviceList, LB_RESETCONTENT, 0, 0);
    gDisplayedDevices = fresh;
    for (const Device& device : gDisplayedDevices) {
        std::wstring name = DisplayNameFor(device);
        SendMessageW(gDeviceList, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(name.c_str()));
    }
    if (!gDisplayedDevices.empty()) SendMessageW(gDeviceList, LB_SETCURSEL, std::clamp(previous, 0, static_cast<int>(gDisplayedDevices.size()) - 1), 0);
    SendMessageW(gDeviceList, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(gDeviceList, nullptr, TRUE);
    std::wstring summary = gDisplayedDevices.empty() ? L"未发现设备。请确认手机已打开“相册”并连接同一 Wi‑Fi。"
                                                     : L"已发现 " + std::to_wstring(gDisplayedDevices.size()) + L" 台设备；可拖入任意文件、ZIP 或整个文件夹。";
    if (!gUploadInProgress) SetWindowTextW(gStatus, summary.c_str());
}

std::vector<sockaddr_in> DiscoveryTargets() {
    std::set<ULONG> addresses{INADDR_BROADCAST};
    ULONG size = 16 * 1024;
    std::vector<unsigned char> buffer(size);
    auto* adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
    ULONG status = GetAdaptersAddresses(AF_INET,
        GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
        nullptr, adapters, &size);
    if (status == ERROR_BUFFER_OVERFLOW) {
        buffer.resize(size);
        adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
        status = GetAdaptersAddresses(AF_INET,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            nullptr, adapters, &size);
    }
    if (status == NO_ERROR) {
        for (auto* adapter = adapters; adapter; adapter = adapter->Next) {
            if (adapter->OperStatus != IfOperStatusUp) continue;
            for (auto* unicast = adapter->FirstUnicastAddress; unicast; unicast = unicast->Next) {
                if (!unicast->Address.lpSockaddr || unicast->Address.lpSockaddr->sa_family != AF_INET) continue;
                auto* ipv4 = reinterpret_cast<sockaddr_in*>(unicast->Address.lpSockaddr);
                ULONG host = ntohl(ipv4->sin_addr.s_addr);
                ULONG prefix = unicast->OnLinkPrefixLength;
                if (prefix > 32 || (host >> 24) == 127) continue;
                ULONG mask = prefix == 0 ? 0 : (0xffffffffu << (32 - prefix));
                addresses.insert(htonl(host | ~mask));
            }
        }
    }
    std::vector<sockaddr_in> targets;
    for (ULONG address : addresses) {
        sockaddr_in target{};
        target.sin_family = AF_INET;
        target.sin_addr.s_addr = address;
        target.sin_port = htons(DISCOVERY_PORT);
        targets.push_back(target);
    }
    return targets;
}

void DiscoveryLoop() {
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        PostStatus(L"局域网发现初始化失败");
        WriteDiagnosticLog(L"discovery_failed", L"WSAStartup failed");
        return;
    }
    SOCKET socketHandle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socketHandle == INVALID_SOCKET) {
        WSACleanup();
        PostStatus(L"局域网发现端口创建失败");
        WriteDiagnosticLog(L"discovery_failed", L"socket create failed");
        return;
    }
    BOOL yes = TRUE;
    setsockopt(socketHandle, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));
    setsockopt(socketHandle, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&yes), sizeof(yes));
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = INADDR_ANY;
    local.sin_port = htons(DISCOVERY_PORT);
    if (bind(socketHandle, reinterpret_cast<sockaddr*>(&local), sizeof(local)) == SOCKET_ERROR) {
        closesocket(socketHandle);
        WSACleanup();
        PostStatus(L"发现端口被占用，请关闭重复打开的中控");
        WriteDiagnosticLog(L"discovery_failed", L"udp port occupied");
        return;
    }
    WriteDiagnosticLog(L"discovery_ready", L"udp=45834");
    auto nextProbe = std::chrono::steady_clock::now();
    while (gRunning) {
        auto now = std::chrono::steady_clock::now();
        if (gRefreshRequested.exchange(false)) nextProbe = now;
        if (now >= nextProbe) {
            const char probe[] = "ZWMDS2_DISCOVER";
            std::string beacon = WindowsBeacon();
            for (sockaddr_in& target : DiscoveryTargets()) {
                sendto(socketHandle, probe, static_cast<int>(sizeof(probe) - 1), 0,
                       reinterpret_cast<sockaddr*>(&target), sizeof(target));
                sendto(socketHandle, beacon.data(), static_cast<int>(beacon.size()), 0,
                       reinterpret_cast<sockaddr*>(&target), sizeof(target));
            }
            nextProbe = now + std::chrono::seconds(2);
        }
        fd_set readSet;
        FD_ZERO(&readSet);
        FD_SET(socketHandle, &readSet);
        timeval timeout{0, 350000};
        int ready = select(0, &readSet, nullptr, nullptr, &timeout);
        if (ready > 0 && FD_ISSET(socketHandle, &readSet)) {
            char buffer[2048]{};
            sockaddr_in source{};
            int sourceLength = sizeof(source);
            int count = recvfrom(socketHandle, buffer, sizeof(buffer) - 1, 0, reinterpret_cast<sockaddr*>(&source), &sourceLength);
            if (count > 0) {
                std::string packet(buffer, static_cast<size_t>(count));
                auto parts = Split(packet, '|');
                if (parts.size() >= 8 && parts[0] == "ZWMDS2_HERE" && parts[1] == "2") {
                    char ip[INET_ADDRSTRLEN]{};
                    inet_ntop(AF_INET, &source.sin_addr, ip, sizeof(ip));
                    Device device;
                    device.id = Utf8ToWide(parts[2]);
                    try { device.port = static_cast<INTERNET_PORT>(std::stoi(parts[3])); } catch (...) { device.port = 45833; }
                    device.name = Utf8ToWide(Base64UrlDecode(parts[4]));
                    device.model = Utf8ToWide(Base64UrlDecode(parts[5]));
                    device.state = Utf8ToWide(Base64UrlDecode(parts[6]));
                    device.taskId = Utf8ToWide(parts[7]);
                    if (parts.size() >= 9) {
                        try { device.workCount = std::max(-1, std::stoi(parts[8])); }
                        catch (...) { device.workCount = -1; }
                    }
                    device.ip = Utf8ToWide(ip);
                    bool newlyRegistered = false;
                    ChannelPreferences channels = ChannelsFor(device.id, &newlyRegistered);
                    device.usbAllowed = channels.usb;
                    device.wifiAllowed = channels.wifi;
                    device.remoteAllowed = channels.remote;
                    if (newlyRegistered) PostStatus(device.name + L" 已自动登记，传送权限已开启");
                    device.lastSeen = std::chrono::steady_clock::now();
                    if (!device.id.empty() && WideToUtf8(device.id) != WindowsDeviceId()) {
                        std::lock_guard<std::mutex> lock(gDeviceMutex);
                        bool isNew = gDevices.find(device.id) == gDevices.end();
                        gDevices[device.id] = device;
                        if (isNew) WriteDiagnosticLog(L"device_found", device.name + L" ip=" + device.ip + L" state=" + device.state);
                        PostMessageW(gWindow, WM_DEVICES_CHANGED, 0, 0);
                    }
                }
            }
        }
    }
    closesocket(socketHandle);
    WSACleanup();
}

void DrawPlatformIcon(HDC dc, const RECT& bounds, bool isApple) {
    COLORREF color = isApple ? RGB(56, 62, 70) : RGB(61, 164, 93);
    HPEN pen = CreatePen(PS_SOLID, 2, color);
    HBRUSH brush = CreateSolidBrush(color);
    HGDIOBJ oldPen = SelectObject(dc, pen);
    HGDIOBJ oldBrush = SelectObject(dc, brush);
    if (isApple) {
        RoundRect(dc, bounds.left + 8, bounds.top + 3, bounds.right - 8, bounds.bottom - 3, 8, 8);
        SelectObject(dc, GetStockObject(NULL_BRUSH));
        MoveToEx(dc, bounds.left + 15, bounds.top + 7, nullptr);
        LineTo(dc, bounds.right - 15, bounds.top + 7);
        MoveToEx(dc, bounds.left + 16, bounds.bottom - 7, nullptr);
        LineTo(dc, bounds.right - 16, bounds.bottom - 7);
    } else {
        RoundRect(dc, bounds.left + 6, bounds.top + 13, bounds.right - 6, bounds.bottom - 5, 8, 8);
        MoveToEx(dc, bounds.left + 11, bounds.top + 11, nullptr);
        LineTo(dc, bounds.left + 7, bounds.top + 5);
        MoveToEx(dc, bounds.right - 11, bounds.top + 11, nullptr);
        LineTo(dc, bounds.right - 7, bounds.top + 5);
        HBRUSH eye = CreateSolidBrush(RGB(255, 255, 255));
        SelectObject(dc, eye);
        Ellipse(dc, bounds.left + 13, bounds.top + 18, bounds.left + 16, bounds.top + 21);
        Ellipse(dc, bounds.right - 16, bounds.top + 18, bounds.right - 13, bounds.top + 21);
        SelectObject(dc, brush);
        DeleteObject(eye);
    }
    SelectObject(dc, oldBrush);
    SelectObject(dc, oldPen);
    DeleteObject(brush);
    DeleteObject(pen);
}

void DrawDeviceItem(const DRAWITEMSTRUCT* item) {
    if (item->itemID == static_cast<UINT>(-1) || item->itemID >= gDisplayedDevices.size()) return;
    const Device& device = gDisplayedDevices[item->itemID];
    HDC dc = item->hDC;
    RECT rect = item->rcItem;
    bool selected = (item->itemState & ODS_SELECTED) != 0;
    HBRUSH background = CreateSolidBrush(selected ? RGB(232, 243, 236) : RGB(255, 255, 255));
    FillRect(dc, &rect, background);
    DeleteObject(background);
    HPEN border = CreatePen(PS_SOLID, 1, selected ? RGB(38, 145, 94) : RGB(229, 226, 220));
    HGDIOBJ oldPen = SelectObject(dc, border);
    HGDIOBJ oldBrush = SelectObject(dc, GetStockObject(NULL_BRUSH));
    RoundRect(dc, rect.left + 4, rect.top + 4, rect.right - 4, rect.bottom - 4, 20, 20);
    SelectObject(dc, oldBrush);
    SelectObject(dc, oldPen);
    DeleteObject(border);

    bool isApple = device.model.find(L"iPhone") != std::wstring::npos
        || device.id.find(L"ios-") == 0 || device.id.find(L"apple:") == 0;
    RECT platformRect{rect.left + 14, rect.top + 16, rect.left + 56, rect.top + 58};
    DrawPlatformIcon(dc, platformRect, isApple);

    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, RGB(25, 28, 32));
    SelectObject(dc, gFont);
    RECT listClient{};
    GetClientRect(gDeviceList, &listClient);
    int contentRight = std::min(rect.right, listClient.right) - 16;
    RECT nameRect{rect.left + 68, rect.top + 9, contentRight - 220, rect.top + 35};
    std::wstring displayName = DisplayNameFor(device);
    bool hasRemark = displayName != device.name;
    if (device.workCount >= 0) {
        displayName += L"（" + std::to_wstring(device.workCount) + L"）";
    }
    DrawTextW(dc, displayName.c_str(), -1, &nameRect, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, RGB(105, 112, 120));
    std::wstring sub = hasRemark ? (device.name + L"  ·  " + device.model) : device.model;
    if (device.state == L"usb_pending" && !device.usbPeer.hint.empty()) {
        sub = device.usbPeer.hint;
    } else if (!device.usbPeer.id.empty() && !device.usbReady) {
        sub += L"  ·  USB 已连接，待文件传输";
    }
    RECT subRect{rect.left + 68, rect.top + 37, rect.right - 16, rect.bottom - 8};
    DrawTextW(dc, sub.c_str(), -1, &subRect, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, StateColor(device.state));
    std::vector<std::wstring> channelLabels;
    if (device.usbAllowed && device.usbReady) channelLabels.push_back(L"USB");
    if (device.wifiAllowed && !device.ip.empty()) channelLabels.push_back(L"WiFi");
    if (device.remoteAllowed && device.remoteConnected) channelLabels.push_back(L"远程");
    if (device.state == L"receiving") channelLabels.push_back(L"接收中");
    if (device.state == L"sharing") channelLabels.push_back(L"分享中");
    if (channelLabels.empty() && device.state == L"usb_pending") channelLabels.push_back(L"USB 待开启");
    if (channelLabels.empty()) channelLabels.push_back(L"无可用通道");
    std::wstring channelText;
    for (const auto& value : channelLabels) {
        if (!channelText.empty()) channelText += L" · ";
        channelText += value;
    }
    RECT channelRect{std::max<LONG>(rect.left + 220, contentRight - 210), rect.top + 9,
                     contentRight, rect.top + 35};
    DrawTextW(dc, channelText.c_str(), -1, &channelRect,
              DT_RIGHT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    if (item->itemState & ODS_FOCUS) DrawFocusRect(dc, &rect);
}

int DeviceIndexFromClientPoint(POINT point) {
    POINT listPoint = point;
    MapWindowPoints(gWindow, gDeviceList, &listPoint, 1);
    RECT listRect{};
    GetClientRect(gDeviceList, &listRect);
    if (!PtInRect(&listRect, listPoint)) return -1;
    LRESULT hit = SendMessageW(gDeviceList, LB_ITEMFROMPOINT, 0, MAKELPARAM(listPoint.x, listPoint.y));
    if (HIWORD(hit)) return -1;
    int index = LOWORD(hit);
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) return -1;
    return index;
}

void RenameDeviceRemark(int index) {
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) return;
    Device device = gDisplayedDevices[static_cast<size_t>(index)];
    std::wstring current = DisplayNameFor(device);
    auto answer = PromptForText(gWindow, L"重命名手机", L"电脑端显示名称", current);
    if (!answer.has_value()) return;
    if (answer->empty() || *answer == device.name) gDeviceRemarks.erase(device.id);
    else gDeviceRemarks[device.id] = *answer;
    SaveDeviceRemarks();
    WriteDiagnosticLog(L"device_renamed", device.name + L" => " + DisplayNameFor(device));
    RefreshDeviceList();
}

void ClearDeviceRemark(int index) {
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) return;
    Device device = gDisplayedDevices[static_cast<size_t>(index)];
    if (gDeviceRemarks.erase(device.id) == 0) return;
    SaveDeviceRemarks();
    WriteDiagnosticLog(L"device_remark_cleared", device.name);
    RefreshDeviceList();
}

void ToggleTransferChannel(int index, int command) {
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) return;
    const Device& device = gDisplayedDevices[static_cast<size_t>(index)];
    ChannelPreferences channels = ChannelsFor(device.id);
    std::wstring channelName;
    bool enabled = false;
    if (command == IDC_TOGGLE_USB) {
        channels.usb = !channels.usb;
        enabled = channels.usb;
        channelName = L"USB";
    } else if (command == IDC_TOGGLE_WIFI) {
        channels.wifi = !channels.wifi;
        enabled = channels.wifi;
        channelName = L"WiFi";
    } else {
        channels.remote = !channels.remote;
        enabled = channels.remote;
        channelName = L"远程传送";
    }
    {
        std::lock_guard<std::mutex> lock(gPreferenceMutex);
        gChannelPreferences[device.id] = channels;
        SaveChannelPreferencesUnlocked();
    }
    {
        std::lock_guard<std::mutex> lock(gDeviceMutex);
        auto found = gDevices.find(device.id);
        if (found != gDevices.end()) {
            found->second.usbAllowed = channels.usb;
            found->second.wifiAllowed = channels.wifi;
            found->second.remoteAllowed = channels.remote;
        }
    }
    WriteDiagnosticLog(L"transfer_channel_preference",
                       device.name + L" " + channelName + (enabled ? L" enabled" : L" disabled"));
    PostStatus(channelName + (enabled ? L" 已打开" : L" 已关闭") + L"，设置已记住");
    RefreshDeviceList();
}

void ShowDeviceContextMenu(LPARAM lParam) {
    POINT screenPoint{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
    POINT clientPoint = screenPoint;
    if (screenPoint.x == -1 && screenPoint.y == -1) {
        int selected = static_cast<int>(SendMessageW(gDeviceList, LB_GETCURSEL, 0, 0));
        if (selected < 0 || selected >= static_cast<int>(gDisplayedDevices.size())) return;
        RECT itemRect{};
        SendMessageW(gDeviceList, LB_GETITEMRECT, selected, reinterpret_cast<LPARAM>(&itemRect));
        clientPoint = POINT{itemRect.left + 18, itemRect.top + 18};
        MapWindowPoints(gDeviceList, gWindow, &clientPoint, 1);
        screenPoint = clientPoint;
        ClientToScreen(gWindow, &screenPoint);
    } else {
        ScreenToClient(gWindow, &clientPoint);
    }
    int index = DeviceIndexFromClientPoint(clientPoint);
    if (index < 0) return;
    SendMessageW(gDeviceList, LB_SETCURSEL, index, 0);
    HMENU menu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING, IDC_RENAME_DEVICE, L"重命名这台手机");
    bool hasRemark = gDeviceRemarks.find(gDisplayedDevices[static_cast<size_t>(index)].id) != gDeviceRemarks.end();
    AppendMenuW(menu, MF_STRING | (hasRemark ? 0 : MF_GRAYED), IDC_CLEAR_DEVICE_REMARK, L"清除电脑备注");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    const Device& device = gDisplayedDevices[static_cast<size_t>(index)];
    HMENU channels = CreatePopupMenu();
    AppendMenuW(channels, MF_STRING | (device.usbAllowed ? MF_CHECKED : 0),
                IDC_TOGGLE_USB, L"USB");
    AppendMenuW(channels, MF_STRING | (device.wifiAllowed ? MF_CHECKED : 0),
                IDC_TOGGLE_WIFI, L"WiFi");
    AppendMenuW(channels, MF_STRING | (device.remoteAllowed ? MF_CHECKED : 0),
                IDC_TOGGLE_REMOTE, L"远程传送");
    AppendMenuW(menu, MF_POPUP, reinterpret_cast<UINT_PTR>(channels), L"传送方式");
    int command = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, screenPoint.x, screenPoint.y, 0, gWindow, nullptr);
    DestroyMenu(menu);
    if (command == IDC_RENAME_DEVICE) RenameDeviceRemark(index);
    if (command == IDC_CLEAR_DEVICE_REMARK) ClearDeviceRemark(index);
    if (command == IDC_TOGGLE_USB || command == IDC_TOGGLE_WIFI || command == IDC_TOGGLE_REMOTE) {
        ToggleTransferChannel(index, command);
    }
}

void HandleDrop(HDROP drop) {
    if (gUploadInProgress) {
        DragFinish(drop);
        MessageBoxW(gWindow, L"上一批作品还在传送，请等进度完成。", L"相册投送", MB_OK | MB_ICONINFORMATION);
        return;
    }
    POINT point{};
    DragQueryPoint(drop, &point);
    POINT listPoint = point;
    MapWindowPoints(gWindow, gDeviceList, &listPoint, 1);
    LRESULT hit = SendMessageW(gDeviceList, LB_ITEMFROMPOINT, 0, MAKELPARAM(listPoint.x, listPoint.y));
    int index = HIWORD(hit) ? static_cast<int>(SendMessageW(gDeviceList, LB_GETCURSEL, 0, 0)) : LOWORD(hit);
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) {
        DragFinish(drop);
        MessageBoxW(gWindow, L"请把文件拖到一台在线手机的卡片上。", L"素材投送", MB_OK | MB_ICONINFORMATION);
        return;
    }
    UINT count = DragQueryFileW(drop, 0xFFFFFFFF, nullptr, 0);
    std::vector<std::filesystem::path> files;
    for (UINT i = 0; i < count; ++i) {
        UINT length = DragQueryFileW(drop, i, nullptr, 0);
        std::wstring path(static_cast<size_t>(length) + 1, L'\0');
        DragQueryFileW(drop, i, path.data(), length + 1);
        path.resize(length);
        std::filesystem::path file(path);
        if (std::filesystem::is_regular_file(file) || std::filesystem::is_directory(file)) files.push_back(file);
    }
    DragFinish(drop);
    if (files.empty()) {
        MessageBoxW(gWindow, L"没有找到可传送的文件或文件夹。", L"素材投送", MB_OK | MB_ICONWARNING);
        return;
    }
    if (files.size() > 100) {
        MessageBoxW(gWindow, L"单次最多拖入 100 个顶层项目；文件夹内部可包含更多文件。", L"素材投送", MB_OK | MB_ICONWARNING);
        return;
    }
    Device device = gDisplayedDevices[static_cast<size_t>(index)];
    std::thread(UploadToDevice, device, std::move(files), std::wstring()).detach();
}

void DrawActionButton(const DRAWITEMSTRUCT* item) {
    RECT rect = item->rcItem;
    bool enabled = IsWindowEnabled(item->hwndItem) != FALSE;
    bool pressed = (item->itemState & ODS_SELECTED) != 0;
    bool primary = item->CtlID == IDC_SEND_PICKER;
    COLORREF fill = primary ? RGB(38, 145, 94) : RGB(255, 255, 255);
    if (pressed) fill = primary ? RGB(31, 122, 78) : RGB(235, 232, 226);
    if (!enabled) fill = RGB(235, 233, 229);
    HBRUSH brush = CreateSolidBrush(fill);
    HPEN pen = CreatePen(PS_SOLID, 1, primary ? fill : RGB(222, 218, 211));
    HGDIOBJ oldBrush = SelectObject(item->hDC, brush);
    HGDIOBJ oldPen = SelectObject(item->hDC, pen);
    RoundRect(item->hDC, rect.left, rect.top, rect.right, rect.bottom, 18, 18);
    SelectObject(item->hDC, oldPen); SelectObject(item->hDC, oldBrush);
    DeleteObject(pen); DeleteObject(brush);
    wchar_t label[96]{}; GetWindowTextW(item->hwndItem, label, 95);
    SetBkMode(item->hDC, TRANSPARENT);
    SetTextColor(item->hDC, !enabled ? RGB(150, 150, 145)
                                     : (primary ? RGB(255, 255, 255) : RGB(52, 52, 49)));
    SelectObject(item->hDC, gFont);
    DrawTextW(item->hDC, label, -1, &rect, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

std::vector<std::filesystem::path> PickPaths(bool folder) {
    std::vector<std::filesystem::path> result;
    IFileOpenDialog* dialog = nullptr;
    if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog)))) return result;
    DWORD options = 0; dialog->GetOptions(&options);
    options |= FOS_FORCEFILESYSTEM | (folder ? FOS_PICKFOLDERS : FOS_ALLOWMULTISELECT);
    dialog->SetOptions(options);
    dialog->SetTitle(folder ? L"选择要传送的文件夹" : L"选择要传送的文件");
    if (SUCCEEDED(dialog->Show(gWindow))) {
        if (folder) {
            IShellItem* item = nullptr;
            if (SUCCEEDED(dialog->GetResult(&item))) { PWSTR path = nullptr; if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) { result.emplace_back(path); CoTaskMemFree(path); } item->Release(); }
        } else {
            IShellItemArray* items = nullptr;
            if (SUCCEEDED(dialog->GetResults(&items))) { DWORD count = 0; items->GetCount(&count); for (DWORD i = 0; i < count; ++i) { IShellItem* item = nullptr; if (SUCCEEDED(items->GetItemAt(i, &item))) { PWSTR path = nullptr; if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) { result.emplace_back(path); CoTaskMemFree(path); } item->Release(); } } items->Release(); }
        }
    }
    dialog->Release(); return result;
}

void UsbDiscoveryLoop() {
    while (gRunning) {
        try {
            auto peers = EnumerateUsbPeers();
            {
                std::lock_guard<std::mutex> lock(gDeviceMutex);
                gUsbPeers.clear();
                for (const auto& peer : peers) gUsbPeers[peer.id] = peer;
            }
            if (gWindow) PostMessageW(gWindow, WM_DEVICES_CHANGED, 0, 0);
            WriteDiagnosticLog(L"usb_discovery", L"devices=" + std::to_wstring(peers.size()));
        } catch (const std::exception& error) {
            WriteDiagnosticLog(L"usb_discovery_failed", Utf8ToWide(error.what()));
        }
        for (int tick = 0; tick < 20 && gRunning && !gUsbRefreshRequested; ++tick) {
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
        }
        gUsbRefreshRequested = false;
    }
}

void ChooseAndSend(bool folder) {
    if (gUploadInProgress) { MessageBoxW(gWindow, L"上一批文件还在传送。", L"相册投送", MB_OK | MB_ICONINFORMATION); return; }
    int index = static_cast<int>(SendMessageW(gDeviceList, LB_GETCURSEL, 0, 0));
    if (index < 0 || index >= static_cast<int>(gDisplayedDevices.size())) { MessageBoxW(gWindow, L"请先选择一台在线设备。", L"相册投送", MB_OK | MB_ICONINFORMATION); return; }
    auto files = PickPaths(folder); if (files.empty()) return;
    Device device = gDisplayedDevices[static_cast<size_t>(index)];
    std::thread(UploadToDevice, device, std::move(files), std::wstring()).detach();
}

void Layout(HWND window) {
    RECT client{};
    GetClientRect(window, &client);
    int width = client.right - client.left;
    int height = client.bottom - client.top;
    const int margin = 22;
    MoveWindow(gRefreshButton, width - margin - 118, 18, 118, 36, TRUE);
    MoveWindow(gDeviceList, margin, 94, width - margin * 2, std::max(170, height - 218), TRUE);
    int buttonWidth = 92;
    int sendWidth = 100;
    int cancelWidth = 76;
    int statusWidth = std::max(180, width - margin * 2 - buttonWidth - cancelWidth - sendWidth - 30);
    MoveWindow(gProgress, margin, height - 104, width - margin * 2, 18, TRUE);
    MoveWindow(gStatus, margin, height - 76, statusWidth, 46, TRUE);
    MoveWindow(gCancelButton, margin + statusWidth + 10, height - 72, cancelWidth, 38, TRUE);
    MoveWindow(gLogButton, margin + statusWidth + cancelWidth + 20, height - 72, buttonWidth, 38, TRUE);
    MoveWindow(gSendButton, width - margin - sendWidth, height - 72, sendWidth, 38, TRUE);
}

LRESULT CALLBACK WindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            gWindow = window;
            DragAcceptFiles(window, TRUE);
            gFont = CreateFontW(-17, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                                OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
            gTitleFont = CreateFontW(-26, 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                                     OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
            HWND title = CreateWindowW(L"STATIC", L"素材投送中控 V3.9.1", WS_CHILD | WS_VISIBLE,
                                       22, 18, 400, 34, window, nullptr, nullptr, nullptr);
            SendMessageW(title, WM_SETFONT, reinterpret_cast<WPARAM>(gTitleFont), TRUE);
            HWND tip = CreateWindowW(L"STATIC", L"拖入任意文件、ZIP 或整个文件夹；原目录结构会保留。",
                                     WS_CHILD | WS_VISIBLE, 22, 54, 640, 26, window, nullptr, nullptr, nullptr);
            SendMessageW(tip, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gRefreshButton = CreateWindowW(L"BUTTON", L"↻  刷新", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                           560, 18, 118, 36, window,
                                           reinterpret_cast<HMENU>(static_cast<INT_PTR>(IDC_REFRESH_DEVICES)), nullptr, nullptr);
            SendMessageW(gRefreshButton, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gDeviceList = CreateWindowExW(0, L"LISTBOX", nullptr,
                WS_CHILD | WS_VISIBLE | WS_VSCROLL | LBS_NOTIFY | LBS_OWNERDRAWFIXED | LBS_NOINTEGRALHEIGHT,
                22, 94, 640, 280, window, reinterpret_cast<HMENU>(101), nullptr, nullptr);
            SendMessageW(gDeviceList, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gStatus = CreateWindowW(L"STATIC", L"正在搜索同一局域网内的手机…",
                                    WS_CHILD | WS_VISIBLE | SS_LEFT | SS_CENTERIMAGE | SS_ENDELLIPSIS,
                                    22, 516, 640, 46, window, nullptr, nullptr, nullptr);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gProgress = CreateWindowExW(0, PROGRESS_CLASSW, nullptr,
                                        WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
                                        22, 502, 640, 18, window, reinterpret_cast<HMENU>(104), nullptr, nullptr);
            SendMessageW(gProgress, PBM_SETRANGE, 0, MAKELPARAM(0, 100));
            SendMessageW(gProgress, PBM_SETPOS, 0, 0);
            ShowWindow(gProgress, SW_HIDE);
            gCancelButton = CreateWindowW(L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                          440, 520, 86, 38, window, reinterpret_cast<HMENU>(105), nullptr, nullptr);
            SendMessageW(gCancelButton, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            EnableWindow(gCancelButton, FALSE);
            gLogButton = CreateWindowW(L"BUTTON", L"诊断", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                       540, 520, 136, 38, window, reinterpret_cast<HMENU>(103), nullptr, nullptr);
            SendMessageW(gLogButton, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gSendButton = CreateWindowW(L"BUTTON", L"✈  传送", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                                        0, 0, 100, 38, window, reinterpret_cast<HMENU>(static_cast<INT_PTR>(IDC_SEND_PICKER)), nullptr, nullptr);
            SendMessageW(gSendButton, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            WriteDiagnosticLog(L"app_start", L"Windows panel opened");
            gDiscoveryThread = std::thread(DiscoveryLoop);
            gUsbDiscoveryThread = std::thread(UsbDiscoveryLoop);
            gReceiverThread = std::thread([] { RunLanReceiver(gRunning, PostStatus, WriteDiagnosticLog); });
            SetTimer(window, 1, 2500, nullptr);
            return 0;
        }
        case WM_COMMAND:
            if (LOWORD(wParam) == IDC_REFRESH_DEVICES) {
                {
                    std::lock_guard<std::mutex> lock(gDeviceMutex);
                    gDevices.clear();
                }
                RefreshDeviceList();
                SetWindowTextW(gStatus, L"正在刷新设备…");
                gRefreshRequested = true;
                gUsbRefreshRequested = true;
                WriteDiagnosticLog(L"discovery_manual_refresh", L"device list cleared and probe requested");
                return 0;
            }
            if (LOWORD(wParam) == IDC_SEND_PICKER) {
                HMENU menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, IDC_PICK_FILES, L"选择文件…");
                AppendMenuW(menu, MF_STRING, IDC_PICK_FOLDER, L"选择文件夹…");
                RECT rect{}; GetWindowRect(gSendButton, &rect);
                int command = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTALIGN | TPM_BOTTOMALIGN,
                                             rect.right, rect.top, 0, window, nullptr);
                DestroyMenu(menu);
                if (command == IDC_PICK_FILES) ChooseAndSend(false);
                if (command == IDC_PICK_FOLDER) ChooseAndSend(true);
                return 0;
            }
            if (LOWORD(wParam) == 105) {
                if (gUploadInProgress) {
                    gCancelRequested = true;
                    EnableWindow(gCancelButton, FALSE);
                    PostStatus(L"正在取消传送…");
                }
                return 0;
            }
            if (LOWORD(wParam) == 103) {
                if (gLogPath.empty()) gLogPath = DiagnosticLogPath();
                WriteDiagnosticLog(L"log_opened", gLogPath.wstring());
                ShellExecuteW(window, L"open", gLogPath.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
                return 0;
            }
            break;
        case WM_SIZE:
            Layout(window);
            return 0;
        case WM_MEASUREITEM: {
            auto* measure = reinterpret_cast<MEASUREITEMSTRUCT*>(lParam);
            if (measure->CtlID == 101) measure->itemHeight = 76;
            return TRUE;
        }
        case WM_DRAWITEM: {
            auto* draw = reinterpret_cast<DRAWITEMSTRUCT*>(lParam);
            if (draw->CtlID == 101) {
                DrawDeviceItem(draw);
                return TRUE;
            }
            if (draw->CtlType == ODT_BUTTON) {
                DrawActionButton(draw);
                return TRUE;
            }
            break;
        }
        case WM_DROPFILES:
            HandleDrop(reinterpret_cast<HDROP>(wParam));
            return 0;
        case WM_CONTEXTMENU:
            if (reinterpret_cast<HWND>(wParam) == gDeviceList || reinterpret_cast<HWND>(wParam) == window) {
                ShowDeviceContextMenu(lParam);
                return 0;
            }
            break;
        case WM_DEVICES_CHANGED:
            RefreshDeviceList();
            return 0;
        case WM_STATUS_CHANGED: {
            auto* text = reinterpret_cast<std::wstring*>(lParam);
            if (text) {
                SetWindowTextW(gStatus, text->c_str());
                delete text;
            }
            return 0;
        }
        case WM_PROGRESS_CHANGED:
            SendMessageW(gProgress, PBM_SETPOS, wParam, 0);
            ShowWindow(gProgress, lParam ? SW_SHOW : SW_HIDE);
            EnableWindow(gCancelButton, lParam && wParam < 100 && gUploadInProgress);
            return 0;
        case WM_TIMER:
            RefreshDeviceList();
            return 0;
        case WM_DESTROY:
            gRunning = false;
            KillTimer(window, 1);
            if (gDiscoveryThread.joinable()) gDiscoveryThread.join();
            if (gUsbDiscoveryThread.joinable()) gUsbDiscoveryThread.join();
            if (gReceiverThread.joinable()) gReceiverThread.join();
            if (gFont) DeleteObject(gFont);
            if (gTitleFont) DeleteObject(gTitleFont);
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int showCommand) {
    gSingleInstance = CreateMutexW(nullptr, TRUE, L"Local\\ZwmDeviceShareHubSingleton");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        MessageBoxW(nullptr, L"素材投送中控已经打开。", L"素材投送", MB_OK | MB_ICONINFORMATION);
        return 0;
    }
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES | ICC_PROGRESS_CLASS};
    InitCommonControlsEx(&controls);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    ConfigureUsbTransport(instance, WriteDiagnosticLog);
    LoadDeviceRemarks();
    LoadChannelPreferences();

    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = WindowProc;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    windowClass.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(IDI_MAIN_ICON));
    windowClass.hIconSm = LoadIconW(instance, MAKEINTRESOURCEW(IDI_MAIN_ICON));
    windowClass.hbrBackground = CreateSolidBrush(RGB(245, 247, 249));
    windowClass.lpszClassName = WINDOW_CLASS;
    RegisterClassExW(&windowClass);

    HWND window = CreateWindowExW(0, WINDOW_CLASS, L"素材投送中控 V3.9.1",
                                   WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 720, 520,
                                   nullptr, nullptr, instance, nullptr);
    if (!window) return 1;
    ShowWindow(window, showCommand);
    UpdateWindow(window);

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    CoUninitialize();
    if (gSingleInstance) CloseHandle(gSingleInstance);
    return static_cast<int>(message.wParam);
}
