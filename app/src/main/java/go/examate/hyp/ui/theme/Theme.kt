package go.examate.hyp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import go.examate.hyp.config.PrefsManager

// ════════════════════════════════════════════════════
//  AppColors — 语义色集合(浅色/深色各一套)
// ════════════════════════════════════════════════════

data class AppColors(
    val bgPrimary: Color,
    val cardBg: Color,           // 卡片/列表项背景
    val dialogBg: Color,         // 对话框/底部弹窗背景
    val inputBg: Color,          // 输入框背景
    val chipBg: Color,           // 标签/芯片背景
    val glassSurface: Color,
    val glassSurfaceDark: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val glassShadow: Color,
    val primary: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val white: Color,
    val divider: Color,
    val accentBlue: Color,
    val accentPurple: Color,
    val accentMint: Color,
    val accentPeach: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val danger: Color,
    val successBg: Color,
    val errorBg: Color,
    val warningBg: Color,
    val surfaceVariant: Color,
)

val LightAppColors = AppColors(
    bgPrimary = BgPrimaryLight,
    cardBg = CardBgLight,
    dialogBg = DialogBgLight,
    inputBg = InputBgLight,
    chipBg = ChipBgLight,
    glassSurface = GlassSurfaceLight,
    glassSurfaceDark = GlassSurfaceDarkLight,
    glassBorder = GlassBorderLight,
    glassHighlight = GlassHighlightLight,
    glassShadow = GlassShadowLight,
    primary = PrimaryLight,
    accent = AccentLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textHint = TextHintLight,
    white = White,
    divider = DividerLight,
    accentBlue = AccentBlueLight,
    accentPurple = AccentPurpleLight,
    accentMint = AccentMintLight,
    accentPeach = AccentPeachLight,
    heroGradientStart = HeroGradientStartLight,
    heroGradientEnd = HeroGradientEndLight,
    success = SuccessLight,
    error = ErrorLight,
    warning = WarningLight,
    danger = DangerLight,
    successBg = SuccessBgLight,
    errorBg = ErrorBgLight,
    warningBg = WarningBgLight,
    surfaceVariant = SurfaceVariantLight,
)

val DarkAppColors = AppColors(
    bgPrimary = BgPrimaryDark,
    cardBg = CardBgDark,
    dialogBg = DialogBgDark,
    inputBg = InputBgDark,
    chipBg = ChipBgDark,
    glassSurface = GlassSurfaceDarkColor,
    glassSurfaceDark = GlassSurfaceDarkDark,
    glassBorder = GlassBorderDark,
    glassHighlight = GlassHighlightDark,
    glassShadow = GlassShadowDark,
    primary = PrimaryDarkColor,
    accent = AccentDark,
    textPrimary = TextPrimaryDarkColor,
    textSecondary = TextSecondaryDarkColor,
    textHint = TextHintDarkColor,
    white = White,
    divider = DividerDark,
    accentBlue = AccentBlueDark,
    accentPurple = AccentPurpleDark,
    accentMint = AccentMintDark,
    accentPeach = AccentPeachDark,
    heroGradientStart = HeroGradientStartDark,
    heroGradientEnd = HeroGradientEndDark,
    success = SuccessDark,
    error = ErrorDark,
    warning = WarningDark,
    danger = DangerDark,
    successBg = SuccessBgDark,
    errorBg = ErrorBgDark,
    warningBg = WarningBgDark,
    surfaceVariant = SurfaceVariantDark,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// ════════════════════════════════════════════════════
//  @Composable 语义色访问器 — 替代原顶层 val
//  用法不变:在 @Composable 函数中直接引用 BgPrimary 等
// ════════════════════════════════════════════════════

val BgPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.bgPrimary

val CardBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.cardBg

val DialogBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.dialogBg

val InputBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.inputBg

val ChipBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.chipBg

val GlassSurface: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassSurface

val GlassSurfaceDark: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassSurfaceDark

val GlassBorder: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassBorder

val GlassHighlight: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassHighlight

val GlassShadow: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassShadow

val Primary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primary

val Accent: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary

val TextHint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textHint

val Divider: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.divider

val AccentBlue: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentBlue

val AccentPurple: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentPurple

val AccentMint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentMint

val AccentPeach: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentPeach

val HeroGradientStart: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.heroGradientStart

val HeroGradientEnd: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.heroGradientEnd

val Success: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.success

val Error: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.error

val Warning: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning

val Danger: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.danger

val SuccessBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.successBg

val ErrorBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.errorBg

val WarningBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warningBg

val SurfaceVariant: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceVariant

// ════════════════════════════════════════════════════
//  Material3 ColorScheme
// ════════════════════════════════════════════════════

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = White,
    primaryContainer = GlassSurfaceLight,
    onPrimaryContainer = TextPrimaryLight,
    secondary = AccentLight,
    onSecondary = White,
    secondaryContainer = GlassSurfaceDarkLight,
    onSecondaryContainer = TextPrimaryLight,
    background = BgPrimaryLight,
    onBackground = TextPrimaryLight,
    surface = CardBgLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = InputBgLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = TextHintLight,
    outlineVariant = DividerLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDarkColor,
    onPrimary = Color(0xFF1A1A2E),
    primaryContainer = GlassSurfaceDarkColor,
    onPrimaryContainer = TextPrimaryDarkColor,
    secondary = AccentDark,
    onSecondary = Color(0xFF1A1A2E),
    secondaryContainer = GlassSurfaceDarkDark,
    onSecondaryContainer = TextPrimaryDarkColor,
    background = BgPrimaryDark,
    onBackground = TextPrimaryDarkColor,
    surface = CardBgDark,
    onSurface = TextPrimaryDarkColor,
    surfaceVariant = InputBgDark,
    onSurfaceVariant = TextSecondaryDarkColor,
    outline = TextHintDarkColor,
    outlineVariant = DividerDark,
)

// ════════════════════════════════════════════════════
//  ExamateTheme
// ════════════════════════════════════════════════════

/**
 * 应用主题。
 *
 * 自动从 [PrefsManager] 读取主题模式偏好(0=跟随系统, 1=浅色, 2=深色),
 * 所有 Activity 无需额外传参即可响应深色模式开关。
 *
 * 深色模式下状态栏图标自动变白,浅色模式变黑(参考 Apple HIG)。
 *
 * @param darkMode 强制指定模式(null=读取用户设置)
 */
@Composable
fun ExamateTheme(
    darkMode: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeMode = PrefsManager.getInstance(context).themeMode
    val isDark = darkMode ?: when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (isDark) DarkAppColors else LightAppColors
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    // 状态栏图标颜色适配:深色模式用白色图标,浅色模式用深色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
