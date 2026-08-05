package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 下载图标（自定义，避免引入 material-icons-extended）
 */
val IconDownload: ImageVector
    get() = ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 向下箭头 + 底部托盘
            moveTo(19f, 9f)
            lineTo(15f, 9f)
            lineTo(15f, 3f)
            lineTo(9f, 3f)
            lineTo(9f, 9f)
            lineTo(5f, 9f)
            lineTo(12f, 16f)
            close()
            moveTo(5f, 18f)
            lineTo(5f, 20f)
            lineTo(19f, 20f)
            lineTo(19f, 18f)
            close()
        }
    }.build()
