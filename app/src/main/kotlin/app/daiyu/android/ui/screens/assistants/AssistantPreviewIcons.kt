package app.daiyu.android.ui.screens.assistants

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The assistant sheet's 24-unit SVG outlines from preview-agent-cards.html. */
internal object AssistantPreviewIcons {
    val Search = outline("search", "M3.5 10.5a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "m16 16 5 5")
    val Add = outline("plus", "M12 3v18M3 12h18")
    val Close = outline("close", "m6 6 12 12M18 6 6 18")
    val Back = outline("back", "m15 18-6-6 6-6")
    val Check = outline("check", "m5 12 4 4L19 6")
    val Chevron = outline("chevron", "m7 10 5 5 5-5")
    val Copy = outline("copy", "M10 8h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2Z", "M15 8V3H3v13h5")
    val Edit = outline("edit", "m16 3 5 5-12 12-6 1 1-6L16 3Zm-3 3 5 5")
    val Delete = outline("trash", "M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7")

    private fun outline(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder("assistant-$name", 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                    stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
}
