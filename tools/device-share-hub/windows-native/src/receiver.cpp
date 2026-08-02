#include "receiver.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shlobj.h>
#include <bcrypt.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <cwchar>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <vector>

namespace {
constexpr unsigned short PORT = 45833;
constexpr size_t MAX_HEADERS = 64 * 1024;
constexpr unsigned long long MAX_FILE = 4ull * 1024 * 1024 * 1024;
std::mutex gReceiveRootMutex;
std::filesystem::path gConfiguredReceiveRoot;

struct ReceivedFile { std::wstring name; std::filesystem::path path; };
struct Task {
    std::string id;
    int expected = 0;
    std::filesystem::path directory;
    std::map<int, ReceivedFile> files;
    std::string messageId;
    std::string originId;
    std::string destinationId;
    std::string previousHopId;
    std::string contentKind;
    long long expiresAt = 0;
    int hopLimit = 0;
};

std::wstring Utf8ToWide(const std::string& value) {
    if (value.empty()) return {};
    int count = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(static_cast<size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count);
    return result;
}

std::string WideToUtf8(const std::wstring& value) {
    if (value.empty()) return {};
    int count = WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    std::string result(static_cast<size_t>(count), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count, nullptr, nullptr);
    return result;
}

std::string Lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}

std::vector<std::string> Split(const std::string& value, char delimiter) {
    std::vector<std::string> result; size_t start = 0;
    while (start <= value.size()) {
        size_t end = value.find(delimiter, start); if (end == std::string::npos) end = value.size();
        result.push_back(value.substr(start, end - start)); if (end == value.size()) break; start = end + 1;
    }
    return result;
}

std::string PercentDecode(const std::string& value) {
    std::string result;
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] == '%' && i + 2 < value.size()) {
            char hex[3]{value[i + 1], value[i + 2], 0};
            char* end = nullptr; long number = std::strtol(hex, &end, 16);
            if (end && *end == 0) { result.push_back(static_cast<char>(number)); i += 2; continue; }
        }
        result.push_back(value[i] == '+' ? ' ' : value[i]);
    }
    return result;
}

std::wstring SafeName(const std::string& value) {
    std::wstring name = std::filesystem::path(Utf8ToWide(PercentDecode(value))).filename().wstring();
    for (wchar_t& c : name) if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) c = L'_';
    while (!name.empty() && (name.back() == L'.' || name.back() == L' ')) name.pop_back();
    return name.empty() ? L"文件" : name;
}

std::filesystem::path CacheRoot() {
    wchar_t path[MAX_PATH]{}; SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, SHGFP_TYPE_CURRENT, path);
    auto root = std::filesystem::path(path) / L"ZwmDeviceShareHub" / L"incoming";
    std::filesystem::create_directories(root); return root;
}

std::filesystem::path ReceiveRoot() {
    {
        std::lock_guard<std::mutex> lock(gReceiveRootMutex);
        if (!gConfiguredReceiveRoot.empty() && std::filesystem::is_directory(gConfiguredReceiveRoot)) {
            return gConfiguredReceiveRoot;
        }
    }
    PWSTR path = nullptr; std::filesystem::path root;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Downloads, 0, nullptr, &path))) { root = path; CoTaskMemFree(path); }
    else root = std::filesystem::temp_directory_path();
    root /= L"相册收件箱"; std::filesystem::create_directories(root); return root;
}

std::filesystem::path Unique(const std::filesystem::path& root, const std::wstring& name) {
    auto original = root / name; if (!std::filesystem::exists(original)) return original;
    std::filesystem::path p(name); auto stem = p.stem().wstring(), ext = p.extension().wstring();
    for (int i = 1; i < 10000; ++i) { auto candidate = root / (stem + L" (" + std::to_wstring(i) + L")" + ext); if (!std::filesystem::exists(candidate)) return candidate; }
    return root / (std::to_wstring(GetTickCount64()) + L"-" + name);
}

