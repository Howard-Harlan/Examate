package go.examate.hyp.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import android.widget.Toast
import go.examate.hyp.util.ToastUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    fontSize: Int = 14,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    isStreaming: Boolean = false
) {
    // ★ 统一用 Compose 原生渲染(参考 rikkahub 的纯 AST 路径)。
    //   全部走 parseBlocks + 各 Block 组件,不会丢内容。
    //   数学公式用原生文本渲染(衬线斜体),化学结构式才用 WebView(SmilesWebView)。
    //
    //   流式时后台解析不阻塞 UI;非流式时同步解析快速显示。
    //   万一 parseBlocks 抛异常,整条消息回退为纯文本,不让一个渲染 bug 拖垮整个 Activity(闪退)。
    val blocks = if (isStreaming) {
        produceState<List<MdBlock>>(initialValue = emptyList(), markdown) {
            value = withContext(Dispatchers.Default) {
                try { parseBlocks(markdown) } catch (_: Exception) { emptyList() }
            }
        }.value
    } else {
        remember(markdown) {
            try { parseBlocks(markdown) } catch (_: Exception) { emptyList() }
        }
    }

    if (blocks.isEmpty() && markdown.isNotBlank()) {
        FallbackFormula(markdown)
        return
    }

    // 自定义正文字体:通过 LocalTextStyle 注入,不影响代码(等宽)与数学(KaTeX 图片)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides
            (if (fontFamily != null) androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = fontFamily)
             else androidx.compose.material3.LocalTextStyle.current)
    ) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, textColor, fontSize, isStreaming)
                is MdBlock.CodeBlock -> RenderedCodeBlock(block, forceText = false, isStreaming = isStreaming)
                is MdBlock.MathBlock -> MathImage(
                    block.latex, display = true, textColor = textColor,
                    fontSize = fontSize, enabled = !isStreaming
                ) { MathTextBlock(block.latex, textColor, fontSize) }
                is MdBlock.ListBlock -> ListBlockView(block, textColor, fontSize, isStreaming)
                is MdBlock.Quote -> QuoteBlockView(block, textColor, fontSize, isStreaming)
                is MdBlock.Table -> TableRenderBox(block, isStreaming)
                is MdBlock.Hr -> HorizontalDivider(
                    color = textColor.copy(0.15f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                is MdBlock.Paragraph -> ParagraphBlock(block.text, textColor, fontSize, forceText = false, isStreaming = isStreaming)
            }
        }
    }
    }
}

// 整个会话同时存在的 WebView(化学结构式)上限。
// 数学公式已改用原生文本渲染,只有 SMILES 化学结构式才用 WebView。
private const val MAX_ACTIVE_WEBVIEWS = 16
private val activeWebViewCount = AtomicInteger(0)

private fun createSafeWebView(ctx: Context): WebView? = try {
    if (activeWebViewCount.get() >= MAX_ACTIVE_WEBVIEWS) null else WebView(ctx)
} catch (_: Throwable) { null }

