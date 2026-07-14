package go.examate.hyp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 液态玻璃悬浮图标按钮 — 参考 AndroidLiquidGlass-2.0.0 的 LiquidButton 样式,
 * 使用 Capsule 形状 + drawBackdrop + vibrancy + blur 实现。
 * 当无 backdrop 时回退为半透明白底圆角。
 */
@Composable
fun LiquidGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    surfaceAlpha: Float = 0.5f
) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        modifier
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(12f)
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(Color.White.copy(surfaceAlpha)) }
                    )
                } else {
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.88f))
                }
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}
