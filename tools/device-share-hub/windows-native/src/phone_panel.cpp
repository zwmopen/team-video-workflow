#include "phone_panel.h"

// 这里的 JSON 解析不引第三方库，也不复用 main.cpp 里那套「找 key 取子串」的简易工具。
// 原因是本模块要处理**数组里的对象数组**：devices 是一个对象数组，每个对象里又有
// 十几个字段。用「全文找 key」的写法，第二个设备的 name 会把第一个的顶掉 ——
// 这类 bug 不报错，只是数据显示错，最难查。所以这里老老实实按括号分层解析。

#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

#pragma comment(lib, "winhttp.lib")

namespace phone_panel {
namespace {

std::wstring Widen(const std::string& value) {
    if (value.empty()) return {};
    int needed = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                                     nullptr, 0);
    if (needed <= 0) return {};
    std::wstring out(static_cast<size_t>(needed), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                        out.data(), needed);
    return out;
}

std::string Narrow(const std::wstring& value) {
    if (value.empty()) return {};
    int needed = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                                     nullptr, 0, nullptr, nullptr);
    if (needed <= 0) return {};
    std::string out(static_cast<size_t>(needed), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                        out.data(), needed, nullptr, nullptr);
    return out;
}

bool IsSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

size_t SkipSpace(const std::string& t, size_t i, size_t end) {
    while (i < end && IsSpace(t[i])) ++i;
    return i;
}

// 取顶层对象的「体内」范围（不含两端花括号）。找不到或不是对象返回 false。
bool ObjectBody(const std::string& t, size_t& bodyStart, size_t& bodyEnd) {
    size_t i = SkipSpace(t, 0, t.size());
    if (i >= t.size() || t[i] != '{') return false;
    bodyStart = i + 1;
    int depth = 1;
    bool inStr = false;
    // ⚠️ 必须先跳过开头的 '{'：depth 已经算上它了，循环若再从它开始扫会数成 2，
    //    结尾的 '}' 就永远回不到 0 —— 结果是所有响应都被判成「不是合法 JSON」。
    ++i;
    while (i < t.size()) {
        const char c = t[i];
        if (inStr) {
            if (c == '\\') { i += 2; continue; }  // 跳过被转义的字符
            if (c == '"') inStr = false;
            ++i;
            continue;
        }
        if (c == '"') { inStr = true; ++i; continue; }
        if (c == '{') {
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 0) { bodyEnd = i; return true; }
        }
        ++i;
    }
    return false;
}

// 在 [start, end) 这段里找「直接子级」的 key。返回 key 起始引号的位置。
//
// ⚠️ 带括号深度与字符串感知是必须的，两个真实陷阱：
//   ① {"scan": {"devices": [...]}, "devices": [...]} —— 不分层会取到里面那个；
//   ② {"name": "a}b"}                                 —— 不认字符串会把值里的括号算进深度。
size_t FindTopKey(const std::string& t, size_t start, size_t end, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    int depth = 0;
    bool inStr = false;
    size_t i = start;
    while (i < end) {
        const char c = t[i];
        if (inStr) {
            if (c == '\\') { i += 2; continue; }
            if (c == '"') inStr = false;
            ++i;
            continue;
        }
        if (c == '"') {
            inStr = true;
            if (depth == 0 && i + marker.size() <= end &&
                t.compare(i, marker.size(), marker) == 0) {
                size_t after = SkipSpace(t, i + marker.size(), end);
                if (after < end && t[after] == ':') return i;
            }
            ++i;
            continue;
        }
        if (c == '{' || c == '[') ++depth;
        else if (c == '}' || c == ']') --depth;
        ++i;
    }
    return std::string::npos;
}

