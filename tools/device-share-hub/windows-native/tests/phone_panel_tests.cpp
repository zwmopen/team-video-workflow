// DSH-122 在线手机面板判据。
//
// 这里的每一条陷阱都是从「不报错、只是显示错」这类最难查的故障里倒推出来的：
// 用「全文找 key 取子串」的简易解析，下面每一条都会悄悄取错值，而接口照样返回 200。

#include <iostream>
#include <string>
#include <vector>

#include "phone_panel.h"
#include "test_check.h"

namespace {

// 一份和 online_gallery_service.py /api/online/phones 真实输出同构的样本。
const char* kRealisticPanel = R"J({
  "ok": true,
  "ip": "192.168.0.109",
  "port": 45835,
  "scannedAt": "2026-09-27 03:42:15",
  "subnets": ["192.168.0"],
  "onlineCount": 2,
  "totalResponded": 3,
  "phonePort": 45833,
  "ttlSeconds": 30.0,
  "devices": [
    {
      "ip": "192.168.0.101", "port": 45833, "online": true,
      "lastSeen": "2026-09-27 03:42:15", "transport": "wifi",
      "name": "红米13", "model": "23127RK0CC", "deviceId": "aabbcc",
      "appVersion": "0.8.63", "versionCode": 172, "network": "zwm-5G",
      "workCount": 42, "state": "ready", "protocol": 2,
      "autoReceiveEnabled": true, "verified": true,
      "packageName": "com.zwm.gallery"
    },
    {
      "ip": "192.168.0.105", "port": 45833, "online": true,
      "lastSeen": "2026-09-27 03:42:15", "name": "192.168.0.105",
      "model": "", "appVersion": "", "workCount": -1,
      "verified": false, "error": "info 解析失败"
    },
    {
      "ip": "192.168.0.106", "port": 45833, "online": true,
      "name": "客厅摄像头", "verified": true, "workCount": 7,
      "appVersion": "0.8.63"
    }
  ]
})J";

void TestRealisticPanel() {
    auto panel = phone_panel::ParsePhonesJson(kRealisticPanel);
    CHECK_MSG(panel.error.empty(), "真实面板不该解析出错");
    CHECK_MSG(panel.ok, "ok 必须是 true");
    CHECK_MSG(panel.localIp == L"192.168.0.109", "本机 IP 要取到");
    CHECK_MSG(panel.scannedAt == L"2026-09-27 03:42:15", "扫描时间要取到");
    CHECK_MSG(panel.onlineCount == 2, "onlineCount 是服务端口径，客户端不该自己重算");
    CHECK_MSG(panel.totalResponded == 3, "totalResponded 包含未验证设备");
    CHECK_MSG(panel.phonePort == 45833, "手机端口要取到");
    CHECK_MSG(panel.devices.size() == 3, "三台设备都要解析出来");

    const auto& first = panel.devices[0];
    CHECK_MSG(first.ip == L"192.168.0.101", "第一台 IP");
    CHECK_MSG(first.port == 45833, "第一台端口");
    CHECK_MSG(first.name == L"红米13", "第一台设备名");
    CHECK_MSG(first.model == L"23127RK0CC", "第一台机型");
    CHECK_MSG(first.appVersion == L"0.8.63", "第一台版本号");
    CHECK_MSG(first.workCount == 42, "第一台作品数");
    CHECK_MSG(first.verified, "第一台是自家 App");
    CHECK_MSG(first.error.empty(), "第一台没有错误");

    // 未验证的那台：名字退化成 IP，且要把失败原因带出来
    const auto& second = panel.devices[1];
    CHECK_MSG(second.verified == false, "第二台不是自家 App");
    CHECK_MSG(second.error == L"info 解析失败", "未验证原因要带出来，用户才知道为什么被跳过");
    CHECK_MSG(second.workCount == -1, "拿不到作品数时是 -1，不是 0（0 会被误读成「手机里没有作品」）");

    CHECK_MSG(panel.devices[2].name == L"客厅摄像头", "第三台设备名");
    CHECK_MSG(panel.devices[2].verified, "第三台已验证");
}

// 陷阱 1：外层对象里还嵌了一个 devices。不按括号分层就会取到里面那个假的。
void TestNestedDevicesTrap() {
    const std::string body = R"J({"scan": {"devices": [{"ip": "1.1.1.1", "verified": true}]},
                                  "devices": [{"ip": "192.168.0.5", "verified": true}],
                                  "onlineCount": 1})J";
    auto panel = phone_panel::ParsePhonesJson(body);
    CHECK_MSG(panel.devices.size() == 1, "只能取顶层的 devices，嵌套那个是干扰项");
    CHECK_MSG(panel.devices[0].ip == L"192.168.0.5", "取到的必须是 192.168.0.5 而不是 1.1.1.1");
}

