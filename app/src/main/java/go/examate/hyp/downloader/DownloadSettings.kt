package go.examate.hyp.downloader

import android.content.Context
import android.content.SharedPreferences

/**
 * 下载器参数配置(aria2 风格)
 *
 * 独立 SharedPreferences 文件,不并入 PrefsManager:
 * 下载器是可独立复用的底层模块,把它的参数塞进全局 PrefsManager 会污染核心配置类。
 * 但仍遵循项目约定:key 常量集中管理,配置项以带 getter/setter 的属性暴露。
 */
object DownloadSettings {

    private const val PREFS_NAME = "DownloaderPrefs"

    private const val KEY_SEGMENT_COUNT = "segment_count"
    private const val KEY_MAX_CONCURRENT = "max_concurrent"
    private const val KEY_SPEED_LIMIT_KB = "speed_limit_kb"
    private const val KEY_MAX_RETRIES = "max_retries"
    private const val KEY_USER_AGENT = "user_agent"

    /** 默认 UA:部分 CDN 会拒绝非浏览器 UA */
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** 单任务分段数(即并发连接数)上限,过高易被服务器限流 */
    const val MAX_SEGMENT_COUNT = 16
    const val MAX_CONCURRENT_TASKS = 6

    /** 小于此大小的文件不分段(分段收益低于建连开销) */
    const val MIN_SEGMENT_BYTES = 1024 * 1024L

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** 单任务分段数(1 = 不分段,单线程) */
    var segmentCount: Int
        get() = prefs.getInt(KEY_SEGMENT_COUNT, 8).coerceIn(1, MAX_SEGMENT_COUNT)
        set(value) = prefs.edit().putInt(KEY_SEGMENT_COUNT, value.coerceIn(1, MAX_SEGMENT_COUNT)).apply()

    /** 同时下载的任务数 */
    var maxConcurrentTasks: Int
        get() = prefs.getInt(KEY_MAX_CONCURRENT, 3).coerceIn(1, MAX_CONCURRENT_TASKS)
        set(value) = prefs.edit().putInt(KEY_MAX_CONCURRENT, value.coerceIn(1, MAX_CONCURRENT_TASKS)).apply()

    /** 全局限速(KB/s),0 = 不限速 */
    var speedLimitKb: Int
        get() = prefs.getInt(KEY_SPEED_LIMIT_KB, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_SPEED_LIMIT_KB, value.coerceAtLeast(0)).apply()

    /** 单分段失败重试次数 */
    var maxRetries: Int
        get() = prefs.getInt(KEY_MAX_RETRIES, 3).coerceIn(0, 10)
        set(value) = prefs.edit().putInt(KEY_MAX_RETRIES, value.coerceIn(0, 10)).apply()

    /** 请求 User-Agent */
    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, DEFAULT_UA)?.ifBlank { DEFAULT_UA } ?: DEFAULT_UA
        set(value) = prefs.edit().putString(KEY_USER_AGENT, value.trim().ifBlank { DEFAULT_UA }).apply()

    /** 全局限速字节/秒,0 = 不限 */
    val speedLimitBps: Long get() = speedLimitKb * 1024L
}
