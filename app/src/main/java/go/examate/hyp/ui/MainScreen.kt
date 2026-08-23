package go.examate.hyp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.LiquidGlassContainer
import go.examate.hyp.ui.screens.FestivalWelcomeHost
import go.examate.hyp.ui.screens.QiushiScreen
import go.examate.hyp.ui.screens.SettingsScreen
import go.examate.hyp.ui.screens.ShensiScreen

@Composable
fun MainScreen(
    onDeepLearningClick: () -> Unit,
    onChatClick: () -> Unit,
    onMesChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onDownloaderClick: () -> Unit,
    onReadingClick: () -> Unit,
    onMusicClick: () -> Unit,
    onMemoClick: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenNotes: (String?) -> Unit,
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
                onDeepLearningClick = onDeepLearningClick,
                onChatClick = onChatClick,
                onOpenTodo = onOpenTodo,
                onOpenNotes = onOpenNotes,
                onOpenSchedule = onScheduleClick
            )
            1 -> QiushiScreen(
                onMesChatClick = onMesChatClick,
                onCampusServiceClick = onCampusServiceClick,
                onWaterCardClick = onWaterCardClick,
                onScheduleClick = onScheduleClick,
                onDownloaderClick = onDownloaderClick,
                onReadingClick = onReadingClick,
                onMusicClick = onMusicClick,
                onMemoClick = onMemoClick
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

    // 节日欢迎页:任意 Tab 下首次进入均会弹一次(活动期间)
    FestivalWelcomeHost()
}

/**
 * 主界面入口 Composable
 */
@Composable
fun MainScreenRoot(
    onDeepLearningClick: () -> Unit,
    onChatClick: () -> Unit,
    onMesChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onDownloaderClick: () -> Unit,
    onReadingClick: () -> Unit,
    onMusicClick: () -> Unit,
    onMemoClick: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenNotes: (String?) -> Unit,
    onUserSettingsClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit
) {
    MainScreen(
        onDeepLearningClick = onDeepLearningClick,
        onChatClick = onChatClick,
        onMesChatClick = onMesChatClick,
        onCampusServiceClick = onCampusServiceClick,
        onWaterCardClick = onWaterCardClick,
        onScheduleClick = onScheduleClick,
        onDownloaderClick = onDownloaderClick,
        onReadingClick = onReadingClick,
        onMusicClick = onMusicClick,
        onMemoClick = onMemoClick,
        onOpenTodo = onOpenTodo,
        onOpenNotes = onOpenNotes,
        onUserSettingsClick = onUserSettingsClick,
        onApiConfigClick = onApiConfigClick,
        onAgentSettingsClick = onAgentSettingsClick,
        onAboutClick = onAboutClick,
        onSearchSettingsClick = onSearchSettingsClick,
        onSystemSettingsClick = onSystemSettingsClick
    )
}
