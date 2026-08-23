package go.examate.hyp.music.lx

import android.content.Context
import go.examate.hyp.music.bili.BiliClient

/**
 * B站搜索适配(小毕音乐) — 复用 BiliClient 视频搜索,输出统一 LxSong。
 * songmid = bvid(播放时由 PlayResolver/BiliClient 解析音频)。支持分页。
 */
object BiliSearch {
    fun search(context: Context, keyword: String, limit: Int = 30, page: Int = 1): List<LxSong> {
        val client = BiliClient(context)
        return client.search(keyword, page.coerceAtLeast(1)).take(limit).map {
            LxSong(
                platform = "bili",
                songmid = it.bvid,
                name = it.title,
                artist = it.author,
                durationMs = it.durationSec * 1000,
                picUrl = it.cover
            )
        }
    }
}
