// 电脑端新版本判定的单测（DSH-121）。
//
// 为什么要测：以前电脑端拿**主仓库的手工 Release tag** 跟 APP_VERSION 比，
// tag 一旦不是 Windows 版本（比如手机端的 v0.8.63），4.3.30 > 0.8.63 就会永远判
// 「当前已是最新版本」—— 用户从此再也收不到电脑端更新提示，而且**全程零报错**。
// 这种「静默判没有更新」的故障只能靠判据钉死。
//
// ⚠️ 用 tests/test_check.h 的 CHECK 而不是 assert：CI 编 Release（NDEBUG），
//    assert 会被整段编译掉，测试就变成永远绿色的假闸门（DSH-120-B 的教训）。
#include "update_check.h"
#include "test_check.h"

#include <iostream>
#include <string>

namespace {

// 发布仓库 latest.json 的真实形状（按 CI 里 jq 输出的样子摆）。
// ⚠️ notes 里故意写了 Windows 这个词 —— 早期版本的取值器就是在这儿翻车的。
std::string RealManifest() {
    return R"({
  "sha256": "74d4dc7ae4ae05989192419baf71bf847a36415c9282fd4a0ba6dca328d9b8de",
  "apk_url": "https://github.com/zwmopen/gallery-updates/releases/download/v0.8.63/album-Android-v0.8.63.apk",
  "ios": {
    "version_name": "0.8.45",
    "build": 117,
    "ipa_url": "https://github.com/zwmopen/gallery-updates/releases/download/v0.8.63/album-iOS-v0.8.45-altstore.ipa"
  },
  "version_code": 174,
  "tag_name": "v0.8.63",
  "html_url": "https://github.com/zwmopen/gallery-updates/releases/tag/v0.8.63",
  "version_name": "0.8.63",
  "notes": "相册 Android 0.8.63（versionCode 174）/ iOS 0.8.45（build 117）；Windows 包一起发",
  "url": "https://github.com/zwmopen/gallery-updates/releases/download/v0.8.63/album-Android-v0.8.63.apk",
  "windows": {
    "version": "4.3.30",
    "sha256": "7b5127c1f1760000000000000000000000000000000000000000000000000000",
    "url": "https://github.com/zwmopen/gallery-updates/releases/download/v0.8.63/DeviceShareHub-Windows-V4.3.30.exe",
    "file_name": "DeviceShareHub-Windows-V4.3.30.exe",
    "release_url": "https://github.com/zwmopen/gallery-updates/releases/tag/v0.8.63"
  }
})";
}

}  // namespace

