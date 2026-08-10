package go.examate.hyp.ui.theme

import androidx.compose.ui.graphics.Color

// ════════════════════════════════════════════════════
//  色值规范
//
//  设计原则(参考 Apple HIG + Cresto):
//  1. 层次化背景 — 深色模式用纯黑底 + 递进亮度的表面层,
//     形成清晰的空间层次(参考 iOS systemBackground 层级)
//  2. 文字对比度 — 深色模式用纯白 + 透明白,而非灰色,
//     保证在深色背景上的可读性(WCAG AA 标准)
//  3. 玻璃材质 — 深色模式下用白色低透明度模拟材质反光,
//     而非深色(深色玻璃在深色背景上会"消失")
//  4. OLED 友好 — 深色模式页面底色用纯黑,省电且对比度最大
// ════════════════════════════════════════════════════

// ── 页面背景(最底层) ──
val BgPrimaryLight = Color(0xFFEFF1F5)       // 浅灰底,柔和非纯白
val BgPrimaryDark = Color(0xFF000000)         // 纯黑(OLED 省电 + 最大对比度)

// ── 表面层次(iOS systemBackground 标准) ──
// 浅色:白→灰递进;深色:黑→灰递进,每层亮度差 ~6-8%
val CardBgLight = Color(0xFFFFFFFF)           // 卡片/列表项
val CardBgDark = Color(0xFF1C1C1E)            // iOS secondarySystemBackground
val DialogBgLight = Color(0xFFFFFFFF)         // 对话框/底部弹窗
val DialogBgDark = Color(0xFF2C2C2E)          // iOS tertiarySystemBackground
val InputBgLight = Color(0xFFF2F2F7)          // 输入框/选中态填充
val InputBgDark = Color(0xFF38383A)           // iOS quaternarySystemFill
val ChipBgLight = Color(0xFFEEF2FF)           // 标签/芯片背景
val ChipBgDark = Color(0xFF3A3A3C)            // 与输入框同级

// ── 玻璃材质 ──
// 浅色:白色半透明(模拟毛玻璃);深色:白色低透明度(模拟材质反光)
// 深色不用深色玻璃,否则在纯黑背景上完全消失
val GlassSurfaceLight = Color(0xCCFFFFFF)     // 主玻璃面(80% 白)
val GlassSurfaceDarkColor = Color(0x1AFFFFFF)  // 深色主玻璃面(10% 白,模拟反光)
val GlassSurfaceDarkLight = Color(0x66FFFFFF)  // 浅色次级玻璃(40% 白)
val GlassSurfaceDarkDark = Color(0x0DFFFFFF)   // 深色次级玻璃(5% 白,极淡)
val GlassBorderLight = Color(0x88FFFFFF)      // 玻璃边框(53% 白)
val GlassBorderDark = Color(0x26FFFFFF)       // 深色玻璃边框(15% 白,可见但不突兀)
val GlassHighlightLight = Color(0xCCFFFFFF)   // 玻璃高光(顶部反光)
val GlassHighlightDark = Color(0x33FFFFFF)    // 深色玻璃高光(20% 白)
val GlassShadowLight = Color(0x1A000000)      // 浅色阴影(10% 黑)
val GlassShadowDark = Color(0x66000000)       // 深色阴影(40% 黑,更深以增强层次)

// ── 主色 ──
val PrimaryLight = Color(0xFF374151)          // 深石墨灰(按钮/图标)
val PrimaryDarkColor = Color(0xFFE5E5EA)      // 深色提亮(iOS systemGray)
val AccentLight = Color(0xFF6B7280)
val AccentDark = Color(0xFFAEAEB2)            // 深色辅助色提亮

// ── 文字(核心可读性) ──
// 深色模式用纯白 + 透明白,而非灰色,保证对比度
val TextPrimaryLight = Color(0xFF1A1A2E)      // 近黑
val TextPrimaryDarkColor = Color(0xFFFFFFFF)  // 纯白(最大对比度)
val TextSecondaryLight = Color(0xFF6B7280)    // 中灰
val TextSecondaryDarkColor = Color(0x99FFFFFF) // 白 60%(次要信息仍清晰)
val TextHintLight = Color(0xFF9CA3AF)         // 浅灰(提示)
val TextHintDarkColor = Color(0x66FFFFFF)     // 白 40%(提示弱化但仍可读)

val White = Color(0xFFFFFFFF)
val DividerLight = Color(0x22000000)          // 黑 13%
val DividerDark = Color(0x22FFFFFF)           // 白 13%

// ── 彩色点缀(深色模式提亮 ~15%,保证在深色背景可见) ──
val AccentBlueLight = Color(0xFF5B8DEF)
val AccentBlueDark = Color(0xFF7BA0FF)        // 提亮
val AccentPurpleLight = Color(0xFF9B7BE8)
val AccentPurpleDark = Color(0xFFB095F0)      // 提亮
val AccentMintLight = Color(0xFF6EC9B4)
val AccentMintDark = Color(0xFF8AD9C7)        // 提亮
val AccentPeachLight = Color(0xFFF2B69C)
val AccentPeachDark = Color(0xFFF5C4AE)       // 提亮

// ── 渐变(深色模式用深色调,避免大面积亮色刺眼) ──
val HeroGradientStartLight = Color(0xFFE8F0FE)
val HeroGradientStartDark = Color(0xFF1A1A2E)  // 深蓝黑
val HeroGradientEndLight = Color(0xFFF5F0FF)
val HeroGradientEndDark = Color(0xFF1A1A2E)    // 统一深色,避免渐变在深色下突兀

// ── 语义色 ──
val SuccessLight = Color(0xFF2E7D32)
val SuccessDark = Color(0xFF4CAF50)            // 提亮
val ErrorLight = Color(0xFFE53935)
val ErrorDark = Color(0xFFFF5252)              // 提亮
val WarningLight = Color(0xFFEF6C00)
val WarningDark = Color(0xFFFFAB40)            // 提亮
val DangerLight = Color(0xFFFF3B30)
val DangerDark = Color(0xFFFF453A)             // iOS systemRed
val SuccessBgLight = Color(0xFFE8F5E9)
val SuccessBgDark = Color(0xFF1B3320)          // 深绿底
val ErrorBgLight = Color(0xFFFFF0F0)
val ErrorBgDark = Color(0xFF331B1B)            // 深红底
val WarningBgLight = Color(0xFFFFF3E0)
val WarningBgDark = Color(0xFF33291B)          // 深橙底
val SurfaceVariantLight = Color(0xFFE0E0E0)
val SurfaceVariantDark = Color(0xFF2C2C2E)     // 与对话框同级
