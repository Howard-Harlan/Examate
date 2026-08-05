package go.examate.hyp.music.lx

import android.content.Context
import android.util.Log
import go.examate.hyp.music.PlayRequest
import go.examate.hyp.music.ResolveResult

/**
 * LX 音源管理 — 连接"平台搜索的歌曲"与"LX 脚本的 musicUrl 解析"。
 *
 * resolve 每次创建/释放引擎(QuickJS 单线程,create-per-resolve 避免跨线程共享;
 * 代价是重跑脚本,阶段2 可接受,阶段3 再优化为单线程复用)。
 *
 * 失败时返回 ResolveResult.Err,带具体原因(脚本未注册处理器/URL 为空/网络异常等),
 * 供播放页直接显示给用户。
 */
class LxManager(private val context: Context) {

    /** 是否有可用(已启用)的 LX 源 */
    fun hasEnabledSource(): Boolean = LxSourceStore.loadEnabled(context).isNotEmpty()

    fun enabledSourceName(): String = LxSourceStore.loadEnabled(context).firstOrNull()?.name ?: ""

    /**
     * 解析歌曲播放 URL(在 IO 线程调用)。
     * @param quality 128k/320k/flac 等,默认 128k 兼容性最好
     * @return Ok(req) 成功;Err(reason) 失败,reason 可直接显示给用户
     */
    fun resolve(song: LxSong, quality: String = "128k"): ResolveResult {
        val source = LxSourceStore.loadEnabled(context).firstOrNull()
            ?: return ResolveResult.Err("未启用 LX 音源")
        val script = LxSourceStore.readScript(context, source)
        if (script.isBlank()) return ResolveResult.Err("音源脚本为空")
        Log.d(TAG, "resolve: source=${source.name} platform=${song.platform} songmid=${song.songmid} scriptLen=${script.length}")
        val engine = try {
            LxEngine.createAndLoad(script)
        } catch (e: Throwable) {
            Log.w(TAG, "createAndLoad fail: scriptHead=${script.take(200)}", e)
            return ResolveResult.Err("脚本加载失败: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            val url = engine.resolveMusicUrl(song.platform, song.songmid, quality)
            when {
                url.isBlank() -> {
                    val err = engine.lastError
                    ResolveResult.Err(if (err.isNotBlank()) "音源解析失败: $err" else "音源未返回播放 URL")
                }
                !url.startsWith("http") -> ResolveResult.Err("音源返回的 URL 无效: $url")
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
            ResolveResult.Err("解析异常: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            engine.close()
        }
    }

    /** 导入脚本后跑一次,回填声明的平台(inited) */
    fun probeSources(source: LxSource) {
        val script = LxSourceStore.readScript(context, source)
        if (script.isBlank()) { Log.w(TAG, "probeSources: script blank"); return }
        Log.d(TAG, "probeSources: ${source.name} scriptLen=${script.length}")
        val engine = try {
            LxEngine.createAndLoad(script)
        } catch (e: Throwable) {
            Log.w(TAG, "probeSources createAndLoad fail: scriptHead=${script.take(200)}", e)
            return
        }
        try {
            if (engine.declaredSources.isNotEmpty()) {
                LxSourceStore.updateSources(context, source.id, engine.declaredSources)
            }
        } finally { engine.close() }
    }

    /**
     * 解析歌词(在 IO 线程调用)。
     * 通过 LX 脚本的 lyric action 获取 LRC 格式歌词文本。
     * @return LRC 歌词文本;空字符串表示无歌词或脚本不支持
     */
    fun resolveLyric(platform: String, songmid: String): String {
        val source = LxSourceStore.loadEnabled(context).firstOrNull()
            ?: return ""
        val script = LxSourceStore.readScript(context, source)
        if (script.isBlank()) return ""
        Log.d(TAG, "resolveLyric: source=${source.name} platform=$platform songmid=$songmid")
        val engine = try {
            LxEngine.createAndLoad(script)
        } catch (e: Throwable) {
            Log.w(TAG, "resolveLyric createAndLoad fail", e)
            return ""
        }
        return try {
            engine.resolveLyric(platform, songmid)
        } catch (e: Throwable) {
            Log.w(TAG, "resolveLyric fail", e)
            ""
        } finally {
            engine.close()
        }
    }

    companion object {
        private const val TAG = "LxManager"
    }
}
