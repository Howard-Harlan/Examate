package go.examate.hyp.meschat

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * UDP 局域网传输层 — MesChat 的第三种传输通道。
 *
 * 在 BLE 与 Wi-Fi Aware 之上,新增 UDP 广播作为同 Wi-Fi 下的高速通道:
 * - 协议端口与旧版 LanChatTransport 一致(19876),便于与旧版 UDP 聊天互通
 * - 承载 MesChat 的 JSON 消息格式(hello/announce/leave/message),与 BLE/Wi-Fi Aware 协议一致
 * - 设备发现:UDP 广播 hello/heartbeat,被动接收 + 超时清理
 * - 消息收发:广播方式,所有同网段设备均可收到(频道广播语义天然契合)
 *
 * 与 BLE/Wi-Fi Aware 的差异:
 * - 无需配对/连接握手,延迟极低
 * - 仅适用于同一 Wi-Fi 网段,无法跨网络
 * - 广播包无法定向到单一 peer(私聊仍靠 recipientPeerId 字段校验)
 *
 * 与 [BleMeshTransport] 共享 [Callback] 接口,上层 [MesChatController] 可透明接入。
 *
 * @param context 应用上下文
 * @param myPeerId 本机 Peer ID(与 BLE/Wi-Fi Aware 共用,确保跨通道身份一致)
 * @param callback 传输层回调(主线程触发)
 */
