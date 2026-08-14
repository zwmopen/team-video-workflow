# 源码、构建与恢复说明

## 源码唯一真源

- 私有主仓库：`zwmopen/team-video-workflow`
- 默认分支：`main`
- 项目目录：`tools/device-share-hub`
- 三端源码：`windows-native/`、`android/`、`ios/`
- 自动构建：`.github/workflows/device-share-hub.yml`
- Android 公开更新仓库 `zwmopen/gallery-updates` 只是 APK 更新通道，不是源码仓库。
- V3.4 手机安装包发布：`zwmopen/gallery-updates` Releases；当前版本和对应源码提交见 `MAINTAINER_HANDOFF.md`。

每个正式版本都必须能从 Git 标签定位到源码，并在 Release 同时提供安装包、显式源码 ZIP、校验值和说明。不能只上传 EXE、APK 或 IPA。

## 云端构建

构建账号按 `zwmopen` 主账号 → `rpgzwm` 第一备用 → `idmzwm-sys` 第二备用切换。三套账号都只是构建额度和发布授权的备用，不形成三份源码真源；主仓库仍是 `zwmopen/team-video-workflow`。

推送涉及 `tools/device-share-hub/**` 的提交后，GitHub Actions 自动执行。main 推送只有在 Windows、Android、iPhone 三个构建任务都成功后，才会进入 Android 公共发布任务：

- Windows 11/10 x64：Visual Studio 2022 + CMake，生成原生便携 EXE；
- Android：JDK 17 + Gradle 9.4.1 + Android SDK 36，运行单元测试、Lint 并生成 APK；
- iPhone：macOS 15 + Xcode + XcodeGen，编译测试目标、真机 SDK 包并生成未预签名 IPA；
- Android 额外生成版本化 APK、`SHA256SUMS.txt` 和 `android-release-metadata.json`；
- 使用 `GALLERY_UPDATES_TOKEN` 更新 `zwmopen/gallery-updates` Release 和 `latest.json`；
- 仓库质量与密钥扫描。

因此“源码推送”和“安装包发布”是同一条流水线：源码提交进入 main，云端构建验证，验证成功后发布同一份 APK。没有版本号/版本码变化的源码提交不会凭空产生可升级版本；正式升级必须递增 Android `versionCode`。

CI 生成的 IPA 不含 Apple ID、证书、设备 UDID 或密码，安装时由 Sideloadly/AltStore 使用用户自己的免费 Apple ID 重新签名。

## 本地构建入口

本机使用统一预检脚本，不把“缓存目录里有旧产物”当成构建成功。当前电脑已确认有 Java 22 和 Gradle 9.4.1，但 Android SDK、CMake/MSVC 等条件可能缺失；缺失时脚本会明确停止，正式构建直接使用 GitHub Actions，不需要在本机补装完整工具链。

```powershell
cd D:\AICode\AI\repos\team-video-workflow
powershell -ExecutionPolicy Bypass -File .\tools\device-share-hub\scripts\build-local.ps1
```

只检查单个平台：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\device-share-hub\scripts\build-local.ps1 -AndroidOnly
powershell -ExecutionPolicy Bypass -File .\tools\device-share-hub\scripts\build-local.ps1 -WindowsOnly
```

### Windows

```powershell
cmake -S tools/device-share-hub/windows-native -B tools/device-share-hub/windows-native/build -G "Visual Studio 17 2022" -A x64
cmake --build tools/device-share-hub/windows-native/build --config Release
```

### Android

在 `tools/device-share-hub/android` 使用 JDK 17 和 Gradle 9.4.1：

```powershell
gradle :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

正式 APK 还必须用 Android build-tools 核对：包含
`android.permission.REQUEST_INSTALL_PACKAGES` 和 `android.permission.FOREGROUND_SERVICE_DATA_SYNC`，但不含特权 `INSTALL_PACKAGES` 或 `application-debuggable`。更新由应用自有 HTTPS 服务断点下载并校验，用户从应用通知进入系统安装器，最终安装确认仍由 Android 完成。

### iPhone

iPhone 本地编译需要 macOS、Xcode 和 XcodeGen；Windows 电脑直接使用云端 IPA：

```bash
cd tools/device-share-hub/ios
xcodegen generate
xcodebuild -project Album.xcodeproj -scheme Album -sdk iphoneos build
```

## 恢复顺序

1. 从私有主仓库克隆 `main`，或检出 Release 对应标签。
2. 先读项目 `README.md`、本文件、兼容矩阵和交接文档。
3. 使用 GitHub Actions 重建三端安装包；不要依赖本机遗留的旧 build 目录。
4. Android 必须保持 `com.zwm.gallery` 与原签名升级链；iPhone 保持 `com.zwm.album`；不能按手机型号另开分叉包。
5. 发布前核对源码提交、版本号、安装包、校验值、更新清单和 Release 一致。

本机仅保留工作源码和当前 Windows 便携版即可。旧 APK、旧 IPA、旧 EXE、CI 下载目录与本地 build 目录都属于可重建产物，可在确认云端发布完整后清理。

## 安全边界

源码和文档可以入库；Apple ID、密码、验证码、Token、Cookie、设备 UDID、配对记录、用户作品、诊断原始日志和个人绝对路径不得进入 Git 或 Release。诊断与案例只保存脱敏结论。
