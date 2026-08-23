package go.examate.hyp.campus.ui

import android.content.Context
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.config.MyConfig
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.ApiResult
import go.examate.hyp.util.HttpUtils
import go.examate.hyp.widget.ScheduleWidgetProvider
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 从文字/图片中提取待办(借鉴 Cresto 的 AiTodoExtractor)。
 *
 * 复用项目现有 Vision 能力(HttpUtils.sendImagesRequestResult)与纯文本能力
 * (HttpUtils.sendPostRequestResult),输出 JSON 数组,本地解析后批量入库。
 */
object TodoAiExtractor {

    /** AI 提取结果项(与 ScheduleTodo 对齐,仅保留可提取字段) */
    data class ExtractedTodo(
        val title: String,
        val date: String,        // yyyy-MM-dd
        val time: String = "",   // HH:mm,可为空
        val priority: Int = 0    // 0 无 / 1 低 / 2 中 / 3 高
    )

    /** 系统提示词(借鉴 Cresto,适配本项目字段:title/date/time/priority) */
    private fun systemPrompt(): String {
        val today = LocalDate.now().toString()
        return """现在时间为 $today。你是一个信息提取AI助手。你的任务是分析我发送给你的文本或图片内容,并严格按照以下要求提取待办事项信息,最后以指定的JSON格式返回。

提取规则:
1. 识别待办事项:从内容中找出所有独立的待办事项。
2. 提取title(待办事项名称):准确提取每个待办事项的内容作为title,包含完整信息。不要在title中包含具体时间,具体时间应提取到time字段。
3. 提取date(日期):提取每个待办事项对应的日期,必须格式化为 yyyy-MM-dd。若内容未明确日期,使用 $today。
4. 提取time(时间,可选):若原文明确提及具体时间点(如"14:30"),提取为 time,格式 HH:mm。无则留空。
5. 提取priority(优先级,可选):根据语义判断优先级,0=无,1=低,2=中,3=高。紧急/重要=3,普通=2,轻微=1,无法判断=0。
6. 如果无法提取任何待办事项,返回 {"quantity":0,"items":[]}。

输出格式要求:
- 返回结果必须是一个结构完整的JSON对象。
- 最外层包含 quantity 字段(待办事项总数)和 items 数组。
- 每个 item 包含 title(必填)、date(必填)、time(可选)、priority(可选)。
- 仅返回JSON,不要包含任何额外解释或文字。

示例:
{"quantity":2,"items":[{"title":"提交实验报告","date":"$today","time":"14:30","priority":2},{"title":"买文具","date":"$today","priority":1}]}"""
    }

    /** 从文本提取 */
    suspend fun extractFromText(context: Context, text: String): List<ExtractedTodo> {
        val provider = pickProvider(context, needVision = false)
            ?: return emptyList()
        val result = HttpUtils.sendPostRequestResult(
            provider.requestUrl, provider.modelId, text,
            provider.apiKey, systemPrompt(), false, provider.endpointType
        )
        return parseResult(result, provider.endpointType)
    }

    /** 从图片提取(可附带文字说明) */
    suspend fun extractFromImage(
        context: Context, images: List<ByteArray>, userText: String = ""
    ): List<ExtractedTodo> {
        val provider = pickProvider(context, needVision = true)
            ?: return emptyList()
        val prompt = userText.ifBlank { "请提取图片中的待办事项" }
        val result = HttpUtils.sendImagesRequestResult(
            provider.requestUrl, provider.modelId, images,
            provider.apiKey, systemPrompt(), prompt, provider.endpointType
        )
        return parseResult(result, provider.endpointType)
    }

    /** 选择可用 provider:优先 vision 能力,其次任意有 key 的 */
    private fun pickProvider(context: Context, needVision: Boolean): ModelProvider? {
        val providers = MyConfig.loadProviders(context).filter { it.visible && !it.apiKey.isNullOrBlank() }
        if (needVision) {
            return providers.firstOrNull { it.hasCapability(ModelProvider.CAP_VISION) }
                ?: providers.firstOrNull()
        }
        return providers.firstOrNull()
    }

    /** 解析 AI 响应为 ExtractedTodo 列表 */
    private fun parseResult(result: ApiResult<String>, endpointType: String): List<ExtractedTodo> {
        val raw = (result as? ApiResult.Success)?.data ?: return emptyList()
        val content = HttpUtils.extractContent(raw, endpointType).ifBlank { return emptyList() }
        val cleaned = cleanJson(content)
        return try {
            val root = JSONObject(cleaned)
            val items = root.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                ExtractedTodo(
                    title = title,
                    date = parseDate(o.optString("date")),
                    time = o.optString("time").trim(),
                    priority = o.optInt("priority", 0).coerceIn(0, 3)
                )
            }
        } catch (_: Exception) {
            // 尝试直接当数组解析
            try {
                val arr = JSONArray(cleaned)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = o.optString("title").trim()
                    if (title.isEmpty()) return@mapNotNull null
                    ExtractedTodo(
                        title = title,
                        date = parseDate(o.optString("date")),
                        time = o.optString("time").trim(),
                        priority = o.optInt("priority", 0).coerceIn(0, 3)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** 日期解析:支持 yyyy-MM-dd / 昨天 / 今天 / 明天 / 后天,失败回退今天 */
    private fun parseDate(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return LocalDate.now().toString()
        return try {
            LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE).toString()
        } catch (_: Exception) {
            val today = LocalDate.now()
            when (t) {
                "昨天", "昨日", "yesterday" -> today.minusDays(1).toString()
                "今天", "今日", "today" -> today.toString()
                "明天", "明日", "tomorrow" -> today.plusDays(1).toString()
                "后天" -> today.plusDays(2).toString()
                else -> today.toString()
            }
        }
    }

    /** 去除 markdown 代码块包裹 */
    private fun cleanJson(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end).trim()
        }
        return t
    }

    /** 批量写入待办(返回写入数量) */
    fun saveExtractedTodos(context: Context, items: List<ExtractedTodo>): Int {
        if (items.isEmpty()) return 0
        items.forEach { item ->
            ScheduleStorage.addTodo(
                context,
                ScheduleTodo(
                    id = "ai_${System.currentTimeMillis()}_${item.hashCode()}",
                    title = item.title,
                    date = item.date,
                    time = item.time,
                    done = false,
                    priority = item.priority
                )
            )
        }
        ScheduleWidgetProvider.refreshAll(context)
        return items.size
    }
}
