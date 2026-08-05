package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.media.legado.BookSourceRepository
import go.examate.hyp.media.legado.BookSourceStore
import go.examate.hyp.media.reader.LegadoTextProvider
import go.examate.hyp.media.reader.LocalTextProvider
import go.examate.hyp.media.reader.TextProvider
import go.examate.hyp.media.reading.BookshelfStore
import go.examate.hyp.media.reading.ReaderPrefs
import go.examate.hyp.media.reading.ShelfBook
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/**
 * 阅读查询工具集 — 让 agent 能搜书、看书架、读章节正文、管理书源。
 *
 * 复用阅读模块的 BookSourceRepository / BookshelfStore / TextProvider,只读为主;
 * 仅 import_book_source 为写操作(PRIVILEGED)。
 */
object ReadingTools {

    /** 搜索类型 → bookSourceType 映射(book=0文本, comic=2漫画, audio=1音频) */
    private fun sourceTypeOf(type: String): Int? = when (type) {
        "book" -> 0
        "comic" -> 2
        "audio" -> 1
        else -> null
    }

    /** bookSourceType → 可读名称 */
    private fun typeName(type: Int): String = when (type) {
        0 -> "小说"
        1 -> "听书"
        2 -> "漫画"
        3 -> "文件"
        else -> "未知"
    }

