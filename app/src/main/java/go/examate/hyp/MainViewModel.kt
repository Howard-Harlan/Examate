package go.examate.hyp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MainViewModel:持有 MainActivity 的 UI 状态,使用 MutableStateFlow + asStateFlow()
 * 暴露不可变 StateFlow,由 Composable 用 collectAsStateWithLifecycle() 收集。
 *
 * 权限类状态(overlay/accessibility/battery/shizuku)由 Activity 查询系统后调用 update 方法更新。
 * 功能设置类状态(forbidCapture/edgeAttach/transparentFloat/multiAiVerify)已迁移到
 * SearchSettingsActivity 直接读写 PrefsManager,不再经过 ViewModel。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ═══ 权限授权状态 ═══

    private val _overlayGrantedState = MutableStateFlow(false)
    val overlayGrantedState: StateFlow<Boolean> = _overlayGrantedState.asStateFlow()

    private val _accessibilityGrantedState = MutableStateFlow(false)
    val accessibilityGrantedState: StateFlow<Boolean> = _accessibilityGrantedState.asStateFlow()

    private val _batteryGrantedState = MutableStateFlow(false)
    val batteryGrantedState: StateFlow<Boolean> = _batteryGrantedState.asStateFlow()

    private val _shizukuGrantedState = MutableStateFlow(false)
    val shizukuGrantedState: StateFlow<Boolean> = _shizukuGrantedState.asStateFlow()

    // ═══ 权限状态更新(由 Activity 查询系统后调用)═══

    fun updateOverlayGranted(value: Boolean) {
        _overlayGrantedState.value = value
    }

    fun updateAccessibilityGranted(value: Boolean) {
        _accessibilityGrantedState.value = value
    }

    fun updateBatteryGranted(value: Boolean) {
        _batteryGrantedState.value = value
    }

    fun updateShizukuGranted(value: Boolean) {
        _shizukuGrantedState.value = value
    }
}
