# 客厅遥控 · Android Apple TV Remote

用 Android 手机控制同一局域网里的 Apple TV。基于 Kotlin 和 Jetpack Compose，提供大触摸板、常用按键、手机键盘输入和已配对设备的自动恢复连接。

这是独立、非官方项目，与 Apple 没有隶属或授权关系。协议兼容性参考 MIT 许可的 [pyatv](https://github.com/postlund/pyatv)，保留其版权与来源说明。

## 下载

### 正式签名 APK（推荐）

**[下载 v0.2.3 正式 APK](https://github.com/ztlyyznf001/appletv-remote-android-community/releases/download/v0.2.3/apple-tv-remote-0.2.3-release.apk)** · [发布说明与全部文件](https://github.com/ztlyyznf001/appletv-remote-android-community/releases/tag/v0.2.3)

适用于 Android 8.0 及以上；应用 ID 为 `info.remote.appletv`，版本 `0.2.3` / code `8`。直接下载 APK 后安装，无需解压。Release 同时提供 `SHA256SUMS`、公开源码与签名元数据 `build-metadata.json`、第三方许可包 `licenses-0.2.3.zip`。正式 Release 资产不受 CI 产物的 7 天保留期限制。

**升级提醒：** 覆盖更新需要相同签名。不同签名的本地/CI debug 包不能直接被正式包覆盖；不要为解决冲突贸然卸载或清除应用数据，这会丢失配对信息。当前没有配对导入/导出或跨签名迁移功能。新触摸、长按、自动输入和设备兼容性仍需真机验收，详见下方“限制和验证”。

### 开发测试 APK

1. 登录 GitHub，打开 [Android CI 构建列表](https://github.com/ztlyyznf001/appletv-remote-android-community/actions/workflows/android-ci.yml)。
2. 选择 **main** 分支最近一次成功且产物未过期的运行，在 **Artifacts** 区域下载 `android-debug-<run id>-<attempt>` ZIP。
3. 解压取得 `app-debug.apk`。`android-reports-...` 是测试报告，不是安装包。

本仓库的 CI 测试产物需要登录 GitHub 后下载，保留 **7 天**，使用临时测试签名，不能当作正式更新包。到期或被删除后，可查看后续成功构建，或按 [开发指南](docs/DEVELOPMENT.md) 自行构建。

### 未签名候选不能直接安装

`Android unsigned release candidate` 工作流的 `android-release-candidate-...` 产物用于构建和来源校验，其中的 `app-release-unsigned.apk` **未签名，不能直接安装**。公开构建流程不会自动发布；正式签名在维护者私有环境完成并单独审核发布，密钥不会进入公开仓库。详见 [构建与签名边界](docs/CI-CD.md)。

## 快速开始

1. 从上方“下载”获取正式 APK，开发者也可按 [开发指南](docs/DEVELOPMENT.md) 本地构建 debug APK。
2. 在 Android 8.0 或以上手机安装；手机与 Apple TV 连接同一可互通局域网。
3. 唤醒电视，从顶部选择设备，点击“连接 / 配对”，输入电视显示的 4 位 PIN。
4. 搜不到时按 [安装指南](docs/INSTALL.md) 手动填写 IPv4 和 Companion 端口；端口不是固定值。

## 功能

- 大触摸板：滑动移动焦点、轻点选择、长按 SELECT 按下/释放；保留方向键。
- 屏幕适配：普通竖屏底部放返回、主屏幕、播放/暂停；设备和连接收在顶部。横屏使用侧栏，短屏和大字体可滚动。
- 局部反馈：按键动画和遵循系统设置的触感反馈；发送指令不让其他按键一起变暗闪动。
- 自动键盘：前台连接时检测电视输入框；手动收起后避免同一焦点反复弹出。需要电视应用提供相应键盘会话。
- 自动追加文字：已提交的中文、英文和 emoji 经 300 ms 防抖后追加；输入法候选/组字阶段不发送。修改已发文字时暂停同步，需确认后手动全量替换。
- 有限重连：回到应用时仅恢复上次成功配对的设备，每次进入前台最多两次，间隔 3 秒；主动断开不立即连回，不自动配对陌生设备。
- 配对保护：Android Keystore AES-GCM 加密身份，关闭 Android 备份，PIN 不持久化；应用不记录协议正文或输入文字。

## 限制和验证

Siri、音量、开关机、键盘 Return/提交、后台常驻重连尚未实现。自动发现使用 `_companion-link._tcp.`，可能看到其他 Apple 设备；访客网络、客户端隔离和组播限制可能阻断发现。

文字同步是追加已提交内容，不是完整双向输入法；删除/修改已发文字需手动确认替换。传输失败不盲目重发。协议发送成功不等于电视显示/执行成功，请以电视画面为准。

既有用户反馈为 Android 基础按键可用，HarmonyOS 7.0 卓易通手动连接可用、自动发现仍失败，不代表全部设备兼容。现有 62 项 JVM 单测；9 项手机 UI 仪器测试此前仅编译，尚未执行模拟器或手机/Apple TV 真机验收。新触摸、长按、自动输入和布局仍需 [设备验收](docs/DEVICE-TESTING.md)。

## 中文文档

- [安装、配对、升级与排错](docs/INSTALL.md)
- [开发、构建与测试](docs/DEVELOPMENT.md)
- [CI、签名与发布边界](docs/CI-CD.md)
- [协议实现与合成夹具来源](protocol/README.md)
- [贡献指南](CONTRIBUTING.md) · [安全报告](SECURITY.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

本快照仅带应用/协议源码、合成测试夹具、构建配置和文档。不带旧提交历史、个人环境路径、原始反编译输出、设备抓包、签名备份或 Secrets。

## 快速构建

准备 JDK 21、Android Platform 36、Build-Tools 35.0.0、Python 3，并设置 JAVA_HOME、ANDROID_HOME：

```sh
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/pipeline.py verify
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`。首次下载依赖需要联网。Windows 用 `gradlew.bat`，详见开发指南。

## 许可证

原创代码和文档采用 [MIT License](LICENSE)。第三方内容保留各自许可，不因本项目 MIT 而重新许可。pyatv 改编布局和夹具保留 [上游声明](protocol/LICENSE-pyatv.txt)；依赖与 APK 随附许可见 [第三方声明](THIRD_PARTY_NOTICES.md)。
