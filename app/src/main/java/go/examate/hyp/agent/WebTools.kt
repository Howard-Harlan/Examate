package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网工具集 — 让 AI 具备联网搜索和网页抓取能力
 */
object WebTools {

    private const val TAG = "WebTools"

    /** 简单 GET 请求 */
    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed: $urlStr", e)
            null
        }
    }

    private val WebSearch = object : ToolRegistry.Tool {
        override val name = "web_search"
        override val description = "联网搜索获取实时信息(新闻、天气、最新数据等)。不要用此工具查询校园信息"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "query(搜索关键词,必填)"
        override val params = listOf(ToolRegistry.ParamDef("query", "string", "搜索关键词", true))

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val query = args.optString("query", "").ifEmpty { return ToolRegistry.Result(false, "缺少 query 参数") }
            return try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                // 抓取 Bing 国内站搜索结果页并解析(无需 API Key;DuckDuckGo 国内不可访问)
                val url = "https://cn.bing.com/search?q=$encoded&ensearch=0"
                val html = httpGet(url, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "Accept" to "text/html,application/xhtml+xml"
                )) ?: return ToolRegistry.Result(false, "搜索请求失败")
                val hits = parseBingResults(html)
                val sb = StringBuilder("搜索结果: $query\n\n")
                if (hits.isEmpty()) {
                    sb.append("未找到结果,建议用 web_fetch 抓取具体网页。")
                } else {
                    hits.take(8).forEachIndexed { i, r ->
                        sb.append("${i + 1}. ${r.title}\n")
                        if (r.snippet.isNotEmpty()) sb.append("   ${r.snippet}\n")
                        if (r.url.isNotEmpty()) sb.append("   ${r.url}\n")
                    }
                }
                ToolRegistry.Result(true, sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "web_search failed", e)
                ToolRegistry.Result(false, "搜索失败: ${e.message}")
            }
        }
    }

    private val WebFetch = object : ToolRegistry.Tool {
        override val name = "web_fetch"
        override val description = "抓取指定 URL 的网页内容(提取文本)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "url(网址,必填)"
        override val params = listOf(ToolRegistry.ParamDef("url", "string", "网页地址", true))

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val url = args.optString("url", "").ifEmpty { return ToolRegistry.Result(false, "缺少 url 参数") }
            return try {
                val html = httpGet(url, mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                )) ?: return ToolRegistry.Result(false, "抓取失败: 无法访问 URL")
                // 简易 HTML→文本
                val text = html
                    .replace(Regex("(?s)<script.*?</script>"), "")
                    .replace(Regex("(?s)<style.*?</style>"), "")
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("&nbsp;"), " ")
                    .replace(Regex("&amp;"), "&")
                    .replace(Regex("&lt;"), "<")
                    .replace(Regex("&gt;"), ">")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
                val truncated = if (text.length > 4000) text.take(4000) + "\n...(内容过长,已截断)" else text
                ToolRegistry.Result(true, "网页内容:\n$truncated")
            } catch (e: Exception) {
                Log.e(TAG, "web_fetch failed", e)
                ToolRegistry.Result(false, "抓取失败: ${e.message}")
            }
        }
    }

    /** 搜索结果条目 */
    private data class SearchHit(val title: String, val url: String, val snippet: String)

    /** 解析 Bing 结果页 HTML:每个 b_algo 块提取标题/链接/摘要 */
    private fun parseBingResults(html: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val blockRe = Regex("<li class=\"b_algo[^\"]*\"[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL))
        val titleRe = Regex("<h2[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL))
        val pRe = Regex("<p[^>]*>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (bm in blockRe.findAll(html)) {
            val block = bm.groupValues[1]
            val tm = titleRe.find(block) ?: continue
            val link = tm.groupValues[1]
            val title = stripHtml(tm.groupValues[2])
            if (title.isBlank()) continue
            val snippet = pRe.find(block)?.groupValues?.get(1)?.let { stripHtml(it) } ?: ""
            hits.add(SearchHit(title, link, snippet))
        }
        return hits
    }

    /** 去 HTML 标签 + 常见实体反转义 */
    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .trim()

    fun all(): List<ToolRegistry.Tool> = listOf(WebSearch, WebFetch)
}
