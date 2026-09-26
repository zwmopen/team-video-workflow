// DSH-123 桌面快捷方式判据（只测纯逻辑部分：spec 拼装与 emoji 检查）。

#include <iostream>
#include <string>
#include <vector>

#include "desktop_shortcut.h"
#include "test_check.h"

namespace {

void TestDefaultSpecs() {
    const std::wstring dir = L"D:\\repo\\tools\\device-share-hub\\scripts";
    auto specs = desktop_shortcut::DefaultSpecs(dir);
    CHECK_MSG(specs.size() == 3, "三个入口：启动 / 状态 / 开机自启");

    CHECK_MSG(specs[0].fileName == L"在线相册", "第一个是启动入口");
    CHECK_MSG(specs[0].target == dir + L"\\online_gallery.cmd", "第一个指向 online_gallery.cmd");
    CHECK_MSG(specs[0].windowStyle == 7,
              "启动入口必须最小化窗口：它拉起的是后台服务，弹黑框会被用户当成崩溃顺手关掉");

    CHECK_MSG(specs[1].fileName == L"在线相册-状态", "第二个是状态入口");
    CHECK_MSG(specs[1].target == dir + L"\\online_gallery-status.cmd", "第二个指向状态脚本");

    CHECK_MSG(specs[2].fileName == L"在线相册-开机自启", "第三个是自启入口");
    CHECK_MSG(specs[2].target == dir + L"\\online_gallery-autostart.cmd", "第三个指向自启脚本");

    // 三个图标必须不同：图标一样的话用户在桌面上分不清哪个是哪个
    CHECK_MSG(specs[0].iconIndex != specs[1].iconIndex, "前两个图标要不同");
    CHECK_MSG(specs[1].iconIndex != specs[2].iconIndex, "后两个图标要不同");
    CHECK_MSG(specs[0].iconIndex != specs[2].iconIndex, "首尾图标要不同");

    for (const auto& spec : specs) {
        CHECK_MSG(!spec.fileName.empty(), "文件名不能为空");
        CHECK_MSG(!spec.description.empty(), "描述不能为空（鼠标悬停时要看得到）");
        CHECK_MSG(spec.target.size() > dir.size(), "目标必须是 scripts 目录下的完整路径");
    }
}

void TestTrailingBackslash() {
    // 目录以反斜杠结尾时不能拼出双斜杠（CreateFile 能容忍，但 .lnk 里的路径会变丑，
    // 而且有些老程序认不出来）
    auto specs = desktop_shortcut::DefaultSpecs(L"D:\\repo\\scripts\\");
    CHECK_MSG(specs.size() == 3, "带尾斜杠时也要生成三个");
    for (const auto& spec : specs) {
        CHECK_MSG(spec.target.find(L"\\\\") == std::wstring::npos,
                  "目录带尾斜杠时目标路径不能出现双斜杠");
    }
    CHECK_MSG(specs[0].target == L"D:\\repo\\scripts\\online_gallery.cmd",
              "带尾斜杠的目录要拼出规范路径");
    CHECK_MSG(specs[2].target == L"D:\\repo\\scripts\\online_gallery-autostart.cmd",
              "第三个入口同样要拼对");
}

void TestEmojiGuard() {
    CHECK_MSG(!desktop_shortcut::HasSurrogatePair(L"在线相册"), "普通中文不该被判成 emoji");
    CHECK_MSG(!desktop_shortcut::HasSurrogatePair(L""), "空串不该被判成 emoji");
    CHECK_MSG(!desktop_shortcut::HasSurrogatePair(L"abc 123 -_()"), "ASCII 不该被判成 emoji");

    // U+1F4F1（手机）在 UTF-16 里是代理对 D83D+DCF1
    CHECK_MSG(desktop_shortcut::HasSurrogatePair(L"在线相册\U0001F4F1"), "emoji 必须被抓出来");
    // 高位代理后跟普通字符也是非法的（孤代理）
    CHECK_MSG(desktop_shortcut::HasSurrogatePair(std::wstring(1, static_cast<wchar_t>(0xD83D)) + L"a"),
              "孤立的代理项也要被抓出来");

    // 自带的三个 spec 必须一个都不含 emoji —— 这是 DSH-110 踩过的坑
    auto specs = desktop_shortcut::DefaultSpecs(L"D:\\repo\\scripts");
    for (const auto& spec : specs) {
        CHECK_MSG(!desktop_shortcut::HasSurrogatePair(spec.fileName), "默认图标名不能含 emoji");
        CHECK_MSG(!desktop_shortcut::HasSurrogatePair(spec.description), "默认描述不能含 emoji");
    }
}

void TestCreateRejectsEmptySpecs() {
    // 不传 desktop 就要去取当前用户桌面，CI 上取不到；这里只验证「空 spec」这条早退路径，
    // 不真的去建图标（避免在 CI 机器上留下垃圾）
    auto outcome = desktop_shortcut::Create({}, L"D:\\repo\\scripts", L"");
    CHECK_MSG(outcome.created == 0, "没有 spec 时一个都不该创建");
    CHECK_MSG(!outcome.firstError.empty(), "空 spec 要给出原因，不能静默返回成功");
}

}  // namespace

int main() {
    std::cout << "desktop_shortcut_tests\n";
    TestDefaultSpecs();
    TestTrailingBackslash();
    TestEmojiGuard();
    TestCreateRejectsEmptySpecs();
    return dsh_test::Finish("desktop_shortcut_tests");
}
