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
        override val parametersHint = "prompt(图片描述,必填), model(模型ID,可选,默认用已配置的生图模型)"
        override val params = listOf(
            ToolRegistry.ParamDef("prompt", "string", "图片描述", true),
            ToolRegistry.ParamDef("model", "string", "模型ID,可选,默认 dall-e-3", false)
        )

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val prompt = args.optString("prompt", "").ifEmpty { return ToolRegistry.Result(false, "生图失败: 缺少 prompt 参数") }
            val model = args.optString("model", "")
            val imageData = generateImage(context, prompt, model)
            return if (imageData != null) ToolRegistry.Result(true, "图片已生成", imageData, true)
            else ToolRegistry.Result(false, "生图失败,请检查 API 配置或网络")
        }
    }

    /**
     * 生图公共入口(工具调用与聊天「生图模式」共用)。
     * @return base64 图片数据或图片直链 URL;失败返回 null
     */
    fun generate(context: Context, prompt: String): String? = generateImage(context, prompt, "")

    /** 调用生图 API(OpenAI /v1/images/generations 格式);地址/模型可用专属覆盖 */
    private fun generateImage(context: Context, prompt: String, model: String): String? {
        // 优先路由到标记「生图」能力的模型;未标记时退回首个有 Key 的厂商(向后兼容)
        val provider = MyConfig.providersFor(context, ModelProvider.CAP_IMAGE_GEN).firstOrNull()
            ?: MyConfig.loadProviders(context).firstOrNull { it.apiKey.isNotEmpty() }
            ?: return null
        // 生图接口地址:厂商配置了覆盖地址则直接用,否则从聊天地址推导
        val url = provider.imageGenUrl.ifBlank {
            ModelProvider.extractBaseUrl(provider.requestUrl) + "/v1/images/generations"
        }
        // 生图模型:调用参数 > 厂商生图覆盖 > 当前模型 ID > dall-e-3
        val useModel = model.ifEmpty {
            provider.imageGenModel.ifBlank { provider.modelId.ifEmpty { "dall-e-3" } }
        }

        val json = JSONObject()
        json.put("model", useModel)
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
