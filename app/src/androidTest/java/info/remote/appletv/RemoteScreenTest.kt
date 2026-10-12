package info.remote.appletv

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import info.appletvremote.protocol.RemoteButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteScreenTest {
    private fun SemanticsNodeInteraction.reach(): SemanticsNodeInteraction {
        if (!isDisplayed()) performScrollTo()
        return this
    }
    @get:Rule val compose = createComposeRule()

    private fun screen(connected: Boolean = true, fontScale: Float = 1f,
        onPress: (RemoteButton) -> Unit = {}, onKeyboard: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RemoteTheme {
                    Surface(Modifier.width(320.dp).fillMaxHeight()) {
                        RemoteScreen("客厅里名称很长的一台 Apple TV", 2, "已发现附近设备", "已连接 · 加密会话已建立",
                            connected, false, false, true, {}, {}, {}, onPress, onKeyboard,
                            { _, _ -> true }, { _, _ -> }, { _, _, _ -> })
                    }
                }
            }
        }
    }

    @Test fun narrowPhoneControlsAreReachableAndKeepTheirCommandMapping() {
        val commands = mutableListOf<RemoteButton>()
        screen(onPress = { commands += it })
        compose.onNodeWithTag(RemoteTags.DirectionMode).reach().performClick()
        for ((description, command) in listOf("向上" to RemoteButton.UP, "向左" to RemoteButton.LEFT,
            "确认" to RemoteButton.SELECT, "向右" to RemoteButton.RIGHT, "向下" to RemoteButton.DOWN)) {
            compose.onNodeWithContentDescription(description).reach()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(command, commands.last())
        }
        for ((label, command) in listOf("返回" to RemoteButton.MENU, "主屏幕" to RemoteButton.HOME,
            "播放 / 暂停" to RemoteButton.PLAY_PAUSE)) {
            compose.onNodeWithText(label).reach().performClick()
            assertEquals(command, commands.last())
        }
    }

    @Test fun largeFontKeepsKeyboardAndConnectionOptionsReachable() {
        var opens = 0
        screen(fontScale = 2f, onKeyboard = { opens++ })
        compose.onNodeWithTag(RemoteTags.Keyboard).reach().assertIsDisplayed().performClick()
        assertEquals(1, opens)
        compose.onNodeWithTag(RemoteTags.Options).reach().performClick()
        compose.onNodeWithText("断开连接").reach().assertIsDisplayed()
        compose.onNodeWithText("移除已保存配对").reach().performClick()
        compose.onNodeWithText("取消").reach().performClick()
        compose.onNodeWithText("移除已保存配对").assertDoesNotExist()
    }

    @Test fun disconnectedRemoteCannotSendCommands() {
        screen(connected = false)
        compose.onNodeWithTag(RemoteTags.TouchPad).reach().assertIsNotEnabled()
        compose.onNodeWithTag(RemoteTags.Keyboard).reach().assertIsNotEnabled()
        compose.onNodeWithTag(RemoteTags.DirectionMode).reach().performClick()
        compose.onNodeWithContentDescription("确认").reach().assertIsNotEnabled()
    }

    @Test fun keyboardDraftClearsWhenDismissedAndReopened() {
        val open = mutableStateOf(true)
        compose.setContent {
            RemoteTheme {
                if (open.value) KeyboardDialog(false, "测试提示", { open.value = false }, { _, _, _ -> })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("不应持久保存的草稿")
        compose.onNodeWithText("关闭").reach().performClick()
        compose.runOnIdle { open.value = true }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    @Test fun keyboardCanDismissByBackdropAndBack() {
        val open = mutableStateOf(true)
        compose.setContent {
            RemoteTheme {
                if (open.value) KeyboardDialog(false, "测试提示", { open.value = false }, { _, _, _ -> })
            }
        }
        compose.onNodeWithText("电视键盘").reach().performTouchInput { click() }
        compose.runOnIdle { assertTrue(open.value) }
        compose.onNodeWithTag("remote_dialog_card").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(open.value) }
        compose.onNodeWithTag("remote_dialog_scrim").performTouchInput { click(Offset(4f, centerY)) }
        compose.runOnIdle { assertFalse(open.value); open.value = true }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        // Automatic focus may show the IME; the first Back then dismisses it before the dialog.
        if (compose.runOnIdle { open.value }) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        compose.runOnIdle { assertFalse(open.value) }
    }

    @Test fun transientInputLocksKeepOtherButtonsPixelsStableAndClicksBlocked() {
        val touching = mutableStateOf(false)
        val busy = mutableStateOf(false)
        val buttonInFlight = mutableStateOf(false)
        var keyboardOpens = 0
        compose.setContent {
            RemoteTheme {
                Surface(Modifier.width(320.dp).fillMaxHeight()) {
                    RemoteScreen("客厅 Apple TV", 1, "已发现设备", "已连接", true,
                        busy.value, touching.value, true, {}, {}, {}, {}, { keyboardOpens++ },
                        { _, _ -> true }, { _, _ -> }, { _, _, _ -> }, buttonInFlight.value)
                }
            }
        }
        fun assertSamePixels(before: ImageBitmap, after: ImageBitmap) {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            val a = before.toPixelMap()
            val b = after.toPixelMap()
            for (y in 0 until a.height) for (x in 0 until a.width) assertEquals(a[x, y], b[x, y])
        }
        compose.onNodeWithTag(RemoteTags.TouchMode).reach()
        val modeBefore = compose.onNodeWithTag(RemoteTags.TouchMode).captureToImage()
        compose.runOnIdle { touching.value = true }
        compose.onNodeWithTag(RemoteTags.TouchMode).assertIsNotEnabled()
        assertSamePixels(modeBefore, compose.onNodeWithTag(RemoteTags.TouchMode).captureToImage())
        compose.runOnIdle { touching.value = false }
        compose.onNodeWithTag(RemoteTags.Keyboard).reach()
        val keyboardBefore = compose.onNodeWithTag(RemoteTags.Keyboard).captureToImage()
        compose.runOnIdle { busy.value = true; buttonInFlight.value = true }
        compose.onNodeWithTag(RemoteTags.Keyboard).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, keyboardOpens) }
        assertSamePixels(keyboardBefore, compose.onNodeWithTag(RemoteTags.Keyboard).captureToImage())
        compose.runOnIdle { busy.value = false; buttonInFlight.value = false }
        compose.onNodeWithTag(RemoteTags.Keyboard).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, keyboardOpens) }
    }

    @Test fun touchLongPressPairsReleaseWithoutTap() {
        var holds = 0; val ends = mutableListOf<Boolean>()
        compose.setContent { RemoteTheme {
            TouchPad(true, { _, _ -> true }, { _, _ -> }, { _, _, tap -> ends += tap },
                Modifier.size(280.dp).testTag("gesture"), onHold = { holds++ })
        } }
        compose.onNodeWithTag("gesture").performTouchInput { longClick(center, durationMillis = 750) }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(listOf(false), ends) }
    }
    @Test fun cancellingTouchDoesNotSelect() {
        val ends = mutableListOf<Boolean>()
        compose.setContent { RemoteTheme {
            TouchPad(true, { _, _ -> true }, { _, _ -> }, { _, _, tap -> ends += tap }, Modifier.size(280.dp).testTag("gesture"))
        } }
        compose.onNodeWithTag("gesture").performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(listOf(false), ends) }
    }
    @Test fun keyboardStaysEditableWhilePreviousSyncIsInFlight() {
        val drafts = mutableListOf<String>()
        compose.setContent { RemoteTheme {
            KeyboardDialog(true, "同步中", {}, { _, _, _ -> }, onDraft = { text, composing, automatic ->
                if (!composing && automatic) drafts += text
            })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("中文")
        compose.onNode(hasSetTextAction()).performTextInput("电影")
        compose.runOnIdle { assertEquals("中文电影", drafts.last()) }
        compose.onNodeWithText("关闭").reach().assertIsEnabled()
    }
}
