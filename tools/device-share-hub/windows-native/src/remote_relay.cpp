#include "remote_relay.h"
#include "p2p_transport.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <winhttp.h>

#include <algorithm>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <random>

#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "winhttp.lib")

namespace remote_relay {
namespace {

constexpr char kEndpoint[] = "https://zwm-device-share-relay.zwmrpg.workers.dev";
constexpr wchar_t kHost[] = L"zwm-device-share-relay.zwmrpg.workers.dev";
constexpr wchar_t kUserAgent[] = L"DeviceShareHub/4.3.12";
constexpr size_t kMaxResponseBytes = 2 * 1024 * 1024;
constexpr uint64_t kMaxTransferBytes = 20ull * 1024ull * 1024ull * 1024ull;

struct AdminIdentity {
    std::vector<uint8_t> privateBlob;
    std::string x;
    std::string y;
    std::string workspaceId;
};

std::string JsonEscape(const std::string& value) {
    std::string output;
    output.reserve(value.size() + 16);
    for (unsigned char c : value) {
        switch (c) {
        case '"': output += "\\\""; break;
        case '\\': output += "\\\\"; break;
        case '\b': output += "\\b"; break;
        case '\f': output += "\\f"; break;
        case '\n': output += "\\n"; break;
        case '\r': output += "\\r"; break;
        case '\t': output += "\\t"; break;
        default:
            if (c < 0x20) {
                char buffer[7]{};
                sprintf_s(buffer, "\\u%04x", c);
                output += buffer;
            } else {
                output.push_back(static_cast<char>(c));
            }
        }
    }
    return output;
}

std::string Base64Url(const uint8_t* data, size_t size) {
    if (!data || size == 0) return {};
    DWORD required = 0;
    if (!CryptBinaryToStringA(data, static_cast<DWORD>(size),
                              CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF,
                              nullptr, &required)) return {};
    std::string output(required, '\0');
    if (!CryptBinaryToStringA(data, static_cast<DWORD>(size),
                              CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF,
                              output.data(), &required)) return {};
    output.resize(required);
    while (!output.empty() && (output.back() == '=' || output.back() == '\0')) output.pop_back();
    std::replace(output.begin(), output.end(), '+', '-');
    std::replace(output.begin(), output.end(), '/', '_');
    return output;
}

std::vector<uint8_t> Sha256(const std::string& value) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    DWORD objectLength = 0;
    DWORD hashLength = 0;
    DWORD received = 0;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0
        || BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH,
                             reinterpret_cast<PUCHAR>(&objectLength), sizeof(objectLength), &received, 0) != 0
        || BCryptGetProperty(algorithm, BCRYPT_HASH_LENGTH,
                             reinterpret_cast<PUCHAR>(&hashLength), sizeof(hashLength), &received, 0) != 0) {
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("SHA-256 初始化失败");
    }
    std::vector<uint8_t> object(objectLength), result(hashLength);
    if (BCryptCreateHash(algorithm, &hash, object.data(), objectLength, nullptr, 0, 0) != 0
        || BCryptHashData(hash, reinterpret_cast<PUCHAR>(const_cast<char*>(value.data())),
                          static_cast<ULONG>(value.size()), 0) != 0
        || BCryptFinishHash(hash, result.data(), hashLength, 0) != 0) {
        if (hash) BCryptDestroyHash(hash);
        BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("SHA-256 计算失败");
    }
    BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return result;
}

std::vector<uint8_t> Sha256File(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    DWORD objectLength = 0;
    DWORD hashLength = 0;
    DWORD received = 0;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0
        || BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH,
                             reinterpret_cast<PUCHAR>(&objectLength), sizeof(objectLength), &received, 0) != 0
        || BCryptGetProperty(algorithm, BCRYPT_HASH_LENGTH,
                             reinterpret_cast<PUCHAR>(&hashLength), sizeof(hashLength), &received, 0) != 0) {
        if (hash) BCryptDestroyHash(hash);
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("SHA-256 初始化失败");
    }
    std::vector<uint8_t> object(objectLength), digest(hashLength);
    if (BCryptCreateHash(algorithm, &hash, object.data(), objectLength, nullptr, 0, 0) != 0) {
        BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("SHA-256 创建失败");
    }
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("无法读取远程作品文件");
    }
    std::vector<char> buffer(1024 * 1024);
    while (input) {
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        auto count = input.gcount();
        if (count > 0 && BCryptHashData(hash, reinterpret_cast<PUCHAR>(buffer.data()),
                                        static_cast<ULONG>(count), 0) != 0) {
            BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
            throw std::runtime_error("SHA-256 计算失败");
        }
    }
    if (BCryptFinishHash(hash, digest.data(), hashLength, 0) != 0) {
        BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("SHA-256 完成失败");
    }
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
    return digest;
}

