package go.examate.hyp.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 聊天图片持久化存储 — 解决 content:// URI 授权过期导致"下次进入图片没了"。
 *
 * - 发送前把选图/拍照图片复制到 {filesDir}/chat_media/,消息里存绝对路径
 * - 定期清理:删除未被任何历史会话引用、且超过 7 天的文件,防止无限膨胀
 */
object ChatMediaStore {

    /** 未被引用文件的保留期 */
    private const val RETAIN_MS = 7L * 24 * 3600 * 1000

    private fun dir(context: Context): File =
        File(context.filesDir, "chat_media").apply { if (!exists()) mkdirs() }

    /** 判断是否为已持久化的本地路径 */
    fun isPersistedPath(uriString: String): Boolean = uriString.startsWith("/")

    /**
     * 把图片 URI(content:// / file://)复制进持久目录。
     * @return 持久化后的绝对路径;复制失败返回 null(调用方回退用原 URI)
     */
    fun importUri(context: Context, uriString: String): String? = runCatching {
        val uri = if (isPersistedPath(uriString)) Uri.fromFile(File(uriString)) else Uri.parse(uriString)
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val ext = when (context.contentResolver.getType(uri)?.substringAfterLast("/")) {
            "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg"
        }
        val target = File(dir(context), "img_${System.currentTimeMillis()}_${(0..999).random()}.$ext")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
        target.absolutePath
    }.getOrNull()

    /** 打开图片字节:本地路径走 File,其余走 contentResolver;失败返回 null */
    fun readBytes(context: Context, uriString: String): ByteArray? = runCatching {
        if (isPersistedPath(uriString)) {
            val f = File(uriString)
            if (f.exists()) f.readBytes() else null
        } else {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
        }
    }.getOrNull()

    /** 渲染用模型对象:本地路径 → File,否则原样返回 URI 字符串 */
    fun renderModel(uriString: String): Any =
        if (isPersistedPath(uriString)) File(uriString) else Uri.parse(uriString)

    /**
     * 清理未被引用的旧文件。
     * @param referenced 所有历史会话消息中仍引用的路径集合
     */
    fun cleanup(context: Context, referenced: Set<String>) {
        runCatching {
            val now = System.currentTimeMillis()
            dir(context).listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val path = f.absolutePath
                if (path !in referenced && now - f.lastModified() > RETAIN_MS) {
                    f.delete()
                }
            }
        }
    }
}
