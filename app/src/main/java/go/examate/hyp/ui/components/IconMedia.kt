package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 阅读图标(打开的书,自定义,避免引入 material-icons-extended)
 */
val IconBook: ImageVector
    get() = ImageVector.Builder(
        name = "Book",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 打开的书:左右两页
            moveTo(12f, 5.5f)
            curveTo(10.2f, 4.3f, 7.8f, 3.8f, 5f, 3.8f)
            curveTo(4.4f, 3.8f, 3.8f, 3.85f, 3.2f, 3.95f)
            curveTo(2.9f, 4f, 2.7f, 4.25f, 2.7f, 4.55f)
            lineTo(2.7f, 18.2f)
            curveTo(2.7f, 18.6f, 3.05f, 18.9f, 3.45f, 18.82f)
            curveTo(4.0f, 18.72f, 4.5f, 18.68f, 5f, 18.68f)
            curveTo(7.3f, 18.68f, 9.75f, 19.1f, 12f, 20.2f)
            curveTo(14.25f, 19.1f, 16.7f, 18.68f, 19f, 18.68f)
            curveTo(19.5f, 18.68f, 20f, 18.72f, 20.55f, 18.82f)
            curveTo(20.95f, 18.9f, 21.3f, 18.6f, 21.3f, 18.2f)
            lineTo(21.3f, 4.55f)
            curveTo(21.3f, 4.25f, 21.1f, 4f, 20.8f, 3.95f)
            curveTo(20.2f, 3.85f, 19.6f, 3.8f, 19f, 3.8f)
            curveTo(16.2f, 3.8f, 13.8f, 4.3f, 12f, 5.5f)
            close()
            // 书脊留白(中缝)
            moveTo(12f, 7.3f)
            curveTo(13.6f, 6.4f, 15.7f, 6f, 19f, 6f)
            lineTo(19f, 16.9f)
            curveTo(16.3f, 16.9f, 14.0f, 17.3f, 12f, 18.1f)
            close()
        }
    }.build()

/**
 * 音乐图标(音符,自定义,避免引入 material-icons-extended)
 */
val IconMusic: ImageVector
    get() = ImageVector.Builder(
        name = "Music",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 双符头 + 符干 + 顶部符桥(八分音符对)
            moveTo(9f, 5f)
            lineTo(9f, 15.2f)
            curveTo(8.5f, 14.9f, 7.8f, 14.8f, 7.1f, 15f)
            curveTo(5.9f, 15.3f, 5.1f, 16.4f, 5.35f, 17.5f)
            curveTo(5.6f, 18.6f, 6.8f, 19.2f, 8f, 18.9f)
            curveTo(9f, 18.65f, 9.7f, 17.8f, 9.7f, 16.9f)
            lineTo(9.7f, 8.2f)
            lineTo(18f, 6.3f)
            lineTo(18f, 12.7f)
            curveTo(17.5f, 12.4f, 16.8f, 12.3f, 16.1f, 12.5f)
            curveTo(14.9f, 12.8f, 14.1f, 13.9f, 14.35f, 15f)
            curveTo(14.6f, 16.1f, 15.8f, 16.7f, 17f, 16.4f)
            curveTo(18f, 16.15f, 18.7f, 15.3f, 18.7f, 14.4f)
            lineTo(18.7f, 4.2f)
            close()
        }
    }.build()
