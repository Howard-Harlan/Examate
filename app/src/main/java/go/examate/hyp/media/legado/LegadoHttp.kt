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

    /**
     * @param contentLength 服务端声明长度(-1=未声明/chunked)
     * @param lengthVerified 长度硬校验通过(声明长度 == 实收字节) — 导入侧据此判断载荷完整性
     */
    data class StrResponse(val finalUrl: String, val body: String, val contentLength: Long = -1L, val lengthVerified: Boolean = false)

    /**
     * 字节级响应(Range 分块下载用):
     * @param code HTTP 状态码(206=分块成功 / 200=服务端忽略 Range 返回整包)
     * @param contentRange Content-Range 头(如 "bytes 0-65535/114852",可解析总长)
     */
    data class ByteResponse(val code: Int, val bytes: ByteArray, val contentLength: Long, val contentRange: String?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 字节级请求 — 支持 Range 头,返回原始字节(不做字符集解码)。
     * 实收字节少于声明 Content-Length 即抛异常(传输截断);分块完整性由上层按区间长度再校验。
     */
    fun fetchBytes(req: AnalyzeUrl.UrlRequest): ByteResponse {
        val builder = Request.Builder().url(req.url)
        if (req.headers.keys.none { it.equals("User-Agent", true) || it.equals("UA", true) }) {
            builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        }
        req.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        builder.get()
        client.newCall(builder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val declaredLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            if (declaredLen > 0 && bytes.size < declaredLen) {
                throw java.io.IOException("下载不完整: 期望 $declaredLen 字节,实际 ${bytes.size} 字节")
            }
            return ByteResponse(resp.code, bytes, declaredLen, resp.header("Content-Range"))
        }
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
            val declaredLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            // 完整性校验:服务端声明了 Content-Length 但实收字节不足 → 传输被截断,
            // 抛异常由上层重试(音源脚本被截断导入后必然解析失败)
            if (declaredLen > 0 && bytes.size < declaredLen) {
                throw java.io.IOException("下载不完整: 期望 $declaredLen 字节,实际 ${bytes.size} 字节")
            }
            val charset = req.charset ?: sniffCharset(bytes, resp.header("Content-Type"))
            val text = try { String(bytes, charset(charset)) } catch (_: Exception) { String(bytes) }
            return StrResponse(finalUrl, text, declaredLen, declaredLen > 0 && bytes.size.toLong() == declaredLen)
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
