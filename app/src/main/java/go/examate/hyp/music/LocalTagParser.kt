package go.examate.hyp.music

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 本地音乐内嵌元数据解析结果:
 *  - coverBytes:内嵌封面图原始字节(APIC/PIC frame 或 FLAC PICTURE block / Vorbis Comment 图)
 *  - lyricText:内嵌歌词文本(USLT frame 或 Vorbis Comment 的 LYRICS 字段)
 */
data class LocalTagData(
    val coverBytes: ByteArray? = null,
    val lyricText: String? = null
)

/**
 * 本地音乐内嵌封面/歌词解析器 — 遍历式解析,不假设元数据块的顺序、不按固定偏移量读取:
 *
 *  - MP3(ID3v2):解析 tag header 后遍历所有 frame(v2.2 PIC / v2.3+ APIC 取封面,
 *    USLT 取未同步歌词;v2.4 支持 frame 级 unsynchronisation)
 *  - FLAC:遍历所有 metadata block(不假设 VORBIS_COMMENT / PICTURE 的先后),
 *    VORBIS_COMMENT 块内遍历全部 comment 条目(LYRICS / UNSYNCEDLYRICS 等),PICTURE 块取封面
 *  - OGG(Vorbis/Opus):遍历 Ogg 页拼接 comment packet,解析 vorbis comment / OpusTags
 *  - M4A/MP4(AAC):遍历顶层 atom 定位 moov→udta→meta→ilst,取 covr 封面与 ©lyr 歌词
 *  - 其余格式(wav 等)返回空,由调用方回退 MediaMetadataRetriever
 *
 * 文本编码:ID3 按 frame 的 encoding 字段(ISO-8859-1 / UTF-16 / UTF-16BE / UTF-8);
 * Vorbis Comment 按 UTF-8。
 */
object LocalTagParser {
    private const val TAG = "LocalTagParser"

    /** 从文件路径解析 */
    fun readFromPath(path: String): LocalTagData {
        val file = File(path)
        if (!file.exists() || !file.isFile) return LocalTagData()
        return try {
            FileInputStream(file).use { readFromStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "readFromPath fail: $path", e)
            LocalTagData()
        }
    }

