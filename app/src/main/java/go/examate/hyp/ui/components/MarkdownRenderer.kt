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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.theme.TextPrimary
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    fontSize: Int = 14
) {
    // 外层 try/catch(放在 remember 内部,因为 Compose 不允许直接 try/catch 包 Composable 调用):
    // 万一 parseBlocks 抛异常,整条消息回退为纯文本,不让一个渲染 bug 拖垮整个 Activity(闪退)。
    val safe = remember(markdown) {
        try { parseBlocks(markdown) } catch (_: Exception) { emptyList<MdBlock>() }
    }
    if (safe.isEmpty() && markdown.isNotBlank()) {
        // 解析失败:回退为纯文本(不加任何渲染)
        FallbackFormula(markdown)
        return
    }
    val blocks = safe
    // 公式过多时整条消息改用文本回退,避免几十个 WebView 同时存活导致崩溃。
    val tooManyFormulas = remember(blocks) { countFormulas(blocks) > MAX_FORMULAS_PER_MESSAGE }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, textColor, fontSize)
                is MdBlock.CodeBlock -> RenderedCodeBlock(block, forceText = tooManyFormulas)
                is MdBlock.MathBlock -> RenderBox(
                    title = "LaTeX",
                    raw = block.latex,
                    exportText = block.latex
                ) {
                    if (tooManyFormulas) FallbackFormula(block.latex)
                    else MathWebView(block.latex, displayMode = true, modifier = Modifier.fillMaxWidth().height(88.dp))
                }
                is MdBlock.ListBlock -> ListBlockView(block, textColor, fontSize)
                is MdBlock.Quote -> QuoteBlockView(block, textColor, fontSize)
                is MdBlock.Table -> TableRenderBox(block)
                is MdBlock.Hr -> Divider(
                    color = textColor.copy(0.15f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                is MdBlock.Paragraph -> ParagraphBlock(block.text, textColor, fontSize, forceText = tooManyFormulas)
            }
        }
    }
}

// 单条消息公式数量超过此阈值时,放弃逐个 WebView 渲染(否则会同时存在几十个
// 完整浏览器实例 -> OOM / 渲染进程崩溃),改为纯文本回退,保证页面不崩。
private const val MAX_FORMULAS_PER_MESSAGE = 24

// 整个会话同时存在的 WebView(公式/结构式)上限。超出部分直接文本回退。
private const val MAX_ACTIVE_WEBVIEWS = 8
private val activeWebViewCount = AtomicInteger(0)

private fun countFormulas(blocks: List<MdBlock>): Int = blocks.sumOf { b ->
    when (b) {
        is MdBlock.MathBlock -> 1
        is MdBlock.Paragraph -> splitInlineContent(b.text).count { it.kind != InlineKind.Text }
        else -> 0
    }
}

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