// 已知 key 在 keyAt，返回它的值范围 [valueStart, valueEnd)。字符串含两端引号。
bool ValueRange(const std::string& t, size_t keyAt, const std::string& key, size_t end,
                size_t& valueStart, size_t& valueEnd) {
    size_t i = keyAt + key.size() + 2;  // 跳过去 "key"
    i = SkipSpace(t, i, end);
    if (i >= end || t[i] != ':') return false;
    ++i;
    i = SkipSpace(t, i, end);
    if (i >= end) return false;
    valueStart = i;
    if (t[i] == '"') {
        ++i;
        while (i < end) {
            if (t[i] == '\\') { i += 2; continue; }
            if (t[i] == '"') break;
            ++i;
        }
        valueEnd = std::min(i + 1, end);
        return true;
    }
    if (t[i] == '{' || t[i] == '[') {
        const char open = t[i];
        const char close = (open == '{') ? '}' : ']';
        int depth = 0;
        bool inStr = false;
        while (i < end) {
            const char c = t[i];
            if (inStr) {
                if (c == '\\') { i += 2; continue; }
                if (c == '"') inStr = false;
                ++i;
                continue;
            }
            if (c == '"') { inStr = true; ++i; continue; }
            if (c == open) ++depth;
            else if (c == close) {
                --depth;
                if (depth == 0) { valueEnd = i + 1; return true; }
            }
            ++i;
        }
        return false;
    }
    // 数字 / true / false / null：读到分隔符为止
    while (i < end && t[i] != ',' && t[i] != '}' && t[i] != ']') ++i;
    valueEnd = i;
    return true;
}

std::wstring StringField(const std::string& t, size_t start, size_t end, const std::string& key,
                         const std::wstring& fallback = {}) {
    size_t keyAt = FindTopKey(t, start, end, key);
    if (keyAt == std::string::npos) return fallback;
    size_t vs = 0, ve = 0;
    if (!ValueRange(t, keyAt, key, end, vs, ve)) return fallback;
    if (ve - vs < 2 || t[vs] != '"') return fallback;
    std::string raw = t.substr(vs + 1, ve - vs - 2);
    // 常见的几个转义还原一遍；\uXXXX 保持原样（设备名里基本不会出现，
    // 半吊子解码解出乱码比原样显示更糟）
    std::string out;
    for (size_t i = 0; i < raw.size(); ++i) {
        if (raw[i] == '\\' && i + 1 < raw.size()) {
            const char n = raw[i + 1];
            if (n == 'n') { out += '\n'; ++i; continue; }
            if (n == 't') { out += '\t'; ++i; continue; }
            if (n == 'r') { out += '\r'; ++i; continue; }
            if (n == '"' || n == '\\' || n == '/') { out += n; ++i; continue; }
        }
        out += raw[i];
    }
    std::wstring wide = Widen(out);
    return wide.empty() ? fallback : wide;
}

long long IntField(const std::string& t, size_t start, size_t end, const std::string& key,
                   long long fallback) {
    size_t keyAt = FindTopKey(t, start, end, key);
    if (keyAt == std::string::npos) return fallback;
    size_t vs = 0, ve = 0;
    if (!ValueRange(t, keyAt, key, end, vs, ve)) return fallback;
    std::string raw = t.substr(vs, ve - vs);
    try {
        size_t consumed = 0;
        double value = std::stod(raw, &consumed);
        return static_cast<long long>(value);
    } catch (...) {
        return fallback;
    }
}

bool BoolField(const std::string& t, size_t start, size_t end, const std::string& key,
               bool fallback) {
    size_t keyAt = FindTopKey(t, start, end, key);
    if (keyAt == std::string::npos) return fallback;
    size_t vs = 0, ve = 0;
    if (!ValueRange(t, keyAt, key, end, vs, ve)) return fallback;
    std::string raw = t.substr(vs, ve - vs);
    return raw == "true" ? true : (raw == "false" ? false : fallback);
}

// 在 [start, end) 里切出所有「直接子级」的对象，返回各自 [objStart, objEnd]（含花括号）。
std::vector<std::pair<size_t, size_t>> SplitObjects(const std::string& t, size_t start,
                                                    size_t end) {
    std::vector<std::pair<size_t, size_t>> out;
    int depth = 0;
    bool inStr = false;
    size_t objStart = std::string::npos;
    size_t i = start;
    while (i < end) {
        const char c = t[i];
        if (inStr) {
            if (c == '\\') { i += 2; continue; }
            if (c == '"') inStr = false;
            ++i;
            continue;
        }
        if (c == '"') { inStr = true; ++i; continue; }
        if (c == '{') {
            if (depth == 0) objStart = i;
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 0 && objStart != std::string::npos) {
                out.emplace_back(objStart, i);
                objStart = std::string::npos;
            }
        }
        ++i;
    }
    return out;
}

