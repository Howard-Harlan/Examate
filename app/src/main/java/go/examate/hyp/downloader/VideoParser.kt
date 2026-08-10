package go.examate.hyp.downloader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 视频平台链接解析器 — 从分享链接提取无水印视频直链。
 *
 * 支持的平台:
 * - **抖音**: 分享短链 → 提取 play_addr.uri → 构造无水印地址(aweme.snssdk.com/aweme/v1/play/?video_id=xxx)
 *             或直接将 playwm 替换为 play
 * - **豆包/通用**: 通过第三方解析 API (hd.188166.top) 获取直链,抖音等平台也可走此通道
 *
 * 使用方式:
 * ```
 * val result = VideoParser.parse("https://v.douyin.com/xxxxx/")
 * result?.videoUrl?.let { DownloadCenter.add(it) }
 * ```
 */
object VideoParser {

    private const val TAG = "VideoParser"

    /** 第三方解析 API(豆包/通用) */
    private const val PARSE_API_URL = "https://hd.188166.top:9443/api/parse"

    /** 解析结果 */
    data class ParseResult(
        val videoUrl: String,       // 无水印视频直链
        val title: String = "",     // 视频标题
        val author: String = "",    // 作者
        val coverUrl: String = "",  // 封面图 URL
        val source: String = ""     // 来源平台(抖音/豆包等)
    )

    /** 解析失败异常 */
    class ParseException(message: String) : Exception(message)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ════════════════════════════════════════
    //  平台识别
    // ════════════════════════════════════════

    /** 判断是否为抖音分享链接 */
    fun isDouyin(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("v.douyin.com") ||
               lower.contains("iesdouyin.com") ||
               lower.contains("douyin.com/share") ||
               lower.contains("amemv.com")
    }

    /** 判断是否为豆包分享链接 */
    fun isDoubao(url: String): Boolean {
        return url.contains("doubao.com")
    }

    /** 判断是否为需要解析的视频平台链接(非直链) */
    fun isVideoPlatformLink(url: String): Boolean {
        val lower = url.lowercase()
        // 排除明显的直链(以视频格式结尾)
        val directExts = listOf(".mp4", ".m4v", ".mkv", ".mov", ".avi", ".flv", ".ts", ".m3u8")
        if (directExts.any { lower.contains(it) }) return false
        return isDouyin(url) || isDoubao(url) ||
               lower.contains("bilibili.com") ||
               lower.contains("kuaishou.com") ||
               lower.contains("xiaohongshu.com") ||
               lower.contains("weishi.qq.com")
    }

    // ════════════════════════════════════════
    //  解析入口
    // ════════════════════════════════════════

