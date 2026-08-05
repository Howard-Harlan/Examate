package go.examate.hyp.util

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import go.examate.hyp.config.PrefsManager
import java.io.File

/**
 * 聊天正文自定义字体:用户导入 ttf/otf → 拷到私有目录 → 记录路径 → 应用到 Markdown 正文。
 * 数学(KaTeX 图片)、代码(等宽)不受影响。加载结果按路径缓存,避免每次重建 FontFamily。
 */
object ReadingFont {

    @Volatile private var cachedPath: String? = null
    @Volatile private var cachedFamily: FontFamily? = null

    /** 当前正文字体;未设置或加载失败返回 null(用系统默认) */
    fun family(context: Context): FontFamily? {
        val path = PrefsManager.getInstance(context).chatFontPath
        if (path.isEmpty()) { cachedPath = ""; cachedFamily = null; return null }
        if (path == cachedPath) return cachedFamily
        val fam = try {
            val f = File(path)
            // 防御:仅当文件存在且能被解析为字体时才加载,否则渲染期会抛 IllegalStateException 闪退
            if (f.exists() && android.graphics.Typeface.Builder(f).build() != null) FontFamily(Font(f)) else null
        } catch (_: Throwable) { null }
        cachedPath = path
        cachedFamily = fam
        return fam
    }

    /** 导入字体文件到私有目录并记录路径;成功返回 true */
    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val dir = File(context.filesDir, "reading_font").apply { mkdirs() }
            // 用带时间戳的新文件名:避免覆盖仍被旧 Typeface 内存映射的文件(可能花字/SIGBUS)
            val dst = File(dir, "chat_font_${System.currentTimeMillis()}.ttf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: return false
            // 校验确为可用字体(picker 不限类型,选到图片/PDF 时在此拦截),否则删除并失败
            if (android.graphics.Typeface.Builder(dst).build() == null) { dst.delete(); return false }
            val prefs = PrefsManager.getInstance(context)
            val old = prefs.chatFontPath
            prefs.chatFontPath = dst.absolutePath
            cachedPath = null   // 失效缓存,下次重新加载
            if (old.isNotEmpty() && old != dst.absolutePath) runCatching { File(old).delete() }
            true
        } catch (_: Throwable) { false }
    }

    /** 恢复系统默认字体 */
    fun clear(context: Context) {
        PrefsManager.getInstance(context).chatFontPath = ""
        cachedPath = null
        cachedFamily = null
    }
}
