# 公共 CI、签名与发布边界

## 当前流程

只有 Android CI 工作流，PR、main 推送和手动运行会执行：

1. 固定 actionlint 校验工作流；Python 流程测试。
2. 协议/应用 JVM 单测、debug lint、debug APK。
3. 编译手机 UI 仪器测试 APK，不执行模拟器。
4. 上传报告和 debug APK，保留 7 天。

稳定检查名称为 **Tests, lint and debug APK**，来源 GitHub Actions。

**没有正式签名 job、自动 tag、GitHub Release 或商店上传。** 不带其他仓库的 Secrets、密钥配置或一次性发布规则。debug 包使用 runner 临时签名，不能充当稳定更新包。

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

## 未来正式签名与发布

以下是维护者准备清单，不是已启用功能：

1. 明确应用 ID、版本和升级策略，递增 versionCode，确认 versionName 与计划 tag 一致。
2. 在自己控制的安全设备上生成或使用长期 keystore，离线备份并验证恢复。密码和私钥不得进入源码、Issues、PR、聊天或日志。
3. 若采用 CI 签名，由维护者亲自在 GitHub 官方设置中配置最小 Secrets，并审核隔离签名、临时密钥清理、无私钥缓存/产物的流程。不索取他人的密钥，不创建广权限 token 绕过限制。
4. 验证 APK 实际版本、包名、非 debuggable、签名、固定证书指纹/有效期、哈希及来源。
5. 完成手机/Apple TV 真机验收，明确已知限制和旧 debug 签名兼容风险。
6. 审核精确 tag 和资产后人工发布，包含校验和、公开构建元数据和第三方声明；不覆盖旧版本资产。
7. 后续版本持续使用同一签名。丢失/更换密钥可能影响既有用户升级，不在普通构建中重建。

当前 pipeline.py release 只是本地未签名候选验证，不读 Secrets、不创建 tag、不发布，不能替代签名流程。

## 安全与成本

- 全工作流只有 contents:read，checkout 不持久保留凭据。
- 不使用 pull_request_target 或特权 workflow_run 执行不可信 PR。
- Actions 固定完整 SHA；Gradle basic cache 的 PR 路径只读，仅 main push 写。
- 没启用 enhanced 商业缓存、Build Scan、依赖提交或签名密钥上传。
- 同 PR/ref 新运行取消旧 CI；分钟和产物存储受现有计划限额约束，不购买额外容量。
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
