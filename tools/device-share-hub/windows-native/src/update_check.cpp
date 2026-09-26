// 电脑端新版本判定的实现（纯逻辑，见 update_check.h 顶部说明）。
//
// ⚠️ 这里刻意不引第三方 JSON 库：只需要 5 个字段，而极简取值器的**定位**才是唯一的坑
//    （跟 online_service.cpp 的 ParseStatusJson 一个套路），把定位逻辑写清楚更好测。
#include "update_check.h"

namespace update_check {
namespace {

bool IsSpace(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

// 找 "key" 作为**键**出现的位置（后面允许空白，然后必须是 ':'）。
//
// ⚠️ 必须带括号深度感知，只认**顶层**（depth==1）的键。
//    不做深度感知的话，形如 {"ios": {"windows": {...}}, "windows": {...}} 的清单里
//    会先命中 ios 内部那个 windows —— 取到的是别人的版本号，而且全程零报错。
//    （这条是写测试时发现的：原实现在嵌套对象面前是错的。）
size_t FindKey(const std::string& text, const std::string& key) {
    const std::string marker = "\"" + key + "\"";
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (size_t i = 0; i < text.size(); ++i) {
        const char c = text[i];
        if (inString) {
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') inString = false;
            continue;
        }
        if (c == '"') {
            inString = true;
            if (depth == 1 && i + marker.size() <= text.size()
                    && text.compare(i, marker.size(), marker) == 0) {
                size_t cursor = i + marker.size();
                while (cursor < text.size() && IsSpace(text[cursor])) ++cursor;
                if (cursor < text.size() && text[cursor] == ':') return i;
            }
            continue;
        }
        if (c == '{') ++depth;
        else if (c == '}') --depth;
    }
    return std::string::npos;
}

// 从 '{' 开始按括号配对抠出整个对象（字符串里的括号不算）。配对不上返回空。
std::string ExtractObject(const std::string& text, size_t bracePos) {
    if (bracePos >= text.size() || text[bracePos] != '{') return {};
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (size_t i = bracePos; i < text.size(); ++i) {
        const char c = text[i];
        if (inString) {
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') inString = false;
            continue;
        }
        if (c == '"') { inString = true; continue; }
        if (c == '{') {
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 0) return text.substr(bracePos, i - bracePos + 1);
        }
    }
    return {};
}

// 取字符串字段值（支持 \" \\ \/ \n \r \t 转义）
std::string StringValue(const std::string& text, const std::string& key) {
    const size_t keyPos = FindKey(text, key);
    if (keyPos == std::string::npos) return {};
    size_t colon = text.find(':', keyPos);
    if (colon == std::string::npos) return {};
    size_t quote = text.find('"', colon + 1);
    if (quote == std::string::npos) return {};
    std::string result;
    bool escaped = false;
    for (size_t i = quote + 1; i < text.size(); ++i) {
        const char c = text[i];
        if (escaped) {
            switch (c) {
                case '"': result.push_back('"'); break;
                case '\\': result.push_back('\\'); break;
                case '/': result.push_back('/'); break;
                case 'n': result.push_back('\n'); break;
                case 'r': result.push_back('\r'); break;
                case 't': result.push_back('\t'); break;
                default: result.push_back(c); break;
            }
            escaped = false;
            continue;
        }
        if (c == '\\') { escaped = true; continue; }
        if (c == '"') break;
        result.push_back(c);
    }
    return result;
}

}  // namespace

std::vector<int> ParseVersion(const std::string& value) {
    size_t start = 0;
    // 容忍 "V4.3.30" / "v4.3.30" 这种前缀
    while (start < value.size() && (value[start] < '0' || value[start] > '9')) ++start;
    if (start == value.size()) return {};
    size_t end = start;
    while (end < value.size() && ((value[end] >= '0' && value[end] <= '9') || value[end] == '.')) {
        ++end;
    }
    const std::string core = value.substr(start, end - start);
    std::vector<int> result;
    size_t cursor = 0;
    while (cursor <= core.size()) {
        const size_t dot = core.find('.', cursor);
        const std::string field = core.substr(cursor, dot == std::string::npos
                                                            ? std::string::npos
                                                            : dot - cursor);
        if (field.empty()) return {};   // "4..3" 或 "4." 这种都算不合法
        long long parsed = 0;
        for (char c : field) {
            if (c < '0' || c > '9') return {};
            parsed = parsed * 10 + (c - '0');
            if (parsed > 1000000) return {};
        }
        result.push_back(static_cast<int>(parsed));
        if (dot == std::string::npos) break;
        cursor = dot + 1;
    }
    return result;
}

int CompareVersionVectors(const std::vector<int>& left, const std::vector<int>& right) {
    const size_t count = left.size() > right.size() ? left.size() : right.size();
    for (size_t i = 0; i < count; ++i) {
        const int l = i < left.size() ? left[i] : 0;
        const int r = i < right.size() ? right[i] : 0;
        if (l != r) return l < r ? -1 : 1;
    }
    return 0;
}

WindowsSection ParseWindowsSection(const std::string& manifestJson) {
    WindowsSection section;
    const size_t keyPos = FindKey(manifestJson, "windows");
    if (keyPos == std::string::npos) return section;
    size_t cursor = keyPos + std::string("\"windows\"").size();
    while (cursor < manifestJson.size() && IsSpace(manifestJson[cursor])) ++cursor;
    if (cursor >= manifestJson.size() || manifestJson[cursor] != ':') return section;
    ++cursor;
    while (cursor < manifestJson.size() && IsSpace(manifestJson[cursor])) ++cursor;
    if (cursor >= manifestJson.size() || manifestJson[cursor] != '{') return section;

    const std::string object = ExtractObject(manifestJson, cursor);
    if (object.empty()) return section;

    section.present = true;
    section.version = StringValue(object, "version");
    section.sha256 = StringValue(object, "sha256");
    section.url = StringValue(object, "url");
    section.releaseUrl = StringValue(object, "release_url");
    section.fileName = StringValue(object, "file_name");
    return section;
}

UpdateDecision DecideWindowsUpdate(const WindowsSection& section,
                                   const std::string& currentVersion) {
    UpdateDecision decision;
    decision.currentVersion = currentVersion;
    decision.latestVersion = section.version;
    decision.downloadUrl = section.url;
    decision.releaseUrl = section.releaseUrl;

    if (!section.present) {
        decision.reason = "清单里没有 Windows 更新信息（发布仓库 latest.json 缺 windows 段）";
        return decision;
    }
    const auto latest = ParseVersion(section.version);
    if (latest.empty()) {
        decision.reason = "清单里的 Windows 版本号不合法：" + section.version;
        return decision;
    }
    const auto current = ParseVersion(currentVersion);
    if (current.empty()) {
        decision.reason = "当前程序版本号异常：" + currentVersion;
        return decision;
    }

    const int cmp = CompareVersionVectors(current, latest);
    // 口径错配守卫：当前比清单新、且主版本号都不是同一代 —— 这几乎必然是拿电脑端版本号
    // 去比了手机端版本号（4.3.30 永远大于 0.8.63）。静默判「已是最新」比直接说不知道坑得多。
    if (cmp > 0 && current[0] != latest[0]) {
        decision.reason = "清单里的 Windows 版本号与当前程序不是同一套（当前 " + currentVersion +
                          "，清单 " + section.version + "），不做比较";
        return decision;
    }

    decision.comparable = true;
    decision.hasUpdate = cmp < 0;
    return decision;
}

}  // namespace update_check
