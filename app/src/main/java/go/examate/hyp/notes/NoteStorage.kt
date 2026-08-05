package go.examate.hyp.notes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记本地存储 — 每条笔记一个 .md 文件,索引 JSON 记录元信息。
 *
 * 目录结构:
 * - `{filesDir}/notes/` — 笔记目录
 *   - `note_<timestamp>.md` — 笔记正文
 *   - `index.json` — 索引(仅元信息,不含正文,节省内存)
 *   - `assets/` — 笔记引用的资源文件(图片等)
 *
 * 所有文件操作均用 runCatching 包裹,失败返回空列表或 null,不抛异常。
 */
object NoteStorage {

    data class Note(
        val id: String,
        val title: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    /** 索引条目(无正文,用于列表展示) */
    private data class IndexEntry(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    private fun notesDir(context: Context): File =
        File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }

    private fun assetsDir(context: Context): File =
        File(notesDir(context), "assets").apply { if (!exists()) mkdirs() }

    private fun indexFile(context: Context): File =
        File(notesDir(context), "index.json")

    private fun noteFile(context: Context, id: String): File =
        File(notesDir(context), "$id.md")

    /** 读取索引文件,返回条目列表(失败返回空列表) */
    private fun readIndex(context: Context): MutableList<IndexEntry> {
        val result = mutableListOf<IndexEntry>()
        val f = indexFile(context)
        if (!f.exists()) return result
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(IndexEntry(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt")
                ))
            }
        }
        return result
    }

    /** 覆盖写索引文件 */
    private fun writeIndex(context: Context, entries: List<IndexEntry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                })
            }
            indexFile(context).writeText(arr.toString())
        }
    }

    /** 创建笔记:生成 id,写 .md 文件,追加到索引 */
    fun createNote(context: Context, title: String, content: String): Note {
        val now = System.currentTimeMillis()
        val id = "note_$now"
        val note = Note(id = id, title = title, content = content, createdAt = now, updatedAt = now)
        runCatching {
            noteFile(context, id).writeText(content)
            val entries = readIndex(context)
            entries.add(IndexEntry(id, title, now, now))
            writeIndex(context, entries)
        }
        return note
    }

    /** 保存笔记:覆盖写 .md 文件,更新索引中的 updatedAt/title */
    fun saveNote(context: Context, note: Note) {
        runCatching {
            noteFile(context, note.id).writeText(note.content)
            val entries = readIndex(context)
            val now = System.currentTimeMillis()
            val idx = entries.indexOfFirst { it.id == note.id }
            if (idx >= 0) {
                entries[idx] = IndexEntry(note.id, note.title, entries[idx].createdAt, now)
            } else {
                entries.add(IndexEntry(note.id, note.title, note.createdAt, now))
            }
            writeIndex(context, entries)
        }
    }

    /** 删除笔记:删 .md 文件,更新索引 */
    fun deleteNote(context: Context, id: String) {
        runCatching {
            noteFile(context, id).delete()
            val entries = readIndex(context)
            entries.removeAll { it.id == id }
            writeIndex(context, entries)
        }
    }

    /** 列出笔记:从索引读取,按 updatedAt 降序(不含正文) */
    fun listNotes(context: Context): List<Note> {
        return readIndex(context)
            .sortedByDescending { it.updatedAt }
            .map { e ->
                Note(
                    id = e.id,
                    title = e.title,
                    content = "",  // 列表不返回正文,节省内存
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt
                )
            }
    }

    /** 读取单条笔记:读 .md 文件全部内容 */
    fun loadNote(context: Context, id: String): Note? {
        return runCatching {
            val f = noteFile(context, id)
            if (!f.exists()) return null
            val content = f.readText()
            val entry = readIndex(context).firstOrNull { it.id == id }
            Note(
                id = id,
                title = entry?.title ?: "",
                content = content,
                createdAt = entry?.createdAt ?: 0L,
                updatedAt = entry?.updatedAt ?: 0L
            )
        }.getOrNull()
    }

    /** 复制资源到 notes/assets/ 目录,返回相对路径 "assets/<filename>" */
    fun copyAsset(context: Context, uri: Uri): String {
        return runCatching {
            val dir = assetsDir(context)
            val ts = System.currentTimeMillis()
            // 推断扩展名:优先从 uri 末段取,取不到则留空
            val ext = guessExtension(context, uri)
            val filename = "$ts$ext"
            val dest = File(dir, filename)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return ""
            "assets/$filename"
        }.getOrDefault("")
    }

    /** 从 uri 推断扩展名(含点号,如 ".png");无法推断返回空串 */
    private fun guessExtension(context: Context, uri: Uri): String {
        // 优先用 DISPLAY_NAME
        runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) {
                    val name = it.getString(idx) ?: ""
                    val dot = name.lastIndexOf('.')
                    if (dot >= 0) return name.substring(dot).lowercase()
                }
            }
        }
        // 回退:从 uri 路径取扩展名
        val path = uri.path ?: return ""
        val dot = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot).lowercase() else ""
    }
}
