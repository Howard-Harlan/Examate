package go.examate.hyp.media.legado

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit

/**
 * legado 书源抓取封装 — 独立于 HttpUtils(后者是 AI API 客户端)。
 *
 * 关注点:字符集嗅探(GBK 站点常见)、重定向终址(作后续相对路径 baseUrl)、header 透传。
 */
object LegadoHttp {

    data class StrResponse(val finalUrl: String, val body: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 发起请求并按字符集解码为字符串。失败抛异常由上层收敛 */
    fun fetch(req: AnalyzeUrl.UrlRequest): StrResponse {
        val builder = Request.Builder().url(req.url)
        // 默认 UA:ghproxy / jsdelivr 等代理会拦截无 UA 的请求返回 HTML 拦截页
        if (req.headers.keys.none { it.equals("User-Agent", true) || it.equals("UA", true) }) {
            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        }
        req.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (req.method == "POST") {
            val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
            builder.post((req.body ?: "").toRequestBody(mediaType))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val charset = req.charset ?: sniffCharset(bytes, resp.header("Content-Type"))
            val text = try { String(bytes, charset(charset)) } catch (_: Exception) { String(bytes) }
            return StrResponse(finalUrl, text)
        }
    }

    /** 字符集嗅探:优先 Content-Type,其次 HTML meta charset,默认 UTF-8 */
    private fun sniffCharset(bytes: ByteArray, contentType: String?): String {
        contentType?.let {
            val m = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)
            if (m != null) return normalize(m.groupValues[1])
        }
        // 从前 2KB 的 meta 里找
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val m = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
        if (m != null) return normalize(m.groupValues[1])
        return "UTF-8"
    }

    private fun normalize(cs: String): String {
        val c = cs.trim().uppercase()
        return when (c) {
            "GB2312", "GBK", "GB18030" -> "GBK"
            else -> c
        }
    }

    private fun charset(name: String): java.nio.charset.Charset =
        try { java.nio.charset.Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }
}
