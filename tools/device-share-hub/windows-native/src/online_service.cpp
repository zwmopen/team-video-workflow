// winsock2.h 必须在 windows.h 之前（online_service.h 会带出 windows.h），
// 否则老版 winsock.h 被拉进来会跟它打架。
#include <winsock2.h>
#include <ws2tcpip.h>

#include "online_service.h"

#include <windows.h>
#include <iphlpapi.h>
#include <shellapi.h>
#include <winhttp.h>

#include <cctype>
#include <filesystem>
#include <string>
#include <vector>

#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "iphlpapi.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "ws2_32.lib")

namespace online_service {
namespace {

constexpr wchar_t kConfigKey[] = L"Software\\ZWMLabs\\DeviceShareHub\\OnlineGallery";
constexpr wchar_t kRunKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr wchar_t kRunValue[] = L"ZwmOnlineGallery";

std::wstring Widen(const std::string& value) {
    if (value.empty()) return {};
    int needed = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), nullptr, 0);
    if (needed <= 0) return {};
    std::wstring out(static_cast<size_t>(needed), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), &out[0], needed);
    return out;
}

size_t SkipWhitespace(const std::string& body, size_t at) {
    while (at < body.size() && std::isspace(static_cast<unsigned char>(body[at]))) ++at;
    return at;
}

size_t FindKey(const std::string& body, const std::string& key) {
    return body.find("\"" + key + "\"");
}

// 取 key 后面的字面量：字符串返回内容（不含引号），数字返回数字文本，其余返回空。
std::string RawValue(const std::string& body, const std::string& key) {
    size_t at = FindKey(body, key);
    if (at == std::string::npos) return {};
    size_t colon = body.find(':', at);
    if (colon == std::string::npos) return {};
    size_t i = SkipWhitespace(body, colon + 1);
    if (i >= body.size()) return {};
    if (body[i] == '"') {
        size_t end = body.find('"', i + 1);
        if (end == std::string::npos) return {};
        return body.substr(i + 1, end - i - 1);
    }
    size_t end = i;
    while (end < body.size()) {
        char c = body[end];
        if (c == '-' || c == '+' || c == '.' || std::isdigit(static_cast<unsigned char>(c))) ++end;
        else break;
    }
    return body.substr(i, end - i);
}

std::optional<long long> IntValue(const std::string& body, const std::string& key) {
    std::string raw = RawValue(body, key);
    if (raw.empty()) return std::nullopt;
    try {
        return std::stoll(raw);
    } catch (...) {
        return std::nullopt;
    }
}

std::optional<bool> BoolValue(const std::string& body, const std::string& key) {
    size_t at = FindKey(body, key);
    if (at == std::string::npos) return std::nullopt;
    size_t colon = body.find(':', at);
    if (colon == std::string::npos) return std::nullopt;
    size_t i = SkipWhitespace(body, colon + 1);
    if (body.compare(i, 4, "true") == 0) return true;
    if (body.compare(i, 5, "false") == 0) return false;
    return std::nullopt;
}

// 只在 anchor 出现之后找 key —— 同一个 key 在不同对象里含义不同（典型的就是 count）。
std::optional<long long> IntValueAfter(const std::string& body, const std::string& anchor,
                                       const std::string& key) {
    size_t at = FindKey(body, anchor);
    if (at == std::string::npos) return std::nullopt;
    return IntValue(body.substr(at), key);
}

std::optional<bool> BoolValueAfter(const std::string& body, const std::string& anchor,
                                   const std::string& key) {
    size_t at = FindKey(body, anchor);
    if (at == std::string::npos) return std::nullopt;
    return BoolValue(body.substr(at), key);
}

std::wstring Join(const std::vector<std::wstring>& parts, const std::wstring& sep) {
    std::wstring out;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) out += sep;
        out += parts[i];
    }
    return out;
}

}  // namespace

// ---------------- 纯逻辑 ----------------

std::vector<std::wstring> ScriptCandidates(const std::wstring& startDir) {
    std::vector<std::wstring> out;
    std::filesystem::path dir(startDir);
    for (int up = 0; up <= 5; ++up) {
        std::filesystem::path base = dir;
        for (int i = 0; i < up; ++i) base = base.parent_path();
        if (base.empty()) break;
        out.push_back((base / kServiceScriptName).wstring());
        out.push_back((base / L"scripts" / kServiceScriptName).wstring());
        out.push_back((base / L"tools" / L"device-share-hub" / L"scripts" / kServiceScriptName).wstring());
    }
    return out;
}

