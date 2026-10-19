package app.daiyu.android.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Exact preview-agent-cards SVG geometry shared by chat controls and their sheets.
internal object ChatPreviewIcons {
    val Attach: ImageVector by lazy {
        previewIcon("Attach") {
            outline("M12 3v18M3 12h18", 1.7f)
        }
    }

    val Thought: ImageVector by lazy {
        previewIcon("Thought") {
            outline("M5 15C2 10 5 4 10 4c6 0 9 5 6 9-2 3-7 2-7-1s4-4 5-1M8 20c6 2 12-2 12-8", 1.7f)
            solid("M6.15 16.5a1.15 1.15 0 1 0 -2.3 0a1.15 1.15 0 1 0 2.3 0Z")
            solid("M21 7.5a1 1 0 1 0 -2 0a1 1 0 1 0 2 0Z")
        }
    }

    val Menu: ImageVector by lazy {
        previewIcon("Menu") {
            outline("M4 6h16M4 12h12M4 18h16", 1.7f)
        }
    }

    val SubAgents: ImageVector by lazy {
        previewIcon("SubAgents") {
            outline("M12 1.5V3M12 9v3M5.5 15v-3h13v3", 1.6f)
            outline("M10 3H14a1.5 1.5 0 0 1 1.5 1.5V7.5a1.5 1.5 0 0 1 -1.5 1.5H10a1.5 1.5 0 0 1 -1.5 -1.5V4.5a1.5 1.5 0 0 1 1.5 -1.5Z", 1.6f)
            outline("M3.5 15H7.5a1.5 1.5 0 0 1 1.5 1.5V19.5a1.5 1.5 0 0 1 -1.5 1.5H3.5a1.5 1.5 0 0 1 -1.5 -1.5V16.5a1.5 1.5 0 0 1 1.5 -1.5Z", 1.6f)
            outline("M16.5 15H20.5a1.5 1.5 0 0 1 1.5 1.5V19.5a1.5 1.5 0 0 1 -1.5 1.5H16.5a1.5 1.5 0 0 1 -1.5 -1.5V16.5a1.5 1.5 0 0 1 1.5 -1.5Z", 1.6f)
            outline("M10.7 5.8v.3m2.6-.3v.3M4.2 17.8v.3m2.6-.3v.3m10.4-.3v.3m2.6-.3v.3", 1.4f)
        }
    }

    val More: ImageVector by lazy {
        previewIcon("More") {
            outline("M5 12h.01M12 12h.01M19 12h.01", 2.8f)
        }
    }

    val Chevron: ImageVector by lazy {
        previewIcon("Chevron") {
            outline("m7 10 5 5 5-5", 1.7f)
        }
    }

    val Send: ImageVector by lazy {
        previewIcon("Send") {
            outline("M12 18V6m-5 5 5-5 5 5", 1.9f)
        }
    }

    val Append: ImageVector by lazy {
        previewIcon("Append") {
            outline("m21 3-6 18-4-8-8-4 18-6ZM11 13 21 3", 1.7f)
        }
    }

    val Pause: ImageVector by lazy {
        previewIcon("Pause") {
            outline("M9 6v12M15 6v12", 1.7f)
        }
    }

    val Play: ImageVector by lazy {
        previewIcon("Play") {
            solid("m9 5 10 7-10 7Z")
        }
    }

}

private fun previewIcon(name: String, content: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder("ChatPreview.$name", 24.dp, 24.dp, 24f, 24f).apply(content).build()

private fun ImageVector.Builder.outline(data: String, width: Float) {
    addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
}

private fun ImageVector.Builder.solid(data: String) {
    addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
}

internal const val ChatContextWingPath = "M12 12C8 1 1 2 3 8c1 3 5 4 9 4C8 12 2 14 5 19c3 5 6-2 7-7C13 17 16 24 19 19c3-5-3-7-7-7 4 0 8-1 9-4 2-6-5-7-9 4Z"

@Composable
internal fun ChatContextMark(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val wings = remember { PathParser().parsePathString(ChatContextWingPath).toPath() }
    val body = remember { PathParser().parsePathString("M12 9v6m0-6-1-2m1 2 1-2").toPath() }
    val progress = remember(fraction) {
        fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.let { value ->
            val measure = PathMeasure().apply { setPath(wings, false) }
            Path().also { measure.getSegment(0f, measure.length * value, it) }
        }
    }
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(wings, color.copy(alpha = color.alpha * .24f), style = stroke)
            progress?.let { drawPath(it, color, style = stroke) }
            drawPath(body, color.copy(alpha = color.alpha * .5f), style = stroke)
        }
    }
}