private fun makeFallbackView(ctx: Context, text: String): TextView =
    TextView(ctx).apply {
        setText(text)
        setTextColor(android.graphics.Color.parseColor("#1b1f24"))
        textSize = 13f
        setTypeface(android.graphics.Typeface.MONOSPACE)
        val pad = (8 * ctx.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

@Composable
private fun FallbackFormula(latex: String) {
    SelectionContainer {
        Text(
            text = latex,
            color = TextPrimary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}

/**
 * 块级数学公式渲染 — 用 Compose 原生文本展示(衬线斜体居中)。
 *
 * 不用 WebView/KaTeX 渲染:基本数学符号和计算式子用原生 Text 即可清晰展示,
 * 且不会出现 WebView 渲染失败/内容丢失的问题。化学结构式等复杂图形才用 WebView(SmilesWebView)。
 * 长公式可横向滚动,居中显示,字号略大于正文。
 */
@Composable
private fun MathTextBlock(latex: String, textColor: Color, fontSize: Int) {
    SelectionContainer {
        Text(
            text = latex,
            color = textColor.copy(0.9f),
            fontSize = (fontSize + 1).sp,
            lineHeight = (fontSize + 8).sp,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        )
    }
}

/** Compose 颜色 → CSS #RRGGBB */
private fun colorToHex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())

/**
 * 数学/化学公式渲染:优先用 KaTeX 离屏渲染成图片(缓存),渲染前/失败/无字体/流式中回退到 [fallback]。
 * @param display true=块级($$),false=行内($)
 * @param enabled false(如流式生成中)时直接走 fallback,不触发渲染
 */
@Composable
private fun MathImage(
    latex: String,
    display: Boolean,
    textColor: Color,
    fontSize: Int,
    enabled: Boolean,
    fallback: @Composable () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val trimmed = latex.trim()
    if (!enabled || trimmed.isEmpty() || !KatexRenderer.fontsAvailable(context)) {
        fallback(); return
    }
    val colorHex = remember(textColor) { colorToHex(textColor) }
    val key = remember(trimmed, display, colorHex, fontSize) {
        KatexRenderer.keyOf(trimmed, display, colorHex, fontSize)
    }
    var bmp by remember(key) { mutableStateOf(KatexRenderer.cached(key)) }
    LaunchedEffect(key) {
        if (bmp == null) KatexRenderer.request(context, trimmed, display, colorHex, fontSize) { bmp = it }
    }
    val b = bmp
    if (b == null) {
        fallback()   // 渲染完成前显示原生文本占位,完成后无缝替换为图片
    } else {
        val wDp = with(density) { b.width.toDp() }
        val hDp = with(density) { b.height.toDp() }
        val img = @Composable {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = trimmed,
                modifier = Modifier.width(wDp).height(hDp)
            )
        }
        if (display) {
            // 块级:居中,超宽可横向滚动
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) { img() }
        } else {
            img()
        }
    }
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class MathBlock(val latex: String) : MdBlock()
    data class ListBlock(val items: List<ListItem>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Hr : MdBlock()
}

private data class ListItem(val text: String, val ordered: Boolean, val level: Int, val checked: Boolean? = null)
private data class InlineSegment(val text: String, val kind: InlineKind)
private enum class InlineKind { Text, Math, Smiles }

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    // 清理残留的工具调用标记(避免 AI 回复中未被 strip 的标记泄漏到 UI)
    val cleaned = md
        .replace(Regex("<tool_call>[\\s\\S]*?</tool_call>"), "")
        .replace(Regex("<tool_call>[\\s\\S]*?(?:\\n\\n|$)"), "")
        .replace(Regex("<delegate>[\\s\\S]*?</delegate>"), "")
        .replace(Regex("<delegate>[\\s\\S]*?(?:\\n\\n|$)"), "")
        .replace(Regex("<!-- ANCHOR:[^>]*-->"), "")
        .replace(Regex("\\{\\{ANCHOR:[^}]*\\}\\}"), "")
    val lines = cleaned.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (trimmed.startsWith("$$")) {
            val sb = StringBuilder(trimmed.removePrefix("$$"))
            if (sb.toString().trim().endsWith("$$")) {
                blocks.add(MdBlock.MathBlock(sb.toString().trim().removeSuffix("$$").trim()))
                i++
                continue
            }
            i++
            var closed = false
            while (i < lines.size) {
                val current = lines[i]
                if (current.trim().endsWith("$$")) {
                    sb.append("\n").append(current.trim().removeSuffix("$$"))
                    i++
                    closed = true
                    break
                }
                sb.append("\n").append(current)
                i++
            }
            if (closed) {
                blocks.add(MdBlock.MathBlock(sb.toString().trim()))
            } else {
                // 未闭合的 $$ :流式输出途中,降级为段落,避免 KaTeX 渲染错误
                blocks.add(MdBlock.Paragraph("$$" + sb.toString().trim()))
            }
            continue
        }

        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().lowercase()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append("\n")
                i++
            }
            if (i < lines.size) {
                i++ // skip closing ```
                blocks.add(MdBlock.CodeBlock(lang, sb.toString().trimEnd()))
            } else {
                // 未闭合的 ``` :流式输出途中,降级为段落,避免 smiles/tex 等触发 WebView 渲染错误
                val prefix = "```" + if (lang.isNotEmpty()) lang else ""
                blocks.add(MdBlock.Paragraph(prefix + "\n" + sb.toString().trimEnd()))
            }
            continue
        }

        val headingMatch = Regex("^(#{1,6})\\s+(.+)").matchEntire(trimmed)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim()))
            i++
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MdBlock.Hr)
            i++
            continue
        }

        if (trimmed.startsWith("> ")) {
            val sb = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith("> ")) {
                sb.append(lines[i].trim().removePrefix("> ")).append("\n")
                i++
            }
            blocks.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        if (looksLikeTable(lines, i)) {
            val headers = splitTableRow(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().contains("|")) {
                rows.add(splitTableRow(lines[i].trim()))
                i++
            }
            blocks.add(MdBlock.Table(headers, rows))
            continue
        }

        if (isListLine(trimmed)) {
            val items = mutableListOf<ListItem>()
            // ★ 任务列表正则(参考 rikkahub:支持 - [ ] 和 - [x] checkbox 语法)
            val taskUnchecked = Regex("^[-*]\\s+\\[\\s]\\s+(.+)")
            val taskChecked = Regex("^[-*]\\s+\\[[xX]]\\s+(.+)")
            while (i < lines.size) {
                val raw = lines[i]
                val l = raw.trim()
                val orderedMatch = Regex("^(\\d+)\\.\\s(.+)").matchEntire(l)
                when {
                    taskChecked.matchEntire(l) != null -> {
                        items.add(ListItem(taskChecked.matchEntire(l)!!.groupValues[1], ordered = false, level = raw.takeWhile { it == ' ' }.length / 2, checked = true))
                        i++
                    }
                    taskUnchecked.matchEntire(l) != null -> {
                        items.add(ListItem(taskUnchecked.matchEntire(l)!!.groupValues[1], ordered = false, level = raw.takeWhile { it == ' ' }.length / 2, checked = false))
                        i++
                    }
                    l.startsWith("- ") || l.startsWith("* ") -> {
                        items.add(ListItem(l.substring(2), ordered = false, level = raw.takeWhile { it == ' ' }.length / 2))
                        i++
                    }
                    orderedMatch != null -> {
                        items.add(ListItem(orderedMatch.groupValues[2], ordered = true, level = raw.takeWhile { it == ' ' }.length / 2))
                        i++
                    }
                    l.isEmpty() -> {
                        i++
                        break
                    }
                    else -> break
                }
            }
            blocks.add(MdBlock.ListBlock(items))
            continue
        }

        val sb = StringBuilder(trimmed)
        i++
        while (i < lines.size && lines[i].trim().isNotEmpty() && !startsNewBlock(lines, i)) {
            sb.append("\n").append(lines[i].trim())
            i++
        }
        blocks.add(MdBlock.Paragraph(sb.toString()))
    }
    return blocks
}

