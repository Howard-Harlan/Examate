package go.examate.hyp.entity

import org.json.JSONArray
import org.json.JSONObject

data class ModelProvider @JvmOverloads constructor(
    var displayName: String,
    var modelId: String,
    var requestUrl: String,
    var apiKey: String,
    var enableThinking: Boolean = false,
    var registerUrl: String = "",
    var tag: String = "",
    var inviteCode: String = "",
    var endpointType: String = inferEndpointType(requestUrl),
    var label: String = "",
    var barColor: String = "",
    var visible: Boolean = true,
    /** 模型能力标签:chat/vision/image_gen/video_gen,用于多模态按需路由 */
    var capabilities: Set<String> = setOf(CAP_CHAT),
    /** 生图接口地址覆盖(空 = 从聊天地址推导 baseUrl + /v1/images/generations) */
    var imageGenUrl: String = "",
    /** 生图模型 id 覆盖(空 = 用 modelId) */
    var imageGenModel: String = ""
) {
    /** 是否具备某项能力 */
    fun hasCapability(cap: String): Boolean = capabilities.contains(cap)

    init {
        if (endpointType.isEmpty()) {
            this.endpointType = inferEndpointType(requestUrl)
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("displayName", displayName)
        json.put("modelId", modelId)
        json.put("requestUrl", requestUrl)
        json.put("apiKey", apiKey)
        json.put("enableThinking", enableThinking)
        json.put("registerUrl", registerUrl)
        json.put("tag", tag)
        json.put("inviteCode", inviteCode)
        json.put("endpointType", endpointType)
        json.put("label", label)
        json.put("barColor", barColor)
        json.put("visible", visible)
        json.put("capabilities", JSONArray(capabilities.toList()))
        json.put("imageGenUrl", imageGenUrl)
        json.put("imageGenModel", imageGenModel)
        return json
    }

    companion object {
        /** 模型能力标签常量 */
        const val CAP_CHAT = "chat"
        const val CAP_VISION = "vision"
        const val CAP_IMAGE_GEN = "image_gen"
        const val CAP_VIDEO_GEN = "video_gen"

        /** 根据 URL 推断 endpoint 类型:responses / anthropic / chat */
        @JvmStatic
        fun inferEndpointType(url: String?): String {
            if (url == null) return "chat"
            return when {
                url.contains("/v1/responses") -> "responses"
                url.contains("/v1/messages") -> "anthropic"
                else -> "chat"
            }
        }

        /** 从完整 URL 提取 baseUrl(到 /v1 之前,不含 /v1) */
        @JvmStatic
        fun extractBaseUrl(fullUrl: String?): String {
            if (fullUrl.isNullOrEmpty()) return ""
            val idx = fullUrl.indexOf("/v1")
            return if (idx != -1) fullUrl.substring(0, idx) else fullUrl
        }

        /** 从完整 URL 提取 endpoint(/v1 及之后部分) */
        @JvmStatic
        fun extractEndpoint(fullUrl: String?): String {
            if (fullUrl.isNullOrEmpty()) return "/v1/chat/completions"
            val idx = fullUrl.indexOf("/v1")
            if (idx == -1) return "/v1/chat/completions"
            val ep = fullUrl.substring(idx)
            return if (ep.isEmpty()) "/v1/chat/completions" else ep
        }

        /** 根据 baseUrl + endpointType 拼接完整 URL */
        @JvmStatic
        fun buildFullUrl(baseUrl: String?, endpointType: String?): String {
            if (baseUrl.isNullOrEmpty()) return ""
            val base = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
            return when (endpointType) {
                "responses" -> "$base/v1/responses"
                "anthropic" -> "$base/v1/messages"
                else -> "$base/v1/chat/completions"
            }
        }

        @JvmStatic
        fun fromJson(json: JSONObject): ModelProvider {
            val url = json.optString("requestUrl", "")
            var endpointType = json.optString("endpointType", "")
            if (endpointType.isEmpty()) {
                endpointType = inferEndpointType(url)
            }
            return ModelProvider(
                json.optString("displayName", ""),
                json.optString("modelId", ""),
                url,
                json.optString("apiKey", ""),
                json.optBoolean("enableThinking", false),
                json.optString("registerUrl", ""),
                json.optString("tag", ""),
                json.optString("inviteCode", ""),
                endpointType,
                json.optString("label", ""),
                json.optString("barColor", ""),
                json.optBoolean("visible", true),
                parseCapabilities(json),
                json.optString("imageGenUrl", ""),
                json.optString("imageGenModel", "")
            )
        }

        /** 解析能力标签数组;缺失或为空时默认仅聊天(向后兼容旧配置) */
        private fun parseCapabilities(json: JSONObject): Set<String> {
            val arr = json.optJSONArray("capabilities") ?: return setOf(CAP_CHAT)
            val set = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
            return if (set.isEmpty()) setOf(CAP_CHAT) else set
        }
    }
}
