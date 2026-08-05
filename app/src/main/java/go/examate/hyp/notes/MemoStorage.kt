package go.examate.hyp.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 速记本地存储 — 瞬时灵感捕捉,纯文本便利贴,无日期无完成状态。
 *
 * 存储到 `{filesDir}/notes/memos.json`(单个 JSON 数组文件),与 [NoteStorage] 共用 notes 目录。
 * 所有文件操作均用 runCatching 包裹,失败返回空列表,不抛异常。
 */
object MemoStorage {

    data class Memo(
        val id: String,        // "memo_<timestamp>"
        val content: String,   // 纯文本内容
        val createdAt: Long,
        val colorIndex: Int    // 0-3,用于卡片背景色变化
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
                result.add(
                    Memo(
                        id = o.optString("id"),
                        content = o.optString("content"),
                        createdAt = o.optLong("createdAt"),
                        colorIndex = o.optInt("colorIndex")
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
                    put("createdAt", m.createdAt)
                    put("colorIndex", m.colorIndex)
                })
            }
            file(context).writeText(arr.toString())
        }
    }

    /** 创建速记:自动分配 id 与 colorIndex(按现有数量递增循环 0-3) */
    fun createMemo(context: Context, content: String): Memo {
        val now = System.currentTimeMillis()
        val memos = readAll(context)
        val colorIndex = memos.size % 4
        val memo = Memo(id = "memo_$now", content = content, createdAt = now, colorIndex = colorIndex)
        memos.add(memo)
        writeAll(context, memos)
        return memo
    }

    /** 列出速记:按 createdAt 降序 */
    fun listMemos(context: Context): List<Memo> =
        readAll(context).sortedByDescending { it.createdAt }

    /** 删除速记 */
    fun deleteMemo(context: Context, id: String) {
        writeAll(context, readAll(context).filterNot { it.id == id })
    }
}
