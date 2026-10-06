# 哔哩听视频

**让听视频更方便。**

B站毕竟是一个视频网站，只想听的时候，常常还要点击很多东西。
哔哩听视频为收听而做，希望你打开软件，就能更快听到自己想听的内容：
音乐、播客、有声书、课程，或者一段想放在耳边的视频。

作者：**金色王冠（Golden Crown）**

项目名：`bililisten`

定位：**个人兴趣项目，免费分享，不承诺持续更新。**

## 下载与交流

Android **8.0 及以上**，当前版本 **0.10.13-beta.1**，仍处于内测阶段。
安装包通过本仓库的 [Releases](https://github.com/JinCrown/bililisten/releases) 页面提供；请从本仓库下载，不要购买付费包
或来历不明的修改版。不同手机的通知样式、后台运行和桌面小组件可能有所差异。

QQ 群：**1126192808**。欢迎交流使用体验、反馈 Bug 和提出好建议。
也欢迎通过 [Issues](https://github.com/JinCrown/bililisten/issues) 和 Pull Requests 参与，详见 [参与说明](CONTRIBUTING.md)。

## 可以做什么

- 视频音频播放、分 P / 合集队列、倍速、后台及锁屏播放。
- 搜索视频与 UP 主，查看排行榜、收藏和最近收听记录。
- 音乐推荐默认开启：随机推荐 200 万播放以上的音乐视频，滑动时分批加载，
  同时推荐音乐热榜创作者。关闭后使用账号的首页推荐来源，并推荐其中的创作者。
- 收藏与取消收藏、音频缓存和下载、桌面播放条 / 唱片 / 封面小组件。
- 查看账号私信和通知、查看字幕及同步显示；精度受原字幕时间信息限制，
  不承诺每个视频都有逐字歌词。
- 浅色 / 深色外观、本地历史、数据导出导入和局域网数据转移。

部分功能需要登录。功能是否可用还取决于账号权限、内容本身和平台接口。

## 界面预览

以下是示例数据下的设置页，不包含真实账号信息。

<p>
  <img src="docs/public/images/settings-light.png" alt="浅色设置页" width="240">
  <img src="docs/public/images/settings-dark.png" alt="深色设置页" width="240">
</p>

## 数据与平台

本项目**不是哔哩哔哩官方客户端，也未获得哔哩哔哩官方授权**，不提供、
不售卖视频、音乐或其他内容的版权。平台接口可能变动或限制访问，因此
不能保证所有功能一直可用，也不提供绕过登录、付费权限或平台验证的功能。

本地收听历史正常保存；**实时同步 B站最近记录默认关闭**，需要自己开启。
登录、播放和账号操作仍会与平台服务通信。详见 [隐私与本地数据](docs/public/PRIVACY.md)。

## 非商业源码许可

允许免费使用、学习、修改和分享，**禁止商用、收费和牟利**，包括售卖安装包、
付费会员、广告、推广返佣等。修改版也要保留许可及来源说明。
完整条款以 [LICENSE](LICENSE) 为准。

这是**非商业源码公开项目（source-available）**，不是 OSI 定义的自由开源项目。
上述限制只适用于本项目有权许可的原创部分，不能覆盖第三方组件的原许可。
SoundTouch 等第三方组件及资源说明见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES)。
公开源码不代表平台、商标或内容权利人给予了使用授权。

## 自己构建

原生 Android：Kotlin、Jetpack Compose、Media3、Room；网络与业务代码在
`shared` 模块。当前只提供 Android 版本，没有已经实现的 iOS 或鸿蒙客户端。

需要 JDK **17+**、Android SDK **36.1**、Build Tools **36.1.0** 和
NDK **28.2.13676358**。使用仓库自带的 Gradle Wrapper，不需要作者的签名私钥。

在 Android Studio 打开项目并配置 SDK；或设置 `ANDROID_HOME` 指向 SDK 目录。
Windows PowerShell 示例：

```powershell
.\scripts\build.ps1 -JdkHome '<你的 JDK 目录>' -Mode Debug
.\scripts\build.ps1 -JdkHome '<你的 JDK 目录>' -Mode Check
.\scripts\audit-public.ps1
```

`Check` 包含接口目录检查、架构检查、主机单元测试、Lint 和 Debug APK 构建，
不会自动连接手机或使用真实账号执行平台操作。

Linux / macOS 也可使用 Gradle：

```bash
bash gradlew checkArchitecture :shared:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
公开安装包使用作者独立的签名；自行构建的包可能无法覆盖它，换签名前请先
导出希望保留的本地数据。不要为了覆盖安装而共享任何签名私钥。

`third_party/soundtouch` 包含 LGPL 组件完整源码，构建说明见
[SoundTouch 来源与替换方式](third_party/soundtouch/UPSTREAM.md)。
内部 UI 草图、实测日志、设备截图和签名文件不随源码发布。

## 已知边界

平台风险验证、无音频轨的内容、失效或权限不足的内容可能无法播放。
歌词效果取决于字幕来源；通知栏封面展示由 Android 和手机系统共同决定。
内测已做部分设备验证，仍欢迎更多机型的反馈，不代表所有机型都已验证。
