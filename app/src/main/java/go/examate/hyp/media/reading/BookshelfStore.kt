package go.examate.hyp.media.reading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架条目 — 书源书与本地 txt 统一模型。
 *
 * bookId 作唯一键:
 *  - 书源书: "legado|{sourceUrl}|{bookUrl}"
 *  - 本地文件: "local|{文件绝对路径}"
 * 通过 bookId 即可无歧义地重建阅读来源。
 */
data class ShelfBook(
    val bookId: String,
    val name: String,
    val author: String = "",
    val coverUrl: String = "",
    val kind: String = "book",        // book(小说)/comic(漫画)/local(本地)
    // 来源重建信息
    val sourceUrl: String = "",       // 书源书:所属书源;本地:空
    val bookUrl: String = "",         // 书源书:详情/目录 url;本地:文件路径
    val tocUrl: String = "",
    val isLocal: Boolean = false,
    // 续读进度
    val lastChapterIndex: Int = 0,
    val lastChapterTitle: String = "",
    val scrollOffset: Int = 0,        // 章内首个可见段落索引
    val updateTime: Long = System.currentTimeMillis()
) {
    companion object {
        fun legadoId(sourceUrl: String, bookUrl: String) = "legado|$sourceUrl|$bookUrl"
        fun localId(path: String) = "local|$path"
    }
}

/**
 * 书架存储 — SharedPreferences + JSON 数组。收藏、进度更新、最近阅读排序。
 */
object BookshelfStore {

    private const val PREFS = "bookshelf"
    private const val KEY = "books"

    fun loadAll(context: Context): List<ShelfBook> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
                .sortedByDescending { it.updateTime }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<ShelfBook>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun contains(context: Context, bookId: String): Boolean = loadAll(context).any { it.bookId == bookId }

    fun find(context: Context, bookId: String): ShelfBook? = loadAll(context).find { it.bookId == bookId }

    /** 加入书架(已存在则更新元信息,保留进度) */
    fun add(context: Context, book: ShelfBook) {
        val list = loadAll(context).toMutableList()
        val existing = list.find { it.bookId == book.bookId }
        if (existing != null) {
            list[list.indexOf(existing)] = book.copy(
                lastChapterIndex = existing.lastChapterIndex,
                lastChapterTitle = existing.lastChapterTitle,
                scrollOffset = existing.scrollOffset,
                updateTime = System.currentTimeMillis()
            )
        } else list.add(book)
        save(context, list)
    }

    fun remove(context: Context, bookId: String) {
        save(context, loadAll(context).filterNot { it.bookId == bookId })
    }

    /** 更新续读进度(仅当已在书架时) */
    fun updateProgress(context: Context, bookId: String, chapterIndex: Int, chapterTitle: String, scrollOffset: Int) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.bookId == bookId }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            lastChapterIndex = chapterIndex,
            lastChapterTitle = chapterTitle,
            scrollOffset = scrollOffset,
            updateTime = System.currentTimeMillis()
        )
        save(context, list)
    }

    private fun toJson(b: ShelfBook): JSONObject = JSONObject().apply {
        put("bookId", b.bookId); put("name", b.name); put("author", b.author)
        put("coverUrl", b.coverUrl); put("kind", b.kind)
        put("sourceUrl", b.sourceUrl); put("bookUrl", b.bookUrl); put("tocUrl", b.tocUrl)
        put("isLocal", b.isLocal)
        put("lastChapterIndex", b.lastChapterIndex); put("lastChapterTitle", b.lastChapterTitle)
        put("scrollOffset", b.scrollOffset); put("updateTime", b.updateTime)
    }

    private fun fromJson(o: JSONObject): ShelfBook = ShelfBook(
        bookId = o.optString("bookId"),
        name = o.optString("name"),
        author = o.optString("author"),
        coverUrl = o.optString("coverUrl"),
        kind = o.optString("kind", "book"),
        sourceUrl = o.optString("sourceUrl"),
        bookUrl = o.optString("bookUrl"),
        tocUrl = o.optString("tocUrl"),
        isLocal = o.optBoolean("isLocal", false),
        lastChapterIndex = o.optInt("lastChapterIndex", 0),
        lastChapterTitle = o.optString("lastChapterTitle"),
        scrollOffset = o.optInt("scrollOffset", 0),
        updateTime = o.optLong("updateTime", 0L)
    )
}
