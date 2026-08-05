package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义声明式工具 — 让用户「不更新应用」即可添加工具。
 *
 * 用户配置 name/description/参数/url/method/headers/body 模板/响应提取(JSON 路径),
 * 执行时用 [HttpUtils.customRequest] 发 HTTP 请求,把参数按 {占位符} 填入 url/headers/body,
 * 再按 responsePath 从 JSON 响应提取文本返回给 AI。
 *
 * 存储:独立 SharedPreferences "custom_tools";运行时由 [ToolRegistry.registerDefaults] 动态注册。
 */
data class CustomToolSpec(
    val id: String,
    val name: String,
    val description: String,
    val method: String,                 // GET / POST / PUT / DELETE
    val url: String,                    // 可含 {参数名} 占位符
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String = "",      // 可含 {参数名} 占位符
    val responsePath: String = "",      // JSON 点分路径,空=返回整体
    val params: List<ToolRegistry.ParamDef> = emptyList()
)

object CustomToolStore {

    private const val PREFS = "custom_tools"
    private const val KEY = "specs"

    fun load(context: Context): List<CustomToolSpec> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, specs: List<CustomToolSpec>) {
        val arr = JSONArray()
        specs.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun addOrUpdate(context: Context, spec: CustomToolSpec) {
        val list = load(context).filterNot { it.id == spec.id } + spec
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    private fun toJson(s: CustomToolSpec): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("description", s.description)
        put("method", s.method)
        put("url", s.url)
        put("bodyTemplate", s.bodyTemplate)
        put("responsePath", s.responsePath)
        put("headers", JSONObject().apply { s.headers.forEach { (k, v) -> put(k, v) } })
        put("params", JSONArray().apply {
            s.params.forEach {
                put(JSONObject().apply {
                    put("name", it.name); put("type", it.type)
                    put("description", it.description); put("required", it.required)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): CustomToolSpec {
        val headers = mutableMapOf<String, String>()
        o.optJSONObject("headers")?.let { h ->
            val keys = h.keys()
            while (keys.hasNext()) { val k = keys.next(); headers[k] = h.optString(k) }
        }
        val params = o.optJSONArray("params")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                ToolRegistry.ParamDef(
                    p.optString("name"), p.optString("type", "string"),
                    p.optString("description"), p.optBoolean("required", false)
                )
            }
        }.orEmpty()
        return CustomToolSpec(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            method = o.optString("method", "GET"),
            url = o.optString("url"),
            headers = headers,
            bodyTemplate = o.optString("bodyTemplate"),
            responsePath = o.optString("responsePath"),
            params = params
        )
    }
}

object CustomToolFactory {

    /** 把一个自定义工具规格构建为可注册的动态 Tool */
    fun toTool(spec: CustomToolSpec): ToolRegistry.Tool = object : ToolRegistry.Tool {
        override val name = spec.name
        override val description = spec.description
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint =
            spec.params.joinToString(", ") { "${it.name}(${it.description})" }
        override val params = spec.params
        override val displayLabel = "调用 ${spec.name}..."

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            // 占位符替换:{参数名} → 参数值
            fun fill(template: String): String {
                var s = template
                spec.params.forEach { p -> s = s.replace("{${p.name}}", args.optString(p.name, "")) }
                return s
            }
            val url = fill(spec.url)
            val body = fill(spec.bodyTemplate)
            val headers = spec.headers.mapValues { fill(it.value) }
            val resp = withContext(Dispatchers.IO) {
                HttpUtils.customRequest(spec.method, url, headers, body.ifEmpty { null })
            } ?: return ToolRegistry.Result(false, "调用失败: ${spec.name}(网络错误或地址无效)")
            return ToolRegistry.Result(true, extractPath(resp, spec.responsePath))
        }
    }

    /** 按点分路径从 JSON 响应提取文本;路径为空或提取失败则返回整体(截断) */
    private fun extractPath(json: String, path: String): String {
        val whole = json.take(4000)
        if (path.isBlank()) return whole
        return try {
            var cur: Any? = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
            for (seg in path.split(".")) {
                cur = when (val c = cur) {
                    is JSONObject -> c.opt(seg)
                    is JSONArray -> seg.toIntOrNull()?.let { c.opt(it) }
                    else -> null
                } ?: return whole
            }
            cur.toString()
        } catch (_: Exception) { whole }
    }
}
