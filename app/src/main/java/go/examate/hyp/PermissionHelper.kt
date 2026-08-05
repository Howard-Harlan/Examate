package go.examate.hyp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import go.examate.hyp.service.MyAccessibilityService
import go.examate.hyp.service.UnifiedFloatingService
import go.examate.hyp.util.ShizukuServiceManager
import go.examate.hyp.util.StringCounter
import go.examate.hyp.util.ToastUtil
import rikka.shizuku.Shizuku

/**
 * MainActivity 的权限与 Shizuku 相关逻辑抽取。
 *
 * 职责:
 *  - 查询悬浮窗/无障碍/电池优化/Shizuku 授权状态
 *  - 注册 Shizuku 权限回调并在 onDestroy 时反注册
 *  - 绑定/解绑 Shizuku UserService,执行特权命令
 *  - 暴露 [requestPermissionLauncher]/[batteryOptimizationLauncher]/[mediaProjectionLauncher]
 *    供 Activity 在 onCreate 中注册
 *  - 启动各种模式(手动/截图/Shizuku),并触发 [onPermissionStatusChanged] 回调
 *
 * 不持有 UI 状态,状态更新通过回调 [onPermissionStatusChanged] 通知调用方。
 */
class PermissionHelper(private val activity: ComponentActivity) {

    companion object {
        private const val REQUEST_CODE = 1
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceManager: ShizukuServiceManager? = null
    private var userService: IUserService? = null

    /** 权限状态变化时回调(主线程),调用方据此更新 ViewModel */
    var onPermissionStatusChanged: (() -> Unit)? = null

    // ═══ Shizuku 权限回调 ═══
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ToastUtil.show(activity, "授权成功")
            }
        }

    // ═══ 通用权限请求 launcher(悬浮窗/无障碍设置页) ═══
    val requestPermissionLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onPermissionStatusChanged?.invoke()
        }

    // ═══ 电池优化设置页 launcher ═══
    val batteryOptimizationLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            requestPermissionLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            ToastUtil.show(activity, "请把后台使用情况改为无限制")
        }

    // ═══ 截屏授权 launcher ═══
    val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(activity, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "screenshot")
                    putExtra(UnifiedFloatingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(UnifiedFloatingService.EXTRA_RESULT_DATA, result.data)
                }
                activity.startService(intent)
                ToastUtil.show(activity, "启动截图搜题")
            } else {
                ToastUtil.show(activity, "截屏授权被拒绝")
            }
        }

    /** onCreate 中调用:注册 Shizuku 监听 */
    fun onCreate() {
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
    }

    /** onDestroy 中调用:反注册监听 + 解绑服务,避免泄漏 */
    fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        } catch (_: Exception) { }
        serviceManager?.unbind(activity)
        serviceManager = null
        userService = null
    }

    // ═══ 权限状态查询 ═══

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(activity)
    fun isAccessibilityGranted(): Boolean =
        MyAccessibilityService.getMyAccessibilityServiceInstance() != null
    fun isBatteryGranted(): Boolean = checkIgnoreBatteryOptimization(activity)
    fun isShizukuGranted(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    private fun checkIgnoreBatteryOptimization(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ═══ 启动各种模式 ═══

    /** 启动手动模式(无障碍读屏) */
    fun startManualMode() {
        when {
            !isOverlayGranted() -> {
                ToastUtil.show(activity, "请授权悬浮窗权限")
                requestPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            }
            !isAccessibilityGranted() -> {
                ToastUtil.show(activity, "请授权无障碍权限")
                requestPermissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !isBatteryGranted() -> {
                ToastUtil.showLong(activity, "建议开启无限制省电策略以获得更稳定的后台运行")
                activity.startService(Intent(activity, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                })
            }
            else -> {
                ToastUtil.show(activity, "启动手动模式")
                activity.startService(Intent(activity, UnifiedFloatingService::class.java).apply {
                    putExtra(UnifiedFloatingService.EXTRA_MODE, "accessibility")
                })
            }
        }
    }

    /** 启动截图搜题模式 */
    fun startScreenshotMode() {
        when {
            !isOverlayGranted() -> {
                ToastUtil.show(activity, "请授权悬浮窗权限")
                requestPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            }
            else -> {
                val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
    }

    /** 启动 Shizuku 藏匿模式 */
    fun startShizukuMode() {
        if (checkShizukuPermission()) {
            try {
                if (userService == null) {
                    bindUserService()
                } else {
                    executeCommand("pm grant ${activity.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    executeCommand("appops set ${activity.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                    executeCommand("dumpsys window windows")
                }
            } catch (e: Exception) {
                ToastUtil.show(activity, "执行错误")
                e.printStackTrace()
            }
        } else {
            ToastUtil.show(activity, "Shizuku启动失败,请检查授权情况")
        }
    }

    /** 请求加入电池优化白名单 */
    fun requestBatteryOptimization() {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
            batteryOptimizationLauncher.launch(this)
        }
    }

    // ═══ Shizuku 内部 ═══

    private fun checkShizukuPermission(): Boolean {
        if (!Shizuku.pingBinder()) {
            ToastUtil.show(activity, "Shizuku未安装")
            return false
        }
        if (Shizuku.isPreV11()) {
            ToastUtil.show(activity, "请更新Shizuku")
            return false
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            ToastUtil.show(activity, "权限被拒绝且不再询问")
            false
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            false
        }
    }

    private fun executeCommand(command: String) {
        try {
            val service = userService ?: return
            val result = service.exec(command)
            val count = StringCounter.countLinesStartingAndContaining(result.stdout)
            ToastUtil.show(activity, "隐私窗口个数为$count")
        } catch (e: RemoteException) {
            ToastUtil.show(activity, "执行错误")
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun bindUserService() {
        serviceManager = ShizukuServiceManager()
        serviceManager?.bind(activity, object : ShizukuServiceManager.OnBindResultCallback {
            override fun onResult(success: Boolean, errorMessage: String?, service: IUserService?) {
                mainHandler.post {
                    if (success) {
                        userService = service
                        ToastUtil.show(activity, "服务绑定成功")
                    } else {
                        ToastUtil.show(activity, "服务绑定失败")
                    }
                }
            }
        })
    }
}
