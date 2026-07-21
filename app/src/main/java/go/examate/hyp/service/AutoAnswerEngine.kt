package go.examate.hyp.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import go.examate.hyp.config.MyConfig
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 自动答题引擎
 *
 * 工作流程:
 * 1. 点击悬浮球"自动"按钮 → 启动引擎
 * 2. 收集当前屏幕文本作为题目
 * 3. 后台调用 AI 获取答案(支持多 AI 圆桌会议)
 * 4. 根据答案类型自动点击选项或填入文字
 * 5. 点击"下一题"
 * 6. 重复 2-5,题目获取失败时跳过标记,稍后重试
 * 7. 一轮结束后回到未作答题重试
 * 8. 提交需手动
 */
class AutoAnswerEngine private constructor(
    private val providers: List<ModelProvider>,
    private val multiAiVerify: Boolean = false,
    private val verifySelectedIndices: Set<Int> = emptySet()
) {
    /** UnifiedFloatingService 实例当前未使用,保留参数以便未来扩展 */
    constructor(service: UnifiedFloatingService, providers: List<ModelProvider>, multiAiVerify: Boolean = false, verifySelectedIndices: Set<Int> = emptySet()) : this(providers, multiAiVerify, verifySelectedIndices)

    companion object {
        private const val TAG = "AutoAnswerEngine"
        private const val DELAY_AFTER_CLICK = 1500L   // 作答后等待(原1200,增大给WebView反应时间)
        private const val DELAY_AFTER_NEXT = 2500L    // 点击下一题后等待(原1500,WebView加载较慢)
        private const val DELAY_RETRY_ROUND = 3000L
        private const val MAX_RETRY_ROUNDS = 3
        private const val MAX_QUESTIONS = 100
        private const val AI_TIMEOUT_SECONDS = 30L
    }

    enum class State {
        IDLE, EXTRACTING, ASKING_AI, ANSWERING, NEXT, RETRY_ROUND, DONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var state: State = State.IDLE
    private var lastError: String? = null
    private var isRunning = false
    private var currentQuestionIndex = 0
    private var retryRound = 0

    private val skippedQuestions = mutableMapOf<String, Int>()

    var onStatusUpdate: ((String) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    /** 用于作答的有效 provider 列表(过滤掉没填 key 的) */
    private val activeProviders: List<ModelProvider> = providers.filterIndexed { index, it ->
        it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty() && it.modelId.isNotEmpty()
    }

    /** 多 AI 验证用到的 provider(用户在弹窗中勾选的,空集合=全部有效) */
    private val verifyProviders: List<ModelProvider> = if (verifySelectedIndices.isEmpty()) {
        activeProviders
    } else {
        activeProviders.filterIndexed { index, _ -> verifySelectedIndices.contains(index) }
    }

    fun start() {
        if (isRunning) return
        if (activeProviders.isEmpty()) {
            updateStatus("没有可用的 API 配置,请先填写")
            return
        }
        isRunning = true
        retryRound = 0
        currentQuestionIndex = 0
        skippedQuestions.clear()
        val mode = if (multiAiVerify && verifyProviders.size >= 2) {
            "多 AI 验证(${verifyProviders.size} 个 AI)"
        } else {
            "单 AI 模式"
        }
        updateStatus("自动答题已启动 - $mode")
        handler.postDelayed({
            state = State.EXTRACTING
            processNextStep()
        }, 1000L)
    }

    fun stop() {
        isRunning = false
        state = State.IDLE
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        updateStatus("自动答题已停止")
    }

    private fun processNextStep() {
        if (!isRunning) return
        when (state) {
            State.IDLE -> { /* do nothing */ }
            State.EXTRACTING -> extractQuestion()
            State.ASKING_AI -> askAI()
            State.ANSWERING -> answerQuestion()
            State.NEXT -> goToNext()
            State.RETRY_ROUND -> startRetryRound()
            State.DONE -> finish()
        }
    }

    // ═══ 提取题目 ═══
    private fun extractQuestion() {
        updateStatus("正在提取题目...")
        val aService = MyAccessibilityService.getMyAccessibilityServiceInstance()
        if (aService == null) {
            updateStatus("无障碍服务未开启,无法提取")
            stop()
            return
        }

        // 用带重试的提取:页面可能正在加载,延迟重试以提高命中率
        scope.launch {
            val screenText = aService.collectAllScreenTextWithRetry(maxRetries = 5)
            handler.post {
                if (screenText.isEmpty() || screenText.length < 10) {
                    Log.d(TAG, "题目文本为空或过短,跳过")
                    markSkipped("")
                    state = State.NEXT
                    handler.postDelayed({ processNextStep() }, DELAY_AFTER_NEXT)
                    return@post
                }

                val textHash = screenText.trim().take(200)
                if (skippedQuestions.containsKey(textHash) && retryRound == 0) {
                    Log.d(TAG, "题目重复,跳过")
                    state = State.NEXT
                    handler.postDelayed({ processNextStep() }, DELAY_AFTER_NEXT)
                    return@post
                }

                currentQuestionText = screenText
                currentQuestionHash = textHash
                state = State.ASKING_AI
                processNextStep()
            }
        }
    }

    private var currentQuestionText: String = ""
    private var currentQuestionHash: String = ""
    private var currentAnswer: String = ""

    // ═══ 调用 AI(支持多 AI 圆桌会议) ═══
    private fun askAI() {
        val useMulti = multiAiVerify && verifyProviders.size >= 2
        updateStatus(if (useMulti) "多 AI 请求中(${verifyProviders.size} 个)..." else "正在请求 AI...")

        scope.launch {
            try {
                val answer = if (useMulti) {
                    askMultiAI()
                } else {
                    askSingleAI(activeProviders.first())
                }

                if (answer.isEmpty()) {
                    handler.post {
                        val err = lastError
                        updateStatus(if (err != null) "AI 请求失败: $err,跳过" else "AI 未返回有效答案,跳过")
                        lastError = null
                        markSkipped(currentQuestionText)
                        state = State.NEXT
                        handler.postDelayed({ processNextStep() }, DELAY_AFTER_NEXT)
                    }
                    return@launch
                }

                currentAnswer = answer
                handler.post {
                    updateStatus("AI 答案: $answer")
                    state = State.ANSWERING
                    processNextStep()
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 请求异常", e)
                handler.post {
                    updateStatus("AI 请求异常: ${e.message},跳过")
                    markSkipped(currentQuestionText)
                    state = State.NEXT
                    handler.postDelayed({ processNextStep() }, DELAY_AFTER_NEXT)
                }
            }
        }
    }

    /** 单 AI 请求 */
    private suspend fun askSingleAI(provider: ModelProvider): String {
        val result = HttpUtils.sendPostRequestResult(
            provider.requestUrl,
            provider.modelId,
            currentQuestionText,
            provider.apiKey,
            MyConfig.SYSTEM_PROMPT,
            provider.enableThinking,
            provider.endpointType ?: "chat"
        )
        return when (result) {
            is go.examate.hyp.util.ApiResult.Success ->
                parseAnswerFromResponse(result.data, provider.endpointType ?: "chat")
            else -> {
                Log.e(TAG, "单 AI 请求失败: ${provider.displayName} - ${result.errorMessage()}")
                lastError = result.errorMessage()
                ""
            }
        }
    }

    /** 多 AI 验证:并发请求 + 智能决策 */
    private suspend fun askMultiAI(): String {
        // 并发请求用户选中的 AI,等待所有返回(最多 30 秒);超时则视为本次无结果
        val pairs: List<Pair<String, String>?> = withTimeoutOrNull(AI_TIMEOUT_SECONDS * 1000L) {
            verifyProviders.map { provider ->
                async {
                    try {
                        val result = HttpUtils.sendPostRequestResult(
                            provider.requestUrl,
                            provider.modelId,
                            currentQuestionText,
                            provider.apiKey,
                            MyConfig.SYSTEM_PROMPT,
                            provider.enableThinking,
                            provider.endpointType ?: "chat"
                        )
                        when (result) {
                            is go.examate.hyp.util.ApiResult.Success -> {
                                val ans = parseAnswerFromResponse(result.data, provider.endpointType ?: "chat")
                                if (ans.isNotEmpty()) provider.displayName to ans else null
                            }
                            else -> {
                                Log.e(TAG, "多AI: ${provider.displayName} 请求失败 - ${result.errorMessage()}")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "多AI: ${provider.displayName} 请求失败", e)
                        null
                    }
                }
            }.awaitAll()
        } ?: emptyList()

        val results = mutableMapOf<String, String>() // providerName → answer
        pairs.forEach { if (it != null) results[it.first] = it.second }

        if (results.isEmpty()) return ""

        Log.d(TAG, "多AI原始结果: $results")

        // 判断是否为选择题(以 A-Z 开头 + 分隔符或单字符)
        val firstAnswer = results.values.first()
        val isChoiceQuestion = isChoiceAnswer(firstAnswer)

        return if (isChoiceQuestion) {
            // 选择题:按首字母投票
            val votes = mutableMapOf<Char, Int>()
            results.forEach { (_, ans) ->
                val letter = ans.trim().firstOrNull()?.uppercaseChar() ?: return@forEach
                if (letter in 'A'..'Z') {
                    votes[letter] = (votes[letter] ?: 0) + 1
                }
            }
            val winner = votes.maxByOrNull { it.value }
            Log.d(TAG, "选择题投票: $votes → 当选: $winner")
            // 返回得票最多的字母(纯字母,便于 clickOptionByAnswer 解析)
            if (winner != null) {
                winner.key.toString()
            } else {
                firstAnswer
            }
        } else {
            // 非选择题(填空/问答):直接用首个 AI 的答案,不投票
            // 因为文本答案难以归一化比较,投票反而可能让正确答案被刷下
            Log.d(TAG, "非选择题,采用首个 AI 答案")
            firstAnswer
        }
    }

    /** 判断答案是否为选择题格式(A / A.xxx / A、xxx) */
    private fun isChoiceAnswer(ans: String): Boolean {
        val trimmed = ans.trim()
        if (trimmed.length > 5) return false
        return trimmed.matches(Regex("^[A-Z](\\.|、|\\s|$)"))
    }

    /**
     * 从 AI 返回中解析答案(兼容 chat/responses 格式)
     */
    private fun parseAnswerFromResponse(response: String, endpointType: String): String {
        return try {
            val content = HttpUtils.extractContent(response, endpointType)
            if (content.isEmpty()) return ""
            // 提取【答案】后面的内容
            val answerIdx = content.indexOf("【答案】")
            if (answerIdx != -1) {
                val afterAnswer = content.substring(answerIdx + "【答案】".length)
                val analysisIdx = afterAnswer.indexOf("【解析】")
                val answer = if (analysisIdx != -1) {
                    afterAnswer.substring(0, analysisIdx)
                } else {
                    afterAnswer
                }
                answer.trim().replace("\n", "")
            } else {
                content.trim().lines().firstOrNull()?.trim() ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 AI 返回失败", e)
            ""
        }
    }

    // ═══ 作答 ═══
    private fun answerQuestion() {
        updateStatus("正在作答: $currentAnswer")
        val aService = MyAccessibilityService.getMyAccessibilityServiceInstance()
        if (aService == null) {
            updateStatus("无障碍服务断开,停止")
            stop()
            return
        }

        val answer = currentAnswer.trim()
        val firstChar = answer.firstOrNull()?.uppercaseChar()

        val isOptionAnswer = firstChar != null &&
            firstChar in 'A'..'Z' &&
            (answer.length == 1 || answer[1] == '.' || answer[1] == '、' || answer[1] == ' ')

        val success = if (isOptionAnswer) {
            aService.clickOptionByAnswer(answer)
        } else {
            aService.fillAnswerToInputField(answer)
        }

        if (!success) {
            val fallbackSuccess = if (isOptionAnswer) {
                aService.fillAnswerToInputField(answer)
            } else {
                aService.clickOptionByAnswer(answer)
            }
            if (!fallbackSuccess) {
                updateStatus("作答失败,跳过此题")
                markSkipped(currentQuestionText)
            }
        }

        state = State.NEXT
        handler.postDelayed({ processNextStep() }, DELAY_AFTER_CLICK)
    }

    // ═══ 下一题 ═══
    private fun goToNext() {
        currentQuestionIndex++
        updateStatus("准备下一题 (${currentQuestionIndex + 1})")

        if (currentQuestionIndex >= MAX_QUESTIONS) {
            state = State.DONE
            processNextStep()
            return
        }

        val aService = MyAccessibilityService.getMyAccessibilityServiceInstance()
        if (aService == null) {
            updateStatus("无障碍服务断开,停止")
            stop()
            return
        }

        val clicked = aService.clickNextButton()
        if (!clicked) {
            updateStatus("未找到下一题按钮,可能已完成")
            state = if (skippedQuestions.isNotEmpty() && retryRound < MAX_RETRY_ROUNDS) {
                State.RETRY_ROUND
            } else {
                State.DONE
            }
        } else {
            state = if (currentQuestionIndex >= MAX_QUESTIONS - 1) {
                if (skippedQuestions.isNotEmpty() && retryRound < MAX_RETRY_ROUNDS) {
                    State.RETRY_ROUND
                } else {
                    State.DONE
                }
            } else {
                State.EXTRACTING
            }
        }

        handler.postDelayed({ processNextStep() }, DELAY_AFTER_NEXT)
    }

    // ═══ 重试轮次 ═══
    private fun startRetryRound() {
        retryRound++
        if (retryRound > MAX_RETRY_ROUNDS || skippedQuestions.isEmpty()) {
            state = State.DONE
            processNextStep()
            return
        }

        updateStatus("第 $retryRound 轮重试未作答题目 (${skippedQuestions.size} 题)")
        currentQuestionIndex = 0
        val toRemove = skippedQuestions.entries.filter { it.value >= MAX_RETRY_ROUNDS }.map { it.key }
        toRemove.forEach { skippedQuestions.remove(it) }

        state = if (skippedQuestions.isEmpty()) State.DONE else State.EXTRACTING
        handler.postDelayed({ processNextStep() }, DELAY_RETRY_ROUND)
    }

    private fun markSkipped(questionText: String) {
        val key = if (questionText.isEmpty()) {
            "__empty_${currentQuestionIndex}__"
        } else {
            questionText.trim().take(200)
        }
        skippedQuestions[key] = (skippedQuestions[key] ?: 0) + 1
    }

    private fun finish() {
        val skippedCount = skippedQuestions.size
        val msg = if (skippedCount > 0) {
            "自动答题完成,${skippedCount} 题未能作答,请手动检查后提交"
        } else {
            "自动答题完成,请手动检查后提交"
        }
        updateStatus(msg)
        isRunning = false
        state = State.IDLE
        onComplete?.invoke()
    }

    private fun updateStatus(msg: String) {
        Log.d(TAG, msg)
        onStatusUpdate?.invoke(msg)
    }
}
