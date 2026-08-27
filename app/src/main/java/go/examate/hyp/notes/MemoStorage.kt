package go.examate.hyp.notes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 速记本地存储 — 画布版。
 *
 * 从"心情便利贴"升级为"灵感卡片":
 * - tag:轻量分类(想法/记录),倒数日是独立卡片不在 tag 体系内;
 *   旧版多标签(灵感/待办/学习/生活)读取时自动迁移:灵感→想法,其余→记录
 * - images:附图路径(相对 filesDir,如 "notes/memo_assets/x.jpg")
 * - colorIndex:用户自选背景色(0..7,兼容旧数据)
 * - pinned:置顶;offsetX/Y:自由画布上的拖动位置
 *
 * 存储:`{filesDir}/notes/memos.json`(单个 JSON 数组文件)。
 * 兼容旧版数据:缺失字段回退默认值,旧 memo 的 mood 字段被忽略。
 * 所有文件操作均用 runCatching 包裹,失败返回空列表,不抛异常。
 */
object MemoStorage {

    /** 分类定义:id → (emoji, 中文名) — 少而准,输入零负担 */
    val Tags: List<Triple<String, String, String>> = listOf(
        Triple("thought", "💭", "想法"),
        Triple("record", "📝", "记录")
    )

    /** 默认分类:不选择时按"记录"归档 */
    const val DEFAULT_TAG = "record"

    /** 旧标签迁移:灵感→想法;待办/学习/生活→记录;未知→记录 */
    private fun migrateTag(raw: String): String = when (raw) {
        "", "thought" -> raw.ifEmpty { "" }
        "idea" -> "thought"
        else -> "record"
    }

    fun tagEmoji(id: String): String = Tags.firstOrNull { it.first == id }?.second ?: ""

    fun tagName(id: String): String = Tags.firstOrNull { it.first == id }?.third ?: ""

    /** 分类展示色(用于统计图),低饱和色系 */
    fun tagColor(id: String): androidx.compose.ui.graphics.Color = when (id) {
        "thought" -> androidx.compose.ui.graphics.Color(0xFF9B7BE8)
        "record" -> androidx.compose.ui.graphics.Color(0xFFE39B5B)
        else -> androidx.compose.ui.graphics.Color(0xFFB8B8B8)
    }

    data class Memo(
        val id: String,          // "memo_<timestamp>"
        val content: String,     // 文本内容
        val tag: String = "",    // 标签 id,空 = 未分类
        val colorIndex: Int = 0,
        val images: List<String> = emptyList(), // 附图相对路径
        val pinned: Boolean = false,
        val createdAt: Long,
        val updatedAt: Long = createdAt,
        /** 自由画布上的拖动位置(px),0 表示未拖动,用默认散开布局 */
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    private fun file(context: Context): File {
        val dir = File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }
        return File(dir, "memos.json")
    }

    /** 读取全部速记(失败返回空列表) */
    private fun readAll(context: Context): MutableList<Memo> {
        val result = mutableListOf<Memo>()
        val f = file(context)
        if (!f.exists()) return result
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val created = o.optLong("createdAt")
                val imgs = mutableListOf<String>()
                val imgArr = o.optJSONArray("images")
                if (imgArr != null) for (j in 0 until imgArr.length()) imgs.add(imgArr.optString(j))
                result.add(
                    Memo(
                        id = o.optString("id"),
                        content = o.optString("content"),
                        tag = migrateTag(o.optString("tag", "")),
                        colorIndex = o.optInt("colorIndex"),
                        images = imgs,
                        pinned = o.optBoolean("pinned"),
                        createdAt = created,
                        updatedAt = o.optLong("updatedAt", created),
                        offsetX = o.optDouble("offsetX", 0.0).toFloat(),
                        offsetY = o.optDouble("offsetY", 0.0).toFloat()
                    )
                )
            }
        }
        return result
    }

    /** 覆盖写全部速记 */
    private fun writeAll(context: Context, memos: List<Memo>) {
        runCatching {
            val arr = JSONArray()
            memos.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("content", m.content)
                    put("tag", m.tag)
                    put("colorIndex", m.colorIndex)
                    put("images", JSONArray(m.images))
                    put("pinned", m.pinned)
                    put("createdAt", m.createdAt)
                    put("updatedAt", m.updatedAt)
                    put("offsetX", m.offsetX.toDouble())
                    put("offsetY", m.offsetY.toDouble())
                })
            }
            file(context).writeText(arr.toString())
        }
    }

    /** 创建速记:自动分配 id,colorIndex 按现有数量循环 0-7 */
    fun createMemo(
        context: Context,
        content: String,
        tag: String = "",
        colorIndex: Int = -1,
        images: List<String> = emptyList()
    ): Memo {
        val now = System.currentTimeMillis()
        val memos = readAll(context)
        val color = if (colorIndex in 0..7) colorIndex else memos.size % 8
        val memo = Memo(
            id = "memo_$now",
            content = content,
            tag = tag,
            colorIndex = color,
            images = images,
            pinned = false,
            createdAt = now,
            updatedAt = now
        )
        memos.add(memo)
        writeAll(context, memos)
        return memo
    }

    /** 更新速记(内容/标签/颜色/附图/置顶),刷新 updatedAt */
    fun updateMemo(context: Context, memo: Memo) {
        val now = System.currentTimeMillis()
        val updated = memo.copy(updatedAt = now)
        writeAll(context, readAll(context).map { if (it.id == memo.id) updated else it })
    }

    /** 置顶/取消置顶 */
    fun setPinned(context: Context, id: String, pinned: Boolean) {
        writeAll(context, readAll(context).map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    /** 保存卡片在自由画布上的拖动位置(不改 updatedAt,避免影响排序) */
    fun updatePosition(context: Context, id: String, x: Float, y: Float) {
        writeAll(context, readAll(context).map { if (it.id == id) it.copy(offsetX = x, offsetY = y) else it })
    }

    /** 列出速记:置顶优先,再按 updatedAt 降序 */
    fun listMemos(context: Context): List<Memo> =
        readAll(context)
            .sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.updatedAt })

    /** 删除速记(附图文件一并清理) */
    fun deleteMemo(context: Context, id: String) {
        readAll(context).firstOrNull { it.id == id }?.let { memo ->
            memo.images.forEach { rel -> resolveImageFile(context, rel).delete() }
        }
        writeAll(context, readAll(context).filterNot { it.id == id })
    }

    // ════════════════════════════════════════
    //  附图管理
    // ════════════════════════════════════════

    private fun assetsDir(context: Context): File =
        File(context.filesDir, "notes/memo_assets").apply { if (!exists()) mkdirs() }

    /** 将选中的图片复制到私有目录,返回相对路径(如 "notes/memo_assets/x.jpg");失败返回 null */
    fun copyImage(context: Context, uri: Uri): String? = runCatching {
        val ext = when (context.contentResolver.getType(uri)?.substringAfterLast("/")) {
            "png" -> "png"; "gif" -> "gif"; "webp" -> "webp"
            else -> "jpg"
        }
        val name = "img_${System.currentTimeMillis()}.$ext"
        val target = File(assetsDir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        "notes/memo_assets/$name"
    }.getOrNull()

    /** 相对路径 → File */
    fun resolveImageFile(context: Context, rel: String): File = File(context.filesDir, rel)
}
