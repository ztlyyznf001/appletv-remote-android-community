package info.remote.appletv

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.appletvremote.protocol.RemoteButton

internal object RemoteTags {
    const val Screen = "remote_screen"
    const val Connection = "connection_panel"
    const val Controls = "remote_controls"
    const val TouchMode = "touch_mode"
    const val DirectionMode = "direction_mode"
    const val TouchPad = "touch_pad"
    const val Keyboard = "keyboard"
    const val Options = "connection_options"
}

@Composable internal fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB8E4CA), onPrimary = Color(0xFF123529),
        primaryContainer = Color(0xFF284C3C), onPrimaryContainer = Color(0xFFD6F6E2),
        background = Color(0xFF101719), onBackground = Color(0xFFE6EEEA),
        surface = Color(0xFF182224), onSurface = Color(0xFFE6EEEA),
        surfaceVariant = Color(0xFF243133), onSurfaceVariant = Color(0xFFABBDB7),
        outline = Color(0xFF52665F),
    ), content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RemoteScreen(
    name: String, count: Int, discovery: String, status: String,
    connected: Boolean, busy: Boolean, touching: Boolean, hasDevice: Boolean,
    onSelect: () -> Unit, onConnect: () -> Unit, onForget: () -> Unit,
    onPress: (RemoteButton) -> Unit, onKeyboard: () -> Unit,
    onTouchBegin: (Int, Int) -> Boolean, onTouchMove: (Int, Int) -> Unit,
    onTouchEnd: (Int, Int, Boolean) -> Unit, buttonInFlight: Boolean = false,
    onTouchHold: () -> Unit = {},
    onTouchCancel: (Int, Int) -> Unit = { x, y -> onTouchEnd(x, y, false) },
    onOptionsVisibility: (Boolean) -> Unit = {},
) {
    var touchMode by rememberSaveable { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    SideEffect { onOptionsVisibility(showOptions || confirmForget) }
    val input = RemoteInteractionPolicy.calculate(connected, busy, touching, buttonInFlight)
    val view = LocalView.current
    val press: (RemoteButton) -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onPress(it)
    }
    val keep = input.keepControlAppearance
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().testTag(RemoteTags.Screen)) {
        val fontScale = LocalDensity.current.fontScale
        val layout = RemoteLayoutPolicy.calculate(maxWidth.value, maxHeight.value, fontScale)
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().testTag(RemoteTags.Connection), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = onSelect, enabled = !busy && !touching, shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RemoteGlyph(RemoteButton.HOME, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (connected) "已连接 · 切换设备" else if (busy) "恢复连接中…" else "选择或连接设备",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { showOptions = true }, enabled = !touching,
                    colors = retainEnabledButtonColors(ButtonDefaults.textButtonColors(), touching),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(RemoteTags.Options)) { Text("连接") }
            }
        }
        val modes: @Composable () -> Unit = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("触摸板", touchMode, !touching, RemoteTags.TouchMode) { touchMode = true }
                ModeButton("方向键", !touchMode, !touching, RemoteTags.DirectionMode) { touchMode = false }
                Box(Modifier.widthIn(min = 80.dp, max = 124.dp)) { KeyboardButton(input.controlsEnabled, onKeyboard, keep, compact = true) }
            }
        }
        val surface: @Composable (Float) -> Unit = { availableHeight ->
            if (touchMode) TouchPad(input.touchEnabled, onTouchBegin, onTouchMove, onTouchEnd,
                Modifier.fillMaxWidth().height(availableHeight.coerceAtLeast(180f).dp).testTag(RemoteTags.TouchPad),
                onAccessibilityPress = press, keepEnabledAppearance = keep, onHold = onTouchHold, onCancel = onTouchCancel)
            else DirectionPad(input.controlsEnabled, minOf(layout.controlWidthDp, availableHeight, 340f), keep, press)
        }
        val actions: @Composable (Boolean) -> Unit = { vertical ->
            ActionDock(input.controlsEnabled, keep, vertical, press)
        }
        val outer = Modifier.align(Alignment.TopCenter).width(layout.contentWidthDp.dp).fillMaxHeight().padding(vertical = 12.dp)
        if (layout.dockActions) {
            Column(outer, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header()
                if (layout.sideControls) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            modes(); surface(layout.surfaceHeightDp)
                        }
                        Column(Modifier.width(120.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Bottom) { actions(true) }
                    }
                } else {
                    modes()
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag(RemoteTags.Controls)) {
                        val availableHeight = maxHeight.value
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            surface(availableHeight)
                        }
                    }
                    actions(false)
                }
            }
        } else {
            // Extreme height / accessibility text: keep all controls reachable rather than clipping a fixed dock.
            Column(outer.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                header(); modes(); surface(layout.surfaceHeightDp); actions(false)
            }
        }
    }
    if (showOptions) RemoteDialog(onDismissRequest = { showOptions = false }, title = { Text("连接与设备") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text("$count 台附近设备 · $discovery", style = MaterialTheme.typography.bodySmall)
            Text("与 Apple TV 连接同一 Wi-Fi。打开应用会尝试恢复上次已配对设备；主动断开后不会自动重连。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showOptions = false; onSelect() }, enabled = !busy && !touching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("选择或切换设备") }
            Button(onClick = { showOptions = false; onConnect() }, enabled = !busy && !touching,
                colors = retainEnabledButtonColors(ButtonDefaults.buttonColors(), keep),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (connected) "断开连接" else if (busy) "连接中…" else if (hasDevice) "连接 / 配对" else "选择 Apple TV")
            }
            TextButton(onClick = { showOptions = false; confirmForget = true }, enabled = hasDevice && !busy && !touching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("移除已保存配对") }
        }
    }, confirmButton = { TextButton(onClick = { showOptions = false }) { Text("关闭") } })
    if (confirmForget) RemoteDialog(onDismissRequest = { confirmForget = false }, title = { Text("移除已保存配对？") },
        text = { Text("下次连接这台 Apple TV 时，需要重新输入电视上显示的配对码。") },
        confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }, enabled = !busy && !touching) { Text("移除配对") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActionDock(enabled: Boolean, keep: Boolean, vertical: Boolean, onPress: (RemoteButton) -> Unit) {
    val buttons = listOf(RemoteButton.MENU, RemoteButton.HOME, RemoteButton.PLAY_PAUSE)
    if (vertical) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { button -> ActionKey(button, button.label(), enabled, keep, Modifier.fillMaxWidth()) { onPress(button) } }
    } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        buttons.forEach { button -> ActionKey(button, button.label(), enabled, keep) { onPress(button) } }
    }
}

