package go.examate.hyp.config

import android.content.Context
import go.examate.hyp.entity.ModelProvider
import org.json.JSONArray
import org.json.JSONException

object MyConfig {

    const val SYSTEM_PROMPT =
        "你是一个AI解题助手，请严格按以下格式回答：\n\n" +
        "【答案】直接给出答案（选择题只写选项字母，填空题只写答案，问答题简要作答）\n\n" +
        "【解析】用1-3句话简要说明理由或解题思路\n\n" +
        "规则：\n" +
        "1. 答案必须简短明确，选择题只输出如 A / B / C / D\n" +
        "2. 解析要精炼，不要长篇大论\n" +
        "3. 如需引用教材内容，参考高等教育出版社思政类教材\n" +
        "4. 不要输出与答案解析无关的内容"

    /** AI聊天页专用:助手角色,自然对话,不使用解题格式 */
    const val CHAT_SYSTEM_PROMPT =
        "你是一个友好的AI助手，可以和用户聊天、讨论学习问题、解答疑问。\n\n" +
        "规则：\n" +
        "1. 自然地对话，不要使用【答案】【解析】这种格式\n" +
        "2. 回答简洁有条理，长内容用分段或列表组织\n" +
        "3. 如果用户发了图片，请描述图片内容并回应\n" +
        "4. 保持友善、有帮助的语气"

    private const val PREFS_NAME = "ApiConfig"
    private const val KEY_PROVIDERS = "providers"

    @JvmStatic
    fun getDefaultProviders(): List<ModelProvider> {
        val list = mutableListOf<ModelProvider>()
        // 稳定
        list.add(ModelProvider("DeepSeek", "deepseek-v4-flash",
            "https://api.deepseek.com/v1/chat/completions", "", false,
            "https://platform.deepseek.com/api_keys", "稳定"))
        list.add(ModelProvider("Kimi", "kimi-k2.6",
            "https://api.moonshot.cn/v1/chat/completions", "", false,
            "https://platform.moonshot.cn/console/api-keys", "稳定"))
        list.add(ModelProvider("小米MiMo", "mimo-v2.5-pro",
            "https://api.xiaomimimo.com/v1/chat/completions", "", false,
            "https://platform.xiaomimimo.com/#/console/api-keys", "稳定", "填写邀请码 8GCZ67，双方均可获得10元体验金"))
        // 免费
        list.add(ModelProvider("商汤SenseNova", "sensenova-6.7-flash-lite",
            "https://token.sensenova.cn/v1/chat/completions", "", false,
            "https://platform.sensenova.cn/console/keys", "免费"))
        list.add(ModelProvider("幻城网安", "auto",
            "https://api.iamhc.cn/v1/chat/completions", "", false,
            "https://api.iamhc.cn/register?aff=HP9n", "免费"))
        return list
    }

    @JvmStatic
    fun loadProviders(context: Context): List<ModelProvider> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROVIDERS, null)
        if (json == null) {
            return getDefaultProviders()
        }
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<ModelProvider>()
            for (i in 0 until arr.length()) {
                list.add(ModelProvider.fromJson(arr.getJSONObject(i)))
            }
            if (list.isEmpty()) getDefaultProviders() else list
        } catch (e: JSONException) {
            getDefaultProviders()
        }
    }

    @JvmStatic
    fun saveProviders(context: Context, providers: List<ModelProvider>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (p in providers) {
            arr.put(p.toJson())
        }
        prefs.edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    /** 返回具备指定能力([capability])且可用(可见、有 Key)的模型列表 */
    @JvmStatic
    fun providersFor(context: Context, capability: String): List<ModelProvider> =
        loadProviders(context).filter { it.visible && it.apiKey.isNotEmpty() && it.hasCapability(capability) }
}
