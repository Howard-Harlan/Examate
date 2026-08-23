package go.examate.hyp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import go.examate.hyp.ui.theme.BgGradientTint
import go.examate.hyp.ui.theme.BgPrimary

/**
 * 轻量液态玻璃背景(无底栏)
 *
 * 给独立 Activity(如 OnboardingActivity)用,提供 bgBackdrop 给按钮/开关。
 * LiquidGlassContainer 自带背景层,不需要这个。
 */
@Composable
fun LiquidGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val bgBackdrop = rememberLayerBackdrop()
    val bgColor = BgPrimary  // 在 @Composable 上下文读取,传入 drawBehind
    val bgGradient = BgGradientTint
    Box(modifier.fillMaxSize()) {
        // 背景层,被 bgBackdrop 捕获
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(bgColor)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(0.04f),
                            0.6f to Color.White.copy(0.03f),
                            1f to bgGradient.copy(0.18f)
                        )
                    )
                }
                .layerBackdrop(bgBackdrop)
        )
        // content,通过 LocalGlassBackdrop 获取 bgBackdrop
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalGlassBackdrop provides bgBackdrop) {
                content()
            }
        }
    }
}
