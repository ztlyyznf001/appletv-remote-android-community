package info.remote.appletv

import androidx.compose.material3.ButtonColors

/** Keep the normal palette during a transient lock without changing enabled/semantics. */
internal fun retainEnabledButtonColors(colors: ButtonColors, retain: Boolean): ButtonColors =
    if (retain) colors.copy(
        disabledContainerColor = colors.containerColor,
        disabledContentColor = colors.contentColor,
    ) else colors