std::string Hex(const std::vector<uint8_t>& value) {
    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (uint8_t byte : value) output << std::setw(2) << static_cast<int>(byte);
    return output.str();
}

std::string PublicJwk(const std::string& x, const std::string& y) {
    return "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\""
        + JsonEscape(x) + "\",\"y\":\"" + JsonEscape(y) + "\"}";
}

std::string WorkspaceId(const std::string& x, const std::string& y) {
    auto digest = Sha256(PublicJwk(x, y));
    return "ws_" + Hex(digest).substr(0, 32);
}

bool ExtractPublicCoordinates(const std::vector<uint8_t>& blob,
                              std::string& x, std::string& y) {
    if (blob.size() < sizeof(BCRYPT_ECCKEY_BLOB)) return false;
    const auto* header = reinterpret_cast<const BCRYPT_ECCKEY_BLOB*>(blob.data());
    if (header->cbKey == 0 || blob.size() < sizeof(*header) + header->cbKey * 3ull) return false;
    const auto* coordinates = blob.data() + sizeof(*header);
    x = Base64Url(coordinates, header->cbKey);
    y = Base64Url(coordinates + header->cbKey, header->cbKey);
    return !x.empty() && !y.empty();
}

std::vector<uint8_t> ExportPrivate(BCRYPT_KEY_HANDLE key) {
    DWORD size = 0;
    if (BCryptExportKey(key, nullptr, BCRYPT_ECCPRIVATE_BLOB, nullptr, 0, &size, 0) != 0 || size == 0) {
        throw std::runtime_error("Windows 管理身份导出失败");
    }
    std::vector<uint8_t> blob(size);
    if (BCryptExportKey(key, nullptr, BCRYPT_ECCPRIVATE_BLOB, blob.data(), size, &size, 0) != 0) {
        throw std::runtime_error("Windows 管理身份导出失败");
    }
    blob.resize(size);
    return blob;
}

std::vector<uint8_t> Protect(const std::vector<uint8_t>& plain) {
    DATA_BLOB input{static_cast<DWORD>(plain.size()), const_cast<BYTE*>(plain.data())};
    DATA_BLOB output{};
    if (!CryptProtectData(&input, L"DeviceShareHub relay identity", nullptr, nullptr, nullptr,
                          CRYPTPROTECT_UI_FORBIDDEN, &output)) {
        throw std::runtime_error("无法保护 Windows 远程身份");
    }
    std::vector<uint8_t> result(output.pbData, output.pbData + output.cbData);
    LocalFree(output.pbData);
    return result;
}

std::vector<uint8_t> Unprotect(const std::vector<uint8_t>& protectedBlob) {
    DATA_BLOB input{static_cast<DWORD>(protectedBlob.size()),
                    const_cast<BYTE*>(protectedBlob.data())};
    DATA_BLOB output{};
    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
                            CRYPTPROTECT_UI_FORBIDDEN, &output)) {
        throw std::runtime_error("Windows 远程身份不可用");
    }
    std::vector<uint8_t> result(output.pbData, output.pbData + output.cbData);
    LocalFree(output.pbData);
    return result;
}

void SaveBlob(const std::filesystem::path& path, const std::vector<uint8_t>& plain) {
    std::filesystem::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("无法保存 Windows 远程身份");
    auto protectedBlob = Protect(plain);
    uint32_t size = static_cast<uint32_t>(protectedBlob.size());
    output.write(reinterpret_cast<const char*>(&size), sizeof(size));
    output.write(reinterpret_cast<const char*>(protectedBlob.data()),
                 static_cast<std::streamsize>(protectedBlob.size()));
    if (!output) throw std::runtime_error("无法保存 Windows 远程身份");
}

