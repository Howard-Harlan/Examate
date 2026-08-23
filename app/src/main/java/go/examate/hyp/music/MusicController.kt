package go.examate.hyp.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 解析后的可播放请求(在线源在播放时才解析,URL 可能带鉴权/时效) */
data class PlayRequest(val uri: Uri, val headers: Map<String, String> = emptyMap())

/** 解析结果:成功带 PlayRequest,失败带 error 原因(供 UI 显示) */
sealed class ResolveResult {
    data class Ok(val req: PlayRequest) : ResolveResult()
    data class Err(val reason: String) : ResolveResult()
}

/**
 * 播放模式 — 列表循环 / 单曲循环 / 随机播放 / 顺序播放(播完停止)。
 * 手动切歌(上一首/下一首按钮)始终顺序切换,不受模式影响。
 */
enum class PlayMode(val id: String, val label: String) {
    LIST_LOOP("list_loop", "列表循环"),
    SINGLE_LOOP("single_loop", "单曲循环"),
    SHUFFLE("shuffle", "随机播放"),
    ORDER("order", "顺序播放");

    companion object {
        fun byId(id: String): PlayMode = entries.firstOrNull { it.id == id } ?: LIST_LOOP
    }
}

/**
 * 统一播放队列项 — 本地与在线共用。
 *  - 本地:localUri 直接可播
 *  - 在线:resolver 在播放时解析出 URL + headers(如 B站 DASH 音频 + Referer)
 */
