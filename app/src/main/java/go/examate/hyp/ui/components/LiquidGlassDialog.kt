package go.examate.hyp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Primary

/**
 * 液态玻璃弹窗(参考 AndroidLiquidGlass-2.0.0 DialogContent)
 *
 * 使用 Dialog 独立 window,不会在调用方布局中撑开区域。
 * 遮罩层和弹窗体作为 Box 的兄弟节点:遮罩在下,弹窗在上(不会被遮罩盖住)。
 */
@Composable
fun LiquidGlassDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!visible) return
    val dimColor = Primary.copy(0.23f)
    val containerColor = CardBg.copy(0.6f)
    val fallbackColor = Color.White.copy(0.92f)
    val backdrop = LocalGlassBackdrop.current
    val noRippleInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(dimColor)
                .clickable(
                    interactionSource = noRippleInteraction,
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(initialScale = 0.92f) + fadeIn(),
                exit = scaleOut(targetScale = 0.92f) + fadeOut()
            ) {
                val innerNoRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(
                    Modifier
                        .widthIn(max = 360.dp)
                        .padding(32.dp)
                        .fillMaxWidth()
                        .then(
                            if (backdrop != null) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RoundedRectangle(48.dp) },
                                    effects = {
                                        vibrancy()
                                        blur(16.dp.toPx())
                                        lens(24.dp.toPx(), 48.dp.toPx(), depthEffect = true)
                                    },
                                    highlight = { Highlight.Plain },
                                    onDrawSurface = { drawRect(containerColor) }
                                )
                            } else {
                                Modifier
                                    .shadow(
                                        elevation = 24.dp,
                                        shape = RoundedCornerShape(32.dp),
                                        ambientColor = Color.Black.copy(0.18f),
                                        spotColor = Color.Black.copy(0.22f)
                                    )
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(fallbackColor)
                            }
                        )
                        .clickable(
                            interactionSource = innerNoRipple,
                            indication = null,
                            onClick = {} // 拦截点击,防止穿透到遮罩层关闭弹窗
                        )
                        .then(modifier)
                ) {
                    content()
                }
            }
        }
    }
}

/** 液态玻璃弹窗标题 */
@Composable
fun GlassDialogTitle(text: String) {
    Text(
        text = text,
        color = Color.Black,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(28.dp, 24.dp, 28.dp, 12.dp)
    )
}

/** 液态玻璃弹窗内容 */
@Composable
fun GlassDialogContent(text: String) {
    Text(
        text = text,
        color = Color.Black.copy(0.68f),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp)
    )
}

/** 液态玻璃弹窗按钮区 */
@Composable
fun GlassDialogButtons(
    buttons: List<Triple<String, Boolean, () -> Unit>>
) {
    val accentColor = AccentBlue
    val textColor = Color.Black

    Row(
        Modifier
            .padding(24.dp, 12.dp, 24.dp, 24.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEachIndexed { index, (text, isAccent, onClick) ->
            if (index > 0) Box(Modifier.padding(start = 12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isAccent) accentColor else Color.White.copy(0.2f))
                    .clickable { onClick() }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = if (isAccent) Color.White else textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