std::vector<uint8_t> LoadBlob(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return {};
    uint32_t size = 0;
    input.read(reinterpret_cast<char*>(&size), sizeof(size));
    if (!input || size == 0 || size > 64 * 1024) return {};
    std::vector<uint8_t> protectedBlob(size);
    input.read(reinterpret_cast<char*>(protectedBlob.data()), static_cast<std::streamsize>(size));
    if (!input) return {};
    return Unprotect(protectedBlob);
}

AdminIdentity LoadOrCreateIdentity(const std::filesystem::path& stateDirectory) {
    const auto path = stateDirectory / "relay-admin-identity.dat";
    std::vector<uint8_t> privateBlob;
    try { privateBlob = LoadBlob(path); } catch (...) { privateBlob.clear(); }
    if (privateBlob.empty()) {
        BCRYPT_ALG_HANDLE algorithm = nullptr;
        BCRYPT_KEY_HANDLE key = nullptr;
        if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0) != 0
            || BCryptGenerateKeyPair(algorithm, &key, 256, 0) != 0
            || BCryptFinalizeKeyPair(key, 0) != 0) {
            if (key) BCryptDestroyKey(key);
            if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
            throw std::runtime_error("无法生成 Windows 远程管理身份");
        }
        privateBlob = ExportPrivate(key);
        BCryptDestroyKey(key);
        BCryptCloseAlgorithmProvider(algorithm, 0);
        SaveBlob(path, privateBlob);
    }
    AdminIdentity identity;
    identity.privateBlob = privateBlob;
    if (!ExtractPublicCoordinates(privateBlob, identity.x, identity.y)) {
        throw std::runtime_error("Windows 远程管理身份格式无效");
    }
    identity.workspaceId = WorkspaceId(identity.x, identity.y);
    return identity;
}

std::string Sign(const AdminIdentity& identity, const std::string& canonical) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_KEY_HANDLE key = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0) != 0
        || BCryptImportKeyPair(algorithm, nullptr, BCRYPT_ECCPRIVATE_BLOB,
                               &key, const_cast<PUCHAR>(identity.privateBlob.data()),
                               static_cast<ULONG>(identity.privateBlob.size()), 0) != 0) {
        if (key) BCryptDestroyKey(key);
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("无法读取 Windows 远程管理身份");
    }
    auto digest = Sha256(canonical);
    DWORD signatureSize = 0;
    if (BCryptSignHash(key, nullptr, digest.data(), static_cast<ULONG>(digest.size()),
                       nullptr, 0, &signatureSize, 0) != 0 || signatureSize == 0) {
        BCryptDestroyKey(key); BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("Windows 远程身份签名失败");
    }
    std::vector<uint8_t> signature(signatureSize);
    if (BCryptSignHash(key, nullptr, digest.data(), static_cast<ULONG>(digest.size()),
                       signature.data(), signatureSize, &signatureSize, 0) != 0) {
        BCryptDestroyKey(key); BCryptCloseAlgorithmProvider(algorithm, 0);
        throw std::runtime_error("Windows 远程身份签名失败");
    }
    signature.resize(signatureSize);
    BCryptDestroyKey(key);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return Base64Url(signature.data(), signature.size());
}

std::string AdminCertificate(const AdminIdentity& identity, const std::string& deviceId,
                             const std::string& name) {
    const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return "{\"agreementPublicKey\":" + PublicJwk(identity.x, identity.y)
        + ",\"deviceId\":\"" + JsonEscape(deviceId)
        + "\",\"deviceName\":\"" + JsonEscape(name)
        + "\",\"expiresAt\":" + std::to_string(now + 365LL * 24 * 60 * 60 * 1000)
        + ",\"issuedAt\":" + std::to_string(now)
        + ",\"role\":\"admin\",\"serial\":1"
        + ",\"signingPublicKey\":" + PublicJwk(identity.x, identity.y)
        + ",\"version\":1,\"workspaceId\":\"" + identity.workspaceId + "\"}";
}

