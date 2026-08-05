package go.examate.hyp.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 从厂商的 /v1/models 接口同步可用模型列表。
 * OpenAI 兼容 API 标准均支持 GET /v1/models，返回格式：
 * { "data": [ { "id": "model-name", "object": "model", ... }, ... ] }
 */
object ModelSyncUtil {

    private const val TAG = "ModelSync"

    /**
     * 同步回调接口。
     * 注：该接口包含两个抽象方法（onSuccess / onError），
     * 不满足 Kotlin fun interface 单抽象方法的约束，故声明为普通 interface。
     */
    interface SyncCallback {
        fun onSuccess(modelIds: List<String>)
        fun onError(message: String)
    }

    /**
     * 用于 fetchModels 的私有作用域,SupervisorJob 保证单个请求失败不会影响其他请求。
     * 通过 [cancelScope] 在不需要时(如 App 退出)取消所有进行中的请求。
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 从厂商 API 地址推导出 /v1/models 端点并请求。
     * @param requestUrl 厂商的 chat completions 地址，如 https://api.deepseek.com/v1/chat/completions
     * @param apiKey     API Key
     * @param callback   回调
     */
    @JvmStatic
    fun fetchModels(requestUrl: String?, apiKey: String?, callback: SyncCallback) {
        // 检查 API Key
        if (apiKey.isNullOrBlank()) {
            callback.onError("请先填写 API Key，同步模型需要身份验证")
            return
        }

        val modelsUrl = deriveModelsUrl(requestUrl)
        Log.d(TAG, "Fetching models from: $modelsUrl")

        // 使用受管理的协程作用域,避免 runBlocking;回调切换到主线程由调用方处理
        fetchScope.launch {
            val result = HttpUtils.fetchModelsResult(modelsUrl, apiKey)
            when (result) {
                is ApiResult.Success -> {
                    val models = parseModelsResponse(result.data)
                    Log.d(TAG, "Fetched ${models.size} models")
                    callback.onSuccess(models)
                }
                is ApiResult.AuthError -> {
                    Log.e(TAG, "Auth failed: ${result.message}")
                    callback.onError("认证失败，请检查 API Key 是否正确")
                }
                else -> {
                    Log.e(TAG, "Fetch failed: ${result.errorMessage()}")
                    callback.onError("请求失败: ${result.errorMessage()}")
                }
            }
        }
    }

    /**
     * 取消所有进行中的模型同步请求(可在 App 退出时调用)。
     */
    @JvmStatic
    fun cancelScope() {
        fetchScope.cancel()
    }

    /**
     * 从 chat completions URL 推导 /v1/models URL。
     * 示例:
     *   https://api.deepseek.com/v1/chat/completions → https://api.deepseek.com/v1/models
     *   https://api.openai.com/v1/chat/completions → https://api.openai.com/v1/models
     *   https://open.bigmodel.cn/api/paas/v4/chat/completions → https://open.bigmodel.cn/api/paas/v4/models
     */
    @JvmStatic
    fun deriveModelsUrl(requestUrl: String?): String {
        if (requestUrl.isNullOrEmpty()) return ""

        var url = requestUrl.trim()
        // 去掉末尾斜杠
        while (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }

        // 如果以 /chat/completions 结尾，替换为 /models
        if (url.endsWith("/chat/completions")) {
            return url.substring(0, url.length - "/chat/completions".length) + "/models"
        }
        // 如果以 /completions 结尾，替换为 /models
        if (url.endsWith("/completions")) {
            return url.substring(0, url.length - "/completions".length) + "/models"
        }
        // 如果以 /v1 或 /v4 等版本号结尾，追加 /models
        if (url.matches(Regex(".*\\/v\\d+$"))) {
            return "$url/models"
        }
        // 兜底：直接追加 /v1/models
        return "$url/v1/models"
    }

    /**
     * 解析 /v1/models 的 JSON 响应，提取模型 ID 列表并排序。
     */
    @JvmStatic
    fun parseModelsResponse(json: String): List<String> {
        val models = mutableListOf<String>()
        try {
            val root = JSONObject(json)
            val data = root.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val id = item.optString("id", "")
                    if (id.isNotEmpty()) {
                        models.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
        models.sort()
        return models
    }
}
