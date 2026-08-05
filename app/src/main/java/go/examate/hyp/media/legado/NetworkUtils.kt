package go.examate.hyp.media.legado

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 网络工具 — 相对 URL 补全等。
 */
object NetworkUtils {

    /** 把相对地址补全为绝对地址(基于 baseUrl)。已是 http(s)/协议相对 则原样处理 */
    fun absoluteUrl(baseUrl: String, relative: String?): String {
        val rel = relative?.trim().orEmpty()
        if (rel.isEmpty()) return baseUrl
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        if (rel.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            return "$scheme:$rel"
        }
        return try {
            java.net.URL(java.net.URL(baseUrl), rel).toString()
        } catch (_: Exception) {
            // 兜底:baseUrl 域名 + rel
            val root = baseUrl.substringBefore("://").let { s ->
                if (baseUrl.contains("://")) baseUrl.substring(0, baseUrl.indexOf("://") + 3) +
                        baseUrl.substringAfter("://").substringBefore("/") else baseUrl
            }
            if (rel.startsWith("/")) root + rel else "$root/$rel"
        }
    }
}

/**
 * 缓存管理 — @put/@get 跨请求变量(按 sourceUrl 落 SharedPreferences)。
 * 章节正文缓存在阶段3实装。
 */
object CacheManager {

    private const val PREFS_VARS = "legado_vars"

    private fun keyOf(sourceUrl: String, key: String) = "${sourceUrl.hashCode()}_$key"

    fun putVariable(context: Context, sourceUrl: String, key: String, value: String) {
        context.getSharedPreferences(PREFS_VARS, Context.MODE_PRIVATE)
            .edit().putString(keyOf(sourceUrl, key), value).apply()
    }

    fun getVariable(context: Context, sourceUrl: String, key: String): String? =
        context.getSharedPreferences(PREFS_VARS, Context.MODE_PRIVATE)
            .getString(keyOf(sourceUrl, key), null)

    // ── 章节正文缓存(filesDir/legado/cache/{md5(bookUrl)}/{index}.txt) ──
    private fun cacheDir(context: Context, bookUrl: String): File {
        val dir = File(File(File(context.filesDir, "legado"), "cache"), md5(bookUrl))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cacheContent(context: Context, bookUrl: String, index: Int, text: String) {
        try { File(cacheDir(context, bookUrl), "$index.txt").writeText(text) } catch (_: Exception) {}
    }

    fun readContent(context: Context, bookUrl: String, index: Int): String? {
        return try {
            val f = File(cacheDir(context, bookUrl), "$index.txt")
            if (f.exists()) f.readText() else null
        } catch (_: Exception) { null }
    }

    /** 清理某本的章节缓存 */
    fun clearBook(context: Context, bookUrl: String) {
        try { cacheDir(context, bookUrl).deleteRecursively() } catch (_: Exception) {}
    }

    private fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { s.hashCode().toString() }
}