private fun startsNewBlock(lines: List<String>, index: Int): Boolean {
    val trimmed = lines[index].trim()
    return trimmed.startsWith("#") ||
        trimmed.startsWith("```") ||
        trimmed.startsWith("> ") ||
        trimmed.startsWith("$$") ||
        isListLine(trimmed) ||
        looksLikeTable(lines, index)
}

private fun isListLine(text: String): Boolean =
    // "* " 开头排除 "**"(粗体标记),避免把粗体行误判为列表项
    text.startsWith("- ") ||
        (text.startsWith("* ") && !text.startsWith("**")) ||
        Regex("^\\d+\\.\\s").containsMatchIn(text)

private fun looksLikeTable(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val current = lines[index].trim()
    val next = lines[index + 1].trim()
    return current.contains("|") && Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$").matches(next)
}

private fun splitTableRow(row: String): List<String> =
    row.trim().trim('|').split("|").map { it.trim() }

@Composable
private fun ParagraphBlock(text: String, textColor: Color, fontSize: Int, forceText: Boolean = false, isStreaming: Boolean = false) {
    val raw = text.trim()
    val prettyJson = remember(raw) { prettyJsonOrNull(raw) }
    val csv = remember(raw) { parseCsvOrNull(raw) }

    when {
        prettyJson != null -> RenderBox(title = "JSON", raw = raw, exportText = jsonToCsvOrNull(raw) ?: prettyJson) {
            CodeText(prettyJson, Modifier.fillMaxWidth())
        }
        csv != null -> CsvRenderBox(csv, raw)
        else -> InlineBlock(text, textColor, fontSize, isStreaming, forceText)
    }
}

/**
 * 行内块统一入口(段落/列表项共用):
 * 含化学结构式($smiles{})→分段渲染(SMILES 需整块);否则→真·内联(文本+行内数学同流)。
 */
@Composable
private fun InlineBlock(text: String, textColor: Color, fontSize: Int, isStreaming: Boolean, forceText: Boolean = false) {
    if (text.contains("\$smiles{")) {
        InlineContent(text, textColor, fontSize, isStreaming, forceText)
    } else {
        RichInlineText(text, textColor, fontSize, isStreaming)
    }
}

