package go.examate.hyp.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 聊天历史消息
 * @param id 稳定唯一标识(用于 LazyColumn key)
 * @param imageUriString 图片Uri字符串(可选,仅当前会话有效,历史记录中可能失效)
 * @param imageBase64 AI 生成的图片(base64,保存在历史中)
 * @param imageUrl AI 生成的图片(URL)
 * @param toolCalls 工具调用日志(仅 assistant 消息)
 */
data class ChatMsg(
    val role: String,       // "user" / "assistant"
    val content: String,
    val isError: Boolean = false,
    val time: Long,
    val imageUriString: String? = null,
    val imageBase64: String? = null,
    val imageUrl: String? = null,
    val toolCalls: List<String> = emptyList(),
    val id: String = java.util.UUID.randomUUID().toString()
)

/**
 * 一个聊天会话
 */
data class ChatSession(
    val id: String,
    val title: String,      // 第一条用户消息前 20 字
    val messages: List<ChatMsg>,
    val updateTime: Long
)

/**
 * 聊天历史管理(SharedPreferences + JSON)
 *
 * 写操作在单线程 IO Executor 中异步执行,避免阻塞主线程;
 * 单线程保证写操作串行化,避免竞态。
 */
object ChatHistoryManager {

    private const val PREFS = "chat_history"
    private const val KEY = "sessions"
    private const val MAX_SESSIONS = 50 // 最多保留 50 个会话

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ChatHistoryIO").apply { isDaemon = true }
    }

    fun loadAll(context: Context): List<ChatSession> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgs = mutableListOf<ChatMsg>()
                val msgArr = obj.getJSONArray("messages")
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    msgs.add(ChatMsg(
                        role = m.getString("role"),
                        content = m.getString("content"),
                        isError = m.optBoolean("isError", false),
                        time = m.getLong("time"),
                        imageBase64 = m.optString("imageBase64", "").ifEmpty { null },
                        imageUrl = m.optString("imageUrl", "").ifEmpty { null }
                    ))
                }
                list.add(ChatSession(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    messages = msgs,
                    updateTime = obj.getLong("updateTime")
                ))
            }
            // 按时间倒序
            list.sortedByDescending { it.updateTime }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSession(context: Context, session: ChatSession) {
        ioExecutor.execute {
            val all = loadAll(context).toMutableList()
            // 移除同 id 的旧会话
            all.removeAll { it.id == session.id }
            all.add(0, session)
            // 限制数量
            val trimmed = if (all.size > MAX_SESSIONS) all.subList(0, MAX_SESSIONS) else all
            persist(context, trimmed)
        }
    }

    fun deleteSession(context: Context, id: String) {
        ioExecutor.execute {
            val all = loadAll(context).toMutableList()
            all.removeAll { it.id == id }
            persist(context, all)
        }
    }

    fun clearAll(context: Context) {
        ioExecutor.execute {
            persist(context, emptyList())
        }
    }

    private fun persist(context: Context, sessions: List<ChatSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            val msgArr = JSONArray()
            for (m in s.messages) {
                msgArr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("isError", m.isError)
                    put("time", m.time)
                    m.imageBase64?.let { put("imageBase64", it) }
                    m.imageUrl?.let { put("imageUrl", it) }
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("messages", msgArr)
                put("updateTime", s.updateTime)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun newSession(): ChatSession = ChatSession(
        id = UUID.randomUUID().toString(),
        title = "新对话",
        messages = emptyList(),
        updateTime = System.currentTimeMillis()
    )

    fun makeTitle(firstUserMsg: String): String {
        val t = firstUserMsg.trim().replace("\n", " ")
        return if (t.length > 20) t.substring(0, 20) + "…" else t
    }
}
