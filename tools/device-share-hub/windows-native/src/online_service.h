// 电脑端「在线相册服务」控制台 —— 把散在外面的 .cmd / .ps1 脚本收进客户端。
//
// 背景（2026-09-27 电脑客户端升级）：
//   手机端看的那个「电脑在线相册」是一个 Python 服务（默认 45835 端口）。此前它完全靠
//   scripts\ 下面的一堆脚本管：online_gallery.cmd（启动）、restart_online_gallery.ps1
//   -Restart（重启）、check_online_gallery.ps1（体检）、online_gallery-status.cmd（状态）、
//   install-autostart.ps1（开机自启）。客户端自己**完全不知道有这个服务存在**
//   —— 源码里连 45835 这个数字都没出现过。
//
//   后果很直接：服务没起来时手机端只会「正在连接电脑在线相册…」转圈，用户不知道要去看
//   哪个黑框、也不知道该点哪个脚本。本模块把这些能力收进「设置 → 在线相册服务」：
//   状态一眼可见、启动/停止/重启一键完成、开机自启打勾、还能直接在浏览器打开。
//
// 设计约束：
//   1. 不引入新依赖。winhttp（已经在用）/ iphlpapi（已经在用）/ advapi32（注册表）。
//   2. 纯逻辑与系统操作分开：前者可在 CI 上单测，后者只在真机跑。
//   3. 端口只认本机回环探测，绝不对外部地址发请求。
#pragma once

// windows.h 必须最先：下面 std::optional<DWORD> 要用到 DWORD，
// 而且放在最前面才能保证 .cpp 里 winsock2.h 先于 windows.h 的顺序不被破坏。
#include <windows.h>

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace online_service {

// 与 scripts\online_gallery_service.py 的 DEFAULT_PORT 保持一致。
constexpr int kDefaultPort = 45835;
constexpr wchar_t kStatusPath[] = L"/api/online/status";
constexpr wchar_t kServiceScriptName[] = L"online_gallery_service.py";

// 一次探测的结果。字段语义与服务端 /api/online/status 一一对应。
struct ProbeResult {
    bool reachable = false;      // 端口通且返回了可解析的 JSON
    std::wstring version;        // server.version，例如 L"1.2.0"
    std::wstring libraryRoot;    // libraryRoot，作品真源目录
    std::wstring localIp;        // ip —— 手机端连的就是这个局域网地址
    bool watchdogActive = false; // watchdog.active —— 成品库文件实时监听有没有在工作
    long long totalWorks = -1;   // totalWorks，-1 = 没拿到
    bool staleCode = false;      // code.staleCode —— 真 = 跑的进程落后于磁盘上的脚本
    // updateRelay 里没有 ok 字段，用三个计数一起判：代取清单、代取安装包都没失败、
    // 且缓存字节与清单 sha256 从未不一致 ⇒ 手机才拿得到能装上的包。
    bool relayOk = false;
    std::wstring relayNote;      // 中转缓存了哪个版本 / 最近一次代取错误
    long long scanErrors = -1;   // scanErrors.count —— 扫描期被跳过的异常数（>0 要警觉）
    long long throttled = -1;    // scanThrottle.throttled
    std::wstring error;          // 探测失败原因（人话）
};

// 持久化在注册表的配置。
struct Config {
    int port = kDefaultPort;
    std::wstring pythonPath;   // pythonw.exe（用 pythonw 才不会弹出黑框）
    std::wstring scriptPath;   // online_gallery_service.py
};

// ---------------- 纯逻辑：不碰系统，CI 可单测 ----------------

// 从 exe 所在目录出发，逐级向上找 scripts\online_gallery_service.py。
// 之所以要逐级向上：开发期 exe 在 windows-native\build\Release，发布期在 windows-native\out，
// 两种布局向上的层数不一样，写死任何一层都会在某一种布局下找不到。
std::vector<std::wstring> ScriptCandidates(const std::wstring& startDir);

// 组装 CreateProcess 用的完整命令行。
std::wstring BuildProcessCommand(const Config& config);

// 命令行参数加引号（路径里几乎必然有空格）。
std::wstring QuoteArgument(const std::wstring& value);

// 启动前检查：端口合法 + 解释器存在 + 脚本存在且名字对。
bool Validate(const Config& config, std::wstring& error);

// 状态栏那三行人文案。
std::wstring StatusHeadline(const ProbeResult& result);
std::wstring StatusDetail(const ProbeResult& result, int port);
// 单独一行：手机自动扫不到电脑时，用户拿这行地址手动填。
std::wstring StatusAddress(const ProbeResult& result, int port);

// 解析服务端 /api/online/status 的响应体。抽出来是为了能在 CI 上单测
// —— JSON 是「看起来能解析、其实字段一改就悄悄失效」的典型位置。
ProbeResult ParseStatusJson(const std::string& body);

// ---------------- 系统操作：只在真机跑 ----------------

// pythonw.exe / python.exe 的候选位置（本机安装目录优先，其次 PATH）。
std::vector<std::wstring> PythonCandidates();

std::wstring ModuleDirectory();
ProbeResult Probe(int port);
std::optional<DWORD> ListeningPid(int port);

bool Start(const Config& config, DWORD& pid, std::wstring& error);
bool Stop(int port, std::wstring& error);
bool Restart(const Config& config, DWORD& pid, std::wstring& error);

Config LoadConfig();
void SaveConfig(const Config& config);

bool AutoStartEnabled();
void SetAutoStart(bool enable, const Config& config);

void OpenInBrowser(int port);

}  // namespace online_service
