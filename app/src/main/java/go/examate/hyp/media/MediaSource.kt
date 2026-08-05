package go.examate.hyp.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体内容源(借鉴 nexhub「源即插件」思路)。
 *
 * 应用只提供壳(界面/阅读器/播放器/解析调度),不内置任何源;
 * 「内容从哪来」由用户导入的源决定,降低版权与合规风险,站点改版只需更新源。
 *
 * type: novel(小说)/comic(漫画)/anime(动漫)/movie(影视)
 * parserType: builtin/xpath/jsonpath/css/script/webview —— 声明式或脚本式解析(壳子阶段仅存,不执行)
 */
data class MediaSource(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val searchUrl: String = "",       // 搜索地址模板,可含 {keyword} 占位
    val parserType: String = "builtin",
    val enabled: Boolean = true,
    val note: String = ""
)

/** 媒体类型分组 */
enum class MediaKind(val id: String, val label: String) {
    NOVEL("novel", "小说"),
    COMIC("comic", "漫画"),
    ANIME("anime", "动漫"),
    MOVIE("movie", "影视")
}

/**
 * 源存储 — 独立 SharedPreferences + JSON 列表(与 CustomToolStore 同款声明式配置范式)。
 * 阅读(小说/漫画)与观影(动漫/影视)共用一套存储,按 type 区分。
 */
object MediaSourceStore {

    private const val PREFS = "media_sources"
    private const val KEY = "sources"

    fun loadAll(context: Context): List<MediaSource> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        } catch (_: Exception) { emptyList() }
    }

    /** 按类型加载(小说/漫画/动漫/影视) */
    fun loadByType(context: Context, type: String): List<MediaSource> =
        loadAll(context).filter { it.type == type }

    fun save(context: Context, sources: List<MediaSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun addOrUpdate(context: Context, source: MediaSource) {
        val list = loadAll(context).filterNot { it.id == source.id } + source
        save(context, list)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, loadAll(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(context: Context, id: String) {
        save(context, loadAll(context).filterNot { it.id == id })
    }

    /**
     * 从 JSON 文本导入一个或多个源(支持单对象或数组)。
     * @return 成功导入的条数;格式非法返回 0
     */
    fun importFromJson(context: Context, text: String): Int {
        return try {
            val trimmed = text.trim()
            val incoming = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseImport(it) } }
                }
                trimmed.startsWith("{") -> listOfNotNull(parseImport(JSONObject(trimmed)))
                else -> emptyList()
            }
            if (incoming.isEmpty()) return 0
            val existing = loadAll(context).associateBy { it.id }.toMutableMap()
            incoming.forEach { existing[it.id] = it }
            save(context, existing.values.toList())
            incoming.size
        } catch (_: Exception) { 0 }
    }

    /** 解析导入的源对象(容错:缺 id 用名称+类型生成,type 非法则跳过) */
    private fun parseImport(o: JSONObject): MediaSource? {
        val name = o.optString("name").ifBlank { return null }
        val type = o.optString("type").ifBlank { return null }
        if (type !in listOf("novel", "comic", "anime", "movie")) return null
        val id = o.optString("id").ifBlank { "${type}_${name.hashCode()}" }
        return MediaSource(
            id = id,
            name = name,
            type = type,
            baseUrl = o.optString("baseUrl"),
            searchUrl = o.optString("searchUrl"),
            parserType = o.optString("parserType", "builtin"),
            enabled = o.optBoolean("enabled", true),
            note = o.optString("note")
        )
    }

    private fun toJson(s: MediaSource): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("type", s.type)
        put("baseUrl", s.baseUrl)
        put("searchUrl", s.searchUrl)
        put("parserType", s.parserType)
        put("enabled", s.enabled)
        put("note", s.note)
    }

    private fun fromJson(o: JSONObject): MediaSource = MediaSource(
        id = o.optString("id"),
        name = o.optString("name"),
        type = o.optString("type"),
        baseUrl = o.optString("baseUrl"),
        searchUrl = o.optString("searchUrl"),
        parserType = o.optString("parserType", "builtin"),
        enabled = o.optBoolean("enabled", true),
        note = o.optString("note")
    )
}
