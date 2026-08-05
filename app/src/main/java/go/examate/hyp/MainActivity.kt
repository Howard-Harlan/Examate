package go.examate.hyp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.ui.MainScreenRoot
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 主界面 Activity。
 *
 * 职责已收敛为:
 *  1. 注册/反注册 [PermissionHelper](权限查询、Shizuku、launcher)
 *  2. setContent 渲染 [MainScreenRoot],通过 [MainNavigator] 处理 9 个功能页跳转
 *  3. 通过 [MainViewModel] 持有 UI 状态
 *
 * 权限查询、Shizuku 绑定、模式启动等业务逻辑全部委托给 [permissionHelper]。
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private lateinit var permissionHelper: PermissionHelper
    private val navigator by lazy { MainNavigator(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化权限助手(内部会注册 launcher 与 Shizuku 监听)
        permissionHelper = PermissionHelper(this).apply {
            onCreate()
            onPermissionStatusChanged = { updatePermissionStatus() }
        }

        // 来自引导页:自动跳转到 API 设置
        if (intent.getBooleanExtra("goto_api_config", false)) {
            navigator.gotoApiConfig()
        }

        updatePermissionStatus()

        setContent {
            val viewModel = viewModel<MainViewModel>()
            val overlayGranted by viewModel.overlayGrantedState.collectAsStateWithLifecycle()
            val accessibilityGranted by viewModel.accessibilityGrantedState.collectAsStateWithLifecycle()
            val batteryGranted by viewModel.batteryGrantedState.collectAsStateWithLifecycle()
            val shizukuGranted by viewModel.shizukuGrantedState.collectAsStateWithLifecycle()

            ExamateTheme {
                MainScreenRoot(
                    overlayGranted = overlayGranted,
                    accessibilityGranted = accessibilityGranted,
                    batteryGranted = batteryGranted,
                    shizukuGranted = shizukuGranted,
                    onLaunchClick = { permissionHelper.startManualMode() },
                    onScreenshotClick = { permissionHelper.startScreenshotMode() },
                    onChatClick = { navigator.gotoChat() },
                    onLanChatClick = { navigator.gotoLanChat() },
                    onCampusServiceClick = { navigator.gotoCampusService() },
                    onWaterCardClick = { navigator.gotoWaterCard() },
                    onScheduleClick = { navigator.gotoSchedule() },
                    onNotesClick = { navigator.gotoNotes() },
                    onDownloaderClick = { navigator.gotoDownloader() },
                    onReadingClick = { navigator.gotoReading() },
                    onMusicClick = { navigator.gotoMusic() },
                    onUserSettingsClick = { navigator.gotoUserSettings() },
                    onApiConfigClick = { navigator.gotoApiConfig() },
                    onAgentSettingsClick = { navigator.gotoAgentSettings() },
                    onAboutClick = { navigator.gotoAbout() },
                    onSearchSettingsClick = { navigator.gotoSearchSettings() },
                    onSystemSettingsClick = { navigator.gotoSystemSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionHelper.onDestroy()
    }

    private fun updatePermissionStatus() {
        mainViewModel.updateOverlayGranted(permissionHelper.isOverlayGranted())
        mainViewModel.updateAccessibilityGranted(permissionHelper.isAccessibilityGranted())
        mainViewModel.updateBatteryGranted(permissionHelper.isBatteryGranted())
        mainViewModel.updateShizukuGranted(permissionHelper.isShizukuGranted())
    }
}
