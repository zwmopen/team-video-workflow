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

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <map>
#include <mutex>
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

namespace {
constexpr UINT WM_DEVICES_CHANGED = WM_APP + 1;
constexpr UINT WM_STATUS_CHANGED = WM_APP + 2;
constexpr int DISCOVERY_PORT = 45834;
constexpr wchar_t WINDOW_CLASS[] = L"ZwmDeviceShareHubWindow";

struct Device {
    std::wstring id;
    std::wstring name;
    std::wstring model;
    std::wstring ip;
    INTERNET_PORT port = 45833;
    std::wstring state;
    std::wstring taskId;
    std::chrono::steady_clock::time_point lastSeen;
};

HWND gWindow = nullptr;
HWND gDeviceList = nullptr;
HWND gCaptionEdit = nullptr;
HWND gCaptionLabel = nullptr;
HWND gStatus = nullptr;
HFONT gFont = nullptr;
HFONT gTitleFont = nullptr;
std::mutex gDeviceMutex;
std::map<std::wstring, Device> gDevices;
std::vector<Device> gDisplayedDevices;
std::atomic<bool> gRunning{true};
std::thread gDiscoveryThread;
HANDLE gSingleInstance = nullptr;

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

std::wstring StateLabel(const std::wstring& state) {
    if (state == L"receiving") return L"正在接收";
    if (state == L"ready") return L"等待手机分享";
    if (state == L"sharing") return L"已打开分享";
    return L"在线";
}

COLORREF StateColor(const std::wstring& state) {
    if (state == L"receiving") return RGB(45, 122, 245);
    if (state == L"ready") return RGB(245, 160, 35);
    if (state == L"sharing") return RGB(124, 85, 220);
    return RGB(35, 170, 92);
}

void PostStatus(const std::wstring& text) {
    if (gWindow) PostMessageW(gWindow, WM_STATUS_CHANGED, 0, reinterpret_cast<LPARAM>(new std::wstring(text)));
}

std::wstring GetWindowTextString(HWND hwnd) {
    int length = GetWindowTextLengthW(hwnd);
    std::wstring value(static_cast<size_t>(length) + 1, L'\0');
    if (length > 0) GetWindowTextW(hwnd, value.data(), length + 1);
    value.resize(static_cast<size_t>(length));
    return value;
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

class HttpClient {
public:
    HttpClient(const std::wstring& host, INTERNET_PORT port) {
        session_ = WinHttpOpen(L"ZwmDeviceShare/0.2", WINHTTP_ACCESS_TYPE_NO_PROXY,
                               WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session_) throw std::runtime_error("无法初始化网络连接");
        WinHttpSetTimeouts(session_, 7000, 7000, 60000, 60000);
        connection_ = WinHttpConnect(session_, host.c_str(), port, 0);
        if (!connection_) {
            WinHttpCloseHandle(session_);
            throw std::runtime_error("无法连接手机");
        }
    }

    ~HttpClient() {
        if (connection_) WinHttpCloseHandle(connection_);
        if (session_) WinHttpCloseHandle(session_);
    }

    void PostJson(const std::wstring& path, const std::string& json) {
        std::wstring headers = L"Content-Type: application/json; charset=utf-8\r\n";
        SendMemory(L"POST", path, headers, reinterpret_cast<const BYTE*>(json.data()), static_cast<DWORD>(json.size()));
    }

    void PostEmpty(const std::wstring& path) {
        SendMemory(L"POST", path, L"Content-Type: text/plain\r\n", nullptr, 0);
    }

    void PutFile(const std::wstring& path, const std::filesystem::path& file, const std::wstring& mime, const std::wstring& sha256) {
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
        if (!request) throw std::runtime_error("无法创建上传请求");
        BOOL ok = WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1L),
                                     WINHTTP_NO_REQUEST_DATA, 0, static_cast<DWORD>(size64), 0);
        if (!ok) {
            WinHttpCloseHandle(request);
            throw std::runtime_error("手机拒绝建立上传连接");
        }
        std::ifstream input(file, std::ios::binary);
        if (!input) {
            WinHttpCloseHandle(request);
            throw std::runtime_error("无法打开本地文件");
        }
        std::vector<char> buffer(1024 * 1024);
        while (input) {
            input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            DWORD count = static_cast<DWORD>(input.gcount());
            if (count == 0) continue;
            DWORD written = 0;
            if (!WinHttpWriteData(request, buffer.data(), count, &written) || written != count) {
                WinHttpCloseHandle(request);
                throw std::runtime_error("上传文件中断");
            }
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
        if (!request) throw std::runtime_error("无法创建请求");
        LPVOID optional = size > 0 ? const_cast<BYTE*>(data) : WINHTTP_NO_REQUEST_DATA;
        BOOL ok = WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1L), optional, size, size, 0);
        if (!ok) {
            WinHttpCloseHandle(request);
            throw std::runtime_error("网络发送失败");
        }
        CheckResponse(request);
        WinHttpCloseHandle(request);
    }

    static void CheckResponse(HINTERNET request) {
        if (!WinHttpReceiveResponse(request, nullptr)) throw std::runtime_error("没有收到手机响应");
        DWORD status = 0;
        DWORD size = sizeof(status);
        if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                 WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX)) {
            throw std::runtime_error("无法读取手机响应");
        }
        if (status < 200 || status >= 300) {
            if (status == 409) throw std::runtime_error("手机上还有一批素材未处理");
            throw std::runtime_error("手机返回错误状态");
        }
    }
};

