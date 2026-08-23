package go.examate.hyp.music.lx

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    private const val KEY_ACTIVE = "active"

    /**
     * 版本号:导入/删除/启停/回填后自增 — UI 层把它放进 remember key,
     * 音源变更时列表自动刷新(修复导入成功但列表不刷新)。
     */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private fun bump() { version++ }

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

    // ═══ 单激活源模型(对标落雪:同一时间只有一个源作为解析源) ═══

    fun activeId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    /** 切换激活源(UI 点选/导入自动切换);bump 驱动列表刷新 */
    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply()
        bump()
    }

    /** 当前激活源:activeId 对应的已启用源;未设置/已禁用/已删除时回退第一个启用源 */
    fun activeSource(context: Context): LxSource? {
        val enabled = loadEnabled(context)
        if (enabled.isEmpty()) return null
        return enabled.firstOrNull { it.id == activeId(context) } ?: enabled.first()
    }

    fun readScript(context: Context, source: LxSource): String =
        runCatching { File(source.scriptFile).readText() }.getOrDefault("")

    /**
     * 导入脚本文本。解析头部注释元信息(@name/@version),落盘并登记。
     * 与落雪官方一致:以头部块注释为合法脚本标志,原文保存不解码;
     * 兼容"整体 base64 发行"形态:仅当内容非头注释开头且具 base64 特征时解码一次,
     * 解码结果仍是非脚本则用原文(不破坏明文脚本,也不拖慢导入)。
     * @return 导入的源;失败返回 null
     */
    fun importScript(context: Context, script: String): LxSource? {
        val content = decodeIfNeeded(script)
        // 校验:排除明显的非脚本内容
        val trimmed = content.trimStart()
        if (trimmed.startsWith("<")) return null  // HTML 页面(如 404)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null  // URL,非脚本
        if (trimmed.isEmpty() || trimmed.length < 50) return null  // 太短,不是有效脚本
        // 校验:官方以 /* 头注释为合法脚本标志;兼容无头注释但含 lx 注册的脚本
        // 注意混淆脚本可能把 lx.on 编码成 lx['\x6f\x6e'],所以不能只靠字符串匹配
        val hasOfficialHeader = trimmed.startsWith("/*")
        val hasHeader = content.contains("@name") || content.contains("@version")
        val hasLxRef = content.contains("lx.on(") || content.contains("globalThis.lx") ||
            content.contains("lx[") || content.contains(".lx") || content.contains(" lx ")
        if (!hasOfficialHeader && !hasHeader && !hasLxRef) return null
        // 断尾检测:下载截断的脚本导入后 sign 必变、解析必失败。
        // 注意:完整脚本末尾可能带换行符(trim 后再看最后一个有效字符;保存仍用原文保 md5)
        val trimmedEnd = content.trimEnd()
        if (trimmedEnd.isEmpty()) return null
        val lastCh = trimmedEnd.last()
        if (lastCh !in ";})\"'`") return null
        val name = headerField(content, "name").ifBlank { "LX音源_${System.currentTimeMillis() % 100000}" }
        val version = headerField(content, "version").ifBlank { "1.0" }
        val id = "lx_${name.hashCode().toUInt()}_${System.currentTimeMillis() % 100000}"
        val file = File(dir(context), "$id.js")
        runCatching { file.writeText(content) } ?: return null
        val src = LxSource(id = id, name = name, version = version, scriptFile = file.absolutePath)
        val list = loadAll(context).filterNot { it.id == id }.toMutableList()
        list.add(0, src)
        persist(context, list)
        // 对标落雪:导入新源自动切换为当前使用源
        setActive(context, id)
        return src
    }

    /**
     * 整体 base64 发行形态的按需解码(单次,特征门控):
     *  - 已是头块注释/行注释开头的明文脚本:直接返回(官方路径,零开销)
     *  - 其余内容:前 300 字符全为 base64 字母表(含换行)才尝试解码一次;
     *    解码产物像脚本(头注释/@name/lx 引用)则采用,否则返回原文
     *
     * ⚠️ 明文路径必须返回原文 s(t 仅作检测用):脚本 sign 按全文字节 md5 计,
     * 末尾换行符被 trim 掉 = md5 变化 = 服务端必拒签(之前所有"残缺脚本"的真凶)。
     */
    private fun decodeIfNeeded(s: String): String {
        val t = s.trim()
        if (t.length < 200) return s
        if (t.startsWith("/*") || t.startsWith("//") || t.startsWith("(") ||
            t.startsWith("var ") || t.startsWith("const ") || t.startsWith("let ")
        ) return s
        // 特征门控:采样前 300 字符,必须全是 base64 字母表(容忍换行/空格)
        if (!t.take(300).all {
                it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' ||
                    it == '-' || it == '_' || it == '\n' || it == '\r' || it == ' '
            }) return s
        val decoded = runCatching {
            val normalized = if (t.contains('-') || t.contains('_')) t.replace('-', '+').replace('_', '/') else t
            String(android.util.Base64.decode(normalized, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return s
        if (decoded.contains('\u0000')) return s   // 二进制,非文本脚本
        val dt = decoded.trimStart()
        val looksLikeScript = dt.startsWith("/*") || dt.startsWith("//") || dt.startsWith("(") ||
            decoded.contains("@name") || decoded.contains("lx.on(") || decoded.contains("lx[")
        return if (looksLikeScript) decoded else s
    }

    /** 更新源的声明信息(引擎跑出 inited 后回填 sources) */
    fun updateSources(context: Context, id: String, sources: Map<String, List<String>>) {
        val list = loadAll(context).map { if (it.id == id) it.copy(sources = sources) else it }
        persist(context, list)
        bump()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        persist(context, loadAll(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
        bump()
    }

    fun delete(context: Context, id: String) {
        loadAll(context).find { it.id == id }?.let { runCatching { File(it.scriptFile).delete() } }
        // 同步丢弃池内引擎,避免旧脚本继续被复用
        runCatching { LxEnginePool.drop(id) }
        val remaining = loadAll(context).filterNot { it.id == id }
        persist(context, remaining)
        // 删掉的是激活源 → 回退到剩余第一个(无剩余则清除)
        if (activeId(context) == id) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = remaining.firstOrNull()?.id
            if (next != null) prefs.edit().putString(KEY_ACTIVE, next).apply()
            else prefs.edit().remove(KEY_ACTIVE).apply()
        }
        bump()
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
