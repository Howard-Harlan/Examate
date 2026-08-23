package go.examate.hyp.music

import android.content.Context

/**
 * 音乐功能设置 — 与音源体系无关的功能开关(「我的 → 功能设置」)。
 *  - 音频独占:播放时申请系统音频焦点(打断其他应用音频);焦点被抢占时自动暂停,归还后恢复。
 *    关闭时不申请焦点,与其他音频应用混音(默认)。
 */
object MusicFeaturePrefs {
    private const val PREFS = "music_feature"

    fun isAudioExclusive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("audio_exclusive", false)

    fun setAudioExclusive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("audio_exclusive", enabled).apply()
    }
}
