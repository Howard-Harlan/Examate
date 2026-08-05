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
                    val fx = remember { AudioEffects(context).also { effects = it } }
                    val ctrl = remember {
                        MusicController(context).apply {
                            effects = fx
                            this@MusicActivity.controller = this
                            MusicControllerHolder.controller = this
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

                    // LX 音源导入(文件/链接二选一,复用通用组件);导入后跑一次回填声明平台
                    val lxImporter = go.examate.hyp.media.rememberSourceImporter(
                        title = "导入 LX 音源",
                        linkHint = "https://…/lx_music.js"
                    ) { text ->
                        val src = go.examate.hyp.music.lx.LxSourceStore.importScript(context, text)
                        if (src != null) {
                            go.examate.hyp.util.ToastUtil.show(context, "已导入:${src.name}")
                            kotlin.concurrent.thread { runCatching { go.examate.hyp.music.lx.LxManager(context).probeSources(src) } }
                        } else {
                            val msg = when {
                                text.trimStart().startsWith("http") -> "导入失败:请粘贴脚本内容,不是链接地址(用「粘贴链接」功能下载)"
                                text.contains("globalThis") || text.contains("lx.on(") -> "导入失败:脚本格式不正确"
                                else -> "导入失败:非有效 LX 音源脚本(需含 lx.on 注册处理器)"
                            }
                            go.examate.hyp.util.ToastUtil.show(context, msg)
                        }
                    }

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
                        onBiliLogin = {
                            context.startActivity(android.content.Intent(context, go.examate.hyp.music.bili.BiliLoginActivity::class.java))
                        },
                        onLxImport = lxImporter.open,
                        folderTreeUri = folderTreeUri
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        MusicControllerHolder.controller = null
        effects?.release()
        controller?.release()
        effects = null
        controller = null
        super.onDestroy()
    }
}