    /** 从任意输入流解析(content URI 等);流会被消费但不关闭 */
    fun readFromStream(input: InputStream): LocalTagData {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        val magic = peek(buffered, 12) ?: return LocalTagData()
        return try {
            when {
                magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() ->
                    parseId3(buffered)
                magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                    magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte() ->
                    parseFlac(buffered)
                magic[0] == 'O'.code.toByte() && magic[1] == 'g'.code.toByte() &&
                    magic[2] == 'g'.code.toByte() && magic[3] == 'S'.code.toByte() ->
                    parseOgg(buffered)
                // MP4 家族(m4a/mp4/aac):偏移 4..8 为 'ftyp'
                magic.size >= 8 && magic[4] == 'f'.code.toByte() && magic[5] == 't'.code.toByte() &&
                    magic[6] == 'y'.code.toByte() && magic[7] == 'p'.code.toByte() ->
                    parseMp4(buffered)
                else -> LocalTagData()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFromStream fail", e)
            LocalTagData()
        }
    }

    // ════════════════════════════════════════════
    //  ID3v2 — 遍历所有 frame
    // ════════════════════════════════════════════

    private fun parseId3(input: InputStream): LocalTagData {
        skip(input, 3) // "ID3"
        val version = readByte(input) ?: return LocalTagData()          // 主版本 2/3/4
        skip(input, 1)                                                  // 次版本
        val flags = readByte(input) ?: return LocalTagData()
        val tagSize = readSynchsafe32(input)   // 标签体大小(不含 10 字节 header)

        var cover: ByteArray? = null
        var lyric: String? = null

        if (tagSize > 0) {
            val body = readBytes(input, tagSize) ?: return LocalTagData()
            // tag 级 unsynchronisation(仅 v2.3 语义):整个 body 中 FF 00 → FF
            val bodyData = if (version == 3 && flags and 0x80 != 0) deUnsync(body) else body
            val frames = parseId3Frames(bodyData, version, flags)
            for (frame in frames) {
                val (frameId, data) = frame
                when (frameId) {
                    // v2.3/v2.4:APIC 封面
                    "APIC" -> if (cover == null) cover = parseApic(data)
                    // v2.2:PIC 封面
                    "PIC" -> if (cover == null) cover = parsePicV22(data)
                    // 未同步歌词
                    "USLT" -> if (lyric == null) lyric = parseUslt(data)
                    else -> {}
                }
            }
        }
        return LocalTagData(cover, lyric)
    }

    /** 解析 ID3 frame 列表,返回 (frameId, data);忽略 extended header / footer / padding */
    private fun parseId3Frames(body: ByteArray, version: Int, tagFlags: Int): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var pos = 0
        val headerLen = if (version == 2) 6 else 10

        // extended header(v2.3/2.4):跳过
        if (version >= 3 && tagFlags and 0x40 != 0) {
            if (version == 4) {
                // v2.4:exthdr size 为 synchsafe,含自身 4 字节
                val extSize = readSynchsafeAt(body, pos)
                pos += extSize.coerceAtLeast(4)
            } else {
                // v2.3:size 为普通 32 位,不含自身 4 字节(后面还有 2 字节 flags + 4 字节 padding)
                val extSize = readInt32BEAt(body, pos)
                pos += 4 + extSize.coerceAtLeast(0)
            }
        }

        while (pos + headerLen <= body.size) {
            // padding:全 0 停止
            if (body[pos] == 0.toByte()) break
            val idBytes = body.copyOfRange(pos, pos + if (version == 2) 3 else 4)
            val id = String(idBytes, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            // 帧 id 必须是字母/数字,否则视为进入垃圾区停止(容错非标准 tag)
            if (!id.all { it.isLetterOrDigit() }) break
            val sizePos = pos + (if (version == 2) 3 else 4)
            var size = if (version == 4) readSynchsafeAt(body, sizePos) else readInt32BEAt(body, sizePos)
            // v2.4 容错:不少 tagger 写的是普通 32 位尺寸而非 synchsafe —
            // 若按 synchsafe 推进后下一帧不像帧头,回退普通尺寸重读
            if (version == 4 && !frameBoundaryLooksValid(body, pos + headerLen + size)) {
                val plain = readInt32BEAt(body, sizePos)
                if (plain != size && frameBoundaryLooksValid(body, pos + headerLen + plain)) size = plain
            }
            val frameFlags = if (version >= 3 && pos + 10 <= body.size)
                ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF) else 0
            val dataStart = pos + headerLen
            val dataEnd = (dataStart + size).coerceAtMost(body.size)
            if (dataEnd <= dataStart) break
            var data = body.copyOfRange(dataStart, dataEnd)
            // v2.4 frame 级 unsynchronisation(format flag bit 1)
            if (version == 4 && frameFlags and 0x02 != 0) data = deUnsync(data)
            result.add(id to data)
            pos = dataEnd
        }
        return result
    }

    /** 帧边界合法性判定:边界恰在末尾,或边界处是字母/数字(下一帧 id)/ 0(padding) */
    private fun frameBoundaryLooksValid(body: ByteArray, boundary: Int): Boolean {
        if (boundary == body.size) return true
        if (boundary > body.size) return false
        val c = body[boundary].toInt().toChar()
        return c.isLetterOrDigit() || c == '\u0000'
    }

