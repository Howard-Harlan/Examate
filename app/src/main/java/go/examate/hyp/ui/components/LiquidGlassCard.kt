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
 * 液态玻璃容器（强效果，用于按钮/开关等小控件）
 *
 * 比 FrostedGlassCard 更强的视觉表现：
 * 1. 更强阴影
 * 2. 主体渐变 + 顶部高光 + 底部反射 + 对角高光
 * 3. 内外双描边（玻璃边缘）
 *
 * 不使用 RenderEffect，避免模糊内容。
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 999.dp,
    tint: Color = Color.White.copy(alpha = 0.55f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = GlassShadow,
                spotColor = GlassShadow
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.85f),
                        tint.copy(alpha = 0.65f),
                        tint.copy(alpha = 0.75f)
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
                            GlassHighlight.copy(alpha = 0.6f),
                            GlassHighlight.copy(alpha = 0.2f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height * 0.4f)
                    )
                )
                // 底部反射
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.15f)
                        ),
                        start = Offset(0f, size.height * 0.6f),
                        end = Offset(0f, size.height)
                    )
                )
                // 对角高光（模拟光源）
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    )
                )
            }
            // 外层亮边
            .border(BorderStroke(1.dp, GlassBorder), shape),
        content = content
    )
}
