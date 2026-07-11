package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 学士帽图标（自定义，避免引入 material-icons-extended）
 */
val IconSchool: ImageVector
    get() = ImageVector.Builder(
        name = "School",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 简化的学士帽：菱形顶板 + 流苏
            moveTo(12f, 3f)
            lineTo(1f, 9f)
            lineTo(12f, 15f)
            lineTo(21f, 10.5f)
            lineTo(21f, 17f)
            lineTo(22f, 17f)
            lineTo(22f, 9f)
            close()
            moveTo(5f, 13.18f)
            lineTo(5f, 17f)
            lineTo(12f, 21f)
            lineTo(19f, 17f)
            lineTo(19f, 13.18f)
            lineTo(12f, 17f)
            close()
        }
    }.build()