    /**
     * 解析视频链接,自动识别平台。
     *
     * - 抖音: 优先用原生方式(提取 uri 构造无水印地址)
     * - 其他平台(含抖音 fallback): 走通用解析 API
     *
     * @throws ParseException 解析失败
     */
    suspend fun parse(url: String): ParseResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        when {
            isDouyin(trimmed) -> {
                // 抖音优先用原生解析,失败则回退通用 API
                try {
                    parseDouyin(trimmed)
                } catch (e: Exception) {
                    Log.w(TAG, "抖音原生解析失败,回退通用 API: ${e.message}")
                    parseViaApi(trimmed)
                }
            }
            else -> parseViaApi(trimmed)
        }
    }

    // ════════════════════════════════════════
    //  抖音原生无水印解析
    // ════════════════════════════════════════

    /**
     * 抖音无水印解析。
     *
     * 原理:
     * 1. 请求分享短链,跟随重定向获取视频页面 URL
     * 2. 从 URL 中提取 item_id(视频 ID)
     * 3. 请求 iesdouyin.com 详情 API 获取视频信息
     * 4. 识别 play_addr 中的 uri 参数,构造无水印播放地址:
     *    `https://aweme.snssdk.com/aweme/v1/play/?video_id={uri}`
     *    或直接将 play_addr URL 中的 playwm 替换为 play
     */
    private suspend fun parseDouyin(url: String): ParseResult {
        // ── 1. 获取重定向后的 URL,提取 item_id ──
        val itemId = extractDouyinItemId(url)
        if (itemId.isEmpty()) throw ParseException("无法提取抖音视频 ID")

        // ── 2. 请求视频详情 API ──
        val detailUrl = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$itemId"
        val req = Request.Builder()
            .url(detailUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://www.iesdouyin.com/")
            .get()
            .build()

        val responseText = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ParseException("抖音 API 返回 HTTP ${resp.code}")
            resp.body?.string() ?: throw ParseException("抖音 API 响应为空")
        }

        val json = JSONObject(responseText)
        val itemInfo = json.optJSONArray("item_list")?.optJSONObject(0)
            ?: throw ParseException("抖音 API 返回数据格式异常")

        // ── 3. 提取视频信息 ──
        val desc = itemInfo.optString("desc", "")
        val author = itemInfo.optJSONObject("author")?.optString("nickname", "") ?: ""

        val videoObj = itemInfo.optJSONObject("video")
            ?: throw ParseException("抖音 API 返回中无视频信息")

        val playAddr = videoObj.optJSONObject("play_addr")
            ?: throw ParseException("抖音 API 返回中无 play_addr")

        val uri = videoObj.optString("uri", "")
        val urlList = playAddr.optJSONArray("url_list")
        val playUrl = urlList?.optString(0, "") ?: ""

        if (playUrl.isEmpty() && uri.isEmpty()) {
            throw ParseException("抖音 API 返回中无视频地址")
        }

        // ── 4. 构造无水印地址 ──
        val noWatermarkUrl = when {
            // 方式1: 用 uri 构造 aweme.snssdk.com 无水印地址
            uri.isNotEmpty() -> "https://aweme.snssdk.com/aweme/v1/play/?video_id=$uri"
            // 方式2: 将 playwm 替换为 play
            playUrl.contains("playwm") -> playUrl.replace("playwm", "play")
            // 方式3: 直接使用原地址
            else -> playUrl
        }

        // 封面图
        val coverUrl = videoObj.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0, "") ?: ""

        return ParseResult(
            videoUrl = noWatermarkUrl,
            title = desc,
            author = author,
            coverUrl = coverUrl,
            source = "抖音"
        )
    }

    /** 从抖音分享链接中提取 item_id */
    private fun extractDouyinItemId(url: String): String {
        // 短链接: 请求后重定向到 /share/video/{item_id}/ 格式
        if (url.contains("v.douyin.com") || url.contains("amemv.com")) {
            return resolveShortUrlAndGetItemId(url)
        }
        // 长链接: 直接从 URL 中提取
        // 格式: https://www.iesdouyin.com/share/video/{item_id}/
        val patterns = listOf(
            Regex("/share/video/(\\d+)"),
            Regex("/video/(\\d+)"),
            Regex("modal_id=(\\d+)")
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /** 请求短链接,从重定向后的 URL 中提取 item_id */
    private fun resolveShortUrlAndGetItemId(shortUrl: String): String {
        // 不自动跟随重定向,手动检查 Location 头
        val noRedirectClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = shortUrl
        for (i in 0..5) { // 最多跟随5次重定向
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .get()
                .build()

            noRedirectClient.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                if (location != null) {
                    // 处理相对路径重定向
                    currentUrl = if (location.startsWith("http")) location
                    else {
                        val base = URI(currentUrl)
                        "${base.scheme}://${base.host}${location}"
                    }
                    // 检查是否已包含 item_id
                    val patterns = listOf(
                        Regex("/share/video/(\\d+)"),
                        Regex("/video/(\\d+)"),
                        Regex("modal_id=(\\d+)")
                    )
                    for (pattern in patterns) {
                        pattern.find(currentUrl)?.let { return it.groupValues[1] }
                    }
                } else {
                    // 无重定向,从响应体中尝试提取
                    val body = resp.body?.string() ?: ""
                    val patterns = listOf(
                        Regex("/share/video/(\\d+)"),
                        Regex("/video/(\\d+)"),
                        Regex("itemId[\"']?\\s*[:=]\\s*[\"']?(\\d+)")
                    )
                    for (pattern in patterns) {
                        pattern.find(body)?.let { return it.groupValues[1] }
                    }
                    return ""
                }
            }
        }
        return ""
    }

    // ════════════════════════════════════════
    //  通用解析 API(豆包/抖音等)
    // ════════════════════════════════════════

    /**
     * 通过第三方解析 API 获取视频直链。
     *
     * 请求: POST https://hd.188166.top:9443/api/parse
     * 负载: {"url":"分享链接","requestId":"唯一标识"}
     *
     * 适用于豆包、抖音等平台。先搭建框架,后续根据实际响应补充字段解析。
     *
     * @throws ParseException 解析失败
     */
    private suspend fun parseViaApi(url: String): ParseResult {
        val requestId = "parse-${UUID.randomUUID().toString().take(8)}"
        val payload = JSONObject().apply {
            put("url", url)
            put("requestId", requestId)
        }

        val req = Request.Builder()
            .url(PARSE_API_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ParseException("解析 API 返回 HTTP ${resp.code}")
            }
            val text = resp.body?.string() ?: throw ParseException("解析 API 响应为空")
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw ParseException("解析 API 响应格式异常: $text")

            // ── 解析响应 ──
            // 框架实现: 尝试多种常见字段名,后续根据实际响应调整
            val videoUrl = json.optString("url", "")
                .ifEmpty { json.optString("videoUrl", "") }
                .ifEmpty { json.optString("video_url", "") }
                .ifEmpty { json.optString("play_url", "") }
                .ifEmpty { json.optString("playUrl", "") }
                .ifEmpty {
                    // 尝试嵌套 data 对象
                    json.optJSONObject("data")?.let { data ->
                        data.optString("url", "")
                            .ifEmpty { data.optString("videoUrl", "") }
                            .ifEmpty { data.optString("video_url", "") }
                            .ifEmpty { data.optString("play_url", "") }
                    } ?: ""
                }

            if (videoUrl.isEmpty()) {
                throw ParseException("解析 API 未返回视频地址: ${json.optString("message", text.take(200))}")
            }

            val title = json.optString("title", "")
                .ifEmpty { json.optJSONObject("data")?.optString("title", "") ?: "" }
            val author = json.optString("author", "")
                .ifEmpty { json.optJSONObject("data")?.optString("author", "") ?: "" }
            val coverUrl = json.optString("cover", "")
                .ifEmpty { json.optString("coverUrl", "") }
                .ifEmpty { json.optJSONObject("data")?.optString("cover", "") ?: "" }

            // 判断来源平台
            val source = when {
                isDouyin(url) -> "抖音"
                isDoubao(url) -> "豆包"
                url.contains("bilibili.com") -> "哔哩哔哩"
                url.contains("kuaishou.com") -> "快手"
                url.contains("xiaohongshu.com") -> "小红书"
                else -> "未知平台"
            }

            return ParseResult(
                videoUrl = videoUrl,
                title = title,
                author = author,
                coverUrl = coverUrl,
                source = source
            )
        }
    }
}
