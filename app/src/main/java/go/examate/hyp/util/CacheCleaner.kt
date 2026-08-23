package go.examate.hyp.util

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存清理工具 — 分门别类管理。
 *
 * 缓存分类(与「缓存管理」页面一一对应):
 * 1. 音乐缓存  filesDir/lx/(音源脚本) + SharedPreferences songlist_cache(推荐歌单缓存)
 * 2. 阅读缓存  filesDir/legado/(书源 + 章节正文缓存)
 * 3. 下载缓存  filesDir/downloads/(下载分段 .part,未完成部分)
 * 4. 图片缓存  Coil memoryCache + diskCache
 * 5. 网页缓存  WebView cache/history + WebStorage
 * 6. 其他缓存  cacheDir + externalCacheDir(临时导入文件、会话缓存等)
 *
 * 不涉及账号凭据、应用设置、下载完成文件(在系统下载目录)。
 */
object CacheCleaner {

    /** 缓存分类枚举(UI 顺序即显示顺序) */
    enum class Category(val key: String, val label: String, val desc: String) {
        MUSIC("music", "音乐缓存", "音源脚本 · 推荐歌单缓存"),
        READING("reading", "阅读缓存", "书源 · 章节正文缓存"),
        DOWNLOAD("download", "下载缓存", "未完成下载的分段文件"),
        IMAGE("image", "图片缓存", "封面 / 头像等图片磁盘缓存"),
        WEB("web", "网页缓存", "WebView 浏览与表单数据"),
        OTHER("other", "其他缓存", "临时导入文件 · 会话缓存")
    }

    /** 各分类目录(文件型;IMAGE/WEB 由专用 API 清理,不含目录) */
    private fun catDirs(context: Context): Map<Category, List<File>> = mapOf(
        Category.MUSIC to listOf(File(context.filesDir, "lx")),
        Category.READING to listOf(File(context.filesDir, "legado")),
        Category.DOWNLOAD to listOf(File(context.filesDir, "downloads")),
        Category.OTHER to listOf(context.cacheDir).let { d -> context.externalCacheDir?.let { d + it } ?: d }
    )

    /** 计算指定分类缓存大小(字节);null 表示无目录型缓存(图片/网页走专用 API) */
    suspend fun categorySize(context: Context, cat: Category): Long = withContext(Dispatchers.IO) {
        when (cat) {
            Category.IMAGE -> imageCacheSize(context)
            Category.WEB -> 0L // WebView 缓存大小不暴露,统一按 0 显示"按需清理"
            else -> catDirs(context)[cat]?.sumOf { dirSize(it) } ?: 0L
        }
    }

    /** 全部分类缓存总大小 */
    suspend fun calculateSize(context: Context): Long = withContext(Dispatchers.IO) {
        var total = dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        total + imageCacheSize(context)
    }

    /** 清理指定分类缓存 */
    suspend fun clearCategory(context: Context, cat: Category) {
        when (cat) {
            Category.IMAGE -> withContext(Dispatchers.IO) { clearImageCache(context) }
            Category.WEB -> withContext(Dispatchers.Main) {
                runCatching {
                    WebView(context).apply { clearCache(true); clearHistory(); destroy() }
                    WebStorage.getInstance().deleteAllData()
                }
            }
            else -> withContext(Dispatchers.IO) {
                catDirs(context)[cat]?.forEach { runCatching { clearDirContents(it) } }
            }
        }
        // 音乐缓存的 SharedPreferences(推荐歌单 1 小时缓存)一并清
        if (cat == Category.MUSIC) {
            runCatching {
                context.getSharedPreferences("songlist_cache", android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }
        }
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
            clearImageCache(context)
            // 应用缓存目录内容(保留目录本身,仅清空内容)
            runCatching { clearDirContents(context.cacheDir) }
            runCatching { context.externalCacheDir?.let { clearDirContents(it) } }
            // 分类目录(音乐/阅读/下载)
            catDirs(context).values.flatten().forEach { runCatching { clearDirContents(it) } }
            // 音乐歌单缓存
            runCatching {
                context.getSharedPreferences("songlist_cache", android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }
        }
    }

    private suspend fun imageCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        runCatching {
            val loader = context.imageLoader
            (loader.diskCache?.size ?: 0L)
        }.getOrDefault(0L)
    }

    private fun clearImageCache(context: Context) {
        runCatching {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
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
