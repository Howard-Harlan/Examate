package go.examate.hyp.media.legado

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup

/**
 * 书源仓库 — 串联 搜索/详情/目录/正文(阶段1/2 仅搜索)。
 *
 * 全部 suspend + Dispatchers.IO;抓取用 LegadoHttp,解析用 RuleEngine。
 * 单源失败不影响其他源(Result 包裹)。
 *
 * @param enableJs 是否启用 QuickJS 执行源内 JS(阶段2 起 true)。
 *   每源创建独立 JsEngine(绑定该源上下文),用完 close(),避免跨源污染与内存泄漏。
 * @param context 用于加载用户导入的全局净化规则(ContentRuleStore);为 null 时不追加用户规则。
 */
class BookSourceRepository(
    private val enableJs: Boolean = true,
    private val context: Context? = null
) {

    companion object { private const val TAG = "BookSourceRepo" }

    /** 单源搜索 */
    suspend fun searchBooks(source: BookSource, keyword: String, page: Int = 1): Result<List<SearchBook>> {
        val ctx = RuleContext(source, source.bookSourceUrl, key = keyword, page = page)
        // 每源独立 JS 引擎(so 加载失败/未启用则为 null → 遇 JS 规则返回空)
        val jsEngine = if (enableJs) JsEngine.createOrNull(JsExtensions(source, ctx)) else null
        if (enableJs && jsEngine == null) Log.w(TAG, "JsEngine unavailable for ${source.bookSourceName}")
        val ruleEngine = RuleEngine(jsEngine)
        return try {
            runCatching {
                val searchUrl = source.searchUrl
                    ?: throw IllegalStateException("书源未配置 searchUrl")
                Log.i(TAG, "search @${source.bookSourceName} kw='$keyword' url=$searchUrl js=${jsEngine != null}")
                val analyze = AnalyzeUrl(searchUrl, key = keyword, page = page, baseUrl = source.bookSourceUrl, source = source, jsEngine = jsEngine)
                if (analyze.isJs) {
                    throw IllegalStateException("该书源需要 JS 引擎(未加载)")
                }

                val req = analyze.build()
                val resp = LegadoHttp.fetch(req)
                ctx.baseUrl = resp.finalUrl   // 相对路径基于重定向终址
                Log.i(TAG, "search @${source.bookSourceName} resp len=${resp.body.length} finalUrl=${resp.finalUrl}")
                val rule = source.ruleSearch
                    ?: throw IllegalStateException("书源未配置搜索规则")

                // 内容根:JSON 路径规则用原文;否则 jsoup 文档
                val listRule = rule.bookList.orEmpty()
                val root: Any = if (listRule.startsWith("$.") || listRule.startsWith("@json:")) resp.body
                else Jsoup.parse(resp.body, resp.finalUrl)

                val elements = ruleEngine.getElements(root, rule.bookList, ctx)
                Log.i(TAG, "search @${source.bookSourceName} elements=${elements.size} bookListRule='$listRule'")
                if (elements.isEmpty()) {
                    Log.i(TAG, "no elements for ${source.bookSourceName}, body head=${resp.body.take(300)}")
                }
                elements.mapNotNull { el ->
                    val bookUrl = ruleEngine.getString(el, rule.bookUrl, ctx, isUrl = true)
                    val name = ruleEngine.getString(el, rule.name, ctx)
                    if (bookUrl.isEmpty() || name.isEmpty()) return@mapNotNull null
                    SearchBook(
                        name = name,
                        author = ruleEngine.getString(el, rule.author, ctx),
                        kind = ruleEngine.getString(el, rule.kind, ctx),
                        wordCount = ruleEngine.getString(el, rule.wordCount, ctx),
                        lastChapter = ruleEngine.getString(el, rule.lastChapter, ctx),
                        intro = ruleEngine.getString(el, rule.intro, ctx),
                        coverUrl = ruleEngine.getString(el, rule.coverUrl, ctx, isUrl = true),
                        bookUrl = bookUrl,
                        sourceUrl = source.bookSourceUrl,
                        sourceName = source.bookSourceName
                    )
                }
            }.onFailure { Log.w(TAG, "search fail @${source.bookSourceName}", it) }
        } finally {
            jsEngine?.close()
        }
    }

    /**
     * 获取书籍详情(ruleBookInfo)。
     */
    suspend fun getBookInfo(source: BookSource, bookUrl: String): Result<BookInfo> = withSourceEngine(source) { ctx, ruleEngine, jsEngine ->
        val analyze = AnalyzeUrl(bookUrl, baseUrl = source.bookSourceUrl, source = source, jsEngine = jsEngine)
        val resp = LegadoHttp.fetch(analyze.build())
        ctx.baseUrl = resp.finalUrl
        val rule = source.ruleBookInfo
        val root: Any = Jsoup.parse(resp.body, resp.finalUrl)
        BookInfo(
            name = rule?.name?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            author = rule?.author?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            kind = rule?.kind?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            coverUrl = rule?.coverUrl?.let { ruleEngine.getString(root, it, ctx, isUrl = true) }.orEmpty(),
            intro = rule?.intro?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            lastChapter = rule?.lastChapter?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            tocUrl = rule?.tocUrl?.let { ruleEngine.getString(root, it, ctx, isUrl = true) }.orEmpty().ifEmpty { bookUrl },
            wordCount = rule?.wordCount?.let { ruleEngine.getString(root, it, ctx) }.orEmpty(),
            bookUrl = bookUrl,
            sourceUrl = source.bookSourceUrl
        )
    }

    /**
     * 获取目录(ruleToc);处理 nextTocUrl 翻页,上限 50 页防死循环。
     */
    suspend fun getChapterList(source: BookSource, tocUrl: String): Result<List<BookChapter>> = withSourceEngine(source) { ctx, ruleEngine, jsEngine ->
        val rule = source.ruleToc ?: return@withSourceEngine emptyList()
        val chapters = mutableListOf<BookChapter>()
        val visited = HashSet<String>()
        var nextUrl: String? = tocUrl
        var pageGuard = 0
        while (nextUrl != null && pageGuard < 50 && visited.add(nextUrl)) {
            pageGuard++
            val resp = LegadoHttp.fetch(AnalyzeUrl(nextUrl, baseUrl = ctx.baseUrl, source = source, jsEngine = jsEngine).build())
            ctx.baseUrl = resp.finalUrl
            val root: Any = Jsoup.parse(resp.body, resp.finalUrl)
            val items = ruleEngine.getElements(root, rule.chapterList, ctx)
            for (el in items) {
                val title = ruleEngine.getString(el, rule.chapterName, ctx)
                val url = ruleEngine.getString(el, rule.chapterUrl, ctx, isUrl = true)
                if (title.isEmpty() || url.isEmpty()) continue
                val vip = rule.isVip?.let { ruleEngine.getString(el, it, ctx) }?.let { it == "true" || it == "1" } ?: false
                chapters.add(BookChapter(title, url, chapters.size, vip, rule.updateTime?.let { ruleEngine.getString(el, it, ctx) }.orEmpty()))
            }
            nextUrl = rule.nextTocUrl?.let { ruleEngine.getString(root, it, ctx, isUrl = true) }?.ifBlank { null }
        }
        chapters
    }

    /**
     * 获取正文(ruleContent);处理 nextContentUrl 分页拼接(≤ 20 页)+ replaceRegex 净化。
     */
    suspend fun getContent(source: BookSource, chapter: BookChapter): Result<String> = withSourceEngine(source) { ctx, ruleEngine, jsEngine ->
        val rule = source.ruleContent ?: return@withSourceEngine ""
        val sb = StringBuilder()
        val visited = HashSet<String>()
        var nextUrl: String? = chapter.url
        var pageGuard = 0
        while (nextUrl != null && pageGuard < 20 && visited.add(nextUrl)) {
            pageGuard++
            val resp = LegadoHttp.fetch(AnalyzeUrl(nextUrl, baseUrl = ctx.baseUrl, source = source, jsEngine = jsEngine).build())
            ctx.baseUrl = resp.finalUrl
            val root: Any = Jsoup.parse(resp.body, resp.finalUrl)
            val part = ruleEngine.getString(root, rule.content, ctx)
            if (part.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(part) }
            nextUrl = rule.nextContentUrl?.let { ruleEngine.getString(root, it, ctx, isUrl = true) }?.ifBlank { null }
        }
        var text = sb.toString()
        // HTML 标签净化:部分书源正文直接抓取含 <p></p><br> 等标签,
        // 默认剥离(可在阅读设置关闭,避免影响依赖 HTML 的特殊源)
        if (context != null && go.examate.hyp.media.reading.ReaderPrefs.isStripHtmlTags(context)) {
            text = stripHtmlTags(text)
        }
        // replaceRegex 净化:每行 pattern##replacement
        rule.replaceRegex?.lineSequence()?.forEach { line ->
            val parts = line.split("##")
            val pat = parts.getOrNull(0)?.trim().orEmpty()
            if (pat.isNotEmpty()) {
                text = try { Regex(pat).replace(text, parts.getOrNull(1)?.trim().orEmpty()) } catch (_: Exception) { text }
            }
        }
        // 追加用户导入的全局净化规则(在源自带 replaceRegex 之后)
        context?.let { ctx ->
            ContentRuleStore.loadAll(ctx).forEach { userRule ->
                userRule.rules.lineSequence().forEach { line ->
                    val parts = line.split("##")
                    val pat = parts.getOrNull(0)?.trim().orEmpty()
                    if (pat.isNotEmpty()) {
                        text = try { Regex(pat).replace(text, parts.getOrNull(1)?.trim().orEmpty()) } catch (_: Exception) { text }
                    }
                }
            }
        }
        text.trim()
    }

    /** 剥离 HTML 标签,保留纯文本;把 <br>/<p> 转为换行 */
    private fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            .replace(Regex("(?i)</div>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** 每源创建 JsEngine + RuleEngine + RuleContext,执行 block 后释放引擎 */
    private suspend fun <T> withSourceEngine(source: BookSource, block: suspend (RuleContext, RuleEngine, JsEngineApi?) -> T): Result<T> {
        val ctx = RuleContext(source, source.bookSourceUrl)
        val jsEngine = if (enableJs) JsEngine.createOrNull(JsExtensions(source, ctx)) else null
        val ruleEngine = RuleEngine(jsEngine)
        return try {
            runCatching { block(ctx, ruleEngine, jsEngine) }
                .onFailure { Log.w(TAG, "op fail @${source.bookSourceName}", it) }
        } finally {
            jsEngine?.close()
        }
    }

    /**
     * 聚合搜索:并发搜索所有源(每源一个 (源, 结果) 事件),边完成边回流。
     * 用 Semaphore 限制并发数,避免源过多时连接爆掉。
     * 相比串行可数倍提速(源越多提升越明显)。
     * @param concurrency 并发数(默认8,可在阅读设置调整)
     */
    fun searchAll(sources: List<BookSource>, keyword: String, concurrency: Int = 8): Flow<Pair<BookSource, Result<List<SearchBook>>>> = flow {
        val sem = kotlinx.coroutines.sync.Semaphore(concurrency.coerceIn(1, 16))
        kotlinx.coroutines.coroutineScope {
            val deferred = sources.map { src ->
                async(Dispatchers.IO) {
                    sem.withPermit { src to searchBooks(src, keyword) }
                }
            }
            // 逐个等待完成并回流(先完成的先回流)
            deferred.forEach { emit(it.await()) }
        }
    }.flowOn(Dispatchers.IO)
}