int wmain() {
    std::cout << "--- 1. 真实清单能正确解析 windows 段 ---\n";
    {
        auto s = update_check::ParseWindowsSection(RealManifest());
        CHECK_MSG(s.present, "真实 latest.json 里必须能认出 windows 段");
        CHECK_MSG(s.version == "4.3.30", "windows.version 必须取到电脑端版本号");
        CHECK(s.fileName == "DeviceShareHub-Windows-V4.3.30.exe");
        CHECK(s.sha256.size() == 64);
        CHECK(s.url.find("DeviceShareHub-Windows-V4.3.30.exe") != std::string::npos);
        CHECK(s.releaseUrl.find("/releases/tag/v0.8.63") != std::string::npos);
        // 不能被上层同名的 ios 段污染
        CHECK_MSG(s.version != "0.8.45", "不能把 ios.version_name 当成 windows.version");
    }

    std::cout << "--- 2. 定位陷阱：别处出现 windows 这个词不能误命中 ---\n";
    {
        // 陷阱 1（真）：ios 段内部也有一个 windows 键。
        //   没有括号深度感知的实现会先命中 ios 里那个 ⇒ 取到别人的版本号，且零报错。
        std::string nested = R"({"ios": {"windows": {"version": "0.1.9"}},
                                 "windows": {"version": "4.3.34", "url": "u"}})";
        auto s = update_check::ParseWindowsSection(nested);
        CHECK_MSG(s.present && s.version == "4.3.34",
                  "嵌套对象里的 windows 键不能顶替顶层那个");
    }
    {
        // 陷阱 2：windows 段出现在别的嵌套对象之后，且顶层在它之前还有数组
        std::string afterArray = R"({"features": ["a", "b"],
                                     "ios": {"build": 117},
                                     "windows": {"version": "4.3.35"}})";
        auto s = update_check::ParseWindowsSection(afterArray);
        CHECK_MSG(s.present && s.version == "4.3.35", "前面有数组时也要能定位到顶层 windows 键");
    }
    {
        // 有别的键以 windows 开头（windows_note），不能被当成 windows 段
        std::string prefixed = R"({"windows_note": "ignore me",
                                   "windows": {"version": "4.3.32"}})";
        auto s = update_check::ParseWindowsSection(prefixed);
        CHECK_MSG(s.present && s.version == "4.3.32",
                  "windows_note 这种前缀键不能被误当成 windows 段");
    }
    {
        // 陷阱 3：字符串值里含**不平衡**的右括号。
        //   ⚠️ 特意用 "a}b" 而不是 "a}b{c"：后者左右括号恰好抵消，
        //      不带字符串感知的实现也会误打误撞配对成功 ⇒ 陷阱失效。
        std::string braces = R"({"windows": {"version": "4.3.33", "url": "a}b"}})";
        auto s = update_check::ParseWindowsSection(braces);
        CHECK_MSG(s.present && s.version == "4.3.33" && s.url == "a}b",
                  "字符串里的大括号不能参与配对");
    }

    std::cout << "--- 3. windows 段缺失 / 异常时要说「不知道」，不能瞎说「已是最新」---\n";
    {
        auto s = update_check::ParseWindowsSection(R"({"version_name": "0.8.63"})");
        CHECK_MSG(!s.present, "没有 windows 段时必须认出来");
        auto d = update_check::DecideWindowsUpdate(s, "4.3.30");
        CHECK_MSG(!d.comparable && !d.hasUpdate, "段缺失时不能比，也不能说有更新");
        CHECK_MSG(!d.reason.empty(), "段缺失时必须给人一个理由");
    }
    {
        // CI 找不到 exe 时会把 windows.version 写成空串
        update_check::WindowsSection empty;
        empty.present = true;
        empty.version = "";
        auto d = update_check::DecideWindowsUpdate(empty, "4.3.30");
        CHECK_MSG(!d.comparable && !d.hasUpdate, "版本号为空时不能拿去比");
    }

    std::cout << "--- 4. 口径错配守卫（本条最重要）---\n";
    {
        // 拿电脑端 4.3.30 去比手机端 0.8.63：4 > 0，朴素比较会永远判「已是最新」
        update_check::WindowsSection wrong;
        wrong.present = true;
        wrong.version = "0.8.63";
        auto d = update_check::DecideWindowsUpdate(wrong, "4.3.30");
        CHECK_MSG(!d.comparable, "主版本号不同 + 当前更新 ⇒ 判定口径错配，拒绝比较");
        CHECK_MSG(!d.hasUpdate, "口径错配时不能顺手判成「有更新」");
        CHECK_MSG(d.reason.find("不是同一套") != std::string::npos,
                  "理由里要说明是口径错配，而不是含糊的失败");
    }
    {
        // 但主版本号真的升了一代（4.x → 5.x）不能被守卫误伤
        update_check::WindowsSection next;
        next.present = true;
        next.version = "5.0.0";
        auto d = update_check::DecideWindowsUpdate(next, "4.3.30");
        CHECK_MSG(d.comparable && d.hasUpdate, "主版本升级必须正常报更新，不能被守卫误伤");
    }

    std::cout << "--- 5. 正常比较 ---\n";
    {
        update_check::WindowsSection newer;
        newer.present = true;
        newer.version = "4.3.31";
        auto d = update_check::DecideWindowsUpdate(newer, "4.3.30");
        CHECK(d.comparable && d.hasUpdate);
        CHECK(d.currentVersion == "4.3.30");
        CHECK(d.latestVersion == "4.3.31");
    }
    {
        update_check::WindowsSection same;
        same.present = true;
        same.version = "4.3.30";
        auto d = update_check::DecideWindowsUpdate(same, "4.3.30");
        CHECK_MSG(d.comparable && !d.hasUpdate, "版本相同要能比，且明确说没有更新");
    }
    {
        // 清单比当前旧，但同一代（4.3.29）⇒ 正常，属于「当前更新」
        update_check::WindowsSection older;
        older.present = true;
        older.version = "4.3.29";
        auto d = update_check::DecideWindowsUpdate(older, "4.3.30");
        CHECK(d.comparable && !d.hasUpdate);
    }

    std::cout << "--- 6. 版本号解析与比较的边界 ---\n";
    {
        CHECK(update_check::ParseVersion("4.3.30") == std::vector<int>({4, 3, 30}));
        CHECK(update_check::ParseVersion("V4.3.30") == std::vector<int>({4, 3, 30}));
        CHECK_MSG(update_check::ParseVersion("").empty(), "空串必须解析不出版本号");
        CHECK_MSG(update_check::ParseVersion("abc").empty(), "纯字母必须解析不出版本号");
        CHECK_MSG(update_check::ParseVersion("4.").empty(), "\"4.\" 这种残缺版本号要判废");
        CHECK_MSG(update_check::ParseVersion("4..3").empty(), "\"4..3\" 要判废");
        CHECK(update_check::CompareVersionVectors({4, 3}, {4, 3, 1}) < 0);
        CHECK(update_check::CompareVersionVectors({4, 3, 1}, {4, 3}) > 0);
        CHECK(update_check::CompareVersionVectors({4, 3, 30}, {4, 3, 30}) == 0);
    }

    return dsh_test::Finish("update_check_tests");
}