    /** 1. 聚合搜索已启用书源 */
    private val SearchBooks = object : ToolRegistry.Tool {
        override val name = "search_books"
        override val description = "聚合搜索书源(小说/漫画/听书),返回匹配的书名、作者、来源、bookUrl"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "keyword(关键词,必填), type(类型 book/comic/audio,可选,默认 book)"
        override val params = listOf(
            ToolRegistry.ParamDef("keyword", "string", "搜索关键词", true),
            ToolRegistry.ParamDef("type", "string", "类型 book/comic/audio,默认 book", false)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val keyword = args.optString("keyword", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 keyword 参数") }
            val type = args.optString("type", "book").trim().ifEmpty { "book" }
            val sourceType = sourceTypeOf(type)
                ?: return ToolRegistry.Result(false, "type 仅支持 book/comic/audio")

            val sources = BookSourceStore.loadEnabled(context, null)
                .filter { it.bookSourceType == sourceType }
            if (sources.isEmpty()) {
                return ToolRegistry.Result(true, "无已启用的${typeName(sourceType)}源,请先导入并启用书源。")
            }

            val concurrency = ReaderPrefs.getSearchConcurrency(context)
            val repo = BookSourceRepository()
            val results = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var timedOut = false
            try {
                withTimeout(15_000) {
                    repo.searchAll(sources, keyword, concurrency).collect { (src, res) ->
                        res.onSuccess { list ->
                            list.forEach { b ->
                                results.add("${b.name} | ${b.author} | ${b.sourceName} | ${b.bookUrl}")
                            }
                        }.onFailure {
                            errors.add("${src.bookSourceName}: ${it.message ?: "未知错误"}")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            }

            val sb = StringBuilder()
            sb.append("搜索 \"$keyword\" (${typeName(sourceType)}) — 命中 ${results.size} 条")
            if (timedOut) sb.append("(15秒超时,仅显示已返回结果)")
            sb.append("\n")
            if (results.isEmpty()) {
                sb.append("未找到匹配结果。")
            } else {
                results.take(30).forEach { sb.append(it).append("\n") }
                if (results.size > 30) sb.append("...(共 ${results.size} 条,仅显示前 30 条)")
            }
            if (errors.isNotEmpty()) {
                sb.append("\n失败源: ").append(errors.size).append("\n")
                errors.take(5).forEach { sb.append("- ").append(it).append("\n") }
            }
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 2. 列出书架 */
    private val ListShelf = object : ToolRegistry.Tool {
        override val name = "list_shelf"
        override val description = "列出书架中的所有书(按最近阅读排序)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val books = BookshelfStore.loadAll(context)
            if (books.isEmpty()) {
                return ToolRegistry.Result(true, "书架为空。")
            }
            val sb = StringBuilder("书架共 ${books.size} 本:\n")
            books.forEach { b ->
                val progress = if (b.lastChapterTitle.isNotEmpty() || b.lastChapterIndex > 0)
                    "第${b.lastChapterIndex + 1}章" else "未读"
                val lastCh = b.lastChapterTitle.ifEmpty { "—" }
                sb.append("${b.name} | ${b.author.ifEmpty { "未知" }} | ${b.kind} | $lastCh | $progress\n")
            }
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 3. 获取章节目录 */
    private val GetChapterList = object : ToolRegistry.Tool {
        override val name = "get_chapter_list"
        override val description = "获取指定书的章节目录(最多 100 条)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "bookId(书ID,必填)"
        override val params = listOf(
            ToolRegistry.ParamDef("bookId", "string", "书架中的 bookId", true)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val bookId = args.optString("bookId", "")
                .ifEmpty { return ToolRegistry.Result(false, "缺少 bookId 参数") }
            val book = BookshelfStore.find(context, bookId)
                ?: return ToolRegistry.Result(false, "书架中未找到该书: $bookId")

            val provider = buildProvider(context, book)
                ?: return ToolRegistry.Result(false, "无法构造内容源(书源可能已被删除)")
            val chapters = provider.loadChapters()
            if (chapters.isEmpty()) {
                return ToolRegistry.Result(false, "未获取到章节目录。")
            }
            val sb = StringBuilder("《${book.name}》共 ${chapters.size} 章:\n")
            chapters.take(100).forEach { c ->
                sb.append("[${c.index}] ${c.title}\n")
            }
            if (chapters.size > 100) sb.append("...(共 ${chapters.size} 章,仅显示前 100 章)")
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 4. 获取章节正文 */
    private val GetChapterContent = object : ToolRegistry.Tool {
        override val name = "get_chapter_content"
        override val description = "获取指定书指定章节的正文纯文本"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "bookId(书ID,必填), index(章节索引,必填)"
        override val params = listOf(
            ToolRegistry.ParamDef("bookId", "string", "书架中的 bookId", true),
            ToolRegistry.ParamDef("index", "number", "章节索引(从 0 开始)", true)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val bookId = args.optString("bookId", "")
                .ifEmpty { return ToolRegistry.Result(false, "缺少 bookId 参数") }
            val index = args.optInt("index", -1)
            if (index < 0) return ToolRegistry.Result(false, "缺少或非法 index 参数")
            val book = BookshelfStore.find(context, bookId)
                ?: return ToolRegistry.Result(false, "书架中未找到该书: $bookId")
            val provider = buildProvider(context, book)
                ?: return ToolRegistry.Result(false, "无法构造内容源(书源可能已被删除)")

            // loadChapters 内部缓存章节列表,loadContent 依赖之(LegadoTextProvider 需先加载目录)
            provider.loadChapters()
            val content = provider.loadContent(index)
            if (content.isEmpty()) {
                return ToolRegistry.Result(false, "未获取到章节内容(索引 $index 可能越界)。")
            }
            val text = if (content.length > 8000) content.take(8000) + "\n...(截断)" else content
            return ToolRegistry.Result(true, text)
        }
    }

    /** 5. 列出书源 */
    private val ListBookSources = object : ToolRegistry.Tool {
        override val name = "list_book_sources"
        override val description = "列出全部书源(可选按类型过滤)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "type(类型 book/comic/audio,可选)"
        override val params = listOf(
            ToolRegistry.ParamDef("type", "string", "类型 book/comic/audio,可选", false)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val typeRaw = args.optString("type", "").trim()
            val typeFilter = if (typeRaw.isNotEmpty()) sourceTypeOf(typeRaw) else null
            if (typeRaw.isNotEmpty() && typeFilter == null) {
                return ToolRegistry.Result(false, "type 仅支持 book/comic/audio")
            }
            val sources = BookSourceStore.loadAll(context)
                .filter { typeFilter == null || it.bookSourceType == typeFilter }
            if (sources.isEmpty()) {
                return ToolRegistry.Result(true, "无书源。")
            }
            val sb = StringBuilder("共 ${sources.size} 个书源:\n")
            sources.forEach { s ->
                val status = if (s.enabled) "启用" else "禁用"
                sb.append("${s.bookSourceName} | ${s.bookSourceUrl} | $status | ${typeName(s.bookSourceType)}\n")
            }
            return ToolRegistry.Result(true, sb.toString().trimEnd())
        }
    }

    /** 6. 导入书源(JSON) — PRIVILEGED 写操作 */
    private val ImportBookSource = object : ToolRegistry.Tool {
        override val name = "import_book_source"
        override val description = "从 JSON 文本导入书源(按 bookSourceUrl 去重覆盖)"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "content(书源 JSON 文本,必填)"
        override val params = listOf(
            ToolRegistry.ParamDef("content", "string", "书源 JSON 文本(单对象或数组)", true)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val content = args.optString("content", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 content 参数") }
            val n = BookSourceStore.importFromJson(context, content)
            return if (n > 0) {
                ToolRegistry.Result(true, "成功导入 $n 个书源。")
            } else {
                ToolRegistry.Result(false, "导入失败:JSON 解析失败或为空。")
            }
        }
    }

    /** 7. 打开书架中的书继续阅读(启动阅读器)— PRIVILEGED 操作 */
    private val OpenBook = object : ToolRegistry.Tool {
        override val name = "open_book"
        override val description = "打开书架中的某本书继续阅读(启动阅读器)"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "bookId(书架书籍ID,必填)"
        override val params = listOf(
            ToolRegistry.ParamDef("bookId", "string", "书架书籍ID(从 list_shelf 获取)", true)
        )
        override val displayLabel = "打开书籍..."

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val bookId = args.optString("bookId", "").ifEmpty {
                return ToolRegistry.Result(false, "缺少 bookId 参数")
            }
            // 检查书架是否有这本书
            val book = BookshelfStore.find(context, bookId)
                ?: return ToolRegistry.Result(false, "书架中未找到 bookId=$bookId 的书籍")
            // 启动 ReadingActivity 传 bookId,触发续读 deep link
            val intent = android.content.Intent(context, go.examate.hyp.ReadingActivity::class.java).apply {
                putExtra("bookId", bookId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ToolRegistry.Result(true, "正在打开《${book.name}》...")
        }
    }

    /** 构造 TextProvider:本地书用 LocalTextProvider,书源书用 LegadoTextProvider */
    private fun buildProvider(context: Context, book: ShelfBook): TextProvider? {
        return if (book.isLocal) {
            LocalTextProvider(File(book.bookUrl), book.name)
        } else {
            val source = BookSourceStore.loadAll(context).find { it.bookSourceUrl == book.sourceUrl }
                ?: return null
            LegadoTextProvider(context, source, book.tocUrl, book.name)
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(
        SearchBooks, ListShelf, GetChapterList, GetChapterContent,
        ListBookSources, ImportBookSource, OpenBook
    )
}
