package go.examate.hyp.music

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 内置音效配置 — 均衡器频段曲线 + 低音增强 + 环绕强度的组合预设。
 * curve: 频率(Hz) → 增益(毫分贝),按设备实际频段中心频率线性插值应用。
 */
data class FxProfile(
    val id: String,
    val name: String,
    val desc: String,
    val bass: Int = 0,          // BassBoost 强度 0..1000
    val virtual: Int = 0,       // Virtualizer 强度 0..1000
    val curve: List<Pair<Int, Int>> = emptyList()   // freq Hz → gain mB
)

/**
 * 音效控制器 — 纯 android.media.audiofx 系统 API,零第三方依赖。
 *
 * 绑定到 MediaPlayer 的 audioSessionId;每次换曲重建 player 后需 rebind(sessionId)。
 * 用户设置持久化到 SharedPreferences,状态用 Compose State 暴露给设置页实时预览。
 *
 * 两套音效来源:
 *  - 内置音效配置([builtinProfiles]):一键应用的调音预设(频段曲线+低音+环绕),
 *    选中后覆盖手动参数;
 *  - 手动参数:低音/环绕滑条 + 系统均衡器预设(选择后视为"自定义",退出内置配置)。
 */
class AudioEffects(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    var enabled by mutableStateOf(prefs.getBoolean(KEY_ENABLED, false))
        private set
    var bassStrength by mutableIntStateOf(prefs.getInt(KEY_BASS, 0))
        private set
    var virtualStrength by mutableIntStateOf(prefs.getInt(KEY_VIRTUAL, 0))
        private set
    var presetIndex by mutableIntStateOf(prefs.getInt(KEY_PRESET, -1))
        private set
    /** 当前内置音效配置 id("none"=未选择,走手动参数) */
    var fxProfileId by mutableStateOf(prefs.getString(KEY_FX_PROFILE, FX_NONE) ?: FX_NONE)
        private set

    /** 均衡器预设名(绑定后可用,否则空) */
    var presetNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 绑定到新的音频会话(换曲后调用) */
    fun bind(sessionId: Int) {
        release()
        if (sessionId == 0) return
        runCatching {
            equalizer = Equalizer(0, sessionId)
            bassBoost = BassBoost(0, sessionId)
            virtualizer = Virtualizer(0, sessionId)
            presetNames = equalizer?.let { eq ->
                (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
            } ?: emptyList()
            applyAll()
        }.onFailure { Log.w(TAG, "bind fail", it) }
    }

    private fun applyAll() {
        val on = enabled
        val profile = builtinProfiles.firstOrNull { it.id == fxProfileId }
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = on
                if (on) {
                    if (profile != null && profile.curve.isNotEmpty()) {
                        // 内置配置:按频段曲线逐级设置(覆盖系统预设)
                        applyCurve(eq, profile.curve)
                    } else if (presetIndex in 0 until eq.numberOfPresets) {
                        eq.usePreset(presetIndex.toShort())
                    }
                }
            }
            // 低音/环绕:内置配置优先,否则手动参数
            val bass = profile?.bass ?: bassStrength
            val virtual = profile?.virtual ?: virtualStrength
            bassBoost?.let { bb ->
                bb.enabled = on && bass > 0
                if (bb.strengthSupported) bb.setStrength(bass.coerceIn(0, 1000).toShort())
            }
            virtualizer?.let { v ->
                v.enabled = on && virtual > 0
                if (v.strengthSupported) v.setStrength(virtual.coerceIn(0, 1000).toShort())
            }
        }.onFailure { Log.w(TAG, "apply fail", it) }
    }

    /** 按曲线插值设置每个频段的电平(毫分贝,钳制到设备范围) */
    private fun applyCurve(eq: Equalizer, curve: List<Pair<Int, Int>>) {
        val range = eq.bandLevelRange   // [min, max] 毫分贝
        val bands = eq.numberOfBands.toInt()
        for (b in 0 until bands) {
            // getCenterFreq 直接返回频段中心频率(毫赫兹)
            val centerHz = (eq.getCenterFreq(b.toShort()) / 1000).toInt()
            val gain = interpolateCurve(curve, centerHz)
            val level = gain.coerceIn(range[0].toInt(), range[1].toInt()).toShort()
            eq.setBandLevel(b.toShort(), level)
        }
    }

    /** 曲线线性插值:区间外取端点值 */
    private fun interpolateCurve(curve: List<Pair<Int, Int>>, hz: Int): Int {
        if (curve.isEmpty()) return 0
        if (hz <= curve.first().first) return curve.first().second
        if (hz >= curve.last().first) return curve.last().second
        for (i in 0 until curve.size - 1) {
            val (f0, g0) = curve[i]
            val (f1, g1) = curve[i + 1]
            if (hz in f0..f1 && f1 > f0) {
                val t = (hz - f0).toFloat() / (f1 - f0)
                return (g0 + (g1 - g0) * t).toInt()
            }
        }
        return curve.last().second
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        applyAll()
    }

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        // 手动调整视为自定义,退出内置配置
        prefs.edit().putInt(KEY_BASS, bassStrength).putString(KEY_FX_PROFILE, FX_NONE).apply()
        fxProfileId = FX_NONE
        applyAll()
    }

    fun setVirtual(strength: Int) {
        virtualStrength = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_VIRTUAL, virtualStrength).putString(KEY_FX_PROFILE, FX_NONE).apply()
        fxProfileId = FX_NONE
        applyAll()
    }

    fun setPreset(index: Int) {
        presetIndex = index
        prefs.edit().putInt(KEY_PRESET, index).putString(KEY_FX_PROFILE, FX_NONE).apply()
        fxProfileId = FX_NONE
        applyAll()
    }

    /** 应用内置音效配置(id 传 [FX_NONE] 取消) */
    fun setFxProfile(id: String) {
        fxProfileId = id
        prefs.edit().putString(KEY_FX_PROFILE, id).apply()
        applyAll()
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null; bassBoost = null; virtualizer = null
    }

    companion object {
        private const val TAG = "AudioEffects"
        private const val PREFS = "music_effects"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASS = "bass"
        private const val KEY_VIRTUAL = "virtual"
        private const val KEY_PRESET = "preset"
        private const val KEY_FX_PROFILE = "fx_profile"
        const val FX_NONE = "none"

        /**
         * 内置音效列表 — 常见听音场景调音(频段曲线按 60/230/910/3600/14000Hz 关键点)。
         */
        val builtinProfiles = listOf(
            FxProfile("bass", "低音增强", "浑厚低频,适合流行/说唱",
                bass = 750, curve = listOf(60 to 420, 150 to 260, 910 to 0, 3600 to -40, 14000 to 60)),
            FxProfile("pop", "流行", "人声突出,三频均衡偏中",
                bass = 180, curve = listOf(60 to 120, 230 to 80, 910 to 240, 3600 to 280, 14000 to 120)),
            FxProfile("rock", "摇滚", "强劲鼓点 + 明亮高频",
                bass = 420, curve = listOf(60 to 340, 230 to 120, 910 to -60, 3600 to 260, 14000 to 240)),
            FxProfile("dance", "电子", "重低音 + V 型调音",
                bass = 620, curve = listOf(60 to 440, 230 to 60, 910 to -140, 3600 to 160, 14000 to 300)),
            FxProfile("classical", "古典", "开阔声场,高频细腻",
                bass = 0, virtual = 300, curve = listOf(60 to 60, 230 to 0, 910 to 80, 3600 to 180, 14000 to 280)),
            FxProfile("vocal", "人声增强", "清晰人声,适合播客/民谣",
                bass = 0, curve = listOf(60 to -80, 230 to 40, 910 to 300, 3600 to 380, 14000 to 80)),
            FxProfile("surround", "环绕声场", "空间感增强(耳机效果更明显)",
                bass = 120, virtual = 780, curve = listOf(60 to 140, 230 to 40, 910 to 60, 3600 to 140, 14000 to 160))
        )
    }
}
