package go.examate.hyp.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * LocalTagParser 单元测试 — 验证遍历式解析(不假设元数据块顺序):
 *  - ID3v2.3/2.4:APIC 封面 + USLT 歌词,帧顺序打乱、中间插其他帧
 *  - FLAC:VORBIS_COMMENT / PICTURE block 顺序打乱
 *  - 编码:ISO-8859-1 / UTF-16 / UTF-8
 */
class LocalTagParserTest {

    // ═══════════════ ID3v2.3 ═══════════════

    private fun id3Header(version: Int, flags: Int, size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(version)
        out.write(0)
        out.write(flags)
        // synchsafe 32
        out.write((size shr 21) and 0x7F)
        out.write((size shr 14) and 0x7F)
        out.write((size shr 7) and 0x7F)
        out.write(size and 0x7F)
        return out.toByteArray()
    }

    private fun id3FrameV23(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write((data.size shr 24) and 0xFF)
        out.write((data.size shr 16) and 0xFF)
        out.write((data.size shr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(0); out.write(0) // frame flags
        out.write(data)
        return out.toByteArray()
    }

    private fun apicData(mime: String, pictureBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // encoding: ISO-8859-1
        out.write(mime.toByteArray()); out.write(0)
        out.write(3) // picture type: cover(front)
        out.write("desc".toByteArray()); out.write(0)
        out.write(pictureBytes)
        return out.toByteArray()
    }

    private fun usltData(lyric: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(3) // encoding: UTF-8
        out.write("chi".toByteArray()) // language
        out.write(0) // descriptor end
        out.write(lyric.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    @Test
    fun `ID3v23 - 遍历 APIC 与 USLT,帧顺序无关`() {
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02, 0x03)
        val lyric = "[00:01.00]第一行\n[00:02.00]第二行"

        // 故意把 USLT 放在 APIC 前面,中间插 TIT2,验证遍历不依赖顺序
        val frames = ByteArrayOutputStream().apply {
            write(id3FrameV23("USLT", usltData(lyric)))
            write(id3FrameV23("TIT2", byteArrayOf(0, 'x'.code.toByte())))
            write(id3FrameV23("APIC", apicData("image/png", cover)))
        }.toByteArray()
        val file = ByteArrayOutputStream().apply {
            write(id3Header(3, 0, frames.size))
            write(frames)
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
        assertEquals(lyric, result.lyricText)
    }

    @Test
    fun `ID3v23 - tag级 unsynchronisation 恢复 FF 00`() {
        val cover = byteArrayOf(0xFF.toByte(), 0x00, 0xAA.toByte(), 0xFF.toByte(), 0x00, 0xBB.toByte())
        val apic = apicData("image/jpeg", cover)
        // 对 apic 数据做 unsync:FF 00 → FF 00(即每 FF 后插 00)
        val unsyncedApic = ByteArrayOutputStream().apply {
            var i = 0
            while (i < apic.size) {
                val b = apic[i]
                write(b.toInt())
                if ((b.toInt() and 0xFF) == 0xFF && i + 1 < apic.size && (apic[i + 1].toInt() and 0xFF) == 0x00) {
                    write(0)
                }
                i++
            }
        }.toByteArray()

        val frames = id3FrameV23("APIC", unsyncedApic)
        val file = ByteArrayOutputStream().apply {
            write(id3Header(3, 0x80, frames.size)) // unsync flag
            write(frames)
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
    }

    @Test
    fun `ID3v24 - frame级 unsynchronisation 与 synchsafe size`() {
        val cover = byteArrayOf(0xFF.toByte(), 0x00, 0x01, 0x02)
        val apicRaw = apicData("image/png", cover)
        // frame 级 unsync:数据中 FF 00 → FF 00
        val apicUnsynced = ByteArrayOutputStream().apply {
            var i = 0
            while (i < apicRaw.size) {
                val b = apicRaw[i]
                write(b.toInt())
                if ((b.toInt() and 0xFF) == 0xFF && i + 1 < apicRaw.size && (apicRaw[i + 1].toInt() and 0xFF) == 0x00) {
                    write(0)
                }
                i++
            }
        }.toByteArray()

        // v2.4:size 为 synchsafe;flags bit1 = unsync
        val out = ByteArrayOutputStream()
        out.write("APIC".toByteArray())
        out.write((apicUnsynced.size shr 21) and 0x7F)
        out.write((apicUnsynced.size shr 14) and 0x7F)
        out.write((apicUnsynced.size shr 7) and 0x7F)
        out.write(apicUnsynced.size and 0x7F)
        out.write(0); out.write(0x02) // format flag: unsync
        out.write(apicUnsynced)
        val frames = out.toByteArray()

        val file = ByteArrayOutputStream().apply {
            write(id3Header(4, 0, frames.size))
            write(frames)
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
    }

    @Test
    fun `ID3v22 - PIC 帧解析`() {
        val cover = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val pic = ByteArrayOutputStream().apply {
            write(0) // encoding
            write("PNG".toByteArray()) // 3 字节格式
            write(3) // type
            write("d".toByteArray()); write(0)
            write(cover)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("PIC".toByteArray())
        out.write((pic.size shr 16) and 0xFF)
        out.write((pic.size shr 8) and 0xFF)
        out.write(pic.size and 0xFF)
        out.write(pic)
        val frames = out.toByteArray()
        val file = ByteArrayOutputStream().apply {
            write(id3Header(2, 0, frames.size))
            write(frames)
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
    }

    @Test
    fun `ID3v23 - UTF16 歌词解码`() {
        val lyric = "[00:01.00]你好世界"
        val lyricBytes = ("\uFEFF".toByteArray(Charsets.UTF_16BE)) // BOM
            .plus(lyric.toByteArray(Charsets.UTF_16BE))
        val out = ByteArrayOutputStream()
        out.write(1) // encoding: UTF-16 with BOM
        out.write("chi".toByteArray())
        out.write(0); out.write(0) // descriptor end (UTF-16 00 00)
        out.write(lyricBytes)
        val data = out.toByteArray()
        val frames = id3FrameV23("USLT", data)
        val file = ByteArrayOutputStream().apply {
            write(id3Header(3, 0, frames.size))
            write(frames)
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertEquals(lyric, result.lyricText)
    }

    // ═══════════════ FLAC ═══════════════

    private fun flacBlock(type: Int, last: Boolean, data: ByteArray): ByteArray {
        // 官方结构:header 4 字节 = last(1bit) + type(7bit) + length(24bit 大端)
        val out = ByteArrayOutputStream()
        out.write((if (last) 0x80 else 0) or (type and 0x7F))
        out.write((data.size shr 16) and 0xFF)
        out.write((data.size shr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun vorbisCommentBlock(vararg comments: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0); out.write(0) // vendor len 0
        out.write(comments.size and 0xFF)
        out.write((comments.size shr 8) and 0xFF)
        out.write((comments.size shr 16) and 0xFF)
        out.write((comments.size shr 24) and 0xFF)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            out.write(bytes.size and 0xFF)
            out.write((bytes.size shr 8) and 0xFF)
            out.write((bytes.size shr 16) and 0xFF)
            out.write((bytes.size shr 24) and 0xFF)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun pictureBlock(mime: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
            out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
        }
        u32(3) // picture type
        u32(mime.length); out.write(mime.toByteArray())
        u32(0) // desc len
        u32(0) // width
        u32(0) // height
        u32(0) // depth
        u32(0) // colors
        u32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `FLAC - PICTURE 在 VORBIS_COMMENT 之前,遍历全部块`() {
        val cover = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val lyric = "[00:01.00]你好\n[00:02.00]世界"
        val streamInfo = ByteArray(34) { 0 }
        val file = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            // 故意:PICTURE 在 VORBIS_COMMENT 之前,中间插 STREAMINFO/PADDING
            write(flacBlock(0, false, streamInfo))
            write(flacBlock(6, false, pictureBlock("image/png", cover)))
            write(flacBlock(1, false, ByteArray(8) { 0xFF.toByte() })) // PADDING
            write(flacBlock(4, true, vorbisCommentBlock(
                "TITLE=测试歌曲", "LYRICS=$lyric"
            )))
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
        assertEquals(lyric, result.lyricText)
    }

    @Test
    fun `FLAC - VORBIS_COMMENT 在 PICTURE 之前,且含其它 comment`() {
        val cover = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val lyric = "[00:10.00]only line"
        val file = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(flacBlock(0, false, ByteArray(34) { 0 }))
            write(flacBlock(4, false, vorbisCommentBlock(
                "ARTIST=张三", "ALBUM=专辑", "UNSYNCEDLYRICS=$lyric"
            )))
            write(flacBlock(6, true, pictureBlock("image/jpeg", cover)))
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
        assertEquals(lyric, result.lyricText)
    }

    @Test
    fun `FLAC - 无歌词时只取封面`() {
        val cover = byteArrayOf(0x01)
        val file = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(flacBlock(0, false, ByteArray(34) { 0 }))
            write(flacBlock(6, true, pictureBlock("image/png", cover)))
        }.toByteArray()

        val result = LocalTagParser.readFromStream(ByteArrayInputStream(file))
        assertArrayEquals(cover, result.coverBytes)
        assertNull(result.lyricText)
    }

    // ═══════════════ 非支持格式 ═══════════════

    @Test
    fun `未知格式返回空`() {
        val result = LocalTagParser.readFromStream(ByteArrayInputStream(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertNull(result.coverBytes)
        assertNull(result.lyricText)
    }

    @Test
    fun `空输入返回空`() {
        val result = LocalTagParser.readFromStream(ByteArrayInputStream(ByteArray(0)))
        assertNull(result.coverBytes)
        assertNull(result.lyricText)
    }

}
