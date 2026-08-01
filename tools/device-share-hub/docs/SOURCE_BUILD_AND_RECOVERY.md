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

构建账号按 `zwmopen` 主账号 → `rpgzwm` 第一备用 → `idmzwm-sys` 第二备用切换。主仓库是唯一源码真源；只有对应账号额度确实需要用于本次构建时，才把当次主仓库提交同步到备用私有仓库。备用仓库不承担日常镜像、额外备份或文档同步，不能单独开发或形成产品分叉；用户明确指定的其他用途除外。

推送涉及 `tools/device-share-hub/**` 的提交后，GitHub Actions 自动执行：

- Windows 11/10 x64：Visual Studio 2022 + CMake，生成原生便携 EXE；
- Android：JDK 17 + Gradle 9.4.1 + Android SDK 36，运行单元测试、Lint 并生成 APK；
- iPhone：macOS 15 + Xcode + XcodeGen，编译测试目标、真机 SDK 包并生成未预签名 IPA；
- 仓库质量与密钥扫描。

CI 生成的 IPA 不含 Apple ID、证书、设备 UDID 或密码，安装时由 Sideloadly/AltStore 使用用户自己的免费 Apple ID 重新签名。

## 本地构建入口

当前 Windows 电脑已经具备 Java 22、Android SDK 36、build-tools 35/36、ADB、Gradle 9.4.1 和现有 Android 签名文件；Android 可直接本机构建，不需要再安装模拟器或重复下载 SDK。C 盘空间紧张时不安装完整 Visual Studio，Windows 与 iPhone 优先使用云端构建，临时工具链必须放在 D 盘并在验收后清理。

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

正式 APK 还必须用 Android build-tools 核对：包含普通权限
`android.permission.REQUEST_INSTALL_PACKAGES`，不含特权 `INSTALL_PACKAGES` 或 `application-debuggable`；前者只允许在用户点击并经系统授权后打开安装器，不能静默安装。

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
