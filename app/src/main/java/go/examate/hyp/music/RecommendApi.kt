package go.examate.hyp.music

import android.content.Context
import android.util.Log
import go.examate.hyp.music.lx.LxSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页推荐歌曲 API — 数据全部来自官方源榜单:
 *  - QQ 榜单池:新歌榜(27)/巅峰热歌(26)/流行指数(4)/美国公告榜(62)
 *  - 酷狗榜单池:TOP500(88)/红歌榜(6666)/新歌榜(8888)
 * 默认取两池首个合并去重;刷新时随机换榜单组合(支持刷新切换),结果缓存 1 小时。
 */
object RecommendApi {
    private const val TAG = "RecommendApi"
    private const val CACHE_KEY = "recommend_songs_v1"
    private const val TTL_MS = 3600_000L
    private const val TAKE_PER_BOARD = 12
    private const val TOTAL = 20

    /** 榜单池(刷新时随机组合,来源更多样) */
    private val TX_BOARDS = listOf("27", "26", "4", "62")
    private val KG_BOARDS = listOf("88", "6666", "8888")

    /** 带缓存的推荐歌曲(首页用) */
    fun recommendSongsCached(context: Context): List<LxSong> {
        MusicDataCache.get(context, CACHE_KEY, TTL_MS)?.let { cached ->
            val list = fromJson(cached)
            if (list.isNotEmpty()) return list
        }
        return fetchMerged(TX_BOARDS.first(), KG_BOARDS.first(), context)
    }

    /** 强制刷新:随机抽取两个榜单重新拉取并覆盖缓存(首页"换一换") */
    fun recommendSongsFresh(context: Context): List<LxSong> {
        val fresh = fetchMerged(TX_BOARDS.random(), KG_BOARDS.random(), context)
        if (fresh.isNotEmpty()) return fresh
        // 随机榜单失败时回退默认榜单,再不行用旧缓存
        return fetchMerged(TX_BOARDS.first(), KG_BOARDS.first(), context)
            .ifEmpty { fromJson(MusicDataCache.get(context, CACHE_KEY, 24L * 3600_000).orEmpty()) }
    }

    /** 指定两榜单合并去重取前 TOTAL 首(单榜单失败不阻断) */
    private fun fetchMerged(txBoard: String, kgBoard: String, context: Context): List<LxSong> {
        val (txSongs, kgSongs) = runBlocking(Dispatchers.IO) {
            val tx = async {
                runCatching { LeaderboardApi.detail("tx", txBoard)?.songs?.take(TAKE_PER_BOARD).orEmpty() }
                    .onFailure { Log.w(TAG, "tx board $txBoard fail", it) }
                    .getOrDefault(emptyList())
            }
            val kg = async {
                runCatching { LeaderboardApi.detail("kg", kgBoard)?.songs?.take(TAKE_PER_BOARD).orEmpty() }
                    .onFailure { Log.w(TAG, "kg board $kgBoard fail", it) }
                    .getOrDefault(emptyList())
            }
            tx.await() to kg.await()
        }
        val merged = mutableListOf<LxSong>()
        val seen = mutableSetOf<String>()
        for (s in txSongs + kgSongs) if (seen.add(normKey(s))) merged.add(s)
        val list = merged.take(TOTAL)
        if (list.isNotEmpty()) MusicDataCache.put(context, CACHE_KEY, toJson(list))
        return list
    }

    /** 追加加载:从不同榜单池拉取更多歌曲,排除已有的(按 normKey 去重) */
    fun recommendSongsMore(context: Context, existingKeys: Set<String>, page: Int): List<LxSong> {
        val txPool = TX_BOARDS
        val kgPool = KG_BOARDS
        val txBoard = txPool[page % txPool.size]
        val kgBoard = kgPool[page % kgPool.size]
        val seen = existingKeys.toMutableSet()
        val merged = mutableListOf<LxSong>()
        runCatching {
            LeaderboardApi.detail("tx", txBoard)?.songs?.forEach { s ->
                if (seen.add(normKey(s))) merged.add(s)
            }
        }.onFailure { Log.w(TAG, "tx more $txBoard fail", it) }
        runCatching {
            LeaderboardApi.detail("kg", kgBoard)?.songs?.forEach { s ->
                if (seen.add(normKey(s))) merged.add(s)
            }
        }.onFailure { Log.w(TAG, "kg more $kgBoard fail", it) }
        return merged.take(TOTAL)
    }

    private fun normKey(s: LxSong): String =
        (s.name + "|" + s.artist).lowercase().replace(" ", "")

    private fun toJson(list: List<LxSong>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("platform", s.platform); put("songmid", s.songmid)
                put("name", s.name); put("artist", s.artist)
                put("durationMs", s.durationMs); put("picUrl", s.picUrl); put("albumId", s.albumId)
            })
        }
        return arr.toString()
    }

    private fun fromJson(json: String): List<LxSong> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LxSong(
                platform = o.optString("platform"), songmid = o.optString("songmid"),
                name = o.optString("name"), artist = o.optString("artist"),
                durationMs = o.optLong("durationMs"), picUrl = o.optString("picUrl"),
                albumId = o.optString("albumId")
            )
        }
    }.getOrDefault(emptyList())
}