std::string MemberCertificate(const AdminIdentity& identity, const EnrollmentInfo& device) {
    const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return "{\"agreementPublicKey\":" + PublicJwk(device.agreementX, device.agreementY)
        + ",\"deviceId\":\"" + JsonEscape(device.deviceId)
        + "\",\"deviceName\":\"" + JsonEscape(device.deviceName)
        + "\",\"expiresAt\":" + std::to_string(now + 365LL * 24 * 60 * 60 * 1000)
        + ",\"issuedAt\":" + std::to_string(now)
        + ",\"role\":\"member\",\"serial\":1"
        + ",\"signingPublicKey\":" + PublicJwk(device.signingX, device.signingY)
        + ",\"version\":1,\"workspaceId\":\"" + identity.workspaceId + "\"}";
}

std::string JsonValue(const std::string& json, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t position = json.find(marker);
    if (position == std::string::npos) return {};
    position = json.find(':', position + marker.size());
    if (position == std::string::npos) return {};
    position = json.find('"', position + 1);
    if (position == std::string::npos) return {};
    std::string result;
    for (size_t i = position + 1; i < json.size(); ++i) {
        if (json[i] == '"' && (i == 0 || json[i - 1] != '\\')) break;
        if (json[i] == '\\' && i + 1 < json.size()) {
            char escaped = json[++i];
            result.push_back(escaped == 'n' ? '\n' : escaped == 'r' ? '\r' : escaped == 't' ? '\t' : escaped);
        } else result.push_back(json[i]);
    }
    return result;
}

int64_t JsonNumber(const std::string& json, const std::string& key, int64_t fallback = 0) {
    const std::string marker = "\"" + key + "\"";
    size_t position = json.find(marker);
    if (position == std::string::npos) return fallback;
    position = json.find(':', position + marker.size());
    if (position == std::string::npos) return fallback;
    try { return std::stoll(json.substr(position + 1)); } catch (...) { return fallback; }
}

std::vector<std::string> JsonPaths(const std::string& json) {
    std::vector<std::string> result;
    size_t cursor = 0;
    while ((cursor = json.find("\"path\"", cursor)) != std::string::npos) {
        auto value = JsonValue(json.substr(cursor), "path");
        if (!value.empty()) result.push_back(value);
        cursor += 6;
    }
    return result;
}

std::string JsonObjectField(const std::string& json, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t position = json.find(marker);
    if (position == std::string::npos) return {};
    position = json.find(':', position + marker.size());
    if (position == std::string::npos) return {};
    position = json.find_first_not_of(" \t\r\n", position + 1);
    if (position == std::string::npos || json[position] != '{') return {};
    const size_t start = position;
    int depth = 0;
    bool quoted = false;
    bool escaped = false;
    for (; position < json.size(); ++position) {
        const char c = json[position];
        if (quoted) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') quoted = false;
        } else if (c == '"') {
            quoted = true;
        } else if (c == '{') {
            ++depth;
        } else if (c == '}' && --depth == 0) {
            return json.substr(start, position - start + 1);
        }
    }
    return {};
}

std::vector<std::string> JsonArrayObjects(const std::string& json, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    size_t cursor = json.find(marker);
    if (cursor == std::string::npos) return {};
    cursor = json.find('[', cursor + marker.size());
    if (cursor == std::string::npos) return {};
    std::vector<std::string> result;
    for (++cursor; cursor < json.size();) {
        cursor = json.find('{', cursor);
        if (cursor == std::string::npos) break;
        const size_t start = cursor;
        int depth = 0;
        bool quoted = false;
        bool escaped = false;
        for (; cursor < json.size(); ++cursor) {
            const char c = json[cursor];
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                ++depth;
            } else if (c == '}' && --depth == 0) {
                result.push_back(json.substr(start, cursor - start + 1));
                ++cursor;
                break;
            }
        }
    }
    return result;
}

std::string NewP2PTransferId() {
    std::random_device random;
    std::mt19937_64 generator(random());
    std::ostringstream output;
    output << "p2p_" << std::hex << GetTickCount64() << generator();
    return output.str();
}

class RelayHttp {
public:
    RelayHttp() {
        session_ = WinHttpOpen(kUserAgent, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                               WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session_) throw std::runtime_error("无法连接 Cloudflare 中继");
        WinHttpSetTimeouts(session_, 8000, 8000, 20000, 1800000);
        connection_ = WinHttpConnect(session_, kHost, INTERNET_DEFAULT_HTTPS_PORT, 0);
        if (!connection_) throw std::runtime_error("无法连接 Cloudflare 中继");
    }
    ~RelayHttp() {
        if (connection_) WinHttpCloseHandle(connection_);
        if (session_) WinHttpCloseHandle(session_);
    }

