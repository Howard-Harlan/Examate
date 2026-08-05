package go.examate.hyp.music.lx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * LX 音源(脚本)条目。
 * @param sources 脚本声明支持的平台(wy/kg/kw/tx/mg)→ 各自支持的音质列表
 */
data class LxSource(
    val id: String,
    val name: String,
    val version: String,
    val scriptFile: String,       // filesDir/lx/<id>.js
    val sources: Map<String, List<String>> = emptyMap(),
    val enabled: Boolean = true
)

/**
 * LX 音源存储 — 脚本落盘 filesDir/lx/,元信息存 SharedPreferences JSON。
 * 默认不内置任何源(源即插件),用户经文件/链接导入。
 */
object LxSourceStore {
    private const val DIR = "lx"
    private const val PREFS = "lx_sources"
    private const val KEY = "sources"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun loadAll(context: Context): List<LxSource> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        } catch (_: Exception) { emptyList() }
    }

    fun loadEnabled(context: Context): List<LxSource> = loadAll(context).filter { it.enabled }

    fun readScript(context: Context, source: LxSource): String =
        runCatching { File(source.scriptFile).readText() }.getOrDefault("")

    /**
     * 导入脚本文本。解析头部注释元信息(@name/@version),落盘并登记。
     * @return 导入的源;失败返回 null
     */
    fun importScript(context: Context, script: String): LxSource? {
        // 校验:排除明显的非脚本内容
        val trimmed = script.trimStart()
        if (trimmed.startsWith("<")) return null  // HTML 页面(如 404)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null  // URL,非脚本
        if (trimmed.isEmpty() || trimmed.length < 50) return null  // 太短,不是有效脚本
        // 校验:LX 音源脚本通常含 @name/@version 头部注释,或含 lx.on / globalThis.lx 调用
        // 注意混淆脚本可能把 lx.on 编码成 lx['\x6f\x6e'],所以不能只靠字符串匹配
        val hasHeader = script.contains("@name") || script.contains("@version")
        val hasLxRef = script.contains("lx.on(") || script.contains("globalThis.lx") ||
            script.contains("lx[") || script.contains(".lx") || script.contains(" lx ")
        if (!hasHeader && !hasLxRef) return null
        val name = headerField(script, "name").ifBlank { "LX音源_${System.currentTimeMillis() % 100000}" }
        val version = headerField(script, "version").ifBlank { "1.0" }
        val id = "lx_${name.hashCode().toUInt()}_${System.currentTimeMillis() % 100000}"
        val file = File(dir(context), "$id.js")
        runCatching { file.writeText(script) } ?: return null
        val src = LxSource(id = id, name = name, version = version, scriptFile = file.absolutePath)
        val list = loadAll(context).filterNot { it.id == id }.toMutableList()
        list.add(0, src)
        persist(context, list)
        return src
    }

    /** 更新源的声明信息(引擎跑出 inited 后回填 sources) */
    fun updateSources(context: Context, id: String, sources: Map<String, List<String>>) {
        val list = loadAll(context).map { if (it.id == id) it.copy(sources = sources) else it }
        persist(context, list)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        persist(context, loadAll(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(context: Context, id: String) {
        loadAll(context).find { it.id == id }?.let { runCatching { File(it.scriptFile).delete() } }
        persist(context, loadAll(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, list: List<LxSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** 取脚本头部注释字段:// @name xxx  或  @version xxx */
    private fun headerField(script: String, field: String): String {
        val re = Regex("@$field\\s+(.+)")
        return re.find(script.take(2000))?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun toJson(s: LxSource): JSONObject = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("version", s.version)
        put("scriptFile", s.scriptFile); put("enabled", s.enabled)
        put("sources", JSONObject().apply { s.sources.forEach { (k, v) -> put(k, JSONArray(v)) } })
    }

    private fun fromJson(o: JSONObject): LxSource {
        val srcObj = o.optJSONObject("sources")
        val map = mutableMapOf<String, List<String>>()
        srcObj?.keys()?.forEach { k ->
            val arr = srcObj.optJSONArray(k) ?: JSONArray()
            map[k] = (0 until arr.length()).map { arr.optString(it) }
        }
        return LxSource(
            id = o.optString("id"), name = o.optString("name"),
            version = o.optString("version"), scriptFile = o.optString("scriptFile"),
            sources = map, enabled = o.optBoolean("enabled", true)
        )
    }
}
