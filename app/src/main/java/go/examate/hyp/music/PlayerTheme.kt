package go.examate.hyp.music

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页动态主题(Player Colors) — Apple Music "专辑主导色"体系。
 *
 * 从封面 Palette 提取多档色(vibrant 强调 / darkMuted 遮罩),派生一套播放页配色:
 * 背景遮罩、文字层级、强调色、毛玻璃按钮面全部跟随封面。
 * 无封面时回退主题化配色(浅色亮 / 深色暗)。
 *
 * 组件通过 [LocalPlayerColors] 读取,无需手动传参判断 hasCover —— 灵活扩展点:
 * 歌词、按钮、进度条、队列页均可自动响应封面色。
 */
data class PlayerColors(
    /** 是否封面背景模式(true=白字体系 / false=主题色体系) */
    val isCoverBacked: Boolean,
    /** 背景遮罩渐变(顶部/底部),封面模式下叠加在模糊封面上 */
    val overlayTop: Color,
    val overlayBottom: Color,
    /** 无封面时的主题化背景渐变 */
    val fallbackTop: Color,
    val fallbackBottom: Color,
    /** 文字层级 */
    val primary: Color,
    val secondary: Color,
    val hint: Color,
    /** 强调色(进度条/歌词高亮/主播放键) */
    val accent: Color,
    /** 毛玻璃按钮面与图标 */
    val surface: Color,
    val onSurface: Color,
    /**
     * 悬浮按钮(顶栏收起/收藏等)面与图标色 — 两套样式:
     * 封面背景/深色主题 → 白半透玻璃面+白图标;
     * 浅色无封面 → 深色凹陷玻璃面+深图标(与浅色卡面/背景天然对比,
     * 修复旧版亮面按钮与控制卡面同色融为一体的问题)
     */
    val btnFace: Color,
    val btnIcon: Color,
    /** 唱片盘面/中心孔 */
    val discGlass: Color,
    val discHole: Color,
) {
    companion object {
        /** 无封面 fallback:跟随深浅主题(颜色由调用方在组合上下文传入) */
        fun themeFallback(
            isDark: Boolean,
            textPrimary: Color,
            textSecondary: Color,
            textHint: Color,
            accentBlue: Color,
        ): PlayerColors = PlayerColors(
            isCoverBacked = false,
            overlayTop = Color.Transparent,
            overlayBottom = Color.Transparent,
            fallbackTop = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF3F1FA),
            fallbackBottom = if (isDark) Color(0xFF23232E) else Color(0xFFEAF0FB),
            primary = if (isDark) Color.White else textPrimary,
            secondary = if (isDark) Color.White.copy(0.78f) else textSecondary,
            hint = if (isDark) Color.White.copy(0.55f) else textHint,
            accent = if (isDark) Color(0xFF7BA0FF) else accentBlue,
            surface = if (isDark) Color.White.copy(0.12f) else Color.White.copy(0.55f),
            onSurface = if (isDark) Color.White else textPrimary,
            // 浅色无封面:深色凹陷玻璃面(与浅色背景/卡面形成对比,不再用与卡面同色的亮面)
            btnFace = if (isDark) Color.White.copy(0.18f) else Color.Black.copy(0.08f),
            btnIcon = if (isDark) Color.White else Color(0xFF232336),
            discGlass = Color.White.copy(if (isDark) 0.12f else 0.7f),
            discHole = if (isDark) Color(0xFF2C2C2E) else Color(0xFFF3F1FA),
        )

        /** 从 Palette 派生封面背景配色(Apple Music 主导色风格) */
        fun fromCover(p: PlayerPalette): PlayerColors {
            val accent = p.vibrant
            val overlayBase = p.darkMuted ?: p.muted ?: accent
            return PlayerColors(
                isCoverBacked = true,
                overlayTop = overlayBase.copy(alpha = 0.55f),
                overlayBottom = Color.Black.copy(alpha = 0.85f),
                fallbackTop = overlayBase,
                fallbackBottom = Color(0xFF101014),
                primary = Color.White,
                secondary = Color.White.copy(0.78f),
                hint = Color.White.copy(0.55f),
                accent = accent,
                surface = Color.White.copy(0.22f),
                onSurface = Color.White,
                btnFace = Color.White.copy(0.24f),
                btnIcon = Color.White,
                discGlass = Color.White.copy(0.18f),
                discHole = Color.White.copy(0.85f),
            )
        }
    }
}

/** Palette 提取结果 — 只保留需要的档位 */
data class PlayerPalette(
    val vibrant: Color,
    val muted: Color? = null,
    val darkMuted: Color? = null,
)

/** 从封面 Bitmap 提取 Palette(先缩小到 64px 提速,CPU 密集请放 IO/Default 线程) */
fun extractPlayerPalette(bitmap: Bitmap): PlayerPalette {
    // 缩小采样,Palette 提取更快且结果更稳
    val sample = if (bitmap.width > 96 || bitmap.height > 96) {
        val scale = 96f / maxOf(bitmap.width, bitmap.height)
        Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
    } else bitmap
    val palette = try {
        Palette.from(sample).maximumColorCount(16).generate()
    } finally {
        if (sample != bitmap) sample.recycle()
    }
    return PlayerPalette(
        vibrant = palette.vibrantSwatch?.rgb?.let(::Color) ?: Color(0xFF3D6BD6),
        muted = palette.mutedSwatch?.rgb?.let(::Color),
        darkMuted = palette.darkMutedSwatch?.rgb?.let(::Color),
    )
}

val LocalPlayerColors = staticCompositionLocalOf {
    PlayerColors.themeFallback(
        isDark = false,
        textPrimary = Color(0xFF1A1A2E),
        textSecondary = Color(0xFF6B7280),
        textHint = Color(0xFF9CA3AF),
        accentBlue = Color(0xFF5B8DEF),
    )
}

/**
 * 记住播放页动态配色:封面变化时在后台线程提取 Palette 派生,
 * 无封面立即回退主题色。所有播放页子组件经 [LocalPlayerColors] 读取。
 */
@Composable
fun rememberPlayerColors(coverBitmap: Bitmap?): PlayerColors {
    val isDark = IsDark
    // 组合上下文读取主题色,传给非组合的 fallback 构造
    val textPrimary = TextPrimary
    val textSecondary = TextSecondary
    val textHint = TextHint
    val accentBlue = AccentBlue
    var colors by remember {
        mutableStateOf(PlayerColors.themeFallback(isDark, textPrimary, textSecondary, textHint, accentBlue))
    }
    LaunchedEffect(coverBitmap, isDark) {
        colors = if (coverBitmap != null) {
            withContext(Dispatchers.Default) {
                PlayerColors.fromCover(extractPlayerPalette(coverBitmap))
            }
        } else {
            PlayerColors.themeFallback(isDark, textPrimary, textSecondary, textHint, accentBlue)
        }
    }
    return colors
}