void UploadToDevice(Device device, std::vector<std::filesystem::path> files, std::wstring caption) {
    std::wstring taskId = NewTaskId();
    try {
        PostStatus(L"正在连接 “" + device.name + L"”…");
        HttpClient client(device.ip, device.port);
        std::ostringstream json;
        json << "{\"taskId\":\"" << WideToUtf8(taskId) << "\",\"text\":\""
             << JsonEscape(caption) << "\",\"fileCount\":" << files.size() << "}";
        client.PostJson(L"/v2/tasks", json.str());
        for (size_t index = 0; index < files.size(); ++index) {
            PostStatus(L"正在传给 “" + device.name + L"”：" + std::to_wstring(index + 1) + L"/" + std::to_wstring(files.size()));
            std::wstring sha = Sha256File(files[index]);
            std::wstring route = L"/v2/tasks/" + taskId + L"/files/" + std::to_wstring(index);
            client.PutFile(route, files[index], MimeForPath(files[index]), sha);
        }
        client.PostEmpty(L"/v2/tasks/" + taskId + L"/commit");
        PostStatus(L"已传送到 “" + device.name + L"”，手机正在打开分享");
    } catch (const std::exception& error) {
        try {
            HttpClient cleanup(device.ip, device.port);
            cleanup.PostEmpty(L"/v2/tasks/" + taskId + L"/cancel");
        } catch (...) {
        }
        PostStatus(L"传送失败：" + Utf8ToWide(error.what()));
    }
}

void RefreshDeviceList() {
    auto now = std::chrono::steady_clock::now();
    std::vector<Device> fresh;
    {
        std::lock_guard<std::mutex> lock(gDeviceMutex);
        for (auto it = gDevices.begin(); it != gDevices.end();) {
            if (now - it->second.lastSeen > std::chrono::seconds(9)) it = gDevices.erase(it);
            else {
                fresh.push_back(it->second);
                ++it;
            }
        }
    }
    std::sort(fresh.begin(), fresh.end(), [](const Device& left, const Device& right) { return left.name < right.name; });
    int previous = static_cast<int>(SendMessageW(gDeviceList, LB_GETCURSEL, 0, 0));
    SendMessageW(gDeviceList, WM_SETREDRAW, FALSE, 0);
    SendMessageW(gDeviceList, LB_RESETCONTENT, 0, 0);
    gDisplayedDevices = fresh;
    for (const Device& device : gDisplayedDevices) SendMessageW(gDeviceList, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(device.name.c_str()));
    if (!gDisplayedDevices.empty()) SendMessageW(gDeviceList, LB_SETCURSEL, std::clamp(previous, 0, static_cast<int>(gDisplayedDevices.size()) - 1), 0);
    SendMessageW(gDeviceList, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(gDeviceList, nullptr, TRUE);
    std::wstring summary = gDisplayedDevices.empty() ? L"未发现设备。请确认手机已打开接收端并连接同一 Wi‑Fi。"
                                                     : L"已发现 " + std::to_wstring(gDisplayedDevices.size()) + L" 台手机；把图片或视频直接拖到对应卡片。";
    SetWindowTextW(gStatus, summary.c_str());
}

void DiscoveryLoop() {
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        PostStatus(L"局域网发现初始化失败");
        return;
    }
    SOCKET socketHandle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socketHandle == INVALID_SOCKET) {
        WSACleanup();
        PostStatus(L"局域网发现端口创建失败");
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
        return;
    }
    sockaddr_in target{};
    target.sin_family = AF_INET;
    target.sin_addr.s_addr = INADDR_BROADCAST;
    target.sin_port = htons(DISCOVERY_PORT);
    auto nextProbe = std::chrono::steady_clock::now();
    while (gRunning) {
        auto now = std::chrono::steady_clock::now();
        if (now >= nextProbe) {
            const char probe[] = "ZWMDS2_DISCOVER";
            sendto(socketHandle, probe, static_cast<int>(sizeof(probe) - 1), 0, reinterpret_cast<sockaddr*>(&target), sizeof(target));
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
                    device.ip = Utf8ToWide(ip);
                    device.lastSeen = std::chrono::steady_clock::now();
                    if (!device.id.empty()) {
                        std::lock_guard<std::mutex> lock(gDeviceMutex);
                        gDevices[device.id] = device;
                        PostMessageW(gWindow, WM_DEVICES_CHANGED, 0, 0);
                    }
                }
            }
        }
    }
    closesocket(socketHandle);
    WSACleanup();
}

