package go.examate.hyp.util

/**
 * 统一 API 结果封装
 *
 * 用于替代各处返回 null 表示失败的旧模式,让错误类型可区分、可处理。
 * Repository / HttpUtils 层产出 ApiResult,UI 层据类型展示对应提示。
 */
sealed class ApiResult<out T> {
    /** 成功,携带数据 */
    data class Success<T>(val data: T) : ApiResult<T>()

    /** 网络错误(连接失败/超时/DNS) */
    data class NetworkError(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()

    /** 认证失败(401/403/API Key 无效) */
    data class AuthError(val message: String = "认证失败,请检查 API Key") : ApiResult<Nothing>()

    /** 服务器错误(5xx) */
    data class ServerError(val code: Int, val message: String) : ApiResult<Nothing>()

    /** 解析错误(响应非预期格式) */
    data class ParseError(val message: String, val raw: String? = null) : ApiResult<Nothing>()

    /** 其他错误 */
    data class OtherError(val message: String, val cause: Throwable? = null) : ApiResult<Nothing>()

    /** 是否成功 */
    val isSuccess: Boolean get() = this is Success

    /** 成功数据,失败返回 null */
    fun getOrNull(): T? = (this as? Success)?.data

    /** 错误消息(非 Success 时) */
    fun errorMessage(): String = when (this) {
        is Success -> ""
        is NetworkError -> message
        is AuthError -> message
        is ServerError -> "服务器错误($code): $message"
        is ParseError -> message
        is OtherError -> message
    }

    /** 成功则执行 block,失败返回自身 */
    inline fun <R> map(block: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(block(data))
        is NetworkError -> this
        is AuthError -> this
        is ServerError -> this
        is ParseError -> this
        is OtherError -> this
    }

    /** 成功则执行 block 返回新 ApiResult,失败返回自身 */
    inline fun <R> flatMap(block: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
        is Success -> block(data)
        is NetworkError -> this
        is AuthError -> this
        is ServerError -> this
        is ParseError -> this
        is OtherError -> this
    }

    companion object {
        /** 从异常推断 ApiResult 类型 */
        fun fromException(e: Throwable): ApiResult<Nothing> = when (e) {
            is java.net.UnknownHostException -> NetworkError("无法解析服务器域名,请检查网络", e)
            is java.net.SocketException -> NetworkError("连接失败,请检查网络", e)
            is java.net.SocketTimeoutException -> NetworkError("请求超时,请稍后重试", e)
            is java.io.InterruptedIOException -> NetworkError("请求被中断", e)
            is org.json.JSONException -> ParseError(e.message ?: "JSON 解析失败")
            else -> OtherError(e.message ?: "未知错误", e)
        }

        /** 从 HTTP 状态码推断 ApiResult */
        fun fromHttpError(code: Int, body: String?): ApiResult<Nothing> = when (code) {
            401, 403 -> AuthError()
            in 500..599 -> ServerError(code, body?.take(200) ?: "服务器内部错误")
            else -> OtherError("HTTP $code: ${body?.take(200) ?: "未知错误"}")
        }
    }
}
