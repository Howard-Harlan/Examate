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
 * 音效控制器 — 纯 android.media.audiofx 系统 API,零第三方依赖。
 *
 * 绑定到 MediaPlayer 的 audioSessionId;每次换曲重建 player 后需 rebind(sessionId)。
 * 用户设置持久化到 SharedPreferences,状态用 Compose State 暴露给设置页实时预览。
 *
 * 强度约定:BassBoost/Virtualizer 为 0..1000;均衡器用预设索引(-1 表示不套预设)。
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
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = on
                if (on && presetIndex in 0 until eq.numberOfPresets) eq.usePreset(presetIndex.toShort())
            }
            bassBoost?.let { bb ->
                bb.enabled = on && bassStrength > 0
                if (bb.strengthSupported) bb.setStrength(bassStrength.toShort())
            }
            virtualizer?.let { v ->
                v.enabled = on && virtualStrength > 0
                if (v.strengthSupported) v.setStrength(virtualStrength.toShort())
            }
        }.onFailure { Log.w(TAG, "apply fail", it) }
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        applyAll()
    }

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_BASS, bassStrength).apply()
        applyAll()
    }

    fun setVirtual(strength: Int) {
        virtualStrength = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_VIRTUAL, virtualStrength).apply()
        applyAll()
    }

    fun setPreset(index: Int) {
        presetIndex = index
        prefs.edit().putInt(KEY_PRESET, index).apply()
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
    }
}