    void SetWorkspace(const std::string& workspaceId) { workspaceId_ = workspaceId; }

    std::string Json(const wchar_t* method, const std::wstring& path,
                     const std::string& body, const std::string& token = {}) {
        std::wstring headers = L"Accept: application/json\r\nContent-Type: application/json; charset=utf-8\r\nExpect:\r\n";
        if (!token.empty()) headers += L"Authorization: Bearer " + ToWide(token) + L"\r\n";
        if (!workspaceId_.empty()) headers += L"X-Workspace-Id: " + ToWide(workspaceId_) + L"\r\n";
        return Memory(method, path, headers,
                      reinterpret_cast<const BYTE*>(body.data()), static_cast<DWORD>(body.size()));
    }

    std::string Put(const std::wstring& path, const std::filesystem::path& file,
                    const std::string& token, const std::string& sha,
                    const ProgressCallback& progress, uintmax_t totalSent,
                    uintmax_t totalBytes) {
        uintmax_t size = std::filesystem::file_size(file);
        if (size > 0xffffffffull) throw std::runtime_error("远程对象超过 4GB");
        std::wstring headers = L"Accept: application/json\r\nContent-Type: application/octet-stream\r\nExpect:\r\nContent-Length: "
            + std::to_wstring(size) + L"\r\nAuthorization: Bearer " + ToWide(token) + L"\r\n";
        if (!workspaceId_.empty()) headers += L"X-Workspace-Id: " + ToWide(workspaceId_) + L"\r\n";
        HINTERNET request = WinHttpOpenRequest(connection_, L"PUT", path.c_str(), nullptr,
                                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                               WINHTTP_FLAG_SECURE);
        if (!request) throw std::runtime_error("无法创建中继上传请求");
        if (!WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1L),
                                WINHTTP_NO_REQUEST_DATA, 0, static_cast<DWORD>(size), 0)) {
            WinHttpCloseHandle(request); throw std::runtime_error("中继上传连接失败");
        }
        std::ifstream input(file, std::ios::binary);
        if (!input) { WinHttpCloseHandle(request); throw std::runtime_error("无法读取远程作品文件"); }
        std::vector<char> buffer(1024 * 1024);
        uintmax_t sent = 0;
        while (input) {
            input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            DWORD count = static_cast<DWORD>(input.gcount());
            if (count == 0) continue;
            DWORD written = 0;
            if (!WinHttpWriteData(request, buffer.data(), count, &written) || written != count) {
                WinHttpCloseHandle(request); throw std::runtime_error("中继上传中断");
            }
            sent += written;
            if (progress) progress(totalSent + sent, totalBytes);
        }
        std::string response = Finish(request);
        WinHttpCloseHandle(request);
        (void)sha;
        return response;
    }

private:
    HINTERNET session_ = nullptr;
    HINTERNET connection_ = nullptr;
    std::string workspaceId_;

    static std::wstring ToWide(const std::string& value) {
        if (value.empty()) return {};
        int size = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
        std::wstring result(size, L'\0');
        MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), size);
        return result;
    }

    std::string Memory(const wchar_t* method, const std::wstring& path,
                       const std::wstring& headers, const BYTE* data, DWORD size) {
        HINTERNET request = WinHttpOpenRequest(connection_, method, path.c_str(), nullptr,
                                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                               WINHTTP_FLAG_SECURE);
        if (!request) throw std::runtime_error("无法创建中继请求");
        if (!WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1),
                                WINHTTP_NO_REQUEST_DATA, 0, size, 0)) {
            WinHttpCloseHandle(request); throw std::runtime_error("中继请求发送失败");
        }
        if (size > 0) {
            DWORD written = 0;
            if (!WinHttpWriteData(request, data, size, &written) || written != size) {
                WinHttpCloseHandle(request); throw std::runtime_error("中继请求内容发送失败");
            }
        }
        std::string response = Finish(request);
        WinHttpCloseHandle(request);
        return response;
    }

    static std::string Finish(HINTERNET request) {
        if (!WinHttpReceiveResponse(request, nullptr)) throw std::runtime_error("中继没有响应");
        DWORD status = 0; DWORD size = sizeof(status);
        if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                 WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX)) {
            throw std::runtime_error("无法读取中继响应");
        }
        std::string body;
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
            if (body.size() + available > kMaxResponseBytes) throw std::runtime_error("中继响应过大");
            std::string chunk(available, '\0'); DWORD read = 0;
            if (!WinHttpReadData(request, chunk.data(), available, &read)) break;
            body.append(chunk.data(), read);
            if (read == 0) break;
        }
        if (status < 200 || status >= 300) {
            std::string message = JsonValue(body, "message");
            if (message.empty()) message = "Cloudflare 中继返回 HTTP " + std::to_string(status);
            throw std::runtime_error(message);
        }
        return body;
    }
};

