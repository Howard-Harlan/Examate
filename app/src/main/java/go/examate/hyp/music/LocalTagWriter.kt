package go.examate.hyp.music

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 本地音乐标签写入器 — 支持 MP3(ID3v2.3):
 * 标题(TIT2)/ 艺术家(TPE1)/ 专辑(TALB)/ 封面(APIC)/ 歌词(USLT)。
 *
 * 实现:读取现有帧 → 保留未编辑帧、替换编辑帧 → 组装新 tag →
 * 流式重写(新 tag + 原音频数据,跳过旧 tag)到临时文件 → 写回文档(SAF content:// 安全)。
 * FLAC/M4A/OGG 暂不支持写入(容器改写复杂度高),返回 false 由 UI 提示。
 */
object LocalTagWriter {
    private const val TAG = "LocalTagWriter"
    private const val PADDING = 1024   // 预留 padding,后续小改不用整文件重写

    /** 编辑补丁:null 字段表示不修改;coverBytes 非空时替换封面 */
    data class Patch(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg",
        val lyricText: String? = null
    )

    /** 是否支持写入(仅 MP3:ID3v2 头或 MPEG 帧同步头) */
    fun isSupported(context: Context, uri: Uri): Boolean {
        val head = readHead(context, uri, 4) ?: return false
        return isMp3Magic(head)
    }

    /** 写入补丁;成功返回 true */
    fun write(context: Context, uri: Uri, patch: Patch): Boolean {
        val resolver = context.contentResolver
        val head = readHead(context, uri, 10) ?: return false
        if (!isMp3Magic(head)) {
            Log.w(TAG, "write: 非 MP3,不支持写入")
            return false
        }
        val hasId3 = head.size >= 3 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        val oldTagSize = if (hasId3) 10 + synchsafe(head[6], head[7], head[8], head[9]) else 0

        // 1. 现有帧(保留未编辑的)
        val existing: List<Pair<String, ByteArray>> = if (hasId3) {
            runCatching {
                resolver.openInputStream(uri)?.use { LocalTagParser.readId3Frames(it)?.third }
            }.getOrNull().orEmpty()
        } else emptyList()

        // 2. 组装新帧:替换编辑项,保留其他
        val frames = LinkedHashMap<String, ByteArray>()
        for ((id, data) in existing) {
            if (id in listOf("TIT2", "TPE1", "TALB", "APIC", "USLT")) continue
            frames[id] = data
        }
        patch.title?.let { frames["TIT2"] = textFrame(it) }
        patch.artist?.let { frames["TPE1"] = textFrame(it) }
        patch.album?.let { frames["TALB"] = textFrame(it) }
        patch.coverBytes?.let { frames["APIC"] = apicFrame(it, patch.coverMime) }
        patch.lyricText?.let { frames["USLT"] = usltFrame(it) }

        // 3. 标签体(帧 + padding)
        val body = ByteArrayOutputStream()
        for ((id, data) in frames) {
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(int32BE(data.size))
            body.write(byteArrayOf(0, 0))   // v2.3 flags
            body.write(data)
        }
        body.write(ByteArray(PADDING))
        val bodyBytes = body.toByteArray()

        // 4. tag 头(ID3v2.3)
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(3, 0, 0))
            write(synchsafeBytes(bodyBytes.size))
        }.toByteArray()

        // 5. 流式重写到临时文件:新 tag + 原音频数据(跳过旧 tag)
        val temp = File(context.cacheDir, "tag_edit_${System.currentTimeMillis()}.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out ->
                    out.write(header)
                    out.write(bodyBytes)
                    if (oldTagSize > 0) skipFully(input, oldTagSize.toLong())
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: return false

            // 6. 写回文档(openOutputStream 默认截断)
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out, bufferSize = 64 * 1024) }
            } ?: return false
            Log.d(TAG, "write OK: frames=${frames.keys}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write fail", e)
            return false
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ── 帧编码 ──

    /** 文本帧:encoding=UTF-16(带 BOM),v2.3 兼容性最好 */
    private fun textFrame(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(0xFF); out.write(0xFE)
        out.write(text.toByteArray(Charsets.UTF_16LE))
        return out.toByteArray()
    }

    /** APIC:encoding(0 latin1)+ MIME\0 + type(3 封面)+ desc\0 + 图片字节 */
    private fun apicFrame(image: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.write(mime.toByteArray(Charsets.ISO_8859_1)); out.write(0x00)
        out.write(0x03)
        out.write(0x00)   // 空描述
        out.write(image)
        return out.toByteArray()
    }

    /** USLT:encoding(1 UTF-16)+ lang("und")+ desc(UTF-16 空)+ 歌词 */
    private fun usltFrame(lyric: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write("und".toByteArray(Charsets.ISO_8859_1))
        out.write(0x00); out.write(0x00)   // UTF-16 空描述
        out.write(0xFF); out.write(0xFE)
        out.write(lyric.toByteArray(Charsets.UTF_16LE))
        return out.toByteArray()
    }

    // ── 工具 ──

    private fun isMp3Magic(head: ByteArray): Boolean {
        if (head.size < 3) return false
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return true
        // MPEG 帧同步:0xFF + 高 3 位为 111
        return head.size >= 2 &&
            head[0].toInt() and 0xFF == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
    }

    private fun readHead(context: Context, uri: Uri, n: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = s.read(b, read, n - read)
                if (r < 0) break
                read += r
            }
            if (read >= 2) b.copyOf(read) else null
        }
    }.getOrNull()

    private fun synchsafe(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0x7F) shl 21) or ((b1.toInt() and 0x7F) shl 14) or
            ((b2.toInt() and 0x7F) shl 7) or (b3.toInt() and 0x7F)

    private fun synchsafeBytes(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte()
    )

    private fun int32BE(v: Int): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else remaining -= skipped
        }
    }
}
