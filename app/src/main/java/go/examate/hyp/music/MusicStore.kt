package go.examate.hyp.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 本地音乐曲目 — 来自 MediaStore.Audio 扫描或文件系统扫描。
 */
@Immutable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: android.net.Uri,
    val albumId: Long = -1L,
    val coverUrl: String = ""
) {
    val artistDisplay: String get() = artist.ifBlank { "未知艺术家" }
    fun durationText(): String {
        val totalSec = (durationMs / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
    /** 本地音乐封面 URI(MediaStore albumart);无 albumId 返回空 */
    val coverUri: android.net.Uri?
        get() = if (albumId > 0) android.content.ContentUris.withAppendedId(
            android.net.Uri.parse("content://media/external/audio/albumart"), albumId
        ) else null
}

/**
 * 本地音乐扫描 — 双重策略:
 *  1. 优先查 MediaStore(系统媒体库,快,但依赖索引)
 *  2. 若 MediaStore 为空,回退到文件系统递归扫描(慢,但不依赖索引)
 *
 * 过滤:时长 > 30 秒,扩展名为 mp3/flac/m4a/wav/ogg/aac
 */
object MusicStore {
    private const val TAG = "MusicStore"

    /** 支持的音频扩展名 */
    private val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus")

    /** 默认扫描根目录(MediaStore 为空时使用) */
    private val SCAN_DIRS = listOf(
        "/sdcard/Music",
        "/sdcard/Download",
        "/sdcard/DCIM",
        "/sdcard/Documents",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/Download"
    )

    fun scan(context: Context, folderRelativePath: String? = null): List<Track> {
        // 策略1:MediaStore 查询
        val mediaStoreResult = scanMediaStore(context, folderRelativePath)
        Log.d(TAG, "scan: MediaStore 返回 ${mediaStoreResult.size} 条, folderPath=$folderRelativePath")
        if (mediaStoreResult.isNotEmpty()) return mediaStoreResult

        // 策略2:文件系统递归扫描(MediaStore 为空时)
        val fsResult = scanFileSystem(folderRelativePath)
        Log.d(TAG, "scan: 文件系统扫描返回 ${fsResult.size} 条, folderPath=$folderRelativePath")
        return fsResult
    }

    /**
     * 基于 SAF 目录树扫描 —— 通过用户选定的 [treeUri] 递归列出其下所有音频文件。
     *
     * 此方式无需申请 READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE 大权限,仅依赖用户授予的
     * 目录访问授权(通过 OpenDocumentTree),权限范围最小、合规性最佳。
     *
     * @param context 上下文
     * @param treeUri OpenDocumentTree 返回的目录树 Uri(已持久化授权)
     * @return 扫描到的音频列表,按标题排序
     */
    fun scanSafTree(context: Context, treeUri: Uri): List<Track> {
        val result = mutableListOf<Track>()
        val resolver = context.contentResolver
        val childrenUri = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        }.getOrNull() ?: return emptyList()
        if (!childrenUri.isDirectory) return emptyList()

        val audioFiles = mutableListOf<androidx.documentfile.provider.DocumentFile>()
        collectAudioDocumentFiles(childrenUri, audioFiles, maxDepth = 6)
        Log.d(TAG, "scanSafTree: 找到 ${audioFiles.size} 个音频文件")

        var idCounter = 200000L
        for (doc in audioFiles) {
            val track = parseAudioDocument(context, doc, idCounter) ?: continue
            if (track.durationMs > 30000) {
                result.add(track)
                idCounter++
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    /** 递归收集 SAF 目录树下的音频 DocumentFile */
    private fun collectAudioDocumentFiles(
        dir: androidx.documentfile.provider.DocumentFile,
        out: MutableList<androidx.documentfile.provider.DocumentFile>,
        maxDepth: Int
    ) {
        if (maxDepth <= 0 || !dir.isDirectory) return
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                if (!child.name.orEmpty().startsWith(".")) {
                    collectAudioDocumentFiles(child, out, maxDepth - 1)
                }
            } else {
                val ext = child.name.orEmpty().substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXTS && child.length() > 30000) {
                    out.add(child)
                }
            }
        }
    }

    /** 用 MediaMetadataRetriever 从 DocumentFile URI 读取元数据 */
    private fun parseAudioDocument(
        context: Context,
        doc: androidx.documentfile.provider.DocumentFile,
        id: Long
    ): Track? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, doc.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: doc.name.orEmpty().substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Track(
                id = id,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                uri = doc.uri,
                albumId = -1L,
                coverUrl = doc.uri.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseAudioDocument fail: ${doc.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** MediaStore 查询(快,依赖系统媒体库索引) */
    private fun scanMediaStore(context: Context, folderRelativePath: String?): List<Track> {
        val result = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA  // 文件路径(albumart 失败时用于提取嵌入封面)
        )
        // is_music 过滤太严格(部分设备不标记),放宽为只按时长过滤
        val baseSel = "${MediaStore.Audio.Media.DURATION} > 30000"
        val (selection, args) = if (!folderRelativePath.isNullOrBlank() && android.os.Build.VERSION.SDK_INT >= 29) {
            "$baseSel AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%$folderRelativePath%")
        } else baseSel to null
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    result.add(
                        Track(
                            id = id,
                            title = cursor.getString(titleCol) ?: "未知",
                            artist = cursor.getString(artistCol).orEmpty(),
                            album = cursor.getString(albumCol).orEmpty(),
                            durationMs = cursor.getLong(durationCol),
                            uri = android.content.ContentUris.withAppendedId(collection, id),
                            albumId = albumId,
                            coverUrl = filePath  // 存文件路径,albumart 失败时用于提取嵌入封面
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "scanMediaStore fail", it) }
        return result
    }

    /**
     * 文件系统递归扫描(慢,但不依赖 MediaStore 索引)。
     * 用 MediaMetadataRetriever 读取标题/艺术家/时长。
     * folderRelativePath 非空时只扫描该目录,否则扫描默认音乐目录。
     */
    private fun scanFileSystem(folderRelativePath: String?): List<Track> {
        val result = mutableListOf<Track>()

        // 确定扫描根目录
        val rootDirs = if (!folderRelativePath.isNullOrBlank()) {
            // 用户选了文件夹:尝试几个可能的路径前缀
            listOf(
                "/sdcard/$folderRelativePath",
                "/storage/emulated/0/$folderRelativePath",
                "/sdcard/Music/$folderRelativePath"
            ).filter { File(it).exists() }
        } else {
            // 未选文件夹:扫描默认音乐目录
            SCAN_DIRS.filter { File(it).exists() }
        }

        Log.d(TAG, "scanFileSystem: rootDirs=$rootDirs")

        // 收集所有音频文件
        val audioFiles = mutableListOf<File>()
        for (root in rootDirs) {
            collectAudioFiles(File(root), audioFiles, maxDepth = 5)
        }

        Log.d(TAG, "scanFileSystem: 找到 ${audioFiles.size} 个音频文件")

        // 用 MediaMetadataRetriever 读取元数据
        var idCounter = 100000L
        for (file in audioFiles) {
            val track = parseAudioFile(file, idCounter) ?: continue
            if (track.durationMs > 30000) {
                result.add(track)
                idCounter++
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    /** 递归收集音频文件 */
    private fun collectAudioFiles(dir: File, out: MutableList<File>, maxDepth: Int) {
        if (maxDepth <= 0 || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                // 跳过隐藏目录和系统目录
                if (!child.name.startsWith(".")) {
                    collectAudioFiles(child, out, maxDepth - 1)
                }
            } else {
                val ext = child.extension.lowercase()
                if (ext in AUDIO_EXTS && child.length() > 30000) {
                    out.add(child)
                }
            }
        }
    }

    /** 用 MediaMetadataRetriever 读取单个音频文件的元数据 + 嵌入封面 */
    private fun parseAudioFile(file: File, id: Long): Track? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Track(
                id = id,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                uri = Uri.fromFile(file),
                albumId = -1L,
                coverUrl = file.absolutePath  // 文件系统扫描用文件路径,UI 层用 EmbeddedPictureLoader 加载
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseAudioFile fail: ${file.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * 播放历史存储 — 最近播放(SharedPreferences + JSON,最多 50 条,最新在前)。
 * 存 QueueItem 元信息 + localUri + historyKey;读取时重建可播放项(在线项重建 resolver)。
 */
object PlayHistoryStore {
    private const val PREFS = "music_history"
    private const val KEY = "recent"
    private const val MAX = 50

    fun load(context: Context): List<QueueItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val localUriStr = o.optString("localUri")
                val historyKey = o.optString("historyKey")
                val base = QueueItem(
                    id = o.optString("id"),
                    title = o.optString("title", "未知"),
                    artist = o.optString("artist"),
                    durationMs = o.optLong("duration"),
                    coverUrl = o.optString("cover"),
                    albumId = o.optLong("albumId", -1L),
                    historyKey = historyKey
                )
                when {
                    localUriStr.isNotBlank() -> base.copy(localUri = android.net.Uri.parse(localUriStr))
                    historyKey.startsWith("bili:") -> {
                        val parts = historyKey.split(":")
                        val bvid = parts.getOrNull(1).orEmpty()
                        val cid = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        base.copy(resolver = {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val req = runCatching {
                                    go.examate.hyp.music.bili.BiliClient(context).resolveAudio(bvid, cid)
                                }.getOrNull()
                                if (req != null) ResolveResult.Ok(req)
                                else ResolveResult.Err("B站音频解析失败")
                            }
                        })
                    }
                    else -> base
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(context: Context, item: QueueItem) {
        val list = load(context).filterNot { it.id == item.id }.toMutableList()
        list.add(0, item)
        val trimmed = list.take(MAX)
        val arr = org.json.JSONArray()
        trimmed.forEach { t ->
            arr.put(org.json.JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist)
                put("duration", t.durationMs); put("cover", t.coverUrl)
                put("albumId", t.albumId)
                put("localUri", t.localUri?.toString().orEmpty()); put("historyKey", t.historyKey)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