std::mutex gAdminSessionMutex;
std::string gAdminSessionWorkspace;
std::string gAdminSessionToken;
int64_t gAdminSessionExpiresAt = 0;

std::string RegisterAndSession(RelayHttp& http, const AdminIdentity& identity,
                               const std::string& adminCertificate) {
    const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    std::lock_guard<std::mutex> guard(gAdminSessionMutex);
    if (gAdminSessionWorkspace == identity.workspaceId && !gAdminSessionToken.empty()
        && gAdminSessionExpiresAt > now + 60 * 1000) {
        return gAdminSessionToken;
    }
    const auto signature = Sign(identity, adminCertificate);
    http.Json(L"POST", L"/v1/workspaces/register",
              "{\"certificate\":" + adminCertificate +
              ",\"certificateSignature\":\"" + signature + "\"}");
    std::string challenge = http.Json(L"POST", L"/v1/challenges",
        "{\"workspaceId\":\"" + identity.workspaceId + "\",\"deviceId\":\"windows-admin\"}");
    std::string challengeCanonical = "{\"challengeId\":\"" + JsonEscape(JsonValue(challenge, "challengeId"))
        + "\",\"deviceId\":\"windows-admin\",\"expiresAt\":"
        + std::to_string(JsonNumber(challenge, "expiresAt"))
        + ",\"nonce\":\"" + JsonEscape(JsonValue(challenge, "nonce"))
        + "\",\"version\":" + std::to_string(JsonNumber(challenge, "version", 1))
        + ",\"workspaceId\":\"" + identity.workspaceId + "\"}";
    auto challengeSignature = Sign(identity, challengeCanonical);
    std::string session = http.Json(L"POST", L"/v1/sessions",
        "{\"certificate\":" + adminCertificate
        + ",\"certificateSignature\":\"" + signature
        + "\",\"challengeId\":\"" + JsonEscape(JsonValue(challenge, "challengeId"))
        + "\",\"challengeSignature\":\"" + challengeSignature + "\"}");
    auto token = JsonValue(session, "token");
    if (token.empty()) throw std::runtime_error("Cloudflare 中继没有返回管理员会话");
    gAdminSessionWorkspace = identity.workspaceId;
    gAdminSessionToken = token;
    gAdminSessionExpiresAt = JsonNumber(session, "expiresAt", now + 24LL * 60 * 60 * 1000);
    return token;
}

}  // namespace

const char* Endpoint() { return kEndpoint; }

bool BuildMobileProfile(const std::filesystem::path& stateDirectory,
                        const EnrollmentInfo& device,
                        std::string& profileJson,
                        std::string& error) {
    try {
        if (device.deviceId.empty() || device.signingX.empty() || device.signingY.empty()
            || device.agreementX.empty() || device.agreementY.empty()) {
            throw std::runtime_error("手机尚未提供远程公钥");
        }
        auto identity = LoadOrCreateIdentity(stateDirectory);
        const auto adminCertificate = AdminCertificate(identity, "windows-admin", "素材投送中控");
        RelayHttp http;
        http.SetWorkspace(identity.workspaceId);
        (void)RegisterAndSession(http, identity, adminCertificate);
        const auto memberCertificate = MemberCertificate(identity, device);
        const auto memberSignature = Sign(identity, memberCertificate);
        profileJson = "{\"endpoint\":\"" + std::string(kEndpoint)
            + "\",\"certificate\":" + memberCertificate
            + ",\"certificateSignature\":\"" + memberSignature + "\"}";
        return true;
    } catch (const std::exception& exception) {
        error = exception.what();
        return false;
    }
}

