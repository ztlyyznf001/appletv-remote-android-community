# 公共 CI、签名与发布边界

## 当前流程

Android CI 工作流在 PR、main 推送和手动运行时执行：

1. 固定 actionlint 校验工作流；Python 流程测试。
2. 协议/应用 JVM 单测、debug lint、debug APK。
3. 编译手机 UI 仪器测试 APK，不执行模拟器。
4. 上传报告和 debug APK，保留 7 天。

稳定检查名称为 **Tests, lint and debug APK**，来源 GitHub Actions。

另有 **Android unsigned release candidate**，只允许从本公开仓库 main 手动运行，详见下节。

**公开仓库没有正式签名 job、自动 tag、GitHub Release 或商店上传。** 不带其他仓库的 Secrets 或密钥配置，不调用其他仓库。debug 包使用 runner 临时签名，不能充当稳定更新包。正式签名在维护者私有环境完成。

## 手动构建未签名 release 候选

1. 先通过 PR 审核和 main 的 Android CI。应用版本仍由源码中的 versionName/versionCode 决定，工作流不改写版本。
2. 打开 Actions → Android unsigned release candidate → Run workflow，分支选择 **main**。
3. 填写 `version_name`（例如 `0.2.2`，不带 v）和 `source_commit`（main 历史中已经审核的完整 40 位小写 SHA）。不接受短 SHA、分支名或 tag 代替提交。
4. 工作流先取 main 触发时的精确 SHA 作为控制脚本；输入只通过环境变量传入，并在任何源码切换前严格校验。确认触发提交仍属于抓取到的 main、目标提交是其祖先后，再单独 checkout 目标精确 SHA。控制脚本与待构建源码分开保存。
5. 运行控制脚本及目标源码的 Python 测试、工作流检查、62 项 JVM 单测、lintDebug/lintRelease、assembleDebug/assembleRelease 和手机 UI AndroidTest 编译。仪器测试仍只编译，不代表真机测试通过。
6. 从 APK 读取包名和版本，拒绝 debuggable、v1 签名文件、v2/v3 签名块或异常签名验证结果；复核构建前后 commit/tree 和干净工作区。版本与输入必须完全一致。
7. 成功后下载 `android-release-candidate-<run_id>-<run_attempt>`，通常需要登录 GitHub，保留 7 天。报告在单独的 `android-candidate-reports-<run_id>-<run_attempt>` 中。

候选 artifact 仅含以下三个文件：

- `app-release-unsigned.apk`：未签名、不能直接安装的 release APK。
- `SHA256SUMS`：`<64 位小写 SHA-256>  app-release-unsigned.apk`，行末换行。
- `release-metadata.json`：下列 schema 1 manifest。

| 字段 | 类型与约束 |
| --- | --- |
| schema | 整数 `1` |
| source_repository | 固定 `ztlyyznf001/appletv-remote-android-community` |
| source_commit | 本次构建 checkout 的完整 40 位小写 Git commit SHA |
| source_tree | 该 commit 的完整 40 位小写 Git tree SHA |
| version_name | APK 实际 versionName，与手动输入一致 |
| version_code | APK 实际 versionCode，整数 |
| application_id | 固定 `info.remote.appletv`，与 APK 一致 |
| apk_sha256 | 未签名 APK 的 64 位小写 SHA-256，与 SHA256SUMS 一致 |
| signing | 固定 `unsigned` |
| expected_tag | `v` 加 version_name，仅为预期名称，不创建 tag |

manifest 是可复核的构建声明，**不是签名、独立认证或正式发布批准**。维护者仍须独立核对成功运行所属仓库、工作流、main 来源、精确 commit/tree、版本、APK 内容和哈希，再在私有环境签名。不把公开仓库中的任意脚本或 manifest 当作可信签名指令。

## 本地检查

要求 JDK 21、Android Platform 36、Build-Tools 35.0.0 和 Python 3：

```sh
python3 -m unittest discover -s scripts/ci/tests -v
scripts/ci/lint-workflows.sh
python3 scripts/ci/pipeline.py verify
```

runner 为 ubuntu-24.04。SDK 缺少时明确失败，不静默接受 SDK 许可。Windows 命令见 [开发指南](DEVELOPMENT.md)。

## main 保护

建议仅对 main 设置：PR 合并必需；**Tests, lint and debug APK** 通过必需；禁止强推/删除；单人维护不强制另一人审批。

不匹配 tags，不要求并不存在的签名/发布检查。以 Settings 实际规则为准，文档本身不启用设置。GitHub Free 公开仓库支持分支保护，见 [官方说明](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)。

## 私有签名与人工发布边界

公开候选只负责构建和来源记录。以下是维护者在私有环境进行正式签名、人工批准发布时的检查清单；公开工作流不会执行这些操作：

1. 明确应用 ID、版本和升级策略，递增 versionCode，确认 versionName 与计划 tag 一致。
2. 在自己控制的安全设备上生成或使用长期 keystore，离线备份并验证恢复。密码和私钥不得进入源码、Issues、PR、聊天或日志。
3. 若采用私有 CI 签名，由维护者亲自在私有环境配置最小 Secrets，并审核隔离签名、临时密钥清理、无私钥缓存/产物的流程。公开仓库不配置这些 Secrets，不放私有仓库地址、个人邮箱或密钥备份位置。不索取他人的密钥，不创建广权限 token 绕过限制。
4. 验证 APK 实际版本、包名、非 debuggable、签名、固定证书指纹/有效期、哈希及来源。
5. 完成手机/Apple TV 真机验收，明确已知限制和旧 debug 签名兼容风险。
6. 审核精确 tag 和资产后人工发布，包含校验和、公开构建元数据和第三方声明；不覆盖旧版本资产。
7. 后续版本持续使用同一签名。丢失/更换密钥可能影响既有用户升级，不在普通构建中重建。

`pipeline.py release` 在本地与公开候选工作流中均只做未签名候选验证，不读 Secrets、不创建 tag、不发布，不能替代私有签名流程。

## 安全与成本

- 全工作流只有 contents:read，checkout 不持久保留凭据。
- 不使用 pull_request_target 或特权 workflow_run 执行不可信 PR。
- Actions 固定完整 SHA；Gradle basic cache 的 PR 和候选构建路径只读，仅 Android CI 的 main push 写。
- 没启用 enhanced 商业缓存、Build Scan、依赖提交或签名密钥上传。
- 同 PR/ref 新运行取消旧 CI；独立手动候选运行不互相取消。分钟和产物存储受现有计划限额约束，不购买额外容量。
- 新增日志前检查输入、配对身份和个人路径泄漏。

## Action 维护

| Action | 版本 | 固定提交 |
| --- | --- | --- |
| actions/checkout | v5 | fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 |
| actions/setup-java | v5 | b6effb05e454b25005698d916606bdc6ffcbf961 |
| gradle/actions | v6.4.0 | 3f5f9adaf7d9fecd50b5935e54106014257a94e6 |
| actions/upload-artifact | v7.0.2 | cf430e030ddbb5b0abf93d22962f4752f3646cd9 |

以上使用 Node.js 24。更新前核验官方源与许可，跑 actionlint、流程测试、实际 CI。wrapper 8.14 校验与 JAR 验证保持启用。

参考：[Actions 安全](https://docs.github.com/en/actions/reference/security/secure-use)、[Gradle setup](https://github.com/gradle/actions/tree/v6.4.0/setup-gradle)、[Android 签名](https://developer.android.com/studio/publish/app-signing)。
