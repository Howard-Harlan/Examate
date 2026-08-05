package go.examate.hyp.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 下载任务状态 */
enum class DownloadStatus { PENDING, RUNNING, PAUSED, FAILED, DONE }

/**
 * 下载分段
 *
 * @param start 分段起始绝对偏移(含)
 * @param end   分段结束绝对偏移(含);-1 表示未知长度,流式下载到结尾
 * @param current 已写入到的绝对偏移(下次从此处继续)
 */
data class Segment(val start: Long, val end: Long, val current: Long) {
    /** 该分段是否已完成 */
    val isDone: Boolean get() = end >= 0 && current > end
}

/** 下载任务(不可变,更新时整体 copy) */
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    /** 瞬时速度(字节/秒),仅 RUNNING 时有意义,不持久化 */
    val speedBps: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val error: String = "",
    /** 完成后导出到系统下载目录的 MediaStore Uri */
    val mediaUri: String = "",
    /** 分段计划(空 = 尚未探测);每段独立记录断点 */
    val segments: List<Segment> = emptyList(),
    /** 远端内容校验符(ETag 或 Last-Modified),续传时用 If-Range 校验文件是否已变更 */
    val validator: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 实际使用的线程数 */
    val threadCount: Int get() = segments.size.coerceAtLeast(1)
}

/**
 * 直链下载中心(应用级单例)
 *
 * aria2 风格的 HTTP/HTTPS 多线程分段下载:
 * - Range 分段并发:文件切成 N 段,各段协程用 RandomAccessFile 按绝对偏移写同一文件,无需二次合并
 * - 分段断点续传:每段独立记录断点游标并持久化,暂停/重启后各段从自己的断点继续
 * - 服务器不支持 Range 或长度未知时自动回退单线程流式下载
 * - 全局限速(令牌桶)、分段失败重试、可配 UA
 * - 完成后经 MediaStore 导出到系统 Download/Examate,无需存储权限
 */
object DownloadCenter {

    private const val TAG = "DownloadCenter"
    private const val EXPORT_DIR = "Examate"
    private const val BUFFER_SIZE = 64 * 1024

