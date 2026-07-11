package go.examate.hyp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MainViewModel:持有 MainActivity 的 UI 状态,使用 MutableStateFlow + asStateFlow()
 * 暴露不可变 StateFlow,由 Composable 用 collectAsState() 收集。
 *
 * - 权限类状态(overlay/accessibility/battery/shizuku)由 Activity 查询系统后调用 update 方法更新。
 * - 设置类状态(forbidCapture/edgeAttach/transparentFloat/multiAiVerify)由 set 方法同时
 *   持久化到 PrefsManager 并更新 Flow。
 * - providerCount 初始值从 MyConfig.loadProviders 读取。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PrefsManager.getInstance(application)

    // ═══ 权限授权状态 ═══

    private val _overlayGrantedState = MutableStateFlow(false)
    val overlayGrantedState: StateFlow<Boolean> = _overlayGrantedState.asStateFlow()

    private val _accessibilityGrantedState = MutableStateFlow(false)
    val accessibilityGrantedState: StateFlow<Boolean> = _accessibilityGrantedState.asStateFlow()

    private val _batteryGrantedState = MutableStateFlow(false)
    val batteryGrantedState: StateFlow<Boolean> = _batteryGrantedState.asStateFlow()

    private val _shizukuGrantedState = MutableStateFlow(false)
    val shizukuGrantedState: StateFlow<Boolean> = _shizukuGrantedState.asStateFlow()

    // ═══ 功能设置状态 ═══

    private val _forbidCaptureState = MutableStateFlow(false)
    val forbidCaptureState: StateFlow<Boolean> = _forbidCaptureState.asStateFlow()

    private val _edgeAttachState = MutableStateFlow(false)
    val edgeAttachState: StateFlow<Boolean> = _edgeAttachState.asStateFlow()

    private val _transparentFloatState = MutableStateFlow(false)
    val transparentFloatState: StateFlow<Boolean> = _transparentFloatState.asStateFlow()

    private val _multiAiVerifyState = MutableStateFlow(false)
    val multiAiVerifyState: StateFlow<Boolean> = _multiAiVerifyState.asStateFlow()

    private val _providerCountState = MutableStateFlow(0)
    val providerCountState: StateFlow<Int> = _providerCountState.asStateFlow()

    init {
        _forbidCaptureState.value = prefs.isForbidCapture
        _edgeAttachState.value = prefs.isEdgeAttach
        _transparentFloatState.value = prefs.isTransparentFloat
        _multiAiVerifyState.value = prefs.isMultiAiVerify
        _providerCountState.value = MyConfig.loadProviders(application).size
    }

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

    // ═══ 设置项更新(同时持久化到 PrefsManager)═══

    fun setForbidCapture(value: Boolean) {
        prefs.isForbidCapture = value
        _forbidCaptureState.value = value
    }

    fun setEdgeAttach(value: Boolean) {
        prefs.isEdgeAttach = value
        _edgeAttachState.value = value
    }

    fun setTransparentFloat(value: Boolean) {
        prefs.isTransparentFloat = value
        _transparentFloatState.value = value
    }

    fun setMultiAiVerify(value: Boolean) {
        prefs.isMultiAiVerify = value
        _multiAiVerifyState.value = value
    }
}
