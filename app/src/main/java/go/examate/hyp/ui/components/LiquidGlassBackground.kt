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
    Box(modifier.fillMaxSize()) {
        // 背景层,被 bgBackdrop 捕获
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(BgPrimary)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(0.08f),
                            0.5f to Color.White.copy(0.02f),
                            1f to Color(0xFFE8EAF0).copy(0.35f)
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
