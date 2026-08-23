package go.examate.hyp.music

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 音乐数据通用缓存 — cacheDir/music_data/ 文件缓存:
 *  - key → md5 文件名,内容 = 时间戳行 + JSON 文本
 *  - get 带 TTL 判定,过期返回 null(文件保留,刷新后覆盖)
 *  - 容量控制:文件数超上限时清理最旧文件
 *
 * 接入点(避免每次重复请求):歌单详情 / 榜单 / 推荐歌曲 / 搜索结果 / 在线歌词。
 * 播放 URL 缓存沿用 MusicUrlCache(带时效语义不同)。
 */
object MusicDataCache {
    private const val TAG = "MusicDataCache"
    private const val DIR = "music_data"
    private const val MAX_FILES = 400

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileOf(context: Context, key: String): File =
        File(dir(context), MusicUtil.md5(key) + ".cache")

    /** 读取缓存;未命中或过期返回 null */
    fun get(context: Context, key: String, ttlMs: Long): String? {
        val f = fileOf(context, key)
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return null
            val ts = text.substring(0, nl).toLongOrNull() ?: return null
            if (System.currentTimeMillis() - ts > ttlMs) return null
            text.substring(nl + 1)
        }.getOrNull()
    }

    /** 写入缓存(空值不写) */
    fun put(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        runCatching {
            trimIfNeeded(context)
            fileOf(context, key).writeText("${System.currentTimeMillis()}\n$value")
        }.onFailure { Log.w(TAG, "put fail: $key", it) }
    }

    /** 缓存总大小(字节) */
    fun sizeBytes(context: Context): Long =
        runCatching { dir(context).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /** 清空全部缓存 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /** 文件数超上限时按最后修改时间删最旧的 */
    private fun trimIfNeeded(context: Context) {
        val files = dir(context).listFiles() ?: return
        if (files.size < MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES + 20)
            .forEach { runCatching { it.delete() } }
    }


}
