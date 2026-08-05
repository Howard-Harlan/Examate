package go.examate.hyp.media.reading

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * 阅读器偏好 — 字号(5档)+ 背景主题(3档),存 SharedPreferences。
 */
object ReaderPrefs {
    private const val PREFS = "reader_prefs"
    private const val KEY_FONT = "font_level"   // 0小 1偏小 2常规 3偏大 4大
    private const val KEY_BG = "bg_theme"       // 0纸白 1护眼绿 2夜黑
    private const val KEY_CUSTOM_FONT = "use_custom_font"  // 阅读器是否启用自定义字体(与 agents 共享导入,选择独立)
    private const val KEY_CONCURRENCY = "search_concurrency"  // 并行搜索数(1-16)
    private const val KEY_STRIP_HTML = "strip_html_tags"      // 正文净化HTML标签(默认true)
    private const val KEY_CUSTOM_CSS = "custom_css"           // 自定义CSS样式(用户输入,覆盖默认阅读样式)

    fun getFontLevel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FONT, 2)

    fun setFontLevel(context: Context, level: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_FONT, level.coerceIn(0, 4)).apply()

    fun getBgTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BG, 0)

    fun setBgTheme(context: Context, theme: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_BG, theme.coerceIn(0, 2)).apply()

    /** 并行搜索数(默认8,范围1-16) */
    fun getSearchConcurrency(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CONCURRENCY, 8).coerceIn(1, 16)

    fun setSearchConcurrency(context: Context, n: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CONCURRENCY, n.coerceIn(1, 16)).apply()

    /** 是否净化正文HTML标签(默认开启) */
    fun isStripHtmlTags(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STRIP_HTML, true)

    fun setStripHtmlTags(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_STRIP_HTML, enabled).apply()

    /** 阅读器是否启用自定义字体(字体文件与 agents 共享,此开关仅控制阅读器自身) */
    fun isCustomFontEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CUSTOM_FONT, false)

    fun setCustomFontEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CUSTOM_FONT, enabled).apply()

    /** 字号 sp(5档:0小/1偏小/2常规/3偏大/4大) */
    fun fontSizeSp(level: Int): Int = when (level) {
        0 -> 14; 1 -> 17; 2 -> 19; 3 -> 22; 4 -> 26
        else -> 19
    }

    /** 背景色(3档) */
    fun bgColor(theme: Int): Color = when (theme) {
        1 -> Color(0xFFCCE8CF)   // 护眼绿
        2 -> Color(0xFF1A1A1A)   // 夜黑
        else -> Color(0xFFF5F1E8) // 纸白
    }

    /** 正文色(与背景对应) */
    fun textColor(theme: Int): Color = when (theme) {
        2 -> Color(0xFFBFBFBF)   // 夜黑上的浅灰
        else -> Color(0xFF2A2A2A)
    }

    /** 字号档位标签(5档) */
    fun fontLevelLabel(level: Int): String = when (level) {
        0 -> "小"; 1 -> "偏小"; 2 -> "常规"; 3 -> "偏大"; 4 -> "大"
        else -> "常规"
    }

    /** 自定义CSS样式(用户输入,用于覆盖阅读器默认样式) */
    fun getCustomCss(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_CSS, "") ?: ""

    fun setCustomCss(context: Context, css: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM_CSS, css).apply()

    /**
     * 解析后的自定义样式(从 CSS-like 文本解析)。
     * 支持属性:font-size, line-height, color, letter-spacing, text-indent,
     *          paragraph-spacing, text-align, font-weight
     * null 表示未设置,使用默认值。
     */
    data class ReaderStyle(
        val fontSize: Int? = null,           // sp,覆盖字号档位
        val lineHeightExtra: Int? = null,    // sp,行距额外间距(对应 lineHeight = fontSize + extra)
        val textColor: Color? = null,        // 正文颜色
        val letterSpacing: Float? = null,    // sp,字间距
        val textIndent: Int? = null,         // sp,首行缩进
        val paragraphSpacing: Int? = null,   // dp,段落间距
        val textAlign: TextAlign? = null,
        val fontWeight: FontWeight? = null
    )

    /**
     * 解析 CSS-like 文本为 ReaderStyle。
     * 支持格式如:
     *   font-size: 20px;
     *   line-height: 1.6;
     *   color: #2A2A2A;
     *   letter-spacing: 0.5px;
     *   text-indent: 2em;
     *   paragraph-spacing: 16px;
     *   text-align: justify;
     *   font-weight: bold;
     */
    fun parseCss(css: String): ReaderStyle {
        if (css.isBlank()) return ReaderStyle()
        var fontSize: Int? = null
        var lineHeightExtra: Int? = null
        var textColor: Color? = null
        var letterSpacing: Float? = null
        var textIndent: Int? = null
        var paragraphSpacing: Int? = null
        var textAlign: TextAlign? = null
        var fontWeight: FontWeight? = null

        for (line in css.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key = line.substring(0, colonIdx).trim().lowercase()
            val value = line.substring(colonIdx + 1).trim().trimEnd(';').trim()
            if (key.isEmpty() || value.isEmpty()) continue
            try {
                when (key) {
                    "font-size" -> fontSize = parseSize(value)
                    "line-height" -> {
                        // 行高倍数:1.6 → extra = (倍数 - 1) * fontSize;若 fontSize 未设用默认 19
                        val mult = value.trimEnd('x', 'p').replace("px", "").toFloatOrNull()
                        if (mult != null && mult > 0) {
                            val base = fontSize ?: 19
                            lineHeightExtra = ((mult - 1f) * base).toInt()
                        }
                    }
                    "color" -> textColor = parseColor(value)
                    "letter-spacing" -> letterSpacing = parseSize(value).toFloat()
                    "text-indent" -> {
                        // em → 按 fontSize 倍数;px → 直接取
                        if (value.endsWith("em")) {
                            val em = value.dropLast(2).trim().toFloatOrNull()
                            if (em != null) textIndent = (em * (fontSize ?: 19)).toInt()
                        } else textIndent = parseSize(value)
                    }
                    "paragraph-spacing" -> paragraphSpacing = parseSize(value)
                    "text-align" -> textAlign = when (value.lowercase()) {
                        "left" -> TextAlign.Left
                        "center" -> TextAlign.Center
                        "right" -> TextAlign.Right
                        "justify" -> TextAlign.Justify
                        else -> null
                    }
                    "font-weight" -> fontWeight = when (value.lowercase()) {
                        "bold", "700" -> FontWeight.Bold
                        "medium", "500" -> FontWeight.Medium
                        "normal", "400" -> FontWeight.Normal
                        "light", "300" -> FontWeight.Light
                        else -> null
                    }
                }
            } catch (_: Exception) { /* 忽略单行解析错误 */ }
        }
        return ReaderStyle(fontSize, lineHeightExtra, textColor, letterSpacing, textIndent, paragraphSpacing, textAlign, fontWeight)
    }

    private fun parseSize(value: String): Int {
        val v = value.replace("px", "").replace("sp", "").replace("dp", "").trim()
        return v.toFloatOrNull()?.toInt() ?: 0
    }

    private fun parseColor(value: String): Color? {
        val v = value.trim()
        return when {
            v.startsWith("#") -> runCatching {
                val hex = v.removePrefix("#")
                when (hex.length) {
                    6 -> Color(android.graphics.Color.parseColor("#$hex"))
                    8 -> Color(android.graphics.Color.parseColor("#$hex"))
                    3 -> Color(android.graphics.Color.parseColor("#${hex.map { c -> "$c$c" }.joinToString("")}"))
                    else -> null
                }
            }.getOrNull()
            v.startsWith("rgb") -> runCatching {
                val nums = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()
                if (nums.size >= 3) Color(nums[0], nums[1], nums[2]) else null
            }.getOrNull()
            else -> null
        }
    }
}
