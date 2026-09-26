#include "desktop_shortcut.h"

#include <windows.h>

#include <objbase.h>
#include <shlobj.h>
#include <shobjidl.h>

#include <filesystem>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "uuid.lib")

namespace desktop_shortcut {

bool HasSurrogatePair(const std::wstring& text) {
    for (wchar_t c : text) {
        // 0xD800~0xDFFF 是代理对（emoji 都落在这一段）；其余 BMP 字符都安全
        const unsigned code = static_cast<unsigned short>(c);
        if (code >= 0xD800 && code <= 0xDFFF) return true;
    }
    return false;
}

namespace {

std::wstring Join(const std::wstring& dir, const std::wstring& name) {
    if (dir.empty()) return name;
    if (dir.back() == L'\\' || dir.back() == L'/') return dir + name;
    return dir + L"\\" + name;
}

}  // namespace

std::vector<ShortcutSpec> DefaultSpecs(const std::wstring& scriptsDir) {
    std::vector<ShortcutSpec> specs;
    // 「启动」那个要最小化窗口（SW_SHOWMINNOACTIVE=7）：它拉起的是一个后台服务，
    // 弹出黑框会让用户以为程序崩了，顺手就给关掉 —— 服务也就没了。
    specs.push_back({L"在线相册", Join(scriptsDir, L"online_gallery.cmd"), 13,
                     L"启动电脑端在线相册服务（手机看电脑相册靠它）", 7});
    specs.push_back({L"在线相册-状态", Join(scriptsDir, L"online_gallery-status.cmd"), 21,
                     L"查看在线相册服务状态：IP / 总作品数 / 文件监听", 1});
    specs.push_back({L"在线相册-开机自启", Join(scriptsDir, L"online_gallery-autostart.cmd"), 166,
                     L"注册或卸载在线相册服务的开机自启", 1});
    return specs;
}

Outcome Create(const std::vector<ShortcutSpec>& specs, const std::wstring& scriptsDir,
               const std::wstring& desktop) {
    Outcome outcome;
    if (specs.empty()) {
        outcome.firstError = L"没有要创建的快捷方式。";
        return outcome;
    }

    std::wstring targetDir = desktop;
    if (targetDir.empty()) {
        wchar_t* path = nullptr;
        // 桌面目录是「当前用户桌面」而不是「公共桌面」：这台机器就一个用户用，
        // 写到 Public 反而要管理员权限，还会弹 UAC。
        if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Desktop, 0, nullptr, &path)) && path) {
            targetDir = path;
            CoTaskMemFree(path);
        }
    }
    if (targetDir.empty()) {
        outcome.firstError = L"取不到桌面目录。";
        return outcome;
    }

    // COM 初始化：S_FALSE 表示本线程已经初始化过了，那也算成功，只是别去 Uninitialize
    HRESULT init = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool uninit = SUCCEEDED(init);
    // RPC_E_CHANGED_MODE = 别人已经用别的线程模型初始化过了。这时照样能创建
    // IShellLink（它是 in-proc 且线程安全），所以只在真正失败时才退出。
    if (FAILED(init) && init != RPC_E_CHANGED_MODE) {
        outcome.firstError = L"COM 初始化失败。";
        return outcome;
    }

    IShellLinkW* link = nullptr;
    HRESULT created = CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
                                       IID_PPV_ARGS(&link));
    if (FAILED(created) || !link) {
        if (link) link->Release();
        if (uninit) CoUninitialize();
        outcome.firstError = L"无法创建快捷方式组件。";
        return outcome;
    }

    for (const auto& spec : specs) {
        // 含 emoji 的名字建出来的 .lnk 要么失败、要么显示成问号，而且不报错。
        // 与其给用户一个坏图标，不如当场跳过并说清楚。
        if (HasSurrogatePair(spec.fileName) || HasSurrogatePair(spec.description)) {
            ++outcome.failed;
            if (outcome.firstError.empty()) {
                outcome.firstError = L"「" + spec.fileName +
                                     L"」的名字或描述里有 emoji，快捷方式会生成失败或显示成问号。";
            }
            if (!outcome.detail.empty()) outcome.detail += L"\r\n";
            outcome.detail += spec.fileName + L"：已跳过（含 emoji）";
            continue;
        }
        std::wstring full = Join(targetDir, spec.fileName + L".lnk");
        std::wstring note = spec.fileName;
        bool ok = true;

        link->SetPath(spec.target.c_str());
        link->SetWorkingDirectory(scriptsDir.c_str());
        link->SetIconLocation(L"%WINDIR%\\system32\\shell32.dll", spec.iconIndex);
        link->SetDescription(spec.description.c_str());
        link->SetShowCmd(spec.windowStyle);

        IPersistFile* file = nullptr;
        if (FAILED(link->QueryInterface(IID_PPV_ARGS(&file))) || !file) {
            note += L"：无法写入（组件不支持保存）";
            ok = false;
        } else {
            HRESULT saved = file->Save(full.c_str(), TRUE);
            file->Release();
            if (FAILED(saved)) {
                note += L"：写入失败";
                ok = false;
            }
        }

        if (ok) {
            ++outcome.created;
            note += L"：已创建";
        } else {
            ++outcome.failed;
            if (outcome.firstError.empty()) {
                outcome.firstError = L"「" + spec.fileName + L"」创建失败。";
            }
        }
        // 目标 .cmd 可能已经不在了（比如换了目录布局）。.lnk 照样建得出来，
        // 但双击会失败 —— 这种「静默失效」必须提前说，不能等用户来问。
        if (ok && !spec.target.empty() &&
            !std::filesystem::exists(std::filesystem::path(spec.target))) {
            note += L"（⚠ 目标文件不在，双击会失败）";
        }
        if (!outcome.detail.empty()) outcome.detail += L"\r\n";
        outcome.detail += note;
    }

    link->Release();
    if (uninit) CoUninitialize();
    return outcome;
}

}  // namespace desktop_shortcut
