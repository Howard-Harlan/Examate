package go.examate.hyp.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import go.examate.hyp.music.lx.LxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * LRC 歌词行 — 时间戳(毫秒) + 文本。
 * @param words 逐字时间轴(逐字歌词用);空 = 未生成,可由 [LyricParser.withSyntheticWords] 补齐
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/** 逐字歌词的一个字/词:绝对起始时间 + 时长 */
data class LyricWord(val startMs: Long, val durMs: Long, val text: String)

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

    /** 解析 LRC 文本为按时间排序的歌词行列表;自动识别 QRC 逐字 / TTML 格式 */
    fun parse(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) return emptyList()
        // QRC 逐字格式检测:行头 [起始,时长] 后跟 字(起始,时长)
        if (lrcText.contains(Regex("""\[\d+,\d+\][^\[]*\(\d+,\d+\)"""))) {
            return parseQrc(lrcText)
        }
        // TTML 格式检测
        if (lrcText.contains("<tt") || lrcText.contains("<p ") && lrcText.contains("<span")) {
            return parseTtml(lrcText)
        }
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
     * 歌词缓存键 — 与 [fetchOnlineLyric] 内部使用的 key 保持一致,
     * 供"搜索导入歌词"持久化后命中后续 fetch 的缓存。
     * - 在线 lx_* 曲目:`lyric_{platform}_{songmid}`
     * - 其他(本地/B站等):`lyric_{item.id}`(搜索导入的兜底 key)
     */
    fun cacheKeyFor(item: QueueItem): String {
        if (item.id.startsWith("lx_")) {
            val rawId = item.id.substring(3)
            val parts = rawId.split("_", limit = 2)
            if (parts.size == 2) return "lyric_${parts[0]}_${parts[1]}"
        }
        return "lyric_${item.id}"
    }

    /**
     * 获取歌词 — 在线歌曲通过 LX 脚本,本地歌曲读嵌入歌词。
     * @param item 当前播放的队列项
     * @param context Context(LxManager 需要)
     * @return 歌词行列表;空列表表示无歌词
     */
    suspend fun fetch(item: QueueItem, context: Context): List<LyricLine> = withContext(Dispatchers.IO) {
        // 0. 搜索导入的歌词缓存优先(用户主动搜索导入的歌词比自动抓取更可信)
        //    适用所有曲目类型(在线/本地/B站):fetchOnlineLyric 内部也有缓存,但本地/B站原本不走缓存
        val cacheKey = cacheKeyFor(item)
        MusicDataCache.get(context, cacheKey, 365L * 24 * 3600_000)?.let { cached ->
            if (cached.isNotBlank()) return@withContext parse(cached)
        }
        // 本地音乐:读取嵌入歌词(遍历 ID3 USLT / Vorbis Comment,不假设块顺序)
        if (item.localUri != null) {
            return@withContext fetchLocalLyric(item.localUri, context)
        }
        // B站无歌词(仅搜索导入缓存可命中)
        if (item.historyKey.startsWith("bili:")) return@withContext emptyList()
        // 在线音乐:通过 LX 脚本获取歌词
        return@withContext fetchOnlineLyric(item, context)
    }

    /**
     * 读取本地文件的嵌入歌词 — 优先遍历式标签解析:
     *  - content:// URI → 内容流经 LocalTagParser.readFromStream(遍历 ID3 USLT / Vorbis Comment / MP4 ©lyr)
     *  - file:// 或路径 → LocalTagParser.readFromPath
     * 其次同名 .lrc 旁路文件(用户常把歌词放音频同目录);最后回退 MediaMetadataRetriever
     */
    private fun fetchLocalLyric(uri: android.net.Uri, context: Context): List<LyricLine> {
        val tagLyric: String? = when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    LocalTagParser.readFromStream(input).lyricText
                }
            }.getOrNull()
            "file" -> runCatching {
                LocalTagParser.readFromPath(uri.path.orEmpty()).lyricText
            }.getOrNull()
            else -> runCatching {
                LocalTagParser.readFromPath(uri.toString()).lyricText
            }.getOrNull()
        }
        if (!tagLyric.isNullOrBlank()) return parse(tagLyric)

        // 同名 .lrc 旁路文件(音频同目录,如 "歌名.mp3" 旁的 "歌名.lrc")
        val sidecar = findSidecarLrc(uri, context)
        if (!sidecar.isNullOrBlank()) return parse(sidecar)

        // 兜底:MediaMetadataRetriever(METADATA_KEY_LYRICS 在 API 23+ 可用,用反射兼容)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
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

    /**
     * 同名 .lrc 旁路查找:
     *  - file:// → 音频文件父目录找 "{文件名}.lrc"
     *  - content://(SAF)→ DocumentFile 的父目录 findFile("{文件名}.lrc")
     * 编码:UTF-8 优先,乱码回退 GBK。
     */
    private fun findSidecarLrc(uri: android.net.Uri, context: Context): String? {
        return when (uri.scheme) {
            "file" -> {
                val f = java.io.File(uri.path ?: return null)
                val dir = f.parentFile ?: return null
                listOf("${f.nameWithoutExtension}.lrc", "${f.nameWithoutExtension}.LRC")
                    .map { java.io.File(dir, it) }
                    .firstOrNull { it.isFile }?.let { readLrcBytes(runCatching { it.readBytes() }.getOrNull()) }
            }
            "content" -> runCatching {
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri) ?: return null
                val base = doc.name?.substringBeforeLast('.') ?: return null
                val parent = doc.parentFile ?: return null
                val lrc = parent.findFile("$base.lrc") ?: parent.findFile("$base.LRC") ?: return null
                val bytes = context.contentResolver.openInputStream(lrc.uri)?.use { it.readBytes() }
                readLrcBytes(bytes)
            }.getOrNull()
            else -> null
        }
    }

    /** LRC 字节 → 文本:UTF-8 优先,乱码回退 GBK */
    private fun readLrcBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (!utf8.contains('\uFFFD')) utf8.trim().takeIf { it.isNotBlank() }
        else runCatching { String(bytes, charset("GBK")) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 在线歌词:官方 API 优先(tx/wy/kw),音源脚本兜底(参照落雪:歌词官方 API + 音源补充);结果长期缓存 */
    private fun fetchOnlineLyric(item: QueueItem, context: Context): List<LyricLine> {
        return try {
            // item.id 格式: "lx_${platform}_${songmid}"(如 "lx_kg_b3a52a7a958bf0aed0ebfba2e9a818b7")
            val rawId = if (item.id.startsWith("lx_")) item.id.substring(3) else item.id
            val parts = rawId.split("_", limit = 2)
            if (parts.size < 2) return emptyList()
            val platform = parts[0]
            val songmid = parts[1]
            if (songmid.isBlank()) return emptyList()
            // 0. 歌词缓存(长期):歌词内容稳定,同一首歌不重复请求
            val cacheKey = "lyric_${platform}_$songmid"
            MusicDataCache.get(context, cacheKey, 365L * 24 * 3600_000)?.let { cached ->
                if (cached.isNotBlank()) return parse(cached)
            }
            // 1. 酷狗官方歌词(lyrics.kugou.com,落雪同款:搜索→下载→KRC 解码;kg 不依赖音源脚本)
            if (platform == "kg") {
                val kgLrc = go.examate.hyp.music.lx.KugouLyricApi.getLyric(item.title, songmid, item.durationMs)
                if (!kgLrc.isNullOrBlank()) {
                    MusicDataCache.put(context, cacheKey, kgLrc)
                    return parse(kgLrc)
                }
            }
            // 2. 官方歌词 API(tx/wy/kw,不依赖音源脚本)
            val official = OfficialLyricApi.fetch(platform, songmid)
            if (!official.isNullOrBlank()) {
                MusicDataCache.put(context, cacheKey, official)
                return parse(official)
            }
            // 3. 音源脚本 lyric action 兜底(传完整歌曲信息,脚本依赖 name/singer 等字段)
            val song = go.examate.hyp.music.lx.LxSong(
                platform, songmid, item.title, item.artist, item.durationMs, item.coverUrl, item.coverRef
            )
            val lrcText = LxManager(context).resolveLyric(song)
            if (lrcText.isNotBlank()) MusicDataCache.put(context, cacheKey, lrcText)
            parse(lrcText)
        } catch (e: Exception) {
            Log.w(TAG, "fetchOnlineLyric fail", e)
            emptyList()
        }
    }

    /**
     * 解析 QQ QRC 逐字歌词格式:
     * [offset:N] 全局偏移;[行起始,行时长]字(字起始,字时长)字(字起始,字时长)...
     * 返回含真实逐字时间轴的 LyricLine(ktv 效果)。
     */
    fun parseQrc(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var offset = 0L
        val lineRegex = Regex("""\[(-?\d+),(\d+)\](.*)""")
        val wordRegex = Regex("""([^(]+)\((\d+),(\d+)\)""")
        for (rawLine in text.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("[offset:")) {
                offset = trimmed.substringAfter("[offset:").substringBefore("]").toLongOrNull() ?: 0L
                continue
            }
            val m = lineRegex.matchEntire(trimmed) ?: continue
            val lineStart = m.groupValues[1].toLong() + offset
            val content = m.groupValues[3]
            val words = mutableListOf<LyricWord>()
            val wordText = StringBuilder()
            for (wm in wordRegex.findAll(content)) {
                val wText = wm.groupValues[1]
                val wStart = wm.groupValues[2].toLong() + offset
                val wDur = wm.groupValues[3].toLong()
                words.add(LyricWord(wStart, wDur, wText))
                wordText.append(wText)
            }
            if (words.isNotEmpty()) {
                lines.add(LyricLine(lineStart, wordText.toString(), words))
            } else if (content.isNotBlank()) {
                lines.add(LyricLine(lineStart, content.trim()))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 解析 TTML(W3C 字幕)逐字歌词:
     * <p begin="..." end="..."><span begin="..." end="...">字</span>...</p>
     * 时间格式支持 "7.47s"/"7470ms"/"00:00.07"。
     */
    fun parseTtml(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(text.reader())
            var event = parser.eventType
            var lineStart = 0L
            val lineText = StringBuilder()
            val words = mutableListOf<LyricWord>()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "p" -> {
                            lineStart = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                            lineText.clear()
                            words.clear()
                        }
                        "span" -> {
                            val begin = parseTtmlTime(parser.getAttributeValue(null, "begin"))
                            val end = parser.getAttributeValue(null, "end")?.let(::parseTtmlTime)
                                ?: (begin + 400L)
                            val spanText = parser.nextText()
                            if (spanText.isNotBlank()) {
                                words.add(LyricWord(begin, (end - begin).coerceAtLeast(40L), spanText))
                                lineText.append(spanText)
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "p") {
                    if (words.isNotEmpty()) {
                        lines.add(LyricLine(lineStart, lineText.toString(), words.toList()))
                    } else if (lineText.isNotEmpty()) {
                        lines.add(LyricLine(lineStart, lineText.toString()))
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseTtml fail", e)
        }
        return lines.sortedBy { it.timeMs }
    }

    /** TTML 时间 → 毫秒:"7.47s"/"7470ms"/"00:00.07" */
    private fun parseTtmlTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return when {
            s.endsWith("ms") -> s.dropLast(2).toDoubleOrNull()?.toLong() ?: 0L
            s.endsWith("s") -> (s.dropLast(1).toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
            s.contains(":") -> {
                val parts = s.split(":")
                if (parts.size == 2) {
                    val min = parts[0].toDoubleOrNull() ?: 0.0
                    val sec = parts[1].toDoubleOrNull() ?: 0.0
                    ((min * 60 + sec) * 1000).toLong()
                } else 0L
            }
            else -> s.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        }
    }

    /**
     * 逐字歌词时间轴合成 — 行时长在本行与下一行之间按字重比例分配:
     *  - 字重:普通字符 1.0 / 空格 0.45 / 标点 0.35(唱得快,不拖拍)
     *  - 行时长钳制 0.6s~8s(无下一行时默认 4s)
     * 适用于任意 LRC 源(官方 API 只给行级时间戳,逐字点亮需合成时间轴)。
     */
    fun withSyntheticWords(lyrics: List<LyricLine>): List<LyricLine> {
        if (lyrics.isEmpty()) return lyrics
        return lyrics.mapIndexed { i, line ->
            if (line.words.isNotEmpty()) return@mapIndexed line
            val chars = line.text.toList()
            if (chars.isEmpty()) return@mapIndexed line
            val next = lyrics.getOrNull(i + 1)?.timeMs
            val span = ((next ?: (line.timeMs + 4000)) - line.timeMs).coerceIn(600L, 8000L)
            val weights = chars.map(::charWeight)
            val totalW = weights.sum().coerceAtLeast(0.001)
            var acc = 0.0
            val words = chars.mapIndexed { j, c ->
                val start = line.timeMs + (acc / totalW * span).toLong()
                acc += weights[j]
                val end = line.timeMs + (acc / totalW * span).toLong()
                LyricWord(start, (end - start).coerceAtLeast(40L), c.toString())
            }
            line.copy(words = words)
        }
    }

    private fun charWeight(c: Char): Double = when {
        c.isWhitespace() -> 0.45
        c in "，。！？…,.!?;；:：、~～()（）\"'「」《》<>—-" -> 0.35
        else -> 1.0
    }

    /**
     * 歌词节奏浮动系数 — 快歌小幅、慢歌大幅:
     * 取相邻行间隔的中位数作为节奏估计,映射到 0.35(快,≈1.8s/行)~1.0(慢,≈5s/行),
     * 供行缩放/逐字放大等浮动幅度按节奏缩放,避免快歌大浮动眼花缭乱。
     */
    fun paceBounce(lyrics: List<LyricLine>): Float {
        val gaps = lyrics.asSequence().zipWithNext()
            .map { (prev, next) -> next.timeMs - prev.timeMs }
            .filter { it in 300..15000 }.toList().sorted()
        if (gaps.isEmpty()) return 0.7f
        val median = gaps[gaps.size / 2].toFloat()
        return ((5000f - median) / (5000f - 1800f)).coerceIn(0.35f, 1f)
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
