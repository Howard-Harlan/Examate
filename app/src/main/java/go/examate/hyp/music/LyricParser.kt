package go.examate.hyp.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import go.examate.hyp.music.lx.LxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LRC 歌词行 — 时间戳(毫秒) + 文本。
 */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * 歌词解析与获取。
 *
 * 解析:LRC 格式 [mm:ss.xx]text,支持多时间戳行、拖拽偏移。
 * 获取:
 *  - 在线:通过 LX 脚本的 lyric action 获取(若脚本支持)
 *  - 本地:通过 MediaMetadataRetriever 读取嵌入歌词
 *  - 兜底:返回空列表,UI 显示"暂无歌词"
 */
object LyricParser {
    private const val TAG = "LyricParser"

    /** 解析 LRC 文本为按时间排序的歌词行列表 */
    fun parse(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        // LRC 时间标签正则:[mm:ss.xx] 或 [mm:ss] 或 [mm:ss.xxx]
        val timeRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        for (rawLine in lrcText.lines()) {
            val matches = timeRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            // 提取歌词文本(最后一个时间标签之后的内容)
            val lastMatch = matches.last()
            val text = rawLine.substring(lastMatch.range.last + 1).trim()
            // 每个时间标签生成一行(同一行可能有多个时间标签)
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val ms = m.groupValues[3].let { frac ->
                    if (frac.isBlank()) 0L
                    else when (frac.length) {
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                }
                val timeMs = min * 60_000 + sec * 1000 + ms
                lines.add(LyricLine(timeMs, text.ifBlank { "♪" }))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 获取歌词 — 在线歌曲通过 LX 脚本,本地歌曲读嵌入歌词。
     * @param item 当前播放的队列项
     * @param context Context(LxManager 需要)
     * @return 歌词行列表;空列表表示无歌词
     */
    suspend fun fetch(item: QueueItem, context: Context): List<LyricLine> = withContext(Dispatchers.IO) {
        // 本地音乐:读取嵌入歌词
        if (item.localUri != null) {
            return@withContext fetchLocalLyric(item.localUri.toString())
        }
        // B站无歌词
        if (item.historyKey.startsWith("bili:")) return@withContext emptyList()
        // 在线音乐:通过 LX 脚本获取歌词
        return@withContext fetchOnlineLyric(item, context)
    }

    /** 读取本地文件的嵌入歌词(MediaMetadataRetriever) */
    private fun fetchLocalLyric(filePath: String): List<LyricLine> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            // METADATA_KEY_LYRICS 在 API 23+ 可用,用反射兼容
            val embedded = runCatching {
                MediaMetadataRetriever::class.java.getField("METADATA_KEY_LYRICS")
                    .getInt(null)
            }.getOrNull()?.let { retriever.extractMetadata(it) }
            if (!embedded.isNullOrBlank()) parse(embedded) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchLocalLyric fail", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 通过 LX 脚本的 lyric action 获取歌词 */
    private fun fetchOnlineLyric(item: QueueItem, context: Context): List<LyricLine> {
        return try {
            // item.id 格式: "lx_${platform}_${songmid}"(如 "lx_kg_b3a52a7a958bf0aed0ebfba2e9a818b7")
            // 需要去掉 "lx_" 前缀,再按 "_" split 取 platform 和 songmid
            val rawId = if (item.id.startsWith("lx_")) item.id.substring(3) else item.id
            val parts = rawId.split("_", limit = 2)
            if (parts.size < 2) return emptyList()
            val platform = parts[0]
            val songmid = parts[1]
            if (songmid.isBlank()) return emptyList()
            val lrcText = LxManager(context).resolveLyric(platform, songmid)
            parse(lrcText)
        } catch (e: Exception) {
            Log.w(TAG, "fetchOnlineLyric fail", e)
            emptyList()
        }
    }

    /** 根据当前播放位置查找当前歌词行索引 */
    fun findCurrentIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        // 二分查找:找到最后一个 timeMs <= positionMs 的行
        var lo = 0
        var hi = lyrics.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyrics[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