// 陷阱 2：值里的「不平衡」右括号。不做字符串感知的括号配对会被它带偏，
// 后面的字段就全取不到。（用 a}b 而不是 a}b{c —— 后者左右抵消，陷阱就失效了）
void TestBraceInsideStringTrap() {
    const std::string body =
        R"J({"devices": [{"ip": "192.168.0.6", "name": "a}b", "verified": true, "workCount": 9}],
             "onlineCount": 1})J";
    auto panel = phone_panel::ParsePhonesJson(body);
    CHECK_MSG(panel.error.empty(), "值里有花括号不该导致解析失败");
    CHECK_MSG(panel.devices.size() == 1, "设备要解析出来");
    CHECK_MSG(panel.devices[0].name == L"a}b", "name 里的花括号要原样保留");
    CHECK_MSG(panel.devices[0].verified, "花括号后面的 verified 仍要读到");
    CHECK_MSG(panel.devices[0].workCount == 9, "花括号后面的 workCount 仍要读到");
}

// 陷阱 3：值里出现转义引号。字符串扫描若不处理反斜杠，会在这里提前截断，
// 结果就是「后面的字段全取不到」，而且接口照样返回 200，看不出错在哪。
void TestEscapedQuoteTrap() {
    const std::string body =
        R"J({"devices": [{"ip": "192.168.0.7", "name": "a\"b", "verified": true, "workCount": 5}],
             "onlineCount": 1})J";
    auto panel = phone_panel::ParsePhonesJson(body);
    CHECK_MSG(panel.error.empty(), "值里有转义引号不该导致解析失败");
    CHECK_MSG(panel.devices.size() == 1, "设备要解析出来");
    // ⚠️ 期望值必须先算进变量，不能把 `L"a\"b"` 直接写进 CHECK_MSG 的表达式：
    //    CHECK_MSG 用 `#expression` 做字符串化，而 **MSVC 传统预处理器不保证给已有
    //    转义序列的反斜杠再补一层转义** ⇒ `\"` 会被展开成提前闭合的坏字面量。
    //    CI 上实测报 C2017 / C3688 / C2146（本机无编译器，发现不了）。
    const std::wstring expectedName = std::wstring(L"a") + wchar_t('"') + L"b";
    CHECK_MSG(panel.devices[0].name == expectedName, "转义引号要还原成普通引号");
    CHECK_MSG(panel.devices[0].verified, "转义引号后面的 verified 仍要读到");
    CHECK_MSG(panel.devices[0].workCount == 5, "转义引号后面的 workCount 仍要读到");
}

// 陷阱 4：设备对象里嵌了子对象，子对象里还有同名字段。
//
// 服务端给每台设备加嵌套字段是迟早的事（workCounts 已经是字典了）。这时候
// 「在该设备的文本里全文找 name / workCount」就会读到子对象里的值 ——
// 界面上设备名和作品数全错，但一条报错也没有。
void TestNestedFieldInsideDeviceTrap() {
    const std::string body = R"J({"onlineCount": 1, "devices": [
        {"ip": "192.168.0.8",
         "extra": {"name": "不该被读到", "workCount": 999},
         "name": "红米13"}
    ]})J";
    auto panel = phone_panel::ParsePhonesJson(body);
    CHECK_MSG(panel.devices.size() == 1, "设备要解析出来");
    CHECK_MSG(panel.devices[0].ip == L"192.168.0.8", "IP 是设备自己的");
    CHECK_MSG(panel.devices[0].name == L"红米13",
              "设备名必须取本层那个，不能取到 extra 子对象里的同名键");
    CHECK_MSG(panel.devices[0].workCount == -1,
              "本层没有 workCount 就该是 -1，不能拿子对象里的 999 来冒充");
}

