package go.examate.hyp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import go.examate.hyp.ui.theme.GlassBorder
import go.examate.hyp.ui.theme.GlassHighlight
import go.examate.hyp.ui.theme.GlassShadow

/**
 * 毛玻璃卡片（用于承载内容，文字清晰）
 *
 * 多层静态效果模拟毛玻璃：
 * 1. 柔和阴影
 * 2. 半透明主体渐变
 * 3. 顶部高光
 * 4. 玻璃边框
 *
 * 不使用 RenderEffect，避免模糊内容文字。
 */
@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.White.copy(alpha = 0.55f),
    elevation: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = GlassShadow,
                spotColor = GlassShadow
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.8f),
                        tint.copy(alpha = 0.6f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, Float.POSITIVE_INFINITY)
                )
            )
            .drawBehind {
                // 顶部高光
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            GlassHighlight.copy(alpha = 0.5f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height * 0.4f)
                    )
                )
            }
            .border(BorderStroke(1.dp, GlassBorder), shape),
        content = content
    )
}
