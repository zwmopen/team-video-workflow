// 「设置 → 在线相册服务」纯逻辑单测。
//
// 为什么单独测这些：客户端以前对这个 Python 服务一无所知（源码里连 45835 都没出现过），
// 状态全靠人去看黑框。现在把它收进客户端之后，最脆的两处是
//   ① 命令行拼装（路径带空格几乎必然，少一对引号就是「启动失败」但看不出为什么）
//   ② /api/online/status 的取值（自写极简 JSON 取值器，字段一多就会取串）
// 这两处都是「看起来对、其实是空的」的典型，所以判据必须能被打掉。
//
// ⚠️ 用自定义 CHECK 而不是 assert：CI 编的是 Release（NDEBUG），assert 会被整段编译掉，
//    那样这个测试就变成「永远绿色」的假闸门。
#include "online_service.h"

#include <windows.h>

#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

namespace {

int gFailures = 0;

void Check(bool condition, const wchar_t* expression, int line) {
    if (condition) return;
    ++gFailures;
    std::wcerr << L"  [FAIL] 第 " << line << L" 行: " << expression << L"\n";
}

#define CHECK(expression) Check((expression), L#expression, __LINE__)

std::wstring Quote(const std::wstring& value) { return L"\"" + value + L"\""; }

// 真实结构：按 scripts/online_gallery_service.py 的 /api/online/status 逐字段摆的。
// 字段顺序也照抄（先 scanErrors 后 scanThrottle），这样测试跑的是真数据的形状。
std::string RealStatusBody() {
    return R"({
      "ok": true,
      "server": "DeviceShareHub-OnlineGallery",
      "version": "1.2.0",
      "ip": "192.168.1.23",
      "port": 45835,
      "totalWorks": 483,
      "libraryRoot": "D:\\主项目\\成品库（GPT+本地脚本制作）",
      "timestamp": 1780000000,
      "features": ["onlineRecycle", "lanUpdateRelay"],
      "code": {"staleCode": false, "ageSec": 12},
      "watchdog": {"active": true, "intervalSec": 60, "lastPollAt": 1780000000,
                   "lastChangeAt": 1779999000, "trackedPaths": 2,
                   "rootDir": "D:\\主项目\\成品库（GPT+本地脚本制作）"},
      "updateRelay": {
        "proxy": "http://127.0.0.1:7897",
        "cachedVersion": "0.8.62",
        "cachedVersionCode": 173,
        "manifestFetches": 6,
        "manifestFailures": 0,
        "downloads": 3,
        "downloadFailures": 0,
        "cachedSha": "c7372c5a44092b6b",
        "shaMismatch": 0,
        "lastError": "",
        "hint": ""
      },
      "scanErrors": {"count": 0, "recent": []},
      "scanThrottle": {"minIntervalSec": 3.0, "throttled": 0}
    })";
}

}  // namespace

