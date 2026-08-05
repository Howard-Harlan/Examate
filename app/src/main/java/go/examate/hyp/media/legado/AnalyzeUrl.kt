package go.examate.hyp.media.legado

import android.util.Log
import org.json.JSONObject

/**
 * URL 解析 — 把 legado 的 searchUrl/tocUrl 等规则串解析成可发起的请求。
 *
 * 支持形态:
 *  1. 纯模板: `https://x.com/search?q={{key}}&p={{page}}` 或 `.../search?q=%s`(key/page 变量)
 *  2. URL 尾巴 options: `url,{"method":"POST","body":"...","headers":{...},"charset":"gbk"}`
 *  3. @js: 生成(阶段1 不支持,isJs=true 标记,由上层跳过)
 */
class AnalyzeUrl(
    private val ruleUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private val baseUrl: String,
    private val source: BookSource,
    private val jsEngine: JsEngineApi? = null
) {
    companion object { private const val TAG = "AnalyzeUrl" }

    data class UrlRequest(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val charset: String? = null
    )

    /** 是否为阶段1 暂不支持的 JS 生成 URL */
    val isJs: Boolean get() = jsEngine == null && ruleUrl.trim().startsWith("@js:")

    /** 构建请求;JS 源在阶段1 返回原始 baseUrl(上层应先用 isJs 跳过) */
    fun build(): UrlRequest {
        var rule = ruleUrl.trim()
        if (rule.isEmpty()) return UrlRequest(baseUrl)

        // @js: 生成(脚本内常给 url 变量赋值,末尾补 ;url 取回)
        if (rule.startsWith("@js:")) {
            if (jsEngine == null) { Log.w(TAG, "@js url unsupported at stage1"); return UrlRequest(baseUrl) }
            rule = try {
                val script = fillVariables(rule.removePrefix("@js:"))
                val wrapped = if (Regex("(^|[^\\w.])url\\s*=").containsMatchIn(script)) "$script;\nurl" else script
                jsEngine.eval(wrapped, baseBindings()).ifBlank { baseUrl }
            } catch (e: Exception) { Log.w(TAG, "js url fail", e); return UrlRequest(baseUrl) }
        }

        // 切分 URL 与 options(第一个逗号后若是 { 开头则为 options)
        var urlPart = rule
        var optionsJson: String? = null
        val commaIdx = findOptionsComma(rule)
        if (commaIdx >= 0) {
            urlPart = rule.substring(0, commaIdx).trim()
            optionsJson = rule.substring(commaIdx + 1).trim()
        }

        // 变量替换
        urlPart = fillVariables(urlPart)

        var method = "GET"
        var headers = parseHeader(source.header)
        var body: String? = null
        var charset: String? = null
        if (optionsJson != null && optionsJson.startsWith("{")) {
            try {
                val o = JSONObject(optionsJson)
                method = o.optString("method", "GET").uppercase()
                body = o.optStringOrNull2("body")?.let { fillVariables(it) }
                charset = o.optStringOrNull2("charset")
                o.optJSONObject("headers")?.let { h ->
                    val m = headers.toMutableMap()
                    h.keys().forEach { k -> m[k] = h.optString(k) }
                    headers = m
                }
            } catch (e: Exception) { Log.w(TAG, "options parse fail: $optionsJson", e) }
        }

        val absUrl = NetworkUtils.absoluteUrl(baseUrl, urlPart)
        return UrlRequest(absUrl, method, headers, body, charset)
    }

    /** 填充 {{key}}/{{page}}、%s、{{page-1}} 等常见变量 */
    private fun fillVariables(s: String): String {
        var r = s
        val k = key ?: ""
        val p = page ?: 1
        r = r.replace("{{key}}", k).replace("{{page}}", p.toString())
        r = r.replace("{{searchKey}}", k).replace("{{searchPage}}", p.toString())
        // %s 占位(部分源用)
        if (r.contains("%s")) r = r.replaceFirst("%s", k)
        // 简单算术 {{page-1}} / {{page+1}}（Android ICU regex 中 } 需转义,否则 }} 报 PatternSyntaxException）
        r = Regex("\\{\\{page([+\\-])(\\d+)\\}\\}").replace(r) { m ->
            val delta = m.groupValues[2].toInt() * (if (m.groupValues[1] == "+") 1 else -1)
            (p + delta).toString()
        }
        return r
    }

    private fun baseBindings(): Map<String, Any?> = mapOf(
        "baseUrl" to baseUrl, "key" to key, "page" to page, "result" to ""
    )

    /** options 逗号定位:找第一个 ",{" 或 ", {" */
    private fun findOptionsComma(s: String): Int {
        val m = Regex(",\\s*\\{").find(s) ?: return -1
        return m.range.first
    }

    /** header 字段 → Map(支持 JSON;@js: 阶段1 忽略) */
    private fun parseHeader(header: String?): Map<String, String> {
        val h = header?.trim() ?: return defaultHeaders()
        if (h.startsWith("@js:") || h.startsWith("<js>")) return defaultHeaders()
        return try {
            val o = JSONObject(h)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            if (m.keys.none { it.equals("User-Agent", true) }) m["User-Agent"] = DEFAULT_UA
            m
        } catch (_: Exception) { defaultHeaders() }
    }

    private fun defaultHeaders() = mapOf("User-Agent" to DEFAULT_UA)
}

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private fun JSONObject.optStringOrNull2(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifBlank { null }
}
