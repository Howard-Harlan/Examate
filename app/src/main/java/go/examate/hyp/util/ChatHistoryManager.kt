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
    val imageUris: List<String> = emptyList(),
    val imageBase64: String? = null,
    val imageUrl: String? = null,
    val toolCalls: List<String> = emptyList(),
    val cards: List<ActionCard> = emptyList(),
    val filePath: String? = null,
    val toolSteps: List<ToolStep> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    /** 父消息 id;null 表示根。构成对话树,用于分支/知识图谱 */
    val parentId: String? = null
)

/** 工具调用步骤状态 */
enum class ToolStatus { RUNNING, DONE, ERROR }

/** 工具调用步骤(用于链式思考容器展示) */
data class ToolStep(
    val name: String,
    val label: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val output: String = "",
    val card: ActionCard? = null
)

/**
 * 行动卡片:AI 回复下方紧贴的可交互卡片
 * type 区分:
 * - "ecard_topup" 一卡通充值入口
 * - "ecard_balance" 一卡通余额信息
 * - "room_electricity" 房间电费信息
 * - "campus_grades" 成绩信息
 * - "link" 普通链接
 */
data class ActionCard(
    val type: String,
    val title: String,
    val summary: String = "",
    val payload: Map<String, String> = emptyMap()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMsg>,
    val updateTime: Long,
    /** 当前聚焦的叶子消息 id(分支对话);null 表示默认取最后一条 */
    val activeLeafId: String? = null
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
                // 向后兼容:旧数据无 id/parentId 时,按存储顺序把每条挂到前一条,重建线性链
                var prevId: String? = null
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    val toolCalls = m.optJSONArray("toolCalls")?.let { tools ->
                        (0 until tools.length()).map { tools.optString(it) }
                    }.orEmpty()
                    val cards = m.optJSONArray("cards")?.let { cdArr ->
                        (0 until cdArr.length()).mapNotNull { idx ->
                            val c = cdArr.optJSONObject(idx) ?: return@mapNotNull null
                            val payloadObj = c.optJSONObject("payload")
                            val payload = mutableMapOf<String, String>()
                            if (payloadObj != null) {
                                val keys = payloadObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    payload[k] = payloadObj.optString(k)
                                }
                            }
                            ActionCard(
                                type = c.optString("type"),
                                title = c.optString("title"),
                                summary = c.optString("summary"),
                                payload = payload
                            )
                        }
                    }.orEmpty()
                    val msgId = m.optString("id", "").ifEmpty { UUID.randomUUID().toString() }
                    val parentId = m.optString("parentId", "").ifEmpty { prevId }
                    msgs.add(
                        ChatMsg(
                            role = m.optString("role"),
                            content = m.optString("content"),
                            isError = m.optBoolean("isError", false),
                            time = m.optLong("time", System.currentTimeMillis()),
                            imageUriString = m.optString("imageUriString", "").ifEmpty { null },
                            imageUris = m.optJSONArray("imageUris")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                            imageBase64 = m.optString("imageBase64", "").ifEmpty { null },
                            imageUrl = m.optString("imageUrl", "").ifEmpty { null },
                            toolCalls = toolCalls,
                            cards = cards,
                            filePath = m.optString("filePath", "").ifEmpty { null },
                            toolSteps = m.optJSONArray("toolSteps")?.let { ts ->
                                (0 until ts.length()).mapNotNull { idx ->
                                    val s = ts.optJSONObject(idx) ?: return@mapNotNull null
                                    ToolStep(
                                        name = s.optString("name"),
                                        label = s.optString("label"),
                                        status = runCatching { ToolStatus.valueOf(s.optString("status")) }.getOrDefault(ToolStatus.DONE),
                                        output = s.optString("output", ""),
                                        card = null  // card 不持久化(运行时产物)
                                    )
                                }
                            }.orEmpty(),
                            id = msgId,
                            parentId = parentId
                        )
                    )
                    prevId = msgId
                }
                list.add(
                    ChatSession(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "新对话"),
                        messages = msgs,
                        updateTime = obj.optLong("updateTime", System.currentTimeMillis()),
                        activeLeafId = obj.optString("activeLeafId", "").ifEmpty { null }
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
                        put("id", msg.id)
                        msg.parentId?.let { put("parentId", it) }
                        put("role", msg.role)
                        put("content", msg.content)
                        put("isError", msg.isError)
                        put("time", msg.time)
                        msg.imageUriString?.let { put("imageUriString", it) }
                        if (msg.imageUris.isNotEmpty()) put("imageUris", JSONArray(msg.imageUris))
                        msg.imageBase64?.let { put("imageBase64", it) }
                        msg.imageUrl?.let { put("imageUrl", it) }
                        msg.filePath?.let { put("filePath", it) }
                        if (msg.toolCalls.isNotEmpty()) put("toolCalls", JSONArray(msg.toolCalls))
                        if (msg.toolSteps.isNotEmpty()) {
                            val tsArr = JSONArray()
                            msg.toolSteps.forEach { ts ->
                                tsArr.put(JSONObject().apply {
                                    put("name", ts.name)
                                    put("label", ts.label)
                                    put("status", ts.status.name)
                                    put("output", ts.output)
                                })
                            }
                            put("toolSteps", tsArr)
                        }
                        if (msg.cards.isNotEmpty()) {
                            val cdArr = JSONArray()
                            msg.cards.forEach { cd ->
                                val p = JSONObject()
                                cd.payload.forEach { (k, v) -> p.put(k, v) }
                                cdArr.put(JSONObject().apply {
                                    put("type", cd.type)
                                    put("title", cd.title)
                                    put("summary", cd.summary)
                                    put("payload", p)
                                })
                            }
                            put("cards", cdArr)
                        }
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("messages", msgArr)
                    put("updateTime", session.updateTime)
                    session.activeLeafId?.let { put("activeLeafId", it) }
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
