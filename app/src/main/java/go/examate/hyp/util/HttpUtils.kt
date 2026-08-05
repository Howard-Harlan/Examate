package go.examate.hyp.util

import android.os.Handler
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import go.examate.hyp.service.UnifiedFloatingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class HttpUtils {

    /**
     * 流式 SSE 事件
     * 将流式解析与 UI 更新解耦:Flow 只产出事件,由调用方决定如何展示
     *
     * 注意:作为 HttpUtils 的直接嵌套类(非 companion object 内部),
     * 以便外部文件可通过 `import go.examate.hyp.util.HttpUtils.SseEvent` 引用。
     */
    sealed class SseEvent {
        data class Token(val text: String) : SseEvent()           // 正常输出
        data class ThinkToken(val text: String) : SseEvent()      // 思考过程
        data class Error(val message: String) : SseEvent()        // 错误
        /** 流式中跨分片累积完成的原生工具调用(在 Done 之前发出一次) */
        data class ToolCalls(val calls: List<go.examate.hyp.agent.NativeToolCall>) : SseEvent()
        object Done : SseEvent()                                  // 流式结束
    }

    companion object {

        private const val TAG = "HttpUtils"

        /** 共享 OkHttpClient(连接池复用,统一超时配置) */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** 流式请求专用 client(长超时) */
        private val streamClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 通用 HTTP 请求(供自定义声明式工具使用)。同步执行,返回响应体文本;失败返回 null。
         * GET/DELETE 不带 body;其余方法以 JSON body 发送。
         */
        fun customRequest(method: String, url: String, headers: Map<String, String>, body: String?): String? {
            return try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                when (method.uppercase()) {
                    "GET", "DELETE", "HEAD" -> builder.method(method.uppercase(), null)
                    else -> builder.method(method.uppercase(), (body ?: "").toRequestBody(JSON_MEDIA_TYPE))
                }
                httpClient.newCall(builder.build()).execute().use { resp -> resp.body?.string() }
            } catch (e: Exception) {
                Log.e(TAG, "customRequest failed: ${e.message}")
                null
            }
        }

        // ════════════════════════════════════════════════════
        //  请求体构建(chat / responses / anthropic 三套格式)
        // ════════════════════════════════════════════════════

        // 构建RequestBody(chat/completions 格式)
        private fun JsonString(modelName: String, userInput: String, isStream: Boolean, systemPrompt: String, enableThinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", modelName)
            json.put("stream", isStream)
            if (enableThinking == true) json.put("enable_thinking", true)

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.put(JSONObject().put("role", "user").put("content", userInput))

            json.put("messages", messages)
            return json.toString()
        }

        // 构建 /v1/responses 格式的 RequestBody
        private fun JsonStringResponses(modelName: String, userInput: String, systemPrompt: String, enableThinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", modelName)
            val input = JSONArray()
            input.put(JSONObject().put("role", "system").put("content", systemPrompt))
            input.put(JSONObject().put("role", "user").put("content", userInput))
            json.put("input", input)
            if (enableThinking == true) {
                json.put("reasoning", JSONObject().put("effort", "medium"))
            }
            return json.toString()
        }

        // 构建 Anthropic /v1/messages 格式的 RequestBody
        private fun JsonStringAnthropic(modelName: String, userInput: String, systemPrompt: String, enableThinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", modelName)
            json.put("max_tokens", 4096)
            json.put("system", systemPrompt)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "user").put("content", userInput))
            json.put("messages", messages)
            if (enableThinking == true) {
                json.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
            }
            return json.toString()
        }

        // ════════════════════════════════════════════════════
        //  统一鉴权头构建
        // ════════════════════════════════════════════════════

        private fun Request.Builder.applyAuth(endpointType: String, apiKey: String?): Request.Builder = apply {
            if (endpointType == "anthropic") {
                apiKey?.let { header("x-api-key", it) }
                header("anthropic-version", "2023-06-01")
            } else {
                apiKey?.let { header("Authorization", "Bearer $it") }
            }
        }

        // ════════════════════════════════════════════════════
        //  非流式请求(旧签名保留,内部委托 OkHttp;返回 ApiResult 的新 suspend 版本)
        // ════════════════════════════════════════════════════

        // 发起HTTP请求(自动判断 endpoint 类型)
        @JvmStatic
        fun sendPostRequest(url: String, modelName: String, userInput: String, apiKey: String?, systemPrompt: String, thinking: Boolean?): String? {
            return sendPostRequest(url, modelName, userInput, apiKey, systemPrompt, thinking, "chat")
        }

        // 发起HTTP请求(指定 endpoint 类型)
        @JvmStatic
        fun sendPostRequest(url: String, modelName: String, userInput: String, apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String): String? {
            return try {
                sendPostRequestSync(url, modelName, userInput, apiKey, systemPrompt, thinking, endpointType)
            } catch (e: Exception) {
                Log.e(TAG, "sendPostRequest failed", e)
                null
            }
        }

        /** 同步版本(内部用 OkHttp),抛异常供调用方捕获 */
        @JvmStatic
        fun sendPostRequestSync(url: String, modelName: String, userInput: String, apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String): String {
            val body = when (endpointType) {
                "responses" -> JsonStringResponses(modelName, userInput, systemPrompt, thinking)
                "anthropic" -> JsonStringAnthropic(modelName, userInput, systemPrompt, thinking)
                else -> JsonString(modelName, userInput, false, systemPrompt, thinking)
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    return resp.body?.string() ?: ""
                } else {
                    val errBody = resp.body?.string()
                    Log.e(TAG, "request error: ${resp.code} $errBody")
                    throw IOException("HTTP ${resp.code}: ${errBody?.take(200)}")
                }
            }
        }

        /** suspend 版本:返回 ApiResult,推荐新代码使用 */
        @JvmStatic
        suspend fun sendPostRequestResult(
            url: String, modelName: String, userInput: String,
            apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String
        ): ApiResult<String> = withContext(Dispatchers.IO) {
            try {
                ApiResult.Success(sendPostRequestSync(url, modelName, userInput, apiKey, systemPrompt, thinking, endpointType))
            } catch (e: Exception) {
                ApiResult.fromException(e)
            }
        }

        // ════════════════════════════════════════════════════
        //  带对话历史的请求(Agent 模式用)
        // ════════════════════════════════════════════════════

        /**
         * 发起带对话历史的 chat 请求(Agent 模式用)
         * @param messages 对话历史列表,每项为 (role, content),role 为 "user"/"assistant"
         * @param systemPrompt 系统提示词(包含工具描述)
         */
        @JvmStatic
        fun sendChatWithHistory(
            url: String, modelName: String, messages: List<Pair<String, String>>,
            apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String
        ): String? {
            return try {
                sendChatWithHistorySync(url, modelName, messages, apiKey, systemPrompt, thinking, endpointType)
            } catch (e: Exception) {
                Log.e(TAG, "sendChatWithHistory failed", e)
                null
            }
        }

        /** 同步版本,抛异常 */
        @JvmStatic
        fun sendChatWithHistorySync(
            url: String, modelName: String, messages: List<Pair<String, String>>,
            apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String
        ): String {
            val body = when (endpointType) {
                "anthropic" -> buildAnthropicHistoryBody(modelName, messages, systemPrompt, thinking)
                "responses" -> buildResponsesHistoryBody(modelName, messages, systemPrompt, thinking)
                else -> buildChatHistoryBody(modelName, messages, systemPrompt, thinking)
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    return resp.body?.string() ?: ""
                } else {
                    val errBody = resp.body?.string()
                    Log.e(TAG, "chat history request error: ${resp.code} $errBody")
                    throw IOException("HTTP ${resp.code}: ${errBody?.take(200)}")
                }
            }
        }

        /** suspend 版本:返回 ApiResult */
        @JvmStatic
        suspend fun sendChatWithHistoryResult(
            url: String, modelName: String, messages: List<Pair<String, String>>,
            apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String
        ): ApiResult<String> = withContext(Dispatchers.IO) {
            try {
                ApiResult.Success(sendChatWithHistorySync(url, modelName, messages, apiKey, systemPrompt, thinking, endpointType))
            } catch (e: Exception) {
                ApiResult.fromException(e)
            }
        }

        // ════════════════════════════════════════════════════
        //  原生 function calling(OpenAI tools / Anthropic tool_use)
        // ════════════════════════════════════════════════════

        /**
         * 发送带 tools 的 chat 请求(原生 function calling)
         *
         * @param messages 消息列表(支持 FuncMessage.ToolResult)
         * @param tools tools JSON 数组字符串(由 ToolSchemaBuilder 构建),null 则不带
         */
        @JvmStatic
        suspend fun sendChatWithToolsResult(
            url: String, modelName: String,
            messages: List<go.examate.hyp.agent.FuncMessage>,
            apiKey: String?, systemPrompt: String,
            thinking: Boolean?, endpointType: String,
            tools: String?
        ): ApiResult<String> = withContext(Dispatchers.IO) {
            try {
                val body = when (endpointType) {
                    "anthropic" -> buildAnthropicToolsBody(modelName, messages, systemPrompt, thinking, tools)
                    "responses" -> buildResponsesHistoryBody(modelName, messages.map { 
                        when (it) {
                            is go.examate.hyp.agent.FuncMessage.Text -> it.role to it.content
                            is go.examate.hyp.agent.FuncMessage.AssistantToolCall -> "assistant" to it.content
                            is go.examate.hyp.agent.FuncMessage.ToolResult -> "user" to it.content
                        }
                    }, systemPrompt, thinking)
                    else -> buildChatToolsBody(modelName, messages, systemPrompt, thinking, tools)
                }
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .applyAuth(endpointType, apiKey)
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ApiResult.Success(resp.body?.string() ?: "")
                    } else {
                        val errBody = resp.body?.string()
                        Log.e(TAG, "tools request error: ${resp.code} $errBody")
                        ApiResult.ServerError(resp.code, errBody?.take(500) ?: "HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                ApiResult.fromException(e)
            }
        }

        /**
         * 从响应中提取文本 + 原生工具调用
         * @return ToolCallParseResult(text, toolCalls)
         */
        @JvmStatic
        fun extractNativeToolCalls(response: String?, endpointType: String): go.examate.hyp.agent.ToolCallParseResult {
            if (response.isNullOrEmpty()) return go.examate.hyp.agent.ToolCallParseResult("", emptyList())
            return try {
                val json = JSONObject(response)
                when (endpointType) {
                    "anthropic" -> parseAnthropicToolCalls(json)
                    "responses" -> {
                        // responses 格式不支持原生,返回纯文本
                        go.examate.hyp.agent.ToolCallParseResult(extractContent(response, endpointType), emptyList())
                    }
                    else -> parseOpenAIToolCalls(json)
                }
            } catch (e: JSONException) {
                Log.e(TAG, "extractNativeToolCalls failed", e)
                go.examate.hyp.agent.ToolCallParseResult(extractContent(response, endpointType), emptyList())
            }
        }

        /** 解析 OpenAI 格式的 tool_calls */
        private fun parseOpenAIToolCalls(json: JSONObject): go.examate.hyp.agent.ToolCallParseResult {
            val choices = json.optJSONArray("choices") ?: return go.examate.hyp.agent.ToolCallParseResult("", emptyList())
            if (choices.length() == 0) return go.examate.hyp.agent.ToolCallParseResult("", emptyList())

            val message = choices.getJSONObject(0).optJSONObject("message") ?: return go.examate.hyp.agent.ToolCallParseResult("", emptyList())
            val text = message.optString("content", "")
            val toolCallsArr = message.optJSONArray("tool_calls")

            val toolCalls = mutableListOf<go.examate.hyp.agent.NativeToolCall>()
            if (toolCallsArr != null) {
                for (i in 0 until toolCallsArr.length()) {
                    val tc = toolCallsArr.getJSONObject(i)
                    val id = tc.optString("id", "")
                    val func = tc.optJSONObject("function")
                    if (func != null) {
                        val name = func.optString("name", "")
                        val args = func.optString("arguments", "{}")
                        if (name.isNotEmpty()) {
                            toolCalls.add(go.examate.hyp.agent.NativeToolCall(id, name, args))
                        }
                    }
                }
            }
            return go.examate.hyp.agent.ToolCallParseResult(text, toolCalls)
        }

        /** 解析 Anthropic 格式的 tool_use */
        private fun parseAnthropicToolCalls(json: JSONObject): go.examate.hyp.agent.ToolCallParseResult {
            val contentArr = json.optJSONArray("content") ?: return go.examate.hyp.agent.ToolCallParseResult("", emptyList())
            val sb = StringBuilder()
            val toolCalls = mutableListOf<go.examate.hyp.agent.NativeToolCall>()

            for (i in 0 until contentArr.length()) {
                val block = contentArr.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> sb.append(block.optString("text", ""))
                    "tool_use" -> {
                        val id = block.optString("id", "")
                        val name = block.optString("name", "")
                        val input = block.optJSONObject("input")
                        val args = input?.toString() ?: "{}"
                        if (name.isNotEmpty()) {
                            toolCalls.add(go.examate.hyp.agent.NativeToolCall(id, name, args))
                        }
                    }
                }
            }
            return go.examate.hyp.agent.ToolCallParseResult(sb.toString(), toolCalls)
        }

        // ════════════════════════════════════════════════════
        //  原生 function calling 请求体构建
        // ════════════════════════════════════════════════════

        /** 构建 OpenAI chat 格式(带 tools)请求体 */
        private fun buildChatToolsBody(
            model: String,
            messages: List<go.examate.hyp.agent.FuncMessage>,
            systemPrompt: String,
            thinking: Boolean?,
            tools: String?
        ): String {
            val json = JSONObject()
            json.put("model", model)
            json.put("stream", false)
            if (thinking == true) json.put("enable_thinking", true)

            val msgArr = JSONArray()
            msgArr.put(JSONObject().put("role", "system").put("content", systemPrompt))

            messages.forEach { msg ->
                when (msg) {
                    is go.examate.hyp.agent.FuncMessage.Text -> {
                        msgArr.put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                    is go.examate.hyp.agent.FuncMessage.AssistantToolCall -> {
                        val obj = JSONObject().put("role", "assistant")
                        if (msg.content.isNotEmpty()) obj.put("content", msg.content)
                        else obj.put("content", JSONObject.NULL)
                        // 添加 tool_calls
                        val tcArr = JSONArray()
                        msg.toolCalls.forEach { tc ->
                            val tcObj = JSONObject()
                            tcObj.put("id", tc.id)
                            tcObj.put("type", "function")
                            tcObj.put("function", JSONObject()
                                .put("name", tc.name)
                                .put("arguments", tc.arguments))
                            tcArr.put(tcObj)
                        }
                        if (tcArr.length() > 0) obj.put("tool_calls", tcArr)
                        msgArr.put(obj)
                    }
                    is go.examate.hyp.agent.FuncMessage.ToolResult -> {
                        val obj = JSONObject()
                        obj.put("role", "tool")
                        obj.put("tool_call_id", msg.toolCallId)
                        obj.put("content", msg.content)
                        msgArr.put(obj)
                    }
                }
            }
            json.put("messages", msgArr)

            // 添加 tools 数组
            if (tools != null) {
                json.put("tools", JSONArray(tools))
            }

            return json.toString()
        }

        /** 构建 Anthropic 格式(带 tools)请求体 */
        private fun buildAnthropicToolsBody(
            model: String,
            messages: List<go.examate.hyp.agent.FuncMessage>,
            systemPrompt: String,
            thinking: Boolean?,
            tools: String?
        ): String {
            val json = JSONObject()
            json.put("model", model)
            json.put("max_tokens", 4096)
            json.put("system", systemPrompt)

            val msgArr = JSONArray()
            messages.forEach { msg ->
                when (msg) {
                    is go.examate.hyp.agent.FuncMessage.Text -> {
                        msgArr.put(JSONObject().put("role", msg.role).put("content", msg.content))
                    }
                    is go.examate.hyp.agent.FuncMessage.AssistantToolCall -> {
                        // assistant 消息:content 是数组,包含 text + tool_use blocks
                        val contentArr = JSONArray()
                        if (msg.content.isNotEmpty()) {
                            contentArr.put(JSONObject().put("type", "text").put("text", msg.content))
                        }
                        msg.toolCalls.forEach { tc ->
                            contentArr.put(JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("input", JSONObject(tc.arguments)))
                        }
                        msgArr.put(JSONObject().put("role", "assistant").put("content", contentArr))
                    }
                    is go.examate.hyp.agent.FuncMessage.ToolResult -> {
                        // tool_result 作为 user 消息的 content block
                        val contentArr = JSONArray()
                        contentArr.put(JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", msg.toolCallId)
                            .put("content", msg.content))
                        msgArr.put(JSONObject().put("role", "user").put("content", contentArr))
                    }
                }
            }
            json.put("messages", msgArr)

            if (thinking == true) {
                json.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
            }

            // 添加 tools 数组
            if (tools != null) {
                json.put("tools", JSONArray(tools))
            }

            return json.toString()
        }

        private fun buildChatHistoryBody(model: String, messages: List<Pair<String, String>>, systemPrompt: String, thinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", model)
            json.put("stream", false)
            if (thinking == true) json.put("enable_thinking", true)
            val msgArr = JSONArray()
            msgArr.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { (role, content) ->
                msgArr.put(JSONObject().put("role", role).put("content", content))
            }
            json.put("messages", msgArr)
            return json.toString()
        }

        private fun buildResponsesHistoryBody(model: String, messages: List<Pair<String, String>>, systemPrompt: String, thinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", model)
            val input = JSONArray()
            input.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { (role, content) ->
                input.put(JSONObject().put("role", role).put("content", content))
            }
            json.put("input", input)
            if (thinking == true) json.put("reasoning", JSONObject().put("effort", "medium"))
            return json.toString()
        }

        private fun buildAnthropicHistoryBody(model: String, messages: List<Pair<String, String>>, systemPrompt: String, thinking: Boolean?): String {
            val json = JSONObject()
            json.put("model", model)
            json.put("max_tokens", 4096)
            json.put("system", systemPrompt)
            val msgArr = JSONArray()
            messages.forEach { (role, content) ->
                msgArr.put(JSONObject().put("role", role).put("content", content))
            }
            json.put("messages", msgArr)
            if (thinking == true) json.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
            return json.toString()
        }

        /** 从响应中提取文本内容(兼容 chat/completions、/v1/responses、Anthropic 格式) */
        @JvmStatic
        fun extractContent(response: String?, endpointType: String): String {
            if (response.isNullOrEmpty()) return ""
            return try {
                val json = JSONObject(response)
                when (endpointType) {
                    "responses" -> {
                        if (json.has("output")) {
                            val output = json.getJSONArray("output")
                            buildString {
                                for (i in 0 until output.length()) {
                                    val item = output.getJSONObject(i)
                                    if (item.has("content")) {
                                        val content = item.getJSONArray("content")
                                        for (j in 0 until content.length()) {
                                            val c = content.getJSONObject(j)
                                            if (c.has("text")) {
                                                append(c.getString("text"))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (json.has("output_text")) {
                            json.getString("output_text")
                        } else {
                            ""
                        }
                    }
                    "anthropic" -> {
                        val content = json.getJSONArray("content")
                        buildString {
                            for (i in 0 until content.length()) {
                                val block = content.getJSONObject(i)
                                if (block.optString("type") == "text" && block.has("text")) {
                                    append(block.getString("text"))
                                }
                            }
                        }
                    }
                    else -> {
                        val choices = json.getJSONArray("choices")
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.getJSONObject("message")
                        message.getString("content")
                    }
                }
            } catch (e: JSONException) {
                Log.e(TAG, "extractContent failed", e)
                ""
            }
        }

        // ════════════════════════════════════════════════════
        //  图片(Vision)请求
        // ════════════════════════════════════════════════════

        /**
         * 发送截图给 AI 进行 OCR 识别并作答(支持 vision 多模态格式)
         */
        @JvmStatic
        fun sendImageRequest(url: String, modelName: String, imageBytes: ByteArray?, apiKey: String?, systemPrompt: String, endpointType: String): String? {
            if (imageBytes == null || imageBytes.isEmpty()) return null
            return try {
                // 单图 OCR(悬浮搜题):固定提示词
                sendImagesSync(url, modelName, listOf(imageBytes), apiKey, systemPrompt, "请识别图片中的题目并给出答案", endpointType)
            } catch (e: Exception) {
                Log.e(TAG, "sendImageRequest failed", e)
                null
            }
        }

        /** 多图 + 用户文本 的 vision 请求(聊天多模态);images 至少一张 */
        @JvmStatic
        suspend fun sendImagesRequestResult(
            url: String, modelName: String, images: List<ByteArray>,
            apiKey: String?, systemPrompt: String, userText: String, endpointType: String
        ): ApiResult<String> = withContext(Dispatchers.IO) {
            if (images.isEmpty()) return@withContext ApiResult.OtherError("图片数据为空")
            try {
                ApiResult.Success(sendImagesSync(url, modelName, images, apiKey, systemPrompt, userText, endpointType))
            } catch (e: Exception) {
                ApiResult.fromException(e)
            }
        }

        /** 嗅探图片 MIME(jpeg/png/webp),供 data URI 与 anthropic media_type 使用 */
        private fun sniffImageMime(b: ByteArray): String = when {
            b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> "image/jpeg"
            b.size >= 4 && (b[0].toInt() and 0xFF) == 0x89 && b[1].toInt() == 0x50 -> "image/png"
            b.size >= 12 && (b[8].toInt() and 0xFF) == 0x57 && (b[9].toInt() and 0xFF) == 0x45 -> "image/webp"
            else -> "image/png"
        }

        /** 同步:多图 vision 请求,userText 为空时回退到 OCR 提示词 */
        private fun sendImagesSync(
            url: String, modelName: String, images: List<ByteArray>,
            apiKey: String?, systemPrompt: String, userText: String, endpointType: String
        ): String {
            val prompt = userText.ifBlank { "请识别图片中的题目并给出答案" }
            val dataUris = images.map { "data:${sniffImageMime(it)};base64,${Base64.encodeToString(it, Base64.NO_WRAP)}" }
            val b64WithMime = images.map { Base64.encodeToString(it, Base64.NO_WRAP) to sniffImageMime(it) }
            val body = when (endpointType) {
                "responses" -> buildImagesBodyResponses(modelName, dataUris, systemPrompt, prompt)
                "anthropic" -> buildImagesBodyAnthropic(modelName, b64WithMime, systemPrompt, prompt)
                else -> buildImagesBodyChat(modelName, dataUris, systemPrompt, prompt)
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    return resp.body?.string() ?: ""
                } else {
                    val errBody = resp.body?.string()
                    Log.e(TAG, "image request error: ${resp.code} $errBody")
                    throw IOException("HTTP ${resp.code}: ${errBody?.take(200)}")
                }
            }
        }

        /** chat/completions 格式:userContent = text + 多个 image_url */
        private fun buildImagesBodyChat(modelName: String, dataUris: List<String>, systemPrompt: String, userText: String): String {
            val json = JSONObject().put("model", modelName).put("stream", false)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            val userContent = JSONArray()
            userContent.put(JSONObject().put("type", "text").put("text", userText))
            dataUris.forEach { uri ->
                userContent.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
            }
            messages.put(JSONObject().put("role", "user").put("content", userContent))
            return json.put("messages", messages).toString()
        }

        /** /v1/responses 格式:input_text + 多个 input_image */
        private fun buildImagesBodyResponses(modelName: String, dataUris: List<String>, systemPrompt: String, userText: String): String {
            val json = JSONObject().put("model", modelName)
            val input = JSONArray()
            input.put(JSONObject().put("role", "system").put("content", systemPrompt))
            val userContent = JSONArray()
            userContent.put(JSONObject().put("type", "input_text").put("text", userText))
            dataUris.forEach { uri ->
                userContent.put(JSONObject().put("type", "input_image").put("image_url", uri))
            }
            input.put(JSONObject().put("role", "user").put("content", userContent))
            return json.put("input", input).toString()
        }

        /** Anthropic /v1/messages 格式:text + 多个 image block(base64 + media_type) */
        private fun buildImagesBodyAnthropic(modelName: String, images: List<Pair<String, String>>, systemPrompt: String, userText: String): String {
            val json = JSONObject().put("model", modelName).put("max_tokens", 4096).put("system", systemPrompt)
            val messages = JSONArray()
            val userContent = JSONArray()
            userContent.put(JSONObject().put("type", "text").put("text", userText))
            images.forEach { (b64, mime) ->
                userContent.put(JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)))
            }
            messages.put(JSONObject().put("role", "user").put("content", userContent))
            return json.put("messages", messages).toString()
        }

        // ════════════════════════════════════════════════════
        //  图片生成(/v1/images/generations)与模型列表(/v1/models)
        // ════════════════════════════════════════════════════

        /**
         * 发送图片生成请求(OpenAI /v1/images/generations 格式)。
         * @param body 完整的 JSON 请求体字符串(由调用方构建)
         * @param apiKey API Key
         * @param endpointType 鉴权类型("chat"/"anthropic"/"responses")
         * @return 成功返回响应体字符串,失败返回 null
         */
        @JvmStatic
        fun sendImageGenerationSync(url: String, body: String, apiKey: String?, endpointType: String): String? {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            return try {
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendImageGeneration failed", e)
                null
            }
        }

        /**
         * 拉取 /v1/models 列表(GET 请求)。
         * @param url 模型列表端点 URL
         * @param apiKey API Key
         * @return ApiResult<String>，成功时 data 为响应体字符串
         */
        @JvmStatic
        suspend fun fetchModelsResult(url: String, apiKey: String?): ApiResult<String> = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .applyAuth("chat", apiKey)
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ApiResult.Success(resp.body?.string() ?: "")
                    } else {
                        val errBody = resp.body?.string()
                        Log.e(TAG, "fetchModels error: ${resp.code} $errBody")
                        ApiResult.fromHttpError(resp.code, errBody)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchModels failed", e)
                ApiResult.fromException(e)
            }
        }

        // ════════════════════════════════════════════════════
        //  流式请求(Flow 回调 + 旧签名兼容)
        // ════════════════════════════════════════════════════

        /**
         * 执行 SSE 流式请求的公共逻辑(请求 + 读流 + 解析 + 错误处理 + Done)
         * 供 sendPostRequestStreamFlow 和 sendChatWithHistoryStreamFlow 复用
         */
        private fun executeSseStream(request: Request, endpointType: String): Flow<SseEvent> = flow {
            try {
                streamClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.bufferedReader()?.use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                if (line.length > 6 && line.startsWith("data:")) {
                                    val event = parseSseToEvent(line.substring(6), endpointType)
                                    if (event != null) emit(event)
                                }
                                line = reader.readLine()
                            }
                        }
                    } else {
                        val errBody = resp.body?.string()
                        emit(SseEvent.Error("HTTP error ${resp.code}: $errBody"))
                    }
                }
            } catch (e: UnknownHostException) {
                emit(SseEvent.Error("无法解析服务器域名，请检查网络设置"))
                Log.e(TAG, "SSE UnknownHost", e)
            } catch (e: InterruptedIOException) {
                emit(SseEvent.Error("请求被中断，这条消息已停止"))
                Log.e(TAG, "SSE InterruptedIO", e)
            } catch (e: SocketException) {
                emit(SseEvent.Error("访问连接失败，请检查网络设置"))
                Log.e(TAG, "SSE SocketException", e)
            } catch (e: IOException) {
                Log.e(TAG, "SSE IOException", e)
            } catch (e: JSONException) {
                Log.e(TAG, "SSE JSONException", e)
            } finally {
                emit(SseEvent.Done)
            }
        }.flowOn(Dispatchers.IO)

        /**
         * 发起流式请求,返回 Flow<SseEvent>
         *
         * 新代码推荐使用此方法,在协程中 collect 即可,
         * 无需传入 Handler/TextView/ScrollView 等 UI 组件。
         */
        @JvmStatic
        fun sendPostRequestStreamFlow(
            url: String, modelName: String, userInput: String,
            apiKey: String?, systemPrompt: String,
            thinking: Boolean?, endpointType: String
        ): Flow<SseEvent> {
            val body = when (endpointType) {
                "anthropic" -> {
                    val json = JSONObject(JsonStringAnthropic(modelName, userInput, systemPrompt, thinking))
                    json.put("stream", true)
                    json.toString()
                }
                "responses" -> JsonStringResponses(modelName, userInput, systemPrompt, thinking)
                else -> JsonString(modelName, userInput, true, systemPrompt, thinking)
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            return executeSseStream(request, endpointType)
        }

        /**
         * 发起带对话历史的流式请求,返回 Flow<SseEvent>。
         * 与 sendPostRequestStreamFlow 的区别: 本方法支持多轮对话历史(messages 列表),
         * 适合在 AgentOrchestrator 中流式输出 AI 的最终文本回复。
         */
        @JvmStatic
        fun sendChatWithHistoryStreamFlow(
            url: String, modelName: String, messages: List<Pair<String, String>>,
            apiKey: String?, systemPrompt: String, thinking: Boolean?, endpointType: String
        ): Flow<SseEvent> {
            val bodyStr = when (endpointType) {
                "anthropic" -> {
                    val json = JSONObject(buildAnthropicHistoryBody(modelName, messages, systemPrompt, thinking))
                    json.put("stream", true)
                    json.toString()
                }
                "responses" -> buildResponsesHistoryBody(modelName, messages, systemPrompt, thinking)
                else -> buildChatHistoryBody(modelName, messages, systemPrompt, thinking)
                    .let { JSONObject(it).apply { put("stream", true) }.toString() }
            }
            val request = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            return executeSseStream(request, endpointType)
        }

        /** 解析 SSE 单条 data 为 SseEvent(纯函数,无 UI 依赖) */
        private fun parseSseToEvent(lineData: String, endpointType: String): SseEvent? {
            return try {
                val jsonObject = JSONObject(lineData)
                if (endpointType == "anthropic") {
                    when (jsonObject.optString("type")) {
                        "content_block_delta" -> {
                            val delta = jsonObject.optJSONObject("delta") ?: return null
                            when (delta.optString("type")) {
                                "text_delta" -> SseEvent.Token(delta.optString("text"))
                                "thinking_delta" -> SseEvent.ThinkToken(delta.optString("thinking"))
                                else -> null
                            }
                        }
                        "error" -> {
                            val error = jsonObject.optJSONObject("error")
                            SseEvent.Error(error?.optString("message") ?: jsonObject.toString())
                        }
                        else -> null
                    }
                } else {
                    when {
                        jsonObject.has("choices") -> {
                            val choicesArray = jsonObject.getJSONArray("choices")
                            // 流式结束时有些 API 会发送空 choices 数组,直接忽略
                            if (choicesArray.length() == 0) return null
                            val firstChoice = choicesArray.optJSONObject(0) ?: return null
                            // 用 opt 安全获取 delta,有些 chunk 只有 role 没有 delta
                            val deltaObject = firstChoice.optJSONObject("delta") ?: return null
                            when {
                                deltaObject.has("content") && !deltaObject.isNull("content") &&
                                    deltaObject.getString("content").isNotEmpty() ->
                                    SseEvent.Token(deltaObject.getString("content"))
                                deltaObject.has("reasoning_content") && !deltaObject.isNull("reasoning_content") &&
                                    deltaObject.getString("reasoning_content").isNotEmpty() ->
                                    SseEvent.ThinkToken(deltaObject.getString("reasoning_content"))
                                else -> null
                            }
                        }
                        jsonObject.has("error") -> {
                            val errorObj = jsonObject.optJSONObject("error")
                            val msg = if (errorObj != null) {
                                val code = errorObj.optString("code", "")
                                if (code == "data_inspection_failed") {
                                    "你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧"
                                } else {
                                    errorObj.optString("message", jsonObject.toString())
                                }
                            } else {
                                jsonObject.toString()
                            }
                            SseEvent.Error(msg)
                        }
                        else -> null
                    }
                }
            } catch (e: JSONException) {
                if (lineData.trim() != "[DONE]") {
                    Log.e(TAG, "Parse Json Failed", e)
                    // 不再把解析失败当错误输出给用户,只在日志记录
                    null
                } else null
            }
        }

        /**
         * 带 tools 的流式请求:实时 emit 文本 Token,并跨分片累积 tool_calls。
         * 若本轮无工具调用则文本按 API 真速逐字流出;若有工具调用,在 Done 前 emit 一次 ToolCalls。
         */
        @JvmStatic
        fun sendChatWithToolsStreamFlow(
            url: String, modelName: String,
            messages: List<go.examate.hyp.agent.FuncMessage>,
            apiKey: String?, systemPrompt: String,
            thinking: Boolean?, endpointType: String,
            tools: String?
        ): Flow<SseEvent> {
            val bodyStr = when (endpointType) {
                "anthropic" -> JSONObject(buildAnthropicToolsBody(modelName, messages, systemPrompt, thinking, tools))
                    .put("stream", true).toString()
                "responses" -> JSONObject(buildResponsesHistoryBody(modelName, messages.map {
                    when (it) {
                        is go.examate.hyp.agent.FuncMessage.Text -> it.role to it.content
                        is go.examate.hyp.agent.FuncMessage.AssistantToolCall -> "assistant" to it.content
                        is go.examate.hyp.agent.FuncMessage.ToolResult -> "user" to it.content
                    }
                }, systemPrompt, thinking)).put("stream", true).toString()
                else -> JSONObject(buildChatToolsBody(modelName, messages, systemPrompt, thinking, tools))
                    .put("stream", true).toString()
            }
            val request = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(endpointType, apiKey)
                .build()
            return executeToolsSseStream(request, endpointType)
        }

        /**
         * 带工具的 SSE 读取循环:文本 Token 实时 emit,tool_calls 按 index 跨分片累积,
         * 流结束前若有工具调用则 emit 一次 ToolCalls。
         */
        private fun executeToolsSseStream(request: Request, endpointType: String): Flow<SseEvent> = flow {
            val accId = HashMap<Int, String>()
            val accName = HashMap<Int, String>()
            val accArgs = HashMap<Int, StringBuilder>()
            var errored = false
            try {
                streamClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.bufferedReader()?.use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                if (line.length > 6 && line.startsWith("data:")) {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") break
                                    val ev = parseToolsSseLine(data, endpointType, accId, accName, accArgs)
                                    if (ev != null) {
                                        if (ev is SseEvent.Error) errored = true
                                        emit(ev)
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    } else {
                        val errBody = resp.body?.string()
                        errored = true
                        emit(SseEvent.Error("HTTP error ${resp.code}: $errBody"))
                    }
                }
            } catch (e: UnknownHostException) {
                errored = true; emit(SseEvent.Error("无法解析服务器域名，请检查网络设置")); Log.e(TAG, "tools SSE UnknownHost", e)
            } catch (e: InterruptedIOException) {
                errored = true; emit(SseEvent.Error("请求被中断，这条消息已停止")); Log.e(TAG, "tools SSE InterruptedIO", e)
            } catch (e: SocketException) {
                errored = true; emit(SseEvent.Error("访问连接失败，请检查网络设置")); Log.e(TAG, "tools SSE SocketException", e)
            } catch (e: IOException) {
                // 流中途断连(ProtocolException/StreamReset/SSL 等均为 IOException):必须置 errored,
                // 否则 finally 会用截断一半的 accArgs 拼出 ToolCalls,导致工具带空参静默执行
                errored = true; emit(SseEvent.Error("网络连接中断，请重试")); Log.e(TAG, "tools SSE IOException", e)
            } catch (e: JSONException) {
                errored = true; Log.e(TAG, "tools SSE JSONException", e)
            } finally {
                if (!errored && accName.isNotEmpty()) {
                    val calls = accName.keys.sorted().mapNotNull { idx ->
                        val name = accName[idx]
                        if (name.isNullOrEmpty()) null
                        else go.examate.hyp.agent.NativeToolCall(
                            accId[idx] ?: "call_$idx",
                            name,
                            accArgs[idx]?.toString()?.ifEmpty { "{}" } ?: "{}"
                        )
                    }
                    if (calls.isNotEmpty()) emit(SseEvent.ToolCalls(calls))
                }
                emit(SseEvent.Done)
            }
        }.flowOn(Dispatchers.IO)

        /**
         * 解析带工具的 SSE 单行:返回需立即 emit 的事件(Token/ThinkToken/Error),
         * 并就地累积 tool_calls 分片到 acc* 映射(无需立即 emit 时返回 null)。
         */
        private fun parseToolsSseLine(
            data: String, endpointType: String,
            accId: HashMap<Int, String>, accName: HashMap<Int, String>, accArgs: HashMap<Int, StringBuilder>
        ): SseEvent? {
            return try {
                val json = JSONObject(data)
                if (endpointType == "anthropic") {
                    when (json.optString("type")) {
                        "content_block_start" -> {
                            val idx = json.optInt("index", 0)
                            val block = json.optJSONObject("content_block")
                            if (block != null && block.optString("type") == "tool_use") {
                                accId[idx] = block.optString("id", "call_$idx")
                                accName[idx] = block.optString("name", "")
                                accArgs.getOrPut(idx) { StringBuilder() }
                            }
                            null
                        }
                        "content_block_delta" -> {
                            val idx = json.optInt("index", 0)
                            val delta = json.optJSONObject("delta") ?: return null
                            when (delta.optString("type")) {
                                "text_delta" -> SseEvent.Token(delta.optString("text"))
                                "thinking_delta" -> SseEvent.ThinkToken(delta.optString("thinking"))
                                "input_json_delta" -> {
                                    accArgs.getOrPut(idx) { StringBuilder() }.append(delta.optString("partial_json"))
                                    null
                                }
                                else -> null
                            }
                        }
                        "error" -> {
                            val error = json.optJSONObject("error")
                            SseEvent.Error(error?.optString("message") ?: json.toString())
                        }
                        else -> null
                    }
                } else {
                    when {
                        json.has("choices") -> {
                            val choices = json.getJSONArray("choices")
                            if (choices.length() == 0) return null
                            val first = choices.optJSONObject(0) ?: return null
                            val delta = first.optJSONObject("delta") ?: return null
                            val tcArr = delta.optJSONArray("tool_calls")
                            if (tcArr != null) {
                                for (i in 0 until tcArr.length()) {
                                    val tc = tcArr.getJSONObject(i)
                                    val idx = tc.optInt("index", i)
                                    if (tc.has("id") && tc.optString("id").isNotEmpty()) accId[idx] = tc.optString("id")
                                    val func = tc.optJSONObject("function")
                                    if (func != null) {
                                        if (func.has("name") && func.optString("name").isNotEmpty())
                                            accName[idx] = func.optString("name")
                                        if (func.has("arguments"))
                                            accArgs.getOrPut(idx) { StringBuilder() }.append(func.optString("arguments"))
                                    }
                                }
                            }
                            when {
                                delta.has("content") && !delta.isNull("content") &&
                                    delta.getString("content").isNotEmpty() ->
                                    SseEvent.Token(delta.getString("content"))
                                delta.has("reasoning_content") && !delta.isNull("reasoning_content") &&
                                    delta.getString("reasoning_content").isNotEmpty() ->
                                    SseEvent.ThinkToken(delta.getString("reasoning_content"))
                                else -> null
                            }
                        }
                        json.has("error") -> {
                            val errorObj = json.optJSONObject("error")
                            SseEvent.Error(errorObj?.optString("message") ?: json.toString())
                        }
                        else -> null
                    }
                }
            } catch (e: JSONException) {
                null
            }
        }

        // ════════════════════════════════════════════════════
        //  旧签名兼容(内部委托 Flow,在 runBlocking 中 collect)
        // ════════════════════════════════════════════════════

        @JvmStatic
        fun sendPostRequestStream(url: String, modelName: String, userInput: String, apiKey: String?, systemPrompt: String, handler: Handler, outputTextView: TextView?, thinkTextView: TextView?, errorTextView: TextView?, service: UnifiedFloatingService, scrollView: ScrollView?, thinking: Boolean?) {
            sendPostRequestStream(url, modelName, userInput, apiKey, systemPrompt, handler, outputTextView, thinkTextView, errorTextView, service, scrollView, thinking, "chat")
        }

        @JvmStatic
        fun sendPostRequestStream(url: String, modelName: String, userInput: String, apiKey: String?, systemPrompt: String, handler: Handler, outputTextView: TextView?, thinkTextView: TextView?, errorTextView: TextView?, service: UnifiedFloatingService, scrollView: ScrollView?, thinking: Boolean?, endpointType: String) {
            // 使用 WeakReference 防止悬浮窗关闭后 Activity/Window 无法回收
            val outputRef = java.lang.ref.WeakReference(outputTextView)
            val thinkRef = java.lang.ref.WeakReference(thinkTextView)
            val errorRef = java.lang.ref.WeakReference(errorTextView)
            val scrollRef = java.lang.ref.WeakReference(scrollView)
            val serviceRef = java.lang.ref.WeakReference(service)
            runBlocking {
                sendPostRequestStreamFlow(url, modelName, userInput, apiKey, systemPrompt, thinking, endpointType).collect { event ->
                    when (event) {
                        is SseEvent.Token -> ApplyTokenOnUI(event.text, handler, outputRef.get(), scrollRef.get())
                        is SseEvent.ThinkToken -> ApplyTokenOnUI(event.text, handler, thinkRef.get(), scrollRef.get())
                        is SseEvent.Error -> ApplyTokenOnUI(event.message, handler, errorRef.get(), scrollRef.get())
                        is SseEvent.ToolCalls -> { /* 悬浮搜题无工具调用 */ }
                        is SseEvent.Done -> {
                            handler.post {
                                outputRef.get()?.let {
                                    if (it.text.isNotEmpty()) {
                                        val formatted = AnswerFormatter.format(it.text.toString())
                                        it.text = formatted
                                    }
                                }
                                scrollRef.get()?.fullScroll(ScrollView.FOCUS_DOWN)
                                serviceRef.get()?.setSubmitBtnStatus(true)
                            }
                        }
                    }
                }
            }
        }

        private fun ApplyTokenOnUI(token: CharSequence, handler: Handler, v: TextView?, scrollView: ScrollView?) {
            handler.post {
                v?.let {
                    if (it.visibility == View.GONE) it.visibility = View.VISIBLE
                    it.append(token)
                }
                scrollView?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
