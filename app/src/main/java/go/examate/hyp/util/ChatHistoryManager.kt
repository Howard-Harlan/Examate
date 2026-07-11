package go.examate.hyp.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

data class ChatMsg(
    val role: String,
    val content: String,
    val isError: Boolean = false,
    val time: Long,
    val imageUriString: String? = null,
    val imageBase64: String? = null,
    val imageUrl: String? = null,
    val toolCalls: List<String> = emptyList(),
    val id: String = UUID.randomUUID().toString()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMsg>,
    val updateTime: Long
)

object ChatHistoryManager {
    private const val PREFS = "chat_history"
    private const val KEY = "sessions"
    private const val MAX_SESSIONS = 50

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
                val msgArr = obj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<ChatMsg>()
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    msgs.add(
                        ChatMsg(
                            role = m.optString("role"),
                            content = m.optString("content"),
                            isError = m.optBoolean("isError", false),
                            time = m.optLong("time", System.currentTimeMillis()),
                            imageUriString = m.optString("imageUriString", "").ifEmpty { null },
                            imageBase64 = m.optString("imageBase64", "").ifEmpty { null },
                            imageUrl = m.optString("imageUrl", "").ifEmpty { null },
                            toolCalls = m.optJSONArray("toolCalls")?.let { tools ->
                                (0 until tools.length()).map { tools.optString(it) }
                            }.orEmpty()
                        )
                    )
                }
                list.add(
                    ChatSession(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "新对话"),
                        messages = msgs,
                        updateTime = obj.optLong("updateTime", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.updateTime }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSession(context: Context, session: ChatSession) {
        ioExecutor.execute {
            val all = loadAll(context).toMutableList()
            all.removeAll { it.id == session.id }
            all.add(0, session)
            persist(context, all.take(MAX_SESSIONS))
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
        sessions.forEach { session ->
            val msgArr = JSONArray()
            session.messages.forEach { msg ->
                msgArr.put(
                    JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("isError", msg.isError)
                        put("time", msg.time)
                        msg.imageUriString?.let { put("imageUriString", it) }
                        msg.imageBase64?.let { put("imageBase64", it) }
                        msg.imageUrl?.let { put("imageUrl", it) }
                        if (msg.toolCalls.isNotEmpty()) put("toolCalls", JSONArray(msg.toolCalls))
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("messages", msgArr)
                    put("updateTime", session.updateTime)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun newSession(): ChatSession = ChatSession(
        id = UUID.randomUUID().toString(),
        title = "新对话",
        messages = emptyList(),
        updateTime = System.currentTimeMillis()
    )

    fun makeTitle(firstUserMsg: String): String {
        val t = firstUserMsg.trim().replace("\n", " ")
        return if (t.length > 20) t.substring(0, 20) + "..." else t
    }
}
