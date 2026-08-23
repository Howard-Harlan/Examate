package go.examate.hyp.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一封面加载器(底层重构,单一入口)。
 *
 * [load] 对任何曲目(本地/在线)返回封面 Bitmap,全部失败返回 null:
 *  - 在线封面(coverUrl http):OkHttp 下载字节 → 采样解码;自动补全协议相对 URL(//→https:)
 *  - 本地嵌入封面:content URI 三级提取(openFileDescriptor → setDataSource(context,uri) → 复制缓存)
 *    → 文件路径直读
 *  - 系统 albumart URI
 *  - 歌曲同目录封面图(cover.jpg / folder.jpg 等)
 *
 * 网络封面不用 Coil 而是 OkHttp:可控 UA/超时/失败日志,兼容各音源防盗链;本地与在线统一一套解码。
 */
object AlbumArtLoader {
    private const val TAG = "AlbumArt"
    private const val MAX_DECODE = 1024

    private val httpClient = MusicHttp.derived(connectTimeout = 10, readTimeout = 15)

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(32 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** 唯一入口:本地/在线 → 封面 Bitmap(带内存缓存,按 item.id 缓存) */
    suspend fun load(context: Context, item: QueueItem): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(item.id) ?: loadRaw(context, item)?.also { cache.put(item.id, it) }
    }

    private suspend fun loadRaw(context: Context, item: QueueItem): Bitmap? = withContext(Dispatchers.IO) {
        val url = normalizeUrl(item.coverUrl)
        Log.d(TAG, "load: title=${item.title} | coverUrl=${item.coverUrl} | localUri=${item.localUri} | albumId=${item.albumId}")
        // 0. 平台级封面修复(优先于直连,避免把占位图当封面显示):
        //    - 酷狗:搜索返回的 img 是占位图(imgessl…/stdmusic/150/…),真实封面经 get_res_privilege 换取
        //    - 酷我:歌单详情等场景封面为空,按 MUSICRID 经 pic.web 取真实封面
        val platform = if (item.id.startsWith("lx_")) item.id.removePrefix("lx_").substringBefore('_') else ""
        val songmid = if (platform.isNotBlank()) item.id.removePrefix("lx_").substringAfter('_', "") else ""
        if (platform == "kg") {
            loadKgCover(item, songmid)?.let {
                Log.d(TAG, "load: 酷狗真实封面成功 title=${item.title}")
                return@withContext it
            }
        }
        if (platform == "kw" && url.isBlank()) {
            val pic = go.examate.hyp.music.lx.KuwoCoverApi.getPicUrl(songmid)
            if (pic != null) {
                loadRemote(pic)?.let {
                    Log.d(TAG, "load: 酷我 pic.web 封面成功 title=${item.title}")
                    return@withContext it
                }
            }
        }
        // 1. 在线封面 URL(酷狗占位图跳过,避免加载到假封面)
        val isKgPlaceholder = platform == "kg" && url.contains("imgessl.kugou.com/stdmusic/150/")
        if (!isKgPlaceholder && url.startsWith("http")) {
            loadRemote(url)?.let {
                Log.d(TAG, "load: 在线封面成功 title=${item.title}")
                return@withContext it
            }
        }
        // 2. 本地嵌入封面
        loadEmbedded(context, item, url)?.let {
            Log.d(TAG, "load: 嵌入封面成功 title=${item.title}")
            return@withContext it
        }
        // 3. 系统专辑图
        loadAlbumArt(context, item)?.let {
            Log.d(TAG, "load: albumart 成功 title=${item.title}")
            return@withContext it
        }
        // 4. 同目录封面图
        loadFolderCover(url)?.let {
            Log.d(TAG, "load: 目录封面成功 title=${item.title}")
            return@withContext it
        }
        // 5. coverRef 兜底(如网易云 albumId → 专辑详情 API 解析 picUrl)
        if (item.coverRef.isNotBlank()) {
            loadViaCoverRef(item.coverRef)?.let {
                Log.d(TAG, "load: coverRef 兜底成功 title=${item.title} ref=${item.coverRef}")
                return@withContext it
            }
        }
        // 6. lx 在线曲目封面兜底:
        //    6a. 酷狗真实封面(步骤 0 已尝试,此处不再重复)
        //    6b. 脚本 pic action 兜底(参照落雪 getPicUrl;酷狗脚本不支持 pic 且封面走官方 API,跳过 kg)
        if (item.id.startsWith("lx_")) {
            val rest = item.id.removePrefix("lx_")
            val sep = rest.indexOf('_')
            if (sep > 0) {
                val platform = rest.substring(0, sep)
                val songmid = rest.substring(sep + 1)
                // 6b. 仅当在线封面 URL 为空且非酷狗时,才走音源脚本 pic action
                if (url.isBlank() && platform != "kg") {
                    val song = go.examate.hyp.music.lx.LxSong(
                        platform, songmid, item.title, item.artist, item.durationMs, item.coverUrl, item.coverRef
                    )
                    val picUrl = go.examate.hyp.music.lx.LxManager(context).resolvePic(song)
                    if (picUrl.isNotBlank()) {
                        Log.d(TAG, "load: lx pic action 返回 url=$picUrl title=${item.title}")
                        loadRemote(picUrl)?.let {
                            Log.d(TAG, "load: lx pic 封面成功 title=${item.title}")
                            return@withContext it
                        }
                    }
                }
            }
        }
        Log.w(TAG, "load: 全部失败 title=${item.title} coverUrl=${item.coverUrl}")
        null
    }

    /** 协议相对 URL(//xxx)补全为 https,空串保持 */
    private fun normalizeUrl(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    /**
     * 酷狗真实封面统一入口(官方源,不走解析脚本):
     *  - 有 albumId(搜索返回的 coverRef) → 直接 get_res_privilege 换取
     *  - 无 albumId(部分歌曲搜索不返回) → 先经 gateway v3 album_audio/audio 按 hash 补齐
     *    专辑信息(对标落雪 kg/musicInfo.js),再换取封面
     */
    private suspend fun loadKgCover(item: QueueItem, hash: String): Bitmap? {
        if (hash.isBlank()) return null
        var albumId = item.coverRef
        if (albumId.isBlank() || albumId == "0") {
            val info = go.examate.hyp.music.lx.KugouSongInfoApi.getSongInfo(hash) ?: return null
            Log.d(TAG, "loadKgCover: gateway 补齐信息 albumId=${info.albumId} album=${info.albumName} title=${item.title}")
            // gateway 直接返回封面模板(sizable_cover),优先用它,省一次 get_res_privilege 换取
            if (info.coverTemplate.isNotBlank()) {
                loadRemote(info.coverTemplate.replace("{size}", "800"))?.let { return it }
            }
            albumId = info.albumId
        }
        if (albumId.isBlank() || albumId == "0") return null
        val real = go.examate.hyp.music.lx.KugouCoverApi.getRealCoverUrl(albumId, hash, item.title)
        return if (real != null) loadRemote(real) else null
    }

    /** 在线封面:OkHttp 下载 + 采样解码(失败重试 1 次,网络波动常见) */
    private suspend fun loadRemote(url: String): Bitmap? {
        repeat(2) { attempt ->
            val bmp = loadRemoteOnce(url)
            if (bmp != null) return bmp
            if (attempt == 0) {
                Log.w(TAG, "loadRemote 第 1 次失败,重试: $url")
                delay(300)
            }
        }
        return null
    }

    private fun loadRemoteOnce(url: String): Bitmap? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "loadRemote: HTTP ${resp.code} url=$url")
                return null
            }
            val bytes = resp.body?.bytes() ?: run {
                Log.w(TAG, "loadRemote: body 为空 url=$url")
                return null
            }
            val bmp = decodeSampled(bytes)
            Log.d(TAG, "loadRemote: HTTP 200 bytes=${bytes.size} decode=${bmp != null} url=$url")
            bmp
        }
    }.getOrNull()

    /**
     * coverRef 兜底:通过平台引用取封面。
     * 目前支持网易云 albumId → 专辑详情 API(新版搜索 API 不再返回 picUrl,但详情接口有)。
     */
    private suspend fun loadViaCoverRef(ref: String): Bitmap? {
        val picUrl: String = runCatching {
            val req = Request.Builder()
                .url("https://music.163.com/api/album/$ref")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile")
                .header("Referer", "https://music.163.com")
                .build()
            var result = ""
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = org.json.JSONObject(resp.body?.string() ?: "")
                    result = json.optJSONObject("album")?.optString("picUrl").orEmpty()
                } else {
                    Log.w(TAG, "loadViaCoverRef: HTTP ${resp.code} ref=$ref")
                }
            }
            result
        }.getOrNull().orEmpty()
        if (picUrl.isBlank()) return null
        return loadRemote(picUrl)
    }

    // ── 本地嵌入封面 ──

    private fun loadEmbedded(context: Context, item: QueueItem, url: String): Bitmap? {
        val bytes: ByteArray? = when {
            url.startsWith("http") -> null
            item.localUri != null -> {
                // 优先遍历式解析(ID3 APIC / FLAC PICTURE 等,不假设块顺序),失败回退系统接口
                val b = extractWithTagParser(context, item.localUri)
                Log.d(TAG, "loadEmbedded: localUri=${item.localUri} tagParserBytes=${b?.size ?: -1}")
                b ?: extractFromContentUri(context, item.localUri)
            }
            url.startsWith("content://") -> {
                val b = extractWithTagParser(context, Uri.parse(url))
                b ?: extractFromContentUri(context, Uri.parse(url))
            }
            url.isNotBlank() -> {
                val b = extractWithTagParserPath(url)
                Log.d(TAG, "loadEmbedded: path=$url tagParserBytes=${b?.size ?: -1}")
                b ?: extractFromPath(url)
            }
            else -> null
        }
        return bytes?.let { decodeSampled(it) }
    }

    /** 遍历式标签解析取内嵌封面(支持 ID3 APIC/PIC、FLAC PICTURE、Vorbis Comment 内嵌图) */
    private fun extractWithTagParser(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            LocalTagParser.readFromStream(input).coverBytes
        }
    }.getOrNull()

    private fun extractWithTagParserPath(path: String): ByteArray? = runCatching {
        LocalTagParser.readFromPath(path).coverBytes
    }.getOrNull()

    /** content URI 提取嵌入封面:fd → context → 复制缓存 三级兜底(零权限) */
    private fun extractFromContentUri(context: Context, uri: Uri): ByteArray? =
        extractByFd(context, uri)
            ?: extractByContext(context, uri)
            ?: extractViaCache(context, uri)

    private fun extractByFd(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.embeddedPicture
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractByContext(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 终极兜底:复制到缓存目录再按路径提取(Provider 不支持 seek 时);>60MB 放弃 */
    private fun extractViaCache(context: Context, uri: Uri): ByteArray? = runCatching {
        val temp = File(context.cacheDir, "cover_extract_${System.currentTimeMillis()}.mp3")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
        } ?: return null
        if (temp.length() > 60L * 1024 * 1024) { temp.delete(); return null }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(temp.absolutePath)
            r.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
            temp.delete()
        }
    }.getOrNull()

    /** 本地文件路径直读嵌入封面 */
    private fun extractFromPath(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── 系统专辑图 / 目录封面 ──

    private fun loadAlbumArt(context: Context, item: QueueItem): Bitmap? {
        if (item.albumId <= 0) return null
        return runCatching {
            val uri = android.content.ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), item.albumId
            )
            context.contentResolver.openInputStream(uri)?.use { stream ->
                decodeSampled(stream.readBytes())
            }
        }.getOrNull()
    }

    /** 歌曲同目录封面图(cover.jpg / folder.jpg / AlbumArt.jpg 等) */
    private fun loadFolderCover(url: String): Bitmap? {
        if (url.isBlank() || url.startsWith("content://") || url.startsWith("http")) return null
        return runCatching {
            val dir = File(url).parentFile ?: return null
            if (!dir.isDirectory) return null
            val candidates = listOf(
                "cover.jpg", "cover.png", "folder.jpg", "folder.png",
                "AlbumArt.jpg", "AlbumArtSmall.jpg", "front.jpg", "front.png"
            )
            for (name in candidates) {
                val f = File(dir, name)
                if (f.exists() && f.isFile) {
                    return decodeSampled(f.readBytes())
                }
            }
            null
        }.getOrNull()
    }

    /** 采样解码,限制最大边长(列表/背景无需全尺寸) */
    private fun decodeSampled(bytes: ByteArray, maxSize: Int = MAX_DECODE): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }
}
