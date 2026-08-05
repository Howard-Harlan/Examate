package go.examate.hyp.campus

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ChnuClient] 各子系统共享的 HTML/JSON 解析工具。
 *
 * 从原 [ChnuClient] 私有 helper 提取,供 [JwxtApi]、[EcardApi]、[ScApi]、[HsxsApi] 复用。
 */
internal object ChnuHtmlHelpers {

    /** 解析 JSON 响应,兼容 `(json)` 包裹格式 */
    fun parseJsonObject(text: String): JSONObject? {
        var t = text.trim()
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length - 1)
        return try { JSONObject(t) } catch (e: Exception) { null }
    }

    /** 解析 JSON 数组,兼容 `(json)` 包裹格式 */
    fun parseJsonArray(text: String): JSONArray? {
        var t = text.trim()
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length - 1)
        return try { JSONArray(t) } catch (e: Exception) { null }
    }

    /** 从学生信息 HTML 解析标签-值对(col-xs-2/col-xs-3 与表格) */
    fun parseStudentInfoHtml(html: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        // col-xs-2 (label) + 紧邻 col-xs-3 (value)
        val labelRegex = Regex("""<div[^>]*class="[^"]*col-xs-2[^"]*"[^>]*>([\s\S]*?)</div>""")
        val allDivs = Regex("""<div[^>]*class="[^"]*col-xs-3[^"]*"[^>]*>([\s\S]*?)</div>""").findAll(html).toList()
        labelRegex.findAll(html).forEachIndexed { idx, m ->
            val label = stripTags(m.groupValues[1]).trim().trimEnd('：', ':')
            val value = allDivs.getOrNull(idx)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
            if (label.isNotEmpty() && value.isNotEmpty()) info[label] = value
        }
        // 兼容表格结构
        if (info.isEmpty()) {
            val tableMatch = Regex("""<table[^>]*class="[^"]*table[^"]*"[^>]*>([\s\S]*?)</table>""").find(html)
            tableMatch?.let {
                val rows = Regex("""<tr[^>]*>([\s\S]*?)</tr>""").findAll(it.groupValues[1]).toList()
                rows.forEach { row ->
                    val cells = Regex("""<t[hd][^>]*>([\s\S]*?)</t[hd]>""").findAll(row.value)
                        .map { c -> stripTags(c.groupValues[1]).trim() }.toList()
                    var i = 0
                    while (i + 1 < cells.size) {
                        val k = cells[i].trimEnd('：', ':')
                        val v = cells[i + 1]
                        if (k.isNotEmpty() && v.isNotEmpty()) info[k] = v
                        i += 2
                    }
                }
            }
        }
        return info
    }

    /** 去除 HTML 标签,保留文本 */
    fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")

    /** 将可能的相对 URL 绝对化(以 http/https 开头则原样,否则拼接 baseUrl) */
    fun absolutize(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            val base = baseUrl.toHttpUrl()
            return "${base.scheme}://${base.host}${if (base.port != 80 && base.port != 443 && base.port > 0) ":${base.port}" else ""}$url"
        }
        return "$baseUrl/$url"
    }
}