Device ParseDevice(const std::string& t, size_t objStart, size_t objEnd) {
    Device device;
    const size_t bodyStart = objStart + 1;
    const size_t bodyEnd = objEnd;
    device.ip = StringField(t, bodyStart, bodyEnd, "ip");
    device.port = static_cast<int>(IntField(t, bodyStart, bodyEnd, "port", 0));
    device.name = StringField(t, bodyStart, bodyEnd, "name");
    device.model = StringField(t, bodyStart, bodyEnd, "model");
    device.appVersion = StringField(t, bodyStart, bodyEnd, "appVersion");
    device.workCount = IntField(t, bodyStart, bodyEnd, "workCount", -1);
    device.verified = BoolField(t, bodyStart, bodyEnd, "verified", false);
    device.lastSeen = StringField(t, bodyStart, bodyEnd, "lastSeen");
    device.error = StringField(t, bodyStart, bodyEnd, "error");
    // 服务端拿不到 /v2/info 时 name 会退化成 ip，这里也一样兜底，免得显示空白
    if (device.name.empty()) device.name = device.ip;
    return device;
}

}  // namespace

Panel ParsePhonesJson(const std::string& body) {
    Panel panel;
    size_t bodyStart = 0, bodyEnd = 0;
    if (!ObjectBody(body, bodyStart, bodyEnd)) {
        panel.error = L"服务返回的不是合法 JSON 对象。";
        return panel;
    }
    // 光有 "ok" 不算数：随便一个 {"ok":true} 也能匹配上，必须同时有 devices 或 ip
    if (FindTopKey(body, bodyStart, bodyEnd, "devices") == std::string::npos &&
        FindTopKey(body, bodyStart, bodyEnd, "ip") == std::string::npos) {
        panel.error = L"服务返回的不是在线手机面板数据。";
        return panel;
    }
    panel.ok = BoolField(body, bodyStart, bodyEnd, "ok", false);
    panel.localIp = StringField(body, bodyStart, bodyEnd, "ip");
    panel.scannedAt = StringField(body, bodyStart, bodyEnd, "scannedAt");
    panel.onlineCount = IntField(body, bodyStart, bodyEnd, "onlineCount", 0);
    panel.totalResponded = IntField(body, bodyStart, bodyEnd, "totalResponded", 0);
    panel.phonePort = static_cast<int>(IntField(body, bodyStart, bodyEnd, "phonePort", 0));

    size_t keyAt = FindTopKey(body, bodyStart, bodyEnd, "devices");
    if (keyAt != std::string::npos) {
        size_t vs = 0, ve = 0;
        if (ValueRange(body, keyAt, "devices", bodyEnd, vs, ve) && ve > vs && body[vs] == '[') {
            for (const auto& range : SplitObjects(body, vs + 1, ve - 1)) {
                panel.devices.push_back(ParseDevice(body, range.first, range.second));
            }
        }
    }
    return panel;
}

SyncResult ParseSyncJson(const std::string& body) {
    SyncResult result;
    size_t bodyStart = 0, bodyEnd = 0;
    if (!ObjectBody(body, bodyStart, bodyEnd)) {
        result.error = L"服务返回的不是合法 JSON 对象。";
        return result;
    }
    if (FindTopKey(body, bodyStart, bodyEnd, "appliedCount") == std::string::npos &&
        FindTopKey(body, bodyStart, bodyEnd, "error") == std::string::npos) {
        result.error = L"服务返回的不是次数同步结果。";
        return result;
    }
    result.ok = BoolField(body, bodyStart, bodyEnd, "ok", false);
    // dryRun 可能出现在顶层，也可能只在嵌套的 results 里；顶层优先
    result.dryRun = BoolField(body, bodyStart, bodyEnd, "dryRun", false);
    result.phoneCount = IntField(body, bodyStart, bodyEnd, "phoneCount", 0);
    result.appliedCount = IntField(body, bodyStart, bodyEnd, "appliedCount", 0);
    result.message = StringField(body, bodyStart, bodyEnd, "message");
    result.error = StringField(body, bodyStart, bodyEnd, "error");

    size_t keyAt = FindTopKey(body, bodyStart, bodyEnd, "results");
    if (keyAt != std::string::npos) {
        size_t vs = 0, ve = 0;
        if (ValueRange(body, keyAt, "results", bodyEnd, vs, ve) && ve > vs && body[vs] == '[') {
            for (const auto& range : SplitObjects(body, vs + 1, ve - 1)) {
                std::wstring reason = StringField(body, range.first + 1, range.second, "error");
                if (reason.empty()) continue;
                std::wstring phone = StringField(body, range.first + 1, range.second, "phone");
                result.phoneErrors.push_back(phone.empty() ? reason : (phone + L"：" + reason));
            }
        }
    }
    return result;
}

