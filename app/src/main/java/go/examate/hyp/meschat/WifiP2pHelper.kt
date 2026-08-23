package go.examate.hyp.meschat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct (P2P) 直连助手 — 应对路由器 AP 隔离的补充通道。
 *
 * 思路:P2P 组自成子网(通常 192.168.49.x),完全绕过路由器;组建立后
 * [UdpLanTransport] 会自动枚举到该子网的广播地址,现有消息协议原样可用,
 * 控制器与 UI 层无需任何改动(对端仍以 LAN_UDP 通道出现)。
 *
 * 权限:发现对端需 NEARBY_WIFI_DEVICES(Android 13+)或 ACCESS_FINE_LOCATION
 * (12 及以下,另需位置服务开启),均为按需申请,不授权不影响 UDP/云端通道。
 */
class WifiP2pHelper(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2pHelper"

        /** P2P 发现所需运行时权限(按需申请) */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean =
            requiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }

    // ═══ Compose 可观察状态(主线程更新) ═══

    /** 发现的附近 P2P 设备 */
    val discoveredPeers = mutableStateListOf<WifiP2pDevice>()

    /** 正在发现中 */
    val isDiscovering = mutableStateOf(false)

    /** P2P 组已建立(可在组内 UDP 广播互通) */
    val groupFormed = mutableStateOf(false)

    /** 组状态描述文案(null=未组网) */
    val groupSummary = mutableStateOf<String?>(null)

    /** 一次性提示(如连接失败原因),UI 读取后可置空 */
    val notice = mutableStateOf<String?>(null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, -1
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        isDiscovering.value = false
                        notice.value = "系统 Wi-Fi Direct 未开启"
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peers ->
                        mainHandler.post {
                            discoveredPeers.clear()
                            discoveredPeers.addAll(peers.deviceList)
                            isDiscovering.value = false
                            if (peers.deviceList.isEmpty()) {
                                notice.value = "未发现附近设备:对方也需打开 Mesh 聊天的 P2P 直连页"
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = intent.getExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    val group = intent.getExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    val connected = info?.groupFormed == true
                    groupFormed.value = connected
                    groupSummary.value = if (connected) describeGroup(info, group) else null
                    if (connected) {
                        isDiscovering.value = false
                        discoveredPeers.clear()
                        notice.value = "P2P 组已建立,组内设备将通过局域网通道自动出现"
                    }
                }
            }
        }
    }

    /** 初始化并注册广播接收器(进入 P2P 页面时调用) */
    fun ensureStarted() {
        if (manager != null) return
        runCatching {
            manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = manager?.initialize(context, Looper.getMainLooper(), null)
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }.onFailure {
            Log.w(TAG, "P2P init failed: ${it.message}")
            notice.value = "此设备不支持 Wi-Fi Direct"
        }
    }

    /** 开始发现附近 P2P 设备 */
    fun startDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!hasPermissions(context)) {
            notice.value = "缺少 P2P 所需权限"
            return
        }
        isDiscovering.value = true
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* 对端列表经 PEERS_CHANGED 广播返回 */ }
            override fun onFailure(reason: Int) {
                mainHandler.post {
                    isDiscovering.value = false
                    notice.value = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "此设备不支持 Wi-Fi Direct"
                        WifiP2pManager.BUSY -> "P2P 忙,请稍后重试"
                        else -> "发现失败(请确认 Wi-Fi 已开启;Android 12 及以下需打开位置服务)"
                    }
                }
            }
        })
    }

    /** 停止发现 */
    fun stopDiscovery() {
        runCatching {
            manager?.stopPeerDiscovery(channel, null)
        }
        isDiscovering.value = false
    }

    /** 连接选中的对端(对端会弹系统确认框;组主协商由系统完成) */
    @Suppress("DEPRECATION")
    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mainHandler.post { notice.value = "正在与 ${device.deviceName.ifBlank { "对端" }} 协商连接…" }
            }
            override fun onFailure(reason: Int) {
                mainHandler.post {
                    notice.value = "连接失败(对方取消或超时),可重试"
                }
            }
        })
    }

    /** 解散当前 P2P 组 */
    fun disconnectGroup() {
        runCatching {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
        groupFormed.value = false
        groupSummary.value = null
        notice.value = "已断开 P2P 组"
    }

    /** 释放资源(页面销毁时调用;已建立的组不受影响,由系统管理) */
    fun release() {
        stopDiscovery()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        manager = null
        channel = null
    }

    /** 组状态描述:角色 + 组主名 + 组内成员数 */
    private fun describeGroup(info: WifiP2pInfo, group: WifiP2pGroup?): String {
        val role = if (info.isGroupOwner) "组主" else "组员"
        val owner = group?.owner?.deviceName?.ifBlank { null } ?: "未知设备"
        val clients = group?.clientList?.size ?: 0
        return "$role · 组主:$owner" + if (clients > 0) " · 组内 ${clients + 1} 台设备" else ""
    }

    /** 兼容 33+/旧版的 getParcelableExtra */
    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name)
        }
}
