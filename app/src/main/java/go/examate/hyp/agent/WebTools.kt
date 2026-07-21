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
        override val description = "联网搜索获取实时信息(新闻、天气、最新数据等)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "query(搜索关键词,必填)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val query = args.optString("query", "").ifEmpty { return ToolRegistry.Result(false, "缺少 query 参数") }
            return try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                // 使用 DuckDuckGo Instant Answer API(无需 API Key)
                val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val raw = httpGet(url, mapOf("Accept" to "application/json")) ?: return ToolRegistry.Result(false, "搜索请求失败")
                val resp = JSONObject(raw)
                val sb = StringBuilder("搜索结果: $query\n\n")

                val abstract = resp.optString("AbstractText", "")
                if (abstract.isNotEmpty()) {
                    sb.append("$abstract\n")
                    resp.optString("AbstractURL", "").let { if (it.isNotEmpty()) sb.append("来源: $it\n") }
                }

                val topics = resp.optJSONArray("RelatedTopics")
                if (topics != null && topics.length() > 0) {
                    sb.append("\n相关结果:\n")
                    val count = minOf(topics.length(), 8)
                    for (i in 0 until count) {
                        val topic = topics.optJSONObject(i)
                        val text = topic?.optString("Text", "") ?: ""
                        if (text.isNotEmpty()) sb.append("${i + 1}. $text\n")
                    }
                }

                if (abstract.isEmpty() && (topics == null || topics.length() == 0)) {
                    sb.append("未找到直接结果,建议用 web_fetch 抓取具体网页。")
                }
                ToolRegistry.Result(true, sb.toString())
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

    fun all(): List<ToolRegistry.Tool> = listOf(WebSearch, WebFetch)
}
