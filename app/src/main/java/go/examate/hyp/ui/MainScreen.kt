package go.examate.hyp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.LiquidGlassContainer
import go.examate.hyp.ui.screens.QiushiScreen
import go.examate.hyp.ui.screens.SettingsScreen
import go.examate.hyp.ui.screens.ShensiScreen

@Composable
fun MainScreen(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    batteryGranted: Boolean,
    shizukuGranted: Boolean,
    onLaunchClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onChatClick: () -> Unit,
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onDownloaderClick: () -> Unit,
    onReadingClick: () -> Unit,
    onMusicClick: () -> Unit,
    onNotesClick: () -> Unit,
    onUserSettingsClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val defaultTab = remember { PrefsManager.getInstance(context).defaultHomeTab }
    var selectedTab by remember { mutableIntStateOf(defaultTab) }

    LiquidGlassContainer(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it }
    ) {
        when (selectedTab) {
            0 -> ShensiScreen(
                overlayGranted = overlayGranted,
                accessibilityGranted = accessibilityGranted,
                batteryGranted = batteryGranted,
                shizukuGranted = shizukuGranted,
                onLaunchAccessibility = onLaunchClick,
                onLaunchScreenshot = onScreenshotClick,
                onChatClick = onChatClick
            )
            1 -> QiushiScreen(
                onLanChatClick = onLanChatClick,
                onCampusServiceClick = onCampusServiceClick,
                onWaterCardClick = onWaterCardClick,
                onScheduleClick = onScheduleClick,
                onDownloaderClick = onDownloaderClick,
                onReadingClick = onReadingClick,
                onMusicClick = onMusicClick,
                onNotesClick = onNotesClick
            )
            2 -> SettingsScreen(
                onUserSettingsClick = onUserSettingsClick,
                onApiConfigClick = onApiConfigClick,
                onAgentSettingsClick = onAgentSettingsClick,
                onAboutClick = onAboutClick,
                onSearchSettingsClick = onSearchSettingsClick,
                onSystemSettingsClick = onSystemSettingsClick
            )
        }
    }
}

/**
 * 主界面入口 Composable
 */
@Composable
fun MainScreenRoot(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    batteryGranted: Boolean,
    shizukuGranted: Boolean,
    onLaunchClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onChatClick: () -> Unit,
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onDownloaderClick: () -> Unit,
    onReadingClick: () -> Unit,
    onMusicClick: () -> Unit,
    onNotesClick: () -> Unit,
    onUserSettingsClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit
) {
    MainScreen(
        overlayGranted = overlayGranted,
        accessibilityGranted = accessibilityGranted,
        batteryGranted = batteryGranted,
        shizukuGranted = shizukuGranted,
        onLaunchClick = onLaunchClick,
        onScreenshotClick = onScreenshotClick,
        onChatClick = onChatClick,
        onLanChatClick = onLanChatClick,
        onCampusServiceClick = onCampusServiceClick,
        onWaterCardClick = onWaterCardClick,
        onScheduleClick = onScheduleClick,
        onDownloaderClick = onDownloaderClick,
        onReadingClick = onReadingClick,
        onMusicClick = onMusicClick,
        onNotesClick = onNotesClick,
        onUserSettingsClick = onUserSettingsClick,
        onApiConfigClick = onApiConfigClick,
        onAgentSettingsClick = onAgentSettingsClick,
        onAboutClick = onAboutClick,
        onSearchSettingsClick = onSearchSettingsClick,
        onSystemSettingsClick = onSystemSettingsClick
    )
}
