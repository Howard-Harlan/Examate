package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 日历图标(自定义,避免引入 material-icons-extended)
 */
val IconCalendar: ImageVector
    get() = ImageVector.Builder(
        name = "Calendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 日历外框
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            arcTo(2f, 2f, 0f, false, true, 22f, 6f)
            lineTo(22f, 20f)
            arcTo(2f, 2f, 0f, false, true, 20f, 22f)
            lineTo(4f, 22f)
            arcTo(2f, 2f, 0f, false, true, 2f, 20f)
            lineTo(2f, 6f)
            arcTo(2f, 2f, 0f, false, true, 4f, 4f)
            close()
            // 顶部横条
            moveTo(2f, 8f)
            lineTo(22f, 8f)
            lineTo(22f, 10f)
            lineTo(2f, 10f)
            close()
            // 左侧挂环
            moveTo(7f, 2f)
            arcTo(1f, 1f, 0f, false, true, 8f, 3f)
            lineTo(8f, 5f)
            arcTo(1f, 1f, 0f, false, true, 6f, 5f)
            lineTo(6f, 3f)
            arcTo(1f, 1f, 0f, false, true, 7f, 2f)
            close()
            // 右侧挂环
            moveTo(17f, 2f)
            arcTo(1f, 1f, 0f, false, true, 18f, 3f)
            lineTo(18f, 5f)
            arcTo(1f, 1f, 0f, false, true, 16f, 5f)
            lineTo(16f, 3f)
            arcTo(1f, 1f, 0f, false, true, 17f, 2f)
            close()
            // 网格点
            moveTo(6f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 7.6f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 6f, 13f)
            close()
            moveTo(11f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 12.6f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 11f, 13f)
            close()
            moveTo(16f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 17.6f, 13f)
            arcTo(0.8f, 0.8f, 0f, false, true, 16f, 13f)
            close()
            moveTo(6f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 7.6f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 6f, 17f)
            close()
            moveTo(11f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 12.6f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 11f, 17f)
            close()
            moveTo(16f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 17.6f, 17f)
            arcTo(0.8f, 0.8f, 0f, false, true, 16f, 17f)
            close()
        }
    }.build()
