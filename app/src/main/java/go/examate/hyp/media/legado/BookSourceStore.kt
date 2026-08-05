package go.examate.hyp.media.legado

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 书源存储 — 文件存储(书源 JSON 体积大,不进 SharedPreferences)。
 *
 * 路径: filesDir/legado/book_sources.json(rawJson 拼数组,保真)。
 * 内存缓存 + 显式 reload;按 bookSourceUrl 去重覆盖。
 */
object BookSourceStore {

    private const val DIR = "legado"
    private const val FILE = "book_sources.json"

    @Volatile
    private var cache: List<BookSource>? = null

    private fun file(context: Context): File {
        val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        return File(dir, FILE)
    }

    fun loadAll(context: Context): List<BookSource> {
        cache?.let { return it }
        val f = file(context)
        val list = if (f.exists()) {
            try { BookSourceParser.parseList(f.readText()) } catch (_: Exception) { emptyList() }
        } else emptyList()
        cache = list
        return list
    }

    /** 按类型加载已启用源(type=null 取全部启用) */
    fun loadEnabled(context: Context, type: Int? = null): List<BookSource> =
        loadAll(context).filter { it.enabled && (type == null || it.bookSourceType == type) }

    /** 保存并刷新缓存 */
    private fun persist(context: Context, list: List<BookSource>) {
        val arr = JSONArray()
        // 用 rawJson 保真回写;rawJson 为空则跳过(理论不会)
        list.forEach { if (it.rawJson.isNotEmpty()) arr.put(org.json.JSONObject(it.rawJson)) }
        file(context).writeText(arr.toString())
        cache = list
    }

    /**
     * 导入源 JSON(单对象或数组),按 bookSourceUrl 去重覆盖。
     * @return 成功导入条数
     */
    fun importFromJson(context: Context, text: String): Int {
        val incoming = BookSourceParser.parseList(text)
        if (incoming.isEmpty()) return 0
        val map = loadAll(context).associateBy { it.bookSourceUrl }.toMutableMap()
        incoming.forEach { map[it.bookSourceUrl] = it }
        persist(context, map.values.toList())
        return incoming.size
    }

    fun setEnabled(context: Context, sourceUrl: String, enabled: Boolean) {
        persist(context, loadAll(context).map { if (it.bookSourceUrl == sourceUrl) it.copy(enabled = enabled, rawJson = updateRawJson(it.rawJson, "enabled", enabled)) else it })
    }

    /** 批量启用/禁用 */
    fun batchSetEnabled(context: Context, sourceUrls: Set<String>, enabled: Boolean) {
        persist(context, loadAll(context).map {
            if (it.bookSourceUrl in sourceUrls) it.copy(enabled = enabled, rawJson = updateRawJson(it.rawJson, "enabled", enabled)) else it
        })
    }

    fun delete(context: Context, sourceUrl: String) {
        persist(context, loadAll(context).filterNot { it.bookSourceUrl == sourceUrl })
    }

    /** 批量删除 */
    fun batchDelete(context: Context, sourceUrls: Set<String>) {
        persist(context, loadAll(context).filterNot { it.bookSourceUrl in sourceUrls })
    }

    /** 更新单个源字段并同步 rawJson */
    fun update(context: Context, sourceUrl: String, name: String?, type: Int?, searchUrl: String?, enabled: Boolean?) {
        persist(context, loadAll(context).map { src ->
            if (src.bookSourceUrl == sourceUrl) {
                val newName = name ?: src.bookSourceName
                val newType = type ?: src.bookSourceType
                val newSearchUrl = searchUrl ?: src.searchUrl
                val newEnabled = enabled ?: src.enabled
                // 同步 rawJson
                var raw = src.rawJson
                raw = updateRawJson(raw, "bookSourceName", newName)
                raw = updateRawJson(raw, "bookSourceType", newType)
                if (newSearchUrl != null) raw = updateRawJson(raw, "searchUrl", newSearchUrl)
                raw = updateRawJson(raw, "enabled", newEnabled)
                src.copy(bookSourceName = newName, bookSourceType = newType, searchUrl = newSearchUrl, enabled = newEnabled, rawJson = raw)
            } else src
        })
    }

    /** 重建 rawJson 中某字段(字符串/Int/Boolean 自动判断类型) */
    private fun updateRawJson(rawJson: String, key: String, value: Any?): String {
        if (rawJson.isEmpty()) return rawJson
        return try {
            val o = org.json.JSONObject(rawJson)
            when (value) {
                is String -> o.put(key, value)
                is Int -> o.put(key, value)
                is Boolean -> o.put(key, value)
                else -> {}
            }
            o.toString()
        } catch (_: Exception) { rawJson }
    }

    /** 保真导出(拼 rawJson 数组) */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        loadAll(context).forEach { if (it.rawJson.isNotEmpty()) arr.put(org.json.JSONObject(it.rawJson)) }
        return arr.toString()
    }
}