    /**
     * 公开接口:读取 ID3v2 标签的帧列表(供 LocalTagWriter 重写标签时保留未编辑帧)。
     * @return (主版本, 标签 flags, 帧列表);非 ID3v2 文件返回 null
     */
    fun readId3Frames(input: InputStream): Triple<Int, Int, List<Pair<String, ByteArray>>>? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        val magic = peek(buffered, 10) ?: return null
        if (!(magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte())) return null
        return try {
            skip(buffered, 3)
            val version = readByte(buffered) ?: return null
            skip(buffered, 1)
            val flags = readByte(buffered) ?: return null
            val tagSize = readSynchsafe32(buffered)
            if (tagSize <= 0) return Triple(version, flags, emptyList())
            val body = readBytes(buffered, tagSize) ?: return null
            val bodyData = if (version == 3 && flags and 0x80 != 0) deUnsync(body) else body
            Triple(version, flags, parseId3Frames(bodyData, version, flags))
        } catch (e: Exception) {
            Log.w(TAG, "readId3Frames fail", e)
            null
        }
    }

    /** APIC(v2.3/2.4):encoding(1) + MIME(0结尾) + type(1) + desc(0结尾,按encoding) + 图片数据 */
    private fun parseApic(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        var pos = 1
        // MIME 以 0x00 结尾
        val mimeEnd = indexOfZero(data, pos, latin1 = true) ?: return null
        pos = mimeEnd + 1
        if (pos >= data.size) return null
        pos++ // picture type
        // description 以 0x00(单字节)或 0x00 0x00(UTF-16)结尾
        val descEnd = indexOfZero(data, pos, latin1 = encoding != 1) ?: return null
        pos = descEnd + if (encoding == 1) 2 else 1
        if (pos >= data.size) return null
        return data.copyOfRange(pos, data.size)
    }

    /** PIC(v2.2):encoding(1) + 图片格式(3字节) + type(1) + desc(0结尾) + 图片数据 */
    private fun parsePicV22(data: ByteArray): ByteArray? {
        if (data.size < 6) return null
        val encoding = data[0].toInt() and 0xFF
        var pos = 1 + 3 // 格式(如 "PNG"/"JPG")
        pos++ // picture type
        val descEnd = indexOfZero(data, pos, latin1 = encoding != 1) ?: return null
        pos = descEnd + if (encoding == 1) 2 else 1
        if (pos >= data.size) return null
        return data.copyOfRange(pos, data.size)
    }

    /** USLT:encoding(1) + language(3) + desc(0结尾) + 歌词文本 */
    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        var pos = 1 + 3 // language
        val descEnd = indexOfZero(data, pos, latin1 = encoding != 1) ?: return null
        pos = descEnd + if (encoding == 1) 2 else 1
        if (pos >= data.size) return null
        return decodeText(data.copyOfRange(pos, data.size), encoding)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** ID3 文本解码:0=ISO-8859-1 1=UTF-16(带BOM) 2=UTF-16BE 3=UTF-8 */
    private fun decodeText(bytes: ByteArray, encoding: Int): String? {
        return try {
            when (encoding) {
                0 -> String(bytes, Charsets.ISO_8859_1)
                1 -> decodeUtf16(bytes, hasBom = true)
                2 -> decodeUtf16(bytes, hasBom = false)
                3 -> String(bytes, Charsets.UTF_8)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun decodeUtf16(bytes: ByteArray, hasBom: Boolean): String? {
        var start = 0
        var charset = Charsets.UTF_16BE
        if (hasBom && bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            when {
                b0 == 0xFF && b1 == 0xFE -> { charset = Charsets.UTF_16LE; start = 2 }
                b0 == 0xFE && b1 == 0xFF -> { charset = Charsets.UTF_16BE; start = 2 }
                else -> {}
            }
        }
        return String(bytes, start, bytes.size - start, charset)
    }

    // ════════════════════════════════════════════
    //  FLAC — 遍历所有 metadata block
    // ════════════════════════════════════════════

    private fun parseFlac(input: InputStream): LocalTagData {
        skip(input, 4) // "fLaC"
        var cover: ByteArray? = null
        var lyric: String? = null
        var last = false
        // 保护上限:封面一般不大,超 32MB 放弃(防恶意文件)
        var total = 0L
        while (!last) {
            val header = readByte(input) ?: break
            last = header and 0x80 != 0
            // 官方结构:1 bit last + 7 bits BLOCK_TYPE + 24 bits length(大端)
            val type = header and 0x7F
            val length = readInt24(input) ?: break
            total += length
            if (total > 32L * 1024 * 1024) break
            when (type) {
                4 -> { // VORBIS_COMMENT
                    val block = readBytes(input, length) ?: break
                    if (lyric == null) lyric = parseVorbisCommentBlock(block)
                }
                6 -> { // PICTURE
                    if (cover == null) {
                        val block = readBytes(input, length) ?: break
                        cover = parsePictureBlock(block)
                    } else skip(input, length)
                }
                else -> {
                    if (!skip(input, length)) break
                }
            }
        }
        return LocalTagData(cover, lyric)
    }

    /** Vorbis Comment 块(vendor + 遍历全部 comment):找 LYRICS / UNSYNCEDLYRICS 等 */
    private fun parseVorbisCommentBlock(block: ByteArray): String? {
        var pos = 0
        val vendorLen = readUint32LEAt(block, pos) ?: return null
        pos += 4 + vendorLen
        if (pos > block.size) return null
        val count = readUint32LEAt(block, pos) ?: return null
        pos += 4
        var lyric: String? = null
        repeat(count) {
            if (pos + 4 > block.size) return@repeat
            val len = readUint32LEAt(block, pos) ?: return@repeat
            pos += 4
            if (len > 0 && pos + len <= block.size) {
                val entry = String(block, pos, len, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).uppercase()
                    val value = entry.substring(eq + 1)
                    // 歌词常用标签:LYRICS / UNSYNCEDLYRICS / UNSYNCED LYRICS
                    if (lyric == null && key in LYRICS_KEYS && value.isNotBlank()) {
                        lyric = value
                    }
                }
                pos += len
            }
        }
        return lyric
    }

    private val LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC", "LYRICSUSLT")

    /** FLAC METADATA_BLOCK_PICTURE:type(4) + mimeLen(4) + mime + descLen(4) + desc + w/h/depth/colors + dataLen(4) + data */
    private fun parsePictureBlock(block: ByteArray): ByteArray? {
        var pos = 0
        val type = readUint32BEAt(block, pos) ?: return null
        pos += 4
        val mimeLen = readUint32BEAt(block, pos) ?: return null
        pos += 4
        pos += mimeLen
        val descLen = readUint32BEAt(block, pos) ?: return null
        pos += 4
        pos += descLen
        pos += 4 + 4 + 4 + 4 // width/height/depth/colors
        val dataLen = readUint32BEAt(block, pos) ?: return null
        pos += 4
        if (dataLen > 0 && pos + dataLen <= block.size) {
            return block.copyOfRange(pos, pos + dataLen)
        }
        return null
    }

    // ════════════════════════════════════════════
    //  OGG — 遍历页取 comment packet(Vorbis \x03vorbis / Opus OpusTags)
    // ════════════════════════════════════════════

    private val VORBIS_MAGIC = byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    private val OPUSTAGS_MAGIC = "OpusTags".toByteArray(Charsets.ISO_8859_1)

    private fun parseOgg(input: InputStream): LocalTagData {
        var lyric: String? = null
        // 最多读前 8 页(comment 通常在 identification 之后的 1~2 页内)
        for (page in 0 until 8) {
            val header = readBytes(input, 27) ?: break
            if (header.size < 27) break
            if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
            ) break
            val segments = header[26].toInt() and 0xFF
            val segTable = readBytes(input, segments) ?: break
            var packetLen = 0
            var continueFlag = false
            for (i in 0 until segments) {
                val s = segTable[i].toInt() and 0xFF
                packetLen += s
                if (s < 255) continueFlag = true
            }
            val packet = readBytes(input, packetLen) ?: break
            // comment packet 判定:以 \x03vorbis 或 OpusTags 开头
            if (packet.size >= 7 && (packet.startsWith(VORBIS_MAGIC) || packet.startsWith(OPUSTAGS_MAGIC))) {
                val parsed = parseOggCommentPacket(packet)
                if (parsed != null) {
                    lyric = parsed
                    break
                }
            }
            if (!continueFlag && page > 0) break
        }
        return LocalTagData(null, lyric)
    }

    /** OGG comment packet:vendor(4 LE) + vendor + count(4 LE) + comments;OpusTags 同构 */
    private fun parseOggCommentPacket(packet: ByteArray): String? {
        var pos = 0
        // 跳过 vorbis 头(7 字节:类型+版本+channels+rate 等)或 OpusTags(8 字节)
        pos = if (packet.startsWith(OPUSTAGS_MAGIC)) 8
        else if (packet.size >= 7 && packet[0] == 3.toByte()) 7 else 0
        if (pos + 4 > packet.size) return null
        val vendorLen = readUint32LEAt(packet, pos) ?: return null
        pos += 4 + vendorLen
        if (pos > packet.size) return null
        val count = readUint32LEAt(packet, pos) ?: return null
        pos += 4
        var lyric: String? = null
        repeat(count) {
            if (pos + 4 > packet.size) return@repeat
            val len = readUint32LEAt(packet, pos) ?: return@repeat
            pos += 4
            if (len > 0 && pos + len <= packet.size) {
                val entry = String(packet, pos, len, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).uppercase()
                    val value = entry.substring(eq + 1)
                    if (lyric == null && key in LYRICS_KEYS && value.isNotBlank()) lyric = value
                }
                pos += len
            }
        }
        return lyric
    }

    // ════════════════════════════════════════════
    //  MP4/M4A — 遍历顶层 atom → moov→udta→meta→ilst(covr / ©lyr)
    // ════════════════════════════════════════════

    /** 顶层 atom 扫描上限(防超大文件恶意扫描);moov 可能在文件尾部,需越过 mdat */
    private const val MP4_SCAN_LIMIT = 512L * 1024 * 1024

    /** 容器 atom 读入内存的上限(超出则跳过,防恶意文件) */
    private const val MP4_CONTAINER_LIMIT = 24 * 1024 * 1024

    private fun parseMp4(input: InputStream): LocalTagData {
        var cover: ByteArray? = null
        var lyric: String? = null
        var scanned = 0L
        while (scanned < MP4_SCAN_LIMIT && (cover == null || lyric == null)) {
            val header = readBytes(input, 8) ?: break
            if (header.size < 8) break
            var size = readUint32BEAt(header, 0)?.toLong() ?: break
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            if (size == 1L) {
                // 64 位 largesize
                val ext = readBytes(input, 8) ?: break
                size = readUint64BE(ext)
                headerLen = 16
            }
            if (size < headerLen) break
            val bodyLen = (size - headerLen).toInt()
            scanned += size
            when (type) {
                "moov" -> {
                    if (bodyLen in 0..MP4_CONTAINER_LIMIT) {
                        val body = readBytes(input, bodyLen) ?: break
                        val found = parseMp4Container(body, 0, body.size, depth = 0)
                        if (cover == null) cover = found.coverBytes
                        if (lyric == null) lyric = found.lyricText
                    } else if (!skip(input, bodyLen)) break
                }
                else -> if (!skip(input, bodyLen)) break
            }
        }
        return LocalTagData(cover, lyric)
    }

    /** 递归解析 MP4 容器 atom(moov/udta/meta/ilst);meta 为 FullBox 需跳过 4 字节版本 */
    private fun parseMp4Container(data: ByteArray, start: Int, end: Int, depth: Int): LocalTagData {
        var cover: ByteArray? = null
        var lyric: String? = null
        var pos = start
        while (pos + 8 <= end) {
            val size = readUint32BEAt(data, pos) ?: break
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || pos + size > end) break
            val bodyStart = pos + 8
            val bodyEnd = pos + size
            when (type) {
                "moov", "udta", "ilst" -> {
                    if (depth < 4) {
                        val r = parseMp4Container(data, bodyStart, bodyEnd, depth + 1)
                        if (cover == null) cover = r.coverBytes
                        if (lyric == null) lyric = r.lyricText
                    }
                }
                "meta" -> {
                    // FullBox:4 字节 version/flags 后才是子 atom
                    if (depth < 4 && bodyStart + 4 <= bodyEnd) {
                        val r = parseMp4Container(data, bodyStart + 4, bodyEnd, depth + 1)
                        if (cover == null) cover = r.coverBytes
                        if (lyric == null) lyric = r.lyricText
                    }
                }
                "covr" -> if (cover == null) cover = parseMp4IlstItem(data, bodyStart, bodyEnd)
                "©lyr", "\u00A9lyr" -> if (lyric == null) {
                    val bytes = parseMp4IlstItem(data, bodyStart, bodyEnd)
                    if (bytes != null) lyric = readLrcText(bytes)
                }
            }
            if (cover != null && lyric != null) break
            pos = bodyEnd
        }
        return LocalTagData(cover, lyric)
    }

    /** ilst 条目(covr/©lyr)内部找 'data' 子 atom,取其 payload(跳过 8 字节类型/区域头) */
    private fun parseMp4IlstItem(data: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUint32BEAt(data, pos) ?: break
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || pos + size > end) break
            if (type == "data") {
                val payloadStart = pos + 8 + 8   // 跳过 4 字节类型指示 + 4 字节区域
                val payloadEnd = pos + size
                if (payloadStart < payloadEnd) return data.copyOfRange(payloadStart, payloadEnd)
                return null
            }
            pos += size
        }
        return null
    }

    /** 64 位大端无符号整数(largesize) */
    private fun readUint64BE(data: ByteArray): Long {
        if (data.size < 8) return 0L
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v
    }

    /** LRC 文本字节 → 字符串:优先 UTF-8,乱码时回退 GBK(国内抓的 lrc 常见) */
    private fun readLrcText(bytes: ByteArray): String? {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (!utf8.contains('\uFFFD')) utf8.trim().takeIf { it.isNotBlank() }
        else runCatching { String(bytes, charset("GBK")) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ════════════════════════════════════════════
    //  字节工具
    // ════════════════════════════════════════════

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** 从流中读 count 字节(不足返回 null) */
    private fun readBytes(input: InputStream, count: Int): ByteArray? {
        if (count < 0) return null
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) return if (read == 0) null else out.copyOf(read)
            read += n
        }
        return out
    }

    private fun readByte(input: InputStream): Int? {
        val b = input.read()
        return if (b < 0) null else b and 0xFF
    }

    /** 跳过 n 字节;EOF 返回 false */
    private fun skip(input: InputStream, n: Int): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining--
            } else remaining -= skipped.toInt()
        }
        return true
    }

    /** peek 前 n 字节(不消费),不足返回 null */
    private fun peek(input: BufferedInputStream, n: Int): ByteArray? {
        input.mark(n + 1)
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        input.reset()
        return if (read == n) out else null
    }

    private fun readSynchsafe32(input: InputStream): Int {
        return ((readByte(input) ?: 0) shl 21) or
            ((readByte(input) ?: 0) shl 14) or
            ((readByte(input) ?: 0) shl 7) or
            (readByte(input) ?: 0)
    }

    private fun readSynchsafeAt(data: ByteArray, pos: Int): Int {
        if (pos + 4 > data.size) return 0
        return ((data[pos].toInt() and 0x7F) shl 21) or
            ((data[pos + 1].toInt() and 0x7F) shl 14) or
            ((data[pos + 2].toInt() and 0x7F) shl 7) or
            (data[pos + 3].toInt() and 0x7F)
    }

    private fun readInt32BEAt(data: ByteArray, pos: Int): Int {
        if (pos + 4 > data.size) return 0
        return ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
    }

    private fun readInt24(input: InputStream): Int? {
        val b0 = readByte(input) ?: return null
        val b1 = readByte(input) ?: return null
        val b2 = readByte(input) ?: return null
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readUint32LEAt(data: ByteArray, pos: Int): Int? {
        if (pos + 4 > data.size) return null
        return (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUint32BEAt(data: ByteArray, pos: Int): Int? {
        if (pos + 4 > data.size) return null
        return ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
    }

    /** 找第一个 0x00(UTF-16 场景忽略高字节判定,由调用方决定步长) */
    private fun indexOfZero(data: ByteArray, start: Int, latin1: Boolean): Int? {
        if (latin1) {
            for (i in start until data.size) if (data[i] == 0.toByte()) return i
            return null
        }
        // UTF-16:找 00 00(小端 0x0000 编码为 00 00)或 FE FF FF FE 后的 00 00
        for (i in start until data.size - 1) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
        }
        return null
    }

    /** ID3v2.3 tag 级 unsync:FF 00 → FF */
    private fun deUnsync(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF && i + 1 < data.size && (data[i + 1].toInt() and 0xFF) == 0x00) {
                i++ // 跳过后面的 00
            }
            i++
        }
        return out.toByteArray()
    }
}
