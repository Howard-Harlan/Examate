package go.examate.hyp.meschat

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 消息持久化存储 — 每会话一个 JSONL 追加文件,保存在应用私有目录(filesDir/meschat_history)。
 *
 * 设计:
 * - **全量持久化**:所有收到的/发出的消息(频道/私聊/密钥匹对)按会话追加落盘,
 *   重启后聊天历史仍在;同步补拉仅从该存储中筛选最近 10 分钟窗口
 * - **容量控制**:单会话文件上限 50MB(超出裁掉最旧行),单条消息上限 2MB
 *   (超大附件行不入库,避免单行拖垮文件)
 * - 追加为异步单线程,读取同步(调用方放后台线程)
 */
class MessageStore(private val context: Context) {

    companion object {
        private const val TAG = "MessageStore"
        /** 单会话文件字节上限(超出裁掉最旧,保留最近一半) */
        private const val MAX_BYTES_PER_CONV = 500L * 1024 * 1024
        /** 单条消息字节上限(覆盖当前 80MB 的本地文件上限,大附件也能持久化) */
        private const val MAX_BYTES_PER_LINE = 100L * 1024 * 1024
    }

    private val dir = File(context.filesDir, "meschat_history")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meschat-store").apply { isDaemon = true }
    }

    init {
        runCatching { dir.mkdirs() }
    }

    private fun fileFor(convId: String): File =
        File(dir, encode(convId) + ".jsonl")

    /** 异步追加一条消息(线程安全,调用方可在任意线程) */
    fun append(convId: String, json: String) {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES_PER_LINE) return
        executor.execute {
            try {
                val f = fileFor(convId)
                f.appendText(json + "\n", Charsets.UTF_8)
                if (f.length() > MAX_BYTES_PER_CONV) trimToFit(f)
            } catch (e: Exception) {
                Log.e(TAG, "append failed: " + convId, e)
            }
        }
    }

    /** 超限时保留最近一半(从新到旧累计),裁掉最旧部分 */
    private fun trimToFit(f: File) {
        try {
            val lines = f.readLines()
            if (lines.size <= 1) return
            val keep = mutableListOf<String>()
            var total = 0L
            for (i in lines.indices.reversed()) {
                val size = (lines[i].length.toLong() + 1) * 2
                if (total + size > MAX_BYTES_PER_CONV / 2) break
                keep.add(lines[i])
                total += size
            }
            val body = if (keep.isEmpty()) "" else keep.reversed().joinToString("\n") + "\n"
            f.writeText(body, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "trimToFit failed", e)
        }
    }

    /**
     * 读取最近 [limit] 条(最旧在前、最新在后)。同步调用,放后台线程。
     * 流式读取 + 环形缓冲,内存占用与文件大小无关。
     */
    fun readRecent(convId: String, limit: Int): List<String> {
        val f = fileFor(convId)
        if (!f.exists()) return emptyList()
        return try {
            val ring = java.util.ArrayDeque<String>()
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    ring.addLast(line)
                    if (ring.size > limit) ring.removeFirst()
                    line = br.readLine()
                }
            }
            ring.toList()
        } catch (e: Exception) {
            Log.e(TAG, "readRecent failed: " + convId, e)
            emptyList()
        }
    }

    /**
     * 读取 timestamp ≥ sinceMs 的消息(最旧在前),累计字节不超过 maxBytes。
     * 用于同步补拉:只回放最近窗口内的消息。
     * 流式扫描 + 字节窗口裁剪,内存占用与文件大小无关。
     */
    fun readSince(convId: String, sinceMs: Long, maxBytes: Int): List<String> {
        val f = fileFor(convId)
        if (!f.exists()) return emptyList()
        return try {
            val out = java.util.ArrayDeque<String>()
            var bytes = 0
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    val size = (line.length + 1) * 2
                    val ts = runCatching { JSONObject(line).optLong("timestamp", 0L) }.getOrDefault(0L)
                    if (ts >= sinceMs) {
                        out.addLast(line)
                        bytes += size
                        while (bytes > maxBytes && out.isNotEmpty()) {
                            bytes -= (out.peekFirst().length + 1) * 2
                            out.removeFirst()
                        }
                    }
                    line = br.readLine()
                }
            }
            out.toList()
        } catch (e: Exception) {
            Log.e(TAG, "readSince failed: " + convId, e)
            emptyList()
        }
    }

    /**
     * 懒加载:读取 timestamp < beforeTs 的最近 [limit] 条(最旧在前)。
     * 流式扫描 + 环形缓冲,内存与文件大小无关。
     */
    fun readRange(convId: String, beforeTs: Long, limit: Int): List<String> {
        val f = fileFor(convId)
        if (!f.exists()) return emptyList()
        return try {
            val ring = java.util.ArrayDeque<String>()
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    val ts = runCatching { JSONObject(line).optLong("timestamp", 0L) }
                        .getOrDefault(0L)
                    if (ts > 0L && ts < beforeTs) {
                        ring.addLast(line)
                        if (ring.size > limit) ring.removeFirst()
                    }
                    line = br.readLine()
                }
            }
            ring.toList()
        } catch (e: Exception) {
            Log.e(TAG, "readRange failed: " + convId, e)
            emptyList()
        }
    }

    /**
     * 同步:读取 timestamp ≥ sinceMs 的最新 [limit] 条(最旧在前)。
     * 用于自动补拉"最近 N 条",避免一次全量拉取。
     */
    fun readNewestSince(convId: String, sinceMs: Long, limit: Int): List<String> {
        val f = fileFor(convId)
        if (!f.exists()) return emptyList()
        return try {
            val ring = java.util.ArrayDeque<String>()
            java.io.BufferedReader(java.io.FileReader(f)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    val ts = runCatching { JSONObject(line).optLong("timestamp", 0L) }
                        .getOrDefault(0L)
                    if (ts >= sinceMs) {
                        ring.addLast(line)
                        if (ring.size > limit) ring.removeFirst()
                    }
                    line = br.readLine()
                }
            }
            ring.toList()
        } catch (e: Exception) {
            Log.e(TAG, "readNewestSince failed: " + convId, e)
            emptyList()
        }
    }

    /** 列出所有已持久化的会话 ID */
    fun listConvIds(): List<String> =
        dir.listFiles()?.mapNotNull { decode(it.nameWithoutExtension) } ?: emptyList()

    private fun encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun decode(s: String): String? = runCatching {
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()
}