std::wstring QuoteArgument(const std::wstring& value) {
    return L"\"" + value + L"\"";
}

std::wstring BuildProcessCommand(const Config& config) {
    std::vector<std::wstring> parts;
    parts.push_back(QuoteArgument(config.pythonPath));
    parts.push_back(QuoteArgument(config.scriptPath));
    parts.push_back(L"--port");
    parts.push_back(std::to_wstring(config.port));
    return Join(parts, L" ");
}

bool Validate(const Config& config, std::wstring& error) {
    if (config.port < 1 || config.port > 65535) {
        error = L"端口必须在 1–65535 之间。";
        return false;
    }
    if (config.pythonPath.empty()) {
        error = L"还没指定 Python 解释器，点「更改…」选一个 pythonw.exe。";
        return false;
    }
    std::error_code ignored;
    if (!std::filesystem::exists(config.pythonPath, ignored)) {
        error = L"指定的 Python 解释器不存在：" + config.pythonPath;
        return false;
    }
    if (config.scriptPath.empty()) {
        error = L"还没指定服务脚本，点「更改…」选 online_gallery_service.py。";
        return false;
    }
    if (!std::filesystem::exists(config.scriptPath, ignored)) {
        error = L"指定的服务脚本不存在：" + config.scriptPath;
        return false;
    }
    if (std::filesystem::path(config.scriptPath).filename().wstring() != kServiceScriptName) {
        error = std::wstring(L"选中的不是 ") + kServiceScriptName + L"。";
        return false;
    }
    return true;
}

ProbeResult ParseStatusJson(const std::string& body) {
    ProbeResult result;
    if (body.empty()) {
        result.error = L"服务返回了空响应。";
        return result;
    }
    // 至少要看到在线相册自己的字段，否则很可能这个端口被别的程序占了
    if (FindKey(body, "totalWorks") == std::string::npos &&
        FindKey(body, "libraryRoot") == std::string::npos) {
        result.error = L"响应里没有在线相册的字段，这个端口可能是别的程序在用。";
        return result;
    }
    result.reachable = true;
    result.version = Widen(RawValue(body, "version"));
    result.libraryRoot = Widen(RawValue(body, "libraryRoot"));
    result.localIp = Widen(RawValue(body, "ip"));
    // active 这种泛用名必须定位到 watchdog 对象里再取，别的地方也可能有
    if (auto flag = BoolValueAfter(body, "watchdog", "active")) result.watchdogActive = *flag;
    if (auto value = IntValue(body, "totalWorks")) result.totalWorks = *value;
    if (auto flag = BoolValue(body, "staleCode")) result.staleCode = *flag;

    size_t relayAt = FindKey(body, "updateRelay");
    if (relayAt != std::string::npos) {
        std::string tail = body.substr(relayAt);
        long long manifestFailures = IntValue(tail, "manifestFailures").value_or(0);
        long long downloadFailures = IntValue(tail, "downloadFailures").value_or(0);
        long long shaMismatch = IntValue(tail, "shaMismatch").value_or(0);
        result.relayOk = (manifestFailures == 0 && downloadFailures == 0 && shaMismatch == 0);
        std::wstring cached = Widen(RawValue(tail, "cachedVersion"));
        std::wstring lastError = Widen(RawValue(tail, "lastError"));
        if (!lastError.empty()) {
            result.relayNote = L"中转上次出错：" + lastError;
        } else if (!cached.empty()) {
            result.relayNote = L"已缓存手机包 " + cached;
        } else {
            result.relayNote = L"还没代取过手机包";
        }
    }
    if (auto value = IntValueAfter(body, "scanErrors", "count")) result.scanErrors = *value;
    if (auto value = IntValueAfter(body, "scanThrottle", "throttled")) result.throttled = *value;
    return result;
}

std::wstring StatusHeadline(const ProbeResult& result) {
    if (!result.reachable) {
        return L"状态：未运行" + (result.error.empty() ? std::wstring() : (L"（" + result.error + L"）"));
    }
    std::wstring head = L"状态：运行中";
    if (result.totalWorks >= 0) {
        head += L" · 手机可见作品 " + std::to_wstring(result.totalWorks) + L" 套";
    }
    if (!result.version.empty()) head += L" · 服务 V" + result.version;
    return head;
}

