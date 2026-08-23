package go.examate.hyp.music.lx

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 多平台搜索统一入口(参照落雪 musicSdk 的 musicSearch):
 *  - 单源:只搜所选平台
 *  - 聚合(all):并行搜所有源,单个源失败不阻断,结果合并
 * platform: wy=小芸(网易) kg=小枸(酷狗) kw=小蜗(酷我) tx=小秋(QQ) bili=小毕(B站)
 */
object PlatformSearcher {
    private const val TAG = "PlatformSearcher"

    /** 参与聚合搜索的源顺序 */
    val ALL_SOURCES = listOf("kw", "kg", "tx", "wy", "mg", "bili")

    /** 单源搜索(支持分页;page 从 1 开始) */
    fun search(context: Context, platform: String, keyword: String, page: Int = 1): List<LxSong> = when (platform) {
        "wy" -> NeteaseSearch.search(keyword, page = page)
        "kg" -> KugouSearch.search(keyword, page = page)
        "kw" -> KuwoSearch.search(keyword, page = page)
        "tx" -> TxSearch.search(keyword, page = page)
        "mg" -> MiguSearch.search(keyword, page = page)
        "bili" -> BiliSearch.search(context, keyword, page = page)
        else -> emptyList()
    }

    /**
     * 聚合搜索:并行搜所有源(参照落雪 search() 的 all 分支)。
     * 每个源单独容错,失败返回空列表不阻断其他源。
     * B站默认不参与聚合,需在账号设置开启。
     */
    suspend fun searchAll(context: Context, keyword: String): List<LxSong> =
        searchAllPage(context, keyword, 1)

    /** 聚合搜索指定页:每个源各取第 [page] 页后合并(供搜索页"加载更多") */
    suspend fun searchAllPage(context: Context, keyword: String, page: Int): List<LxSong> {
        val sources = if (go.examate.hyp.music.AccountPrefs.isBiliSearchEnabled(context))
            ALL_SOURCES else ALL_SOURCES.filter { it != "bili" }
        return coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching { search(context, source, keyword, page) }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
    }
}