// 陷阱 5：多台设备之间字段串台。这是「全文找 key」写法最典型的翻车方式：
// 第二个设备的 name 会把第一个的顶掉，而且两台都显示得出来，看不出错。
void TestNoCrossTalkBetweenDevices() {
    const std::string body = R"J({"onlineCount": 2, "devices": [
        {"ip": "192.168.0.20", "name": "红米13", "workCount": 11, "verified": true},
        {"ip": "192.168.0.21", "name": "iPhone", "workCount": 22, "verified": true},
        {"ip": "192.168.0.22", "name": "K60",    "workCount": 33, "verified": false}
    ]})J";
    auto panel = phone_panel::ParsePhonesJson(body);
    CHECK_MSG(panel.devices.size() == 3, "三台设备");
    CHECK_MSG(panel.devices[0].name == L"红米13", "第一台不能拿到后面的名字");
    CHECK_MSG(panel.devices[0].workCount == 11, "第一台作品数");
    CHECK_MSG(panel.devices[1].name == L"iPhone", "第二台名字");
    CHECK_MSG(panel.devices[1].workCount == 22, "第二台作品数");
    CHECK_MSG(panel.devices[2].name == L"K60", "第三台名字");
    CHECK_MSG(panel.devices[2].workCount == 33, "第三台作品数");
    CHECK_MSG(panel.devices[2].verified == false, "第三台未验证，不能被邻居的 true 带成已验证");
}

void TestRejectsNonPanelPayloads() {
    // 随便一个 {"ok":true} 不能被当成面板数据：真出这种事用户会看到「在线 0 台」还以为没问题
    auto bare = phone_panel::ParsePhonesJson(R"J({"ok": true})J");
    CHECK_MSG(!bare.error.empty(), "光有 ok 不算面板数据，必须报出来");

    auto array = phone_panel::ParsePhonesJson(R"J([1,2,3])J");
    CHECK_MSG(!array.error.empty(), "顶层是数组要拒绝");

    auto empty = phone_panel::ParsePhonesJson("");
    CHECK_MSG(!empty.error.empty(), "空响应要拒绝");

    auto broken = phone_panel::ParsePhonesJson(R"J({"devices": [{"ip": "1.2.3.4")J");
    CHECK_MSG(!broken.error.empty(), "括号没闭合要拒绝，不能返回一个看着正常的空面板");

    // 空 devices 是合法的：真的一台都没有时服务端就这么返回
    auto none = phone_panel::ParsePhonesJson(
        R"J({"ok": true, "ip": "192.168.0.9", "onlineCount": 0, "devices": []})J");
    CHECK_MSG(none.error.empty(), "空 devices 列表是合法结果，不能误报成解析失败");
    CHECK_MSG(none.devices.empty(), "设备列表为空");
    CHECK_MSG(none.ok, "ok 仍是 true");
}

void TestPanelHeadline() {
    phone_panel::Panel none;
    CHECK_MSG(phone_panel::PanelHeadline(none).find(L"一台都没扫到") != std::wstring::npos,
              "一台都没有时要说清前提（同局域网 + App 在前台），别只说「0 台」");

    phone_panel::Panel two;
    two.onlineCount = 2;
    two.totalResponded = 3;
    two.scannedAt = L"2026-09-27 03:42:15";
    auto head = phone_panel::PanelHeadline(two);
    CHECK_MSG(head.find(L"2 台") != std::wstring::npos, "在线台数要出现");
    CHECK_MSG(head.find(L"另有 1 台") != std::wstring::npos,
              "有非本产品占用端口时要提示，否则用户以为自己的手机被识别错了");
    CHECK_MSG(head.find(L"扫描于") != std::wstring::npos, "扫描时间要带上，用户才知道数据是不是陈的");

    // 「有应答但没有一台是本产品」和「一台都没扫到」是两回事，不能混成一句
    phone_panel::Panel stranger;
    stranger.totalResponded = 2;
    CHECK_MSG(phone_panel::PanelHeadline(stranger).find(L"没有一台是自家相册") != std::wstring::npos,
              "全是陌生设备时要说清楚，用户才知道是网段里有别的软件占了端口");
}

void TestPanelWarning() {
    // 口径自检：服务端说在线 2 台，但列表里一台 verified 都没有 ⇒ 一定有鬼
    phone_panel::Panel mismatch;
    mismatch.onlineCount = 2;
    mismatch.totalResponded = 2;
    phone_panel::Device fake;
    fake.ip = L"192.168.0.30";
    fake.name = L"192.168.0.30";
    fake.verified = false;
    mismatch.devices.push_back(fake);
    CHECK_MSG(!phone_panel::PanelWarning(mismatch).empty(), "服务端与列表对不上时必须给警告");
    CHECK_MSG(phone_panel::PanelWarning(mismatch).find(L"刷新") != std::wstring::npos,
              "警告要给出下一步动作，不能只说「不一致」");

    // 数量对得上就不该弹警告，否则就成了常亮噪声
    phone_panel::Panel good;
    good.onlineCount = 2;
    good.totalResponded = 2;
    for (int i = 0; i < 2; ++i) {
        phone_panel::Device device;
        device.ip = L"192.168.0.4" + std::to_wstring(i);
        device.verified = true;
        good.devices.push_back(device);
    }
    CHECK_MSG(phone_panel::PanelWarning(good).empty(), "一致时不该有警告（常亮警告 = 没人看）");

    // 一台都没有时也不该弹（那是由 headline 负责说明的）
    phone_panel::Panel zero;
    CHECK_MSG(phone_panel::PanelWarning(zero).empty(), "零台不该叠一个警告");
}

