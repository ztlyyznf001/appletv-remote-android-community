package info.remote.appletv

/** Uses inset-safe window size. Ordinary portrait keeps actions docked, short landscape uses a side rail. */
internal data class RemoteLayoutPolicy(
    val contentWidthDp: Float,
    val controlWidthDp: Float,
    val surfaceHeightDp: Float,
    val sideControls: Boolean,
    val dockActions: Boolean,
) {
    companion object {
        fun calculate(widthDp: Float, heightDp: Float, fontScale: Float): RemoteLayoutPolicy {
            val side = widthDp >= 600f && widthDp > heightDp && fontScale < 1.5f
            val gutter = if (widthDp < 360f) 16f else 24f
            val width = (widthDp - gutter * 2).coerceIn(0f, if (side) 1040f else 520f)
            val controls = if (side) width - 136f else width
            val surface = minOf(controls, (heightDp - if (side) 150f else 240f).coerceIn(180f, 500f))
            val dock = if (side) heightDp >= 260f else fontScale < 1.5f && heightDp >= 380f + 110f * fontScale
            return RemoteLayoutPolicy(width, controls, surface, side, dock)
        }
    }
}
