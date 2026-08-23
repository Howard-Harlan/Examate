package go.examate.hyp.music

import android.content.Context
import go.examate.hyp.music.lx.LxSourceStore

/**
 * 音源种类 — 用户视角的音乐来源(而非底层解析器)。
 *  - LOCAL   本地音乐(设备文件)
 *  - NETEASE 在线音源(经导入的 LX 脚本解析取流,UI 统称"音源"去除大厂名)
 *  - BILI    B站(登录后搜索视频取音频)
 *
 * @param code 简写代码(用于搜索栏 chip 显示)
 */
enum class MusicSourceKind(val id: String, val label: String, val code: String) {
    LOCAL("local", "本地", "local"),
    NETEASE("netease", "音源", "音源"),
    BILI("bili", "B站", "bili")
}

/**
 * 音源启用态。
 *  - LOCAL/BILI:SharedPreferences 单开关
 *  - NETEASE:由 LxSourceStore 中各 LX 脚本的 enabled 状态聚合(任一启用即视为该类启用)
 */
object MusicSourcePrefs {
    private const val PREFS = "music_sources_enabled"

    fun isEnabled(context: Context, kind: MusicSourceKind): Boolean = when (kind) {
        MusicSourceKind.LOCAL ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind.id, true)
        MusicSourceKind.NETEASE ->
            LxSourceStore.loadEnabled(context).isNotEmpty()
        MusicSourceKind.BILI ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(kind.id, false)
    }

    fun setEnabled(context: Context, kind: MusicSourceKind, enabled: Boolean) {
        // NETEASE 类别无主开关,由各 LX 脚本独立控制(见 LxSourceStore.setEnabled)
        if (kind == MusicSourceKind.NETEASE) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(kind.id, enabled).apply()
    }

    /** 该源是否已完成配置(B站无需登录即可搜索/播放,登录态可选) */
    fun isConfigured(context: Context, kind: MusicSourceKind): Boolean = when (kind) {
        MusicSourceKind.LOCAL -> true
        MusicSourceKind.NETEASE -> LxSourceStore.loadAll(context).isNotEmpty()
        MusicSourceKind.BILI -> true
    }

    /** 可用于搜索的源(已启用 且 已配置) */
    fun usableSources(context: Context): List<MusicSourceKind> =
        MusicSourceKind.entries.filter { isEnabled(context, it) && isConfigured(context, it) }
}