bool SendPlainTransfer(const std::filesystem::path& stateDirectory,
                       const std::string& recipientDeviceId,
                       const std::vector<std::filesystem::path>& files,
                       const ProgressCallback& progress,
                       std::string& transferId,
                       std::string& error) {
    try {
        if (files.empty()) throw std::runtime_error("没有可传送的远程作品");
        auto identity = LoadOrCreateIdentity(stateDirectory);
        const auto adminCertificate = AdminCertificate(identity, "windows-admin", "素材投送中控");
        RelayHttp http;
        http.SetWorkspace(identity.workspaceId);
        const auto token = RegisterAndSession(http, identity, adminCertificate);
        std::string objects = "[";
        uint64_t totalBytes = 0;
        std::vector<std::string> hashes;
        for (size_t index = 0; index < files.size(); ++index) {
            auto size = std::filesystem::file_size(files[index]);
            if (size == 0 || totalBytes + size > kMaxTransferBytes) throw std::runtime_error("远程作品大小无效或超过限制");
            auto digest = Sha256File(files[index]);
            auto hash = Hex(digest); hashes.push_back(hash);
            if (index) objects += ",";
            auto name = files[index].filename().u8string();
            objects += "{\"index\":" + std::to_string(index) + ",\"bytes\":" + std::to_string(size)
                + ",\"sha256\":\"" + hash + "\",\"name\":\"" + JsonEscape(name)
                + "\",\"mime\":\"application/octet-stream\"}";
            totalBytes += size;
        }
        objects += "]";
        std::string created = http.Json(L"POST", L"/v1/transfers",
            "{\"mode\":\"plain\",\"recipientDeviceId\":\""
            + JsonEscape(recipientDeviceId) + "\",\"objects\":" + objects + "}", token);
        transferId = JsonValue(created, "transferId");
        auto paths = JsonPaths(created);
        if (transferId.empty() || paths.size() != files.size()) throw std::runtime_error("中继创建任务响应无效");
        uintmax_t sent = 0;
        for (size_t index = 0; index < files.size(); ++index) {
            http.Put(std::wstring(paths[index].begin(), paths[index].end()), files[index], token,
                     hashes[index], progress, sent, totalBytes);
            sent += std::filesystem::file_size(files[index]);
        }
        http.Json(L"POST", std::wstring(L"/v1/transfers/") +
                  std::wstring(transferId.begin(), transferId.end()) + L"/commit", "{}", token);
        return true;
    } catch (const std::exception& exception) {
        error = exception.what();
        return false;
    }
}