/**
 * 行内内容渲染(文本/行内数学/SMILES 混排),供段落、列表项复用。
 * 含 `$...$`/`$smiles{}` 时拆成多段逐行渲染;纯文本则一段渲染。
 */
@Composable
private fun InlineContent(text: String, textColor: Color, fontSize: Int, isStreaming: Boolean, forceText: Boolean = false) {
    val segments = remember(text) { splitInlineContent(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { seg ->
            when (seg.kind) {
                InlineKind.Math -> MathImage(
                    seg.text, display = false, textColor = textColor,
                    fontSize = fontSize, enabled = !isStreaming
                ) {
                    Text(
                        text = seg.text,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        color = textColor.copy(0.85f),
                        fontSize = fontSize.sp,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
                InlineKind.Smiles -> if (forceText) FallbackFormula(seg.text) else RenderBox(title = "SMILES", raw = seg.text, exportText = seg.text) {
                    SmilesWebView(seg.text, modifier = Modifier.fillMaxWidth().height(210.dp))
                }
                InlineKind.Text -> if (seg.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = renderInline(seg.text, textColor),
                            color = textColor,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 6).sp
                        )
                    }
                }
            }
        }
    }
}

/** 行内数学正则(仅 $...$,不跨行);供 RichInlineText 提取内联公式 */
private val INLINE_MATH_REGEX = Regex("\\${'$'}([^${'$'}\n]+)\\${'$'}")

/**
 * 富行内文本:用 InlineTextContent 让 $...$ 行内数学「真正内联」于文字流,
 * 适用于表格单元格、标题、引用块等任意 Text 语境。
 * 占位符先用估算尺寸,图片就绪后重组用真实尺寸(类图片加载)。
 * 无行内数学 / 无字体 / 流式中 → 退回普通 renderInline。
 */
@Composable
private fun RichInlineText(
    text: String,
    textColor: Color,
    fontSize: Int,
    isStreaming: Boolean,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hasMath = remember(text) { text.contains('$') && INLINE_MATH_REGEX.containsMatchIn(text) }
    if (!hasMath || isStreaming || !KatexRenderer.fontsAvailable(context)) {
        Text(
            renderInline(text, textColor), color = textColor, fontSize = fontSize.sp,
            fontWeight = fontWeight, fontStyle = fontStyle, lineHeight = (fontSize + 6).sp
        )
        return
    }
    val colorHex = remember(textColor) { colorToHex(textColor) }
    val bitmaps = remember(text) { mutableStateMapOf<String, android.graphics.Bitmap>() }
    LaunchedEffect(text, colorHex, fontSize) {
        INLINE_MATH_REGEX.findAll(text).map { it.groupValues[1] }.distinct().forEach { latex ->
            KatexRenderer.request(context, latex, false, colorHex, fontSize) { b ->
                if (b != null) bitmaps[latex] = b
            }
        }
    }
    val inlineContent = HashMap<String, InlineTextContent>()
    var idx = 0
    val annotated = buildAnnotatedString {
        var last = 0
        INLINE_MATH_REGEX.findAll(text).forEach { m ->
            if (m.range.first > last) parseInlineRec(text.substring(last, m.range.first), textColor, SpanStyle())
            val latex = m.groupValues[1]
            val id = "m${idx++}"
            val bmp = bitmaps[latex]
            val wSp: TextUnit
            val hSp: TextUnit
            if (bmp != null) {
                wSp = with(density) { bmp.width.toSp() }
                hSp = with(density) { bmp.height.toSp() }
            } else {
                wSp = (fontSize * 0.62 * latex.length).coerceIn(fontSize.toDouble(), 320.0).sp
                hSp = (fontSize * 1.25).sp
            }
            appendInlineContent(id, latex)
            inlineContent[id] = InlineTextContent(
                Placeholder(wSp, hSp, PlaceholderVerticalAlign.TextCenter)
            ) {
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), contentDescription = latex, modifier = Modifier.fillMaxSize())
                } else {
                    Text(latex, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                        color = textColor.copy(0.85f), fontSize = fontSize.sp, maxLines = 1)
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) parseInlineRec(text.substring(last), textColor, SpanStyle())
    }
    Text(
        annotated, inlineContent = inlineContent, color = textColor, fontSize = fontSize.sp,
        fontWeight = fontWeight, fontStyle = fontStyle, lineHeight = (fontSize + 6).sp
    )
}

