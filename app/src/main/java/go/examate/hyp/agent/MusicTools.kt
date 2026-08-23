package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.music.MusicControllerHolder
import go.examate.hyp.music.MusicStore
import go.examate.hyp.music.PlayHistoryStore
import go.examate.hyp.music.QueueItem
import go.examate.hyp.music.Track
import go.examate.hyp.music.lx.LxManager
import go.examate.hyp.music.lx.LxSong
import go.examate.hyp.music.lx.PlatformSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 音乐工具集 — 让 agent 能搜索在线音乐、查歌词、扫描本地曲库、查播放历史和音源状态。
 *
 * 在线搜索走 PlatformSearcher(直接调平台 API,不依赖 LX 音源);
 * 歌词/音源状态走 LxManager(需已启用 LX 音源);
 * 本地扫描/历史走 MusicStore + PlayHistoryStore(本地存储,离线)。
 */
object MusicTools {

    private const val TAG = "MusicTools"

    /** 平台代码 → 可读名 */
    private fun platformName(code: String): String = when (code) {
        "wy" -> "网易"
        "kg" -> "酷狗"
        "kw" -> "酷我"
        else -> code
    }

    /** 毫秒 → m:ss;非正数返回 --:-- */
    private fun durationText(ms: Long): String {
        if (ms <= 0) return "--:--"
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** 在线音乐搜索(直接调平台 API,不依赖 LX 音源) */
    val SearchMusic = object : ToolRegistry.Tool {
        override val name = "search_music"
        override val description = "搜索在线音乐(网易/酷狗/酷我)。返回歌曲列表,可用 songmid 调 get_lyrics 取歌词"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "keyword(关键词,必填), platform(平台 wy/kg/kw,可选,默认 wy)"
        override val params = listOf(
            ToolRegistry.ParamDef("keyword", "string", "搜索关键词", true),
            ToolRegistry.ParamDef("platform", "string", "平台: wy=网易 kg=酷狗 kw=酷我,默认 wy", false)
        )

        // 搜索直接调平台 API,无需 LX 音源,故始终可用
        override fun isAvailable(context: Context): Boolean = true

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val keyword = args.optString("keyword", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 keyword 参数") }
            val platform = args.optString("platform", "wy").trim().ifEmpty { "wy" }
            if (platform !in listOf("wy", "kg", "kw", "tx", "mg", "bili")) {
                return ToolRegistry.Result(false, "不支持的平台: $platform(可选: wy/kg/kw/tx/bili)")
            }
            return try {
                val songs: List<LxSong> = PlatformSearcher.search(context, platform, keyword)
                if (songs.isEmpty()) {
                    return ToolRegistry.Result(true, "未找到「$keyword」相关歌曲(${platformName(platform)})。")
                }
                val sb = StringBuilder("搜索结果(${platformName(platform)}): $keyword\n\n")
                songs.take(20).forEachIndexed { i, s ->
                    sb.append("${i + 1}. ${s.name} | ${s.artist.ifBlank { "未知艺术家" }} | ${platformName(s.platform)} | ${s.songmid} | ${durationText(s.durationMs)}\n")
                }
                ToolRegistry.Result(true, sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "search_music failed", e)
                ToolRegistry.Result(false, "搜索失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 获取歌词(通过 LX 脚本 lyric action 获取,需已启用 LX 音源) */
    val GetLyrics = object : ToolRegistry.Tool {
        override val name = "get_lyrics"
        override val description = "获取指定歌曲的 LRC 歌词(需已启用 LX 音源)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "platform(平台 wy/kg/kw,必填), songmid(歌曲 id,必填,来自 search_music 结果)"
        override val params = listOf(
            ToolRegistry.ParamDef("platform", "string", "平台: wy/kg/kw", true),
            ToolRegistry.ParamDef("songmid", "string", "歌曲 id(来自 search_music)", true)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val platform = args.optString("platform", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 platform 参数") }
            val songmid = args.optString("songmid", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 songmid 参数") }
            return try {
                val song = go.examate.hyp.music.lx.LxSong(platform, songmid, "", "", 0L)
                val lyric = LxManager(context).resolveLyric(song)
                if (lyric.isBlank()) {
                    ToolRegistry.Result(true, "未找到歌词")
                } else {
                    ToolRegistry.Result(true, lyric)
                }
            } catch (e: Exception) {
                Log.e(TAG, "get_lyrics failed", e)
                ToolRegistry.Result(false, "获取歌词失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 扫描本地音乐(MediaStore + 文件系统双重策略) */
    val ScanLocalMusic = object : ToolRegistry.Tool {
        override val name = "scan_local_music"
        override val description = "扫描本地音乐曲库(系统 MediaStore + 文件系统),返回标题/艺术家/时长/路径"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            return try {
                val tracks: List<Track> = MusicStore.scan(context, null)
                if (tracks.isEmpty()) {
                    return ToolRegistry.Result(true, "本地未扫描到音乐文件。")
                }
                val sb = StringBuilder("本地音乐(${tracks.size} 首):\n\n")
                tracks.take(50).forEachIndexed { i, t ->
                    sb.append("${i + 1}. ${t.title.ifBlank { "未知" }} | ${t.artist.ifBlank { "未知艺术家" }} | ${durationText(t.durationMs)} | ${t.uri}\n")
                }
                ToolRegistry.Result(true, sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "scan_local_music failed", e)
                ToolRegistry.Result(false, "扫描本地音乐失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 查询播放历史(本地存储,最近 50 条,最新在前) */
    val GetPlayHistory = object : ToolRegistry.Tool {
        override val name = "get_play_history"
        override val description = "查询最近播放历史(本地存储,最新在前)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            return try {
                val items: List<QueueItem> = PlayHistoryStore.load(context)
                if (items.isEmpty()) {
                    return ToolRegistry.Result(true, "暂无播放历史。")
                }
                val sb = StringBuilder("播放历史:\n\n")
                items.take(30).forEachIndexed { i, it ->
                    sb.append("${i + 1}. ${it.title.ifBlank { "未知" }} | ${it.artist.ifBlank { "未知艺术家" }} | ${durationText(it.durationMs)}\n")
                }
                ToolRegistry.Result(true, sb.toString().trimEnd())
            } catch (e: Exception) {
                Log.e(TAG, "get_play_history failed", e)
                ToolRegistry.Result(false, "查询播放历史失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 查询当前 LX 音源状态 */
    val GetMusicSourceStatus = object : ToolRegistry.Tool {
        override val name = "get_music_source_status"
        override val description = "查询当前 LX 音源状态(是否可用 + 音源名称)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            return try {
                val mgr = LxManager(context)
                val enabled = mgr.hasEnabledSource()
                val name = mgr.enabledSourceName()
                val text = if (enabled) {
                    "LX 音源已启用: $name"
                } else {
                    "未启用 LX 音源(在线播放和歌词解析将不可用)。请先在音乐页导入音源脚本。"
                }
                ToolRegistry.Result(true, text)
            } catch (e: Exception) {
                Log.e(TAG, "get_music_source_status failed", e)
                ToolRegistry.Result(false, "查询音源状态失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 播放音乐(在线搜索第一首,经 LX 音源解析后送入音乐应用播放) */
    val PlayMusic = object : ToolRegistry.Tool {
        override val name = "play_music"
        override val description = "播放指定歌曲(在线搜索第一首并播放,需先打开音乐应用并启用 LX 音源)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "keyword(歌名/关键词,必填), platform(平台 wy/kg/kw,可选,默认 wy)"
        override val params = listOf(
            ToolRegistry.ParamDef("keyword", "string", "搜索关键词(歌名/歌手)", true),
            ToolRegistry.ParamDef("platform", "string", "平台: wy=网易 kg=酷狗 kw=酷我,默认 wy", false)
        )

        // 未启动音乐应用时不可用,AI 会提示用户先打开音乐应用
        override fun isAvailable(context: Context): Boolean = MusicControllerHolder.isActive()

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val controller = MusicControllerHolder.controller
                ?: return ToolRegistry.Result(false, "音乐应用未启动，请先打开音乐应用")
            val keyword = args.optString("keyword", "").trim()
                .ifEmpty { return ToolRegistry.Result(false, "缺少 keyword 参数") }
            val platform = args.optString("platform", "wy").trim().ifEmpty { "wy" }
            if (platform !in listOf("wy", "kg", "kw", "tx", "mg", "bili")) {
                return ToolRegistry.Result(false, "不支持的平台: $platform(可选: wy/kg/kw/tx/bili)")
            }
            return try {
                val songs: List<LxSong> = PlatformSearcher.search(context, platform, keyword)
                if (songs.isEmpty()) {
                    return ToolRegistry.Result(false, "未找到「$keyword」相关歌曲(${platformName(platform)})")
                }
                val song = songs.first()
                val manager = LxManager(context)
                val item = QueueItem(
                    id = "lx_${song.platform}_${song.songmid}",
                    title = song.name,
                    artist = song.artist,
                    durationMs = song.durationMs,
                    coverUrl = song.picUrl,
                    resolver = { withContext(Dispatchers.IO) { manager.resolve(song) } }
                )
                controller.playQueue(listOf(item), 0)
                ToolRegistry.Result(true, "已开始播放: ${song.name} - ${song.artist.ifBlank { "未知艺术家" }}(${platformName(song.platform)})")
            } catch (e: Exception) {
                Log.e(TAG, "play_music failed", e)
                ToolRegistry.Result(false, "播放失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 播放/暂停切换 */
    val MusicToggle = object : ToolRegistry.Tool {
        override val name = "music_toggle"
        override val description = "切换音乐播放/暂停状态(需先打开音乐应用)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override fun isAvailable(context: Context): Boolean = MusicControllerHolder.isActive()

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val controller = MusicControllerHolder.controller
                ?: return ToolRegistry.Result(false, "音乐应用未启动，请先打开音乐应用")
            return try {
                controller.toggle()
                val state = if (controller.isPlaying) "播放中" else "已暂停"
                ToolRegistry.Result(true, "已切换播放状态: $state")
            } catch (e: Exception) {
                Log.e(TAG, "music_toggle failed", e)
                ToolRegistry.Result(false, "切换失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 下一首 */
    val MusicNext = object : ToolRegistry.Tool {
        override val name = "music_next"
        override val description = "切换到下一首歌曲(需先打开音乐应用)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override fun isAvailable(context: Context): Boolean = MusicControllerHolder.isActive()

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val controller = MusicControllerHolder.controller
                ?: return ToolRegistry.Result(false, "音乐应用未启动，请先打开音乐应用")
            return try {
                controller.next()
                ToolRegistry.Result(true, "已切换到下一首")
            } catch (e: Exception) {
                Log.e(TAG, "music_next failed", e)
                ToolRegistry.Result(false, "切换失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 上一首 */
    val MusicPrev = object : ToolRegistry.Tool {
        override val name = "music_prev"
        override val description = "切换到上一首歌曲(需先打开音乐应用)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override fun isAvailable(context: Context): Boolean = MusicControllerHolder.isActive()

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val controller = MusicControllerHolder.controller
                ?: return ToolRegistry.Result(false, "音乐应用未启动，请先打开音乐应用")
            return try {
                controller.prev()
                ToolRegistry.Result(true, "已切换到上一首")
            } catch (e: Exception) {
                Log.e(TAG, "music_prev failed", e)
                ToolRegistry.Result(false, "切换失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    /** 查询当前播放状态 */
    val GetNowPlaying = object : ToolRegistry.Tool {
        override val name = "get_now_playing"
        override val description = "查询当前正在播放的歌曲信息(标题/艺术家/进度/状态)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = ""

        override fun isAvailable(context: Context): Boolean = MusicControllerHolder.isActive()

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val controller = MusicControllerHolder.controller
                ?: return ToolRegistry.Result(false, "音乐应用未启动，请先打开音乐应用")
            return try {
                controller.refreshPosition()
                val current = controller.current
                if (current == null) {
                    ToolRegistry.Result(true, "当前未播放任何音乐")
                } else {
                    val state = if (controller.isPlaying) "播放中" else "已暂停"
                    val pos = durationText(controller.positionMs.toLong())
                    val dur = durationText(current.durationMs)
                    ToolRegistry.Result(true, "正在播放: ${current.title} - ${current.artistDisplay()} [$pos/$dur] $state")
                }
            } catch (e: Exception) {
                Log.e(TAG, "get_now_playing failed", e)
                ToolRegistry.Result(false, "查询失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(
        SearchMusic, GetLyrics, ScanLocalMusic, GetPlayHistory, GetMusicSourceStatus,
        PlayMusic, MusicToggle, MusicNext, MusicPrev, GetNowPlaying
    )
}
