package go.examate.hyp.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记忆管理器:管理 AI 的长期记忆
 *
 * 记忆是一些关键信息片段,会注入到系统提示词,
 * 让 AI 记住用户的偏好、重要事项等。
 *
 * 原内嵌于 SkillManager.kt,现独立成文件,职责单一、便于维护。
 */
object MemoryManager {

    private const val PREFS_NAME = "agent_memory"
    private const val KEY_MEMORIES = "memories_json"

    /** 记忆条目 */
    data class Memory(
        val id: String,
        val content: String,
        val category: String = "通用",  // 通用/偏好/重要事项/人物
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 加载所有记忆 */
    fun loadAll(context: Context): List<Memory> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEMORIES, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Memory>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(Memory(
                id = obj.getString("id"),
                content = obj.getString("content"),
                category = obj.optString("category", "通用"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            ))
        }
        return list
    }

    /** 添加记忆 */
    fun add(context: Context, content: String, category: String = "通用"): Memory {
        val memories = loadAll(context).toMutableList()
        val memory = Memory(
            id = System.currentTimeMillis().toString(),
            content = content,
            category = category
        )
        memories.add(memory)
        save(context, memories)
        return memory
    }

    /** 删除记忆 */
    fun delete(context: Context, id: String) {
        val memories = loadAll(context).filter { it.id != id }
        save(context, memories)
    }

    /** 清空记忆 */
    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, memories: List<Memory>) {
        val arr = JSONArray()
        memories.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("content", m.content)
                put("category", m.category)
                put("createdAt", m.createdAt)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MEMORIES, arr.toString()).apply()
    }

    /** 构建记忆提示词片段 */
    fun buildMemoryPrompt(context: Context): String {
        val memories = loadAll(context)
        if (memories.isEmpty()) return ""

        // 渐进瘦身:最多注入最近 15 条,单条超 100 字截断,控制每轮输入 token
        val recent = memories.takeLast(15)
        val sb = StringBuilder()
        sb.append("\n\n## 长期记忆\n")
        sb.append("以下是关于用户的重要信息,请在对话中参考:\n\n")
        recent.forEach { m ->
            val c = if (m.content.length > 100) m.content.take(100) + "…" else m.content
            sb.append("- [${m.category}] $c\n")
        }
        return sb.toString()
    }
}