std::wstring PanelHeadline(const Panel& panel) {
    if (!panel.error.empty()) return L"在线手机：" + panel.error;
    if (panel.onlineCount <= 0) {
        if (panel.totalResponded > 0) {
            return L"在线手机：有 " + std::to_wstring(panel.totalResponded) +
                   L" 台设备应答，但没有一台是自家相册 App";
        }
        return L"在线手机：一台都没扫到（手机要跟电脑在同一个局域网、相册 App 在前台）";
    }
    std::wstring head = L"在线手机：" + std::to_wstring(panel.onlineCount) + L" 台";
    if (panel.totalResponded > panel.onlineCount) {
        head += L"（另有 " + std::to_wstring(panel.totalResponded - panel.onlineCount) +
                L" 台设备占用了同端口但不是相册 App）";
    }
    if (!panel.scannedAt.empty()) head += L" · 扫描于 " + panel.scannedAt;
    return head;
}

std::wstring DeviceLine(const Device& device) {
    std::wstring line = device.ip;
    if (device.port > 0) line += L":" + std::to_wstring(device.port);
    line += L"  " + (device.name.empty() ? device.ip : device.name);
    if (!device.model.empty()) line += L"（" + device.model + L"）";
    if (!device.appVersion.empty()) line += L" V" + device.appVersion;
    if (device.workCount >= 0) line += L" · 手机内 " + std::to_wstring(device.workCount) + L" 个作品";
    if (!device.verified) {
        line += device.error.empty()
                    ? L" · 不是自家相册 App，已跳过"
                    : (L" · 未识别（" + device.error + L"）");
    }
    return line;
}

std::wstring PanelWarning(const Panel& panel) {
    if (!panel.error.empty() || panel.onlineCount <= 0) return {};
    long long verified = 0;
    for (const auto& device : panel.devices) {
        if (device.verified) ++verified;
    }
    if (verified == 0) {
        return L"服务端说在线 " + std::to_wstring(panel.onlineCount) +
               L" 台，但列表里一台可确认的都没有 —— 刷新一次再看。";
    }
    if (verified != panel.onlineCount) {
        return L"服务端说在线 " + std::to_wstring(panel.onlineCount) + L" 台，列表里确认了 " +
               std::to_wstring(verified) + L" 台，以刷新后的结果为准。";
    }
    return {};
}

std::wstring SyncSummary(const SyncResult& result) {
    if (!result.error.empty()) return result.error;
    std::wstring text = result.dryRun ? L"预演（没有落盘）：" : L"已落盘：";
    text += L"共回写 " + std::to_wstring(result.appliedCount) + L" 个作品的使用次数";
    if (result.phoneCount > 0) {
        text += L"（" + std::to_wstring(result.phoneCount) + L" 台手机）";
    }
    if (!result.message.empty()) text += L"。服务端：" + result.message;
    for (const auto& reason : result.phoneErrors) text += L"\r\n  · " + reason;
    return text;
}

std::string BuildSyncRequestBody(bool dryRun, const std::vector<std::wstring>& hosts) {
    std::string json = "{\"dryRun\":";
    json += dryRun ? "true" : "false";
    if (!hosts.empty()) {
        json += ",\"hosts\":[";
        for (size_t i = 0; i < hosts.size(); ++i) {
            if (i > 0) json += ",";
            std::string host = Narrow(hosts[i]);
            // 手工转义：host 来自界面输入，引号和反斜杠必须处理掉，否则 JSON 直接坏掉
            json += "\"";
            for (char c : host) {
                if (c == '"' || c == '\\') json += '\\';
                json += c;
            }
            json += "\"";
        }
        json += "]";
    }
    json += "}";
    return json;
}

