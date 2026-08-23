package go.examate.hyp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import go.examate.hyp.notes.NotesActivity
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.screens.DeepLearningScreen
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * Deep Learning 学习页 — 搜题 + 笔记。
 *
 * 复用 [PermissionHelper] 管理权限与启动模式,
 * 权限状态实时内嵌到搜题卡片上(ON_RESUME 与权限回调都会刷新)。
 */
class DeepLearningActivity : ComponentActivity() {

    private lateinit var permissionHelper: PermissionHelper

    /** 权限刷新信号:自增触发 Compose 重组并重新查询权限 */
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionHelper = PermissionHelper(this).apply {
            onCreate()
            onPermissionStatusChanged = { refreshTick++ }
        }

        setContent {
            // 读取 refreshTick 建立重组依赖,每次刷新都重新查询权限
            refreshTick
            ExamateTheme {
                LiquidGlassBackground {
                    DeepLearningScreen(
                        overlayGranted = permissionHelper.isOverlayGranted(),
                        accessibilityGranted = permissionHelper.isAccessibilityGranted(),
                        batteryGranted = permissionHelper.isBatteryGranted(),
                        shizukuGranted = permissionHelper.isShizukuGranted(),
                        onLaunchAccessibility = { permissionHelper.startManualMode() },
                        onLaunchScreenshot = { permissionHelper.startScreenshotMode() },
                        onNotesClick = { startActivity(Intent(this@DeepLearningActivity, NotesActivity::class.java)) },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionHelper.onDestroy()
    }
}
