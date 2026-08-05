package go.examate.hyp

import android.content.Context
import android.content.Intent
import go.examate.hyp.notes.NotesActivity

/**
 * MainActivity 的导航逻辑抽取。
 *
 * 集中管理从主界面跳转到各功能页的 Intent 启动,
 * 让 MainActivity 只负责 setContent,不再散落 9 处 startActivity 调用。
 */
class MainNavigator(private val context: Context) {

    fun gotoApiConfig() = context.startActivity(Intent(context, ApiConfigActivity::class.java))
    fun gotoChat() = context.startActivity(Intent(context, ChatActivity::class.java))
    fun gotoLanChat() = context.startActivity(Intent(context, go.examate.hyp.meschat.MesChatActivity::class.java))
    fun gotoCampusService() = context.startActivity(Intent(context, CampusServiceActivity::class.java))
    fun gotoWaterCard() = context.startActivity(Intent(context, WaterCardActivity::class.java))
    fun gotoSchedule() = context.startActivity(Intent(context, ScheduleActivity::class.java))
    fun gotoNotes() = context.startActivity(Intent(context, NotesActivity::class.java))
    fun gotoDownloader() = context.startActivity(Intent(context, DownloaderActivity::class.java))
    fun gotoReading() = context.startActivity(Intent(context, ReadingActivity::class.java))
    fun gotoMusic() = context.startActivity(Intent(context, MusicActivity::class.java))
    fun gotoSystemSettings() = context.startActivity(Intent(context, SystemSettingsActivity::class.java))
    fun gotoUserSettings() = context.startActivity(Intent(context, UserSettingsActivity::class.java))
    fun gotoAgentSettings() = context.startActivity(Intent(context, AgentSettingsActivity::class.java))
    fun gotoAbout() = context.startActivity(Intent(context, AboutActivity::class.java))
    fun gotoSearchSettings() = context.startActivity(Intent(context, SearchSettingsActivity::class.java))
}
