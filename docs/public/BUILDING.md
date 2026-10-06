# 自己构建哔哩听视频

原生 Android：Kotlin、Jetpack Compose、Media3、Room；网络与业务代码在
`shared` 模块。当前只提供 Android 版本。

## 环境

- JDK 17 或以上。
- Android SDK 36.1 与 Build Tools 36.1.0。
- Android NDK 28.2.13676358。
- 使用仓库自带的 Gradle Wrapper，不需要作者的签名私钥。

在 Android Studio 打开项目并配置 SDK，或设置 `ANDROID_HOME` 指向 SDK 目录。

## Windows

```powershell
.\scripts\build.ps1 -JdkHome '<你的 JDK 目录>' -Mode Debug
.\scripts\build.ps1 -JdkHome '<你的 JDK 目录>' -Mode Check
.\scripts\audit-public.ps1
```

`Check` 包含接口目录检查、架构检查、主机单元测试、Lint 和 Debug APK 构建，
不会自动连接手机或使用真实账号执行平台操作。

## Linux / macOS

```bash
./gradlew checkArchitecture :shared:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 签名与第三方源码

公开安装包使用作者独立的签名。自行构建的包可能无法覆盖它，换签名前
请先导出希望保留的本地数据，不要共享任何签名私钥。

`third_party/soundtouch` 包含 LGPL 组件完整源码，构建与替换说明见
[SoundTouch 来源](../../third_party/soundtouch/UPSTREAM.md)。本项目原创许可
不限制 LGPL 单独授予的修改、替换和调试权利。

内部 UI 草图、实测记录和签名文件不随源码发布。公开文件扫描不会代替
素材来源与第三方许可证的人工核查。
