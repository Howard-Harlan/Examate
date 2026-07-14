package go.examate.hyp.util

import android.content.Context
import go.examate.hyp.ui.screens.LanChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 局域网聊天消息缓存
 *
 * 缓存最近 5 分钟的消息,退出后重新进入可看到历史消息,
 * 新加入的设备也能看到之前的消息(通过 UDP 广播历史消息)。
 *
 * 存储位置:应用缓存目录 lan_chat_cache.json
 * 清理策略:启动时过滤超过 5 分钟的消息
 */
object LanChatCache {

    private const val CACHE_FILE = "lan_chat_cache.json"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟

    private fun cacheFile(context: Context): File =
        File(context.cacheDir, CACHE_FILE)

    /**
     * 加载缓存消息,过滤超过 5 分钟的。
     */
    fun load(context: Context): List<LanChatMessage> {
        val file = cacheFile(context)
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
    fun append(context: Context, msg: LanChatMessage) {
        try {
            val list = load(context).toMutableList()
            list.add(msg)
            // 过滤超时消息
            val now = System.currentTimeMillis()
            list.removeAll { now - it.time > CACHE_TTL_MS }
            save(context, list)
        } catch (e: Exception) { /* ignore */ }
    }

    /** 保存完整消息列表 */
    private fun save(context: Context, messages: List<LanChatMessage>) {
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
            cacheFile(context).writeText(arr.toString())
        } catch (e: Exception) { /* ignore */ }
    }

    /** 清空缓存 */
    fun clear(context: Context) {
        try { cacheFile(context).delete() } catch (e: Exception) { }
    }
}
