package go.examate.hyp.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import go.examate.hyp.ui.screens.LanChatMessage
import go.examate.hyp.ui.screens.LanChatWindow
import go.examate.hyp.ui.theme.ExamateTheme
import java.text.SimpleDateFormat

/**
 * 局域网群聊悬浮窗服务(Compose UI)
 *
 * 通过 [LanChatTransport] 进行 UDP 广播通信(255.255.255.255:19876)。
 * - 通信与设备发现全部委托给 transport
 * - 悬浮窗:输入框聚焦时去掉 FLAG_NOT_FOCUSABLE 以允许输入,
 *   失焦时恢复(避免抢占全局焦点导致其他窗口无法操作)
 */
class LanChatService : Service(), LifecycleOwner, SavedStateRegistryOwner, LanChatTransport.Callback {

    companion object {
        private const val TAG = "LanChatService"
    }

    // ═══ Compose 状态(主线程访问) ═══
    private val messages = mutableStateListOf<LanChatMessage>()
    private val onlineCount = mutableStateOf(1)
    private val inputText = mutableStateOf("")

    private lateinit var windowManager: WindowManager
    private lateinit var chatParams: WindowManager.LayoutParams
    private lateinit var composeView: ComposeView
    private var isViewAdded = false

    private var transport: LanChatTransport? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    /** Service 实现 SavedStateRegistryOwner 以支持 ComposeView */
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            initWindow()
            windowManager.addView(composeView, chatParams)
            isViewAdded = true
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            transport = LanChatTransport(this, this).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        transport?.stop()
        transport = null
        if (isViewAdded && composeView.isAttachedToWindow) {
            try { windowManager.removeView(composeView) } catch (e: Exception) {}
            isViewAdded = false
        }
        super.onDestroy()
    }

    // ═══ LanChatTransport.Callback ═══

    override fun onMessageReceived(msg: LanChatMessage) {
        // 回调在收包线程触发,需切主线程更新 Compose 状态
        mainHandler.post { messages.add(msg) }
    }

    override fun onOnlineCountChanged(count: Int) {
        mainHandler.post { onlineCount.value = count }
    }

    override fun onError(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ═══ 消息发送 ═══

    private fun sendMessage(text: String) {
        // 超长由 transport 回调 onError 提示
        transport?.sendMessage(text)
        messages.add(LanChatMessage(text, true, transport?.myName ?: Build.MODEL, System.currentTimeMillis()))
    }

    // ═══ 悬浮窗(Compose) ═══

    private fun initWindow() {
        chatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = (dm.widthPixels - dp(300)) / 2
            y = (dm.heightPixels - dp(440)) / 2
        }
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LanChatService)
            setViewTreeSavedStateRegistryOwner(this@LanChatService)
            setContent {
                ExamateTheme {
                    LanChatWindow(
                        messages = messages,
                        onlineCount = onlineCount.value,
                        inputText = inputText.value,
                        myName = transport?.myName ?: Build.MODEL,
                        timeFmt = timeFmt,
                        onInputChange = { inputText.value = it },
                        onSend = {
                            val text = inputText.value.trim()
                            if (text.isNotEmpty()) {
                                sendMessage(text)
                                inputText.value = ""
                            }
                        },
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            chatParams.x += dx.toInt()
                            chatParams.y += dy.toInt()
                            try { windowManager.updateViewLayout(composeView, chatParams) } catch (e: Exception) {}
                        },
                        onInputFocusChange = { focused ->
                            // 输入框聚焦时移除 FLAG_NOT_FOCUSABLE 让窗口可获取焦点输入;
                            // 失焦时恢复 FLAG_NOT_FOCUSABLE,避免持续抢占全局焦点
                            updateWindowFocusable(focused)
                        }
                    )
                }
            }
        }
    }

    /** 根据输入框聚焦状态切换窗口 flags */
    private fun updateWindowFocusable(focused: Boolean) {
        if (!isViewAdded || !composeView.isAttachedToWindow) return
        try {
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            chatParams.flags = if (focused) {
                baseFlags
            } else {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager.updateViewLayout(composeView, chatParams)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
