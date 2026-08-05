package go.examate.hyp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 笔记图标（自定义，避免引入 material-icons-extended）
 *
 * 一张带折角的纸 + 横线文字，呼应 NotesActivity 的笔记语义。
 */
val IconNote: ImageVector
    get() = ImageVector.Builder(
        name = "Note",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // 纸张主体（左上+左下+底部+右下折角+折角斜线+右上）
            moveTo(5f, 2f)
            lineTo(15f, 2f)
            lineTo(21f, 8f)
            lineTo(21f, 20f)
            arcTo(2f, 2f, 0f, false, true, 19f, 22f)
            lineTo(5f, 22f)
            arcTo(2f, 2f, 0f, false, true, 3f, 20f)
            lineTo(3f, 4f)
            arcTo(2f, 2f, 0f, false, true, 5f, 2f)
            close()
            // 右上折角（小三角，形成"卷角"视觉）
            moveTo(15f, 2f)
            lineTo(15f, 6f)
            arcTo(2f, 2f, 0f, false, false, 17f, 8f)
            lineTo(21f, 8f)
            close()
            // 文字横线（三条）
            moveTo(6f, 12f)
            lineTo(18f, 12f)
            lineTo(18f, 13.6f)
            lineTo(6f, 13.6f)
            close()
            moveTo(6f, 15.2f)
            lineTo(18f, 15.2f)
            lineTo(18f, 16.8f)
            lineTo(6f, 16.8f)
            close()
            moveTo(6f, 18.4f)
            lineTo(14f, 18.4f)
            lineTo(14f, 20f)
            lineTo(6f, 20f)
            close()
        }
    }.build()
