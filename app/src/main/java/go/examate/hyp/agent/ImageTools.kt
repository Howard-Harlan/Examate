package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import go.examate.hyp.config.MyConfig
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.HttpUtils
import org.json.JSONObject

/**
 * 生图工具 — 从 AgentTools 迁移
 */
object ImageTools {

    private const val TAG = "ImageTools"

    val GenerateImage = object : ToolRegistry.Tool {
        override val name = "generate_image"
        override val description = "根据文字描述生成图片"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "prompt(图片描述,必填), model(模型ID,可选,默认dall-e-3)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val prompt = args.optString("prompt", "").ifEmpty { return ToolRegistry.Result(false, "生图失败: 缺少 prompt 参数") }
            val model = args.optString("model", "dall-e-3")
            val imageData = generateImage(context, prompt, model)
            return if (imageData != null) ToolRegistry.Result(true, "图片已生成", imageData, true)
            else ToolRegistry.Result(false, "生图失败,请检查 API 配置或网络")
        }
    }

    /** 调用生图 API(OpenAI /v1/images/generations 格式) */
    private fun generateImage(context: Context, prompt: String, model: String): String? {
        val providers = MyConfig.loadProviders(context)
        val provider = providers.firstOrNull { it.apiKey.isNotEmpty() } ?: return null
        val baseUrl = ModelProvider.extractBaseUrl(provider.requestUrl)
        val url = "$baseUrl/v1/images/generations"

        val json = JSONObject()
        json.put("model", model)
        json.put("prompt", prompt)
        json.put("n", 1)
        json.put("size", "1024x1024")
        json.put("response_format", "b64_json")

        val response = HttpUtils.sendImageGenerationSync(url, json.toString(), provider.apiKey, "chat")
            ?: return null

        return try {
            val respJson = JSONObject(response)
            val data = respJson.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val b64 = data.getJSONObject(0).optString("b64_json", "")
                if (b64.isNotEmpty()) return b64
                val imgUrl = data.getJSONObject(0).optString("url", "")
                if (imgUrl.isNotEmpty()) return imgUrl
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "generateImage parse failed", e)
            null
        }
    }
}
