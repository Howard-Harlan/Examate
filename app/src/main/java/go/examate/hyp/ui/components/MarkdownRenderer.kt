package go.examate.hyp.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 轻量 Markdown + LaTeX 渲染器
 *
 * 支持:标题、粗体、斜体、行内代码、代码块、列表、引用、表格、分隔线、
 * 链接、行内公式($...$)、块级公式($$...$$)。
 *
 * 纯 Compose 实现_markdown 解析,LaTeX 通过 WebView + KaTeX 渲染。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    fontSize: Int = 14
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block, textColor, fontSize)
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.MathBlock -> MathView(block.latex)
                is MdBlock.ListBlock -> ListBlockView(block, textColor, fontSize)
                is MdBlock.Quote -> QuoteBlockView(block, textColor, fontSize)
                is MdBlock.Table -> TableBlockView(block, textColor, fontSize)
                is MdBlock.Hr -> Divider(color = textColor.copy(0.15f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                is MdBlock.Paragraph -> {
                    val segments = splitInlineMath(block.text)
                    segments.forEach { seg ->
                        if (seg.isMath) {
                            MathView(seg.text)
                        } else {
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
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ════════════════════════════════════════
//  Block 解析
// ════════════════════════════════════════

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class MathBlock(val latex: String) : MdBlock()
    data class ListBlock(val items: List<ListItem>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    object Hr : MdBlock()
}

private data class ListItem(val text: String, val ordered: Boolean, val level: Int)

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 块级公式 $$...$$
        if (trimmed.startsWith("$$")) {
            val sb = StringBuilder(trimmed.substring(2))
            if (sb.toString().trim().endsWith("$$")) {
                blocks.add(MdBlock.MathBlock(sb.toString().trim().removeSuffix("$$").trim()))
                i++; continue
            }
            i++
            while (i < lines.size) {
                if (lines[i].trim().endsWith("$$")) {
                    sb.append("\n").append(lines[i].trim().removeSuffix("$$"))
                    i++; break
                }
                sb.append("\n").append(lines[i])
                i++
            }
            blocks.add(MdBlock.MathBlock(sb.toString().trim()))
            continue
        }

        // 代码块 ```lang ... ```
        if (trimmed.startsWith("```")) {
            val lang = trimmed.substring(3).trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append("\n")
                i++
            }
            i++ // skip closing ```
            blocks.add(MdBlock.CodeBlock(lang, sb.toString().trimEnd()))
            continue
        }

        // 标题
        val headingMatch = Regex("^(#{1,6})\\s+(.+)").matchEntire(trimmed)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim()))
            i++; continue
        }

        // 分隔线
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MdBlock.Hr)
            i++; continue
        }

        // 引用
        if (trimmed.startsWith("> ")) {
            val sb = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith("> ")) {
                sb.append(lines[i].trim().substring(2)).append("\n")
                i++
            }
            blocks.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        // 表格(含 | 分隔)
        if (trimmed.contains("|") && i + 1 < lines.size && Regex("^\\|?[\\s:-]+\\|").matches(lines[i + 1].trim())) {
            val headers = trimmed.split("|").filter { it.isNotBlank() }.map { it.trim() }
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().contains("|")) {
                rows.add(lines[i].trim().split("|").filter { it.isNotBlank() }.map { it.trim() })
                i++
            }
            blocks.add(MdBlock.Table(headers, rows))
            continue
        }

        // 列表
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || Regex("^\\d+\\.\\s").matches(trimmed)) {
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val l = lines[i].trim()
                val unordered = l.startsWith("- ") || l.startsWith("* ")
                val orderedMatch = Regex("^(\\d+)\\.\\s(.+)").matchEntire(l)
                if (unordered) {
                    val indent = lines[i].takeWhile { it == ' ' }.length / 2
                    items.add(ListItem(l.substring(2), false, indent))
                    i++
                } else if (orderedMatch != null) {
                    val indent = lines[i].takeWhile { it == ' ' }.length / 2
                    items.add(ListItem(orderedMatch.groupValues[2], true, indent))
                    i++
                } else if (l.isEmpty()) {
                    i++; break
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListBlock(items))
            continue
        }

        // 空行
        if (trimmed.isEmpty()) {
            i++; continue
        }

        // 普通段落(连续非空行)
        val sb = StringBuilder(trimmed)
        i++
        while (i < lines.size && lines[i].trim().isNotEmpty()
            && !lines[i].trim().startsWith("#")
            && !lines[i].trim().startsWith("```")
            && !lines[i].trim().startsWith("- ")
            && !lines[i].trim().startsWith("* ")
            && !lines[i].trim().startsWith("> ")
            && !lines[i].trim().startsWith("$$")
            && !Regex("^\\d+\\.\\s").matches(lines[i].trim())
        ) {
            sb.append("\n").append(lines[i].trim())
            i++
        }
        blocks.add(MdBlock.Paragraph(sb.toString()))
    }
    return blocks
}