void DrawDeviceItem(const DRAWITEMSTRUCT* item) {
    if (item->itemID == static_cast<UINT>(-1) || item->itemID >= gDisplayedDevices.size()) return;
    const Device& device = gDisplayedDevices[item->itemID];
    HDC dc = item->hDC;
    RECT rect = item->rcItem;
    bool selected = (item->itemState & ODS_SELECTED) != 0;
    HBRUSH background = CreateSolidBrush(selected ? RGB(235, 242, 255) : RGB(250, 251, 252));
    FillRect(dc, &rect, background);
    DeleteObject(background);
    HPEN border = CreatePen(PS_SOLID, 1, selected ? RGB(90, 135, 220) : RGB(222, 226, 230));
    HGDIOBJ oldPen = SelectObject(dc, border);
    HGDIOBJ oldBrush = SelectObject(dc, GetStockObject(NULL_BRUSH));
    RoundRect(dc, rect.left + 4, rect.top + 4, rect.right - 4, rect.bottom - 4, 12, 12);
    SelectObject(dc, oldBrush);
    SelectObject(dc, oldPen);
    DeleteObject(border);

    HBRUSH dot = CreateSolidBrush(StateColor(device.state));
    HGDIOBJ oldDotBrush = SelectObject(dc, dot);
    HGDIOBJ oldDotPen = SelectObject(dc, GetStockObject(NULL_PEN));
    Ellipse(dc, rect.left + 18, rect.top + 20, rect.left + 30, rect.top + 32);
    SelectObject(dc, oldDotPen);
    SelectObject(dc, oldDotBrush);
    DeleteObject(dot);

    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, RGB(25, 28, 32));
    SelectObject(dc, gFont);
    RECT nameRect{rect.left + 42, rect.top + 10, rect.right - 125, rect.top + 36};
    DrawTextW(dc, device.name.c_str(), -1, &nameRect, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, RGB(105, 112, 120));
    std::wstring sub = device.model + L"  ·  " + device.ip;
    RECT subRect{rect.left + 42, rect.top + 38, rect.right - 18, rect.bottom - 8};
    DrawTextW(dc, sub.c_str(), -1, &subRect, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, StateColor(device.state));
    std::wstring label = StateLabel(device.state);
    RECT stateRect{rect.right - 120, rect.top + 12, rect.right - 16, rect.top + 36};
    DrawTextW(dc, label.c_str(), -1, &stateRect, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
    if (item->itemState & ODS_FOCUS) DrawFocusRect(dc, &rect);
}