// ------------------------------------------------------------------
// 系统操作
// ------------------------------------------------------------------
namespace {

// 与 online_service::Probe 同样的三条纪律：
//   ① NO_PROXY（本机有 Clash 系统代理，走代理探 127.0.0.1 会被绕到外网）
//   ② 只连 127.0.0.1
//   ③ 超时压短，避免服务假死时把设置窗口卡住
struct Response {
    int status = 0;
    std::string body;
    std::wstring error;
};

Response SendToLocalService(const wchar_t* method, const wchar_t* path, int port,
                            const std::string* payload, int timeoutMs) {
    Response out;
    HINTERNET session = WinHttpOpen(L"DeviceShareHub/PhonePanel",
                                    WINHTTP_ACCESS_TYPE_NO_PROXY,
                                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        out.error = L"无法建立本机连接会话。";
        return out;
    }
    WinHttpSetTimeouts(session, timeoutMs, timeoutMs, timeoutMs, timeoutMs);
    HINTERNET connection = WinHttpConnect(session, L"127.0.0.1",
                                          static_cast<INTERNET_PORT>(port), 0);
    if (!connection) {
        WinHttpCloseHandle(session);
        out.error = L"在线相册服务没在监听（先回上面把它启动）。";
        return out;
    }
    HINTERNET request = WinHttpOpenRequest(connection, method, path, nullptr,
                                           WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
    if (!request) {
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
        out.error = L"无法创建请求。";
        return out;
    }
    // 几个参数在「有 / 没有请求体」两种情况下类型不一样，拆成显式变量，
    // 免得塞进三元表达式里被 MSVC 的 /permissive- 挑类型毛病。
    const wchar_t* headers = WINHTTP_NO_ADDITIONAL_HEADERS;
    DWORD headersLength = 0;
    LPVOID body = WINHTTP_NO_REQUEST_DATA;
    DWORD bodyLength = 0;
    std::wstring contentType = L"Content-Type: application/json\r\n";
    if (payload) {
        headers = contentType.c_str();
        headersLength = static_cast<DWORD>(-1L);
        body = const_cast<LPVOID>(static_cast<LPCVOID>(payload->data()));
        bodyLength = static_cast<DWORD>(payload->size());
    }
    BOOL sent = WinHttpSendRequest(request, headers, headersLength, body, bodyLength,
                                   bodyLength, 0) != FALSE;
    BOOL received = sent && WinHttpReceiveResponse(request, nullptr);
    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    if (received) {
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                            WINHTTP_NO_HEADER_INDEX);
    }
    if (received && status == 200) {
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
            std::string chunk(available, '\0');
            DWORD read = 0;
            if (!WinHttpReadData(request, chunk.data(), available, &read)) break;
            out.body.append(chunk.data(), read);
            if (read == 0) break;
        }
    }
    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connection);
    WinHttpCloseHandle(session);
    if (!sent || !received) {
        out.error = L"服务没有响应（可能正在启动，稍等再试）。";
        return out;
    }
    out.status = static_cast<int>(status);
    if (status != 200) {
        out.error = L"服务返回了 HTTP " + std::to_wstring(status) + L"。";
    }
    return out;
}

}  // namespace

Panel FetchPhones(int port, bool refresh) {
    // refresh=1 会丢掉服务端 30 秒缓存重扫整个 /24 网段，254 个地址探一遍要几秒，
    // 所以超时给 20 秒；读缓存那次 5 秒足够。
    const std::wstring path = refresh ? L"/api/online/phones?refresh=1" : L"/api/online/phones";
    Response response = SendToLocalService(L"GET", path.c_str(), port, nullptr,
                                           refresh ? 20000 : 5000);
    Panel panel;
    if (!response.error.empty()) {
        panel.error = response.error;
        return panel;
    }
    panel = ParsePhonesJson(response.body);
    return panel;
}

SyncResult SyncCounts(int port, bool dryRun, const std::vector<std::wstring>& hosts) {
    const std::string payload = BuildSyncRequestBody(dryRun, hosts);
    // 同步要一台一台拉手机的 /v2/works 再写盘，比探测慢得多，超时给 60 秒
    Response response = SendToLocalService(L"POST", L"/api/online/sync-phone-counts", port,
                                           &payload, 60000);
    SyncResult result;
    if (!response.error.empty()) {
        result.error = response.error;
        return result;
    }
    result = ParseSyncJson(response.body);
    // 服务端对「未发现在线手机」返回的是 HTTP 200 + ok:false + error，
    // 这里把它当成正常结果展示，而不是当成网络错误
    return result;
}

}  // namespace phone_panel