private data class ListItem(val text: String, val ordered: Boolean, val level: Int)
private data class InlineSegment(val text: String, val kind: InlineKind)
private enum class InlineKind { Text, Math, Smiles }

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").lines()
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
            while (i < lines.size) {
                val current = lines[i]
                if (current.trim().endsWith("$$")) {
                    sb.append("\n").append(current.trim().removeSuffix("$$"))
                    i++
                    break
                }
                sb.append("\n").append(current)
                i++
            }
            blocks.add(MdBlock.MathBlock(sb.toString().trim()))
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
            if (i < lines.size) i++
            blocks.add(MdBlock.CodeBlock(lang, sb.toString().trimEnd()))
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
            while (i < lines.size) {
                val raw = lines[i]
                val l = raw.trim()
                val orderedMatch = Regex("^(\\d+)\\.\\s(.+)").matchEntire(l)
                when {
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
    text.startsWith("- ") || text.startsWith("* ") || Regex("^\\d+\\.\\s").containsMatchIn(text)

private fun looksLikeTable(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val current = lines[index].trim()
    val next = lines[index + 1].trim()
    return current.contains("|") && Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$").matches(next)
}

private fun splitTableRow(row: String): List<String> =
    row.trim().trim('|').split("|").map { it.trim() }

@Composable
private fun ParagraphBlock(text: String, textColor: Color, fontSize: Int, forceText: Boolean = false) {
    val raw = text.trim()
    val prettyJson = remember(raw) { prettyJsonOrNull(raw) }
    val csv = remember(raw) { parseCsvOrNull(raw) }

    when {
        prettyJson != null -> RenderBox(title = "JSON", raw = raw, exportText = jsonToCsvOrNull(raw) ?: prettyJson) {
            CodeText(prettyJson, Modifier.fillMaxWidth())
        }
        csv != null -> CsvRenderBox(csv, raw)
        else -> {
            val segments = remember(text) { splitInlineContent(text) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                segments.forEach { seg ->
                    when (seg.kind) {
                        // 行内数学:直接融入文段(无框),用衬线斜体示意数学语义。
                        // 块级公式 $$...$$ 仍走 RenderBox+MathWebView。
                        InlineKind.Math -> Text(
                            text = seg.text,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            color = textColor.copy(0.85f),
                            fontSize = fontSize.sp,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
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
    }
}

@Composable
private fun RenderedCodeBlock(block: MdBlock.CodeBlock, forceText: Boolean = false) {
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
        lang in setOf("smiles", "smi", "mol") -> RenderBox(title = "SMILES", raw = block.code, exportText = block.code) {
            if (forceText) FallbackFormula(block.code) else SmilesWebView(block.code, modifier = Modifier.fillMaxWidth().height(240.dp))
        }
        lang in setOf("tex", "latex", "math") -> RenderBox(title = "LaTeX", raw = block.code, exportText = block.code) {
            if (forceText) FallbackFormula(block.code) else MathWebView(block.code, displayMode = true, modifier = Modifier.fillMaxWidth().height(96.dp))
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
            .background(Color(0xFFF7F8FA))
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
                    .background(Color.White)
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
    RenderBox(
        title = if (block.language.isBlank()) "Code" else block.language.uppercase(),
        raw = block.code,
        exportText = block.code
    ) {
        CodeText(block.code, Modifier.fillMaxWidth())
    }
}

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
private fun TableRenderBox(block: MdBlock.Table) {
    val csv = tableToCsv(block.headers, block.rows)
    RenderBox(title = "Table", raw = csv, exportText = csv) {
        TableView(block.headers, block.rows)
    }
}

@Composable
private fun CsvRenderBox(rows: List<List<String>>, raw: String) {
    val headers = rows.firstOrNull().orEmpty()
    val body = rows.drop(1)
    RenderBox(title = "CSV", raw = raw, exportText = raw) {
        TableView(headers, body)
    }
}

@Composable
private fun TableView(headers: List<String>, rows: List<List<String>>) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
    ) {
        if (headers.isNotEmpty()) {
            Row(Modifier.background(Color(0xFFEFF3FA)).padding(vertical = 7.dp, horizontal = 8.dp)) {
                headers.forEach { cell ->
                    Text(cell, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 92.dp, max = 180.dp))
                }
            }
        }
        rows.forEach { row ->
            Divider(color = Color(0x11000000), thickness = 0.5.dp)
            Row(Modifier.padding(vertical = 7.dp, horizontal = 8.dp)) {
                val count = maxOf(headers.size, row.size)
                repeat(count) { index ->
                    Text(row.getOrNull(index).orEmpty(), color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.widthIn(min = 92.dp, max = 180.dp))
                }
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, textColor: Color, baseSize: Int) {
    val size = when (block.level) {
        1 -> baseSize + 8
        2 -> baseSize + 6
        3 -> baseSize + 4
        else -> baseSize + 2
    }
    SelectionContainer {
        Text(
            text = renderInline(block.text, textColor),
            color = textColor,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = (size + 6).sp
        )
    }
}

@Composable
private fun ListBlockView(block: MdBlock.ListBlock, textColor: Color, fontSize: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { idx, item ->
            Row(Modifier.padding(start = (item.level * 12).dp)) {
                Text(
                    text = if (item.ordered) "${idx + 1}. " else "- ",
                    color = textColor,
                    fontSize = fontSize.sp
                )
                SelectionContainer {
                    Text(
                        text = renderInline(item.text, textColor),
                        color = textColor,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 6).sp
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteBlockView(block: MdBlock.Quote, textColor: Color, fontSize: Int) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .width(3.dp)
                .height(24.dp)
                .background(textColor.copy(0.3f))
        )
        Spacer(Modifier.width(8.dp))
        SelectionContainer {
            Text(
                text = renderInline(block.text, textColor),
                color = textColor.copy(0.8f),
                fontSize = fontSize.sp,
                fontStyle = FontStyle.Italic,
                lineHeight = (fontSize + 6).sp
            )
        }
    }
}

private fun splitInlineContent(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    // $smiles{...} 内联化学结构式 | $...$ 行内数学(要求无空格,避免误匹配货币)
    val regex = Regex("\\${'$'}smiles\\{([^}]+)\\}|\\${'$'}([^${'$'}\\s]+)\\${'$'}")
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

private fun renderInline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end != -1 && end != i + 1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(0.08f))) {
                    append(text.substring(i + 1, end))
                }
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
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
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
// WebView 渲染组件(KaTeX 数学公式 + SmilesDrawer 化学结构)
// 用 AndroidView 嵌入 WebView,带安全兜底与自动回收
// ══════════════════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MathWebView(latex: String, displayMode: Boolean, modifier: Modifier = Modifier) {
    val html = remember(latex, displayMode) { buildMathHtml(latex, displayMode) }
    var contentHeightDp by remember { mutableStateOf(if (displayMode) 88.dp else 60.dp) }
    AndroidView(
        factory = { ctx ->
            val webView = createSafeWebView(ctx) ?: return@AndroidView makeFallbackView(ctx, latex)
            activeWebViewCount.incrementAndGet()
            webView.apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(MathHeightBridge { heightPx ->
                    val density = ctx.resources.displayMetrics.density
                    val dp = (heightPx / density).toInt()
                    contentHeightDp = dp.dp
                }, "MathHeightBridge")
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            if (view is WebView) {
                try { view.destroy() } catch (_: Throwable) {}
                activeWebViewCount.decrementAndGet()
            }
        },
        modifier = modifier.heightIn(min = 40.dp, max = 600.dp).then(Modifier.height(contentHeightDp))
    )
}

private class MathHeightBridge(private val onHeightReady: (Int) -> Unit) {
    @JavascriptInterface
    fun postHeight(heightPx: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onHeightReady(heightPx) }
    }
}

private fun buildMathHtml(latex: String, displayMode: Boolean): String {
    val encodedLatex = JSONObject.quote(latex)
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.17.0/dist/katex.min.css">
          <script src="https://cdn.jsdelivr.net/npm/katex@0.17.0/dist/katex.min.js"></script>
          <style>
            html,body{margin:0;padding:0;background:transparent;color:#1b1f24;font-family:sans-serif;}
            #formula{padding:8px;overflow-x:auto;font-size:16px;}
            .error{font-family:monospace;color:#b42318;white-space:pre-wrap;}
          </style>
        </head>
        <body>
          <div id="formula"></div>
          <script>
            function renderFormula(){
              var target = document.getElementById('formula');
              if (!window.katex) { target.textContent = 'KaTeX loading failed'; target.className = 'error'; return; }
              try {
                katex.render($encodedLatex, target, { throwOnError:false, displayMode:$displayMode });
              } catch(e) {
                target.textContent = e.message;
                target.className = 'error';
              }
              requestAnimationFrame(function() {
                requestAnimationFrame(function() {
                  var h = document.documentElement.scrollHeight || document.body.scrollHeight || 60;
                  if (window.MathHeightBridge) window.MathHeightBridge.postHeight(h + 16);
                });
              });
            }
            if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', renderFormula); else renderFormula();
          </script>
        </body>
        </html>
    """.trimIndent()
}

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
                loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
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
          <script src="https://cdn.jsdelivr.net/npm/smiles-drawer@2.4.1/dist/smiles-drawer.min.js"></script>
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
