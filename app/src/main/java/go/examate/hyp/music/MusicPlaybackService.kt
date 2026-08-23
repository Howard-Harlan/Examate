package go.examate.hyp.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 音乐后台播放前台服务:
 *  - 前台服务(mediaPlayback 类型),退到后台/锁屏仍持续播放
 *  - MediaSession:系统媒体键、蓝牙、锁屏控制接入
 *  - 通知栏:曲名/歌手 + 封面 + 上一首/播放暂停/下一首/关闭
 *
 * 生命周期:MusicController 播放开始时 startForeground;停止播放时 stopSelf。
 * 控制经 MusicControllerHolder 转发到 MusicController(与播放页共享同一实例)。
 */
class MusicPlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIF_ID = 1001

        private const val ACTION_START = "go.examate.hyp.music.START_PLAYBACK"
        private const val ACTION_UPDATE = "go.examate.hyp.music.UPDATE_PLAYBACK"
        private const val ACTION_STOP = "go.examate.hyp.music.STOP_PLAYBACK"
        private const val ACTION_TOGGLE = "go.examate.hyp.music.TOGGLE"
        private const val ACTION_NEXT = "go.examate.hyp.music.NEXT"
        private const val ACTION_PREV = "go.examate.hyp.music.PREV"

        /** 播放开始:启动前台服务(Android O+ 必须 startForegroundService) */
        fun start(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * 切歌/播放状态变化:刷新通知。
         * 统一走 startForegroundService:服务未启动则建立前台,已启动则 onStartCommand 再次触发更新,
         * 避免后台直接 startService 在 O+ 崩溃。
         */
        fun update(context: Context) {
            runCatching {
                val intent = Intent(context, MusicPlaybackService::class.java).setAction(ACTION_UPDATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        /** 停止播放:停掉前台服务,移除通知(服务进程前台,startService 安全) */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, MusicPlaybackService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var coverBitmap: Bitmap? = null
    private var coverJob: Job? = null
    private var isForegroundEstablished = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "ExamateMusic").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = ctrl { if (!it.isPlaying) it.toggle() }
                override fun onPause() = ctrl { if (it.isPlaying) it.toggle() }
                override fun onSkipToNext() = ctrl { it.next() }
                override fun onSkipToPrevious() = ctrl { it.prev() }
                override fun onStop() = stopAndRelease()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndRelease()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                ensureForeground()
                ctrl { it.toggle() }
            }
            ACTION_NEXT -> {
                ensureForeground()
                ctrl { it.next() }
            }
            ACTION_PREV -> {
                ensureForeground()
                ctrl { it.prev() }
            }
            else -> { /* ACTION_START / ACTION_UPDATE / 系统重启 */ }
        }
        // 非停止动作:确保前台通知已建立,再按当前播放状态刷新(新启动与已运行统一走这里)
        startForegroundCompat(buildNotification())
        refreshNotification()
        return START_STICKY
    }

    /** 控制动作前确保前台已建立(通知按钮点击时服务一定在前台,防御性调用) */
    private fun ensureForeground() {
        if (!isForegroundEstablished) {
            startForegroundCompat(buildNotification())
            isForegroundEstablished = true
        }
    }

    /** 更新 MediaSession 元数据(含封面,锁屏控制器显示用) */
    private fun updateMetadata() {
        val controller = MusicControllerHolder.controller ?: return
        val track = controller.current
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder().apply {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "未在播放")
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "")
                if (track != null) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
                coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }.build()
        )
    }

    /** 刷新 MediaSession 元数据/状态 + 通知;封面异步加载完成后补发一次 */
    private fun refreshNotification() {
        val controller = MusicControllerHolder.controller ?: return
        val track = controller.current

        updateMetadata()
        val state = if (controller.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, controller.positionMs.toLong(), 1f)
                .build()
        )

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification())

        // 异步封面:换歌后封面更新
        if (track != null) {
            coverJob?.cancel()
            coverJob = scope.launch {
                val bmp = AlbumArtLoader.load(this@MusicPlaybackService, track)
                coverBitmap = bmp
                if (bmp != null) {
                    updateMetadata()
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIF_ID, buildNotification())
                }
            }
        } else {
            coverJob?.cancel()
            coverJob = null
            coverBitmap = null
        }
    }

    /** 构建通知:标题/歌手 + 封面 + 控制按钮(MediaStyle 展开样式) */
    private fun buildNotification(): Notification {
        val controller = MusicControllerHolder.controller
        val track = controller?.current
        val isPlaying = controller?.isPlaying == true

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, go.examate.hyp.MusicActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "音乐")
            .setContentText(track?.artistDisplay() ?: "")
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        coverBitmap?.let { builder.setLargeIcon(it) }

        builder.addAction(notifAction(ACTION_PREV, "上一首", android.R.drawable.ic_media_previous))
        builder.addAction(
            notifAction(
                ACTION_TOGGLE,
                if (isPlaying) "暂停" else "播放",
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        )
        builder.addAction(notifAction(ACTION_NEXT, "下一首", android.R.drawable.ic_media_next))
        builder.addAction(notifAction(ACTION_STOP, "关闭", android.R.drawable.ic_menu_close_clear_cancel))

        val style = MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setCancelButtonIntent(notifPendingIntent(ACTION_STOP))
        builder.setStyle(style)

        return builder.build()
    }

    private fun notifAction(action: String, label: String, icon: Int): NotificationCompat.Action {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(icon, label, pi).build()
    }

    private fun notifPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "音乐后台播放控制"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        isForegroundEstablished = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ctrl(block: (MusicController) -> Unit) {
        MusicControllerHolder.controller?.let(block)
    }

    private fun stopAndRelease() {
        isForegroundEstablished = false
        ctrl { it.release() }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        coverBitmap = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 应用从最近任务划掉:停止播放并关闭服务
        stopAndRelease()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
