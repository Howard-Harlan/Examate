package go.examate.hyp.media.reader

import android.content.Context
import go.examate.hyp.media.legado.BookChapter
import go.examate.hyp.media.legado.BookSource
import go.examate.hyp.media.legado.BookSourceRepository
import go.examate.hyp.media.legado.CacheManager
import java.io.File

/**
 * legado 书源 TextProvider — 把在线书源包装成通用阅读器数据源。
 * 目录来自 repo.getChapterList;正文优先章节缓存,否则抓取并写缓存。
 */
class LegadoTextProvider(
    private val context: Context,
    private val source: BookSource,
    private val tocUrl: String,
    override val title: String
) : TextProvider {

    private val repo = BookSourceRepository(context = context)
    @Volatile private var chapters: List<BookChapter> = emptyList()

    override suspend fun loadChapters(): List<ReaderChapter> {
        val list = repo.getChapterList(source, tocUrl).getOrDefault(emptyList())
        chapters = list
        return list.map { ReaderChapter(it.title, it.index, if (it.isVip) "VIP" else "") }
    }

    override suspend fun loadContent(index: Int): String {
        val ch = chapters.getOrNull(index) ?: return ""
        CacheManager.readContent(context, ch.url, ch.index)?.let { return it }
        val text = repo.getContent(source, ch).getOrDefault("")
        if (text.isNotEmpty()) CacheManager.cacheContent(context, ch.url, ch.index, text)
        return text
    }
}

/**
 * 本地文本 TextProvider — 打开本地文本文件(txt/md/html/log/csv/json/epub)。
 * html 自动剥离标签;epub 解压后提取 XHTML 正文。按常见章节标题切分;切不出则按固定字数分页。
 */
class LocalTextProvider(
    private val file: File,
    override val title: String = file.nameWithoutExtension
) : TextProvider {

    private var segments: List<Pair<String, String>> = emptyList()  // (标题, 正文)

    override suspend fun loadChapters(): List<ReaderChapter> {
        val text = readAndParseFile(file)
        segments = splitChapters(text)
        return segments.mapIndexed { i, (t, _) -> ReaderChapter(t, i) }
    }

    override suspend fun loadContent(index: Int): String = segments.getOrNull(index)?.second.orEmpty()

    /** 读取文件并按扩展名解析:html/htm 剥离标签,epub 解压提取 XHTML */
    private fun readAndParseFile(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "html", "htm" -> {
                val raw = runCatching { f.readText() }.getOrDefault("")
                stripHtmlTags(raw)
            }
            "epub" -> parseEpub(f)
            else -> runCatching { f.readText() }.getOrDefault("")
        }
    }

    /** 用 jsoup 剥离 HTML 标签,保留段落换行 */
    private fun stripHtmlTags(html: String): String {
        return runCatching {
            val doc = org.jsoup.Jsoup.parse(html)
            // 把 <p> <br> <div> 转成换行,再取纯文本
            doc.select("p, br, div, h1, h2, h3, h4, h5, h6").append("\n")
            doc.text().replace("\\s*\\n\\s*".toRegex(), "\n").trim()
        }.getOrDefault(html)
    }

    /** 简易 EPUB 解析:解压 zip,提取 OEBPS/Content 下的 XHTML 文件,按 spine 顺序拼接 */
    private fun parseEpub(f: File): String {
        return runCatching {
            val sb = StringBuilder()
            java.util.zip.ZipFile(f).use { zip ->
                // 找到 OPF 文件(container.xml 指向)
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return ""
                val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
                val opfPath = Regex("full-path=\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1) ?: return ""
                val opfEntry = zip.getEntry(opfPath) ?: return ""
                val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                val opfDir = opfPath.substringBeforeLast('/', "")

                // 提取 manifest 中的 id → href 映射
                val manifest = mutableMapOf<String, String>()
                Regex("<item[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"").findAll(opfXml).forEach { m ->
                    manifest[m.groupValues[1]] = m.groupValues[2]
                }
                // 按 spine itemref 顺序读取
                val spineIds = Regex("<itemref[^>]*idref=\"([^\"]+)\"").findAll(opfXml).map { it.groupValues[1] }.toList()
                for (id in spineIds) {
                    val href = manifest[id] ?: continue
                    val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                    val entry = zip.getEntry(fullPath) ?: continue
                    val xhtml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    val plain = stripHtmlTags(xhtml)
                    if (plain.isNotBlank()) {
                        sb.append(plain).append("\n\n")
                    }
                }
            }
            sb.toString().trim().ifEmpty { "EPUB 解析为空" }
        }.getOrDefault("EPUB 解析失败: ${f.name}")
    }

    /** 章节切分:优先按"第N章/第N卷/序章/楔子"标题行;否则每 ~4000 字一段 */
    private fun splitChapters(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return listOf("正文" to "")
        val titleRe = Regex("^\\s*(第[0-9一二三四五六七八九十百千零两]+[章节卷回]|序章|楔子|尾声|后记|番外).{0,30}$")
        val lines = text.split("\n")
        val hasTitles = lines.count { titleRe.matches(it.trim()) } >= 3
        if (hasTitles) {
            val result = mutableListOf<Pair<String, String>>()
            var curTitle = "正文"
            val sb = StringBuilder()
            for (line in lines) {
                if (titleRe.matches(line.trim())) {
                    if (sb.isNotBlank()) result.add(curTitle to sb.toString().trim())
                    curTitle = line.trim(); sb.clear()
                } else sb.append(line).append("\n")
            }
            if (sb.isNotBlank()) result.add(curTitle to sb.toString().trim())
            if (result.isNotEmpty()) return result
        }
        // 无标题:按字数分页
        val pageSize = 4000
        if (text.length <= pageSize) return listOf(title to text)
        return text.chunked(pageSize).mapIndexed { i, chunk -> "第${i + 1}页" to chunk }
    }
}