@Composable private fun KeyboardButton(enabled: Boolean, onClick: () -> Unit, keepAppearance: Boolean, compact: Boolean = false) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = retainEnabledButtonColors(ButtonDefaults.outlinedButtonColors(), keepAppearance),
        border = remoteOutlineBorder(enabled || keepAppearance),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 52.dp).testTag(RemoteTags.Keyboard)) {
        Text(if (compact) "键盘" else "键盘输入", textAlign = TextAlign.Center)
    }
}

@Composable private fun remoteOutlineBorder(availableAppearance: Boolean) = BorderStroke(
    1.dp,
    if (availableAppearance) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
)

@Composable private fun ModeButton(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    // Intrinsic text width allows FlowRow to wrap instead of clipping at large font scales.
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.widthIn(min = 112.dp).heightIn(min = 48.dp).testTag(tag).semantics { this.selected = selected },
        colors = retainEnabledButtonColors(ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ), retain = !enabled)) { Text(label) }
}

@Composable private fun DirectionPad(enabled: Boolean, surfaceHeight: Float, keepAppearance: Boolean, onPress: (RemoteButton) -> Unit) {
    // Very narrow split-screen windows use a list rather than shrinking the 48dp hit targets.
    if (surfaceHeight < 180f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (button in listOf(RemoteButton.UP, RemoteButton.LEFT, RemoteButton.SELECT, RemoteButton.RIGHT, RemoteButton.DOWN)) {
                FilledTonalButton(onClick = { onPress(button) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = retainEnabledButtonColors(ButtonDefaults.filledTonalButtonColors(), keepAppearance)) { Text(button.label()) }
            }
        }
        return
    }
    // A real grid reserves separate cells; labels never overlap adjacent hit targets.
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(surfaceHeight.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0..2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0..2) {
                    val button = when (row to column) {
                        0 to 1 -> RemoteButton.UP
                        1 to 0 -> RemoteButton.LEFT
                        1 to 1 -> RemoteButton.SELECT
                        1 to 2 -> RemoteButton.RIGHT
                        2 to 1 -> RemoteButton.DOWN
                        else -> null
                    }
                    if (button == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                    else FilledTonalButton(onClick = { onPress(button) }, enabled = enabled,
                        modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = button.label() },
                        shape = if (button == RemoteButton.SELECT) CircleShape else RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = retainEnabledButtonColors(ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (button == RemoteButton.SELECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (button == RemoteButton.SELECT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ), keepAppearance)) { RemoteGlyph(button, Modifier.size(24.dp), LocalContentColor.current) }
                }
            }
        }
    }
}

