package info.remote.appletv

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.appletvremote.protocol.CompanionClient
import info.appletvremote.protocol.CompanionCredentials
import info.appletvremote.protocol.RemoteButton
import info.appletvremote.protocol.TouchPhase
import info.appletvremote.protocol.CompanionCommandRejectedException
import info.appletvremote.protocol.KeyboardFocusChangedException
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var devices by mutableStateOf<List<TVDevice>>(emptyList())
    private var lastDevice by mutableStateOf<TVDevice?>(null)
    private lateinit var reconnect: ReconnectPolicy
    private var manualDevice by mutableStateOf<TVDevice?>(null)
    private var showManual by mutableStateOf(false)
    private var discoveryText by mutableStateOf("正在启动发现…")
    private var selectedId by mutableStateOf<String?>(null)
    private var status by mutableStateOf("选择设备，开始配对")
    private var busy by mutableStateOf(false)
    private var buttonInFlight by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private val keyboardFocus = KeyboardFocusPolicy()
    private val keyboardHandler = Handler(Looper.getMainLooper())
    private var keyboardDraftKey by mutableStateOf(0)
    private var showKeyboard by mutableStateOf(false)
    private var keyboardMessage by mutableStateOf("输入法选词完成后自动同步。")
    private val keyboardSync = KeyboardSyncPolicy()
    private var keyboardMonitoring = false
    private var keyboardAutomatic = true
    private var keyboardSending by mutableStateOf(false)
    private var syncRunnable: Runnable? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var recoveryDraft by mutableStateOf<String?>(null)
    private var touching by mutableStateOf(false)
    private var touchPump: TouchGesturePump? = null
    private var showPin by mutableStateOf(false)
    private var showDevices by mutableStateOf(false)
    private var connectionOptionsOpen = false
    private var keyboardOpening = false
    private val keyboardOpenRequests = SessionGate()
    private var client: CompanionClient? = null
    private var pairingDevice: TVDevice? = null
    private val sessionGate = SessionGate()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var discovery: DeviceDiscovery
    private lateinit var credentials: CredentialStore
    private val selected get() = devices.firstOrNull { it.id == selectedId } ?: manualDevice?.takeIf { it.id == selectedId } ?: lastDevice?.takeIf { it.id == selectedId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        credentials = CredentialStore(this)
        selectedId = getSharedPreferences("preferences", MODE_PRIVATE).getString("selected_device", null)
        val preferences = getSharedPreferences("preferences", MODE_PRIVATE)
        reconnect = ReconnectPolicy(preferences.getBoolean("resume_connection", true))
        val lastId = preferences.getString("last_device_id", null)
        val lastHost = preferences.getString("last_device_host", null)
        val lastPort = preferences.getInt("last_device_port", 0)
        if (lastId != null && !lastHost.isNullOrBlank() && lastPort in 1..65535) {
            lastDevice = TVDevice(lastId, preferences.getString("last_device_name", "Apple TV") ?: "Apple TV", lastHost, lastPort)
        }
        ManualEndpoint.parse(preferences.getString("manual_host", "") ?: "", preferences.getString("manual_port", "") ?: "")?.let {
            manualDevice = TVDevice(it.id, "Apple TV（手动）", it.host, it.port)
        }
        discovery = DeviceDiscovery(this) { found, message ->
            devices = found; discoveryText = message
            restoreConnection()
        }
        setContent {
            RemoteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val screenSession = sessionGate.current()
                    RemoteScreen(selected?.name ?: "选择 Apple TV", devices.size, discoveryText, status, connected, busy, touching, hasDevice = selected != null,
                        onSelect = { showDevices = true }, onConnect = { connectOrPair() }, onForget = {
                            disconnect(); selectedId?.let { credentials.remove(it) }; status = "配对身份已移除，可以重新配对"
                        }, onPress = { if (sessionGate.isCurrent(screenSession)) press(it) }, onKeyboard = { if (sessionGate.isCurrent(screenSession)) openKeyboard() },
                        onTouchBegin = { x, y -> sessionGate.isCurrent(screenSession) && beginTouch(x, y) },
                        onTouchMove = { x, y -> if (sessionGate.isCurrent(screenSession)) touchPump?.offer(GestureSample(x, y, GesturePhase.MOVE)) },
                        onTouchEnd = { x, y, tap -> if (sessionGate.isCurrent(screenSession)) touchPump?.offer(GestureSample(x, y, GesturePhase.END, tap)) },
                        buttonInFlight = buttonInFlight,
                        onTouchHold = { if (sessionGate.isCurrent(screenSession)) touchPump?.offer(GestureSample(0, 0, GesturePhase.HOLD)) },
                        onTouchCancel = { x, y -> if (sessionGate.isCurrent(screenSession)) touchPump?.offer(GestureSample(x, y, GesturePhase.END, cancelled = true)) },
                        onOptionsVisibility = { connectionOptionsOpen = it })
                    if (showKeyboard) {
                        val dialog = KeyboardDialogToken(sessionGate.current(), keyboardDraftKey)
                        fun isCurrentDialog() = dialog.matches(sessionGate.current(), keyboardDraftKey, showKeyboard)
                        key(dialog) {
                            KeyboardDialog(keyboardSending, keyboardMessage, onClose = {
                                if (isCurrentDialog()) closeKeyboard()
                            }, onSend = { _, replace, _ ->
                                if (isCurrentDialog()) sendNextText(replace = replace, manual = true)
                            }, onDraft = { text, composing, automatic ->
                                if (isCurrentDialog()) {
                                    keyboardAutomatic = automatic
                                    keyboardSync.edit(text, composing)
                                    if (keyboardSync.needsReplacement()) keyboardMessage = "已发送的文字被修改。自动同步已暂停；核对电视后可手动替换全部文字。"
                                    scheduleTextSync()
                                }
                            })
                        }
                    }
                    if (showDevices) RemoteDialog(onDismissRequest = { showDevices = false }, title = { Text("附近的设备") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${discoveryText}\nCompanion 广播也可能来自其他 Apple 设备，请核对名称。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { showDevices = false; showManual = true }) { Text("手动填写地址和端口") }
                            (devices + listOfNotNull(manualDevice, lastDevice)).distinctBy { it.id }.forEach { device -> TextButton(onClick = {
                                disconnect(); selectedId = device.id
                                getSharedPreferences("preferences", MODE_PRIVATE).edit().putString("selected_device", device.id).apply()
                                status = "已选择设备，尚未连接"; showDevices = false
                                connectOrPair()
                            }) { Column(Modifier.fillMaxWidth()) { Text(device.name); Text("${device.host}:${device.port}", fontSize = 12.sp) } } }
                        }
                    }, confirmButton = { TextButton(onClick = { discovery.stop(); discovery.start() }) { Text("重新发现") } }, dismissButton = { TextButton(onClick = { showDevices = false }) { Text("关闭") } })
                    if (showManual) ManualConnectionDialog(manualDevice, onCancel = { showManual = false }, onSave = { endpoint ->
                        disconnect()
                        val device = TVDevice(endpoint.id, "Apple TV（手动）", endpoint.host, endpoint.port)
                        manualDevice = device; selectedId = device.id
                        getSharedPreferences("preferences", MODE_PRIVATE).edit()
                            .putString("selected_device", device.id)
                            .putString("manual_host", device.host).putString("manual_port", device.port.toString()).apply()
                        showManual = false
                        connectOrPair()
                    })
                    recoveryDraft?.let { draft ->
                        RemoteDialog(onDismissRequest = { recoveryDraft = null }, title = { Text("同步已中断") }, text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("以下草稿不会自动重发，以免重复输入或写入另一输入框。可长按复制，重新连接后手动恢复。")
                                OutlinedTextField(value = draft, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(), maxLines = 6)
                            }
                        }, confirmButton = { TextButton(onClick = { recoveryDraft = null }) { Text("关闭并清除草稿") } })
                    }
                    if (showPin) PinDialog(onCancel = { disconnect(); status = "配对已取消" }, onSubmit = { finishPairing(it) }, busy = busy)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        reconnect.enterForeground()
        discovery.start()
        restoreConnection()
    }
    override fun onStop() {
        recoveryDraft = null
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnect.leaveForeground()
        discovery.stop()
        disconnect(preserveResume = true)
        super.onStop()
    }
    override fun onDestroy() { disconnect(preserveResume = true); worker.shutdown(); super.onDestroy() }

    private fun restoreConnection() {
        val last = lastDevice ?: return
        val target = devices.firstOrNull { it.id == last.id } ?: last
        if (reconnect.takeAttempt(hasDevice = credentials.contains(target.id),
                idle = !connected && !busy && !showPin && !showDevices && !showManual,
                now = SystemClock.elapsedRealtime())) {
            selectedId = target.id
            connectOrPair(automatic = true, target = target)
        }
    }
    private fun rememberConnection(device: TVDevice) {
        lastDevice = device
        reconnect.connected()
        getSharedPreferences("preferences", MODE_PRIVATE).edit()
            .putBoolean("resume_connection", true)
            .putString("last_device_id", device.id).putString("last_device_name", device.name)
            .putString("last_device_host", device.host).putInt("last_device_port", device.port).apply()
    }
    private fun disconnect(preserveResume: Boolean = false) {
        reconnectHandler.removeCallbacksAndMessages(null)
        if (!preserveResume) {
            reconnect.disconnectByUser()
            getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("resume_connection", false).apply()
        }
        sessionGate.invalidate(); connected = false; busy = false; buttonInFlight = false; showPin = false; pairingDevice = null
        keyboardOpenRequests.invalidate(); keyboardOpening = false
        keyboardHandler.removeCallbacksAndMessages(null); syncRunnable = null; keyboardSync.bind(null); keyboardSending = false; keyboardFocus.reset(); keyboardDraftKey++
        touchPump?.abort(); touchPump = null; touching = false; showKeyboard = false; keyboardMonitoring = false
        keyboardMessage = "输入法选词完成后自动同步。"
        val previous = client; client = null
        closeLater(previous)
        status = "未连接"
    }
    private fun closeLater(remote: CompanionClient?) {
        if (remote == null) return
        val close = Runnable { try { remote.finishInput() } catch (_: Exception) { } finally { remote.close() } }
        try { worker.execute(close) } catch (_: java.util.concurrent.RejectedExecutionException) {
            Thread(close, "remote-session-cleanup").start()
        }
    }
    private fun connectOrPair(automatic: Boolean = false, target: TVDevice? = selected) {
        val device = target ?: run { showDevices = true; return }
        if (connected) { disconnect(); return }
        if (busy) return
        reconnectHandler.removeCallbacksAndMessages(null)
        val current = sessionGate.invalidate()
        busy = true; status = if (automatic) "正在恢复连接…" else "正在建立安全连接…"
        worker.execute {
            var opened: CompanionClient? = null
            try {
                if (!sessionGate.isCurrent(current)) return@execute
                val remote = CompanionClient(device.host, device.port)
                opened = remote
                val saved = credentials.load(device.id)
                if (saved == null && automatic) {
                    remote.close()
                    runOnUiThread {
                        if (sessionGate.isCurrent(current)) { busy = false; status = "点击连接 / 配对以连接电视" }
                    }
                    return@execute
                }
                if (saved == null) remote.beginPairing() else remote.connect(CompanionCredentials.decode(saved))
                val monitoring = saved != null && monitorKeyboard(remote, current)
                runOnUiThread {
                    if (!sessionGate.isCurrent(current)) { closeLater(remote); return@runOnUiThread }
                    client = remote; busy = false
                    if (saved == null) { pairingDevice = device; showPin = true; status = "输入电视上显示的配对码" }
                    else {
                        connected = true; keyboardMonitoring = monitoring; rememberConnection(device); status = "已连接 · 加密会话已建立"
                        maybeShowKeyboard()
                        if (monitoring) scheduleKeyboardPoll(remote, current)
                    }
                }
            } catch (e: Exception) {
                runCatching { opened?.close() }
                fail(current, e, automatic)
            }
        }
    }
    private fun finishPairing(pin: String) {
        val remote = client ?: return
        val device = pairingDevice ?: return
        val current = sessionGate.current()
        busy = true; status = "正在验证配对码…"
        worker.execute {
            var opened: CompanionClient? = null
            try {
                if (!sessionGate.isCurrent(current)) return@execute
                val identity = remote.finishPairing(pin)
                if (!sessionGate.commit(current) { credentials.save(device.id, identity.encode()) }) {
                    remote.close(); return@execute
                }
                remote.close()
                val paired = CompanionClient(device.host, device.port)
                opened = paired
                paired.connect(identity)
                val monitoring = monitorKeyboard(paired, current)
                runOnUiThread {
                    if (!sessionGate.isCurrent(current)) { closeLater(paired); return@runOnUiThread }
                    client = paired; showPin = false; busy = false; connected = true; keyboardMonitoring = monitoring; rememberConnection(device); status = "已连接 · 配对身份已安全保存"
                    maybeShowKeyboard()
                    if (monitoring) scheduleKeyboardPoll(paired, current)
                }
            } catch (e: Exception) { runCatching { opened?.close() }; fail(current, e) }
        }
    }
    private fun maybeShowKeyboard() {
        if (connected && !busy && !touching && !showKeyboard && !showDevices && !showManual && !showPin && !connectionOptionsOpen && keyboardFocus.shouldOpen())
            openKeyboard()
    }
    private fun openKeyboard() {
        if (!connected || busy || touching || showKeyboard || keyboardOpening) return
        val editor = keyboardFocus.sessionId
        if (editor != null && keyboardMonitoring) {
            keyboardSync.bind(editor); keyboardAutomatic = true; keyboardDraftKey++
            keyboardMessage = "输入法选词完成后自动追加；电视已有文字会保留。"
            showKeyboard = true
            return
        }
        val remote = client ?: return
        val current = sessionGate.current()
        val request = keyboardOpenRequests.invalidate()
        keyboardOpening = true
        // Coalesce repeated taps; a dismissed or replaced query cannot reopen the old editor.
        worker.execute {
            try {
                if (!sessionGate.isCurrent(current) || !keyboardOpenRequests.isCurrent(request)) return@execute
                val snapshot = remote.keyboardState()
                runOnUiThread {
                    if (!sessionGate.isCurrent(current) || !keyboardOpenRequests.isCurrent(request)) return@runOnUiThread
                    keyboardOpening = false
                    if (showKeyboard) return@runOnUiThread
                    keyboardFocus.update(snapshot?.sessionId)
                    keyboardSync.bind(snapshot?.sessionId); keyboardAutomatic = true; keyboardDraftKey++
                    keyboardMessage = if (snapshot == null) "电视没有可用输入框。请先在电视打开输入框，再重新打开键盘。" else "输入法选词完成后自动追加；电视已有文字会保留。"
                    showKeyboard = true
                }
            } catch (_: CompanionCommandRejectedException) {
                runOnUiThread {
                    if (sessionGate.isCurrent(current) && keyboardOpenRequests.isCurrent(request)) {
                        keyboardOpening = false; keyboardSync.bind(null); keyboardDraftKey++
                        keyboardMessage = "此电视输入框暂不支持远程键盘，请使用电视上的键盘；遥控按键仍可用。"
                        showKeyboard = true
                    }
                }
            } catch (e: Exception) { fail(current, e) }
        }
    }
    private fun closeKeyboard() {
        keyboardOpenRequests.invalidate(); keyboardOpening = false
        syncRunnable?.let(keyboardHandler::removeCallbacks); syncRunnable = null
        keyboardSync.bind(null); keyboardSending = false
        keyboardFocus.dismiss(); showKeyboard = false
    }
    private fun monitorKeyboard(remote: CompanionClient, current: Int): Boolean {
        return try {
            remote.startKeyboardMonitoring { id ->
                runOnUiThread {
                    if (!sessionGate.isCurrent(current)) return@runOnUiThread
                    if (id != keyboardFocus.sessionId) {
                        keyboardOpenRequests.invalidate(); keyboardOpening = false
                        showKeyboard = false; keyboardDraftKey++
                        syncRunnable?.let(keyboardHandler::removeCallbacks); syncRunnable = null
                        keyboardSync.bind(null); keyboardSending = false
                        keyboardMessage = "已检测到电视输入框。"
                    }
                    keyboardFocus.update(id)
                    maybeShowKeyboard()
                }
            }
            true
        } catch (_: CompanionCommandRejectedException) {
            runOnUiThread {
                if (sessionGate.isCurrent(current)) {
                    keyboardFocus.reset(); showKeyboard = false
                    keyboardMessage = "自动检测暂不可用，请先在电视打开输入框。"
                }
            }
            false
        }
    }
    private fun scheduleKeyboardPoll(remote: CompanionClient, current: Int) {
        keyboardHandler.postDelayed({
            if (!sessionGate.isCurrent(current) || !connected) return@postDelayed
            worker.execute {
                try {
                    if (!sessionGate.isCurrent(current)) return@execute
                    remote.pollEvents()
                    runOnUiThread {
                        if (sessionGate.isCurrent(current) && connected) {
                            maybeShowKeyboard()
                            scheduleKeyboardPoll(remote, current)
                        }
                    }
                } catch (e: Exception) { fail(current, e) }
            }
        }, 200)
    }
    private fun beginTouch(x: Int, y: Int): Boolean {
        val remote = client ?: return false
        if (!connected || busy || touching) return false
        val current = sessionGate.current()
        touching = true
        val hold = SelectHoldPolicy()
        touchPump = TouchGesturePump(worker, { sample ->
            if (sessionGate.isCurrent(current)) {
                if (sample.phase == GesturePhase.HOLD) {
                    remote.holdSelect(true)
                    hold.started(SystemClock.elapsedRealtime())
                } else {
                    val phase = when (sample.phase) {
                        GesturePhase.BEGIN -> TouchPhase.BEGIN
                        GesturePhase.MOVE -> TouchPhase.MOVE
                        else -> TouchPhase.END
                    }
                    remote.touch(sample.x, sample.y, phase)
                    if (sample.phase == GesturePhase.END) {
                        val release = Runnable {
                            if (sessionGate.isCurrent(current)) worker.execute {
                                try {
                                    if (!sessionGate.isCurrent(current)) return@execute
                                    remote.holdSelect(false)
                                    if (sample.tap) remote.press(RemoteButton.SELECT)
                                    runOnUiThread { if (sessionGate.isCurrent(current)) { touching = false; touchPump = null; maybeShowKeyboard() } }
                                } catch (e: Exception) { fail(current, e) }
                            }
                        }
                        val delay = hold.releaseDelay(SystemClock.elapsedRealtime(), sample.cancelled)
                        keyboardHandler.postDelayed(release, delay)
                    }
                }
            }
        }, { fail(current, it) })
        touchPump?.offer(GestureSample(x, y, GesturePhase.BEGIN))
        return true
    }
    private fun scheduleTextSync() {
        syncRunnable?.let(keyboardHandler::removeCallbacks)
        syncRunnable = null
        if (!keyboardAutomatic || !showKeyboard || !connected) return
        syncRunnable = Runnable {
            syncRunnable = null
            if (keyboardAutomatic) sendNextText()
        }.also { keyboardHandler.postDelayed(it, 300) }
    }
    private fun sendNextText(replace: Boolean = false, manual: Boolean = false) {
        val remote = client ?: return
        if (!connected || busy || touching || !showKeyboard) return
        val send = keyboardSync.take(replace, manual) ?: return
        val current = sessionGate.current()
        keyboardSending = true
        worker.execute {
            var attempted = false
            try {
                if (!sessionGate.isCurrent(current) || !keyboardSync.isReady(send)) {
                    runOnUiThread {
                        if (keyboardSync.complete(send, false, attempted = false)) { keyboardSending = false; scheduleTextSync() }
                    }
                    return@execute
                }
                attempted = true
                val sent = remote.sendText(send.payload, send.replace, send.editor)
                runOnUiThread {
                    if (!sessionGate.isCurrent(current) || !keyboardSync.complete(send, sent)) return@runOnUiThread
                    keyboardSending = false
                    keyboardMessage = when {
                        !sent -> "电视没有可用输入框；草稿已保留，请核对后手动重试。"
                        keyboardSync.needsReplacement() -> "已发送的文字被修改。自动同步已暂停；可核对后手动替换全部文字。"
                        else -> "已发送。电视应用不会返回文字显示确认，请以电视为准。"
                    }
                    if (sent) scheduleTextSync()
                }
            } catch (_: KeyboardFocusChangedException) {
                runOnUiThread {
                    if (sessionGate.isCurrent(current) && keyboardSync.complete(send, false)) {
                        keyboardSending = false
                        if (!keyboardMonitoring) keyboardFocus.reset()
                        keyboardMessage = "电视输入框已切换；已暂停同步，请关闭后重新打开键盘。"
                    }
                }
            } catch (_: CompanionCommandRejectedException) {
                runOnUiThread {
                    if (sessionGate.isCurrent(current) && keyboardSync.complete(send, false)) {
                        keyboardSending = false
                        keyboardMessage = "电视暂不支持这次文字输入，草稿已保留；遥控按键仍可用。"
                    }
                }
            } catch (e: Exception) {
                // Do not retry an unacknowledged append: it may already be visible on the TV.
                if (attempted) keyboardSync.pause()
                fail(current, e)
            }
        }
    }
    private fun press(button: RemoteButton) {
        val remote = client ?: return
        if (!connected || busy || touching) return
        val current = sessionGate.current()
        buttonInFlight = true
        busy = true
        worker.execute {
            try { if (!sessionGate.isCurrent(current)) return@execute; remote.press(button); runOnUiThread { if (sessionGate.isCurrent(current)) { busy = false; buttonInFlight = false; maybeShowKeyboard() } } }
            catch (e: Exception) { fail(current, e) }
        }
    }
    private fun fail(current: Int, error: Exception, automatic: Boolean = false) {
        runOnUiThread {
            if (!sessionGate.isCurrent(current)) return@runOnUiThread
            val interruptedDraft = keyboardSync.draft.takeIf { it.isNotEmpty() && showKeyboard }
            disconnect(preserveResume = true)
            recoveryDraft = interruptedDraft
            // Protocol payloads and pairing identities must never appear in the UI or logs.
            status = if (automatic) "暂未连接，轻点设备可重试" else "连接中断（${error.javaClass.simpleName}），可轻点重试"
            if (automatic) {
                val retryable = error is java.net.SocketException || error is java.net.SocketTimeoutException || error is java.io.EOFException
                reconnect.failed(SystemClock.elapsedRealtime(), retryable)
                if (retryable) reconnectHandler.postDelayed({ restoreConnection() }, 3000)
            }
        }
    }
}

