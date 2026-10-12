package info.remote.appletv

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Even the title/actions can scroll when a landscape keyboard leaves very little height. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RemoteDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable ColumnScope.() -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().testTag("remote_dialog_scrim")
            .pointerInput(onDismissRequest) { detectTapGestures(onTap = { onDismissRequest() }) }
            .safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("remote_dialog_card")
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }, shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(Modifier.semantics { heading() }) { title() }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = text)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
