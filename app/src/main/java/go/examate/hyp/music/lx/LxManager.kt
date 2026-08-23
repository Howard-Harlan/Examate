package go.examate.hyp.music.lx

import android.content.Context
import android.util.Log
import go.examate.hyp.music.PlayRequest
import go.examate.hyp.music.ResolveResult

/**
 * LX 音源引擎池 — 对齐落雪"脚本常驻"模型:
 * 音源脚本初始化成本高(QuickJS 创建 + 脚本执行 + inited 等待),按源 id 缓存引擎实例,
 * musicUrl/lyric/pic 解析共用;脚本内容变更或引擎失效时重建。
 */
object LxEnginePool {
    private const val TAG = "LxEnginePool"
    private val engines = HashMap<String, Pair<String, LxEngine>>()   // sourceId → (脚本内容hash, 引擎)

    /**
     * 取(必要时创建)指定源的引擎;创建失败抛异常由调用方捕获。
     * @param recreate true 时强制重建(上次解析出现脚本级错误后重试用)
     */
    @Synchronized
    fun acquire(context: Context, source: LxSource, recreate: Boolean = false): LxEngine {
        val script = LxSourceStore.readScript(context, source)
        if (script.isBlank()) throw RuntimeException("音源脚本为空")
        val scriptKey = "${script.length}_${script.hashCode()}"
        val cached = engines[source.id]
        if (!recreate && cached != null && cached.first == scriptKey && cached.second.isHealthy()) {
            return cached.second
        }
        cached?.second?.let { runCatching { it.close() } }
        Log.d(TAG, "acquire: 创建引擎 source=${source.name} recreate=$recreate")
        val engine = LxEngine.createAndLoad(context, script)
        engines[source.id] = scriptKey to engine
        return engine
    }

    /** 丢弃某源的缓存引擎(删除源时调用) */
    @Synchronized
    fun drop(sourceId: String) {
        engines.remove(sourceId)?.second?.let { runCatching { it.close() } }
    }
}

/**
 * LX 音源管理 — 连接"平台搜索的歌曲"与"LX 脚本的 musicUrl 解析"。
 *
 * 对齐落雪 mobile 的源选择逻辑(utils/musicSdk/api-source.js + music/utils.ts):
 *  - resolve 时选择"声明支持该平台"的已启用源(assertApiSupport:global.lx.qualityList[source] != null),
 *    而不是固定取第一个源 —— 首个源可能不支持该平台,导致解析必然失败。
 *  - musicInfo 传入完整结构(name/singer/songmid/interval/albumId 等,见 LxEngine.buildMusicInfo),
 *    与落雪 toOldMusicInfo 等价 —— 脚本 handler 依赖这些字段构造请求。
 *  - 引擎经 [LxEnginePool] 常驻复用(脚本常驻,对齐落雪),解析失败可强制重建一次。
 *
 * 失败时返回 ResolveResult.Err,带具体原因(脚本未注册处理器/URL 为空/网络异常等),
 * 供播放页直接显示给用户。
 */
class LxManager(private val context: Context) {

    /** 是否有可用(已启用)的 LX 源 */
    fun hasEnabledSource(): Boolean = LxSourceStore.loadEnabled(context).isNotEmpty()

    fun enabledSourceName(): String = LxSourceStore.loadEnabled(context).firstOrNull()?.name ?: ""

    /** 单激活源模型(对标落雪):只用当前激活源,不自动跨源切换;无激活源返回 null */
    private fun pickSource(platform: String): LxSource? = LxSourceStore.activeSource(context)

    /**
     * 解析歌曲播放 URL(在 IO 线程调用)。
     * @param quality 128k/320k/flac 等,默认 128k 兼容性最好
     * @return Ok(req) 成功;Err(reason) 失败,reason 可直接显示给用户
     */
    fun resolve(song: LxSong, quality: String = "128k"): ResolveResult {
        val source = pickSource(song.platform)
            ?: return ResolveResult.Err("未启用 LX 音源")
        Log.d(TAG, "resolve: source=${source.name} platform=${song.platform} songmid=${song.songmid} name=${song.name}")

        // 首次尝试(池内引擎);失败且为脚本级异常时强制重建引擎重试一次。
        // 例外:服务端拒签(sign 校验不过)是脚本文件本身的问题,重建引擎无用 — 直接失败不重试
        val first = resolveOnce(source, song, quality, recreate = false)
        if (first !is ResolveResult.Err) return first
        val reason = first.reason
        if (isSignRejected(reason)) return first
        val scriptLevel = reason.contains("脚本") || reason.contains("注册") || reason.contains("初始化")
        if (scriptLevel) {
            Log.w(TAG, "resolve: 疑似脚本级错误,重建引擎重试: $reason")
            val retry = resolveOnce(source, song, quality, recreate = true)
            if (retry !is ResolveResult.Err) return retry
        }
        return first
    }

