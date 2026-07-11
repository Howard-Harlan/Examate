package go.examate.hyp.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

/**
 * 通过 CompositionLocal 向下层 Composable 传递当前可用的 Backdrop。
 * 仅在实验性真液态玻璃开启时(LiquidGlassContainer 内)有值,否则为 null。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
