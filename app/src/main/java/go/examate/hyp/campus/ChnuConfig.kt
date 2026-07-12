package go.examate.hyp.campus

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

// ==================== 配置常量 ====================

/** 淮北师范大学各系统 URL 与超时配置 */
object ChnuConfig {
    const val CAS_LOGIN_URL = "https://auth.chnu.edu.cn/authserver/login"
    /** CAS 基础 URL(由 CAS_LOGIN_URL 解析,用于 AJAX /authserver/userLogin) */
    val CAS_BASE: String = run {
        val u = CAS_LOGIN_URL.toHttpUrl()
        "${u.scheme}://${u.host}"
    }
    const val ZHXY_URL = "https://oshall.chnu.edu.cn/zhxy-new"
    const val JWXT_BASE_URL = "https://jwglxt.chnu.edu.cn"
    const val ECARD_BASE_URL = "http://210.45.128.224:8081/easytong_portal"
    const val SC_BASE_URL = "https://sc.chnu.edu.cn"
    const val SC_API_BASE = "https://sc.chnu.edu.cn/hbsdDkApi"
    const val HSXS_BASE_URL = "https://hsxs.chnu.edu.cn"
    const val HSXS_API_BASE = "https://hsxs.chnu.edu.cn/api"
    const val TIMEOUT_SECONDS = 20L

    /** 默认 User-Agent(Chrome) */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** AES 加密参数(硬编码,来自 auth.chnu.edu.cn 主题 common.js) */
    const val AES_KEY = "1234567890adbcde"
    const val AES_IV = "1234567890hjlkew"

    /**
     * 根据当前日期推算学年学期。
     * 9月~次年1月:第一学期;2月~7月:第二学期;8月归第一学期。
     * @return (学年, 学期),如 ("2024-2025", "1")
     */
    fun currentXnXq(): Pair<String, String> {
        val now = LocalDate.now()
        val year = now.year
        val month = now.monthValue
        return if (month >= 9) "${year}-${year + 1}" to "1"
        else if (month >= 2) "${year - 1}-${year}" to "2"
        else "${year - 1}-${year}" to "1"
    }
}

// ==================== 异常 ====================

/** 校园系统异常(登录失败/请求失败/会话过期等) */
class CampusException(message: String) : Exception(message)

// ==================== CookieJar ====================

/**
 * 简单的内存 CookieJar(OkHttp 默认不持久化 cookie)。
 * 按 host 存储,供 CAS 与各子系统共享会话。
 */
class SimpleCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = this.cookies.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            // 移除同名旧 cookie 再添加新的(模拟浏览器覆盖语义)
            cookies.forEach { nc ->
                list.removeAll { it.name == nc.name && it.path == nc.path }
                list.add(nc)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = cookies[url.host] ?: return emptyList()
        synchronized(list) {
            // 过滤已过期 cookie
            val now = System.currentTimeMillis()
            return list.filter { it.expiresAt >= now }
        }
    }
}

// ==================== HttpUrl 扩展工具 ====================

/** 将 String 转为 HttpUrl,非法时抛 CampusException */
internal fun String.toHttpUrl(): HttpUrl =
    this.toHttpUrlOrNull() ?: throw CampusException("无效的 URL: $this")