@Composable private fun PinDialog(onCancel: () -> Unit, onSubmit: (String) -> Unit, busy: Boolean) {
    var pin by remember { mutableStateOf("") }
    RemoteDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("与 Apple TV 配对") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("输入电视屏幕上显示的 4 位数字。配对码仅用于本次验证。")
            OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("配对码") }) }
    }, confirmButton = { TextButton(onClick = { onSubmit(pin); pin = "" }, enabled = pin.length == 4 && !busy) { Text(if (busy) "验证中…" else "配对") } }, dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("取消") } })
}

@Composable private fun ManualConnectionDialog(device: TVDevice?, onCancel: () -> Unit, onSave: (ManualEndpoint) -> Unit) {
    var host by remember { mutableStateOf(device?.host ?: "") }
    var port by remember { mutableStateOf(device?.port?.toString() ?: "") }
    val endpoint = ManualEndpoint.parse(host, port)
    RemoteDialog(onDismissRequest = onCancel, title = { Text("手动连接 Apple TV") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在能搜到电视的安卓手机上，打开本应用的设备列表，抄下电视名称下的 IP 地址和端口。两台手机需与电视处于同一局域网。")
            OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("IPv4 地址") }, placeholder = { Text("192.168.1.20") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = port, onValueChange = { port = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("端口") }, supportingText = { Text("设备列表中 IP 地址冒号后的数字") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("端口不是固定值，电视重启后可能需要更新。手动连接仍需在电视上完成配对。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { endpoint?.let(onSave) }, enabled = endpoint != null) { Text("连接 / 配对") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } })
}
