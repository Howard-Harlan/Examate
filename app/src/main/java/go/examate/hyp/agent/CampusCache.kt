package go.examate.hyp.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 校园数据透明缓存层。
 *
 * 每次 AgentTools 执行工具调用后,把结果缓存 5 分钟。
 * 下次同一工具被调用时直接返回缓存数据,省掉 1 次 API 往返,
 * AI 直接基于缓存数据回答——从 10 秒降到 2 秒。
 *
 * 使用方式(AgentTools 内部):
 *   val cached = CampusCache.get("get_balance")
 *   if (cached != null) return ToolResult(true, cached + "\n(缓存)")
 *   val result = repo.getBalance(...)
 *   CampusCache.put("get_balance", formatResult(result))
 */
object CampusCache {

    private data class Entry(val data: String, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 缓存有效期(毫秒)。工具调用直连服务器有网络开销,5 分钟内余额/成绩不会大变 */
    private const val TTL_MS = 5 * 60 * 1000L

    /** 读取缓存。过期或不存在返回 null */
    fun get(toolName: String): String? {
        val entry = cache[toolName] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache.remove(toolName)
            return null
        }
        return entry.data
    }

    /** 写入缓存 */
    fun put(toolName: String, data: String) {
        cache[toolName] = Entry(data, System.currentTimeMillis())
    }

    /** 清除所有缓存(如重新登录时) */
    fun clear() {
        cache.clear()
    }
}