uint16_t Le16(const unsigned char* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t Le32(const unsigned char* p) { return static_cast<uint32_t>(Le16(p) | (static_cast<uint32_t>(Le16(p + 2)) << 16)); }

int ExtractStoredZip(const std::filesystem::path& archive, const std::filesystem::path& root) {
    std::ifstream input(archive, std::ios::binary); if (!input) throw std::runtime_error("无法读取文件夹传送包");
    auto staging = root / (L".相册接收-" + std::to_wstring(GetTickCount64())); std::filesystem::create_directories(staging);
    int count = 0;
    try {
        while (true) {
            unsigned char signature[4]{}; input.read(reinterpret_cast<char*>(signature), 4);
            if (input.gcount() == 0) break; if (input.gcount() != 4) throw std::runtime_error("文件夹传送包损坏");
            uint32_t sig = Le32(signature); if (sig == 0x02014b50 || sig == 0x06054b50) break;
            if (sig != 0x04034b50) throw std::runtime_error("文件夹传送包损坏");
            unsigned char header[26]{}; input.read(reinterpret_cast<char*>(header), 26); if (input.gcount() != 26) throw std::runtime_error("文件夹传送包损坏");
            if (Le16(header + 2) & 0x0008 || Le16(header + 4) != 0) throw std::runtime_error("文件夹压缩格式不受支持");
            uint32_t size = Le32(header + 14); uint16_t nameLength = Le16(header + 22), extraLength = Le16(header + 24);
            std::string raw(nameLength, '\0'); input.read(raw.data(), nameLength); input.seekg(extraLength, std::ios::cur);
            std::replace(raw.begin(), raw.end(), '\\', '/');
            if (raw.empty() || raw[0] == '/' || raw.find(':') != std::string::npos) throw std::runtime_error("文件夹包含不安全路径");
            auto pieces = Split(raw, '/'); std::filesystem::path destination = staging;
            for (const auto& piece : pieces) { if (piece.empty()) continue; if (piece == "." || piece == "..") throw std::runtime_error("文件夹包含不安全路径"); destination /= Utf8ToWide(piece); }
            bool directory = raw.back() == '/';
            if (directory) std::filesystem::create_directories(destination);
            else {
                std::filesystem::create_directories(destination.parent_path()); std::ofstream output(destination, std::ios::binary);
                std::vector<char> buffer(1024 * 1024); uint32_t remaining = size;
                while (remaining) { size_t take = std::min<size_t>(buffer.size(), remaining); input.read(buffer.data(), static_cast<std::streamsize>(take)); if (static_cast<size_t>(input.gcount()) != take) throw std::runtime_error("文件夹传送包不完整"); output.write(buffer.data(), static_cast<std::streamsize>(take)); remaining -= static_cast<uint32_t>(take); }
                ++count; if (count > 10000) throw std::runtime_error("文件夹内文件过多");
            }
        }
        for (const auto& child : std::filesystem::directory_iterator(staging)) std::filesystem::rename(child.path(), Unique(root, child.path().filename().wstring()));
        std::filesystem::remove_all(staging); return count;
    } catch (...) { std::error_code ignored; std::filesystem::remove_all(staging, ignored); throw; }
}

std::string Sha256(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm = nullptr; BCRYPT_HASH_HANDLE hash = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) throw std::runtime_error("校验初始化失败");
    DWORD objectSize = 0, bytes = 0, hashSize = 0;
    BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize), sizeof(objectSize), &bytes, 0);
    BCryptGetProperty(algorithm, BCRYPT_HASH_LENGTH, reinterpret_cast<PUCHAR>(&hashSize), sizeof(hashSize), &bytes, 0);
    std::vector<unsigned char> object(objectSize), digest(hashSize);
    BCryptCreateHash(algorithm, &hash, object.data(), objectSize, nullptr, 0, 0);
    std::ifstream input(path, std::ios::binary); std::vector<unsigned char> buffer(1024 * 1024);
    while (input) { input.read(reinterpret_cast<char*>(buffer.data()), buffer.size()); auto n = input.gcount(); if (n > 0) BCryptHashData(hash, buffer.data(), static_cast<ULONG>(n), 0); }
    BCryptFinishHash(hash, digest.data(), hashSize, 0); BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
    static const char hex[] = "0123456789abcdef"; std::string result; result.reserve(digest.size() * 2);
    for (auto b : digest) { result.push_back(hex[b >> 4]); result.push_back(hex[b & 15]); } return result;
}

void SendResponse(SOCKET socket, int status, const std::string& body) {
    const char* reason = status >= 200 && status < 300 ? "OK" : "Error";
    std::ostringstream response; response << "HTTP/1.1 " << status << ' ' << reason << "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " << body.size() << "\r\nConnection: close\r\n\r\n" << body;
    auto wire = response.str(); send(socket, wire.data(), static_cast<int>(wire.size()), 0);
}