bool TryP2PTransfer(const std::filesystem::path& stateDirectory,
                    const std::string& recipientDeviceId,
                    const std::vector<std::filesystem::path>& files,
                    const ProgressCallback& progress,
                    std::string& transferId,
                    std::string& error) {
    try {
        if (files.empty()) throw std::runtime_error("没有可传送的 P2P 作品");
        auto identity = LoadOrCreateIdentity(stateDirectory);
        const auto adminCertificate = AdminCertificate(identity, "windows-admin", "素材投送中控");
        RelayHttp http;
        http.SetWorkspace(identity.workspaceId);
        const auto token = RegisterAndSession(http, identity, adminCertificate);
        const std::string created = http.Json(L"POST", L"/v1/p2p/sessions",
            "{\"recipientDeviceId\":\"" + JsonEscape(recipientDeviceId)
            + "\",\"protocol\":\"webrtc-datachannel-v1\"}", token);
        const auto sessionId = JsonValue(created, "sessionId");
        if (sessionId.empty()) throw std::runtime_error("中继没有返回 P2P 会话");

        // Every path after session creation must close the signaling session.
        // In particular, file hashing/transport exceptions used to jump to the
        // outer catch and leave an open session until the relay TTL expired.
        auto closeSession = [&]() {
            try {
                http.Json(L"POST", L"/v1/p2p/sessions/" +
                          std::wstring(sessionId.begin(), sessionId.end()) + L"/close",
                          "{}", token);
            } catch (...) { }
        };

        try {
            std::vector<p2p_transport::FileItem> items;
            for (const auto& path : files) {
                const uintmax_t bytes = std::filesystem::file_size(path);
                if (bytes == 0 || bytes > 20ull * 1024ull * 1024ull * 1024ull) {
                    throw std::runtime_error("P2P 作品大小无效或超过限制");
                }
                items.push_back({path, path.filename().u8string(), "application/octet-stream",
                                 bytes, Hex(Sha256File(path))});
            }

            transferId = NewP2PTransferId();
            std::string signalError;
            auto sendSignal = [&](const std::string& type, const std::string& data) {
                try {
                    http.Json(L"POST", L"/v1/p2p/sessions/" +
                        std::wstring(sessionId.begin(), sessionId.end()) + L"/signals",
                        "{\"type\":\"" + JsonEscape(type) + "\",\"data\":" + data + "}", token);
                } catch (const std::exception& exception) {
                    signalError = exception.what();
                }
            };
            auto pollSignals = [&]() {
                const std::string response = http.Json(L"GET", L"/v1/p2p/sessions/" +
                    std::wstring(sessionId.begin(), sessionId.end()), "", token);
                std::vector<p2p_transport::Signal> signals;
                for (const auto& item : JsonArrayObjects(response, "signals")) {
                    const auto type = JsonValue(item, "type");
                    const auto from = JsonValue(item, "fromDeviceId");
                    const auto sentAt = JsonNumber(item, "sentAt", 0);
                    const auto data = JsonObjectField(item, "data");
                    if (type.empty() || data.empty()) continue;
                    signals.push_back({from + ":" + std::to_string(sentAt) + ":" + type, type, data});
                }
                return signals;
            };
            std::string p2pError;
            const bool sent = p2p_transport::Send(
                transferId, "windows-admin", recipientDeviceId, items, sendSignal, pollSignals,
                [&](uintmax_t done, uintmax_t total) { if (progress) progress(done, total); }, p2pError);
            closeSession();
            if (!sent) {
                if (!signalError.empty()) p2pError += ": " + signalError;
                error = p2pError.empty() ? "P2P 直连未完成" : p2pError;
                return false;
            }
            return true;
        } catch (...) {
            closeSession();
            throw;
        }
    } catch (const std::exception& exception) {
        error = exception.what();
        return false;
    }
}

bool ListDevices(const std::filesystem::path& stateDirectory,
                 std::vector<RelayDevice>& devices,
                 std::string& error) {
    try {
        devices.clear();
        auto identity = LoadOrCreateIdentity(stateDirectory);
        const auto adminCertificate = AdminCertificate(identity, "windows-admin", "素材投送中控");
        RelayHttp http;
        http.SetWorkspace(identity.workspaceId);
        const auto token = RegisterAndSession(http, identity, adminCertificate);
        const auto response = http.Json(L"GET", L"/v1/devices", "", token);
        std::set<std::string> seen;
        size_t cursor = response.find("\"devices\"");
        cursor = cursor == std::string::npos ? std::string::npos : response.find('[', cursor);
        while (cursor != std::string::npos &&
               (cursor = response.find('{', cursor)) != std::string::npos) {
            size_t end = cursor;
            int depth = 0;
            bool quoted = false;
            bool escaped = false;
            for (; end < response.size(); ++end) {
                const char c = response[end];
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '\"') quoted = false;
                } else if (c == '\"') {
                    quoted = true;
                } else if (c == '{') {
                    ++depth;
                } else if (c == '}' && --depth == 0) {
                    break;
                }
            }
            if (end >= response.size()) break;
            std::string item = response.substr(cursor, end - cursor + 1);
            RelayDevice device;
            device.deviceId = JsonValue(item, "deviceId");
            device.name = JsonValue(item, "name");
            device.online = item.find("\"online\":true") != std::string::npos;
            device.remoteAllowed = item.find("\"remoteAllowed\":true") != std::string::npos;
            if (!device.deviceId.empty() && seen.insert(device.deviceId).second) {
                devices.push_back(std::move(device));
            }
            cursor = response.find('{', end + 1);
        }
        return true;
    } catch (const std::exception& exception) {
        error = exception.what();
        return false;
    }
}

}  // namespace remote_relay
