package go.examate.hyp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import go.examate.hyp.music.AudioEffects
import go.examate.hyp.music.MusicController
import go.examate.hyp.music.MusicControllerHolder
import go.examate.hyp.music.MusicScreen
import go.examate.hyp.music.QueueItem
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 URI 构建可播放的 QueueItem(系统打开音频文件时使用)。
 * 用 MediaMetadataRetriever 提取标题/艺术家/时长/嵌入封面。
 */
private suspend fun buildItemFromUri(context: Context, uri: Uri): QueueItem? {
    return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "未知"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            QueueItem(
                id = "ext_${uri.hashCode()}",
                title = title,
                artist = artist,
                durationMs = durationMs,
                localUri = uri,
                coverUrl = ""  // 封面由 MusicCover 通过 localUri 提取嵌入封面
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * 音乐 — 本地音乐播放器(原生 MediaPlayer + MediaStore + AudioEffect,零第三方播放库)。
 * 无顶栏、悬浮按钮;主页两卡 + 底部玻璃播放条 + 独立「我的」按钮。
 *
 * 播放器为应用级单例(ExamateApp.musicController):本页只是 UI 载体,
 * 退出页面不停止播放,通知栏控制持续可用;整个应用退出才停止。
 */
class MusicActivity : ComponentActivity() {

    private var controller: MusicController? = null
    private var effects: AudioEffects? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 系统打开音频文件:从 ACTION_VIEW intent 取 URI,创建临时播放项
        val pendingAudioUri = if (intent?.action == android.content.Intent.ACTION_VIEW) intent.data else null
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    val context = LocalContext.current
                    // 播放器/音效均为应用级单例:跨页面存活,退出音乐页不释放
                    val fx = remember { (context.applicationContext as ExamateApp).musicEffects.also { effects = it } }
                    val ctrl = remember {
                        (context.applicationContext as ExamateApp).musicController.apply {
                            effects = fx
                            this@MusicActivity.controller = this
                            // MusicControllerHolder 已在 ExamateApp 创建时赋值,此处保持指向同一实例
                            MusicControllerHolder.controller = this
                        }
                    }

                    // 通知栏控制(Android 13+):首次进入请求通知权限,否则前台服务通知不显示
                    val notifPerm = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // 恢复上次播放状态:进入时若队列为空,加载最近播放历史(PlayerBar 显示上次曲目)
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (ctrl.current == null) {
                            val history = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                go.examate.hyp.music.PlayHistoryStore.load(context)
                            }
                            if (history.isNotEmpty()) ctrl.restore(history, 0)
                        }
                    }

                    // 系统打开音频文件:提取元数据后加入队列并播放
                    androidx.compose.runtime.LaunchedEffect(pendingAudioUri) {
                        val uri = pendingAudioUri ?: return@LaunchedEffect
                        val item = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            buildItemFromUri(context, uri)
                        }
                        if (item != null) {
                            ctrl.playQueue(listOf(item), 0)
                        } else {
                            go.examate.hyp.util.ToastUtil.show(context, "无法播放此音频文件")
                        }
                    }

                    // ═══ 本地音乐:SAF 选文件夹授权(替代 READ_MEDIA_AUDIO 大权限) ═══
                    val prefs = remember { context.getSharedPreferences("music_prefs", android.content.Context.MODE_PRIVATE) }
                    // folder 名称(显示用)与 treeUri(扫描用)都持久化
                    var folder by remember { mutableStateOf<String?>(prefs.getString("local_folder", null)) }
                    var folderTreeUri by remember {
                        mutableStateOf<android.net.Uri?>(
                            prefs.getString("local_folder_uri", null)?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                        )
                    }

                    // 选文件夹:SAF 目录树,持久化授权 + 保存 URI
                    val folderPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            // 持久化授权,避免重启后失效
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            val seg = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                            val name = seg?.ifBlank { null }
                            folder = name
                            folderTreeUri = uri
                            prefs.edit()
                                .putString("local_folder", name)
                                .putString("local_folder_uri", uri.toString())
                                .apply()
                        }
                    }

                    // LX 音源导入(文件/链接二选一,复用通用组件);
                    // 竞速验证模式:多通道候选到达一个验一个(服务端 sign 探针),
                    // 第一个通过者入库 — 不等死通道,全程有进度提示
                    val lxImporter = go.examate.hyp.media.rememberSourceImporter(
                        title = "导入 LX 音源",
                        linkHint = "https://…/lx_music.js",
                        raceValidator = { text -> tryImportOneLx(context, text) },
                        onProgress = { msg -> go.examate.hyp.util.ToastUtil.show(context, msg) },
                        onText = { }
                    )

                    MusicScreen(
                        controller = ctrl,
                        effects = fx,
                        hasPermission = folderTreeUri != null,
                        onRequestPermission = { folderPicker.launch(null) },
                        onPickFolder = { folderPicker.launch(null) },
                        currentFolder = folder,
                        onClearFolder = {
                            prefs.edit()
                                .remove("local_folder")
                                .remove("local_folder_uri")
                                .apply()
                            folder = null
                            folderTreeUri = null
                        },
                        onLxImport = lxImporter.open,
                        folderTreeUri = folderTreeUri
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // 播放器/音效为应用级单例,退出音乐页不释放、不停止播放;
        // 音乐继续在后台(前台服务)播放,直到整个应用退出或用户主动停止。
        effects = null
        controller = null
        super.onDestroy()
    }
}

/**
 * LX 音源单候选导入验证(后台线程):入库 → 回填声明 → 服务端 sign 探针。
 * 通过 → 保留并提示,true;拒签/无效 → 即时删除并给具体原因,false。
 */
private fun tryImportOneLx(context: android.content.Context, text: String): Boolean {
    val src = go.examate.hyp.music.lx.LxSourceStore.importScript(context, text)
    if (src == null) {
        val msg = when {
            text.trimStart().startsWith("http") -> "导入失败:请粘贴脚本内容,不是链接地址(用「粘贴链接」功能下载)"
            text.contains("globalThis") || text.contains("lx.on(") -> "导入失败:脚本格式不正确"
            else -> "导入失败:非有效 LX 音源脚本(需含 lx.on 注册处理器)"
        }
        go.examate.hyp.util.ToastUtil.show(context, msg)
        return false
    }
    val mgr = go.examate.hyp.music.lx.LxManager(context)
    runCatching { mgr.probeSources(src) }   // 回填声明平台(引擎留池复用)
    return if (mgr.verifyAgainstServer(src)) {
        go.examate.hyp.util.ToastUtil.show(context, "导入成功:${src.name}(已通过服务端校验)")
        true
    } else {
        go.examate.hyp.music.lx.LxSourceStore.delete(context, src.id)
        android.util.Log.w("MusicActivity", "LX 候选载荷被服务端拒签,已移除")
        false
    }
}
