// 电脑端「在线手机」面板 —— 把 scripts\phone_sync.py 的能力收进客户端。
//
// 背景（2026-09-27 电脑客户端升级）：
//   scripts\phone_sync.py 干两件事：① 扫本机所在 /24 网段，找出此刻真能连上的手机；
//   ② 把手机「本地相册」里分享过的次数回写到电脑端元数据，让两端次数一致。
//   服务端 online_gallery_service.py 早就把这两个能力挂成了接口
//   （GET /api/online/phones、POST /api/online/sync-phone-counts），
//   手机一来读相册还会自动在后台回读一次。
//
//   但**客户端完全没有入口**：想知道「现在有哪台手机在线、各发了几次」，
//   用户得自己开命令行去跑 python 脚本。本模块把这两个接口接进设置窗口。
//
// 设计约束：
//   1. 纯逻辑（JSON 解析 / 文案）与系统操作（HTTP）分开，前者可在 CI 上单测。
//   2. 只探本机回环 127.0.0.1:45835，绝不对外部地址发请求。
//   3. 客户端只负责「展示 + 发起」，次数怎么算、只增不减这些铁律仍由服务端把关，
//      这里不重新实现一遍，避免出现两套口径。
#pragma once

#include <string>
#include <vector>

namespace phone_panel {

// 与 scripts\phone_sync.py discover_phones() 里 _enrich() 的字段一一对应。
struct Device {
    std::wstring ip;
    int port = 0;
    std::wstring name;       // 设备名；拿不到 /v2/info 时服务端会退化成 ip
    std::wstring model;      // 机型
    std::wstring appVersion; // 手机端相册版本号
    long long workCount = -1; // 手机本地作品数，-1 = 没拿到
    bool verified = false;   // 包名确实是 com.zwm.gallery（不是别的服务占了 45833）
    std::wstring lastSeen;
    std::wstring error;      // /v2/info 拉不到时的原因
};

// GET /api/online/phones 的解析结果。
struct Panel {
    bool ok = false;
    std::wstring localIp;
    std::wstring scannedAt;
    long long onlineCount = 0;     // 服务端口径：只算 verified 的
    long long totalResponded = 0;  // 端口有应答的总数（含非本产品）
    int phonePort = 0;
    std::vector<Device> devices;
    std::wstring error;
};

// POST /api/online/sync-phone-counts 的解析结果。
struct SyncResult {
    bool ok = false;
    bool dryRun = false;
    long long phoneCount = 0;
    long long appliedCount = 0;
    std::wstring message;                     // 服务端给的总结（人话）
    std::wstring error;                       // 整体失败原因（未发现在线手机等）
    std::vector<std::wstring> phoneErrors;    // 每台手机各自的失败原因
};

// ---------------- 纯逻辑：不碰网络，CI 可单测 ----------------

Panel ParsePhonesJson(const std::string& body);
SyncResult ParseSyncJson(const std::string& body);

// 面板那几行人文案。抽出来单测，是因为「能解析」和「展示对了」是两回事：
// 数字对但单位/口径写错，用户一样会被误导。
std::wstring PanelHeadline(const Panel& panel);
std::wstring DeviceLine(const Device& device);
// 口径自检：服务端说在线 N 台，但解析出来的设备里一台 verified 都没有 ⇒ 一定有鬼。
std::wstring PanelWarning(const Panel& panel);
std::wstring SyncSummary(const SyncResult& result);

// hosts 为空时只发 {"dryRun":...}，让服务端自己扫在线手机。
std::string BuildSyncRequestBody(bool dryRun, const std::vector<std::wstring>& hosts);

// ---------------- 系统操作：只在真机跑 ----------------

// refresh=true 会让服务端丢掉 30 秒缓存重新扫一遍网段（要等几秒）。
Panel FetchPhones(int port, bool refresh);
SyncResult SyncCounts(int port, bool dryRun, const std::vector<std::wstring>& hosts);

}  // namespace phone_panel