@Composable private fun ActionKey(button: RemoteButton, label: String, enabled: Boolean, keepAppearance: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, label = "button press")
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.widthIn(min = 80.dp).heightIn(min = 72.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interaction,
        colors = retainEnabledButtonColors(ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), keepAppearance)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteGlyph(button, Modifier.size(24.dp), LocalContentColor.current)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

private fun RemoteButton.label() = when (this) {
    RemoteButton.UP -> "向上"
    RemoteButton.DOWN -> "向下"
    RemoteButton.LEFT -> "向左"
    RemoteButton.RIGHT -> "向右"
    RemoteButton.SELECT -> "确认"
    RemoteButton.MENU -> "返回"
    RemoteButton.HOME -> "主屏幕"
    RemoteButton.PLAY_PAUSE -> "播放 / 暂停"
    else -> name
}

/** Vector strokes remain crisp and do not depend on a device font's symbol coverage. */
@Composable private fun RemoteGlyph(button: RemoteButton, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun point(x: Float, y: Float) = Offset(x * w, y * h)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 2.2.dp.toPx(), StrokeCap.Round)
        when (button) {
            RemoteButton.UP -> { line(.22f, .62f, .5f, .34f); line(.5f, .34f, .78f, .62f) }
            RemoteButton.DOWN -> { line(.22f, .38f, .5f, .66f); line(.5f, .66f, .78f, .38f) }
            RemoteButton.LEFT, RemoteButton.MENU -> { line(.62f, .18f, .3f, .5f); line(.3f, .5f, .62f, .82f) }
            RemoteButton.RIGHT -> { line(.38f, .18f, .7f, .5f); line(.7f, .5f, .38f, .82f) }
            RemoteButton.SELECT -> { line(.22f, .52f, .42f, .72f); line(.42f, .72f, .8f, .3f) }
            RemoteButton.HOME -> {
                drawRoundRect(color, point(.1f, .15f), Size(w * .8f, h * .56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
                line(.36f, .87f, .64f, .87f); line(.5f, .74f, .5f, .85f)
            }
            RemoteButton.PLAY_PAUSE -> {
                drawPath(Path().apply { moveTo(w * .12f, h * .16f); lineTo(w * .54f, h * .5f); lineTo(w * .12f, h * .84f); close() }, color)
                line(.7f, .2f, .7f, .8f); line(.9f, .2f, .9f, .8f)
            }
            else -> Unit
        }
    }
}

@Preview(name = "Small phone", widthDp = 320, heightDp = 568)
@Preview(name = "Phone", widthDp = 393, heightDp = 852)
@Preview(name = "Landscape", widthDp = 800, heightDp = 360)
@Preview(name = "Large type", widthDp = 360, heightDp = 640, fontScale = 2f)
@Preview(name = "Tablet", widthDp = 1000, heightDp = 800)
@Composable private fun RemoteScreenPreview() {
    RemoteTheme {
        Surface {
            RemoteScreen("客厅 Apple TV", 2, "已发现附近设备", "已连接 · 加密会话已建立", true, false, false, true,
                {}, {}, {}, {}, {}, { _, _ -> false }, { _, _ -> }, { _, _, _ -> })
        }
    }
}
