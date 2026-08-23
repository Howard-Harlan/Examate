package go.examate.hyp.music

import android.content.Context
import android.util.Log
import go.examate.hyp.music.auth.MusicAuth
import go.examate.hyp.music.lx.LxManager
import go.examate.hyp.music.lx.LxSong
import go.examate.hyp.music.lx.PlatformSearcher
import go.examate.hyp.music.lx.LxSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一播放解析器 — 在线歌曲播放的双路线分派(设置中选择,见 PlayRoutePrefs),双路线互不打扰:
 *
 *  1. OFFICIAL 官方账号播放:官方 API 直连(OnlineMusicApi),"模拟登录拿数据" —
 *     wy/tx/kg/kw/mg 一律要求对应账号已登录(「我的-账号」),未登录直接提示先登录;
 *     登录态 Cookie 附到官方接口,按登录账号取播放链接与音质。
 *  2. SCRIPT   音源解析脚本播放:参照落雪 lx-music-mobile 的 music/online.ts 逻辑 —
 *     音质选择(getPlayQuality 降级)→ URL 缓存(musicUrlCache)→ 脚本解析 → 失败换源
 *     (getOtherSource:在其他平台搜索同曲并逐个尝试解析,retryedSource 去重)。
 *     脚本路线不要求登录,也不回落官方(与官方路线互不打扰)。
 *
 * B站(bili)不随路线切换:始终走官方 DASH 解析(BiliClient,只取音频流;无需登录,登录态可选)。
 * 本地音乐(localUri 非空)不走此解析器。
 */
object PlayResolver {
    private const val TAG = "PlayResolver"

    /** 音质降级顺序(参照落雪 TRY_QUALITYS_LIST,含 24bit 臻品档) */
    private val TRY_QUALITYS = listOf("flac24bit", "flac", "320k", "128k")

    /** 官方路线需登录的平台(与 MusicAuth 平台代号一致) */
    private val OFFICIAL_LOGIN_PLATFORMS = setOf("wy", "tx", "kg", "kw", "mg")

    /**
     * 统一入口:解析歌曲播放地址。
     * @param quality 目标音质;null 时用设置音质(PlayRoutePrefs.getQuality)
     * @return Ok(req) 成功;Err(reason) 失败(原因可直接显示)
     */
    suspend fun resolve(
        context: Context,
        song: LxSong,
        quality: String? = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        val target = quality ?: PlayRoutePrefs.getQuality(context)
        if (song.platform == "bili") {
            return@withContext resolveBili(context, song.songmid)
        }
        when (PlayRoutePrefs.getRoute(context)) {
            PlayRoute.OFFICIAL -> resolveOfficial(context, song, target)
            PlayRoute.SCRIPT -> resolveScript(context, song, target)
        }
    }

    /** B站:官方 DASH 解析(无需登录即可播放;登录态 Cookie 存在时自动附带,可享更高质量/同步进度) */
    fun resolveBili(context: Context, bvid: String, cid: Long = 0): ResolveResult {
        val req = runCatching {
            go.examate.hyp.music.bili.BiliClient(context).resolveAudio(bvid, cid)
        }.getOrNull()
        return req?.let { ResolveResult.Ok(it) }
            ?: ResolveResult.Err("B站音频解析失败(该视频可能受限或需要登录)")
    }

    // ════════════════════════════════════════════
    //  路线一:官方账号播放(模拟登录拿数据)
    // ════════════════════════════════════════════

    private fun resolveOfficial(context: Context, song: LxSong, quality: String): ResolveResult {
        // 官方路线登录前置:未登录对应账号直接提示,不发请求
        if (song.platform in OFFICIAL_LOGIN_PLATFORMS && !MusicAuth.isLoggedIn(context, song.platform)) {
            return ResolveResult.Err("请先登录${MusicAuth.aliasOf(song.platform)}账号(我的-账号)")
        }
        val url = OnlineMusicApi.resolvePlayUrl(context, song.platform, song.songmid, quality)
        if (url != null) {
            Log.d(TAG, "official ok: ${song.platform}/${song.songmid}($quality)")
            return ResolveResult.Ok(playRequest(url))
        }
        val loginHint = if (song.platform in OFFICIAL_LOGIN_PLATFORMS)
            "官方播放失败:该平台歌曲可能需要会员或登录态已过期,请重新登录或换用解析脚本路线"
        else "官方播放失败(该平台可能限制),可在「我的-播放设置」切换音源解析脚本路线"
        return ResolveResult.Err(loginHint)
    }

    // ════════════════════════════════════════════
    //  路线二:音源解析脚本播放(参照落雪 music/online.ts)
    // ════════════════════════════════════════════

