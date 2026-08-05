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

    val current: QueueItem? get() = playlist.getOrNull(currentIndex)

    fun clearError() { errorMessage = "" }

    fun setPlaylist(items: List<QueueItem>) { playlist = items }

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
                it.start()
                this@MusicController.isPlaying = true
                runCatching { effects?.bind(it.audioSessionId) }
                runCatching { PlayHistoryStore.add(context, item) }
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, what, extra ->
                // what/extra 为 MediaPlayer 错误码,转可读文本
                this@MusicController.errorMessage = "播放错误(what=$what extra=$extra)"
                this@MusicController.isPlaying = false
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
        if (p.isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
    }

    fun next() {
        if (playlist.isEmpty()) return
        playAt((currentIndex + 1) % playlist.size)
    }

    fun prev() {
        if (playlist.isEmpty()) return
        playAt(if (currentIndex <= 0) playlist.size - 1 else currentIndex - 1)
    }

    fun seekTo(ms: Int) { player?.seekTo(ms); positionMs = ms }

    fun refreshPosition() {
        positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    }

    fun release() {
        resolveJob?.cancel()
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }
}

/**
 * 全局音乐控制器持有者 — 让 agent 工具层能拿到当前活跃的 MusicController 实例。
 * 由 MusicActivity 在 controller 创建后赋值、onDestroy 中置空(在 release 之前)。
 * Vololatile 保证多线程可见性;听书页 AudioPlayerScreen 用独立局部实例,不写入此处。
 */
object MusicControllerHolder {
    @Volatile
    var controller: MusicController? = null

    fun isActive(): Boolean = controller != null
}
