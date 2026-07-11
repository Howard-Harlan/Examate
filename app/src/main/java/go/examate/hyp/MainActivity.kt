package go.examate.hyp

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.service.UnifiedFloatingService
import go.examate.hyp.service.MyAccessibilityService
import go.examate.hyp.ui.MainScreenRoot
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.util.ShizukuServiceManager
import go.examate.hyp.util.StringCounter
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CODE = 1
    }

    // ViewModel 持有全部 UI 状态;非 Composable 方法(如 updatePermissionStatus)通过该字段访问
    private val mainViewModel: MainViewModel by lazy { ViewModelProvider(this)[MainViewModel::class.java] }

    private var serviceManager: ShizukuServiceManager? = null
    private var userService: IUserService? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            onRequestPermissionsResult(grantResult)
        }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updatePermissionStatus() }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        requestPermissionLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        Toast.makeText(this, "请把后台使用情况改为无限制", Toast.LENGTH_SHORT).show()
    }

    // 截屏授权结果:获取resultCode和data,传给UnifiedFloatingService(截图模式)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, UnifiedFloatingService::class.java).apply {
                putExtra(UnifiedFloatingService.EXTRA_MODE, "screenshot")
                putExtra(UnifiedFloatingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(UnifiedFloatingService.EXTRA_RESULT_DATA, result.data)
            }
            startService(intent)
            Toast.makeText(this, "启动截图搜题", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "截屏授权被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 来自引导页:自动跳转到 API 设置
        if (intent.getBooleanExtra("goto_api_config", false)) {
            startActivity(Intent(this, ApiConfigActivity::class.java))
        }

        // 设置项初始值已由 MainViewModel 的 init 块从 PrefsManager 读取
        // 显示授权状态
        updatePermissionStatus()

        // ═══ 直接 setContent,无需 ComposeView 嵌套 ═══
        setContent {
            val viewModel = viewModel<MainViewModel>()
            val overlayGranted by viewModel.overlayGrantedState.collectAsState()
            val accessibilityGranted by viewModel.accessibilityGrantedState.collectAsState()
            val batteryGranted by viewModel.batteryGrantedState.collectAsState()
            val shizukuGranted by viewModel.shizukuGrantedState.collectAsState()
            val forbidCapture by viewModel.forbidCaptureState.collectAsState()
            val edgeAttach by viewModel.edgeAttachState.collectAsState()
            val transparentFloat by viewModel.transparentFloatState.collectAsState()
            val multiAiVerify by viewModel.multiAiVerifyState.collectAsState()
            val providerCount by viewModel.providerCountState.collectAsState()

            ExamateTheme {
                MainScreenRoot(
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    batteryGranted = batteryGranted,
                    shizukuGranted = shizukuGranted,
                    forbidCapture = forbidCapture,
                    edgeAttach = edgeAttach,
                    transparentFloat = transparentFloat,
                    multiAiVerify = multiAiVerify,
                    providerCount = providerCount,
                    onLaunchClick = { startManualMode() },
                    onShizukuClick = { startShizukuMode() },
                    onScreenshotClick = { startScreenshotMode() },
                    onChatClick = {
                        startActivity(Intent(this@MainActivity, ChatActivity::class.java))
                    },
                    onLanChatClick = {
                        startActivity(Intent(this@MainActivity, LanChatActivity::class.java))
                    },
                    onCampusServiceClick = {
                        startActivity(Intent(this@MainActivity, CampusServiceActivity::class.java))
                    },
                    onUserSettingsClick = {
                        startActivity(Intent(this@MainActivity, UserSettingsActivity::class.java))
                    },
                    onApiConfigClick = {
                        startActivity(Intent(this@MainActivity, ApiConfigActivity::class.java))
                    },
                    onAgentSettingsClick = {
                        startActivity(Intent(this@MainActivity, AgentSettingsActivity::class.java))
                    },
                    onAboutClick = {
                        startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                    },
                    onSearchSettingsClick = {
                        startActivity(Intent(this@MainActivity, SearchSettingsActivity::class.java))
                    },
                    onBatteryOptClick = { requestBatteryOptimization() },
                    onForbidCaptureChanged = { value ->
                        viewModel.setForbidCapture(value)
                    },
                    onEdgeAttachChanged = { value ->
                        viewModel.setEdgeAttach(value)
                    },
                    onTransparentFloatChanged = { value ->
                        viewModel.setTransparentFloat(value)
                    },
                    onMultiAiVerifyChanged = { value ->
                        viewModel.setMultiAiVerify(value)
                    }
                )
            }
        }

        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        mainViewModel.updateOverlayGranted(Settings.canDrawOverlays(this))
        mainViewModel.updateAccessibilityGranted(MyAccessibilityService.getMyAccessibilityServiceInstance() != null)
        mainViewModel.updateBatteryGranted(checkIgnoreBatteryOptimization(this))
        mainViewModel.updateShizukuGranted(isShizukuGranted())
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // ═══ 启动手动模式 ═══
    private fun startManualMode() {
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            MyAccessibilityService.getMyAccessibilityServiceInstance() == null -> {
                Toast.makeText(this, "请授权无障碍权限", Toast.LENGTH_SHORT).show()
                requestPermissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !checkIgnoreBatteryOptimization(this) -> {
                Toast.makeText(this, "建议开启无限制省电策略以获得更稳定的后台运行", Toast.LENGTH_LONG).show()
                startService(Intent(this, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                })
            }
            else -> {
                Toast.makeText(this, "启动手动模式", Toast.LENGTH_SHORT).show()
                startService(Intent(this, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                })
            }
        }
    }

    // ═══ 启动藏匿模式(Shizuku)═══
    private fun startShizukuMode() {
        if (checkShizukuPermission(REQUEST_CODE)) {
            try {
                if (userService == null) {
                    bindUserService()
                } else {
                    executeCommand("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
                    executeCommand("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
                    executeCommand("dumpsys window windows")
                }
            } catch (e: Exception) {
                Toast.makeText(this, "执行错误", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        } else {
            Toast.makeText(this, "Shizuku启动失败,请检查授权情况", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══ 启动截图搜题模式 ═══
    private fun startScreenshotMode() {
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            else -> {
                // 请求 MediaProjection 截屏授权
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    // ═══ 电池优化 ═══
    private fun requestBatteryOptimization() {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            batteryOptimizationLauncher.launch(this)
        }
    }

    private fun checkIgnoreBatteryOptimization(activity: Activity): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(activity.packageName)
    }

    // ═══ Shizuku ═══
    private fun executeCommand(command: String) {
        Log.d("TAG", "executeCommand: $command")
        try {
            val service = userService ?: return
            val result = service.exec(command)
            val count = StringCounter.countLinesStartingAndContaining(result.stdout)
            mainHandler.post {
                Toast.makeText(this, "隐私窗口个数为$count", Toast.LENGTH_SHORT).show()
            }
        } catch (e: RemoteException) {
            Toast.makeText(this, "执行错误", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bindUserService() {
        serviceManager = ShizukuServiceManager()
        serviceManager?.bind(this, object : ShizukuServiceManager.OnBindResultCallback {
            override fun onResult(success: Boolean, errorMessage: String?, service: IUserService?) {
                mainHandler.post {
                    if (success) {
                        userService = service
                        Toast.makeText(this@MainActivity, "服务绑定成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "服务绑定失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun onRequestPermissionsResult(grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkShizukuPermission(code: Int): Boolean {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku未安装", Toast.LENGTH_SHORT).show()
            return false
        }
        if (Shizuku.isPreV11()) {
            Toast.makeText(this, "请更新Shizuku", Toast.LENGTH_SHORT).show()
            return false
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(this, "权限被拒绝且不再询问", Toast.LENGTH_SHORT).show()
            false
        } else {
            Shizuku.requestPermission(code)
            false
        }
    }
}