@Composable
private fun RenderedCodeBlock(block: MdBlock.CodeBlock, forceText: Boolean = false, isStreaming: Boolean = false) {
    val lang = block.language.lowercase()
    when {
        lang in setOf("json", "jsonc") || prettyJsonOrNull(block.code) != null -> {
            val pretty = prettyJsonOrNull(block.code) ?: block.code
            RenderBox(title = "JSON", raw = block.code, exportText = jsonToCsvOrNull(block.code) ?: pretty) {
                CodeText(pretty, Modifier.fillMaxWidth())
            }
        }
        lang in setOf("csv", "tsv") -> {
            parseCsvOrNull(block.code, delimiter = if (lang == "tsv") '\t' else ',')?.let { rows ->
                CsvRenderBox(rows, block.code)
            } ?: GenericCodeBlock(block)
        }
        lang in setOf("smiles", "smi", "mol") -> if (isStreaming) {
            // 流式输出途中:用纯文本展示,避免 SmilesWebView 创建/销毁抖动
            FallbackFormula(block.code)
        } else {
            RenderBox(title = "SMILES", raw = block.code, exportText = block.code) {
                if (forceText) FallbackFormula(block.code) else SmilesWebView(block.code, modifier = Modifier.fillMaxWidth().height(240.dp))
            }
        }
        lang in setOf("tex", "latex", "math") -> RenderBox(title = "LaTeX", raw = block.code, exportText = block.code) {
            // 优先 KaTeX 离屏渲染成图;无字体/失败/流式中回退到原生文本
            MathImage(
                block.code, display = true, textColor = TextPrimary,
                fontSize = 14, enabled = !isStreaming
            ) {
                SelectionContainer {
                    Text(
                        text = block.code,
                        color = TextPrimary.copy(0.9f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
        else -> GenericCodeBlock(block)
    }
}

@Composable
private fun RenderBox(
    title: String,
    raw: String,
    exportText: String,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(InputBg)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, color = Color(0xFF5F6B7A), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            BoxAction("复制") { copyText(context, raw) }
            Spacer(Modifier.width(8.dp))
            BoxAction("全屏") { fullscreen = true }
            Spacer(Modifier.width(8.dp))
            BoxAction("导出") { shareText(context, "$title export", exportText) }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 720.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    BoxAction("关闭") { fullscreen = false }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun BoxAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color(0xFF3867D6),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x0F3867D6))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun GenericCodeBlock(block: MdBlock.CodeBlock) {
    // ★ 代码块折叠(参考 rikkahub:超过阈值行数默认折叠,避免长代码撑满屏幕)
    val lineCount = remember(block.code) { block.code.count { it == '\n' } + 1 }
    val collapsible = lineCount > COLLAPSE_LINE_THRESHOLD
    var expanded by remember(block.code) { mutableStateOf(!collapsible) }

    RenderBox(
        title = if (block.language.isBlank()) "Code" else block.language.uppercase(),
        raw = block.code,
        exportText = block.code
    ) {
        Column {
            CodeText(
                block.code,
                Modifier.fillMaxWidth().then(
                    if (collapsible && !expanded) Modifier.heightIn(max = 120.dp) else Modifier
                )
            )
            if (collapsible) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded) "收起" else "展开全部 ($lineCount 行)",
                    color = Color(0xFF3867D6),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x0F3867D6))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { expanded = !expanded }
                )
            }
        }
    }
}

/** 代码块超过此行数时默认折叠 */
private const val COLLAPSE_LINE_THRESHOLD = 15

@Composable
private fun CodeText(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    SelectionContainer {
        Text(
            text = text,
            color = Color(0xFF263238),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            modifier = modifier.horizontalScroll(scroll)
        )
    }
}

@Composable
private fun TableRenderBox(block: MdBlock.Table, isStreaming: Boolean = false) {
    val csv = tableToCsv(block.headers, block.rows)
    RenderBox(title = "Table", raw = csv, exportText = csv) {
        TableView(block.headers, block.rows, isStreaming)
    }
}

@Composable
private fun CsvRenderBox(rows: List<List<String>>, raw: String) {
    val headers = rows.firstOrNull().orEmpty()
    val body = rows.drop(1)
    RenderBox(title = "CSV", raw = raw, exportText = raw) {
        TableView(headers, body, false)
    }
}

