package go.examate.hyp.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.security.keystore.UserNotAuthenticatedException
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import go.examate.hyp.CustomView.RectangleDrawableConstraintLayout
import go.examate.hyp.R
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.util.AdbCommandHelper
import go.examate.hyp.util.AnswerFormatter
import go.examate.hyp.util.HttpUtils

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * 悬浮窗运行模式
 * - [ACCESSIBILITY]: 无障碍模式(原 FloatingWidgetService),含藏匿/自动答题/流式输出/吸边/双击隐藏
 * - [SCREENSHOT]:    截图模式(原 ScreenshotService),含 MediaProjection/SelectionOverlayView/captureRegion/captureAndAsk
 */
enum class FloatingMode {
    NONE,
    ACCESSIBILITY,
    SCREENSHOT
}

/**
 * 统一悬浮窗服务:合并 FloatingWidgetService(无障碍) 与 ScreenshotService(截图)。
 *
 * 通过 [EXTRA_MODE] 区分启动模式:
 * - "accessibility"(默认): 无障碍框选 + 流式问问 + 自动答题 + 藏匿
 * - "screenshot":          MediaProjection 截图 OCR + 非流式问问
 *
 * 菜单项由【用户勾选(PrefsManager.floatingMenuItems) ∩ 当前模式】动态决定(见 [updateMenuVisibility])。
 */