void HandleDrop(HDROP drop) {
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
        if (std::filesystem::is_regular_file(file)) files.push_back(file);
    }
    DragFinish(drop);
    if (files.empty()) {
        MessageBoxW(gWindow, L"没有找到可传送的文件。", L"素材投送", MB_OK | MB_ICONWARNING);
        return;
    }
    if (files.size() > 100) {
        MessageBoxW(gWindow, L"单次最多传送 100 个文件。", L"素材投送", MB_OK | MB_ICONWARNING);
        return;
    }
    Device device = gDisplayedDevices[static_cast<size_t>(index)];
    std::wstring caption = GetWindowTextString(gCaptionEdit);
    std::thread(UploadToDevice, device, std::move(files), std::move(caption)).detach();
}

void Layout(HWND window) {
    RECT client{};
    GetClientRect(window, &client);
    int width = client.right - client.left;
    int height = client.bottom - client.top;
    const int margin = 22;
    int captionTop = std::max(330, height - 178);
    MoveWindow(gDeviceList, margin, 94, width - margin * 2, std::max(170, captionTop - 130), TRUE);
    MoveWindow(gCaptionLabel, margin, captionTop - 28, width - margin * 2, 24, TRUE);
    MoveWindow(gCaptionEdit, margin, captionTop, width - margin * 2, 80, TRUE);
    MoveWindow(gStatus, margin, height - 76, width - margin * 2, 46, TRUE);
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
            HWND title = CreateWindowW(L"STATIC", L"素材投送中控", WS_CHILD | WS_VISIBLE,
                                       22, 18, 400, 34, window, nullptr, nullptr, nullptr);
            SendMessageW(title, WM_SETFONT, reinterpret_cast<WPARAM>(gTitleFont), TRUE);
            HWND tip = CreateWindowW(L"STATIC", L"同一 Wi‑Fi 下自动识别手机。把图片或视频拖到对应设备卡片即可。",
                                     WS_CHILD | WS_VISIBLE, 22, 54, 640, 26, window, nullptr, nullptr, nullptr);
            SendMessageW(tip, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gDeviceList = CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", nullptr,
                WS_CHILD | WS_VISIBLE | WS_VSCROLL | LBS_NOTIFY | LBS_OWNERDRAWFIXED | LBS_NOINTEGRALHEIGHT,
                22, 94, 640, 280, window, reinterpret_cast<HMENU>(101), nullptr, nullptr);
            SendMessageW(gDeviceList, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gCaptionLabel = CreateWindowW(L"STATIC", L"文案（可选，传到手机后自动复制）",
                                           WS_CHILD | WS_VISIBLE, 22, 390, 500, 24, window, nullptr, nullptr, nullptr);
            SendMessageW(gCaptionLabel, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gCaptionEdit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", nullptr,
                WS_CHILD | WS_VISIBLE | ES_MULTILINE | ES_AUTOVSCROLL | WS_VSCROLL,
                22, 418, 640, 80, window, reinterpret_cast<HMENU>(102), nullptr, nullptr);
            SendMessageW(gCaptionEdit, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            SendMessageW(gCaptionEdit, EM_SETLIMITTEXT, 50000, 0);
            gStatus = CreateWindowW(L"STATIC", L"正在搜索同一局域网内的手机…",
                                    WS_CHILD | WS_VISIBLE | SS_CENTER | SS_CENTERIMAGE,
                                    22, 516, 640, 46, window, nullptr, nullptr, nullptr);
            SendMessageW(gStatus, WM_SETFONT, reinterpret_cast<WPARAM>(gFont), TRUE);
            gDiscoveryThread = std::thread(DiscoveryLoop);
            SetTimer(window, 1, 2500, nullptr);
            return 0;
        }
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
            break;
        }
        case WM_DROPFILES:
            HandleDrop(reinterpret_cast<HDROP>(wParam));
            return 0;
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
        case WM_TIMER:
            RefreshDeviceList();
            return 0;
        case WM_DESTROY:
            gRunning = false;
            KillTimer(window, 1);
            if (gDiscoveryThread.joinable()) gDiscoveryThread.join();
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
    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&controls);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = WindowProc;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    windowClass.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    windowClass.hbrBackground = CreateSolidBrush(RGB(245, 247, 249));
    windowClass.lpszClassName = WINDOW_CLASS;
    RegisterClassExW(&windowClass);

    HWND window = CreateWindowExW(0, WINDOW_CLASS, L"素材投送中控",
                                   WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 720, 640,
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
