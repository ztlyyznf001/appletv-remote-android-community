package info.remote.appletv

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import info.appletvremote.protocol.RemoteButton
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable internal fun TouchPad(enabled: Boolean, onBegin: (Int, Int) -> Boolean, onMove: (Int, Int) -> Unit, onEnd: (Int, Int, Boolean) -> Unit, modifier: Modifier = Modifier, onAccessibilityPress: (RemoteButton) -> Unit = {}, keepEnabledAppearance: Boolean = false, onHold: () -> Unit = {}, onCancel: (Int, Int) -> Unit = { x, y -> onEnd(x, y, false) }) {
    val availableAppearance = enabled || keepEnabledAppearance
    val view = LocalView.current
    var contact by remember { mutableStateOf<Offset?>(null) }
    var held by remember { mutableStateOf(false) }
    val feedbackAlpha by animateFloatAsState(if (contact != null) .16f else 0f, label = "touch feedback")
    val feedbackScale by animateFloatAsState(if (contact != null) .985f else 1f, label = "touch scale")
    val hold by rememberUpdatedState(onHold)
    val cancel by rememberUpdatedState(onCancel)
    val begin by rememberUpdatedState(onBegin)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.graphicsLayer { scaleX = feedbackScale; scaleY = feedbackScale }
            .semantics {
                contentDescription = "Apple TV 触摸板"
                if (!enabled) disabled() else {
                    onClick(label = "确认") { onAccessibilityPress(RemoteButton.SELECT); true }
                    customActions = listOf(
                        CustomAccessibilityAction("向上") { onAccessibilityPress(RemoteButton.UP); true },
                        CustomAccessibilityAction("向下") { onAccessibilityPress(RemoteButton.DOWN); true },
                        CustomAccessibilityAction("向左") { onAccessibilityPress(RemoteButton.LEFT); true },
                        CustomAccessibilityAction("向右") { onAccessibilityPress(RemoteButton.RIGHT); true },
                    )
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Freeze callbacks for this gesture; recomposition must not retarget it to a new connection.
                    val moveGesture = move; val endGesture = end; val cancelGesture = cancel; val holdGesture = hold
                    fun x(value: Float) = (value / size.width.coerceAtLeast(1) * 1000).toInt().coerceIn(0, 1000)
                    fun y(value: Float) = (value / size.height.coerceAtLeast(1) * 1000).toInt().coerceIn(0, 1000)
                    if (!begin(x(down.position.x), y(down.position.y))) return@awaitEachGesture
                    down.consume()
                    contact = down.position; held = false
                    var last = down.position
                    var maxDistance = 0f
                    var lastSent = down.uptimeMillis
                    var released = false
                    var longPressEligible = true
                    try {
                        while (true) {
                            val elapsed = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                            val deadline = if (longPressEligible && !held) viewConfiguration.longPressTimeoutMillis else 10_000L
                            val event = withTimeoutOrNull((deadline - elapsed).coerceAtLeast(1L)) { awaitPointerEvent() }
                            if (event == null) {
                                if (longPressEligible && !held && elapsed < 10_000L) {
                                    held = true; holdGesture()
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    continue
                                }
                                released = true; cancelGesture(x(last.x), y(last.y))
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            contact = last
                            maxDistance = maxOf(maxDistance, (last - down.position).getDistance())
                            if (maxDistance >= viewConfiguration.touchSlop) longPressEligible = false
                            val cancelled = change.isConsumed || event.changes.any { it.id != down.id && it.pressed }
                            change.consume()
                            if (cancelled || !change.pressed) {
                                val tap = !cancelled && !held && maxDistance < viewConfiguration.touchSlop && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                                released = true
                                if (cancelled) cancelGesture(x(last.x), y(last.y)) else endGesture(x(last.x), y(last.y), tap)
                                if (tap) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                break
                            }
                            if (change.uptimeMillis - lastSent >= 16) {
                                moveGesture(x(last.x), y(last.y)); lastSent = change.uptimeMillis
                            }
                        }
                    } finally {
                        if (!released) cancelGesture(x(last.x), y(last.y))
                        contact = null; held = false
                    }
                }
            }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val highlight = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                drawCircle(highlight.copy(alpha = feedbackAlpha), radius = if (held) 56.dp.toPx() else 40.dp.toPx(), center = contact ?: center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                Text("触摸板", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    color = if (availableAppearance) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (availableAppearance) "滑动移动 · 轻点确认 · 长按选择" else "连接后即可使用", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun KeyboardDialog(
    busy: Boolean, message: String, onClose: () -> Unit, onSend: (String, Boolean, () -> Unit) -> Unit,
    onDraft: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
) {
    // TextFieldValue preserves the IME composing range; candidates are never sent to the TV.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var replace by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        softwareKeyboard?.show()
    }
    RemoteDialog(onDismissRequest = onClose, title = { Text("电视键盘") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选词完成后自动追加到电视。电视已有文字会保留。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = draft, onValueChange = {
                if (it.text.length <= 4096) {
                    draft = it
                    onDraft(it.text, it.composition != null, automatic)
                }
            }, label = { Text("输入文字") }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester), minLines = 2, maxLines = 5,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = automatic, role = Role.Switch, onValueChange = {
                    automatic = it; onDraft(draft.text, draft.composition != null, it)
                }), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = automatic, onCheckedChange = null)
                Text("自动同步已完成的输入", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = replace, enabled = !busy, role = Role.Checkbox, onValueChange = { replace = it }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = replace, onCheckedChange = null, enabled = !busy)
                Text("手动替换电视全部文字（含原有内容）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (replace) Text("此操作会清空电视输入框，再写入上方文字。空内容会清空输入框。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !busy && draft.composition == null && (draft.text.isNotEmpty() || replace), onClick = {
        onSend(draft.text, replace) { }
    }) { Text(if (busy) "同步中…" else if (replace) "替换并发送" else "立即同步") } }, dismissButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
