package go.examate.hyp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.components.glass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** 按钮视觉风格 */
enum class ButtonStyle {
    /** 深色实心(原 primary) */
    Primary,
    /** 白色实心 + 浅灰边框 + 深色文字 */
    White,
    /** 半透明玻璃(原 outline) */
    Outline
}

/**
 * 液态玻璃按钮(完全移植自 backdrop 库 demo 的 LiquidButton)
 *
 * 特性:
 * - drawBackdrop 真玻璃: vibrancy + blur(2dp) + lens(12,24)
 * - InteractiveHighlight: 按下时跟随手指的光斑
 * - layerBlock: tanh 偏移 + 按角度缩放(手指拖拽时形变)
 * - onDrawSurface: tint(BlendMode.Hue) + surfaceColor
 *
 * 通过 LocalGlassBackdrop 获取 backdrop(由 LiquidGlassContainer 提供)
 */
@Composable
fun LiquidGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    height: Dp = 50.dp
) {
    val backdrop = LocalGlassBackdrop.current
        ?: error("LiquidGlassButton 必须在 LiquidGlassContainer 内使用")
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    val textColor = when (style) {
        ButtonStyle.Primary -> Color.White
        ButtonStyle.White -> Color(0xFF1A1A2E)
        ButtonStyle.Outline -> Color(0xFF1A1A2E)
    }
    val tint = when (style) {
        ButtonStyle.Primary -> Color(0xFF1A1A2E)
        ButtonStyle.White -> Color.Unspecified
        ButtonStyle.Outline -> Color.Unspecified
    }
    val surfaceColor = when (style) {
        ButtonStyle.Primary -> Color.Unspecified
        ButtonStyle.White -> Color.White.copy(0.5f)
        ButtonStyle.Outline -> Color.White.copy(0.25f)
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = {
                    val width = size.width
                    val height = size.height

                    val progress = interactiveHighlight.pressProgress
                    val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                    val maxOffset = size.minDimension
                    val initialDerivative = 0.05f
                    val offset = interactiveHighlight.offset
                    translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                    translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                    val maxDragScale = 4f.dp.toPx() / size.height
                    val offsetAngle = atan2(offset.y, offset.x)
                    scaleX = scale +
                            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                            (width / height).fastCoerceAtMost(1f)
                    scaleY = scale +
                            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                            (height / width).fastCoerceAtMost(1f)
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}