std::wstring StatusDetail(const ProbeResult& result, int port) {
    std::wstring detail = L"端口 " + std::to_wstring(port);
    if (!result.reachable) {
        return detail + L" · 点「启动」把这个服务跑起来，手机才能连上电脑在线相册";
    }
    detail += result.staleCode ? L" · 代码陈旧：正在跑的进程落后于磁盘上的脚本，请点「重启」"
                               : L" · 代码新鲜";
    detail += result.relayOk ? L" · 更新中转正常" : L" · 更新中转异常";
    detail += result.watchdogActive ? L" · 文件实时监听中" : L" · 文件监听未开（只能手动刷新）";
    if (result.scanErrors > 0) {
        detail += L" · 扫描跳过 " + std::to_wstring(result.scanErrors) + L" 个异常目录（值得看一眼）";
    }
    if (!result.relayNote.empty()) detail += L" · " + result.relayNote;
    return detail;
}

std::wstring StatusAddress(const ProbeResult& result, int port) {
    if (!result.reachable || result.localIp.empty()) {
        return L"服务跑起来后，这里会显示手机要连的局域网地址。";
    }
    return L"手机连的地址：http://" + result.localIp + L":" + std::to_wstring(port) +
           L"（手机自动扫不到时，可以拿这个手动填）";
}

// ---------------- 系统操作 ----------------

std::wstring ModuleDirectory() {
    wchar_t buffer[MAX_PATH]{};
    GetModuleFileNameW(nullptr, buffer, MAX_PATH);
    return std::filesystem::path(buffer).parent_path().wstring();
}

std::vector<std::wstring> PythonCandidates() {
    std::vector<std::wstring> out;
    // 本机固定的安装位置优先；pythonw.exe 才不会弹出控制台黑框。
    // 用 GetEnvironmentVariableW 而不是 _wgetenv：后者在 MSVC /W4 下会报
    // C4996「可能不安全」，CI 日志里看着像隐患，实际只是 CRT 的旧接口。
    wchar_t localAppData[4096]{};
    const DWORD localAppDataCapacity =
        static_cast<DWORD>(sizeof(localAppData) / sizeof(localAppData[0]));
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, localAppDataCapacity) > 0) {
        std::filesystem::path base(localAppData);
        for (int minor = 313; minor >= 308; --minor) {
            std::wstring folder = L"Python" + std::to_wstring(minor);
            out.push_back((base / L"Programs" / L"Python" / folder / L"pythonw.exe").wstring());
            out.push_back((base / L"Programs" / L"Python" / folder / L"python.exe").wstring());
        }
    }
    // 其次 PATH 上能找到的
    const wchar_t* names[] = {L"pythonw.exe", L"python.exe"};
    for (const wchar_t* name : names) {
        wchar_t buffer[MAX_PATH]{};
        DWORD size = SearchPathW(nullptr, name, nullptr, MAX_PATH, buffer, nullptr);
        if (size > 0 && size < MAX_PATH) out.push_back(buffer);
    }
    return out;
}

ProbeResult Probe(int port) {
    ProbeResult result;
    // ⚠️ 必须 NO_PROXY：这台机器上有 Clash 系统代理，走代理探 127.0.0.1 会被绕到外网去
    HINTERNET session = WinHttpOpen(L"DeviceShareHub/OnlineGalleryProbe",
                                    WINHTTP_ACCESS_TYPE_NO_PROXY,
                                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        result.error = L"无法建立本机探测会话。";
        return result;
    }
    // 探的是本机回环，正常情况下几毫秒就有结果；超时压短是为了「服务假死」时
    // 不会让设置窗口卡上十几秒。
    WinHttpSetTimeouts(session, 1000, 1000, 2000, 2000);
    HINTERNET connection = WinHttpConnect(session, L"127.0.0.1", static_cast<INTERNET_PORT>(port), 0);
    if (!connection) {
        WinHttpCloseHandle(session);
        result.error = L"端口没有在监听（服务没启动）。";
        return result;
    }
    HINTERNET request = WinHttpOpenRequest(connection, L"GET", kStatusPath, nullptr,
                                           WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
    if (!request) {
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
        result.error = L"无法创建探测请求。";
        return result;
    }
    bool sent = WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                   WINHTTP_NO_REQUEST_DATA, 0, 0, 0) != FALSE;
    bool received = sent && WinHttpReceiveResponse(request, nullptr) != FALSE;
    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    if (received) {
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize, WINHTTP_NO_HEADER_INDEX);
    }
    std::string body;
    if (received && status == 200) {
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
            std::string chunk(available, '\0');
            DWORD read = 0;
            if (!WinHttpReadData(request, chunk.data(), available, &read)) break;
            body.append(chunk.data(), read);
            if (read == 0) break;
        }
    }
    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connection);
    WinHttpCloseHandle(session);
    if (!sent || !received) {
        result.error = L"端口没有响应（服务可能正在启动，稍等再刷新）。";
        return result;
    }
    if (status != 200) {
        result.error = L"服务返回了 HTTP " + std::to_wstring(status) + L"。";
        return result;
    }
    return ParseStatusJson(body);
}

