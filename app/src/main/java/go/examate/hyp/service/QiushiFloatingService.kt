package go.examate.hyp.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import go.examate.hyp.R
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.AnswerFormatter
import go.examate.hyp.util.HttpUtils
import kotlinx.coroutines.runBlocking

/**
 * 求是悬浮窗服务 — 从 UnifiedFloatingService(搜题)解耦的独立轻量 AI 问答窗。
 *
 * 定位:对一段内容(聊天回复/随手输入)做多轮深挖追问,不与搜题管线共享任何状态:
 * - 独立进程内服务,自己管理窗口生命周期
 * - 会话内维护多轮历史(最多 20 条),支持连续追问
 * - 提供者取首个可用的聊天能力模型;流式输出,可中途停止
 */
class QiushiFloatingService : Service() {

    companion object {
        const val EXTRA_CONTENT = "qiushi_content"

        /** 求是系统提示:深挖求证,区别于搜题的应试格式 */
        private val QIUSHI_PROMPT =
            "你是「求是」助手:对用户提供的内容做多角度深入分析、求证与追问。" +
                "回答严谨、结构清晰;发现内容中的疑点或可深挖之处时主动指出。"

        /** 会话内历史上限(条) */
        private const val MAX_HISTORY = 20
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private val viewParams = WindowManager.LayoutParams(
        800,
        1000,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    )
    private lateinit var containerView: View
    private var isShow = false
    private var thread: Thread? = null

    /** 本次求是会话的多轮历史(role to content) */
    private val history = mutableListOf<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 防截屏关闭时窗口可被截图
        if (!PrefsManager.getInstance(this).isForbidCapture) {
            viewParams.flags = viewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        // 默认尺寸钳制在屏幕内,避免小屏溢出
        val metrics = resources.displayMetrics
        viewParams.width = minOf(800, (metrics.widthPixels * 0.86f).toInt())
        viewParams.height = minOf(1000, (metrics.heightPixels * 0.72f).toInt())
        viewParams.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        viewParams.x = 0
        viewParams.y = 0
        containerView = LayoutInflater.from(this).inflate(R.layout.qiushi_widget, null)
        initViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val content = intent?.getStringExtra(EXTRA_CONTENT) ?: ""
        show(content)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val t = thread
        if (t != null && t.isAlive) t.interrupt()
        thread = null
        if (this::containerView.isInitialized && containerView.isAttachedToWindow) {
            windowManager.removeView(containerView)
        }
        isShow = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        val outputTextView = containerView.findViewById<TextView>(R.id.qiushi_output)
        val scrollView = containerView.findViewById<ScrollView>(R.id.qiushi_scroll)
        val editText = containerView.findViewById<EditText>(R.id.qiushi_input)

        // 关闭按钮:关窗即结束本次会话(历史清空)
        containerView.findViewById<View>(R.id.qiushi_close_btn).setOnClickListener {
            hide()
        }

        // 发送按钮 → 流式请求(带会话内历史,支持连续追问)
        containerView.findViewById<View>(R.id.qiushi_submit_btn).setOnClickListener {
            val userInput = editText.text.toString()
            if (userInput.isEmpty()) return@setOnClickListener

            outputTextView.text = ""
            setSubmitStatus(false)
            // 窗口失焦,交还键盘焦点给下层
            viewParams.flags = viewParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            viewParams.flags = viewParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            windowManager.updateViewLayout(containerView, viewParams)

            val provider = MyConfig.providersFor(this, ModelProvider.CAP_CHAT).firstOrNull()
            if (provider == null) {
                outputTextView.text = "未配置可用的聊天模型,请先在 API 设置中添加"
                setSubmitStatus(true)
                return@setOnClickListener
            }

            val context = this
            thread = Thread(Runnable {
                runBlocking {
                    val collected = StringBuilder()
                    HttpUtils.sendChatWithHistoryStreamFlow(
                        provider.requestUrl, provider.modelId,
                        history.toList() + ("user" to userInput),
                        provider.apiKey, QIUSHI_PROMPT,
                        provider.enableThinking, provider.endpointType
                    ).collect { event ->
                        when (event) {
                            is HttpUtils.SseEvent.Token -> {
                                collected.append(event.text)
                                handler.post {
                                    outputTextView.append(event.text)
                                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                                }
                            }
                            is HttpUtils.SseEvent.ThinkToken -> { /* 思考过程暂不展示 */ }
                            is HttpUtils.SseEvent.Error -> {
                                handler.post {
                                    outputTextView.text = "错误: ${event.message}"
                                    setSubmitStatus(true)
                                }
                            }
                            is HttpUtils.SseEvent.Done -> {
                                handler.post {
                                    val formatted = AnswerFormatter.format(outputTextView.text.toString())
                                    if (formatted != null) outputTextView.text = formatted
                                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                                    setSubmitStatus(true)
                                    // 沉淀本轮问答到会话历史(截断防膨胀)
                                    history += "user" to userInput
                                    history += "assistant" to outputTextView.text.toString()
                                    while (history.size > MAX_HISTORY) history.removeAt(0)
                                }
                            }
                            is HttpUtils.SseEvent.ToolCalls -> { /* 求是无工具调用 */ }
                        }
                    }
                }
            })
            thread?.start()
        }

        // 停止按钮
        containerView.findViewById<View>(R.id.qiushi_stop_btn).setOnClickListener {
            val t = thread
            if (t != null && t.isAlive) t.interrupt()
            setSubmitStatus(true)
        }

        // 输入框触摸时允许窗口获得焦点
        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                viewParams.flags = viewParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                viewParams.flags = viewParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                windowManager.updateViewLayout(containerView, viewParams)
            }
            false
        }

        // 标题栏拖拽(移动窗口)
        containerView.findViewById<View>(R.id.qiushi_title_bar).setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = viewParams.x
                        initialY = viewParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        viewParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        viewParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(containerView, viewParams)
                        return true
                    }
                }
                return false
            }
        })
    }

    /** 显示/聚焦求是窗;[content] 非空时填充为输入参考 */
    private fun show(content: String) {
        if (!isShow) {
            isShow = true
            if (content.isNotEmpty()) {
                containerView.findViewById<EditText>(R.id.qiushi_input).setText(content)
                containerView.findViewById<TextView>(R.id.qiushi_output).text = "求是结果将显示在这里..."
            }
            windowManager.addView(containerView, viewParams)
        } else if (content.isNotEmpty()) {
            containerView.findViewById<EditText>(R.id.qiushi_input).setText(content)
        }
    }

    /** 关窗并结束会话 */
    private fun hide() {
        isShow = false
        if (this::containerView.isInitialized && containerView.isAttachedToWindow) {
            windowManager.removeView(containerView)
        }
        val t = thread
        if (t != null && t.isAlive) t.interrupt()
        setSubmitStatus(true)
        history.clear()
        stopSelf()
    }

    /** 切换发送/停止按钮状态 */
    private fun setSubmitStatus(active: Boolean) {
        if (!this::containerView.isInitialized) return
        val submit = containerView.findViewById<Button>(R.id.qiushi_submit_btn)
        val stop = containerView.findViewById<Button>(R.id.qiushi_stop_btn)
        submit.isEnabled = active
        stop.isEnabled = !active
        if (active) {
            submit.visibility = View.VISIBLE
            stop.visibility = View.GONE
        } else {
            submit.visibility = View.GONE
            stop.visibility = View.VISIBLE
        }
    }
}
