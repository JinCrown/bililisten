# 0.10.13-beta.1 版本材料

手机安装请前往 [发布页](https://github.com/JinCrown/bililisten/releases/tag/v0.10.13-beta.1)，
只需下载其中的 APK。这里保存该安装包对应的许可、校验信息与第三方组件源码，
不是需要另外安装的文件。

## 材料归档

以下文件从本版本发布附件原样迁移，内容与原附件一致。

| 材料 | 用途 |
| --- | --- |
| [原创部分许可](assets/LICENSE.txt) | 本版本随包提供的使用许可 |
| [第三方组件与资源说明](assets/THIRD_PARTY_NOTICES.txt) | 本版本随包提供的第三方说明 |
| [SHA-256 校验信息](assets/SHA256SUMS.txt) | 检查 APK 和归档材料是否完整 |
| [SoundTouch 完整对应源码与替换说明](https://raw.githubusercontent.com/JinCrown/bililisten/main/docs/public/releases/v0.10.13-beta.1/assets/bililisten-0.10.13-beta.1-soundtouch-source.zip) | 免费下载本版本倍速组件的完整源码、上游源码包、JNI 适配与构建替换文件 |

SoundTouch 源码包遵循组件原有许可，不受本项目原创代码非商业许可限制。
更多构建与替换说明见 [SoundTouch 来源](../../../../third_party/soundtouch/UPSTREAM.md)。

## 安装包校验

文件：`bililisten-0.10.13-beta.1.apk`

SHA-256：

```text
3e75447b4dd4242448a17b206921f4ee95088f7a933dbd1be2ccf265d8685bd0
```

Windows PowerShell 可以使用 `Get-FileHash -Algorithm SHA256 <APK 文件路径>` 核对。
普通安装不需要执行这一步。