class UnifiedFloatingService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode" // "accessibility" 或 "screenshot"

        @JvmField
        var isActive = false // 兼容 MyQSTileService 等外部查询

        @JvmField
        var currentMode: FloatingMode = FloatingMode.NONE // 外部查询当前模式

        private const val SECURE_COUNT_POLL_INTERVAL_MS = 500L
        private const val TAG = "UnifiedFloatingService"
    }

    private lateinit var windowManager: WindowManager

    private var modelIndex = 0
    private var usingStream = true
    private val handler = Handler(Looper.getMainLooper())
    private var forbidCapture = false // 是否开启防截屏保护
    private var transparentFloat = false // 是否半透明悬浮球

    /** 当前运行模式(由 onStartCommand 读取 EXTRA_MODE 设置) */
    private var mode: FloatingMode = FloatingMode.NONE

    // 设置变化监听:实现设置热更新(无需重启 Service)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val prefs = PrefsManager.getInstance(this)
        when (key) {
            PrefsManager.KEY_FORBID_CAPTURE -> {
                val newValue = prefs.isForbidCapture
                if (newValue != forbidCapture) {
                    forbidCapture = newValue
                    applyForbidCaptureChange(newValue)
                }
            }
            PrefsManager.KEY_TRANSPARENT_FLOAT -> {
                transparentFloat = prefs.isTransparentFloat
                applyTransparentFloat()
            }
            PrefsManager.KEY_FLOATING_MENU_ITEMS -> {
                // 菜单项变化时,若菜单已展开则即时刷新显隐
                if (isPopupMenuShow) updateMenuVisibility()
            }
            // KEY_EDGE_ATTACH 已在拖拽抬起时实时读取,无需处理
        }
    }

    // 屏幕大小
    var screenWidth = 0
    var screenHeight = 0
    // 小白点控件
    private var floatLogo: View? = null

    private lateinit var modelProviders: List<ModelProvider>

    private var outerSecureWindowCount = 0 // 隐私窗口数量
    private var inExamMode = false // 是否处于考试场景(检测到过FLAG_SECURE窗口)

    // 自动答题引擎
    private var autoAnswerEngine: AutoAnswerEngine? = null

    // 问问请求线程
    private var thread: Thread? = null

    // ═══ 截图模式(MediaProjection)字段 ═══
    private var statusBarHeight = 0
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var selectionOverlay: SelectionOverlayView
    private var selectionButtons: View? = null

    //<editor-fold desc="窗口参数">

    // 小白点窗口参数
    val floatToggleBtnParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT)
    val popupMenuViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT)
    // 小白点窗口与切换菜单
    private var floatToggleBtnView: View? = null
    private lateinit var popupMenuView: View
    private var isPopupMenuShow = false

    // AI问问窗口参数
    val askViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT)
    private lateinit var askContainerView: View
    private var isAskViewShow = false
    // 网页浏览窗口参数
    val webViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT)
    private lateinit var webContainerView: View
    private var isWebViewShow = false
    // 选区窗口参数(无障碍:WRAP_CONTENT 小面板; 截图:在 initSelectZoneViewScreenshot 中改为 MATCH_PARENT 全屏遮罩)
    val selectZoneViewParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT)
    private lateinit var selectZoneView: View
    private var isSelectZoneViewShow = false

    //</editor-fold>

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化防截屏
        val prefs = PrefsManager.getInstance(this)
        forbidCapture = prefs.isForbidCapture
        transparentFloat = prefs.isTransparentFloat
        // 注册设置变化监听,实现热更新
        prefs.registerSettingsListener(prefsListener)
        if (!forbidCapture) {
            popupMenuViewParams.flags = popupMenuViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            askViewParams.flags = askViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            webViewParams.flags = webViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            selectZoneViewParams.flags = selectZoneViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        } else {
            try {
                launcherSecureCountListener()
            } catch (e: Exception) {
                Log.d(TAG, "藏匿模式启动失败，Shizuku 未授权: " + e.message)
            }
        }
        // 初始化小白点及菜单
        initToggleBtnView()
        // 初始化AI问问
        initAskView()
        // 初始化网页浏览
        initWebView()
        // 注:选区窗口依赖 mode,故在 onStartCommand 中根据模式初始化(见 initSelectZoneView)
        isActive = true
        // 启动悬浮窗后,从后台任务列表隐藏本应用
        setExcludeFromRecents(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeStr = intent?.getStringExtra(EXTRA_MODE)
        if (modeStr == "screenshot") {
            mode = FloatingMode.SCREENSHOT
            currentMode = FloatingMode.SCREENSHOT
            // Android 14+: MediaProjection 必须在前台服务中运行
            startForegroundCompat()
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultData != null) {
                setupMediaProjection(resultCode, resultData)
            }
            // 状态栏高度(截图模式裁剪/坐标用)
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        } else {
            mode = FloatingMode.ACCESSIBILITY
            currentMode = FloatingMode.ACCESSIBILITY
        }
        // 选区窗口按模式初始化(仅一次)
        if (!::selectZoneView.isInitialized) {
            initSelectZoneView()
        }
        return START_NOT_STICKY
    }

    /**
     * 以前台服务运行(Android 14+ MediaProjection 要求)。
     * 创建通知渠道并启动前台服务,类型为 mediaProjection。
     */
    private fun startForegroundCompat() {
        val channelId = "screenshot_service"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "截屏服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("截屏搜题运行中")
            .setContentText("正在提供截屏搜题服务")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, 1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    /**
     * 动态设置本应用任务是否从后台列表隐藏
     * true: 隐藏(悬浮窗运行中)
     * false: 显示(悬浮窗关闭后)
     */
    private fun setExcludeFromRecents(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { task ->
                task.setExcludeFromRecents(exclude)
            }
        } catch (e: Exception) {
            Log.d(TAG, "setExcludeFromRecents 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoAnswerEngine?.stop()
        autoAnswerEngine = null
        PrefsManager.getInstance(this).unregisterSettingsListener(prefsListener)
        hideAllWindows()
        shutdownSecureCountListener()
        val btnView = floatToggleBtnView
        if (btnView != null && btnView.isAttachedToWindow) {
            windowManager.removeView(btnView)
        }
        // 截图模式资源释放
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
        // 中断问问线程
        val t = thread
        if (t != null && t.isAlive) t.interrupt()
        thread = null
        isActive = false
        currentMode = FloatingMode.NONE
        // 悬浮窗关闭后,恢复在后台任务列表显示
        setExcludeFromRecents(false)
    }

    //<editor-fold desc="初始化窗口">

    @SuppressLint("ClickableViewAccessibility")
    private fun initToggleBtnView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 获取屏幕尺寸
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Specify the view position
        floatToggleBtnParams.gravity = Gravity.TOP or Gravity.START
        popupMenuViewParams.gravity = Gravity.TOP or Gravity.START
        floatToggleBtnParams.x = 50
        floatToggleBtnParams.y = 200

        floatToggleBtnView = LayoutInflater.from(this).inflate(R.layout.toggle_float_btn, null)
        popupMenuView = LayoutInflater.from(this).inflate(R.layout.pop_menu, null)
        val toggleBtn = floatToggleBtnView ?: return
        floatLogo = toggleBtn.findViewById(R.id.shape_circle)

        // 添加小白点悬浮窗
        windowManager.addView(toggleBtn, floatToggleBtnParams)

        // 应用半透明悬浮球样式
        if (transparentFloat) {
            toggleBtn.alpha = 0.25f
        }

        val floatBtn = toggleBtn
        // 触摸
        floatBtn.findViewById<View>(R.id.root_container).setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = System.currentTimeMillis()
            private var durationTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {//按下
                        durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 500L) {//双击隐藏所有窗口
                            hideAllWindows()
                            return true
                        }
                        initialX = floatToggleBtnParams.x
                        initialY = floatToggleBtnParams.y
                        initialTime = System.currentTimeMillis()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {//抬起
                        if (System.currentTimeMillis() - initialTime > 2000L && Math.abs(initialTouchX - event.rawX) < 50f && Math.abs(initialTouchY - event.rawY) < 50f) { // 长按抬起
                            try {
                                // 解除隐匿
                                exitHideMode()
                            } catch (e: InterruptedException) {
                                throw RuntimeException(e)
                            }
                            return true
                        }

                        if (durationTime < 500L && Math.abs(initialTouchX - event.rawX) < 10f && Math.abs(initialTouchY - event.rawY) < 10f)
                            return true//已经隐藏所有窗口，不进行后续操作

                        durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 200L && Math.abs(initialTouchX - event.rawX) < 10f && Math.abs(initialTouchY - event.rawY) < 10f) {//短按
                            showPopupMenu()
                            return v.performClick()
                        } else {//长按
                            if (PrefsManager.getInstance(this@UnifiedFloatingService).isEdgeAttach) {//设置了吸边
                                if (isFloatingBallOnLeftSide()) {
                                    floatToggleBtnParams.x = 60
                                    if (isPopupMenuShow) popupMenuViewParams.x = floatToggleBtnParams.x + 140
                                } else {
                                    floatToggleBtnParams.x = screenWidth - floatBtn.width - 60
                                    if (isPopupMenuShow) popupMenuViewParams.x = floatToggleBtnParams.x - floatBtn.width - 140
                                }
                                windowManager.updateViewLayout(floatBtn, floatToggleBtnParams)
                                if (isPopupMenuShow) windowManager.updateViewLayout(popupMenuView, popupMenuViewParams)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {//移动
                        //移动小白点
                        floatToggleBtnParams.x = Math.min(Math.max(initialX + (event.rawX - initialTouchX).toInt(), 0), screenWidth - floatBtn.width)
                        floatToggleBtnParams.y = Math.min(Math.max(initialY + (event.rawY - initialTouchY).toInt(), 0), screenHeight - floatBtn.height)
                        windowManager.updateViewLayout(floatBtn, floatToggleBtnParams)
                        //移动菜单
                        if (isPopupMenuShow) {
                            if (isFloatingBallOnLeftSide()) {
                                popupMenuViewParams.x = floatToggleBtnParams.x + 140
                            } else {
                                popupMenuViewParams.x = floatToggleBtnParams.x - floatBtn.width - 140
                            }
                            popupMenuViewParams.y = floatToggleBtnParams.y
                            windowManager.updateViewLayout(popupMenuView, popupMenuViewParams)
                        }
                        return true
                    }
                }
                return false
            }
        })
        // 选区事件绑定
        popupMenuView.findViewById<View>(R.id.select_zone_btn).setOnClickListener {
            showSelectZoneView()
            // 截图模式的 showSelectZoneView 内部已关闭菜单;无障碍模式需在此关闭菜单
            if (mode == FloatingMode.ACCESSIBILITY) showPopupMenu()
        }
        // AI问问选项事件绑定
        popupMenuView.findViewById<View>(R.id.ask_btn).setOnClickListener {
            showAskView()
            showPopupMenu()
        }
        // 自动答题选项事件绑定
        popupMenuView.findViewById<View>(R.id.auto_btn).setOnClickListener {
            showPopupMenu()
            toggleAutoAnswerMode()
        }
        // 网页浏览选项事件绑定
        popupMenuView.findViewById<View>(R.id.web_btn).setOnClickListener {
            showWebView()
            showPopupMenu()
        }
        // 退出选项
        popupMenuView.findViewById<View>(R.id.close_btn).setOnClickListener {
            stopService(Intent(applicationContext, UnifiedFloatingService::class.java))
            onDestroy()
        }
    }

    /**
     * 根据【用户勾选 ∩ 当前模式】动态控制菜单按钮显隐
     */
    private fun updateMenuVisibility() {
        val enabled = PrefsManager.getInstance(this).floatingMenuItems
        // select:框选(无障碍/截图,根据启动模式自动走对应实现)
        popupMenuView.findViewById<View>(R.id.select_zone_btn).visibility =
            if (enabled.contains("select")) View.VISIBLE else View.GONE
        // ask:问问AI,无需额外权限
        popupMenuView.findViewById<View>(R.id.ask_btn).visibility =
            if (enabled.contains("ask")) View.VISIBLE else View.GONE
        // auto:仅无障碍模式且有自动答题
        val showAuto = enabled.contains("auto") && mode == FloatingMode.ACCESSIBILITY
        popupMenuView.findViewById<View>(R.id.auto_btn).visibility =
            if (showAuto) View.VISIBLE else View.GONE
        // browse:浏览
        popupMenuView.findViewById<View>(R.id.web_btn).visibility =
            if (enabled.contains("browse")) View.VISIBLE else View.GONE
        // close:退出,固定保留
        popupMenuView.findViewById<View>(R.id.close_btn).visibility = View.VISIBLE
    }

    /**
     * 切换自动答题模式
     * - 首次点击:启动自动答题,显示状态窗口
     * - 再次点击:停止自动答题
     */
    private fun toggleAutoAnswerMode() {
        // 检查无障碍服务
        if (MyAccessibilityService.getMyAccessibilityServiceInstance() == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 初始化模型配置(确保已加载)
        if (!::modelProviders.isInitialized) {
            modelProviders = MyConfig.loadProviders(this)
        }
        if (modelProviders.isEmpty()) {
            Toast.makeText(this, "请先配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        if (autoAnswerEngine != null) {
            // 已在运行,停止
            autoAnswerEngine?.stop()
            autoAnswerEngine = null
            Toast.makeText(this, "自动答题已停止", Toast.LENGTH_SHORT).show()
            return
        }

        // 启动自动答题
        val prefsMgr = PrefsManager.getInstance(this)
        val multiAiVerify = prefsMgr.isMultiAiVerify
        val verifyIndices = prefsMgr.getMultiAiVerifyIndices()
        autoAnswerEngine = AutoAnswerEngine(this, modelProviders, multiAiVerify, verifyIndices).also { engine ->
            engine.onStatusUpdate = { status ->
                handler.post {
                    // 静默运行:仅用 Toast 显示关键状态,不弹问问窗口
                    Toast.makeText(
                        this@UnifiedFloatingService,
                        status,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            engine.onComplete = {
                handler.post {
                    autoAnswerEngine = null
                }
            }
            engine.start()
        }
        Toast.makeText(this, "自动答题已启动,请切到考试界面", Toast.LENGTH_LONG).show()
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun initAskView() {
        askViewParams.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        askViewParams.x = 0
        askViewParams.y = 0
        askContainerView = LayoutInflater.from(this).inflate(R.layout.completion_widget, null)

        //初始化模型名
        modelProviders = MyConfig.loadProviders(this)
        val titleTextView = askContainerView.findViewById<TextView>(R.id.title_text)
        if (modelProviders.isNotEmpty()) {
            titleTextView.text = modelProviders[modelIndex].displayName
        }

        //关闭事件
        askContainerView.findViewById<View>(R.id.close_btn).setOnClickListener {
            isAskViewShow = false
            if (this::askContainerView.isInitialized) windowManager.removeView(askContainerView)
            //关闭线程
            val t = thread
            if (t != null && t.isAlive) {
                t.interrupt()
                setSubmitBtnStatus(true)
            }
        }

        //提交事件
        askContainerView.findViewById<View>(R.id.submit_btn).setOnClickListener {
            // 获取用户输入
            val editText = askContainerView.findViewById<EditText>(R.id.contentInput)
            val userInput = editText.text.toString()
            if (userInput.isEmpty()) return@setOnClickListener
            // 清空输入框
            val promptTextView = askContainerView.findViewById<TextView>(R.id.userPrompt)
            val thinkTextView = askContainerView.findViewById<TextView>(R.id.thinkOutput)
            val errorTextView = askContainerView.findViewById<TextView>(R.id.errorOutput)
            val textView = askContainerView.findViewById<TextView>(R.id.contentOutput)
            // 单击AI回复内容→关闭问问窗口
            textView.setOnClickListener {
                if (isAskViewShow) {
                    isAskViewShow = false
                    windowManager.removeView(askContainerView)
                }
            }
            thinkTextView.text = ""
            errorTextView.text = ""
            textView.text = ""
            editText.setText("")
            // 切换控件显示
            if (promptTextView.visibility == View.GONE)
                promptTextView.visibility = View.VISIBLE // 显示用户输入
            if (thinkTextView.visibility == View.VISIBLE)
                thinkTextView.visibility = View.GONE // 隐藏思考
            if (errorTextView.visibility == View.VISIBLE)
                errorTextView.visibility = View.GONE // 隐藏错误信息
            promptTextView.text = userInput
            // 窗口失焦
            askViewParams.flags = askViewParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            askViewParams.flags = askViewParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            windowManager.updateViewLayout(askContainerView, askViewParams)
            // 指令
            if (userInput == "/stream") {
                usingStream = !usingStream
                textView.text = "当前流输出状态为: " + if (usingStream) "开" else "关"
                return@setOnClickListener
            } else if (userInput.startsWith("/title")) {
                val commandParams = userInput.split(" ".toRegex())
                if (commandParams.size < 2) {
                    textView.text = "请提供title参数"
                } else {
                    askViewParams.setTitle(commandParams[1])
                    windowManager.updateViewLayout(askContainerView, askViewParams)
                    textView.text = "窗口标题已设置为" + askViewParams.title
                }
                return@setOnClickListener
            }
            // 禁用提交按钮
            setSubmitBtnStatus(false)

            val currentProvider = if (modelProviders.isEmpty()) null else modelProviders[modelIndex]
            if (currentProvider == null) {
                setSubmitBtnStatus(true)
                return@setOnClickListener
            }

            try {
                thread = Thread(Runnable {
                    when {
                        // 无障碍模式 + 流式:沿用 FloatingWidgetService 的流式输出
                        mode == FloatingMode.ACCESSIBILITY && usingStream -> {
                            HttpUtils.sendPostRequestStream(
                                currentProvider.requestUrl, currentProvider.modelId, userInput,
                                currentProvider.apiKey, MyConfig.SYSTEM_PROMPT, handler, textView,
                                thinkTextView, errorTextView, this@UnifiedFloatingService,
                                (textView.parent as View).parent as ScrollView, currentProvider.enableThinking,
                                currentProvider.endpointType
                            )
                        }
                        // 无障碍模式 + 非流式:沿用 FloatingWidgetService 的手动 JSON 解析
                        mode == FloatingMode.ACCESSIBILITY -> {
                            val resText = HttpUtils.sendPostRequest(
                                currentProvider.requestUrl, currentProvider.modelId, userInput,
                                currentProvider.apiKey, MyConfig.SYSTEM_PROMPT, currentProvider.enableThinking
                            )
                            if (resText == null) return@Runnable
                            try {
                                val resJson = JSONObject(resText)
                                val choicesArray = resJson.getJSONArray("choices")
                                val firstChoice = choicesArray.getJSONObject(0)
                                val messageObject = firstChoice.getJSONObject("message")
                                val content = messageObject.getString("content")
                                handler.post {
                                    textView.text = AnswerFormatter.format(content)
                                }
                            } catch (e: JSONException) {
                                handler.post {
                                    textView.text = "Error parse JSON Content:\n$resText"
                                }
                            } finally {
                                handler.post { setSubmitBtnStatus(true) }
                            }
                        }
                        // 截图模式:只用非流式 sendPostRequest + extractContent
                        else -> {
                            val resText = HttpUtils.sendPostRequest(
                                currentProvider.requestUrl, currentProvider.modelId, userInput,
                                currentProvider.apiKey, MyConfig.SYSTEM_PROMPT,
                                currentProvider.enableThinking, currentProvider.endpointType
                            )
                            if (resText == null) {
                                handler.post {
                                    errorTextView.visibility = View.VISIBLE
                                    errorTextView.text = "请求失败,请检查网络或 API 配置"
                                    setSubmitBtnStatus(true)
                                }
                                return@Runnable
                            }
                            val content = HttpUtils.extractContent(resText, currentProvider.endpointType)
                            handler.post {
                                if (content.isEmpty()) {
                                    errorTextView.visibility = View.VISIBLE
                                    errorTextView.text = "解析失败:\n$resText"
                                } else {
                                    textView.text = AnswerFormatter.format(content)
                                }
                                setSubmitBtnStatus(true)
                            }
                        }
                    }
                    Log.d(TAG, "Thread End!")
                })
                thread?.start()
            } catch (e: Exception) {
                e.printStackTrace()
                setSubmitBtnStatus(true)
            }

        }

        //停止事件
        askContainerView.findViewById<View>(R.id.stop_btn).setOnClickListener {
            //关闭线程
            val t = thread
            if (t != null && t.isAlive) {
                t.interrupt()
                setSubmitBtnStatus(true)
            }
        }

        //选区事件
        askContainerView.findViewById<View>(R.id.copy_btn).setOnClickListener {
            showSelectZoneView()
            // 窗口失焦
            askViewParams.flags = askViewParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            askViewParams.flags = askViewParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            windowManager.updateViewLayout(askContainerView, askViewParams)
        }

        //输入框
        askContainerView.findViewById<View>(R.id.contentInput).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {//按下输入框
                //允许窗口获得焦点
                askViewParams.flags = askViewParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                askViewParams.flags = askViewParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                windowManager.updateViewLayout(askContainerView, askViewParams)
            }
            false
        }

        //移动窗口
        askContainerView.findViewById<View>(R.id.title_bar).setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = System.currentTimeMillis()

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {//按下
                        initialX = askViewParams.x
                        initialY = askViewParams.y
                        initialTime = System.currentTimeMillis()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {//抬起
                        val durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 200L && Math.abs(initialTouchX - event.rawX) < 10f && Math.abs(initialTouchY - event.rawY) < 10f) {//单击
                            //单击时切换模型
                            if (modelProviders.isNotEmpty()) {
                                if (++modelIndex == modelProviders.size) {
                                    modelIndex = 0
                                }
                                Log.d("modelIndex", modelIndex.toString())
                                //UI更新模型名
                                val titleTextView = askContainerView.findViewById<TextView>(R.id.title_text)
                                titleTextView.text = modelProviders[modelIndex].displayName
                            }
                            return v.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {//移动
                        //移动窗口
                        askViewParams.x = Math.min(Math.max(initialX + (event.rawX - initialTouchX).toInt(), -(screenWidth - askContainerView.width) / 2), (screenWidth - askContainerView.width) / 2)
                        askViewParams.y = Math.min(Math.max(initialY + (event.rawY - initialTouchY).toInt(), -(screenHeight - askContainerView.height) / 2), (screenHeight - askContainerView.height) / 2)
                        Log.d("xy", "x:" + askViewParams.x + "y:" + askViewParams.y)
                        windowManager.updateViewLayout(askContainerView, askViewParams)
                        return true
                    }
                }
                return false
            }
        })

        //切换模型
        titleTextView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = System.currentTimeMillis()

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {//按下
                        initialX = askViewParams.x
                        initialY = askViewParams.y
                        initialTime = System.currentTimeMillis()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return false
                    }
                    MotionEvent.ACTION_UP -> {//抬起
                        Log.d("ACTION_UP", modelIndex.toString())
                        val durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 200L && Math.abs(initialTouchX - event.rawX) < 10f && Math.abs(initialTouchY - event.rawY) < 10f) {//单击
                            titleTextView.performClick()
                        }
                        return false
                    }
                }
                return false
            }
        })

    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webViewParams.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        webViewParams.x = 0
        webViewParams.y = 0
        webContainerView = LayoutInflater.from(this).inflate(R.layout.web_view_widget, null)

        val webView = webContainerView.findViewById<WebView>(R.id.web_view)
        webView.setInitialScale(150)//默认缩放
        val webSettings = webView.settings
        // webSettings.setBuiltInZoomControls(true) // 启用内置缩放控件
        webSettings.javaScriptEnabled = true // 启用JavaScript
        webSettings.useWideViewPort = false //不允许页面宽度超过设备宽度
        webSettings.loadWithOverviewMode = true // 缩放至屏幕的大小
        webSettings.domStorageEnabled = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val homePageUrl = "https://cn.bing.com"
        webView.loadUrl(homePageUrl)

        //切换页面
        webView.webViewClient = object : WebViewClient() {
            val urlBox = webContainerView.findViewById<EditText>(R.id.url_box)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {// 当点击链接等行为发生时调用
                val newUrl = request.url.toString()
                // 更新URL显示
                urlBox.setText(newUrl)
                // 返回false表示由WebView自行处理加载
                return false
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // 更新URL显示
                urlBox.setText(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 更新URL显示
                urlBox.setText(url)
            }
        }

        //同步网页标签页标题
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {// 当接收到网页标题时调用
                super.onReceivedTitle(view, title)
            }
        }

        //后退
        webContainerView.findViewById<View>(R.id.go_back_btn).setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        val editText = webContainerView.findViewById<EditText>(R.id.url_box)
        //URL输入框回车键导航
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                webContainerView.findViewById<View>(R.id.go_to_btn).performClick()
                true
            } else {
                false
            }
        }

        //导航
        webContainerView.findViewById<View>(R.id.go_to_btn).setOnClickListener {
            val urlBox = webContainerView.findViewById<EditText>(R.id.url_box)
            if (urlBox.text.toString().isEmpty()) {
                webView.loadUrl(homePageUrl)
            } else {
                var inputUrl = urlBox.text.toString()
                if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                    //输入非域名触发搜索
                    inputUrl = "https://cn.bing.com/search?q=$inputUrl"
                }
                webView.loadUrl(inputUrl)
            }
        }

        //关闭
        webContainerView.findViewById<View>(R.id.close_btn).setOnClickListener {
            showWebView()
        }

        //移动窗口（仅拖动手柄）
        webContainerView.findViewById<View>(R.id.drag_handle).setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = System.currentTimeMillis()

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {//按下
                        initialX = webViewParams.x
                        initialY = webViewParams.y
                        initialTime = System.currentTimeMillis()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {//抬起
                        val durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 200L) {
                            return v.performClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {//移动
                        //移动窗口
                        webViewParams.x = Math.min(Math.max(initialX + (event.rawX - initialTouchX).toInt(), -(screenWidth - webView.width) / 2), (screenWidth - webView.width) / 2)
                        webViewParams.y = Math.min(Math.max(initialY + (event.rawY - initialTouchY).toInt(), -(screenHeight - webView.height) / 2), (screenHeight - webView.height) / 2)
                        windowManager.updateViewLayout(webContainerView, webViewParams)
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 选区初始化:根据模式分流
     * - 无障碍模式:RectangleDrawableConstraintLayout 小面板 + 屏幕节点框选提取文字
     * - 截图模式:SelectionOverlayView 全屏遮罩 + captureRegion 截图 OCR
     */
    private fun initSelectZoneView() {
        if (mode == FloatingMode.SCREENSHOT) {
            initSelectZoneViewScreenshot()
        } else {
            initSelectZoneViewAccessibility()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSelectZoneViewAccessibility() {
        selectZoneViewParams.gravity = Gravity.TOP or Gravity.START
        selectZoneViewParams.x = 0
        selectZoneViewParams.y = 200
        selectZoneView = LayoutInflater.from(this).inflate(R.layout.select_zone_widget, null)

        val container = selectZoneView.findViewById<RectangleDrawableConstraintLayout>(R.id.root_container)

        val resourceId = selectZoneView.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (resourceId > 0) selectZoneView.resources.getDimensionPixelSize(resourceId) else 0

        //移动窗口
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialTime = System.currentTimeMillis()
            private var resize = false//按住角落调整面板大小
            private var holdingCorner = 0//0左上 1左下 2右上 3右下
            val cornerDistance = 100//按住角落调整面板大小判定区到面板边缘的距离
            val layout = selectZoneView.findViewById<RectangleDrawableConstraintLayout>(R.id.root_container)
            val contentDisplay = selectZoneView.findViewById<TextView>(R.id.contentOutput)
            val deltaOffset = IntArray(2)
            var rightEdge = 0
            var bottomEdge = 0
            var statusBarHeight = statusHeight

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.pointerCount > 1) return false//不处理多指事件

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {//按下
                        initialX = selectZoneViewParams.x
                        initialY = selectZoneViewParams.y
                        initialTime = System.currentTimeMillis()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        // 检查是否在角落
                        isInCorner(event, deltaOffset)

                        //双指缩放完需要抬起双指才能再次进行移动操作
                        container.isResizingDown = false
                        container.blueLine = resize

                        //隐藏按钮
                        for (i in 0 until layout.childCount) {
                            val view = layout.getChildAt(i)
                            if (view is Button) {
                                view.visibility = View.INVISIBLE
                            }
                        }
                        //清除矩形绘图
                        layout.ClearRectangleDraw()
                        contentDisplay.text = "扫描中...单击获取结果"

                        return false
                    }
                    MotionEvent.ACTION_UP -> {//抬起
                        resize = false
                        if (container.blueLine) {
                            container.blueLine = false
                            container.invalidate()
                        }
                        val durationTime = System.currentTimeMillis() - initialTime
                        if (durationTime < 200L && Math.abs(initialTouchX - event.rawX) < 10f && Math.abs(initialTouchY - event.rawY) < 10f) {
                            //触发重绘
                            val resText = layout.UpdateRectangleDraw()
                            //显示按钮
                            for (i in 0 until layout.childCount) {
                                val view = layout.getChildAt(i)
                                if (view is Button) {
                                    view.visibility = View.VISIBLE
                                }
                            }
                            //更新UI
                            if (resText.toString().isEmpty()) {
                                contentDisplay.text = "什么都木有..."
                            } else {
                                contentDisplay.text = resText
                            }

                            return v.performClick()
                        }
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {//移动
                        if (resize) {
                            //调整窗口大小
                            updateSizeFromCorner(event, deltaOffset)
                            return true
                        } else if (!container.isResizingDown) {
                            //移动窗口
                            selectZoneViewParams.x = Math.min(Math.max(initialX + (event.rawX - initialTouchX).toInt(), 0), screenWidth - selectZoneView.width)
                            selectZoneViewParams.y = Math.min(Math.max(initialY + (event.rawY - initialTouchY).toInt(), 0), screenHeight - selectZoneView.height)
                            windowManager.updateViewLayout(selectZoneView, selectZoneViewParams)
                        }
                        return true
                    }
                }
                return false
            }

            private fun isInCorner(event: MotionEvent, offset: IntArray) {
                val location = IntArray(2)
                selectZoneView.getLocationOnScreen(location)
                rightEdge = location[0] + selectZoneView.width
                bottomEdge = location[1] + selectZoneView.height//原点在屏幕左上角，包括状态栏的高度

                if (event.rawX < (location[0] + cornerDistance).toFloat() && event.rawY < (location[1] + cornerDistance).toFloat()) {
                    //左上角
                    resize = true
                    holdingCorner = 1
                } else if (event.rawX < (location[0] + cornerDistance).toFloat() && event.rawY > (bottomEdge - cornerDistance).toFloat()) {
                    //左下角
                    resize = true
                    holdingCorner = 2
                } else if (event.rawX > (rightEdge - cornerDistance).toFloat() && event.rawY < (location[1] + cornerDistance).toFloat()) {
                    //右上角
                    resize = true
                    holdingCorner = 3
                } else if (event.rawX > (rightEdge - cornerDistance).toFloat() && event.rawY > (bottomEdge - cornerDistance).toFloat()) {
                    //右下角
                    resize = true
                    holdingCorner = 4
                } else {
                    resize = false
                }
                //返回从窗口左上角到触控点的向量
                offset[0] = event.rawX.toInt() - location[0]
                offset[1] = event.rawY.toInt() - location[1]
                Log.d("TAG", "windowHeight" + container.height + " BottomEdge" + bottomEdge + " location" + Arrays.toString(location) + " paramx" + selectZoneViewParams.x + " barheight" + statusHeight)
                Log.d("TGA", "resize$resize corner$holdingCorner")
                Log.d("TGA", "offset" + Arrays.toString(offset))
            }

            private fun updateSizeFromCorner(event: MotionEvent, deltaOffset: IntArray) {
                val location = IntArray(2)
                selectZoneView.getLocationOnScreen(location)

                val params = layout.layoutParams

                if (holdingCorner == 1) {
                    //左上角
                    selectZoneViewParams.x = event.rawX.toInt() - deltaOffset[0]
                    params.width = rightEdge - selectZoneViewParams.x
                    selectZoneViewParams.y = event.rawY.toInt() - deltaOffset[1] - statusHeight
                    params.height = bottomEdge - selectZoneViewParams.y - statusHeight
                } else if (holdingCorner == 2) {
                    //左下角
                    selectZoneViewParams.x = event.rawX.toInt() - deltaOffset[0]
                    params.width = rightEdge - selectZoneViewParams.x
                    params.height = event.rawY.toInt() - location[1] + (bottomEdge - location[1]) - deltaOffset[1]
                } else if (holdingCorner == 3) {
                    //右上角
                    params.width = event.rawX.toInt() - location[0] + (rightEdge - location[0]) - deltaOffset[0]
                    selectZoneViewParams.y = event.rawY.toInt() - deltaOffset[1] - statusHeight
                    params.height = bottomEdge - selectZoneViewParams.y - statusHeight
                } else if (holdingCorner == 4) {
                    //右下角
                    params.width = event.rawX.toInt() - location[0] + (rightEdge - location[0]) - deltaOffset[0]
                    params.height = event.rawY.toInt() - location[1] + (bottomEdge - location[1]) - deltaOffset[1]
                }
                //应用窗口位置
                windowManager.updateViewLayout(selectZoneView, selectZoneViewParams)
                //应用窗口大小
                params.width = Math.min(params.width, screenWidth)
                params.height = Math.min(params.height, screenHeight)
                selectZoneView.layoutParams = params
            }
        })

        //关闭
        selectZoneView.findViewById<View>(R.id.close_btn).setOnClickListener {
            showSelectZoneView()
        }

        //复制
        selectZoneView.findViewById<View>(R.id.copy_btn).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val textView = selectZoneView.findViewById<TextView>(R.id.contentOutput)
            val data = textView.text.toString()
            val clip = ClipData.newPlainText("content", data)
            clipboard.setPrimaryClip(clip)
        }

        //问AI
        selectZoneView.findViewById<View>(R.id.ask_btn).setOnClickListener {
            val layout = selectZoneView.findViewById<RectangleDrawableConstraintLayout>(R.id.root_container)
            val contentDisplay = selectZoneView.findViewById<TextView>(R.id.contentOutput)
            val askInput = askContainerView.findViewById<EditText>(R.id.contentInput)
            val textView = selectZoneView.findViewById<TextView>(R.id.contentOutput)
            if (!isAskViewShow) showAskView()
            askInput.setText(textView.text)
            //隐藏按钮
            for (i in 0 until layout.childCount) {
                val view = layout.getChildAt(i)
                if (view is Button) {
                    view.visibility = View.INVISIBLE
                }
            }
            //清除矩形绘图
            layout.ClearRectangleDraw()
            contentDisplay.text = "扫描中...单击获取结果"
            //自动关闭选区窗口
            if (isSelectZoneViewShow) showSelectZoneView()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSelectZoneViewScreenshot() {
        // 截图模式:全屏遮罩
        selectZoneViewParams.width = WindowManager.LayoutParams.MATCH_PARENT
        selectZoneViewParams.height = WindowManager.LayoutParams.MATCH_PARENT
        selectZoneViewParams.gravity = Gravity.TOP or Gravity.START
        selectZoneViewParams.x = 0
        selectZoneViewParams.y = 0

        selectZoneView = LayoutInflater.from(this).inflate(R.layout.selection_overlay, null)
        selectionOverlay = selectZoneView.findViewById(R.id.selection_view)
        selectionButtons = selectZoneView.findViewById(R.id.bottom_buttons)

        // 框选完成回调:显示底部按钮
        selectionOverlay.onSelectionComplete = { _ ->
            selectionButtons?.visibility = View.VISIBLE
        }

        // 返回:关闭选区,恢复悬浮球
        selectZoneView.findViewById<View>(R.id.btn_back).setOnClickListener {
            showSelectZoneView()
        }

        // 重新框选:清除当前选区
        selectZoneView.findViewById<View>(R.id.btn_reselect).setOnClickListener {
            selectionOverlay.resetSelection()
            selectionButtons?.visibility = View.GONE
        }

        // 问AI:截图选区并发送
        selectZoneView.findViewById<View>(R.id.btn_ask_ai).setOnClickListener {
            captureAndAsk()
        }
    }

    //</editor-fold>

    //<editor-fold desc="截图模式:MediaProjection / captureRegion / captureAndAsk">

    /**
     * 设置 MediaProjection 并准备 ImageReader
     */
    @SuppressLint("WrongConstant")
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            // ImageReader 用于捕获屏幕帧
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
            )
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotCapture",
                screenWidth, screenHeight, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            Log.d(TAG, "MediaProjection 已就绪")
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 初始化失败", e)
            Toast.makeText(this, "截屏初始化失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 从 ImageReader 获取当前屏幕帧并裁剪到选区
     */
    private fun captureRegion(rect: Rect): ByteArray? {
        val reader = imageReader ?: return null
        var image: Image? = null
        // 最多重试 10 次,避免无限递归导致栈溢出
        var retries = 0
        while (retries < 10) {
            image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e(TAG, "acquireLatestImage failed", e)
                null
            }
            if (image != null) break
            retries++
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (image == null) return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            // 创建全屏 bitmap
            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            fullBitmap.copyPixelsFromBuffer(buffer)

            // 裁剪到选区(确保边界合法)
            val left = rect.left.coerceIn(0, screenWidth - 1)
            val top = rect.top.coerceIn(0, screenHeight - 1)
            val right = rect.right.coerceIn(left + 1, screenWidth)
            val bottom = rect.bottom.coerceIn(top + 1, screenHeight)
            val w = right - left
            val h = bottom - top
            val cropped = Bitmap.createBitmap(fullBitmap, left, top, w, h)

            // 转 PNG
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            fullBitmap.recycle()
            if (cropped !== fullBitmap) cropped.recycle()
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "captureRegion failed", e)
            null
        } finally {
            image.close()
        }
    }

    /**
     * 截屏选区并发送给 AI OCR
     */
    private fun captureAndAsk() {
        if (mediaProjection == null || imageReader == null) {
            Toast.makeText(this, "截屏未就绪,请重新启动", Toast.LENGTH_SHORT).show()
            return
        }
        if (modelProviders.isEmpty()) {
            Toast.makeText(this, "请先配置 API", Toast.LENGTH_SHORT).show()
            return
        }

        // 从 SelectionOverlayView 获取选区
        val rect = selectionOverlay.getSelectionRect()
        if (rect == null) {
            Toast.makeText(this, "请先框选区域", Toast.LENGTH_SHORT).show()
            return
        }

        // 隐藏选区遮罩,避免截到自身
        if (isSelectZoneViewShow) {
            windowManager.removeView(selectZoneView)
            isSelectZoneViewShow = false
        }
        floatToggleBtnView?.visibility = View.INVISIBLE

        // 显示问问窗口(用于展示答案)
        if (!isAskViewShow) showAskView()
        val contentOutput = askContainerView.findViewById<TextView>(R.id.contentOutput)
        val errorOutput = askContainerView.findViewById<TextView>(R.id.errorOutput)
        contentOutput.text = "正在截图识别..."
        errorOutput.visibility = View.GONE
        errorOutput.text = ""

        handler.postDelayed({
            thread = Thread(Runnable {
                val imageBytes = captureRegion(rect)
                // 恢复悬浮球显示
                handler.post { floatToggleBtnView?.visibility = View.VISIBLE }
                if (imageBytes == null) {
                    handler.post {
                        errorOutput.visibility = View.VISIBLE
                        errorOutput.text = "截图失败,请重试"
                    }
                    return@Runnable
                }

                val provider = modelProviders[modelIndex]
                val response = HttpUtils.sendImageRequest(
                    provider.requestUrl, provider.modelId, imageBytes,
                    provider.apiKey, MyConfig.SYSTEM_PROMPT, provider.endpointType
                )
                if (response == null) {
                    handler.post {
                        errorOutput.visibility = View.VISIBLE
                        errorOutput.text = "请求失败,请检查网络或 API 配置(确认模型支持视觉)"
                    }
                    return@Runnable
                }
                val content = HttpUtils.extractContent(response, provider.endpointType)
                handler.post {
                    if (content.isEmpty()) {
                        errorOutput.visibility = View.VISIBLE
                        errorOutput.text = "解析失败:\n$response"
                    } else {
                        contentOutput.text = AnswerFormatter.format(content)
                    }
                }
            }).also { it.start() }
        }, 200) // 200ms 延迟确保窗口已隐藏
    }

    //</editor-fold>

    //<editor-fold desc="窗口显隐">

    private fun isFloatingBallOnLeftSide(): Boolean {
        // 获取悬浮窗位置
        val location = IntArray(2)
        val btn = floatToggleBtnView ?: return false
        btn.getLocationOnScreen(location)
        val viewX = location[0]
        // 比较viewX和屏幕宽度的一半
        return viewX < screenWidth / 2
    }

    private fun showPopupMenu() {
        if (!isPopupMenuShow) {
            if (secureCountListenerThread?.isAlive == true && inExamMode && outerSecureWindowCount == 0)
                return // 截屏瞬间不允许打开窗口
            // 根据勾选 ∩ 模式刷新菜单显隐
            updateMenuVisibility()
            // 定位
            if (isFloatingBallOnLeftSide()) {
                popupMenuViewParams.x = floatToggleBtnParams.x + 140
            } else {
                popupMenuViewParams.x = floatToggleBtnParams.x - 220
            }
            popupMenuViewParams.y = floatToggleBtnParams.y
            // 添加小白点悬浮窗
            isPopupMenuShow = true
            windowManager.addView(popupMenuView, popupMenuViewParams)
        } else {
            // 如果已经显示，则隐藏
            if (popupMenuView.windowToken != null && popupMenuView.isAttachedToWindow) {
                isPopupMenuShow = false
                windowManager.removeView(popupMenuView)
            }
        }
    }

    private fun showAskView() {
        modelProviders = MyConfig.loadProviders(this)
        if (modelProviders.isNotEmpty() && modelIndex >= modelProviders.size) {
            modelIndex = 0
        }
        if (!isAskViewShow) {
            isAskViewShow = true
            windowManager.addView(askContainerView, askViewParams)
        } else {
            if (askContainerView.windowToken != null && askContainerView.isAttachedToWindow) {
                isAskViewShow = false
                windowManager.removeView(askContainerView)
            }
        }
    }

    private fun showWebView() {
        val webView = webContainerView.findViewById<WebView>(R.id.web_view)
        if (!isWebViewShow) {
            isWebViewShow = true
            windowManager.addView(webContainerView, webViewParams)
            webView.onResume()
        } else {
            if (webContainerView.windowToken != null && webContainerView.isAttachedToWindow) {
                isWebViewShow = false
                windowManager.removeView(webContainerView)
                webView.onPause()
            }
        }
    }

    private fun showSelectZoneView() {
        if (mode == FloatingMode.SCREENSHOT) {
            showSelectZoneViewScreenshot()
        } else {
            showSelectZoneViewAccessibility()
        }
    }

    private fun showSelectZoneViewAccessibility() {
        if (!isSelectZoneViewShow) {
            isSelectZoneViewShow = true
            windowManager.addView(selectZoneView, selectZoneViewParams)
        } else {
            if (selectZoneView.windowToken != null && selectZoneView.isAttachedToWindow) {
                isSelectZoneViewShow = false
                windowManager.removeView(selectZoneView)
            }
        }
    }

    private fun showSelectZoneViewScreenshot() {
        if (!isSelectZoneViewShow) {
            isSelectZoneViewShow = true
            // 隐藏悬浮球和菜单,避免干扰框选
            floatToggleBtnView?.visibility = View.INVISIBLE
            if (isPopupMenuShow) {
                windowManager.removeView(popupMenuView)
                isPopupMenuShow = false
            }
            // 重置选区状态
            selectionOverlay.resetSelection()
            selectionButtons?.visibility = View.GONE
            windowManager.addView(selectZoneView, selectZoneViewParams)
        } else {
            if (selectZoneView.windowToken != null && selectZoneView.isAttachedToWindow) {
                isSelectZoneViewShow = false
                windowManager.removeView(selectZoneView)
                // 恢复悬浮球显示
                floatToggleBtnView?.visibility = View.VISIBLE
            }
        }
    }

    private fun hideAllWindows() {
        if (isPopupMenuShow) showPopupMenu()
        if (isAskViewShow) showAskView()
        if (isWebViewShow) showWebView()
        if (isSelectZoneViewShow) showSelectZoneView()
    }

    //</editor-fold>

    // ═══ 设置热更新:响应 PrefsManager 变化 ═══

    /** 防截屏开关变化时实时应用 */
    private fun applyForbidCaptureChange(enabled: Boolean) {
        if (enabled) {
            // 开启防截屏:启动藏匿模式监听
            try {
                launcherSecureCountListener()
            } catch (e: Exception) {
                Log.d(TAG, "藏匿模式启动失败: " + e.message)
            }
        } else {
            // 关闭防截屏:停止监听 + 清除所有窗口 FLAG_SECURE + 恢复悬浮球
            shutdownSecureCountListener()
            inExamMode = false
            outerSecureWindowCount = 0
            floatToggleBtnParams.flags = floatToggleBtnParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            popupMenuViewParams.flags = popupMenuViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            askViewParams.flags = askViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            webViewParams.flags = webViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            selectZoneViewParams.flags = selectZoneViewParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            handler.post {
                val btnView = floatToggleBtnView
                if (btnView != null && btnView.isAttachedToWindow) {
                    windowManager.updateViewLayout(btnView, floatToggleBtnParams)
                }
                floatLogo?.setBackgroundResource(R.drawable.rounded_circle)
            }
            applyTransparentFloat()
        }
    }

    /** 半透明悬浮球开关变化时实时应用 alpha */
    private fun applyTransparentFloat() {
        // 截屏中(inExamMode 且 num==0)保持 0.05f,不覆盖
        if (inExamMode && outerSecureWindowCount == 0) return
        val alpha = if (inExamMode) {
            // 藏匿模式 num>0:0.25f / 0.6f
            if (transparentFloat) 0.25f else 0.6f
        } else {
            // 正常状态:0.25f / 1.0f(默认)
            if (transparentFloat) 0.25f else 1.0f
        }
        floatToggleBtnView?.alpha = alpha
    }

    @Throws(InterruptedException::class)
    fun setOuterSecureWindowCount(num: Int) {
        if (forbidCapture) {
            outerSecureWindowCount = num
            if (num > 0) {
                // 检测到考试APP的FLAG_SECURE窗口
                inExamMode = true
                // 截屏结束/正常考试中：恢复悬浮球，用户可继续操作
                floatToggleBtnParams.flags = floatToggleBtnParams.flags or WindowManager.LayoutParams.FLAG_SECURE // 防录屏
                handler.post {
                    val btnView = floatToggleBtnView
                    if (btnView != null && btnView.isAttachedToWindow) {
                        windowManager.updateViewLayout(btnView, floatToggleBtnParams)
                    }
                    floatLogo?.setBackgroundResource(R.drawable.rounded_circle)
                }
                floatToggleBtnView?.alpha = if (transparentFloat) 0.25f else 0.6f
            } else if (num == 0 && inExamMode) {
                // 考试中FLAG_SECURE突然消失=正在截屏
                hideAllWindows()
                // 悬浮球极淡透明+去FLAG_SECURE，截屏端几乎不可见但保留点触
                floatToggleBtnParams.flags = floatToggleBtnParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                handler.post {
                    val btnView = floatToggleBtnView
                    if (btnView != null && btnView.isAttachedToWindow) {
                        windowManager.updateViewLayout(btnView, floatToggleBtnParams)
                        floatLogo?.setBackgroundResource(R.drawable.rounded_circle)
                    }
                }
                floatToggleBtnView?.alpha = 0.05f
            }
            // 如果 num==0 且 !inExamMode，说明不在考试中，不做任何操作
        }
    }

    @Throws(InterruptedException::class)
    private fun exitHideMode() {
        shutdownSecureCountListener()
        inExamMode = false
        // 恢复悬浮球
        floatToggleBtnParams.flags = floatToggleBtnParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        handler.post {
            val btnView = floatToggleBtnView
            if (btnView != null && btnView.isAttachedToWindow) {
                windowManager.updateViewLayout(btnView, floatToggleBtnParams)
            }
            floatLogo?.setBackgroundResource(R.drawable.rounded_circle)
        }
        floatToggleBtnView?.alpha = if (transparentFloat) 0.25f else 0.6f
    }

    /**
     * 提交/停止按钮状态切换。
     * HttpUtils.sendPostRequestStream(UnifiedFloatingService 重载)在流式结束后回调此方法。
     */
    fun setSubmitBtnStatus(active: Boolean) {
        val submit = askContainerView.findViewById<Button>(R.id.submit_btn)
        val stop = askContainerView.findViewById<Button>(R.id.stop_btn)
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

    @Volatile
    private var secureCounterRunning = false
    private var secureCountListenerThread: Thread? = null

    private fun launcherSecureCountListener() {
        if (secureCountListenerThread?.isAlive == true) {
            return
        }
        secureCounterRunning = true
        secureCountListenerThread = Thread {
            Log.d(TAG, "启动监听进程")
            try {
                val commandHelper = AdbCommandHelper(this@UnifiedFloatingService)
                while (secureCounterRunning) {
                    setOuterSecureWindowCount(commandHelper.GetOuterSecureWindowCount())
                    Thread.sleep(SECURE_COUNT_POLL_INTERVAL_MS)
                }
            } catch (e: RemoteException) {
                Log.d(TAG, "Shizuku调用出现错误")
                e.printStackTrace()
            } catch (e: UserNotAuthenticatedException) {
                shutdownSecureCountListener()
                Log.d(TAG, "藏匿模式: Shizuku 未授权，监听已停止")
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            Log.d(TAG, "隐私窗口统计线程结束")
        }
        secureCountListenerThread?.start()
    }

    private fun shutdownSecureCountListener() {
        secureCounterRunning = false
        val listenerThread = secureCountListenerThread
        if (listenerThread != null) {
            listenerThread.interrupt()
            if (Thread.currentThread() != listenerThread) {
                try {
                    listenerThread.join(SECURE_COUNT_POLL_INTERVAL_MS * 2)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (!listenerThread.isAlive) {
                secureCountListenerThread = null
            }
        }
    }
}
