package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 跑步图标（自定义，避免引入 material-icons-extended）
 * 基于 Material Design DirectionsRun 路径简化。
 */
val IconRun: ImageVector
    get() = ImageVector.Builder(
        name = "Run",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 头部圆形
            moveTo(13.5f, 5.5f)
            curveTo(14.6f, 5.5f, 15.5f, 4.6f, 15.5f, 3.5f)
            curveTo(15.5f, 2.4f, 14.6f, 1.5f, 13.5f, 1.5f)
            curveTo(12.4f, 1.5f, 11.5f, 2.4f, 11.5f, 3.5f)
            curveTo(11.5f, 4.6f, 12.4f, 5.5f, 13.5f, 5.5f)
            close()
            // 身体 + 四肢
            moveTo(9.8f, 8.9f)
            lineTo(7f, 23f)
            lineTo(9.1f, 23f)
            lineTo(10.9f, 15f)
            lineTo(13f, 17f)
            lineTo(13f, 23f)
            lineTo(15f, 23f)
            lineTo(15f, 15.5f)
            lineTo(12.9f, 13.5f)
            lineTo(13.5f, 10.5f)
            curveTo(14.8f, 12f, 16.8f, 13f, 19f, 13f)
            lineTo(19f, 11f)
            curveTo(17.1f, 11f, 15.5f, 10f, 14.7f, 8.6f)
            lineTo(13.3f, 6.1f)
            curveTo(12.9f, 5.8f, 12.2f, 5.5f, 11.5f, 5.5f)
            curveTo(11.2f, 5.5f, 11f, 5.6f, 10.7f, 5.7f)
            lineTo(6f, 8.5f)
            lineTo(6f, 13f)
            lineTo(8f, 13f)
            lineTo(8f, 9.6f)
            lineTo(9.8f, 8.9f)
            close()
        }
    }.build()