void TestDeviceLine() {
    phone_panel::Device full;
    full.ip = L"192.168.0.101";
    full.port = 45833;
    full.name = L"红米13";
    full.model = L"23127RK0CC";
    full.appVersion = L"0.8.63";
    full.workCount = 42;
    full.verified = true;
    auto line = phone_panel::DeviceLine(full);
    CHECK_MSG(line.find(L"192.168.0.101:45833") != std::wstring::npos, "IP 与端口都要出现");
    CHECK_MSG(line.find(L"红米13") != std::wstring::npos, "设备名要出现");
    CHECK_MSG(line.find(L"23127RK0CC") != std::wstring::npos, "机型要出现");
    CHECK_MSG(line.find(L"0.8.63") != std::wstring::npos, "版本号要出现");
    CHECK_MSG(line.find(L"42") != std::wstring::npos, "作品数要出现");
    CHECK_MSG(line.find(L"不是自家") == std::wstring::npos, "已验证的不该带跳过提示");

    // 未验证且带原因的：原因必须显示出来，否则用户只看到「被跳过」不知道为什么
    phone_panel::Device stranger;
    stranger.ip = L"192.168.0.105";
    stranger.name = L"192.168.0.105";
    stranger.verified = false;
    stranger.error = L"info 解析失败";
    auto strangerLine = phone_panel::DeviceLine(stranger);
    CHECK_MSG(strangerLine.find(L"info 解析失败") != std::wstring::npos, "未验证原因要显示");

    // workCount 为 -1（没拿到）时不能显示成 0 个作品
    phone_panel::Device unknown;
    unknown.ip = L"192.168.0.106";
    unknown.name = L"192.168.0.106";
    unknown.workCount = -1;
    unknown.verified = true;
    CHECK_MSG(phone_panel::DeviceLine(unknown).find(L"-1") == std::wstring::npos,
              "拿不到作品数时不能把 -1 直接显示出来");
}

void TestSyncResultParsing() {
    const std::string applied = R"J({
        "ok": true, "dryRun": false, "phoneCount": 2, "appliedCount": 5,
        "message": "共回写 5 个作品的使用次数",
        "results": [
            {"phone": "192.168.0.101:45833", "ok": true, "appliedCount": 3},
            {"phone": "192.168.0.105:45833", "ok": false, "appliedCount": 0,
             "error": "手机相册服务不可达（确认同网段且相册 App 在前台）"}
        ]
    })J";
    auto result = phone_panel::ParseSyncJson(applied);
    CHECK_MSG(result.error.empty(), "整体没有错误");
    CHECK_MSG(result.ok, "ok 为 true");
    CHECK_MSG(result.dryRun == false, "这是真落盘不是预演");
    CHECK_MSG(result.appliedCount == 5, "回写总数");
    CHECK_MSG(result.phoneCount == 2, "手机台数");
    CHECK_MSG(result.phoneErrors.size() == 1, "只有一台失败");
    CHECK_MSG(result.phoneErrors[0].find(L"192.168.0.105") != std::wstring::npos,
              "失败原因要带上是哪台手机，否则用户不知道该去看哪台");
    CHECK_MSG(result.phoneErrors[0].find(L"不可达") != std::wstring::npos, "具体原因要带出来");

    // 「未发现在线手机」是 HTTP 200 + ok:false，不能当成网络错误丢掉
    const std::string none = R"J({"ok": false, "error": "未发现在线手机；可显式传 host（例如 192.168.1.200）",
                                   "appliedCount": 0})J";
    auto noneResult = phone_panel::ParseSyncJson(none);
    CHECK_MSG(noneResult.error.find(L"未发现在线手机") != std::wstring::npos,
              "未发现手机的原因要原样展示给用户");
    CHECK_MSG(noneResult.appliedCount == 0, "appliedCount 为 0");

    // 干扰项：results 里 ok:true 的那条没有 error，不能被当成失败
    CHECK_MSG(result.phoneErrors.size() == 1, "成功那台不该出现在错误列表里");

    auto garbage = phone_panel::ParseSyncJson(R"J({"hello": "world"})J");
    CHECK_MSG(!garbage.error.empty(), "没有 appliedCount / error 字段的响应要拒绝");
}