@Composable
private fun TableView(headers: List<String>, rows: List<List<String>>, isStreaming: Boolean = false) {
    val scroll = rememberScrollState()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    // 在 @Composable 上下文读取颜色,供 remember lambda 使用
    val tableTextColor = TextPrimary
    // 量出每列最大内容宽度并统一列宽 → 跨行对齐(修复"部分行偏移";列宽含内边距与余量)
    val colWidths = remember(headers, rows) {
        val style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        List(colCount) { c ->
            val cells = buildList {
                add(headers.getOrNull(c).orEmpty())
                rows.forEach { add(it.getOrNull(c).orEmpty()) }
            }
            val maxPx = cells.maxOf { measurer.measure(renderInline(it, tableTextColor), style = style).size.width }
            with(density) { maxPx.toDp() } + 24.dp
        }
    }
    Column(
        Modifier
            .horizontalScroll(scroll)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
    ) {
        if (headers.isNotEmpty()) {
            Row(Modifier.background(Color(0xFFEFF3FA)).padding(vertical = 7.dp)) {
                repeat(colCount) { c ->
                    Box(Modifier.width(colWidths[c]).padding(horizontal = 8.dp)) {
                        RichInlineText(headers.getOrNull(c).orEmpty(), TextPrimary, 12, isStreaming, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        rows.forEachIndexed { rIdx, row ->
            HorizontalDivider(color = Color(0x11000000), thickness = 0.5.dp)
            Row(
                Modifier
                    .background(if (rIdx % 2 == 1) InputBg else CardBg)
                    .padding(vertical = 7.dp)
            ) {
                repeat(colCount) { index ->
                    Box(Modifier.width(colWidths[index]).padding(horizontal = 8.dp)) {
                        RichInlineText(row.getOrNull(index).orEmpty(), TextPrimary, 12, isStreaming)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, textColor: Color, baseSize: Int, isStreaming: Boolean = false) {
    val size = when (block.level) {
        1 -> baseSize + 8
        2 -> baseSize + 6
        3 -> baseSize + 4
        else -> baseSize + 2
    }
    // 标题:不再包 SelectionContainer(屏蔽系统长按选中,交由自定义长按逻辑处理)
    RichInlineText(block.text, textColor, size, isStreaming, fontWeight = FontWeight.Bold)
}

@Composable
private fun ListBlockView(block: MdBlock.ListBlock, textColor: Color, fontSize: Int, isStreaming: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { idx, item ->
            Row(Modifier.padding(start = (item.level * 12).dp)) {
                // ★ 任务列表 checkbox 样式(参考 rikkahub:☑/☐ 符号区分已完成/未完成)
                val prefix = when {
                    item.checked == true -> "☑ "
                    item.checked == false -> "☐ "
                    item.ordered -> "${idx + 1}. "
                    else -> "• "
                }
                Text(
                    text = prefix,
                    color = if (item.checked == true) textColor.copy(0.5f) else textColor,
                    fontSize = fontSize.sp
                )
                val itemColor = if (item.checked == true) textColor.copy(0.5f) else textColor
                InlineBlock(item.text, itemColor, fontSize, isStreaming)
            }
        }
    }
}

@Composable
private fun QuoteBlockView(block: MdBlock.Quote, textColor: Color, fontSize: Int, isStreaming: Boolean = false) {
    // ★ 引用块样式增强(参考 rikkahub:左竖线 + 背景色 + 内边距,视觉层次更清晰)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(textColor.copy(0.05f))
    ) {
        Row(Modifier.padding(10.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(IntrinsicSize.Max)
                    .background(textColor.copy(0.3f))
            )
            Spacer(Modifier.width(10.dp))
            RichInlineText(block.text, textColor.copy(0.8f), fontSize, isStreaming, fontStyle = FontStyle.Italic)
        }
    }
}

private fun splitInlineContent(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    // $smiles{...} 内联化学结构式 | $...$ 行内数学(允许空格,以 $ 为分隔符,仍可正确匹配多对 $...$)
    val regex = Regex("\\${'$'}smiles\\{([^}]+)\\}|\\${'$'}([^${'$'}]+)\\${'$'}")
    var lastEnd = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            segments.add(InlineSegment(text.substring(lastEnd, match.range.first), InlineKind.Text))
        }
        val smiles = match.groups[1]?.value
        val math = match.groups[2]?.value
        if (smiles != null) segments.add(InlineSegment(smiles.trim(), InlineKind.Smiles))
        if (math != null) segments.add(InlineSegment(math.trim(), InlineKind.Math))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        segments.add(InlineSegment(text.substring(lastEnd), InlineKind.Text))
    }
    return segments.ifEmpty { listOf(InlineSegment(text, InlineKind.Text)) }
}

/**
 * 行内 Markdown 渲染 — 递归下降解析,支持嵌套格式(粗体内含斜体/代码/删除线等)。
 *
 * 之前的扁平扫描用 indexOf("**", i+2) 最近匹配 + append(substring),
 * 粗体内部子串不递归解析,导致 `**粗体 *斜体* 粗体**` 里斜体失效、
 * `**外层 **内层** 外层**` 会提前闭合产生孤立 `*`。
 *
 * 现改为递归:遇到 ** * ` ~~ [ 时,对内部子串递归调用 parseInlineRec,
 * 用 parentStyle.merge(...) 让粗体+斜体等样式可叠加。
 */
private fun renderInline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    parseInlineRec(text, baseColor, SpanStyle())
}

private fun AnnotatedString.Builder.parseInlineRec(
    text: String,
    baseColor: Color,
    parentStyle: SpanStyle
) {
    var i = 0
    while (i < text.length) {
        // 链接 [text](url) — 链接文本内可嵌套粗体/斜体
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen != -1) {
                    val linkText = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    pushStringAnnotation(tag = "URL", annotation = url)
                    val linkStyle = parentStyle.merge(
                        SpanStyle(color = Color(0xFF0066CC), fontWeight = FontWeight.Medium)
                    )
                    withStyle(linkStyle) {
                        parseInlineRec(linkText, baseColor, linkStyle)
                    }
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
        }
        // 删除线 ~~text~~ — 内部可嵌套粗体/斜体/代码
        if (i + 1 < text.length && text[i] == '~' && text[i + 1] == '~') {
            val end = text.indexOf("~~", i + 2)
            if (end != -1) {
                val inner = text.substring(i + 2, end)
                val merged = parentStyle.merge(
                    SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                )
                withStyle(merged) { parseInlineRec(inner, baseColor, merged) }
                i = end + 2
                continue
            }
        }
        // 粗体 **text** — 内部递归解析(支持粗体内嵌套斜体/代码/删除线)
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                val inner = text.substring(i + 2, end)
                val merged = parentStyle.merge(SpanStyle(fontWeight = FontWeight.Bold))
                withStyle(merged) { parseInlineRec(inner, baseColor, merged) }
                i = end + 2
                continue
            }
        }
        // 斜体 *text* — 内部递归解析(排除 ** 的情况)
        if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end != -1 && end != i + 1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                val inner = text.substring(i + 1, end)
                val merged = parentStyle.merge(SpanStyle(fontStyle = FontStyle.Italic))
                withStyle(merged) { parseInlineRec(inner, baseColor, merged) }
                i = end + 1
                continue
            }
        }
        // 行内代码 `code` — 代码内容原样显示(不递归),但代码本身继承外层样式(如粗体)
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                val inner = text.substring(i + 1, end)
                val merged = parentStyle.merge(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(0.08f))
                )
                withStyle(merged) { append(inner) }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

private fun prettyJsonOrNull(raw: String): String? {
    val text = raw.trim()
    return try {
        when {
            text.startsWith("{") && text.endsWith("}") -> JSONObject(text).toString(2)
            text.startsWith("[") && text.endsWith("]") -> JSONArray(text).toString(2)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun parseCsvOrNull(raw: String, delimiter: Char = ','): List<List<String>>? {
    val lines = raw.trim().lines().filter { it.isNotBlank() }
    if (lines.size < 2 || lines.none { it.contains(delimiter) }) return null
    val rows = lines.map { parseCsvLine(it, delimiter) }
    val width = rows.firstOrNull()?.size ?: return null
    if (width < 2) return null
    return rows.takeIf { rows.all { row -> row.size == width } }
}

private fun parseCsvLine(line: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                sb.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> {
                cells.add(sb.toString().trim())
                sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    cells.add(sb.toString().trim())
    return cells
}

private fun tableToCsv(headers: List<String>, rows: List<List<String>>): String =
    (listOf(headers) + rows).joinToString("\n") { row -> row.joinToString(",") { csvEscape(it) } }

private fun jsonToCsvOrNull(raw: String): String? {
    val text = raw.trim()
    return try {
        val array = when {
            text.startsWith("[") -> JSONArray(text)
            text.startsWith("{") -> JSONArray().put(JSONObject(text))
            else -> return null
        }
        val objects = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        if (objects.isEmpty()) return null
        val headers = linkedSetOf<String>()
        objects.forEach { obj -> obj.keys().forEach { headers.add(it) } }
        val rows = objects.map { obj -> headers.map { obj.opt(it)?.toString().orEmpty() } }
        tableToCsv(headers.toList(), rows)
    } catch (_: Exception) {
        null
    }
}

private fun csvEscape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("content", text))
    ToastUtil.show(context, "已复制")
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "导出"))
}

// ══════════════════════════════════════════════════════
// WebView 渲染组件(化学结构 SmilesDrawer)
// 用 AndroidView 嵌入 WebView,带安全兜底与自动回收
// 数学公式不再用 WebView/KaTeX,改用 Compose 原生文本渲染(避免内容丢失)
// ══════════════════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SmilesWebView(smiles: String, modifier: Modifier = Modifier) {
    val html = remember(smiles) { buildSmilesHtml(smiles) }
    AndroidView(
        factory = { ctx ->
            val webView = createSafeWebView(ctx) ?: return@AndroidView makeFallbackView(ctx, smiles)
            activeWebViewCount.incrementAndGet()
            webView.apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            if (view is WebView) {
                try { view.destroy() } catch (_: Throwable) {}
                activeWebViewCount.decrementAndGet()
            }
        },
        modifier = modifier
    )
}

