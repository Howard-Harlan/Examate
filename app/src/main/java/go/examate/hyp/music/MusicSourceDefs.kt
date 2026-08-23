package go.examate.hyp.music

import androidx.compose.ui.graphics.Color

/**
 * 音乐源定义 — 参照落雪音乐(lx-music)的源体系。
 * UI 显示"小X音乐"别名(规避大厂名,与落雪 source_alias_* 一致):
 *   kw=小蜗音乐(酷我) kg=小枸音乐(酷狗) tx=小秋音乐(QQ) wy=小芸音乐(网易)
 *   mg=小蜜音乐(咪咕) bili=小毕音乐(B站,本项目新增) all=聚合大全(聚合搜索)
 */
data class MusicSourceInfo(
    val code: String,       // kw/kg/tx/wy/mg/bili/all
    val alias: String,      // UI 显示名(小蜗音乐等)
    val realName: String,   // 真实平台名(酷我音乐等)
    val color: Color        // 平台主题色(列表标签用)
)

object MusicSourceDefs {
    val KW = MusicSourceInfo("kw", "小蜗音乐", "酷我音乐", Color(0xFF4CAF50))
    val KG = MusicSourceInfo("kg", "小枸音乐", "酷狗音乐", Color(0xFFFF7043))
    val TX = MusicSourceInfo("tx", "小秋音乐", "QQ音乐", Color(0xFF1E88E5))
    val WY = MusicSourceInfo("wy", "小芸音乐", "网易音乐", Color(0xFFE53935))
    val MG = MusicSourceInfo("mg", "小蜜音乐", "咪咕音乐", Color(0xFFFFC107))
    val BILI = MusicSourceInfo("bili", "小毕音乐", "B站音乐", Color(0xFF00A1D6))
    val ALL = MusicSourceInfo("all", "聚合大全", "聚合搜索", Color(0xFF8E24AA))

    /** 可搜索的单个源(静态,不含 B站;B站默认隐藏需手动开启) */
    val searchable = listOf(KW, KG, TX, WY, MG)

    /** 源选择器完整列表(静态) */
    val selectorList = listOf(KW, KG, TX, WY, MG, ALL)

    /**
     * 动态可搜索源(参照落雪:按账号设置过滤)。
     * B站默认不在搜索源,手动开启(AccountPrefs.isBiliSearchEnabled)后才显示。
     */
    fun searchableDynamic(context: android.content.Context): List<MusicSourceInfo> =
        if (go.examate.hyp.music.AccountPrefs.isBiliSearchEnabled(context))
            listOf(KW, KG, TX, WY, MG, BILI)
        else listOf(KW, KG, TX, WY, MG)

    /** 动态选择器列表(含聚合 + 可选的 B站) */
    fun selectorListDynamic(context: android.content.Context): List<MusicSourceInfo> =
        searchableDynamic(context) + ALL

    /** 代码 → 定义;未知返回 ALL */
    fun byCode(code: String): MusicSourceInfo =
        (searchable + BILI + ALL).firstOrNull { it.code == code } ?: ALL

    /** 别名(UI 显示) */
    fun aliasOf(code: String): String = byCode(code).alias
}
