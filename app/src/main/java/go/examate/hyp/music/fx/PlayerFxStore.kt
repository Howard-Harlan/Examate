package go.examate.hyp.music.fx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 特效/主题页条目:内置 3D 粒子 / 内置网页播放器示例 / 用户导入页 */
data class PlayerFxEntry(
    val id: String,           // "builtin_particles" / "builtin_web_player" / "fx_<hash>"
    val name: String,
    val file: String,         // 内置为 asset 相对路径,自定义为绝对路径
    val builtin: Boolean,
    val mode: String = MODE_FX   // fx=背景特效层 / player=网页播放器(替换原生播放页内容)
) {
    companion object {
        const val MODE_FX = "fx"
        const val MODE_PLAYER = "player"
    }
}

/**
 * 播放页特效/主题(自定义播放器)存储:
 *  - 内置 assets/player_fx/particles.html(three.js 3D 粒子背景特效,默认不开启)
 *  - 内置 assets/player_fx/web_player_demo.html(网页播放器示例预设,展示桥接规范)
 *  - 用户导入 HTML 文件 → filesDir/player_fx/<id>.html;
 *    页面含 <meta name="player-theme-mode" content="player"> 时识别为网页播放器,否则为背景特效
 *  - 配置:特效启用开关 + 特效页选择 + 播放页主题选择(native=原生默认)
 *
 * 特效/主题页经 WebView 渲染,原生通过 window.__onPlayerEvent(json) 推送播放状态;
 * 网页播放器额外经 window.PlayerBridge.action(json) 回调原生控制(见 PLAYER_THEME_SPEC.md)。
 */
