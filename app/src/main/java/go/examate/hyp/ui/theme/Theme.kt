package go.examate.hyp.ui.theme

import android.app.Activity
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import go.examate.hyp.config.PrefsManager

// ════════════════════════════════════════════════════
//  AppColors — 语义色集合(浅色/深色各一套)
// ════════════════════════════════════════════════════

data class AppColors(
    val isDark: Boolean,
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
    val glassCardTint: Color,    // 毛玻璃卡片主体 tint
    val glassChipTint: Color,    // 玻璃胶囊表面(顶栏按钮/选择器等)
    val glassInputTint: Color,   // 高亮输入/表面(输入栏、气泡等)
    val bgGradientTint: Color,   // 页面背景底部微光色
    val selectedBg: Color,       // 选择器/切换按钮选中背景(深色用品牌蓝,避免 Primary 提亮成浅灰发白)
    val onSelected: Color,       // 选中背景上的文字/图标色
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
    isDark = false,
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
    glassCardTint = GlassCardTintLight,
    glassChipTint = GlassChipTintLight,
    glassInputTint = GlassInputTintLight,
    bgGradientTint = BgGradientTintLight,
    selectedBg = SelectedBgLight,
    onSelected = OnSelectedLight,
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
    isDark = true,
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
    glassCardTint = GlassCardTintDark,
    glassChipTint = GlassChipTintDark,
    glassInputTint = GlassInputTintDark,
    bgGradientTint = BgGradientTintDark,
    selectedBg = SelectedBgDark,
    onSelected = OnSelectedDark,
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

val IsDark: Boolean
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.isDark

val GlassCardTint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassCardTint

val GlassChipTint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassChipTint

val GlassInputTint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.glassInputTint

val BgGradientTint: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.bgGradientTint

val SelectedBg: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.selectedBg

val OnSelected: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSelected

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
 * 主题即时生效:themeMode 以 Compose 状态 + SharedPreferences 监听器驱动,
 * 切换偏好后所有在栈中的 Activity 都会自动重组刷新,无需重建或重启应用。
 *
 * @param darkMode 强制指定模式(null=读取用户设置)
 */
@Composable
fun ExamateTheme(
    darkMode: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }

    // 主题模式作为可观察状态:SharedPreferences 变化时触发全局重组,
    // 返回栈中尚未销毁的 Activity 也会即时刷新主题
    var themeMode by remember { mutableIntStateOf(prefs.themeMode) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PrefsManager.KEY_THEME_MODE) {
                themeMode = prefs.themeMode
            }
        }
        prefs.registerSettingsListener(listener)
        onDispose { prefs.unregisterSettingsListener(listener) }
    }

    val isDark = darkMode ?: when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (isDark) DarkAppColors else LightAppColors
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    // 状态栏图标颜色 + 窗口背景适配:
    // 深色模式用白色图标、深色窗口背景(避免转场/启动时闪白)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 悬浮窗服务的 ComposeView 上下文是 Service 而非 Activity,
            // 强转 Activity 会抛 ClassCastException,这里统一做安全转换
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            window.decorView.setBackgroundColor(colors.bgPrimary.toArgb())
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