std::optional<DWORD> ListeningPid(int port) {
    DWORD size = 0;
    // 先问一次需要多大的缓冲区；第二次才真正取数据（期间可能又变了，所以允许重试几次）
    for (int attempt = 0; attempt < 4; ++attempt) {
        DWORD result = GetExtendedTcpTable(nullptr, &size, FALSE, AF_INET,
                                           TCP_TABLE_OWNER_PID_LISTENER, 0);
        if (result != ERROR_INSUFFICIENT_BUFFER || size == 0) return std::nullopt;
        std::vector<char> buffer(size, '\0');
        PMIB_TCPTABLE_OWNER_PID table = reinterpret_cast<PMIB_TCPTABLE_OWNER_PID>(buffer.data());
        if (GetExtendedTcpTable(table, &size, FALSE, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0) != NO_ERROR) {
            return std::nullopt;
        }
        const USHORT wanted = htons(static_cast<USHORT>(port));
        for (DWORD i = 0; i < table->dwNumEntries; ++i) {
            const MIB_TCPROW_OWNER_PID& row = table->table[i];
            if (row.dwLocalAddr == 0u && row.dwLocalPort == wanted &&
                row.dwState == MIB_TCP_STATE_LISTEN) {
                return row.dwOwningPid;
            }
        }
        // 扫到了表但表里没有这一条：确实是没在监听，不用重试
        return std::nullopt;
    }
    return std::nullopt;
}

bool Start(const Config& config, DWORD& pid, std::wstring& error) {
    if (!Validate(config, error)) return false;
    pid = 0;
    std::wstring command = BuildProcessCommand(config);
    std::vector<wchar_t> mutableCommand(command.begin(), command.end());
    mutableCommand.push_back(L'\0');

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION process{};
    // 工作目录设成脚本所在目录：脚本里有一堆相对路径依赖，从别处启动会找不到素材库。
    std::wstring workingDirectory = std::filesystem::path(config.scriptPath).parent_path().wstring();

    BOOL ok = CreateProcessW(nullptr, mutableCommand.data(), nullptr, nullptr, FALSE,
                             CREATE_NO_WINDOW, nullptr,
                             workingDirectory.empty() ? nullptr : workingDirectory.c_str(),
                             &startup, &process);
    if (!ok) {
        DWORD code = GetLastError();
        error = L"启动失败（系统错误 " + std::to_wstring(code) + L"）。检查解释器与脚本路径是否正确。";
        return false;
    }
    pid = process.dwProcessId;
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return true;
}

bool Stop(int port, std::wstring& error) {
    // ⚠️ 先确认这个端口上跑的确实是在线相册服务，再去结束进程。
    // 只按端口找 PID 就 TerminateProcess 是很危险的事：端口是用户可以手改的，
    // 万一改成了某个别的程序正在用的端口，点一下「停止」就把人家的进程杀了。
    ProbeResult probe = Probe(port);
    if (!probe.reachable) {
        error = L"这个端口上没跑在线相册服务，为避免误伤别的程序，不做任何操作。";
        return false;
    }
    auto pid = ListeningPid(port);
    if (!pid || *pid == 0) {
        error = L"这个端口上现在没有在监听的服务。";
        return false;
    }
    HANDLE handle = OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, *pid);
    if (!handle) {
        error = L"找到了服务进程但没有权限结束它（PID " + std::to_wstring(*pid) + L"）。";
        return false;
    }
    BOOL terminated = TerminateProcess(handle, 0);
    if (terminated) WaitForSingleObject(handle, 5000);
    CloseHandle(handle);
    if (!terminated) {
        error = L"结束服务进程失败（PID " + std::to_wstring(*pid) + L"）。";
        return false;
    }
    return true;
}

