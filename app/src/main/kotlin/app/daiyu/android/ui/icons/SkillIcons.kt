package app.daiyu.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original skill marks share Daiyu's round-ended 24dp outline family. */
internal object SkillIcons {
    val Improve: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.SkillImprove", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(20f, 12f); lineTo(20f, 18f); curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
                lineTo(6f, 20f); curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
                lineTo(4f, 6f); curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f); lineTo(12f, 4f)
                moveTo(8f, 15f); lineTo(12f, 11f); lineTo(15f, 13f); lineTo(20f, 5f)
                moveTo(16f, 5f); lineTo(20f, 5f); lineTo(20f, 9f)
            }
        }.build()
    }

    val Create: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.SkillCreate", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(11f, 4f); lineTo(6f, 4f); curveTo(4.9f, 4f, 4f, 4.9f, 4f, 6f)
                lineTo(4f, 18f); curveTo(4f, 19.1f, 4.9f, 20f, 6f, 20f)
                lineTo(18f, 20f); curveTo(19.1f, 20f, 20f, 19.1f, 20f, 18f); lineTo(20f, 13f)
                moveTo(10f, 14f); lineTo(11f, 10f); lineTo(18f, 3f); lineTo(21f, 6f)
                lineTo(14f, 13f); lineTo(10f, 14f); close()
                moveTo(16f, 5f); lineTo(19f, 8f)
            }
        }.build()
    }

    val Import: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.SkillImport", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 8f); lineTo(21f, 18f)
                curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f); lineTo(5f, 20f)
                curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f); lineTo(3f, 8f); close()
                moveTo(3f, 8f); lineTo(21f, 8f)
                moveTo(12f, 11f); lineTo(12f, 17f)
                moveTo(9.5f, 14.5f); lineTo(12f, 17f); lineTo(14.5f, 14.5f)
            }
        }.build()
    }
}
