package go.examate.hyp.util

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存清理工具
 *
 * 覆盖三类缓存:
 * 1. 应用内浏览器(WebView)缓存:化学结构式渲染、网页浏览、网页导入课表等使用的 WebView
 * 2. 图片缓存(Coil):头像、淮师心声图片等的内存与磁盘缓存
 * 3. 应用缓存目录(cacheDir / externalCacheDir):临时导入文件、可再生的会话缓存等
 *
 * 不涉及账号凭据、应用设置(SharedPreferences)、下载完成的文件(在系统下载目录)
 * 与未完成的下载分段(在 filesDir),故清理是安全的。
 */
object CacheCleaner {

    /** 计算可清理缓存总大小(字节):应用缓存目录 + 外部缓存目录 */
    suspend fun calculateSize(context: Context): Long = withContext(Dispatchers.IO) {
        var total = dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        total
    }

    /**
     * 清除全部缓存。
     *
     * @param context 建议传入 Activity Context——部分设备构造 WebView 需要带主题的 Context。
     */
    suspend fun clear(context: Context) {
        // WebView 相关 API 必须在主线程调用
        withContext(Dispatchers.Main) {
            runCatching {
                WebView(context).apply {
                    clearCache(true)
                    clearHistory()
                    destroy()
                }
                WebStorage.getInstance().deleteAllData()
            }
        }
        withContext(Dispatchers.IO) {
            // Coil 图片缓存
            runCatching {
                val loader = context.imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            // 应用缓存目录内容(保留目录本身,仅清空内容)
            runCatching { clearDirContents(context.cacheDir) }
            runCatching { context.externalCacheDir?.let { clearDirContents(it) } }
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun clearDirContents(dir: File?) {
        dir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