private fun buildSmilesHtml(smiles: String): String {
    val cleaned = smiles.trim()
    val encoded = JSONObject.quote(cleaned)
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <script src="file:///android_asset/smiles-drawer.min.js"></script>
          <style>
            html,body{margin:0;padding:0;background:transparent;font-family:sans-serif;color:#1b1f24;}
            #root{min-height:190px;display:flex;align-items:center;justify-content:center;overflow:auto;padding:8px;box-sizing:border-box;}
            .reaction{display:flex;align-items:center;gap:12px;min-width:max-content;}
            .side{display:flex;align-items:center;gap:6px;}
            .mol{width:170px;height:150px;background:#fff;border:1px solid #edf0f3;border-radius:8px;}
            .single{width:260px;height:200px;background:#fff;border:1px solid #edf0f3;border-radius:8px;}
            .arrow{min-width:72px;text-align:center;color:#5f6b7a;font-size:12px;}
            .arrowLine{font-size:24px;line-height:24px;color:#1f2937;}
            .error{font-family:monospace;color:#b42318;white-space:pre-wrap;padding:12px;}
          </style>
        </head>
        <body>
          <div id="root"></div>
          <script>
            var input = $encoded;
            function stripOptions(value) {
              return value.replace(/\s+__[\s\S]*__\s*\${'$'}/, '').trim();
            }
            function drawOne(smiles, svgId, small) {
              var drawer = new SmilesDrawer.SvgDrawer({ width: small ? 170 : 260, height: small ? 150 : 200, bondThickness: 0.7, padding: 12 });
              SmilesDrawer.parse(smiles, function(tree) {
                drawer.draw(tree, svgId, 'light', false);
              }, function(err) {
                document.getElementById(svgId).outerHTML = '<div class="error">' + String(err) + '</div>';
              });
            }
            function moleculeSide(items, prefix) {
              return '<div class="side">' + items.filter(Boolean).map(function(_, i) {
                return '<svg class="mol" id="' + prefix + i + '"></svg>';
              }).join('<span>+</span>') + '</div>';
            }
            function render() {
              var root = document.getElementById('root');
              if (!window.SmilesDrawer) { root.textContent = 'SmilesDrawer loading failed'; root.className='error'; return; }
              var value = stripOptions(input);
              if (value.indexOf('>') >= 0) {
                var parts = value.split('>');
                var left = (parts[0] || '').split('.');
                var middle = parts[1] || '';
                var right = (parts[2] || '').split('.');
                root.innerHTML = '<div class="reaction">' + moleculeSide(left, 'l') + '<div class="arrow"><div>' + middle + '</div><div class="arrowLine">→</div></div>' + moleculeSide(right, 'r') + '</div>';
                left.filter(Boolean).forEach(function(s, i){ drawOne(s.trim(), 'l' + i, true); });
                right.filter(Boolean).forEach(function(s, i){ drawOne(s.trim(), 'r' + i, true); });
              } else {
                root.innerHTML = '<svg class="single" id="single"></svg>';
                drawOne(value, 'single', false);
              }
            }
            if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', render); else render();
          </script>
        </body>
        </html>
    """.trimIndent()
}
