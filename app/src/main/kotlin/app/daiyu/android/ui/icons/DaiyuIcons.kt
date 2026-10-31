package app.daiyu.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original in-app outlines, not launcher artwork. Color is supplied by the caller's tint. */
object DaiyuIcons {
    val Menu: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Menu", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(16f, 12f)
                moveTo(4f, 18f); lineTo(20f, 18f)
            }
        }.build()
    }

    val More: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.More", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5.5f, 10.5f); curveTo(6.328f, 10.5f, 7.0f, 11.172f, 7.0f, 12f); curveTo(7.0f, 12.828f, 6.328f, 13.5f, 5.5f, 13.5f); curveTo(4.672f, 13.5f, 4.0f, 12.828f, 4.0f, 12f); curveTo(4.0f, 11.172f, 4.672f, 10.5f, 5.5f, 10.5f); close()
                moveTo(12f, 10.5f); curveTo(12.828f, 10.5f, 13.5f, 11.172f, 13.5f, 12f); curveTo(13.5f, 12.828f, 12.828f, 13.5f, 12f, 13.5f); curveTo(11.172f, 13.5f, 10.5f, 12.828f, 10.5f, 12f); curveTo(10.5f, 11.172f, 11.172f, 10.5f, 12f, 10.5f); close()
                moveTo(18.5f, 10.5f); curveTo(19.328f, 10.5f, 20.0f, 11.172f, 20.0f, 12f); curveTo(20.0f, 12.828f, 19.328f, 13.5f, 18.5f, 13.5f); curveTo(17.672f, 13.5f, 17.0f, 12.828f, 17.0f, 12f); curveTo(17.0f, 11.172f, 17.672f, 10.5f, 18.5f, 10.5f); close()
            }
        }.build()
    }

    val Add: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Add", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 12f); lineTo(19f, 12f)
                moveTo(12f, 5f); lineTo(12f, 19f)
            }
        }.build()
    }

    val Attach: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Attach", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 13f); lineTo(15.5f, 6.5f); curveTo(18f, 4f, 21f, 7f, 18.5f, 9.5f)
                lineTo(9f, 19f); curveTo(5f, 21f, 3f, 18f, 3.5f, 15f)
                curveTo(3.7f, 14.3f, 4f, 13.8f, 4.5f, 13.3f)
                lineTo(13.5f, 4.3f); curveTo(15f, 3f, 17f, 3f, 18.5f, 4f)
            }
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Image", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(6f, 4f); lineTo(18f, 4f); curveTo(19.2f, 4f, 20f, 4.8f, 20f, 6f)
                lineTo(20f, 18f); curveTo(20f, 19.2f, 19.2f, 20f, 18f, 20f)
                lineTo(6f, 20f); curveTo(4.8f, 20f, 4f, 19.2f, 4f, 18f)
                lineTo(4f, 6f); curveTo(4f, 4.8f, 4.8f, 4f, 6f, 4f); close()
                moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 16f); lineTo(17f, 13f); lineTo(20f, 16f)
                moveTo(15.5f, 7f); curveTo(17.5f, 7f, 17.5f, 10f, 15.5f, 10f); curveTo(13.5f, 10f, 13.5f, 7f, 15.5f, 7f); close()
            }
        }.build()
    }

    val Video: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Video", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 6f); lineTo(13f, 6f); curveTo(14.2f, 6f, 15f, 6.8f, 15f, 8f)
                lineTo(15f, 16f); curveTo(15f, 17.2f, 14.2f, 18f, 13f, 18f)
                lineTo(5f, 18f); curveTo(3.8f, 18f, 3f, 17.2f, 3f, 16f)
                lineTo(3f, 8f); curveTo(3f, 6.8f, 3.8f, 6f, 5f, 6f); close()
                moveTo(15f, 10f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(15f, 14f)
            }
        }.build()
    }

    val File: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.File", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(7f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 19f)
                curveTo(19f, 20.2f, 18.2f, 21f, 17f, 21f); lineTo(7f, 21f)
                curveTo(5.8f, 21f, 5f, 20.2f, 5f, 19f); lineTo(5f, 5f); curveTo(5f, 3.8f, 5.8f, 3f, 7f, 3f); close()
                moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
                moveTo(9f, 13f); lineTo(15f, 13f); moveTo(9f, 17f); lineTo(13f, 17f)
            }
        }.build()
    }

    val Send: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Send", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 20f); lineTo(12f, 4f)
                moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f)
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Pause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 5f); lineTo(8f, 19f)
                moveTo(16f, 5f); lineTo(16f, 19f)
            }
        }.build()
    }

    val Resume: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Resume", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close()
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Stop", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(7f, 5f); lineTo(17f, 5f); curveTo(18.2f, 5f, 19f, 5.8f, 19f, 7f)
                lineTo(19f, 17f); curveTo(19f, 18.2f, 18.2f, 19f, 17f, 19f)
                lineTo(7f, 19f); curveTo(5.8f, 19f, 5f, 18.2f, 5f, 17f)
                lineTo(5f, 7f); curveTo(5f, 5.8f, 5.8f, 5f, 7f, 5f); close()
            }
        }.build()
    }

    val Steer: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Steer", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 3f); lineTo(5f, 10f); curveTo(5f, 12.2f, 6.8f, 14f, 9f, 14f); lineTo(20f, 14f)
                moveTo(15f, 9f); lineTo(20f, 14f); lineTo(15f, 19f)
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Copy", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 8f); lineTo(18f, 8f); curveTo(19.2f, 8f, 20f, 8.8f, 20f, 10f)
                lineTo(20f, 19f); curveTo(20f, 20.2f, 19.2f, 21f, 18f, 21f)
                lineTo(9f, 21f); curveTo(7.8f, 21f, 7f, 20.2f, 7f, 19f)
                lineTo(7f, 10f); curveTo(7f, 8.8f, 7.8f, 8f, 9f, 8f); close()
                moveTo(15f, 4f); lineTo(6f, 4f); curveTo(4.8f, 4f, 4f, 4.8f, 4f, 6f); lineTo(4f, 16f)
            }
        }.build()
    }

    val Edit: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Edit", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 16f); lineTo(15.5f, 4.5f); curveTo(17.5f, 2.5f, 21.5f, 6.5f, 19.5f, 8.5f)
                lineTo(8f, 20f); lineTo(3f, 21f); lineTo(4f, 16f); close()
                moveTo(13.5f, 6.5f); lineTo(17.5f, 10.5f)
            }
        }.build()
    }

    val Branch: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Branch", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(6f, 8f); lineTo(6f, 16f)
                moveTo(6f, 13f); curveTo(6f, 9f, 18f, 14f, 18f, 8f)
                moveTo(6f, 3f); curveTo(9.3f, 3f, 9.3f, 8f, 6f, 8f); curveTo(2.7f, 8f, 2.7f, 3f, 6f, 3f); close()
                moveTo(6f, 16f); curveTo(9.3f, 16f, 9.3f, 21f, 6f, 21f); curveTo(2.7f, 21f, 2.7f, 16f, 6f, 16f); close()
                moveTo(18f, 3f); curveTo(21.3f, 3f, 21.3f, 8f, 18f, 8f); curveTo(14.7f, 8f, 14.7f, 3f, 18f, 3f); close()
            }
        }.build()
    }

    val Delete: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Delete", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
                moveTo(6f, 6f); lineTo(7f, 19f); curveTo(7.1f, 20.2f, 8f, 21f, 9f, 21f)
                lineTo(15f, 21f); curveTo(16f, 21f, 16.9f, 20.2f, 17f, 19f); lineTo(18f, 6f)
                moveTo(10f, 10f); lineTo(10.5f, 17f); moveTo(14f, 10f); lineTo(13.5f, 17f)
            }
        }.build()
    }

    val Regenerate: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Regenerate", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(20f, 10f); curveTo(19f, 4f, 12f, 2f, 7f, 6f); lineTo(4f, 9f)
                moveTo(4f, 4f); lineTo(4f, 9f); lineTo(9f, 9f)
                moveTo(4f, 14f); curveTo(5f, 20f, 12f, 22f, 17f, 18f); lineTo(20f, 15f)
                moveTo(15f, 15f); lineTo(20f, 15f); lineTo(20f, 20f)
            }
        }.build()
    }

    val Speak: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Speak", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
                moveTo(16f, 9f); curveTo(18f, 10.5f, 18f, 13.5f, 16f, 15f)
                moveTo(18f, 5f); curveTo(22f, 8.5f, 22f, 15.5f, 18f, 19f)
            }
        }.build()
    }

    val Share: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Share", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 11f); lineTo(16f, 6.5f); moveTo(8f, 13f); lineTo(16f, 17.5f)
                moveTo(6f, 9.5f); curveTo(9.3f, 9.5f, 9.3f, 14.5f, 6f, 14.5f); curveTo(2.7f, 14.5f, 2.7f, 9.5f, 6f, 9.5f); close()
                moveTo(18f, 3f); curveTo(21.3f, 3f, 21.3f, 8f, 18f, 8f); curveTo(14.7f, 8f, 14.7f, 3f, 18f, 3f); close()
                moveTo(18f, 16f); curveTo(21.3f, 16f, 21.3f, 21f, 18f, 21f); curveTo(14.7f, 21f, 14.7f, 16f, 18f, 16f); close()
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Check", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
            }
        }.build()
    }

    val Error: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Error", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                moveTo(12f, 8f); lineTo(12f, 13f); moveTo(12f, 16.5f); lineTo(12f, 16.6f)
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.ChevronRight", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.ChevronDown", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f)
            }
        }.build()
    }

    val Back: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Back", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(20f, 12f); lineTo(4f, 12f); moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f)
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Close", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Search", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(10.5f, 3f); curveTo(14.6f, 3f, 18f, 6.4f, 18f, 10.5f)
                curveTo(18f, 14.6f, 14.6f, 18f, 10.5f, 18f); curveTo(6.4f, 18f, 3f, 14.6f, 3f, 10.5f)
                curveTo(3f, 6.4f, 6.4f, 3f, 10.5f, 3f); close()
                moveTo(16f, 16f); lineTo(21f, 21f)
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Settings", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 6f); lineTo(7f, 6f); moveTo(11f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(13f, 12f); moveTo(17f, 12f); lineTo(20f, 12f)
                moveTo(4f, 18f); lineTo(7f, 18f); moveTo(11f, 18f); lineTo(20f, 18f)
                moveTo(9f, 4f); curveTo(11.7f, 4f, 11.7f, 8f, 9f, 8f); curveTo(6.3f, 8f, 6.3f, 4f, 9f, 4f); close()
                moveTo(15f, 10f); curveTo(17.7f, 10f, 17.7f, 14f, 15f, 14f); curveTo(12.3f, 14f, 12.3f, 10f, 15f, 10f); close()
                moveTo(9f, 16f); curveTo(11.7f, 16f, 11.7f, 20f, 9f, 20f); curveTo(6.3f, 20f, 6.3f, 16f, 9f, 16f); close()
            }
        }.build()
    }

    val Model: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Model", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f)
                lineTo(4f, 16.5f); lineTo(4f, 7.5f); close()
                moveTo(4f, 7.5f); lineTo(12f, 12f); lineTo(20f, 7.5f)
                moveTo(12f, 12f); lineTo(12f, 21f); moveTo(8f, 5.3f); lineTo(16f, 9.8f)
            }
        }.build()
    }

    val Reasoning: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Reasoning", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 17f); curveTo(9f, 14f, 5.5f, 13.5f, 5.5f, 9.5f)
                curveTo(5.5f, 5.9f, 8.4f, 3f, 12f, 3f); curveTo(15.6f, 3f, 18.5f, 5.9f, 18.5f, 9.5f)
                curveTo(18.5f, 13.5f, 15f, 14f, 15f, 17f); close()
                moveTo(9.5f, 20.5f); lineTo(14.5f, 20.5f)
                moveTo(12f, 17f); lineTo(12f, 12f); moveTo(9.5f, 9.5f); lineTo(12f, 12f); lineTo(14.5f, 9.5f)
            }
        }.build()
    }

    val Assistant: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Assistant", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17.3f, 3f, 17.3f, 11f, 12f, 11f); curveTo(6.7f, 11f, 6.7f, 3f, 12f, 3f); close()
                moveTo(4f, 21f); lineTo(4f, 19f); curveTo(4f, 12f, 20f, 12f, 20f, 19f); lineTo(20f, 21f)
            }
        }.build()
    }

    val Context: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Context", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
                moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f)
                moveTo(3f, 16.5f); lineTo(12f, 21.5f); lineTo(21f, 16.5f)
            }
        }.build()
    }

    val SubAgent: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.SubAgent", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 8f); lineTo(12f, 11f); moveTo(6f, 16f); lineTo(6f, 13f); lineTo(18f, 13f); lineTo(18f, 16f)
                moveTo(12f, 11f); lineTo(12f, 13f)
                moveTo(12f, 3f); curveTo(15.3f, 3f, 15.3f, 8f, 12f, 8f); curveTo(8.7f, 8f, 8.7f, 3f, 12f, 3f); close()
                moveTo(6f, 16f); curveTo(9.3f, 16f, 9.3f, 21f, 6f, 21f); curveTo(2.7f, 21f, 2.7f, 16f, 6f, 16f); close()
                moveTo(18f, 16f); curveTo(21.3f, 16f, 21.3f, 21f, 18f, 21f); curveTo(14.7f, 21f, 14.7f, 16f, 18f, 16f); close()
            }
        }.build()
    }

    val Tool: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Tool", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(14f, 4f); curveTo(11f, 5f, 10f, 8f, 11.5f, 11f)
                lineTo(4f, 18.5f); curveTo(2f, 20.5f, 4f, 22f, 6f, 20f)
                lineTo(13.5f, 12.5f); curveTo(18f, 14f, 21f, 11f, 20f, 7f)
                lineTo(16.5f, 10f); lineTo(13f, 6.5f); lineTo(16f, 3.5f); curveTo(15.3f, 3.5f, 14.7f, 3.7f, 14f, 4f); close()
            }
        }.build()
    }

    val Retry: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Retry", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 9f); curveTo(6f, 3f, 14f, 1.5f, 18.5f, 7f)
                curveTo(23f, 12.5f, 18.5f, 21f, 12f, 21f); curveTo(8f, 21f, 5f, 18.5f, 4f, 15f)
                moveTo(4f, 4f); lineTo(4f, 9f); lineTo(9f, 9f)
            }
        }.build()
    }

    val JumpUp: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.JumpUp", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(19f, 4f); moveTo(12f, 20f); lineTo(12f, 8f)
                moveTo(6f, 14f); lineTo(12f, 8f); lineTo(18f, 14f)
            }
        }.build()
    }

    val Lightning: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Lightning", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(14f, 3f); lineTo(5f, 13f); lineTo(11f, 13f); lineTo(10f, 21f)
                lineTo(19f, 10f); lineTo(13f, 10f); close()
            }
        }.build()
    }

    val Compress: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Compress", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 10f); lineTo(20f, 10f)
                moveTo(4f, 14f); lineTo(20f, 14f)
                moveTo(12f, 2f); lineTo(12f, 7f)
                moveTo(9f, 4f); lineTo(12f, 7f); lineTo(15f, 4f)
                moveTo(12f, 22f); lineTo(12f, 17f)
                moveTo(9f, 20f); lineTo(12f, 17f); lineTo(15f, 20f)
            }
        }.build()
    }

    val Id: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Id", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 5f); lineTo(19f, 5f); curveTo(20.2f, 5f, 21f, 5.8f, 21f, 7f)
                lineTo(21f, 17f); curveTo(21f, 18.2f, 20.2f, 19f, 19f, 19f)
                lineTo(5f, 19f); curveTo(3.8f, 19f, 3f, 18.2f, 3f, 17f)
                lineTo(3f, 7f); curveTo(3f, 5.8f, 3.8f, 5f, 5f, 5f); close()
                moveTo(8f, 8f); curveTo(10.7f, 8f, 10.7f, 12f, 8f, 12f); curveTo(5.3f, 12f, 5.3f, 8f, 8f, 8f); close()
                moveTo(5.5f, 16f); curveTo(5.5f, 12.5f, 10.5f, 12.5f, 10.5f, 16f)
                moveTo(14f, 10f); lineTo(18f, 10f); moveTo(14f, 14f); lineTo(17f, 14f)
            }
        }.build()
    }

    val Export: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Export", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 15f); lineTo(12f, 3f); moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
                moveTo(4f, 13f); lineTo(4f, 19f); curveTo(4f, 20.2f, 4.8f, 21f, 6f, 21f)
                lineTo(18f, 21f); curveTo(19.2f, 21f, 20f, 20.2f, 20f, 19f); lineTo(20f, 13f)
            }
        }.build()
    }

    val Rename: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Rename", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 4f); lineTo(8f, 20f); moveTo(5f, 4f); lineTo(11f, 4f); moveTo(5f, 20f); lineTo(11f, 20f)
                moveTo(5f, 8f); lineTo(3f, 8f); lineTo(3f, 16f); lineTo(5f, 16f)
                moveTo(11f, 8f); lineTo(19f, 8f); curveTo(20.2f, 8f, 21f, 8.8f, 21f, 10f)
                lineTo(21f, 14f); curveTo(21f, 15.2f, 20.2f, 16f, 19f, 16f); lineTo(11f, 16f)
                moveTo(13f, 12f); lineTo(17f, 12f)
            }
        }.build()
    }

    val NewChat: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.NewChat", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(19f, 12f); lineTo(19f, 16f); curveTo(19f, 17.2f, 18.2f, 18f, 17f, 18f)
                lineTo(8f, 18f); lineTo(3f, 21f); lineTo(3f, 6f); curveTo(3f, 4.8f, 3.8f, 4f, 5f, 4f); lineTo(12f, 4f)
                moveTo(18f, 3f); lineTo(18f, 9f); moveTo(15f, 6f); lineTo(21f, 6f)
            }
        }.build()
    }

    val Interactive: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Interactive", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 5f); lineTo(13f, 5f); curveTo(14.2f, 5f, 15f, 5.8f, 15f, 7f)
                lineTo(15f, 11f); curveTo(15f, 12.2f, 14.2f, 13f, 13f, 13f); lineTo(7f, 13f); lineTo(3f, 16f); lineTo(3f, 6f); curveTo(3f, 5.4f, 3.4f, 5f, 4f, 5f); close()
                moveTo(18f, 9f); lineTo(19f, 9f); curveTo(20.2f, 9f, 21f, 9.8f, 21f, 11f)
                lineTo(21f, 21f); lineTo(17f, 18f); lineTo(11f, 18f); curveTo(9.8f, 18f, 9f, 17.2f, 9f, 16f)
                moveTo(6f, 9f); lineTo(11f, 9f)
            }
        }.build()
    }

    val Paint: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Paint", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(10f, 13f); lineTo(17.5f, 3.5f); curveTo(18.5f, 2.5f, 21.5f, 5.5f, 20.5f, 6.5f)
                lineTo(12f, 15f); close()
                moveTo(10f, 13f); curveTo(5f, 12f, 6f, 18f, 3f, 20f)
                curveTo(8f, 22f, 13f, 19f, 12f, 15f)
            }
        }.build()
    }

    val Movie: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Movie", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(3f, 9f); lineTo(21f, 9f); lineTo(21f, 18f); curveTo(21f, 19.2f, 20.2f, 20f, 19f, 20f)
                lineTo(5f, 20f); curveTo(3.8f, 20f, 3f, 19.2f, 3f, 18f); close()
                moveTo(3f, 9f); lineTo(3f, 5f); curveTo(3f, 4.4f, 3.4f, 4f, 4f, 4f); lineTo(20f, 4f); lineTo(21f, 9f)
                moveTo(7f, 4f); lineTo(10f, 9f); moveTo(14f, 4f); lineTo(17f, 9f)
                moveTo(10f, 12f); lineTo(15f, 14.5f); lineTo(10f, 17f); close()
            }
        }.build()
    }

    val Terminal: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Terminal", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(19f, 4f); curveTo(20.2f, 4f, 21f, 4.8f, 21f, 6f)
                lineTo(21f, 18f); curveTo(21f, 19.2f, 20.2f, 20f, 19f, 20f)
                lineTo(5f, 20f); curveTo(3.8f, 20f, 3f, 19.2f, 3f, 18f)
                lineTo(3f, 6f); curveTo(3f, 4.8f, 3.8f, 4f, 5f, 4f); close()
                moveTo(7f, 8f); lineTo(11f, 12f); lineTo(7f, 16f); moveTo(13f, 16f); lineTo(17f, 16f)
            }
        }.build()
    }

    val Browser: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Browser", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(19f, 4f); curveTo(20.2f, 4f, 21f, 4.8f, 21f, 6f)
                lineTo(21f, 18f); curveTo(21f, 19.2f, 20.2f, 20f, 19f, 20f)
                lineTo(5f, 20f); curveTo(3.8f, 20f, 3f, 19.2f, 3f, 18f)
                lineTo(3f, 6f); curveTo(3f, 4.8f, 3.8f, 4f, 5f, 4f); close()
                moveTo(3f, 9f); lineTo(21f, 9f); moveTo(6f, 6.5f); lineTo(6.1f, 6.5f)
                moveTo(9f, 6.5f); lineTo(9.1f, 6.5f); moveTo(12f, 6.5f); lineTo(18f, 6.5f)
                moveTo(8f, 14f); lineTo(11f, 17f); lineTo(16f, 12f)
            }
        }.build()
    }

    val Device: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Device", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 3f); lineTo(16f, 3f); curveTo(17.2f, 3f, 18f, 3.8f, 18f, 5f)
                lineTo(18f, 19f); curveTo(18f, 20.2f, 17.2f, 21f, 16f, 21f)
                lineTo(8f, 21f); curveTo(6.8f, 21f, 6f, 20.2f, 6f, 19f)
                lineTo(6f, 5f); curveTo(6f, 3.8f, 6.8f, 3f, 8f, 3f); close()
                moveTo(10f, 6f); lineTo(14f, 6f); moveTo(11f, 18f); lineTo(13f, 18f)
            }
        }.build()
    }

    val Accessibility: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Accessibility", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(14.7f, 3f, 14.7f, 7f, 12f, 7f); curveTo(9.3f, 7f, 9.3f, 3f, 12f, 3f); close()
                moveTo(4f, 9f); curveTo(9f, 11f, 15f, 11f, 20f, 9f)
                moveTo(9f, 10f); lineTo(9f, 14f); lineTo(6f, 21f)
                moveTo(15f, 10f); lineTo(15f, 14f); lineTo(18f, 21f)
                moveTo(9f, 14f); lineTo(15f, 14f)
            }
        }.build()
    }

    val VirtualScreen: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.VirtualScreen", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 4f); lineTo(5f, 4f); curveTo(3.8f, 4f, 3f, 4.8f, 3f, 6f); lineTo(3f, 9f)
                moveTo(15f, 4f); lineTo(19f, 4f); curveTo(20.2f, 4f, 21f, 4.8f, 21f, 6f); lineTo(21f, 9f)
                moveTo(3f, 13f); lineTo(3f, 16f); curveTo(3f, 17.2f, 3.8f, 18f, 5f, 18f); lineTo(19f, 18f)
                curveTo(20.2f, 18f, 21f, 17.2f, 21f, 16f); lineTo(21f, 13f)
                moveTo(9f, 21f); lineTo(15f, 21f); moveTo(12f, 18f); lineTo(12f, 21f)
                moveTo(10f, 8f); lineTo(16f, 11f); lineTo(10f, 14f); close()
            }
        }.build()
    }

    val Backup: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Backup", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(7f, 18f); curveTo(4.8f, 18f, 3f, 16.2f, 3f, 14f)
                curveTo(3f, 11.8f, 4.8f, 10f, 7f, 10f)
                curveTo(6f, 3f, 17f, 3f, 18f, 10f); curveTo(21f, 10f, 21f, 18f, 17f, 18f)
                moveTo(12f, 21f); lineTo(12f, 12f); moveTo(8.5f, 15.5f); lineTo(12f, 12f); lineTo(15.5f, 15.5f)
            }
        }.build()
    }

    val Skills: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Skills", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(9f, 4f); curveTo(9.6f, 4f, 10f, 4.4f, 10f, 5f)
                lineTo(10f, 9f); curveTo(10f, 9.6f, 9.6f, 10f, 9f, 10f)
                lineTo(5f, 10f); curveTo(4.4f, 10f, 4f, 9.6f, 4f, 9f)
                lineTo(4f, 5f); curveTo(4f, 4.4f, 4.4f, 4f, 5f, 4f); close()
                moveTo(15f, 14f); lineTo(19f, 14f); curveTo(19.6f, 14f, 20f, 14.4f, 20f, 15f)
                lineTo(20f, 19f); curveTo(20f, 19.6f, 19.6f, 20f, 19f, 20f)
                lineTo(15f, 20f); curveTo(14.4f, 20f, 14f, 19.6f, 14f, 19f)
                lineTo(14f, 15f); curveTo(14f, 14.4f, 14.4f, 14f, 15f, 14f); close()
                moveTo(14f, 7f); lineTo(20f, 7f); moveTo(17f, 4f); lineTo(17f, 10f)
                moveTo(4f, 17f); lineTo(10f, 17f); moveTo(7f, 14f); lineTo(7f, 20f)
            }
        }.build()
    }

    val Call: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Call", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(6.2f, 3.5f); lineTo(9.4f, 7.4f)
                curveTo(9.9f, 8f, 9.8f, 8.8f, 9.3f, 9.3f); lineTo(7.7f, 10.9f)
                curveTo(8.9f, 13.2f, 10.8f, 15.1f, 13.1f, 16.3f); lineTo(14.7f, 14.7f)
                curveTo(15.2f, 14.2f, 16f, 14.1f, 16.6f, 14.6f); lineTo(20.5f, 17.8f)
                curveTo(21.1f, 18.3f, 21.2f, 19.2f, 20.6f, 19.8f); lineTo(19.5f, 20.9f)
                curveTo(18.9f, 21.5f, 18f, 21.7f, 17.2f, 21.4f)
                curveTo(10.3f, 19.1f, 4.9f, 13.7f, 2.6f, 6.8f)
                curveTo(2.3f, 6f, 2.5f, 5.1f, 3.1f, 4.5f); lineTo(4.2f, 3.4f)
                curveTo(4.8f, 2.8f, 5.7f, 2.9f, 6.2f, 3.5f); close()
            }
        }.build()
    }

    val Voice: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Voice", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 6f); curveTo(9f, 2f, 15f, 2f, 15f, 6f); lineTo(15f, 12f)
                curveTo(15f, 16f, 9f, 16f, 9f, 12f); close()
                moveTo(5f, 11f); lineTo(5f, 12f); curveTo(5f, 21f, 19f, 21f, 19f, 12f); lineTo(19f, 11f)
                moveTo(12f, 19f); lineTo(12f, 21f); moveTo(9f, 21f); lineTo(15f, 21f)
            }
        }.build()
    }

    val Palette: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Palette", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17f, 3f, 21f, 6.5f, 21f, 11f)
                curveTo(21f, 14f, 18f, 14f, 16f, 14f); curveTo(12f, 14f, 17f, 19f, 13f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
                moveTo(7f, 11f); lineTo(7.1f, 11f); moveTo(10f, 7f); lineTo(10.1f, 7f)
                moveTo(15f, 7f); lineTo(15.1f, 7f); moveTo(18f, 10f); lineTo(18.1f, 10f)
            }
        }.build()
    }

    val Vibration: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Vibration", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 5f); lineTo(15f, 5f); curveTo(15.6f, 5f, 16f, 5.4f, 16f, 6f)
                lineTo(16f, 18f); curveTo(16f, 18.6f, 15.6f, 19f, 15f, 19f)
                lineTo(9f, 19f); curveTo(8.4f, 19f, 8f, 18.6f, 8f, 18f)
                lineTo(8f, 6f); curveTo(8f, 5.4f, 8.4f, 5f, 9f, 5f); close()
                moveTo(5f, 8f); lineTo(3f, 10f); lineTo(5f, 12f); lineTo(3f, 14f); lineTo(5f, 16f)
                moveTo(19f, 8f); lineTo(21f, 10f); lineTo(19f, 12f); lineTo(21f, 14f); lineTo(19f, 16f)
                moveTo(11f, 16f); lineTo(13f, 16f)
            }
        }.build()
    }

    val Bell: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Bell", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 4f); curveTo(7f, 4f, 6f, 7f, 6f, 11f)
                curveTo(6f, 14f, 5f, 15f, 4f, 17f); lineTo(20f, 17f)
                curveTo(19f, 15f, 18f, 14f, 18f, 11f); curveTo(18f, 7f, 17f, 4f, 12f, 4f); close()
                moveTo(12f, 3f); lineTo(12f, 4f); moveTo(9f, 20f); curveTo(10.5f, 21.5f, 13.5f, 21.5f, 15f, 20f)
            }
        }.build()
    }

    val Folder: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Folder", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 5f); lineTo(9f, 5f); lineTo(12f, 8f); lineTo(19f, 8f)
                curveTo(20.2f, 8f, 21f, 8.8f, 21f, 10f); lineTo(21f, 18f)
                curveTo(21f, 19.2f, 20.2f, 20f, 19f, 20f); lineTo(5f, 20f)
                curveTo(3.8f, 20f, 3f, 19.2f, 3f, 18f); lineTo(3f, 7f)
                curveTo(3f, 5.8f, 3.8f, 5f, 5f, 5f); close()
                moveTo(3f, 11f); lineTo(21f, 11f)
            }
        }.build()
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Pin", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 3f); lineTo(16f, 3f); lineTo(15f, 10f); lineTo(19f, 14f)
                lineTo(5f, 14f); lineTo(9f, 10f); close()
                moveTo(12f, 14f); lineTo(12f, 21f)
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Info", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
                moveTo(12f, 7.5f); lineTo(12f, 7.6f); moveTo(10f, 11f); lineTo(12f, 11f); lineTo(12f, 17f)
                moveTo(10f, 17f); lineTo(14f, 17f)
            }
        }.build()
    }

    val Key: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Key", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(11.5f, 10.2f); curveTo(10f, 7f, 12f, 3f, 16f, 3f)
                curveTo(18.8f, 3f, 21f, 5.2f, 21f, 8f); curveTo(21f, 12f, 17f, 14f, 13.8f, 12.5f)
                lineTo(10f, 16.3f); lineTo(10f, 19f); lineTo(7.3f, 19f); lineTo(5.3f, 21f)
                lineTo(3f, 21f); lineTo(3f, 18.7f); close()
                moveTo(17f, 6f); curveTo(19f, 6f, 19f, 9f, 17f, 9f); curveTo(15f, 9f, 15f, 6f, 17f, 6f); close()
            }
        }.build()
    }

    val Globe: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Globe", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
                moveTo(12f, 3f); curveTo(17f, 7f, 17f, 17f, 12f, 21f); curveTo(7f, 17f, 7f, 7f, 12f, 3f)
                moveTo(3f, 12f); lineTo(21f, 12f)
            }
        }.build()
    }

    val Shield: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Shield", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f)
                curveTo(20f, 16f, 16f, 19f, 12f, 21f); curveTo(8f, 19f, 4f, 16f, 4f, 12f)
                lineTo(4f, 6f); close()
                moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
            }
        }.build()
    }

    val Chart: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Chart", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 3f); lineTo(4f, 20f); lineTo(21f, 20f)
                moveTo(8f, 16f); lineTo(8f, 11f); moveTo(13f, 16f); lineTo(13f, 7f)
                moveTo(18f, 16f); lineTo(18f, 4f)
            }
        }.build()
    }

    val Provider: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Provider", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 4f); lineTo(19f, 4f); curveTo(20.2f, 4f, 21f, 4.8f, 21f, 6f)
                lineTo(21f, 9f); curveTo(21f, 10.2f, 20.2f, 11f, 19f, 11f)
                lineTo(5f, 11f); curveTo(3.8f, 11f, 3f, 10.2f, 3f, 9f)
                lineTo(3f, 6f); curveTo(3f, 4.8f, 3.8f, 4f, 5f, 4f); close()
                moveTo(5f, 14f); lineTo(19f, 14f); curveTo(20.2f, 14f, 21f, 14.8f, 21f, 16f)
                lineTo(21f, 19f); curveTo(21f, 20.2f, 20.2f, 21f, 19f, 21f)
                lineTo(5f, 21f); curveTo(3.8f, 21f, 3f, 20.2f, 3f, 19f)
                lineTo(3f, 16f); curveTo(3f, 14.8f, 3.8f, 14f, 5f, 14f); close()
                moveTo(7f, 7.5f); lineTo(7.1f, 7.5f); moveTo(12f, 7.5f); lineTo(17f, 7.5f)
                moveTo(7f, 17.5f); lineTo(7.1f, 17.5f); moveTo(12f, 17.5f); lineTo(17f, 17.5f)
            }
        }.build()
    }

    val Sponsor: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Sponsor", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 20.5f)
                curveTo(9.8f, 18.4f, 3f, 13.3f, 3f, 8.5f)
                curveTo(3f, 3.2f, 9.4f, 2.3f, 12f, 6.5f)
                curveTo(14.6f, 2.3f, 21f, 3.2f, 21f, 8.5f)
                curveTo(21f, 13.3f, 14.2f, 18.4f, 12f, 20.5f)
                close()
            }
        }.build()
    }

    val Balance: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Balance", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(12f, 20f); moveTo(8f, 21f); lineTo(16f, 21f)
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(6f, 6f); lineTo(3f, 13f); lineTo(9f, 13f); close()
                moveTo(3f, 13f); curveTo(3f, 17f, 9f, 17f, 9f, 13f)
                moveTo(18f, 6f); lineTo(15f, 13f); lineTo(21f, 13f); close()
                moveTo(15f, 13f); curveTo(15f, 17f, 21f, 17f, 21f, 13f)
            }
        }.build()
    }

    val Gateway: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Gateway", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 21f); lineTo(5f, 5f); curveTo(5f, 3.8f, 5.8f, 3f, 7f, 3f)
                lineTo(17f, 3f); curveTo(18.2f, 3f, 19f, 3.8f, 19f, 5f); lineTo(19f, 8f)
                moveTo(19f, 17f); lineTo(19f, 21f); moveTo(3f, 21f); lineTo(8f, 21f)
                moveTo(11f, 12f); lineTo(21f, 12f); moveTo(17f, 8f); lineTo(21f, 12f); lineTo(17f, 16f)
            }
        }.build()
    }

    val Vision: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Vision", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(3f, 12f); curveTo(7f, 3f, 17f, 3f, 21f, 12f)
                curveTo(17f, 21f, 7f, 21f, 3f, 12f); close()
                moveTo(12f, 8.5f); curveTo(16.7f, 8.5f, 16.7f, 15.5f, 12f, 15.5f)
                curveTo(7.3f, 15.5f, 7.3f, 8.5f, 12f, 8.5f); close()
            }
        }.build()
    }

    val Reconnect: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Reconnect", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(9f, 3f); lineTo(9f, 7f); moveTo(15f, 3f); lineTo(15f, 7f)
                moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 11f); curveTo(18f, 14.3f, 15.3f, 17f, 12f, 17f)
                curveTo(8.7f, 17f, 6f, 14.3f, 6f, 11f); close()
                moveTo(12f, 17f); lineTo(12f, 21f)
            }
        }.build()
    }

    val Title: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Title", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 8f); lineTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 8f)
                moveTo(12f, 4f); lineTo(12f, 20f); moveTo(8f, 20f); lineTo(16f, 20f)
            }
        }.build()
    }

    val Pet: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Pet", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(5f, 10f); lineTo(5f, 4f); lineTo(9f, 7.5f); curveTo(10.6f, 7f, 13.4f, 7f, 15f, 7.5f)
                lineTo(19f, 4f); lineTo(19f, 10f); curveTo(20.5f, 12f, 20.5f, 15f, 19f, 17f)
                curveTo(17f, 20.5f, 7f, 20.5f, 5f, 17f); curveTo(3.5f, 15f, 3.5f, 12f, 5f, 10f); close()
            }
        }.build()
    }

    val AppIcon: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.AppIcon", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(8f, 3f); lineTo(16f, 3f); curveTo(19.5f, 3f, 21f, 4.5f, 21f, 8f)
                lineTo(21f, 16f); curveTo(21f, 19.5f, 19.5f, 21f, 16f, 21f)
                lineTo(8f, 21f); curveTo(4.5f, 21f, 3f, 19.5f, 3f, 16f)
                lineTo(3f, 8f); curveTo(3f, 4.5f, 4.5f, 3f, 8f, 3f); close()
                moveTo(6f, 12f); curveTo(9f, 7f, 14f, 7f, 17f, 12f)
                curveTo(14f, 17f, 9f, 17f, 6f, 12f); close()
                moveTo(17f, 12f); lineTo(18f, 9f); moveTo(17f, 12f); lineTo(18f, 15f)
                moveTo(10f, 11f); lineTo(10.1f, 11f)
            }
        }.build()
    }

    val Timestamp: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Timestamp", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
                moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Refresh", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(20f, 9f); curveTo(18.6f, 5.5f, 15.5f, 3f, 12f, 3f)
                curveTo(7f, 3f, 3f, 7f, 3f, 12f); curveTo(3f, 17f, 7f, 21f, 12f, 21f)
                curveTo(15.5f, 21f, 18.6f, 19f, 20f, 16f)
                moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f)
            }
        }.build()
    }

    val Locate: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Locate", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 5f); curveTo(21.3f, 5f, 21.3f, 19f, 12f, 19f); curveTo(2.7f, 19f, 2.7f, 5f, 12f, 5f); close()
                moveTo(12f, 3f); lineTo(12f, 7f); moveTo(12f, 17f); lineTo(12f, 21f)
                moveTo(3f, 12f); lineTo(7f, 12f); moveTo(17f, 12f); lineTo(21f, 12f)
                moveTo(12f, 10f); curveTo(14.7f, 10f, 14.7f, 14f, 12f, 14f); curveTo(9.3f, 14f, 9.3f, 10f, 12f, 10f); close()
            }
        }.build()
    }

    val Import: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Import", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                moveTo(4f, 13f); lineTo(4f, 19f); curveTo(4f, 20.2f, 4.8f, 21f, 6f, 21f)
                lineTo(18f, 21f); curveTo(19.2f, 21f, 20f, 20.2f, 20f, 19f); lineTo(20f, 13f)
            }
        }.build()
    }

    val Help: ImageVector by lazy {
        ImageVector.Builder(name = "Daiyu.Help", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
                curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
                moveTo(9f, 8.5f); curveTo(9f, 5f, 15f, 5f, 15f, 8.5f)
                curveTo(15f, 11f, 12f, 11f, 12f, 14f)
                moveTo(12f, 17f); lineTo(12f, 17.1f)
            }
        }.build()
    }
}
