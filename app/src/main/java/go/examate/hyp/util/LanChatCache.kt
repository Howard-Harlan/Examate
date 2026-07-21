package go.examate.hyp.util

import android.content.Context
import go.examate.hyp.ui.screens.LanChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 聊天消息缓存(5 分钟 TTL)
 *
 * 按 [cacheKey] 区分不同会话:
 * - "lan":局域网模式
 * - "room_{roomCode}":MQTT 房间模式
 *
 * 退出后重新进入可看到历史消息(5 分钟内)。
 * 由 [go.examate.hyp.LanChatActivity] 和 [go.examate.hyp.service.LanChatService] 共用。
 */
object LanChatCache {

    private const val CACHE_FILE_PREFIX = "lan_chat_cache_"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟

    private fun cacheFile(context: Context, cacheKey: String): File =
        File(context.cacheDir, "$CACHE_FILE_PREFIX${sanitize(cacheKey)}.json")

    /** cacheKey 只允许字母数字下划线 */
    private fun sanitize(key: String): String =
        if (key.matches(Regex("[A-Za-z0-9_]+"))) key else "default"

    /**
     * 加载缓存消息,过滤超过 5 分钟的。
     */
    fun load(context: Context, cacheKey: String = "lan"): List<LanChatMessage> {
        val file = cacheFile(context, cacheKey)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            val arr = JSONArray(text)
            val now = System.currentTimeMillis()
            val result = mutableListOf<LanChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val time = obj.optLong("time", 0)
                if (now - time <= CACHE_TTL_MS) {
                    result.add(LanChatMessage(
                        text = obj.optString("text"),
                        isMine = obj.optBoolean("isMine"),
                        senderName = obj.optString("senderName"),
                        time = time
                    ))
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 追加一条消息到缓存。
     */
    fun append(context: Context, msg: LanChatMessage, cacheKey: String = "lan") {
        try {
            val list = load(context, cacheKey).toMutableList()
            list.add(msg)
            // 过滤超时消息
            val now = System.currentTimeMillis()
            list.removeAll { now - it.time > CACHE_TTL_MS }
            save(context, list, cacheKey)
        } catch (e: Exception) { /* ignore */ }
    }

    /** 保存完整消息列表 */
    private fun save(context: Context, messages: List<LanChatMessage>, cacheKey: String) {
        try {
            val arr = JSONArray()
            messages.forEach { msg ->
                arr.put(JSONObject().apply {
                    put("text", msg.text)
                    put("isMine", msg.isMine)
                    put("senderName", msg.senderName)
                    put("time", msg.time)
                })
            }
            cacheFile(context, cacheKey).writeText(arr.toString())
        } catch (e: Exception) { /* ignore */ }
    }

    /** 清空指定缓存 */
    fun clear(context: Context, cacheKey: String = "lan") {
        try { cacheFile(context, cacheKey).delete() } catch (e: Exception) { }
    }
}
