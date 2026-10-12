# 第三方声明

根 MIT 只适用于项目原创代码与文档；第三方软件、改编内容和上游声明继续遵循各自许可，不被改标为本项目 MIT。本快照不分发 Apple 系统二进制、原始反编译材料或真实设备抓包。

## 协议参考与改编

pyatv 固定提交 `b277a4c8222ecdcbaab8a24e3e713ca44765adb4`，MIT，Copyright (c) 2020 Pierre Ståhl。协议字段/算法用于互操作参考；RTI archive 布局及两份合成 bplist 夹具有改编。保留 [完整许可](protocol/LICENSE-pyatv.txt) 和 [来源说明](protocol/README.md)，没有打包 pyatv Python 运行时。

## 应用运行时

当前 release 解析模型包含 57 项第三方打包坐标（不将 BOM/common 元数据误算作独立运行库）。Bouncy Castle bcprov-jdk18on 1.80 为 MIT，其余为 Apache-2.0。完整坐标与官方来源见 [运行时清单](app/src/main/assets/licenses/THIRD-PARTY.txt)。

- [Bouncy Castle 许可原文](app/src/main/assets/licenses/BouncyCastle-1.80.txt)：来自 [r1rv80/LICENSE.html](https://github.com/bcgit/bc-java/blob/r1rv80/LICENSE.html)，仅规范 HTML/空白排版，版权与全部授权条件保留。
- [Apache-2.0 全文](app/src/main/assets/licenses/Apache-2.0.txt)：来自 AndroidX activity 1.10.0 AAR 随附文本，仅规范行尾和末尾空白。
- Kotlin stdlib、coroutines、JetBrains annotations、Guava listenablefuture 与 AndroidX 的准确坐标和官方上游在清单中列出。

这些文本放在主 APK 的 assets/licenses 内；应用内暂未提供浏览页面。不要把构建工具的整套许可证混作 Android 运行时，也不凭空声称上游具有未见到的 NOTICE 文件。

## 调试和测试

Debug 主 APK 另外使用四项 AndroidX Compose 1.7.6 组件，均 Apache-2.0，见 [debug 清单](app/src/debug/assets/licenses/DEBUG-DEPENDENCIES.txt)。

JUnit 4.13.2（EPL-1.0）和 Hamcrest 1.3（BSD-3-Clause）仅用于测试，不是 release 运行时：

- [JUnit 完整许可](app/src/androidTest/assets/licenses/JUnit-4.13.2-EPL-1.0.txt)；[固定版本源码](https://github.com/junit-team/junit4/tree/r4.13.2)。
- [Hamcrest 完整许可](app/src/androidTest/assets/licenses/Hamcrest-1.3-BSD.txt)；[固定版本源码](https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3)。
- AndroidX Test/Compose UI Test、kotlinx-coroutines-test、javawriter、javax.inject、jsr305 等测试辅助依赖为 Apache-2.0，完整文本随测试 APK。来源：[AndroidX Test](https://github.com/android/android-test)、[AndroidX](https://github.com/androidx/androidx)、[coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.7.3)、[javawriter](https://github.com/square/javawriter)、[javax.inject](https://github.com/javax-inject/javax-inject)、[jsr305](https://code.google.com/archive/p/jsr-305/)。

JUnit/Hamcrest 使用未经修改的官方依赖；上面的固定 tag 提供源码。测试 APK 的许可文件在 app/src/androidTest/assets/licenses，单元测试不产生用户安装包。

## 构建与 CI 工具

这些工具用于构建，不作为完整工具发行打包进 APK：

- Gradle Wrapper 8.14、Android Gradle Plugin 8.11.1、Kotlin 插件 2.0.21：Apache-2.0。保留 gradlew/gradlew.bat 的上游头和 wrapper JAR 内许可。[Gradle](https://github.com/gradle/gradle/blob/v8.14.0/LICENSE)、[Kotlin](https://github.com/JetBrains/kotlin/blob/v2.0.21/license/LICENSE.txt)。
- actionlint 1.7.12：MIT，Copyright (c) 2021 rhysd；脚本从官方发行下载并校验，未在源码中捆绑可执行文件。[许可](https://github.com/rhysd/actionlint/blob/v1.7.12/LICENSE.txt)。
- 官方 GitHub Actions checkout/setup-java/upload-artifact：MIT，引用固定 SHA，不打包到应用。[Actions](https://github.com/actions)。
- gradle/actions 的 basic runner/cache 部分为 MIT；本项目明确选择 basic，不使用另行授权的 enhanced caching。[许可](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/LICENSE)、[分发说明](https://github.com/gradle/actions/blob/3f5f9adaf7d9fecd50b5935e54106014257a94e6/DISTRIBUTION.md)。

增加依赖或更新版本时，重新检查 POM、上游许可和实际打包结果，同步这些声明。此清单不是对所有第三方权利或互操作兼容性的法律保证。