// ════════════════════════════════════════
//  Inline 解析(粗体/斜体/代码/链接)
// ════════════════════════════════════════

private data class InlineSegment(val text: String, val isMath: Boolean)

private fun splitInlineMath(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val regex = Regex("\\\$([^$]+)\\\$")
    var lastEnd = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            segments.add(InlineSegment(text.substring(lastEnd, match.range.first), false))
        }
        segments.add(InlineSegment(match.groupValues[1], true))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        segments.add(InlineSegment(text.substring(lastEnd), false))
    }
    return segments.ifEmpty { listOf(InlineSegment(text, false)) }
}

private fun renderInline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // 粗体 **text**
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2; continue
            }
        }
        // 斜体 *text*
        if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end != -1 && end != i + 1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }
        // 行内代码 `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(0.08f))) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1; continue
            }
        }
        append(text[i])
        i++
    }
}

// ════════════════════════════════════════
//  Block 渲染
// ════════════════════════════════════════

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
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .padding(12.dp)
    ) {
        if (block.language.isNotEmpty()) {
            Text(block.language, color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
        }
        SelectionContainer {
            Text(
                text = block.code,
                color = Color(0xFF333333),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ListBlockView(block: MdBlock.ListBlock, textColor: Color, fontSize: Int) {
    Column {
        block.items.forEachIndexed { idx, item ->
            Row(Modifier.padding(start = (item.level * 12).dp)) {
                Text(
                    text = if (item.ordered) "${idx + 1}. " else "•  ",
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
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun QuoteBlockView(block: MdBlock.Quote, textColor: Color, fontSize: Int) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .background(textColor.copy(0.3f))
        ) {}
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

@Composable
private fun TableBlockView(block: MdBlock.Table, textColor: Color, fontSize: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF9F9F9))
    ) {
        // Header
        Row(Modifier.fillMaxWidth().background(textColor.copy(0.06f)).padding(vertical = 6.dp, horizontal = 8.dp)) {
            block.headers.forEach { h ->
                Text(
                    renderInline(h, textColor),
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Rows
        block.rows.forEach { row ->
            Divider(color = textColor.copy(0.08f), thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 8.dp)) {
                row.forEach { cell ->
                    Text(
                        renderInline(cell, textColor),
                        color = textColor,
                        fontSize = fontSize.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  LaTeX 渲染(WebView + KaTeX)
// ════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(latex: String) {
    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"></script>
            <style>
                body { margin: 4px; padding: 0; background: transparent; }
                .math { font-size: 15px; color: #1a1a1a; }
            </style>
        </head>
        <body>
            <div class="math" id="formula"></div>
            <script>
                try {
                    katex.render(${"\"\"\""}$latex${"\"\"\""}, document.getElementById('formula'), {
                        throwOnError: false,
                        displayMode: true
                    });
                } catch(e) {
                    document.getElementById('formula').textContent = e.message;
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
