package go.examate.hyp.music

import java.security.MessageDigest

/**
 * 音乐模块共享小工具(HTML 实体解码 / MD5),消除各音源 API 内的重复实现。
 */
object MusicUtil {
    /** 常见 HTML 实体解码(各音源搜索结果清洗用)。 */
    fun cleanHtml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /** MD5 十六进制小写(各音源签名用);异常返回空串以兼容原各实现。 */
    fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }
}