void TestSyncSummary() {
    phone_panel::SyncResult dry;
    dry.dryRun = true;
    dry.appliedCount = 7;
    dry.phoneCount = 2;
    auto dryText = phone_panel::SyncSummary(dry);
    CHECK_MSG(dryText.find(L"预演") != std::wstring::npos, "干跑必须写明是预演");
    // ⚠️ 判据要查「已落盘」而不是「落盘」：干跑文案是「预演（没有落盘）」，
    //    里面本来就含「落盘」两个字，判「不含落盘」必然失败。
    CHECK_MSG(dryText.find(L"已落盘") == std::wstring::npos, "干跑文案里不能出现「已落盘」");
    CHECK_MSG(dryText.find(L"没有落盘") != std::wstring::npos, "干跑要明确说没有落盘");
    CHECK_MSG(dryText.find(L"7") != std::wstring::npos, "回写数量要出现");

    phone_panel::SyncResult real;
    real.dryRun = false;
    real.appliedCount = 3;
    real.phoneCount = 1;
    auto realText = phone_panel::SyncSummary(real);
    CHECK_MSG(realText.find(L"已落盘") != std::wstring::npos, "真落盘要写明");
    CHECK_MSG(realText.find(L"预演") == std::wstring::npos, "真落盘不该出现预演字样");

    phone_panel::SyncResult failed;
    failed.error = L"未发现在线手机";
    CHECK_MSG(phone_panel::SyncSummary(failed) == L"未发现在线手机",
              "整体失败时直接给原因，别再拼一句「已落盘：共回写 0 个」看着像成功了");
}

void TestBuildSyncRequestBody() {
    // ⚠️ 期望值一律先算进变量再断言，不把带引号/反斜杠的字面量写进 CHECK_MSG ——
    //    宏里的 `#expression` 字符串化在 MSVC 传统预处理器下会把它展开坏（见陷阱 3）。
    const std::string expectBare = R"J({"dryRun":true})J";
    const std::string expectReal = R"J({"dryRun":false})J";
    const std::string expectHostsKey = R"J("hosts":[)J";
    const std::string expectHost1 = R"J("192.168.0.101")J";
    const std::string expectHost2 = R"J("192.168.0.105:45833")J";
    // 期望的请求体片段：`"a\"b\\c"`（外层是 JSON 引号，内部各转义一层）
    const std::string expectEscaped = std::string("\"a\\\"b\\\\c\"");

    // 不传 host ⇒ 让服务端自己扫在线手机
    auto bare = phone_panel::BuildSyncRequestBody(true, {});
    CHECK_MSG(bare == expectBare, "空 hosts 时只发 dryRun");

    auto real = phone_panel::BuildSyncRequestBody(false, {});
    CHECK_MSG(real == expectReal, "落盘时 dryRun 为 false");

    auto withHosts = phone_panel::BuildSyncRequestBody(true, {L"192.168.0.101", L"192.168.0.105:45833"});
    CHECK_MSG(withHosts.find(expectHostsKey) != std::string::npos, "带 hosts 数组");
    CHECK_MSG(withHosts.find(expectHost1) != std::string::npos, "第一个 host");
    CHECK_MSG(withHosts.find(expectHost2) != std::string::npos, "第二个 host 带端口");
    CHECK_MSG(withHosts.back() == '}', "收尾是右花括号");

    // 转义：界面输入里带引号/反斜杠会把 JSON 直接弄坏，服务端会返回 400
    auto escaped = phone_panel::BuildSyncRequestBody(false, {L"a\"b\\c"});
    CHECK_MSG(escaped.find(expectEscaped) != std::string::npos,
              "引号和反斜杠必须转义，否则请求体不是合法 JSON");
}

}  // namespace

int main() {
    std::cout << "phone_panel_tests\n";
    TestRealisticPanel();
    TestNestedDevicesTrap();
    TestBraceInsideStringTrap();
    TestEscapedQuoteTrap();
    TestNestedFieldInsideDeviceTrap();
    TestNoCrossTalkBetweenDevices();
    TestRejectsNonPanelPayloads();
    TestPanelHeadline();
    TestPanelWarning();
    TestDeviceLine();
    TestSyncResultParsing();
    TestSyncSummary();
    TestBuildSyncRequestBody();
    return dsh_test::Finish("phone_panel_tests");
}
