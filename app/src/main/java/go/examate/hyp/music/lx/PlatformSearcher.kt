package go.examate.hyp.music.lx

/**
 * 多平台搜索统一入口。
 * platform: wy=网易 kg=酷狗 kw=酷我
 */
object PlatformSearcher {
    /** 平台代码: wy=网易 kg=酷狗 kw=酷我 */
    fun search(platform: String, keyword: String): List<LxSong> = when (platform) {
        "wy" -> NeteaseSearch.search(keyword)
        "kg" -> KugouSearch.search(keyword)
        "kw" -> KuwoSearch.search(keyword)
        else -> emptyList()
    }
}
