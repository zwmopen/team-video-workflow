// 桌面快捷方式 —— 把 scripts\create-desktop-shortcuts.py 收进客户端。
//
// 背景（2026-09-27 电脑客户端升级）：
//   scripts\ 下的 online_gallery.cmd / -status / -autostart 三个入口本来要靠
//   create-desktop-shortcuts.py 手动在桌面生成 .lnk，而那个脚本又依赖 pywin32。
//   用户装了客户端之后，这三件事仍然「只有知道脚本的人才会用」。
//
//   客户端里直接用 IShellLink COM 生成，不依赖 Python、不依赖 pywin32，
//   一键就有三个桌面图标。
//
// 设计约束：
//   1. 纯逻辑（spec 拼装）与系统操作（COM）分开，前者可在 CI 上单测。
//   2. 文件名与描述一律不含 emoji：旧版 WScript.Shell 走 ANSI 通道，
//      emoji 会让 .lnk 生成失败或者变成问号（DSH-110 踩过）。
//   3. 只创建，不删除：删图标是用户在桌面上的操作，替他删属于越界。
#pragma once

#include <string>
#include <vector>

namespace desktop_shortcut {

struct ShortcutSpec {
    std::wstring fileName;    // 不含 .lnk 后缀
    std::wstring target;      // 完整路径（.cmd）
    int iconIndex = 0;
    std::wstring description;
    int windowStyle = 1;      // SW_SHOWNORMAL=1 / SW_SHOWMINNOACTIVE=7
};

// 三个入口。顺序固定：一键启动 / 看状态 / 管开机自启。
std::vector<ShortcutSpec> DefaultSpecs(const std::wstring& scriptsDir);

// 文本里有没有代理对（emoji 都落在 0xD800~0xDFFF 这一段）。
// ⛔ 图标名和描述里绝不能有：WScript.Shell 那套走 ANSI 通道，emoji 会让 .lnk
//    要么生成失败、要么显示成一串问号，而且全程不报错（DSH-110 踩过）。
bool HasSurrogatePair(const std::wstring& text);

// ---------------- 系统操作：只在真机跑 ----------------

struct Outcome {
    int created = 0;
    int failed = 0;
    std::wstring firstError;   // 第一条失败原因（人话）
    std::wstring detail;       // 全部结果，一行一个
};

// 在 desktop 目录下按 specs 生成 .lnk。desktop 为空时自动取当前用户桌面。
Outcome Create(const std::vector<ShortcutSpec>& specs, const std::wstring& scriptsDir,
               const std::wstring& desktop);

}  // namespace desktop_shortcut