data class QueueItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val localUri: Uri? = null,
    val albumId: Long = -1L,        // 本地音乐专辑ID,用于加载 albumart 封面
    val historyKey: String = "",    // 在线项持久化重建用(如 "bili:BV..:cid");本地项留空
    val coverRef: String = "",      // 封面兜底引用(如网易云 albumId → 专辑详情 API 取封面)
    val qualityTag: String = "",    // 音质标注(搜索接口给出的最高可得档:24bit/SQ/HQ;空=不标)
    val resolver: (suspend () -> ResolveResult)? = null
) {
    fun artistDisplay(): String = artist.ifBlank { "未知" }
    fun durationText(): String {
        if (durationMs <= 0) return "--:--"
        val s = (durationMs / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** Track → QueueItem(本地),传递 albumId 用于封面加载 */
fun Track.toQueueItem(): QueueItem = QueueItem(
    id = "local_$id", title = title, artist = artist, durationMs = durationMs,
    localUri = uri, albumId = albumId, coverUrl = coverUrl
)

/**
 * 音乐播放控制器 — 原生 MediaPlayer 封装。统一队列(本地/在线),零第三方播放库。
 * 在线项播放时经 resolver 解析 URL(带 header),本地项直接播 uri。
 */
class MusicController(private val context: Context) {

    private var player: MediaPlayer? = null
    private var playlist: List<QueueItem> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resolveJob: Job? = null

    // ═══ B站进度账号同步:恢复进度 + 周期上报 ═══
    private var biliSyncJob: Job? = null
    /** 上一次真正开始播放的条目(用于切歌/释放时上报最终进度) */
    private var lastPlayed: QueueItem? = null

    // ═══ 音频独占(功能设置):开启后播放申请音频焦点,焦点被抢占自动暂停,归还恢复 ═══
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var focusRequest26: android.media.AudioFocusRequest? = null
    private var focusLostBySystem = false
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val p = player
                if (p != null && runCatching { p.isPlaying }.getOrDefault(false)) {
                    p.pause(); isPlaying = false; focusLostBySystem = true
                }
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusLostBySystem) {
                    focusLostBySystem = false
                    runCatching { player?.start() }
                    isPlaying = true
                }
            }
        }
    }

    /** 音频独占开启时申请焦点(播放准备完成后调用) */
    private fun requestFocusIfNeeded() {
        if (!MusicFeaturePrefs.isAudioExclusive(context)) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest26 = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    /** 释放焦点(停止播放/退出时) */
    private fun abandonFocus() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            focusRequest26?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focusRequest26 = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
    }

    var effects: AudioEffects? = null

    var currentIndex by mutableIntStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    /** 最近一次播放错误(在线解析失败/MediaPlayer 错误),供 UI 显示;清空用 clearError() */
    var errorMessage by mutableStateOf("")
        private set
    /** 播放倍速(1.0 正常,支持 0.75/1.25/1.5/2.0,持久化) */
    var speed by mutableStateOf(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1f)
    )
        private set
    /** 播放模式(持久化,默认列表循环) */
    var playMode by mutableStateOf(
        PlayMode.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, PlayMode.LIST_LOOP.id) ?: PlayMode.LIST_LOOP.id)
    )
        private set

    /** 收藏状态变化信号(收藏本身在 FavoritesStore,此计数供 UI/网页主题观察刷新) */
    var favoriteVersion by mutableIntStateOf(0)
        private set
    /** 睡眠定时剩余毫秒(0=未开启) */
    var sleepRemainingMs by mutableStateOf(0L)
        private set

    private var sleepJob: Job? = null

    val current: QueueItem? get() = playlist.getOrNull(currentIndex)

    /** 当前播放队列(播放列表页展示用) */
    val queueItems: List<QueueItem> get() = playlist

    /** 播放指定索引(供播放列表页点选) */
    fun playIndex(index: Int) { playAt(index) }

    fun clearError() { errorMessage = "" }

    fun setPlaylist(items: List<QueueItem>) { playlist = items }

    /** 恢复上次播放状态:设置队列与当前曲目(不自动播放,供 PlayerBar 显示) */
    fun restore(items: List<QueueItem>, index: Int = 0) {
        if (items.isEmpty()) return
        playlist = items
        currentIndex = index.coerceIn(0, items.size - 1)
    }

    /** 以给定队列从某项开始播放 */
    fun playQueue(items: List<QueueItem>, index: Int) {
        playlist = items
        playAt(index)
    }

    /** 播放指定索引(在线项先解析,失败时设置 errorMessage) */
    fun playAt(index: Int) {
        val item = playlist.getOrNull(index) ?: return
        currentIndex = index
        release()
        errorMessage = ""
        // 开始解析前建立前台服务:此时用户在前台点击播放,避免 Android 12+ 后台启动前台服务被拒;
        // 自动切歌时服务已在前台运行,重复调用无副作用
        MusicPlaybackService.start(context)
        resolveJob?.cancel()
        resolveJob = scope.launch {
            loading = true
            val result: ResolveResult = when {
                item.localUri != null -> ResolveResult.Ok(PlayRequest(item.localUri))
                item.resolver != null -> withContext(Dispatchers.IO) {
                    runCatching { item.resolver.invoke() }
                        .getOrElse { ResolveResult.Err(it.message ?: "解析异常") }
                }
                else -> ResolveResult.Err("无可播放源")
            }
            loading = false
            when (result) {
                is ResolveResult.Ok -> startPlayer(result.req, item)
                is ResolveResult.Err -> {
                    errorMessage = result.reason
                    isPlaying = false
                    // 解析失败:撤掉前台服务与占位通知
                    MusicPlaybackService.stop(context)
                }
            }
        }
    }

    private fun startPlayer(req: PlayRequest, item: QueueItem) {
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            if (req.headers.isEmpty()) setDataSource(context, req.uri)
            else setDataSource(context, req.uri, req.headers)
            setOnPreparedListener {
                requestFocusIfNeeded()
                it.start()
                this@MusicController.isPlaying = true
                lastPlayed = item
                // 单曲循环:同一文件循环,不重走解析(更快且省请求)
                runCatching { if (playMode == PlayMode.SINGLE_LOOP) it.isLooping = true }
                runCatching { effects?.bind(it.audioSessionId) }
                runCatching { PlayHistoryStore.add(context, item) }
                // B站:从账号历史恢复进度 + 周期上报(登录后才生效)
                startBiliSync(item, it)
            }
            setOnCompletionListener { next(manual = false) }
            setOnErrorListener { _, what, extra ->
                // what/extra 为 MediaPlayer 错误码,转可读文本
                this@MusicController.errorMessage = "播放错误(what=$what extra=$extra)"
                this@MusicController.isPlaying = false
                MusicPlaybackService.update(context)
                // 在线项播放失败:失效 URL 缓存(可能是缓存了过期 URL),下次重新解析
                val cur = current
                if (cur != null && cur.id.startsWith("lx_")) {
                    val rest = cur.id.removePrefix("lx_")
                    val sep = rest.indexOf('_')
                    if (sep > 0) {
                        MusicUrlCache.invalidateAll(context, rest.substring(0, sep), rest.substring(sep + 1))
                    }
                }
                true
            }
            prepareAsync()
        }
    }

    fun toggle() {
        val p = player
        if (p == null) {
            if (currentIndex >= 0) playAt(currentIndex) else if (playlist.isNotEmpty()) playAt(0)
            return
        }
        if (p.isPlaying) {
            p.pause(); isPlaying = false
            // 暂停即上报一次 B 站进度,保证进度及时同步
            reportBiliFinal()
        } else { p.start(); isPlaying = true }
        // 通知栏同步播放/暂停状态
        MusicPlaybackService.update(context)
    }

    fun next(manual: Boolean = true) {
        if (playlist.isEmpty()) return
        // 自动播完按模式推进;手动切歌始终顺序
        if (!manual) {
            when (playMode) {
                PlayMode.SINGLE_LOOP -> { playAt(currentIndex); return }
                PlayMode.SHUFFLE -> {
                    if (playlist.size > 1) {
                        var i: Int
                        do { i = kotlin.random.Random.nextInt(playlist.size) } while (i == currentIndex)
                        playAt(i)
                    } else playAt(0)
                    return
                }
                PlayMode.ORDER -> {
                    if (currentIndex >= playlist.size - 1) {
                        isPlaying = false
                        MusicPlaybackService.update(context)  // 播完停止,通知同步暂停态
                        return
                    }
                }
                PlayMode.LIST_LOOP -> {}
            }
        }
        playAt((currentIndex + 1) % playlist.size)
    }

    /** 切换播放模式(持久化);单曲循环时对当前播放器即时生效 */
    fun changePlayMode(mode: PlayMode) {
        playMode = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.id).apply()
        runCatching { player?.isLooping = (mode == PlayMode.SINGLE_LOOP) }
    }

    fun prev() {
        if (playlist.isEmpty()) return
        playAt(if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1)
    }

    fun seekTo(ms: Int) { player?.seekTo(ms); positionMs = ms }

    /** 切换当前曲目收藏状态(网页主题桥接动作用);返回切换后是否已收藏 */
    fun toggleFavorite(): Boolean {
        val t = current ?: return false
        val now = FavoritesStore.toggle(context, t)
        favoriteVersion++   // 触发观察方(播放页/网页主题)同步状态
        return now
    }

    /** 当前曲目是否已收藏(网页主题事件推送用) */
    fun isCurrentFavorite(): Boolean {
        val t = current ?: return false
        return FavoritesStore.isFavorite(context, t.id)
    }

    /** 设置播放倍速(API 23+,低版本忽略) */
    fun changeSpeed(rate: Float) {
        speed = rate
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, rate).apply()
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val p = player ?: return
            runCatching { p.playbackParams = p.playbackParams.setSpeed(rate) }
        }
    }

    /** 播放错误提示(UI 调用,如音质切换失败) */
    fun setError(msg: String) { errorMessage = msg }

    /** 直接播放一个 URL(音质切换等场景,用新 URL 重播当前曲目) */
    fun playUrl(url: String) {
        val item = current ?: return
        release()
        errorMessage = ""
        startPlayer(PlayRequest(android.net.Uri.parse(url)), item)
    }

    /** 定时停止播放(分钟);minutes<=0 视为取消 */
    fun scheduleSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { sleepRemainingMs = 0; return }
        sleepRemainingMs = minutes * 60_000L
        sleepJob = scope.launch {
            while (sleepRemainingMs > 0) {
                kotlinx.coroutines.delay(1000)
                sleepRemainingMs -= 1000
                if (sleepRemainingMs <= 0) {
                    sleepRemainingMs = 0
                    player?.pause()
                    isPlaying = false
                    MusicPlaybackService.update(context)  // 定时结束,通知同步暂停态
                }
            }
        }
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepRemainingMs = 0
    }

    /** 剩余睡眠时间可读文本,如 "12:30" */
    fun sleepText(): String {
        val s = (sleepRemainingMs / 1000).toInt()
        if (s <= 0) return ""
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun refreshPosition() {
        positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    }

    // ════════════════════════════════════════
    //  B站进度账号同步(恢复进度 + 周期上报)
    // ════════════════════════════════════════

    /** 从条目中解析 bvid(hint:historyKey="bili:bvid:cid" 或 id="lx_bili_bvid") */
    private fun bvidOf(item: QueueItem): String = when {
        item.historyKey.startsWith("bili:") -> item.historyKey.split(":")[1]
        item.id.startsWith("lx_bili_") -> item.id.removePrefix("lx_bili_")
        else -> ""
    }

    /**
     * 播放就绪后:登录状态下
     * 1) 查账号历史进度,若在中段则恢复播放位置(账号同步 → 本地)
     * 2) 每 20 秒把当前进度上报回账号(本地 → 账号同步)
     */
    private fun startBiliSync(item: QueueItem, mp: MediaPlayer) {
        val bvid = bvidOf(item)
        if (bvid.isBlank()) return
        val appContext = context
        if (!go.examate.hyp.music.bili.BiliAuth.isLoggedIn(appContext)) return
        biliSyncJob?.cancel()
        biliSyncJob = scope.launch(Dispatchers.IO) {
            val client = go.examate.hyp.music.bili.BiliClient(appContext)
            val durSec = (item.durationMs / 1000).toInt()
            // 1) 恢复账号进度(历史记录在 5s ~ 时长-5s 之间才恢复,避免片头片尾误恢复)
            val (histCid, progressSec) = client.historyProgress(bvid)
            if (progressSec >= 5 && (durSec <= 0 || progressSec < durSec - 5)) {
                withContext(Dispatchers.Main) {
                    if (player === mp) {
                        runCatching { mp.seekTo(progressSec * 1000) }
                        positionMs = progressSec * 1000
                    }
                }
            }
            // 2) 周期上报(每 20 秒)
            val (aid, cid) = client.viewInfo(bvid)
            val reportCid = if (histCid > 0) histCid else cid
            while (isActive) {
                kotlinx.coroutines.delay(20_000)
                val p = player
                if (p === mp && isPlaying) {
                    val sec = runCatching { mp.currentPosition / 1000 }.getOrDefault(0)
                    if (sec > 0) {
                        runCatching { client.reportProgress(bvid, reportCid, aid, sec) }
                    }
                }
            }
        }
    }

    /** 切歌/释放前:把上一首的最终进度上报到 B 站账号 */
    private fun reportBiliFinal() {
        val item = lastPlayed ?: return
        val bvid = bvidOf(item)
        if (bvid.isBlank()) return
        val sec = runCatching { player?.currentPosition?.div(1000) ?: 0 }.getOrDefault(0)
        if (sec <= 0) return
        val appContext = context
        if (!go.examate.hyp.music.bili.BiliAuth.isLoggedIn(appContext)) return
        scope.launch(Dispatchers.IO) {
            runCatching { go.examate.hyp.music.bili.BiliClient(appContext).reportProgressAuto(bvid, sec) }
        }
    }

    companion object {
        private const val PREFS = "music_controller"
        private const val KEY_MODE = "play_mode"
        private const val KEY_SPEED = "speed"
    }

    fun release() {
        resolveJob?.cancel()
        biliSyncJob?.cancel()
        // 释放前:把上一首 B 站进度上报回账号(切歌/停止/退出都走这里)
        reportBiliFinal()
        abandonFocus()
        runCatching { player?.release() }
        player = null
        isPlaying = false
        lastPlayed = null
    }
}

/**
 * 全局音乐控制器持有者 — 让 agent 工具层 / 通知栏服务能拿到当前活跃的 MusicController 实例。
 * 由 ExamateApp 在懒加载创建时赋值(应用级单例,退出音乐页不置空,整个应用退出才结束)。
 * 听书页 AudioPlayerScreen 用独立局部实例,不写入此处。
 */
object MusicControllerHolder {
    @Volatile
    var controller: MusicController? = null

    fun isActive(): Boolean = controller != null
}