    private lateinit var appContext: Context
    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val limiter = RateLimiter()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 初始化并恢复历史任务(重复调用无副作用) */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            DownloadSettings.init(appContext)
            _tasks.value = loadPersisted()
            initialized = true
        }
    }

    /** 新增下载任务,返回错误信息(null 表示成功入队) */
    fun add(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "仅支持 http/https 直链"
        }
        if (_tasks.value.any { it.url == trimmed && it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED }) {
            return "该链接已在下载列表中"
        }
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = trimmed,
            fileName = guessFileNameFromUrl(trimmed)
        )
        _tasks.update { listOf(task) + it }
        persist()
        maybeStartQueued()
        return null
    }

    /** 暂停任务(分段进度已在内存中,先落盘再取消) */
    fun pause(id: String) {
        updateTask(id) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0L) }
        persist()
        jobs.remove(id)?.cancel()
        maybeStartQueued()
    }

    /** 恢复/重试任务 */
    fun resume(id: String) {
        val task = _tasks.value.find { it.id == id } ?: return
        if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.DONE) return
        updateTask(id) { it.copy(status = DownloadStatus.PENDING, error = "") }
        persist()
        maybeStartQueued()
    }

    /** 删除任务记录及未完成的临时文件(已完成导出的文件保留在系统下载目录) */
    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        tempFile(id).delete()
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
        maybeStartQueued()
    }

    /** 打开已完成的文件 */
    fun openFile(context: Context, task: DownloadTask) {
        if (task.mediaUri.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(task.mediaUri), guessMime(task.fileName) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            ToastUtil.show(context, "没有可打开此文件的应用")
        }
    }

    // ════════════════════════════════════════
    //  可编程下载入口(供阅读/观影/漫画等模块复用,不进任务列表、不导出系统目录)
    // ════════════════════════════════════════

    /**
     * 直接把 URL 下载到指定文件并 suspend 等待完成。复用本模块的共享 OkHttpClient。
     *
     * 与 [add] 的区别:不进任务列表、不做分段/断点、不导出系统下载目录 —— 面向
     * "落到应用私有目录、由程序消费"的场景(缓存整章、下载视频、批量漫画图)。
     *
     * @param url http/https 直链
     * @param target 目标文件(调用方决定目录/文件名,如 filesDir 下私有路径)
     * @param headers 自定义请求头(如 Referer 防盗链)
     * @return 成功返回 target;失败返回 null(不抛异常)
     */
    suspend fun fetchToFile(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap()
    ): File? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        try {
            target.parentFile?.mkdirs()
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            if (headers.keys.none { it.equals("User-Agent", true) }) {
                reqBuilder.addHeader("User-Agent", DownloadSettings.userAgent)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                target.outputStream().use { out -> body.byteStream().copyTo(out, BUFFER_SIZE) }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "fetchToFile fail: $url", e)
            runCatching { target.delete() }
            null
        }
    }


    // ════════════════════════════════════════
    //  调度
    // ════════════════════════════════════════

    /** 按并发上限启动排队中的任务(同步:从主线程与多个 IO 协程并发调用,避免 check-then-act 竞态超发) */
    @Synchronized
    private fun maybeStartQueued() {
        val limit = DownloadSettings.maxConcurrentTasks
        val list = _tasks.value
        var running = list.count { it.status == DownloadStatus.RUNNING }
        list.filter { it.status == DownloadStatus.PENDING }
            .sortedByDescending { it.createdAt }
            .forEach { task ->
                if (running >= limit) return
                // 防御:该任务仍有存活 Job 时不重复启动
                if (jobs.containsKey(task.id)) return@forEach
                running++
                updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING) }
                launchDownload(task.id)
            }
    }

    private fun launchDownload(id: String) {
        val job = scope.launch {
            try {
                executeDownload(id)
            } catch (e: CancellationException) {
                throw e // 暂停/删除导致的取消,状态已由调用方设置
            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}", e)
                updateTask(id) { it.copy(status = DownloadStatus.FAILED, speedBps = 0L, error = e.message ?: "未知错误") }
                persist()
                maybeStartQueued()
            }
        }
        jobs[id] = job
        // 只在映射值仍是自己时移除:避免旧 Job 结束时把"暂停后快速恢复"产生的新 Job 挤掉
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    // ════════════════════════════════════════
    //  下载执行
    // ════════════════════════════════════════

    private suspend fun executeDownload(id: String) {
        val task = _tasks.value.find { it.id == id } ?: return
        val temp = tempFile(id)
        temp.parentFile?.mkdirs()

        // ── 1. 分段计划:已有则直接续传,否则探测服务器能力后切分 ──
        var segments = task.segments
        var total = task.totalBytes
        var validator = task.validator
        if (segments.isEmpty()) {
            val probe = probeUrl(task.url)
            total = probe.total
            validator = probe.validator
            val count = planSegmentCount(total, probe.supportsRange)
            segments = planSegments(total, count)
            val fileName = probe.fileName?.let { sanitizeFileName(it) } ?: task.fileName
            updateTask(id) { it.copy(totalBytes = total, fileName = fileName, segments = segments, validator = validator) }
            persist()
            // 预分配文件,避免多段并发写造成碎片
            if (total > 0) {
                runCatching { RandomAccessFile(temp, "rw").use { it.setLength(total) } }
            }
        } else if (!temp.exists()) {
            // 临时文件丢失(被清理),重置所有分段断点
            segments = segments.map { it.copy(current = it.start) }
            updateTask(id) { it.copy(segments = segments, downloadedBytes = 0L) }
            if (total > 0) {
                runCatching { RandomAccessFile(temp, "rw").use { it.setLength(total) } }
            }
        }

        // ── 2. 各分段并发下载,独立协程汇报聚合进度 ──
        val plan = segments
        val progress = plan.map { AtomicLong(it.current) }
        coroutineScope {
            val reporter = launch { reportProgress(id, plan, progress) }
            plan.mapIndexed { index, segment ->
                async { downloadSegment(task.url, temp, segment, progress[index], validator) }
            }.awaitAll()
            reporter.cancel()
        }

        // ── 3. 收尾:校验完整性后导出到系统下载目录 ──
        val downloaded = plan.indices.sumOf { progress[it].get() - plan[it].start }
        // chunked/无长度响应下提前 EOF 不会抛异常,必须显式比对,防止导出残缺文件
        if (total > 0 && downloaded != total) {
            throw IllegalStateException("下载不完整: $downloaded/$total 字节")
        }
        val finalName = _tasks.value.find { it.id == id }?.fileName ?: task.fileName
        val uri = exportToDownloads(temp, finalName)
        temp.delete()
        updateTask(id) {
            it.copy(
                status = DownloadStatus.DONE,
                downloadedBytes = downloaded,
                totalBytes = if (total > 0) total else downloaded,
                speedBps = 0L,
                mediaUri = uri?.toString() ?: "",
                segments = emptyList() // 已完成,分段信息无需保留
            )
        }
        persist()
        maybeStartQueued()
    }

    /** 单个分段下载(带重试),按文件绝对偏移写入,重复写同偏移是安全的 */
    private suspend fun downloadSegment(
        url: String,
        temp: File,
        segment: Segment,
        progress: AtomicLong,
        validator: String
    ) {
        val maxRetries = DownloadSettings.maxRetries
        var attempt = 0
        while (true) {
            try {
                transferSegment(url, temp, segment, progress, validator)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) throw e
                Log.w(TAG, "分段重试 $attempt/$maxRetries: ${e.message}")
                delay(1000L * attempt)
            }
        }
    }

    private suspend fun transferSegment(
        url: String,
        temp: File,
        segment: Segment,
        progress: AtomicLong,
        validator: String
    ) {
        var offset = progress.get()
        if (segment.end >= 0 && offset > segment.end) return // 该段已完成

        val builder = Request.Builder().url(url)
            .header("User-Agent", DownloadSettings.userAgent)
        when {
            segment.end >= 0 -> builder.header("Range", "bytes=$offset-${segment.end}")
            offset > 0 -> builder.header("Range", "bytes=$offset-")
        }
        // 真正续传时带 If-Range:远端文件已变更时服务器会返回 200 而非 206,下方分支会拦住
        if (validator.isNotEmpty() && offset > segment.start) {
            builder.header("If-Range", validator)
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("服务器返回 HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应无内容")
            // 请求了 Range 却返回 200:响应体是从第 0 字节开始的完整内容。
            // 非首段若按 segment.start 落盘会写入错位数据,只能报错重试;首段可安全地从头重写。
            if (offset > 0 && resp.code != 206) {
                if (segment.start > 0) {
                    throw IllegalStateException("服务器不支持分段续传(返回 ${resp.code})")
                }
                offset = 0L
                progress.set(0L)
            }
            val limit = DownloadSettings.speedLimitBps
            RandomAccessFile(temp, "rw").use { raf ->
                raf.seek(offset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        // 截断到段边界:服务器可能忽略 Range 的 end 而多发数据,避免越界覆盖相邻分段
                        val writable = if (segment.end >= 0) {
                            minOf(read.toLong(), segment.end - offset + 1).toInt()
                        } else read
                        if (writable <= 0) break
                        limiter.acquire(writable, limit)
                        raf.write(buffer, 0, writable)
                        offset += writable
                        progress.set(offset)
                        if (segment.end >= 0 && offset > segment.end) break
                    }
                }
            }
        }
    }

    /** 定时汇总各段进度,计算速度并回写任务(节流,避免高频重组) */
    private suspend fun reportProgress(id: String, plan: List<Segment>, progress: List<AtomicLong>) {
        var lastTime = System.currentTimeMillis()
        var lastBytes = plan.indices.sumOf { progress[it].get() - plan[it].start }
        while (currentCoroutineContext().isActive) {
            delay(400)
            val downloaded = plan.indices.sumOf { progress[it].get() - plan[it].start }
            val now = System.currentTimeMillis()
            val speed = (downloaded - lastBytes) * 1000 / (now - lastTime).coerceAtLeast(1)
            updateTask(id) { t ->
                t.copy(
                    downloadedBytes = downloaded,
                    speedBps = speed.coerceAtLeast(0),
                    segments = plan.mapIndexed { i, s -> s.copy(current = progress[i].get()) }
                )
            }
            lastBytes = downloaded
            lastTime = now
        }
    }

    // ════════════════════════════════════════
    //  探测与分段计划
    // ════════════════════════════════════════

    private data class ProbeResult(
        val total: Long,
        val supportsRange: Boolean,
        val fileName: String?,
        val validator: String
    )

    /**
     * 探测总大小与 Range 支持。
     * 用 `Range: bytes=0-0` 而非 HEAD:部分服务器不实现 HEAD,但会正确响应 206 + Content-Range。
     */
    private fun probeUrl(url: String): ProbeResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", DownloadSettings.userAgent)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("服务器返回 HTTP ${resp.code}")
            val fileName = parseDispositionFileName(resp.header("Content-Disposition"))
            val rangeTotal = parseContentRangeTotal(resp.header("Content-Range"))
            // ETag 优先,次选 Last-Modified,作为续传时的 If-Range 校验符
            val validator = resp.header("ETag") ?: resp.header("Last-Modified") ?: ""
            if (resp.code == 206) {
                // 206 的 Content-Length 是本次分部长度(=1),绝不能当总大小;
                // Content-Range 缺失或为 "bytes 0-0/*" 时按长度未知处理,回退单线程流式
                return ProbeResult(rangeTotal ?: -1L, rangeTotal != null, fileName, validator)
            }
            // 200:不支持 Range,长度取 Content-Length
            val length = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            return ProbeResult(length, false, fileName, validator)
        }
    }

    /** 决定分段数:需支持 Range、长度已知且足够大 */
    private fun planSegmentCount(total: Long, supportsRange: Boolean): Int {
        if (!supportsRange || total <= 0) return 1
        val configured = DownloadSettings.segmentCount
        if (configured <= 1) return 1
        // 保证每段不小于 MIN_SEGMENT_BYTES,避免小文件切太碎
        val maxBySize = (total / DownloadSettings.MIN_SEGMENT_BYTES).toInt()
        return configured.coerceAtMost(maxBySize).coerceAtLeast(1)
    }

    /** 按段数均分 [0, total),末段吸收余数;单段或长度未知时返回流式段 */
    private fun planSegments(total: Long, count: Int): List<Segment> {
        if (count <= 1 || total <= 0) {
            return listOf(Segment(start = 0L, end = if (total > 0) total - 1 else -1L, current = 0L))
        }
        val size = total / count
        return (0 until count).map { i ->
            val start = i * size
            val end = if (i == count - 1) total - 1 else start + size - 1
            Segment(start = start, end = end, current = start)
        }
    }

    // ════════════════════════════════════════
    //  导出与工具
    // ════════════════════════════════════════

    /** 通过 MediaStore 导出到 Download/Examate,返回内容 Uri */
    private fun exportToDownloads(src: File, fileName: String): Uri? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
            guessMime(fileName)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入系统下载目录")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "无法写入系统下载目录" }
            src.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun tempFile(id: String) = File(appContext.filesDir, "downloads/$id.part")

    private fun guessFileNameFromUrl(url: String): String {
        val segment = Uri.parse(url).lastPathSegment?.substringBefore('?')?.trim()
        val name = if (segment.isNullOrBlank()) "download_${System.currentTimeMillis()}" else segment
        return sanitizeFileName(runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name))
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "download_${System.currentTimeMillis()}" }

    /** 解析 Content-Disposition 中的文件名(支持 filename*=UTF-8'' 与 filename=) */
    private fun parseDispositionFileName(disposition: String?): String? {
        disposition ?: return null
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8") }.getOrNull()
        }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    /** 解析 "bytes 0-0/1000" 中的 total */
    private fun parseContentRangeTotal(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    private fun guessMime(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    // ════════════════════════════════════════
    //  持久化
    // ════════════════════════════════════════

    private fun persistFile() = File(appContext.filesDir, "downloads.json")

    private fun persist() {
        try {
            val arr = JSONArray()
            _tasks.value.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("fileName", t.fileName)
                    put("totalBytes", t.totalBytes)
                    put("downloadedBytes", t.downloadedBytes)
                    put("status", t.status.name)
                    put("error", t.error)
                    put("mediaUri", t.mediaUri)
                    put("validator", t.validator)
                    put("createdAt", t.createdAt)
                    put("segments", JSONArray().apply {
                        t.segments.forEach { s ->
                            put(JSONObject().apply {
                                put("start", s.start)
                                put("end", s.end)
                                put("current", s.current)
                            })
                        }
                    })
                })
            }
            persistFile().writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "持久化下载任务失败", e)
        }
    }

    private fun loadPersisted(): List<DownloadTask> {
        return try {
            val file = persistFile()
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rawStatus = runCatching { DownloadStatus.valueOf(o.getString("status")) }
                    .getOrDefault(DownloadStatus.PAUSED)
                val segArr = o.optJSONArray("segments")
                val segments = if (segArr == null) emptyList() else (0 until segArr.length()).map { j ->
                    val s = segArr.getJSONObject(j)
                    Segment(s.optLong("start"), s.optLong("end", -1L), s.optLong("current"))
                }
                DownloadTask(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    fileName = o.getString("fileName"),
                    totalBytes = o.optLong("totalBytes", -1L),
                    downloadedBytes = if (segments.isEmpty()) o.optLong("downloadedBytes")
                    else segments.sumOf { it.current - it.start },
                    // 进程重启后进行中/排队中的任务一律回落为暂停,由用户手动恢复
                    status = if (rawStatus == DownloadStatus.RUNNING || rawStatus == DownloadStatus.PENDING)
                        DownloadStatus.PAUSED else rawStatus,
                    error = o.optString("error"),
                    mediaUri = o.optString("mediaUri"),
                    segments = segments,
                    validator = o.optString("validator"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取下载任务失败", e)
            emptyList()
        }
    }
}

/**
 * 全局限速器(简易令牌桶,按 1 秒窗口计量)
 *
 * 在锁内 delay 会同时挂住其他分段协程,这正是全局限速期望的效果。
 */
private class RateLimiter {
    private val mutex = Mutex()
    private var windowStart = 0L
    private var consumed = 0L

    suspend fun acquire(bytes: Int, limitBps: Long) {
        if (limitBps <= 0) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1000) {
                windowStart = now
                consumed = 0
            }
            consumed += bytes
            if (consumed >= limitBps) {
                val wait = 1000 - (now - windowStart)
                if (wait > 0) delay(wait)
                windowStart = System.currentTimeMillis()
                consumed = 0
            }
        }
    }
}

/** 文件大小格式化: B / KB / MB / GB */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
}
