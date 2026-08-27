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
        val updatedAt: Long,
        val pinned: Boolean = false,
        /** 所属文件夹 id,空 = 根目录 */
        val folderId: String = "",
        /** 是否作为 AI 搜题的知识来源(默认是,可在笔记「功能设置」里关闭) */
        val knowledgeSource: Boolean = true
    )

    /** 笔记文件夹 */
    data class NoteFolder(
        val id: String,
        val name: String,
        val createdAt: Long
    )

    /** 索引条目(无正文,用于列表展示) */
    private data class IndexEntry(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean = false,
        val folderId: String = "",
        val knowledgeSource: Boolean = true
    )

    private fun notesDir(context: Context): File =
        File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }

    private fun assetsDir(context: Context): File =
        File(notesDir(context), "assets").apply { if (!exists()) mkdirs() }

    private fun indexFile(context: Context): File =
        File(notesDir(context), "index.json")

    private fun foldersFile(context: Context): File =
        File(notesDir(context), "folders.json")

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
                    updatedAt = o.optLong("updatedAt"),
                    pinned = o.optBoolean("pinned"),
                    folderId = o.optString("folderId", ""),
                    knowledgeSource = o.optBoolean("knowledgeSource", true)
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
                    put("pinned", e.pinned)
                    put("folderId", e.folderId)
                    put("knowledgeSource", e.knowledgeSource)
                })
            }
            indexFile(context).writeText(arr.toString())
        }
    }

    // ════════════════════════════════════════
    //  文件夹
    // ════════════════════════════════════════

    /** 文件夹列表(按创建时间升序) */
    fun listFolders(context: Context): List<NoteFolder> {
        val result = mutableListOf<NoteFolder>()
        val f = foldersFile(context)
        if (!f.exists()) return result
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(NoteFolder(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    createdAt = o.optLong("createdAt")
                ))
            }
        }
        return result.sortedBy { it.createdAt }
    }

    private fun writeFolders(context: Context, folders: List<NoteFolder>) {
        runCatching {
            val arr = JSONArray()
            folders.forEach { fd ->
                arr.put(JSONObject().apply {
                    put("id", fd.id)
                    put("name", fd.name)
                    put("createdAt", fd.createdAt)
                })
            }
            foldersFile(context).writeText(arr.toString())
        }
    }

    /** 创建文件夹,返回新文件夹 */
    fun createFolder(context: Context, name: String): NoteFolder {
        val folder = NoteFolder(
            id = "folder_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "未命名文件夹" },
            createdAt = System.currentTimeMillis()
        )
        writeFolders(context, listFolders(context) + folder)
        return folder
    }

    /** 重命名文件夹 */
    fun renameFolder(context: Context, folderId: String, name: String) {
        writeFolders(context, listFolders(context).map {
            if (it.id == folderId) it.copy(name = name.trim().ifBlank { it.name }) else it
        })
    }

    /** 删除文件夹:文件夹内笔记移回根目录,不删除笔记本身 */
    fun deleteFolder(context: Context, folderId: String) {
        writeFolders(context, listFolders(context).filterNot { it.id == folderId })
        writeIndex(context, readIndex(context).map {
            if (it.folderId == folderId) it.copy(folderId = "") else it
        })
    }

    /** 移动笔记到文件夹(folderId 空 = 移回根目录) */
    fun moveNoteToFolder(context: Context, noteId: String, folderId: String) {
        runCatching {
            val entries = readIndex(context)
            val idx = entries.indexOfFirst { it.id == noteId }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(folderId = folderId)
                writeIndex(context, entries)
            }
        }
    }

    /** 创建笔记:生成 id,写 .md 文件,追加到索引(可指定所属文件夹) */
    fun createNote(context: Context, title: String, content: String, folderId: String = ""): Note {
        val now = System.currentTimeMillis()
        val id = "note_$now"
        val note = Note(id = id, title = title, content = content, createdAt = now, updatedAt = now, folderId = folderId)
        runCatching {
            noteFile(context, id).writeText(content)
            val entries = readIndex(context)
            entries.add(IndexEntry(id, title, now, now, folderId = folderId, knowledgeSource = true))
            writeIndex(context, entries)
        }
        return note
    }

    /** 保存笔记:覆盖写 .md 文件,更新索引中的 updatedAt/title(保留 folderId) */
    fun saveNote(context: Context, note: Note) {
        runCatching {
            noteFile(context, note.id).writeText(note.content)
            val entries = readIndex(context)
            val now = System.currentTimeMillis()
            val idx = entries.indexOfFirst { it.id == note.id }
            if (idx >= 0) {
                entries[idx] = IndexEntry(note.id, note.title, entries[idx].createdAt, now, note.pinned, entries[idx].folderId, note.knowledgeSource)
            } else {
                entries.add(IndexEntry(note.id, note.title, note.createdAt, now, note.pinned, note.folderId, note.knowledgeSource))
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

    /** 列出笔记:置顶优先,再按 updatedAt 降序(不含正文) */
    fun listNotes(context: Context): List<Note> {
        return readIndex(context)
            .sortedWith(compareByDescending<IndexEntry> { it.pinned }.thenByDescending { it.updatedAt })
            .map { e ->
                Note(
                    id = e.id,
                    title = e.title,
                    content = "",  // 列表不返回正文,节省内存
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt,
                    pinned = e.pinned,
                    folderId = e.folderId,
                    knowledgeSource = e.knowledgeSource
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
                updatedAt = entry?.updatedAt ?: 0L,
                pinned = entry?.pinned ?: false,
                folderId = entry?.folderId ?: "",
                knowledgeSource = entry?.knowledgeSource ?: true
            )
        }.getOrNull()
    }

    /** 置顶/取消置顶 */
    fun setPinned(context: Context, id: String, pinned: Boolean) {
        runCatching {
            val entries = readIndex(context)
            val idx = entries.indexOfFirst { it.id == id }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(pinned = pinned)
                writeIndex(context, entries)
            }
        }
    }

    /** 设置是否作为 AI 搜题知识来源 */
    fun setKnowledgeSource(context: Context, id: String, enabled: Boolean) {
        runCatching {
            val entries = readIndex(context)
            val idx = entries.indexOfFirst { it.id == id }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(knowledgeSource = enabled)
                writeIndex(context, entries)
            }
        }
    }

    /**
     * 构建笔记知识索引(渐进披露):只注入标题 + 前 [excerptLen] 字摘要,
     * 用于搜题系统提示词,把 token 影响压到最低(类似 Skills 的索引注入)。
     *
     * @param maxNotes 最多注入的笔记数(按置顶/更新时间取前 N 条)
     */
    fun buildIndexPrompt(context: Context, maxNotes: Int = 12, excerptLen: Int = 60): String {
        val notes = listNotes(context)
            .filter { it.knowledgeSource }
            .take(maxNotes)
        if (notes.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## 笔记知识库(渐进披露)\n")
        sb.append("以下是用户笔记的标题与摘要。当题目与某条笔记相关时,优先参考该笔记的摘要作答;如需全文,可提示用户打开笔记查看:\n")
        notes.forEachIndexed { i, n ->
            val excerpt = loadNote(context, n.id)?.content
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(excerptLen)
                ?: ""
            val title = n.title.ifEmpty { "无标题" }
            sb.append("${i + 1}. ${title}：${excerpt}\n")
        }
        return sb.toString()
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

/**
 * 笔记显示偏好 — 仅作用于笔记模块(列表 + 编辑器)。
 *  - layoutMode:外显布局(0=卡片式, 1=列表式)
 *  - previewTheme:Markdown 预览渲染主题(默认/新闻/学术/等宽)
 */
object NotePrefs {
    const val PREFS = "note_prefs"
    const val KEY_LAYOUT_MODE = "layout_mode"
    const val KEY_PREVIEW_THEME = "preview_theme"
    const val KEY_PREVIEW_MODE = "preview_mode"  // 读写切换状态:0=编辑, 1=预览

    fun layoutMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAYOUT_MODE, 0)

    fun setLayoutMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LAYOUT_MODE, mode).apply()
    }

    fun previewThemeIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PREVIEW_THEME, 0)

    fun setPreviewTheme(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PREVIEW_THEME, index).apply()
    }

    /** 读取上次退出时的读/写状态(默认 false=编辑) */
    fun previewMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PREVIEW_MODE, false)

    /** 持久化读/写切换状态 */
    fun setPreviewMode(context: Context, preview: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PREVIEW_MODE, preview).apply()
    }
}

/** Markdown 预览渲染主题定义 */
data class NotePreviewTheme(
    val name: String,
    val fontFamily: androidx.compose.ui.text.font.FontFamily?,
    val fontSize: Int,
    val lineHeightExtra: Int
)

/** 可选预览主题:默认 / 新闻 / 学术 / 等宽 */
val NOTE_PREVIEW_THEMES = listOf(
    NotePreviewTheme("默认", null, 15, 6),
    NotePreviewTheme("新闻", androidx.compose.ui.text.font.FontFamily.Serif, 16, 8),
    NotePreviewTheme("学术", androidx.compose.ui.text.font.FontFamily.Serif, 14, 7),
    NotePreviewTheme("等宽", androidx.compose.ui.text.font.FontFamily.Monospace, 13, 6)
)
