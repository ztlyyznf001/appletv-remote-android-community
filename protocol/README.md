# Companion 互操作模块

阻塞式 Kotlin/JVM 模块，应用使用单一后台工作线程串行调用。PIN 只在当前 UI；配对身份包含秘密，必须加密持久化，应用使用 Android Keystore。

## 实现范围

OPACK/TLV8 编解码、HAP SRP-3072/SHA-512 配对和服务端 proof、Ed25519 身份验证、Pair Verify（X25519/Ed25519）、ChaCha20-Poly1305、认证帧头和独立方向计数。

支持会话启动、方向/选择/menu/home/play-pause 按下/释放、SELECT 长按显式释放、1000×1000 `_hidT` 触摸事件、键盘焦点快照/通知、RTI Unicode 追加/替换/清空。请求按 XID 匹配，跳过无关事件并限制总响应时间；认证或命令失败关闭连接。发现广播不是可信身份，只有成功配对后才保存并在重连验证。

Siri、音量/CEC、开关机、元数据、键盘 Return/提交、后台重连、多控制器和完整会话停止尚未实现；未请求 attention state，省略 TVRCSessionStart。真实兼容性待 [设备验收](../docs/DEVICE-TESTING.md)。

## 来源和许可

参考 [pyatv](https://github.com/postlund/pyatv) 固定提交 `b277a4c8222ecdcbaab8a24e3e713ca44765adb4`：Companion api/auth/connection/protocol、hap_srp、opack、keyed_archiver，以及 [rti_text_operations.py](https://github.com/postlund/pyatv/blob/b277a4c8222ecdcbaab8a24e3e713ca44765adb4/pyatv/protocols/companion/plist_payloads/rti_text_operations.py)。

这是参考开源互操作实现的独立 Kotlin 实现，RTI archive 布局含改编内容，保留 [Pierre Ståhl 的 MIT 声明](LICENSE-pyatv.txt)。未打包整个 pyatv Python 运行时，不声称 Apple 官方授权。密码原语由 Bouncy Castle 提供。

公开快照不包含 Apple 系统框架、原始反编译伪代码或真实抓包。研究工具开源许可不等于被分析第三方内容的再分发许可。

## 触摸与输入

`touch(x,y,phase)` 使用 BEGIN/MOVE/END 对应 1/3/4、单调纳秒时间和受限坐标；事件有递增事务 ID，无需回复。

`keyboardState()` 无编辑器时返回 null；`sendText(text,replace=false)` 无编辑器时返回 false，replace=true 先清空后插入，空替换即清空。无监控时刷新 `_tiStop`/`_tiStart`，有监控时使用最新会话 UUID，避免每次键入伪造 stop/start。UI 绑定目标 editor ID 并拒绝已变目标，但焦点仍可能在检查后变化。发送成功不代表电视显示确认。

二进制 plist 支持数据、UTF-16、日期、集合和 UID，有大小/对象数/深度限制；类名仅为数据，不实例化类。这是 RTI 子集，不是通用 Foundation 反序列化。

## 焦点监控

`startKeyboardMonitoring` 发送 `_tiStart`，返回初始 ID/null；`_tiStarted`/`_tiStopped` 更新状态，初始化时交错的新通知优先。前台连接中，每 200ms 在同一个串行 worker 上 pollEvents，每批最多 16 帧，保持边界和计数。部分帧可等到传输超时，异常关闭连接。可选监控遭拒绝不阻断基础按键。断开/后台取消轮询，重连重新初始化，不是后台服务。

## 合成夹具和测试

src/test/resources 下：

- rti-session.bplist：Python plistlib 合成，含中文/emoji/重音字符和合成会话。
- rti-insert-pyatv.bplist、rti-clear-pyatv.bplist：固定上游函数用合成 UUID/文字生成，检查解码布局等价性，沿用 pyatv 许可。

它们不是用户设备抓包。测试还包含 RFC 8032/8439 向量、篡改拒绝、独立 SRP server、编码边界、loopback 配对→验证→会话→按下/释放，以及错误身份/签名、碎片帧、无关 XID、总超时、RTI clear-before-insert、编辑器切换、焦点交错和拒绝监控后继续遥控。

这些验证软件机制，不等于手机/电视兼容认证。
