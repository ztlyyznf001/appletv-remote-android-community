# 开发、构建与验证

## 环境

JDK 21（与 CI 一致，字节码目标 Java 17）、Android Platform 36、Build-Tools 35.0.0、Python 3、Git；连接设备还需 SDK Platform-Tools。

通过官方 Android Studio SDK Manager 安装组件，自行检查/接受适用许可。设置 JAVA_HOME、ANDROID_HOME 指向本机真实目录，不提交个人 local.properties。首次解析依赖需要联网。

Gradle Wrapper 8.14 的下载摘要已固定，无需系统 Gradle；AGP 8.11.1，Kotlin 2.0.21。

## 构建与单测

Linux/macOS：

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

debug APK：`app/build/outputs/apk/debug/app-debug.apk`。仪器测试包：`app/build/outputs/apk/androidTest/debug/`。

Linux/macOS 的 CI 同款命令：

```sh
python3 -m unittest discover -s scripts/ci/tests -v
scripts/ci/lint-workflows.sh
python3 scripts/ci/pipeline.py verify
```

工作流 linter 在 Linux x86_64 从官方发行下载并校验固定 actionlint；其他平台预装 actionlint 1.7.12。Windows 直接使用 gradlew.bat，现有 Python 编排器调用 Unix gradlew。

## 设备验证

确认手机/模拟器已在 Android Studio 或 adb devices 中出现：

```sh
./gradlew :app:connectedDebugAndroidTest
```

这执行手机 UI 测试，不等于 Apple TV 配对/互操作验收。当前有 62 项 JVM 单测、9 项手机 UI 仪器测试；后者此前仅编译，尚未实际设备验收。按 [设备清单](DEVICE-TESTING.md) 分别记录通过、失败、未运行，不把 loopback 写成真机结论。

## 未签名 release 构建

```sh
./gradlew :app:lintRelease :app:assembleRelease
```

产物 `app/build/outputs/apk/release/app-release-unsigned.apk` 未签名，不是正式交付安装包。此仓库不配置正式签名。

在已提交源码的 Git checkout 中，可执行：

```sh
python3 scripts/ci/pipeline.py release --tag v0.2.2
```

参数须匹配源码 versionName。该命令生成 build/release-candidate 中的未签名包、校验和与来源元数据，不读密钥、不创建 tag、不操作 GitHub。目录已存在时退出，先检查并移动旧产物。

## 许可资源

主 APK 的 `assets/licenses/` 随附项目 MIT、pyatv MIT、BC MIT、Apache-2.0 全文和组件表。应用内暂未提供许可浏览页面，可从源码或 APK ZIP 读取。

测试许可位于 `app/src/androidTest/assets/licenses/`，仅随仪器测试 APK。更新依赖时同步 [第三方声明](../THIRD_PARTY_NOTICES.md) 与资源，并检查实际打包内容。

## 目录

- app：Compose、生命周期、NSD、加密存储及状态机。
- protocol：编码、认证、加密会话、JVM 测试和合成 bplist。
- scripts/ci：检查脚本与流程测试。
- .github/workflows/android-ci.yml：无发布 Secrets 的 CI。
- docs：中文文档。

构建和测试不需要原始逆向输出或个人开发环境。不要记录输入正文、PIN、配对身份或敏感抓包。