class UdpLanTransport(
    context: Context,
    private val myPeerId: String,
    private val callback: BleMeshTransport.Callback
) {
    companion object {
        private const val TAG = "UdpLanTransport"
        /** UDP 端口(与旧版 LanChatTransport 一致,便于互通) */
        private const val PORT = 19876
        /** 广播地址 */
        private const val BROADCAST_ADDR = "255.255.255.255"
        /** 心跳间隔 */
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        /** 设备超时清理阈值 */
        private const val DEVICE_TIMEOUT_MS = 30_000L
        /** UDP 单包最大字节数(留足余量避免 IP 分片) */
        private const val MAX_PACKET_BYTES = 4096
    }

    private val context = context.applicationContext

    /** 已发现的 UDP peer:peerId -> PeerInfo */
    private data class PeerInfo(val nickname: String, val address: String, val lastSeen: Long)
    private val udpPeers = ConcurrentHashMap<String, PeerInfo>()

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    private var multicastLock: WifiManager.MulticastLock? = null
    /** WifiLock:防止 Wi-Fi 休眠导致收不到广播包 */
    private var wifiLock: WifiManager.WifiLock? = null
    /** 子网定向广播地址(基于本机 DHCP 信息计算,优先于 255.255.255.255) */
    private var broadcastAddress: String = BROADCAST_ADDR

    /** 传输层是否在运行 */
    val isRunning: Boolean get() = running

    /** 启动 UDP 传输层(开 socket、收发线程、心跳),并发送 hello */
    fun start(): Boolean {
        return try {
            // 重建 executor(上次 stop 可能已 shutdown)
            if (executor.isShutdown) {
                executor = Executors.newSingleThreadExecutor()
            }
            acquireMulticastLock()
            acquireWifiLock()
            updateBroadcastAddress()
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                broadcast = true
            }
            running = true
            receiveThread = Thread({ receiveLoop() }, "udp-meschat-recv").also { it.start() }
            heartbeatThread = Thread({ heartbeatLoop() }, "udp-meschat-hb").also { it.start() }
            // 发送 hello,携带 peerId 让对端学习本机身份
            sendUdp(buildHelloPacket())
            Log.i(TAG, "UDP transport started on port $PORT, peerId=$myPeerId, broadcast=$broadcastAddress")
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP transport start failed", e)
            running = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            try { multicastLock?.release() } catch (_: Exception) {}
            multicastLock = null
            try { wifiLock?.release() } catch (_: Exception) {}
            wifiLock = null
            false
        }
    }

    /** 停止:发送 leave,中断线程,关闭 socket */
    fun stop() {
        if (!running) return
        running = false
        try { sendUdp(buildLeavePacket()) } catch (e: Exception) { Log.w(TAG, "send leave failed", e) }
        executor.shutdown()
        try { executor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        receiveThread?.interrupt()
        heartbeatThread?.interrupt()
        try { socket?.close() } catch (e: Exception) { Log.w(TAG, "socket close failed", e) }
        socket = null
        try { multicastLock?.release() } catch (e: Exception) { Log.w(TAG, "multicastLock release failed", e) }
        multicastLock = null
        try { wifiLock?.release() } catch (e: Exception) { Log.w(TAG, "wifiLock release failed", e) }
        wifiLock = null
        // 通知所有 UDP peer 断开
        udpPeers.keys.toList().forEach { callback.onPeerDisconnected(it) }
        udpPeers.clear()
    }

    /**
     * 发送 JSON 消息。
     * - targetPeerId == null:广播(频道消息),所有 UDP peer 都能收到
     * - targetPeerId != null:私聊(仍用广播发送,由对端 recipientPeerId 校验过滤)
     *
     * 注:UDP 广播无法定向到单一设备,私聊靠应用层 recipientPeerId 字段校验。
     * 这与 BLE/Wi-Fi Aware 的单播语义不同,但 MesChatController 已统一处理校验。
     */
    fun sendMessage(json: String, targetPeerId: String? = null) {
        if (!running) return
        sendUdp(json)
    }

    // ════════════════════════════════════════
    //  内部实现
    // ════════════════════════════════════════

    private fun acquireMulticastLock() {
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("meschat_udp").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "acquireMulticastLock failed", e) }
    }

    /** 获取 WifiLock,防止 Wi-Fi 休眠导致收不到广播包 */
    private fun acquireWifiLock() {
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "meschat_udp_recv").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "acquireWifiLock failed", e) }
    }

    /**
     * 基于本机 DHCP 信息计算子网定向广播地址。
     *
     * 255.255.255.255(限定广播)在部分 Android 版本/路由器上被阻止发送,
     * 改用子网定向广播(如 192.168.1.255)可显著提升到达率。
     * 计算失败时回退到 255.255.255.255。
     */
    private fun updateBroadcastAddress() {
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifi.dhcpInfo ?: return
            if (dhcp.netmask == 0) return
            // dhcp.ipAddress / netmask 均为 little-endian int,直接位运算结果同序
            val broadcast = (dhcp.ipAddress and dhcp.netmask) or (dhcp.netmask.inv())
            val addr = "${broadcast and 0xFF}.${(broadcast shr 8) and 0xFF}." +
                "${(broadcast shr 16) and 0xFF}.${(broadcast shr 24) and 0xFF}"
            if (addr != "0.0.0.0") {
                broadcastAddress = addr
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateBroadcastAddress failed", e)
        }
    }

    /** 构造 hello 包(携带 peerId,用于身份广播) */
    private fun buildHelloPacket(): String = JSONObject().apply {
        put("_type", "hello")
        put("peerId", myPeerId)
    }.toString()

    /** 构造 leave 包 */
    private fun buildLeavePacket(): String = JSONObject().apply {
        put("_type", "leave")
        put("peerId", myPeerId)
    }.toString()

    /** 构造 heartbeat 包 */
    private fun buildHeartbeatPacket(): String = JSONObject().apply {
        put("_type", "heartbeat")
        put("peerId", myPeerId)
    }.toString()

    private fun receiveLoop() {
        val buf = ByteArray(MAX_PACKET_BYTES)
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                val text = String(packet.data, 0, packet.length)
                val sourceAddress = packet.address.hostAddress ?: ""
                onPacketReceived(text, sourceAddress)
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "receiveLoop error", e)
            }
        }
    }

    private fun heartbeatLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS) } catch (e: InterruptedException) { break }
            if (!running) break
            try {
                val now = System.currentTimeMillis()
                // 清理超时 peer
                val expired = udpPeers.entries.filter { now - it.value.lastSeen > DEVICE_TIMEOUT_MS }.map { it.key }
                expired.forEach { peerId ->
                    udpPeers.remove(peerId)
                    callback.onPeerDisconnected(peerId)
                }
                // 发送心跳
                sendUdp(buildHeartbeatPacket())
            } catch (e: Exception) {
                Log.w(TAG, "heartbeatLoop error", e)
                if (!running) break
            }
        }
    }

    private fun onPacketReceived(text: String, sourceAddress: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("_type", "message")
            val peerId = obj.optString("peerId", obj.optString("senderPeerId", ""))
            // 忽略自己的广播回环
            if (peerId == myPeerId || peerId.isEmpty()) return

            val now = System.currentTimeMillis()
            when (type) {
                "hello", "heartbeat" -> {
                    val existing = udpPeers[peerId]
                    val nickname = obj.optString("nickname", existing?.nickname ?: "")
                    if (existing == null) {
                        // 新 peer 上线
                        udpPeers[peerId] = PeerInfo(nickname, sourceAddress, now)
                        // 收到 hello 回应一次 hello(双向交换 peerId)
                        if (type == "hello") sendUdp(buildHelloPacket())
                        // 通知上层 peer 连接(昵称为空时上层会通过 announce 补全)
                        callback.onPeerConnected(peerId, nickname)
                    } else {
                        udpPeers[peerId] = existing.copy(lastSeen = now)
                    }
                }
                "leave" -> {
                    udpPeers.remove(peerId)
                    callback.onPeerDisconnected(peerId)
                }
                else -> {
                    // 聊天消息/announce 等,更新 peer 最后活跃时间
                    udpPeers[peerId]?.let { udpPeers[peerId] = it.copy(lastSeen = now) }
                    callback.onDataReceived(text, peerId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onPacketReceived parse error", e)
        }
    }

    private fun sendUdp(json: String) {
        if (executor.isShutdown) return
        try {
            executor.execute {
                try {
                    val data = json.toByteArray(Charsets.UTF_8)
                    if (data.size > MAX_PACKET_BYTES) {
                        Log.w(TAG, "packet too large (${data.size}B), drop")
                        return@execute
                    }
                    // 优先发送子网定向广播(255.255.255.255 在部分 Android/路由器被阻止发送)
                    socket?.send(DatagramPacket(data, data.size, InetAddress.getByName(broadcastAddress), PORT))
                    // 子网广播与限定广播不同时,额外发一次限定广播提高可靠性
                    if (broadcastAddress != BROADCAST_ADDR) {
                        socket?.send(DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT))
                    }
                } catch (e: Exception) { Log.w(TAG, "sendUdp failed", e) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "sendUdp rejected: executor stopped")
        }
    }
}
