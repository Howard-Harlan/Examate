package go.examate.hyp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = White,
    primaryContainer = GlassSurface,
    onPrimaryContainer = TextPrimary,
    secondary = Accent,
    onSecondary = White,
    secondaryContainer = GlassSurfaceDark,
    onSecondaryContainer = TextPrimary,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = GlassSurface,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurfaceDark,
    onSurfaceVariant = TextSecondary,
    outline = TextHint,
    outlineVariant = Divider,
)

@Composable
fun ExamateTheme(
    content: @Composable () -> Unit
) {
    // 应用整体为浅色风格设计,始终使用浅色配色,确保 AlertDialog 等组件背景为白色
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