    /** 服务端拒签判定(sign 含脚本全文 md5,文件差一字节即被拒)— 重建引擎/换音质均无用 */
    fun isSignRejected(reason: String): Boolean = reason.contains("sign 校验不过") || reason.contains("拒绝该脚本")

    /**
     * 导入后服务端 sign 验证 — 用探针歌曲请求一次 musicUrl:
     * 拒签(404+HTML)→ false(脚本残缺/过期);其余响应(含“歌曲不存在”类错误)均证明 sign 已通过 → true。
     * 在导入入口拦截残缺脚本,避免入库后每次播放才发现。
     */
    fun verifyAgainstServer(source: LxSource): Boolean {
        val platform = source.sources.keys.firstOrNull() ?: "kw"
        val probe = LxSong(platform, "1", "探针", "", 0, "", "")
        // recreate=false:导入流程中 probeSources 刚建过引擎,直接复用,省一次重建(快 ~0.4s)
        val r = runCatching { resolveOnce(source, probe, "128k", recreate = false) }.getOrElse {
            return it.message?.let { m -> !isSignRejected(m) } ?: false
        }
        if (r is ResolveResult.Err && isSignRejected(r.reason)) return false
        return true
    }

    private fun resolveOnce(source: LxSource, song: LxSong, quality: String, recreate: Boolean): ResolveResult {
        val engine = try {
            LxEnginePool.acquire(context, source, recreate)
        } catch (e: Throwable) {
            Log.w(TAG, "acquire fail", e)
            return ResolveResult.Err("[${source.name}] 脚本加载失败: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            val url = engine.resolveMusicUrl(song.platform, song, quality)
            when {
                url.isBlank() -> {
                    val err = engine.lastError
                    ResolveResult.Err(
                        if (err.isNotBlank()) "[${source.name}] 音源解析失败: $err"
                        else "[${source.name}] 音源未返回播放 URL(该源可能不支持此歌曲)"
                    )
                }
                !url.startsWith("http") -> ResolveResult.Err("[${source.name}] 音源返回的 URL 无效: $url")
                else -> ResolveResult.Ok(
                    PlayRequest(
                        android.net.Uri.parse(url),
                        mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "resolve fail", e)
            ResolveResult.Err("[${source.name}] 解析异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 导入脚本后跑一次,回填声明的平台(inited);引擎留在池中供后续解析复用 */
    fun probeSources(source: LxSource) {
        val engine = try {
            LxEnginePool.acquire(context, source, recreate = true)
        } catch (e: Throwable) {
            Log.w(TAG, "probeSources acquire fail", e)
            return
        }
        if (engine.declaredSources.isNotEmpty()) {
            LxSourceStore.updateSources(context, source.id, engine.declaredSources)
        }
    }

    /**
     * 解析歌词(在 IO 线程调用)。
     * 通过 LX 脚本的 lyric action 获取 LRC 格式歌词文本(池内引擎复用)。
     * @return LRC 歌词文本;空字符串表示无歌词或脚本不支持
     */
    fun resolveLyric(song: LxSong): String {
        val source = pickSource(song.platform) ?: return ""
        val engine = runCatching { LxEnginePool.acquire(context, source) }.getOrNull() ?: return ""
        return runCatching { engine.resolveLyric(song.platform, song) }.getOrDefault("")
    }

    /**
     * 解析封面图 URL(在 IO 线程调用)。
     * 通过 LX 脚本的 pic action 获取封面直链(参照落雪:封面与播放一样走音源脚本)。
     * @return 封面 URL;空字符串表示脚本不支持或解析失败
     */
    fun resolvePic(song: LxSong): String {
        val source = pickSource(song.platform) ?: return ""
        val engine = runCatching { LxEnginePool.acquire(context, source) }.getOrNull() ?: return ""
        return runCatching { engine.resolvePic(song.platform, song) }.getOrDefault("")
    }

    companion object {
        private const val TAG = "LxManager"
    }
}