object PlayerFxStore {
    private const val PREFS = "player_fx"
    private const val DIR = "player_fx"
    const val BUILTIN_PARTICLES = "builtin_particles"
    const val BUILTIN_WEB_PLAYER = "builtin_web_player"
    /** 播放页主题:原生默认(当前这套原生实现即默认预设) */
    const val THEME_NATIVE = "native"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
    }

    /** 当前选中的特效页 id(默认内置粒子) */
    fun selectedId(context: Context): String =
        prefs(context).getString("selected", BUILTIN_PARTICLES) ?: BUILTIN_PARTICLES

    fun setSelected(context: Context, id: String) {
        prefs(context).edit().putString("selected", id).apply()
    }

    /** 全部条目(内置特效 + 内置网页播放器示例 + 自定义) */
    fun loadAll(context: Context): List<PlayerFxEntry> {
        val list = mutableListOf(
            PlayerFxEntry(
                id = BUILTIN_PARTICLES,
                name = "内置 · 3D 粒子律动(three.js)",
                file = "player_fx/particles.html",
                builtin = true,
                mode = PlayerFxEntry.MODE_FX
            ),
            PlayerFxEntry(
                id = BUILTIN_WEB_PLAYER,
                name = "内置 · 网页播放器示例(预设)",
                file = "player_fx/web_player_demo.html",
                builtin = true,
                mode = PlayerFxEntry.MODE_PLAYER
            )
        )
        val json = prefs(context).getString("custom", null) ?: return list
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = File(o.optString("file"))
                if (file.isFile) {
                    list.add(
                        PlayerFxEntry(
                            o.optString("id"), o.optString("name"), file.absolutePath, false,
                            mode = o.optString("mode", PlayerFxEntry.MODE_FX)
                        )
                    )
                }
            }
        }
        return list
    }

    /** 当前选中条目(背景特效选择用) */
    fun selectedEntry(context: Context): PlayerFxEntry =
        loadAll(context).firstOrNull { it.id == selectedId(context) } ?: loadAll(context).first()

    // ═══ 播放页主题选择(native=原生默认 / 网页播放器条目 id) ═══

    fun selectedPlayerThemeId(context: Context): String =
        prefs(context).getString("player_theme", THEME_NATIVE) ?: THEME_NATIVE

    fun setSelectedPlayerTheme(context: Context, id: String) {
        prefs(context).edit().putString("player_theme", id).apply()
    }

    /** 指定 id 的网页播放器条目(不存在/非 player 模式返回 null) */
    fun playerThemeEntry(context: Context, id: String): PlayerFxEntry? =
        loadAll(context).firstOrNull { it.id == id && it.mode == PlayerFxEntry.MODE_PLAYER }

    // ═══ 歌词样式(外观设置;播放页原生歌词渲染读取) ═══

    /** 逐字歌词开关(Apple Music 式逐字点亮+放大;默认关) */
    fun isKaraokeEnabled(context: Context): Boolean =
        prefs(context).getBoolean("lyric_karaoke", false)

    fun setKaraokeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("lyric_karaoke", enabled).apply()
    }

    /** 3D 歌单墙:点击封面进入的 3D 卡片墙歌单视图(默认开) */
    fun isWall3DEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wall3d_enabled", true)

    fun setWall3DEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wall3d_enabled", enabled).apply()
    }

    /**
     * 3D 歌单墙背景预设 id:
     *  - "space"  深空(默认,#070910 + 蓝色径向聚光)
     *  - "black"  纯黑
     *  - "navy"   深蓝
     *  - "plum"   暗紫
     *  - "cover"  跟随当前封面(高斯模糊 + 暗化)
     */
    fun wall3DBgPreset(context: Context): String =
        prefs(context).getString("wall3d_bg", "space") ?: "space"

    fun setWall3DBgPreset(context: Context, id: String) {
        prefs(context).edit().putString("wall3d_bg", id).apply()
    }

    /** 3D 歌单墙自定义背景文件绝对路径(图片/视频);null=未设置。预设为 "custom" 时生效 */
    fun wall3DCustomBg(context: Context): String? =
        prefs(context).getString("wall3d_custom_bg", null)

    fun setWall3DCustomBg(context: Context, path: String?) {
        prefs(context).edit().apply {
            if (path == null) remove("wall3d_custom_bg") else putString("wall3d_custom_bg", path)
        }.apply()
    }

    /** 3D 歌单墙是否显示胶片颗粒(默认 true,参照 reference HTML) */
    fun wall3DGrainEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wall3d_grain", true)

    fun setWall3DGrainEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wall3d_grain", enabled).apply()
    }

    /** 3D 歌单墙是否显示外围暗角(默认 true,参照 reference HTML) */
    fun wall3DVignetteEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wall3d_vignette", true)

    fun setWall3DVignetteEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wall3d_vignette", enabled).apply()
    }

    /** 高光(当前行)歌词字号 sp,范围 16~28,默认 21 */
    fun lyricActiveSize(context: Context): Float =
        prefs(context).getFloat("lyric_active_size", 21f).coerceIn(16f, 28f)

    fun setLyricActiveSize(context: Context, size: Float) {
        prefs(context).edit().putFloat("lyric_active_size", size.coerceIn(16f, 28f)).apply()
    }

    /** 逐字放大倍率(正在唱的字),范围 1.0~1.6,默认 1.25 */
    fun lyricKaraokeScale(context: Context): Float =
        prefs(context).getFloat("lyric_karaoke_scale", 1.25f).coerceIn(1f, 1.6f)

    fun setLyricKaraokeScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat("lyric_karaoke_scale", scale.coerceIn(1f, 1.6f)).apply()
    }

    /** 非播放行(及未唱字)透明度,范围 0.1~0.8,默认 0.38 */
    fun lyricInactiveAlpha(context: Context): Float =
        prefs(context).getFloat("lyric_inactive_alpha", 0.38f).coerceIn(0.1f, 0.8f)

    fun setLyricInactiveAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat("lyric_inactive_alpha", alpha.coerceIn(0.1f, 0.8f)).apply()
    }

    /**
     * 导入自定义特效/主题页 HTML;返回条目或 null。
     * 模式自动识别:页面含 <meta name="player-theme-mode" content="player"> 时为网页播放器,
     * 否则为背景特效页。
     */
    fun import(context: Context, html: String): PlayerFxEntry? {
        val trimmed = html.trim()
        if (trimmed.isEmpty() || !trimmed.contains("<", ignoreCase = true)) return null
        if (!trimmed.contains("canvas", ignoreCase = true) && !trimmed.contains("script", ignoreCase = true)) return null
        val mode = if (Regex("player-theme-mode\"\\s+content=\"player\"", RegexOption.IGNORE_CASE).containsMatchIn(trimmed))
            PlayerFxEntry.MODE_PLAYER else PlayerFxEntry.MODE_FX
        val id = "fx_${System.currentTimeMillis() % 1000000}"
        val file = File(dir(context), "$id.html")
        runCatching { file.writeText(trimmed) }.getOrElse { return null }
        // 名称:取 <title> 或用默认
        val name = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: "自定义${if (mode == PlayerFxEntry.MODE_PLAYER) "播放器" else "特效"}_${id.substringAfter('_')}"
        val list = loadAll(context).filter { !it.builtin }
        persistCustom(context, list + PlayerFxEntry(id, name, file.absolutePath, false, mode))
        return PlayerFxEntry(id, name, file.absolutePath, false, mode)
    }

    fun delete(context: Context, id: String) {
        val list = loadAll(context).filter { !it.builtin }
        list.find { it.id == id }?.let { runCatching { File(it.file).delete() } }
        persistCustom(context, list.filterNot { it.id == id })
        if (selectedId(context) == id) setSelected(context, BUILTIN_PARTICLES)
        if (selectedPlayerThemeId(context) == id) setSelectedPlayerTheme(context, THEME_NATIVE)
    }

    private fun persistCustom(context: Context, list: List<PlayerFxEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("name", e.name); put("file", e.file); put("mode", e.mode)
            })
        }
        prefs(context).edit().putString("custom", arr.toString()).apply()
    }
}