int wmain() {
    using online_service::Config;
    using online_service::ProbeResult;

    // ---------------- 命令行拼装 ----------------
    CHECK(online_service::QuoteArgument(L"C:\\Program Files\\py.exe") ==
          L"\"C:\\Program Files\\py.exe\"");

    {
        // 路径里带空格是常态（「Program Files」/「成品库（GPT+本地脚本制作）」），
        // 引号缺失时 CreateProcess 会把路径从空格处切成两半，报的错还是看不懂的系统错误。
        Config config;
        config.pythonPath = L"C:\\Program Files\\Python311\\pythonw.exe";
        config.scriptPath = L"D:\\tools\\device share hub\\scripts\\online_gallery_service.py";
        config.port = 45835;
        CHECK(online_service::BuildProcessCommand(config) ==
              Quote(config.pythonPath) + L" " + Quote(config.scriptPath) + L" --port 45835");
    }
    {
        Config config;
        config.pythonPath = L"pythonw.exe";
        config.scriptPath = L"online_gallery_service.py";
        config.port = 45999;
        // 端口必须跟着配置走，不然改了端口重启还是起在老端口上
        CHECK(online_service::BuildProcessCommand(config).rfind(L"--port 45999") != std::wstring::npos);
    }

    // ---------------- 脚本位置候选 ----------------
    {
        const std::wstring start = L"C:\\a\\b\\c\\d\\e\\f";
        auto candidates = online_service::ScriptCandidates(start);
        // 逐级向上 6 层、每层 3 个候选（本级 / scripts\ / tools\device-share-hub\scripts\）。
        // 之所以要逐级向上：开发期 exe 在 build\Release、发布期在 out，写死层数必然有一种布局找不到。
        CHECK(candidates.size() == 18);
        CHECK(candidates[0] == start + L"\\" + online_service::kServiceScriptName);
        CHECK(candidates[1] == start + L"\\scripts\\" + online_service::kServiceScriptName);
        bool allNamedRight = true;
        for (const auto& item : candidates) {
            if (std::filesystem::path(item).filename().wstring() !=
                online_service::kServiceScriptName) {
                allNamedRight = false;
            }
        }
        CHECK(allNamedRight);
    }

    // ---------------- 启动前检查 ----------------
    {
        std::filesystem::path root = std::filesystem::temp_directory_path() /
            (L"dsh-online-service-test-" + std::to_wstring(GetCurrentProcessId()));
        std::filesystem::remove_all(root);
        std::filesystem::create_directories(root);
        std::filesystem::path fakePython = root / L"pythonw.exe";
        std::filesystem::path fakeScript = root / online_service::kServiceScriptName;
        std::filesystem::path wrongScript = root / L"三平台文案.txt";
        { std::ofstream out(fakePython, std::ios::binary); out << "x"; }
        { std::ofstream out(fakeScript, std::ios::binary); out << "x"; }
        { std::ofstream out(wrongScript, std::ios::binary); out << "x"; }

        std::wstring error;
        Config config;
        config.pythonPath = fakePython.wstring();
        config.scriptPath = fakeScript.wstring();

        Config bad = config;
        bad.port = 0;
        CHECK(!online_service::Validate(bad, error) && !error.empty());
        bad = config;
        bad.port = 65536;
        CHECK(!online_service::Validate(bad, error) && !error.empty());

        bad = config;
        bad.pythonPath.clear();
        CHECK(!online_service::Validate(bad, error) && error.find(L"解释器") != std::wstring::npos);

        bad = config;
        bad.scriptPath.clear();
        CHECK(!online_service::Validate(bad, error) && error.find(L"服务脚本") != std::wstring::npos);

        bad = config;
        bad.scriptPath = wrongScript.wstring();
        // 选错文件要指名道姓说出来，而不是「无法启动」
        CHECK(!online_service::Validate(bad, error) &&
              error.find(online_service::kServiceScriptName) != std::wstring::npos);

        bad = config;
        bad.pythonPath = (root / L"not-here.exe").wstring();
        CHECK(!online_service::Validate(bad, error) && error.find(L"不存在") != std::wstring::npos);

        error.clear();
        CHECK(online_service::Validate(config, error) && error.empty());

        std::filesystem::remove_all(root);
    }

    // ---------------- 状态解析 ----------------
    {
        ProbeResult empty = online_service::ParseStatusJson("");
        CHECK(!empty.reachable);
        CHECK(!empty.error.empty());
    }
    {
        // 端口被别的程序占了：不能因为「返回的确实是 200」就当服务在跑
        ProbeResult other = online_service::ParseStatusJson(R"({"ok":true,"hello":"world"})");
        CHECK(!other.reachable);
        CHECK(!other.error.empty());
    }
    {
        ProbeResult result = online_service::ParseStatusJson(RealStatusBody());
        CHECK(result.reachable);
        CHECK(result.error.empty());
        CHECK(result.version == L"1.2.0");
        CHECK(result.totalWorks == 483);
        CHECK(!result.libraryRoot.empty());
        // 手机端连的就是这个地址，手机自动扫不到电脑时用户要抄走它
        CHECK(result.localIp == L"192.168.1.23");
        CHECK(result.watchdogActive);
        CHECK(!result.staleCode);
        CHECK(result.relayOk);
        CHECK(result.relayNote.find(L"0.8.62") != std::wstring::npos);
        CHECK(result.scanErrors == 0);
        CHECK(result.throttled == 0);
    }
    {
        // 中转坏了必须亮出来：这是手机升不了级的直接原因（shaMismatch 时手机校验必挂且双方零报错）
        std::string body = RealStatusBody();
        const size_t at = body.find("\"shaMismatch\": 0");
        CHECK(at != std::string::npos);
        body.replace(at, std::string("\"shaMismatch\": 0").size(), "\"shaMismatch\": 2");
        ProbeResult result = online_service::ParseStatusJson(body);
        CHECK(result.reachable);
        CHECK(!result.relayOk);
    }
    {
        std::string body = RealStatusBody();
        const size_t at = body.find("\"staleCode\": false");
        CHECK(at != std::string::npos);
        body.replace(at, std::string("\"staleCode\": false").size(), "\"staleCode\": true");
        ProbeResult result = online_service::ParseStatusJson(body);
        CHECK(result.staleCode);
    }
    {
        // ⚠️ 取值定位：scanThrottle 里塞一个假的 count，再让 scanErrors 排在它后面。
        // 取值器不先定位到 scanErrors 就会把 scanThrottle 的 count 当成扫描错误数。
        const std::string body = R"({
          "totalWorks": 7,
          "scanThrottle": {"minIntervalSec": 3.0, "throttled": 12, "count": 999},
          "scanErrors": {"count": 3, "recent": []}
        })";
        ProbeResult result = online_service::ParseStatusJson(body);
        CHECK(result.reachable);
        CHECK(result.scanErrors == 3);
        CHECK(result.throttled == 12);
    }
    {
        // 同样的道理：active 是个泛用名，thumbnail 里也有一个。
        // 不先定位到 watchdog 对象，就会把缩略图的活跃状态当成文件监听状态。
        const std::string body = R"({
          "totalWorks": 7,
          "thumbnail": {"active": false, "pillow": true},
          "watchdog": {"active": true, "intervalSec": 60}
        })";
        ProbeResult result = online_service::ParseStatusJson(body);
        CHECK(result.reachable);
        CHECK(result.watchdogActive);
    }

    // ---------------- 状态文案 ----------------
    {
        ProbeResult down;
        down.error = L"端口没有在监听（服务没启动）。";
        CHECK(online_service::StatusHeadline(down).find(L"未运行") != std::wstring::npos);
        // 没跑起来时要直接告诉用户下一步点什么，而不是只说「未运行」
        CHECK(online_service::StatusDetail(down, 45835).find(L"启动") != std::wstring::npos);
    }
    {
        ProbeResult up = online_service::ParseStatusJson(RealStatusBody());
        std::wstring head = online_service::StatusHeadline(up);
        CHECK(head.find(L"运行中") != std::wstring::npos);
        CHECK(head.find(L"483") != std::wstring::npos);
        CHECK(head.find(L"1.2.0") != std::wstring::npos);
        std::wstring detail = online_service::StatusDetail(up, 45835);
        CHECK(detail.find(L"45835") != std::wstring::npos);
        CHECK(detail.find(L"代码新鲜") != std::wstring::npos);
        CHECK(detail.find(L"更新中转正常") != std::wstring::npos);
        CHECK(detail.find(L"文件实时监听中") != std::wstring::npos);
        // 地址行：手机自动扫不到电脑时，用户要靠这行手动填
        CHECK(online_service::StatusAddress(up, 45835).find(L"http://192.168.1.23:45835") !=
              std::wstring::npos);
    }
    {
        ProbeResult noWatchdog = online_service::ParseStatusJson(RealStatusBody());
        noWatchdog.watchdogActive = false;
        CHECK(online_service::StatusDetail(noWatchdog, 45835).find(L"手动刷新") !=
              std::wstring::npos);
    }
    {
        ProbeResult down;
        down.error = L"端口没有在监听（服务没启动）。";
        CHECK(online_service::StatusAddress(down, 45835).find(L"服务跑起来后") !=
              std::wstring::npos);
    }
    {
        ProbeResult stale = online_service::ParseStatusJson(RealStatusBody());
        stale.staleCode = true;
        // 陈旧必须指名让「重启」：改了脚本不重启，跑的还是旧代码
        CHECK(online_service::StatusDetail(stale, 45835).find(L"代码陈旧") != std::wstring::npos);
    }
    {
        ProbeResult noisy = online_service::ParseStatusJson(RealStatusBody());
        noisy.scanErrors = 4;
        CHECK(online_service::StatusDetail(noisy, 45835).find(L"扫描跳过") != std::wstring::npos);
    }
    {
        ProbeResult relayBroken = online_service::ParseStatusJson(RealStatusBody());
        relayBroken.relayOk = false;
        CHECK(online_service::StatusDetail(relayBroken, 45835).find(L"更新中转异常") !=
              std::wstring::npos);
    }

    if (gFailures) {
        std::wcerr << L"online_service_tests FAILED (" << gFailures << L" 项)\n";
        return 1;
    }
    std::wcout << L"online_service_tests passed\n";
    return 0;
}