bool Restart(const Config& config, DWORD& pid, std::wstring& error) {
    // 端口上已经有服务就先停掉；没有也无所谓（重启的语义是「保证最后是在跑的」）
    if (ListeningPid(config.port).has_value()) {
        std::wstring stopError;
        if (!Stop(config.port, stopError)) {
            error = stopError;
            return false;
        }
        // 等端口真正放开：TerminateProcess 返回后 socket 释放还有一点点延迟
        for (int i = 0; i < 40; ++i) {
            if (!ListeningPid(config.port).has_value()) break;
            Sleep(100);
        }
    }
    return Start(config, pid, error);
}

// ---------------- 配置持久化 ----------------

Config LoadConfig() {
    Config config;
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kConfigKey, 0, nullptr, 0, KEY_READ, nullptr, &key,
                        nullptr) != ERROR_SUCCESS) {
        return config;
    }
    auto readString = [key](const wchar_t* name, std::wstring& out) {
        wchar_t buffer[1024]{};
        DWORD size = sizeof(buffer);
        DWORD type = 0;
        if (RegQueryValueExW(key, name, nullptr, &type, reinterpret_cast<LPBYTE>(buffer),
                             &size) == ERROR_SUCCESS &&
            type == REG_SZ) {
            out.assign(buffer);
        }
    };
    readString(L"PythonPath", config.pythonPath);
    readString(L"ScriptPath", config.scriptPath);
    DWORD port = 0;
    DWORD portSize = sizeof(port);
    if (RegQueryValueExW(key, L"Port", nullptr, nullptr, reinterpret_cast<LPBYTE>(&port),
                         &portSize) == ERROR_SUCCESS &&
        port >= 1 && port <= 65535) {
        config.port = static_cast<int>(port);
    }
    RegCloseKey(key);

    // 没配过就自动探测一次，探测到什么就落盘什么（下次直接可用）
    if (config.scriptPath.empty()) {
        std::error_code ignored;
        for (const auto& candidate : ScriptCandidates(ModuleDirectory())) {
            if (std::filesystem::exists(candidate, ignored)) {
                config.scriptPath = candidate;
                break;
            }
        }
    }
    if (config.pythonPath.empty()) {
        std::error_code ignored;
        for (const auto& candidate : PythonCandidates()) {
            if (std::filesystem::exists(candidate, ignored)) {
                config.pythonPath = candidate;
                break;
            }
        }
    }
    return config;
}

void SaveConfig(const Config& config) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kConfigKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key,
                        nullptr) != ERROR_SUCCESS) {
        return;
    }
    auto writeString = [key](const wchar_t* name, const std::wstring& value) {
        RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
                       static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    };
    writeString(L"PythonPath", config.pythonPath);
    writeString(L"ScriptPath", config.scriptPath);
    DWORD port = static_cast<DWORD>(config.port);
    RegSetValueExW(key, L"Port", 0, REG_DWORD, reinterpret_cast<const BYTE*>(&port), sizeof(port));
    RegCloseKey(key);
}

bool AutoStartEnabled() {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kRunKey, 0, KEY_READ, &key) != ERROR_SUCCESS) return false;
    wchar_t value[1024]{};
    DWORD size = sizeof(value);
    DWORD type = 0;
    LRESULT result = RegQueryValueExW(key, kRunValue, nullptr, &type,
                                      reinterpret_cast<LPBYTE>(value), &size);
    RegCloseKey(key);
    return result == ERROR_SUCCESS && type == REG_SZ && value[0] != L'\0';
}

void SetAutoStart(bool enable, const Config& config) {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kRunKey, 0, KEY_SET_VALUE, &key) != ERROR_SUCCESS) return;
    if (enable) {
        // 开机自启要跑的是服务，不是客户端本体 —— 所以写的是 pythonw + 脚本
        std::wstring command = BuildProcessCommand(config);
        RegSetValueExW(key, kRunValue, 0, REG_SZ, reinterpret_cast<const BYTE*>(command.c_str()),
                       static_cast<DWORD>((command.size() + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(key, kRunValue);
    }
    RegCloseKey(key);
}

void OpenInBrowser(int port) {
    std::wstring url = L"http://127.0.0.1:" + std::to_wstring(port) + L"/";
    ShellExecuteW(nullptr, L"open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
}

}  // namespace online_service