std::string JsonString(const std::string& json, const std::string& key) {
    auto start = json.find('"' + key + '"'); if (start == std::string::npos) return {};
    start = json.find(':', start); start = json.find('"', start); if (start == std::string::npos) return {};
    auto end = json.find('"', start + 1); return end == std::string::npos ? std::string() : json.substr(start + 1, end - start - 1);
}
int JsonInt(const std::string& json, const std::string& key) {
    auto start = json.find('"' + key + '"'); if (start == std::string::npos) return 0;
    start = json.find(':', start); if (start == std::string::npos) return 0; return std::atoi(json.c_str() + start + 1);
}
long long JsonInt64(const std::string& json, const std::string& key) {
    auto start = json.find('"' + key + '"'); if (start == std::string::npos) return 0;
    start = json.find(':', start); if (start == std::string::npos) return 0;
    return std::strtoll(json.c_str() + start + 1, nullptr, 10);
}

void PersistRelayTask(const Task& task) {
    std::ofstream output(task.directory / L"relay.meta", std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("无法保存中转状态");
    output << task.messageId << '\n' << task.originId << '\n' << task.destinationId << '\n'
           << task.previousHopId << '\n' << task.contentKind << '\n' << task.expiresAt << '\n'
           << task.hopLimit << '\n' << task.expected << '\n';
    for (const auto& [index, file] : task.files) {
        output << index << '|' << WideToUtf8(file.name) << '|'
               << WideToUtf8(file.path.filename().wstring()) << '\n';
    }
    output.flush();
}

std::vector<Task> LoadRelayQueue() {
    std::vector<Task> result;
    for (const auto& directory : std::filesystem::directory_iterator(CacheRoot())) {
        if (!directory.is_directory()) continue;
        auto metadata = directory.path() / L"relay.meta";
        if (!std::filesystem::is_regular_file(metadata)) continue;
        std::ifstream input(metadata, std::ios::binary);
        Task task; task.directory = directory.path();
        std::string line;
        if (!std::getline(input, task.messageId)
            || !std::getline(input, task.originId)
            || !std::getline(input, task.destinationId)
            || !std::getline(input, task.previousHopId)
            || !std::getline(input, task.contentKind)
            || !std::getline(input, line)) continue;
        task.expiresAt = std::strtoll(line.c_str(), nullptr, 10);
        if (!std::getline(input, line)) continue; task.hopLimit = std::atoi(line.c_str());
        if (!std::getline(input, line)) continue; task.expected = std::atoi(line.c_str());
        while (std::getline(input, line)) {
            auto first = line.find('|'), second = line.find('|', first == std::string::npos ? first : first + 1);
            if (first == std::string::npos || second == std::string::npos) continue;
            int index = std::atoi(line.substr(0, first).c_str());
            auto path = task.directory / Utf8ToWide(line.substr(second + 1));
            if (std::filesystem::is_regular_file(path)) {
                task.files[index] = {Utf8ToWide(line.substr(first + 1, second - first - 1)), path};
            }
        }
        if (!task.messageId.empty() && static_cast<int>(task.files.size()) == task.expected) {
            result.push_back(std::move(task));
        }
    }
    return result;
}

void Handle(SOCKET client, Task& task, std::vector<Task>& relayQueue,
            const std::string& ownDeviceId,
            const std::function<void(const std::string&)>& clipboard,
            const std::function<void(const std::wstring&)>& status,
            const std::function<void(const std::wstring&, const std::wstring&)>& log) {
    std::string data; char buffer[64 * 1024]; size_t headerEnd = std::string::npos;
    while ((headerEnd = data.find("\r\n\r\n")) == std::string::npos) {
        int n = recv(client, buffer, sizeof(buffer), 0); if (n <= 0) throw std::runtime_error("请求中断");
        data.append(buffer, n); if (data.size() > MAX_HEADERS) throw std::runtime_error("请求头过大");
    }
    std::string headerText = data.substr(0, headerEnd), initial = data.substr(headerEnd + 4);
    std::istringstream lines(headerText); std::string first; std::getline(lines, first); if (!first.empty() && first.back() == '\r') first.pop_back();
    std::istringstream firstLine(first); std::string method, path, version; firstLine >> method >> path >> version;
    std::map<std::string, std::string> headers; std::string line;
    while (std::getline(lines, line)) { if (!line.empty() && line.back() == '\r') line.pop_back(); auto colon = line.find(':'); if (colon == std::string::npos) continue; auto key = Lower(line.substr(0, colon)); auto value = line.substr(colon + 1); while (!value.empty() && value.front() == ' ') value.erase(value.begin()); headers[key] = value; }
    unsigned long long length = headers.count("content-length") ? std::strtoull(headers["content-length"].c_str(), nullptr, 10) : 0;
    if (length > MAX_FILE) throw std::runtime_error("文件过大");
    auto readBody = [&](std::ostream& output) {
        unsigned long long received = 0; if (!initial.empty()) { size_t take = static_cast<size_t>(std::min<unsigned long long>(length, initial.size())); output.write(initial.data(), take); received += take; }
        while (received < length) { int n = recv(client, buffer, static_cast<int>(std::min<unsigned long long>(sizeof(buffer), length - received)), 0); if (n <= 0) throw std::runtime_error("文件传送中断"); output.write(buffer, n); received += n; }
    };
    if (method == "GET" && path == "/v2/info") { SendResponse(client, 200, "{\"protocol\":2,\"state\":\"online\",\"relayVersion\":1,\"relayEnabled\":true}"); return; }
    if (method == "POST" && path == "/v2/clipboard") {
        std::ostringstream body; readBody(body); clipboard(body.str());
        SendResponse(client, 200, "{\"ok\":true}"); return;
    }
    if (method == "POST" && path == "/v2/tasks") {
        std::ostringstream body; readBody(body); auto json = body.str(); auto id = JsonString(json, "taskId"); int count = JsonInt(json, "fileCount");
        if (id.empty() || count < 1 || count > 100) { SendResponse(client, 400, "任务信息无效"); return; }
        if (!task.id.empty()) { SendResponse(client, 409, "电脑正在接收另一批文件"); return; }
        task.id = id; task.expected = count; task.directory = CacheRoot() / Utf8ToWide(id); std::filesystem::remove_all(task.directory); std::filesystem::create_directories(task.directory);
        task.messageId = JsonString(json, "messageId");
        task.originId = JsonString(json, "originId");
        task.destinationId = JsonString(json, "destinationId");
        task.previousHopId = JsonString(json, "previousHopId");
        task.contentKind = JsonString(json, "contentKind");
        task.expiresAt = JsonInt64(json, "expiresAt");
        task.hopLimit = JsonInt(json, "hopLimit");
        status(L"正在接收 " + std::to_wstring(count) + L" 个项目…"); log(L"incoming_task", Utf8ToWide(id)); SendResponse(client, 201, "OK"); return;
    }
    auto pieces = Split(path, '/');
    if (method == "PUT" && pieces.size() == 6 && pieces[1] == "v2" && pieces[2] == "tasks" && pieces[4] == "files") {
        if (task.id != pieces[3]) { SendResponse(client, 404, "接收任务不存在"); return; }
        int index = std::atoi(pieces[5].c_str()); if (index < 0 || index >= task.expected) { SendResponse(client, 400, "文件序号无效"); return; }
        auto name = SafeName(headers.count("x-file-name") ? headers["x-file-name"] : "file"); auto target = task.directory / (std::to_wstring(index) + L".part");
        std::ofstream output(target, std::ios::binary); readBody(output); output.close();
        auto expected = Lower(headers.count("x-file-sha256") ? headers["x-file-sha256"] : ""); if (!expected.empty() && Sha256(target) != expected) { std::filesystem::remove(target); SendResponse(client, 422, "文件校验失败，请重试"); return; }
        task.files[index] = {name, target}; status(L"已接收 " + std::to_wstring(task.files.size()) + L"/" + std::to_wstring(task.expected)); SendResponse(client, 200, "OK"); return;
    }
    if (method == "POST" && pieces.size() == 5 && pieces[1] == "v2" && pieces[2] == "tasks" && task.id == pieces[3]) {
        if (pieces[4] == "cancel") { std::filesystem::remove_all(task.directory); task = {}; status(L"传送已取消"); SendResponse(client, 200, "OK"); return; }
        if (pieces[4] == "commit") {
            if (static_cast<int>(task.files.size()) != task.expected) { SendResponse(client, 409, "文件尚未接收完整"); return; }
            if (!task.destinationId.empty() && task.destinationId != ownDeviceId) {
                PersistRelayTask(task);
                relayQueue.push_back(std::move(task)); task = {};
                status(L"截图已进入中转队列");
                SendResponse(client, 202, "{\"relayStatus\":\"queued\"}"); return;
            }
            auto root = ReceiveRoot(); int count = 0;
            for (int i = 0; i < task.expected; ++i) { auto& file = task.files.at(i); auto lower = Lower(WideToUtf8(file.name)); if (lower.rfind("album-folder-", 0) == 0 && lower.size() > 4 && lower.substr(lower.size() - 4) == ".zip") count += ExtractStoredZip(file.path, root); else { std::filesystem::rename(file.path, Unique(root, file.name)); ++count; } }
            auto id = task.id; std::filesystem::remove_all(task.directory); task = {}; status(L"已收到 " + std::to_wstring(count) + L" 个文件，保存在“下载\\相册收件箱”"); log(L"incoming_commit", Utf8ToWide(id)); SendResponse(client, 200, "OK"); return;
        }
    }
    SendResponse(client, 404, "请求路径不存在");
}

void ProcessRelayQueue(std::vector<Task>& queued,
                       const std::function<bool(const RelayIncomingTask&, std::wstring&)>& relay,
                       const std::function<void(const std::wstring&)>& status,
                       const std::function<void(const std::wstring&, const std::wstring&)>& log) {
    auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    for (auto it = queued.begin(); it != queued.end();) {
        if (it->expiresAt <= now || it->hopLimit <= 0) {
            std::error_code ignored; std::filesystem::remove_all(it->directory, ignored);
            log(L"relay_expired", Utf8ToWide(it->messageId));
            it = queued.erase(it); continue;
        }
        RelayIncomingTask package;
        package.messageId = it->messageId; package.originId = it->originId;
        package.destinationId = it->destinationId; package.previousHopId = it->previousHopId;
        package.contentKind = it->contentKind; package.expiresAt = it->expiresAt;
        package.hopLimit = it->hopLimit;
        for (const auto& [index, file] : it->files) package.files.push_back({file.name, file.path});
        std::wstring error;
        if (relay(package, error)) {
            std::error_code ignored; std::filesystem::remove_all(it->directory, ignored);
            log(L"relay_delivered", Utf8ToWide(it->messageId));
            status(L"截图中转完成");
            it = queued.erase(it);
        } else {
            if (!error.empty()) log(L"relay_retry_waiting", error);
            ++it;
        }
    }
}
}

