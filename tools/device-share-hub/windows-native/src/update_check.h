// 电脑端（Windows 面板）自身的新版本判定。
//
// 为什么要单独抽出来（DSH-121）：
// 以前 CheckForUpdates() 拿**主仓库** team-video-workflow 的手工 Release tag
// （形如 `device-share-hub-v4.3.29-2026.08.23`）跟本程序的 APP_VERSION 比。
// 这条路有两个坑：
//   ① 主仓库的 Release 是**人手发**的，忘了发就永远停在旧版本 —— CI 根本不会更新它；
//   ② 一旦哪天主仓库发了别的 tag（比如手机端的 v0.8.63），就会拿 4.3.30 去比 0.8.63，
//      4 > 0 ⇒ 永远判定「当前已是最新版本」—— 用户从此再也收不到电脑端更新提示。
//
// 正确的真源是发布仓库 gallery-updates 的 latest.json 里的 `windows` 段：
// 那一节是 CI 每次发版自动写的（见 workflow 的 .windows={...}）。
//
// 这里只放**纯逻辑**，不碰网络，这样 CI 能单测（见 tests/update_check_tests.cpp）。
#pragma once

#include <string>
#include <vector>

namespace update_check {

// latest.json 里 Windows 那一节。CI 每次发版都会重写这一节。
struct WindowsSection {
    bool present = false;     // latest.json 里到底有没有 "windows" 这一节
    std::string version;      // windows.version   例如 "4.3.30"
    std::string sha256;       // windows.sha256
    std::string url;          // windows.url       exe 直链
    std::string releaseUrl;   // windows.release_url 发布页
    std::string fileName;     // windows.file_name
};

struct UpdateDecision {
    bool comparable = false;  // 能不能做版本比较。不能比就别瞎说「已是最新」
    bool hasUpdate = false;
    std::string currentVersion;
    std::string latestVersion;
    std::string downloadUrl;
    std::string releaseUrl;
    std::string reason;       // comparable=false 时给人看的原因
};

// 从 latest.json 原文里抠出 windows 段。
// ⚠️ 定位是这里唯一容易错的地方：清单里别处也可能出现 "windows" 这个词
//    （例如 notes 里写了「支持 Windows」），必须在 `"windows"` **作为键**出现的位置
//    后面找 `{`，再按括号配对把整个子对象抠出来。
WindowsSection ParseWindowsSection(const std::string& manifestJson);

// 判定有没有更新。currentVersion 传 APP_VERSION（如 "4.3.30"）。
//
// ⚠️ 关键判据：当「当前版本比清单版本新」且**两者主版本号不同**时，判定为口径错配，
//    拒绝比较（comparable=false）。因为那通常意味着拿电脑端版本号去比了手机端版本号
//    —— 4.3.30 永远大于 0.8.63，静默判「已是最新」比直接说「不知道」坑得多。
UpdateDecision DecideWindowsUpdate(const WindowsSection& section,
                                   const std::string& currentVersion);

// 供测试与内部复用：把 "4.3.30" 拆成 [4,3,30]；取不到数字返回空。
std::vector<int> ParseVersion(const std::string& value);

// 供测试与内部复用：left < right 返回 -1，相等 0，left > right 返回 1。
int CompareVersionVectors(const std::vector<int>& left, const std::vector<int>& right);

}  // namespace update_check