    private suspend fun resolveScript(context: Context, song: LxSong, quality: String): ResolveResult {
        val manager = LxManager(context)
        if (!manager.hasEnabledSource()) {
            return ResolveResult.Err("未启用音源脚本,请先在「我的-音源设置」导入并启用")
        }
        // 1. 音质降级链(参照落雪 music/online.ts 的 quality 逐级重试):
        //    从设置音质开始,向下逐档尝试,只保留源声明支持的档位
        val qualitys = qualityCascade(context, song.platform, quality)
        var reason = "解析失败"
        // 2. 首选源逐档尝试(落雪同序:先降音质,全档失败再换源)
        for (q in qualitys) {
            MusicUrlCache.get(context, song.platform, song.songmid, q)?.let {
                Log.d(TAG, "script cache hit: ${song.platform}/${song.songmid}($q)")
                return ResolveResult.Ok(playRequest(it))
            }
            val r = manager.resolve(song, q)
            if (r is ResolveResult.Ok) {
                MusicUrlCache.put(context, song.platform, song.songmid, q, r.req.uri.toString())
                return r
            }
            reason = (r as? ResolveResult.Err)?.reason ?: reason
            Log.w(TAG, "script ${song.platform}($q) 失败: $reason")
            // 服务端拒签 = 脚本文件本身残缺/过期:降音质/换平台均无用,立即短路,提示重导入
            if (manager.isSignRejected(reason)) {
                return ResolveResult.Err("音源脚本被服务端拒签(文件不完整或已过期):请在「我的-音源设置」删除后重新导入")
            }
        }
        // 3. 全档失败 → 换源(参照落雪 getOtherSource + getOnlineOtherSourceMusicUrl):
        //    用歌曲名/歌手在其它平台搜索同曲,逐个平台按音质降级链尝试,已试过的平台去重。
        //    (脚本路线与官方路线互不打扰:不回落官方直连)
        val retryed = mutableSetOf(song.platform)
        val candidates = findOtherSource(context, song, qualitys.first(), retryed)
        for (cand in candidates) {
            val candQualitys = qualityCascade(context, cand.platform, quality)
            for (q in candQualitys) {
                Log.d(TAG, "try toggle to ${cand.platform}/${cand.songmid}($q): ${cand.name} - ${cand.artist}")
                val r = manager.resolve(cand, q)
                if (r is ResolveResult.Ok) {
                    MusicUrlCache.put(context, cand.platform, cand.songmid, q, r.req.uri.toString())
                    return r
                }
                reason = (r as? ResolveResult.Err)?.reason ?: reason
            }
            retryed.add(cand.platform)
        }
        return ResolveResult.Err("音源解析失败: $reason")
    }

    /**
     * 音质降级链:从目标音质开始向下(flac→320k→128k),只保留激活源声明支持的档位;
     * 源未声明时给全链(部分脚本声明不全但实际可解析低档)。
     */
    private fun qualityCascade(context: Context, platform: String, setting: String): List<String> {
        // 单激活源模型(对标落雪):声明音质只看当前激活源
        val declared = LxSourceStore.activeSource(context)?.sources?.get(platform) ?: emptyList()
        val idx = TRY_QUALITYS.indexOf(setting)
        val from = if (idx >= 0) TRY_QUALITYS.subList(idx, TRY_QUALITYS.size) else TRY_QUALITYS
        return if (declared.isEmpty()) from.toList() else from.filter { it in declared }.ifEmpty { listOf("128k") }
    }

    /**
     * 换源:用歌曲名在所有平台搜索候选(参照落雪 findMusic 的匹配思路),
     * 按 名称/歌手/时长 相似度排序,排除已试过的平台。
     */
    private suspend fun findOtherSource(
        context: Context,
        song: LxSong,
        quality: String,
        retryed: MutableSet<String>
    ): List<LxSong> {
        if (song.name.isBlank()) return emptyList()
        val candidates = runCatching {
            PlatformSearcher.searchAll(context, song.name)
        }.getOrDefault(emptyList())
        val normalizedName = normalize(song.name)
        val normalizedSinger = normalize(song.artist)
        return candidates
            .filter { it.platform !in retryed && it.songmid != song.songmid && it.songmid.isNotBlank() }
            .map { cand ->
                // 匹配评分:名称相等 3 分 / 名称包含 2 分 / 歌手相等 1 分;时长接近 +1
                var score = 0
                val cn = normalize(cand.name)
                if (cn == normalizedName) score += 3
                else if (cn.isNotEmpty() && (cn.contains(normalizedName) || normalizedName.contains(cn))) score += 2
                val cs = normalize(cand.artist)
                if (cs.isNotEmpty() && normalizedSinger.isNotEmpty() && cs == normalizedSinger) score += 1
                if (cand.durationMs > 0 && song.durationMs > 0 &&
                    kotlin.math.abs(cand.durationMs - song.durationMs) < 5000
                ) score += 1
                cand to score
            }
            .filter { it.second >= 2 }   // 至少要名称近似,避免拿无关歌曲解析
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }
    }

    /** 单档音质选择(旧入口兼容):目标音质若源不支持,降到源支持的最高档 */
    private fun getPlayQuality(context: Context, platform: String, setting: String): String =
        qualityCascade(context, platform, setting).first()

    private fun playRequest(url: String): PlayRequest = PlayRequest(
        android.net.Uri.parse(url),
        mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
    )

    /** 归一化:去空白/标点/大小写(参照落雪 filterStr) */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""\s|'|\.|,|，|&|"|、|\(|\)|（|）|`|~|-|<|>|\||\/|\]|\[|!|！"""), "")
}