void SetReceiveRoot(const std::filesystem::path& root) {
    std::lock_guard<std::mutex> lock(gReceiveRootMutex);
    gConfiguredReceiveRoot = root;
}

void RunLanReceiver(std::atomic<bool>& running,
                    const std::function<void(const std::wstring&)>& status,
                    const std::function<void(const std::wstring&, const std::wstring&)>& log,
                    const std::string& ownDeviceId,
                    const std::function<bool(const RelayIncomingTask&, std::wstring&)>& relay,
                    const std::function<void(const std::string&)>& clipboard) {
    WSADATA data{}; if (WSAStartup(MAKEWORD(2, 2), &data) != 0) { log(L"receiver_failed", L"WSAStartup"); return; }
    SOCKET server = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP); if (server == INVALID_SOCKET) { WSACleanup(); return; }
    BOOL yes = TRUE; setsockopt(server, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = INADDR_ANY; address.sin_port = htons(PORT);
    if (bind(server, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == SOCKET_ERROR || listen(server, 4) == SOCKET_ERROR) { log(L"receiver_failed", L"tcp port 45833 occupied"); closesocket(server); WSACleanup(); return; }
    log(L"receiver_ready", L"tcp=45833"); Task task;
    std::vector<Task> relayQueue = LoadRelayQueue();
    while (running) {
        fd_set set; FD_ZERO(&set); FD_SET(server, &set); timeval timeout{0, 350000};
        if (select(0, &set, nullptr, nullptr, &timeout) <= 0) {
            ProcessRelayQueue(relayQueue, relay, status, log); continue;
        }
        SOCKET client = accept(server, nullptr, nullptr); if (client == INVALID_SOCKET) continue;
        try { Handle(client, task, relayQueue, ownDeviceId, clipboard, status, log); }
        catch (const std::exception& error) { log(L"receiver_request_failed", Utf8ToWide(error.what())); SendResponse(client, 500, error.what()); }
        closesocket(client);
    }
    if (!task.directory.empty()) { std::error_code ignored; std::filesystem::remove_all(task.directory, ignored); }
    closesocket(server); WSACleanup();
}
