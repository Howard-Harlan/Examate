package go.examate.hyp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    forbidCapture: Boolean,
    edgeAttach: Boolean,
    transparentFloat: Boolean,
    multiAiVerify: Boolean,
    providerCount: Int,
    onLaunchClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onChatClick: () -> Unit,
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onUserSettingsClick: () -> Unit,
    onShizukuClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onBatteryOptClick: () -> Unit,
    onForbidCaptureChanged: (Boolean) -> Unit,
    onEdgeAttachChanged: (Boolean) -> Unit,
    onTransparentFloatChanged: (Boolean) -> Unit,
    onMultiAiVerifyChanged: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

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
                onWaterCardClick = onWaterCardClick
            )
            2 -> SettingsScreen(
                shizukuGranted = shizukuGranted,
                forbidCapture = forbidCapture,
                edgeAttach = edgeAttach,
                transparentFloat = transparentFloat,
                multiAiVerify = multiAiVerify,
                providerCount = providerCount,
                onUserSettingsClick = onUserSettingsClick,
                onShizukuClick = onShizukuClick,
                onApiConfigClick = onApiConfigClick,
                onAgentSettingsClick = onAgentSettingsClick,
                onAboutClick = onAboutClick,
                onSearchSettingsClick = onSearchSettingsClick,
                onBatteryOptClick = onBatteryOptClick,
                onForbidCaptureChanged = onForbidCaptureChanged,
                onEdgeAttachChanged = onEdgeAttachChanged,
                onTransparentFloatChanged = onTransparentFloatChanged,
                onMultiAiVerifyChanged = onMultiAiVerifyChanged
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
    forbidCapture: Boolean,
    edgeAttach: Boolean,
    transparentFloat: Boolean,
    multiAiVerify: Boolean,
    providerCount: Int,
    onLaunchClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onChatClick: () -> Unit,
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onUserSettingsClick: () -> Unit,
    onShizukuClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onBatteryOptClick: () -> Unit,
    onForbidCaptureChanged: (Boolean) -> Unit,
    onEdgeAttachChanged: (Boolean) -> Unit,
    onTransparentFloatChanged: (Boolean) -> Unit,
    onMultiAiVerifyChanged: (Boolean) -> Unit
) {
    MainScreen(
        overlayGranted = overlayGranted,
        accessibilityGranted = accessibilityGranted,
        batteryGranted = batteryGranted,
        shizukuGranted = shizukuGranted,
        forbidCapture = forbidCapture,
        edgeAttach = edgeAttach,
        transparentFloat = transparentFloat,
        multiAiVerify = multiAiVerify,
        providerCount = providerCount,
        onLaunchClick = onLaunchClick,
        onScreenshotClick = onScreenshotClick,
        onChatClick = onChatClick,
        onLanChatClick = onLanChatClick,
        onCampusServiceClick = onCampusServiceClick,
        onWaterCardClick = onWaterCardClick,
        onUserSettingsClick = onUserSettingsClick,
        onShizukuClick = onShizukuClick,
        onApiConfigClick = onApiConfigClick,
        onAgentSettingsClick = onAgentSettingsClick,
        onAboutClick = onAboutClick,
        onSearchSettingsClick = onSearchSettingsClick,
        onBatteryOptClick = onBatteryOptClick,
        onForbidCaptureChanged = onForbidCaptureChanged,
        onEdgeAttachChanged = onEdgeAttachChanged,
        onTransparentFloatChanged = onTransparentFloatChanged,
        onMultiAiVerifyChanged = onMultiAiVerifyChanged
    )
}
