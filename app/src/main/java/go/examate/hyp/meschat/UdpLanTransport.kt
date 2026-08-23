package go.examate.hyp.meschat

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网 UDP 广播传输 — Wi-Fi Aware 的替代通道。
 *
 * 原理:同一 Wi-Fi 局域网内的设备向子网广播地址的固定端口收发 UDP 数据报。
 * - 发现:周期性广播 hello,对端首次收到即视为上线,超时未续约即离线
 * - 数据:消息以 JSON 信封广播;定向消息在已知对端地址时单播 + 广播双发
 *   (部分路由器的 AP 隔离只拦客户端间单播、放行广播,双发保证私聊可达,
 *    接收端按信封 pid 去重,不会出现重复消息)
 * - 多子网:动态枚举所有网络接口的广播地址(含 Wi-Fi 子网与 Wi-Fi Direct
 *   P2P 组子网),P2P 组建立后无需任何额外配置即可互通
 * - 大消息由 [MesChatController] 预先分片(2048B/片),每片独立成包,UDP 无需再分片
 *
 * 基础收发无需任何运行时权限(仅普通权限 CHANGE_WIFI_MULTICAST_STATE 用于
 * 组播锁,提高休眠状态下收到广播包的可靠性)。
 */
class UdpLanTransport(
    context: Context,
    private val peerId: String,
    private val callback: MeshTransport.Callback
) : MeshTransport {

    companion object {
        /** 局域网广播端口(沿用旧版 UDP 实现的端口) */
        const val PORT = 19876

        /** hello 广播周期(毫秒) */
        private const val HELLO_INTERVAL_MS = 4_000L

        /** 对端超时:超过该时长未收到任何包视为离线 */
        private const val PEER_TIMEOUT_MS = 15_000L

        /** UDP 数据报安全上限(控制器分片 2048B,信封远小于此) */
        private const val MAX_DATAGRAM_BYTES = 60_000

        /** 包级去重:LRU 上限与过期时间(双发兜底的兜底) */
        private const val DEDUP_MAX_ENTRIES = 2048
        private const val DEDUP_TTL_MS = 120_000L

        private const val TAG = "UdpLanTransport"
    }

    override val type: TransportType = TransportType.LAN_UDP

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveThread: Thread? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "meschat-udp-scheduler").apply { isDaemon = true }
    }

    /** 已知对端:peerId -> (地址, 最后活跃时间) */
    private data class PeerEntry(var address: InetAddress, var lastSeen: Long)
    private val knownPeers = ConcurrentHashMap<String, PeerEntry>()

    /** 双发去重:pid -> 首次收到时间(访问需 synchronized) */
    private val recentPacketIds = LinkedHashMap<String, Long>()

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    override fun start(): Boolean {
        if (running.get()) return true
        return try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            // 绑定通配地址:P2P 组接口上到达的包同样能收到
            s.bind(InetSocketAddress(PORT))
            socket = s
            acquireMulticastLock()
            running.set(true)

            receiveThread = Thread({ receiveLoop() }, "meschat-udp-receive").apply {
                isDaemon = true
                start()
            }
            scheduler.scheduleWithFixedDelay({ sendHello() }, 0, HELLO_INTERVAL_MS, TimeUnit.MILLISECONDS)
            // 超时扫描:离线对端回调到主线程
            scheduler.scheduleWithFixedDelay({ sweepStalePeers() }, PEER_TIMEOUT_MS, PEER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.i(TAG, "UDP LAN transport started on port $PORT")
            true
        } catch (e: Exception) {
            Log.w(TAG, "UDP start failed: ${e.message}")
            release()
            false
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        release()
        val offline = knownPeers.keys.toList()
        knownPeers.clear()
        mainHandler.post {
            offline.forEach { callback.onPeerDisconnected(it, type) }
        }
        Log.i(TAG, "UDP LAN transport stopped")
    }

    private fun release() {
        runCatching { scheduler.shutdownNow() }
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        receiveThread = null
    }

    override fun isRunning(): Boolean = running.get()

    /** UDP 广播无特殊前置条件,连接 Wi-Fi 即可用(未连网时发广播静默失败,不算错误) */
    override fun isAvailable(): Boolean = true

    // ════════════════════════════════════════
    //  发送
    // ════════════════════════════════════════

    override fun sendMessage(json: String, targetPeerId: String?) {
        val s = socket ?: return
        val envelope = JSONObject()
            .put("_udp", "d")
            .put("id", peerId)
            .put("pid", UUID.randomUUID().toString())
            .put("d", json)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (envelope.size > MAX_DATAGRAM_BYTES) {
            mainHandler.post { callback.onError("消息过大,超出 UDP 单包上限") }
            return
        }
        // 定向消息:已知地址先单播,再向所有子网广播兜底(AP 隔离常只拦单播),
        // 两次投递携带同一 pid,接收端去重
        if (targetPeerId != null) {
            knownPeers[targetPeerId]?.address?.let { addr ->
                runCatching {
                    s.send(DatagramPacket(envelope, envelope.size, addr, PORT))
                }.onFailure { Log.d(TAG, "unicast failed: ${it.message}") }
            }
        }
        val broadcasts = broadcastAddresses()
        if (broadcasts.isEmpty()) {
            mainHandler.post { callback.onError("无法确定局域网广播地址,请检查 Wi-Fi 连接") }
            return
        }
        broadcasts.forEach { dest ->
            runCatching {
                s.send(DatagramPacket(envelope, envelope.size, dest, PORT))
            }.onFailure { Log.d(TAG, "broadcast failed: ${it.message}") }
        }
    }

    /** 存活广播:对端据此发现本机 */
    private fun sendHello() {
        val s = socket ?: return
        val hello = JSONObject()
            .put("_udp", "hello")
            .put("id", peerId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        broadcastAddresses().forEach { dest ->
            runCatching {
                s.send(DatagramPacket(hello, hello.size, dest, PORT))
            }.onFailure { Log.d(TAG, "hello failed: ${it.message}") }
        }
    }

    // ════════════════════════════════════════
    //  接收
    // ════════════════════════════════════════

    private fun receiveLoop() {
        val buf = ByteArray(MAX_DATAGRAM_BYTES + 64)
        while (running.get()) {
            try {
                val s = socket ?: break
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                handlePacket(packet, buf)
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "receive error: ${e.message}")
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket, buf: ByteArray) {
        val text = String(buf, 0, packet.length, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val from = obj.optString("id")
        if (from.isBlank() || from == peerId) return

        // 包级去重(单播+广播双发会收到两次)
        val pid = obj.optString("pid")
        if (pid.isNotEmpty() && !markPacketSeen(pid)) return

        val isNew = knownPeers[from] == null
        val entry = knownPeers.getOrPut(from) { PeerEntry(packet.address, 0L) }
        entry.address = packet.address
        entry.lastSeen = System.currentTimeMillis()

        val kind = obj.optString("_udp")
        if (isNew && kind == "hello") {
            mainHandler.post { callback.onPeerConnected(from, from, type, 0) }
        }
        if (kind == "d") {
            val data = obj.optString("d")
            if (data.isNotBlank()) {
                mainHandler.post { callback.onDataReceived(data, from, type, 0) }
            }
        }
    }

    /** 记录 pid;已见过则返回 false(丢弃重复包),并顺手清理过期项 */
    private fun markPacketSeen(pid: String): Boolean = synchronized(recentPacketIds) {
        val now = System.currentTimeMillis()
        if (recentPacketIds.containsKey(pid)) return false
        // 过期清理(懒式,超阈值时才扫描)
        if (recentPacketIds.size > DEDUP_MAX_ENTRIES) {
            recentPacketIds.entries.removeAll { now - it.value > DEDUP_TTL_MS }
            // 仍超限(极高频场景)则按插入序丢弃最旧的一半
            val iter = recentPacketIds.entries.iterator()
            while (recentPacketIds.size > DEDUP_MAX_ENTRIES / 2 && iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }
        recentPacketIds[pid] = now
        true
    }

    /** 回收超时对端 */
    private fun sweepStalePeers() {
        val now = System.currentTimeMillis()
        val stale = knownPeers.entries.filter { now - it.value.lastSeen > PEER_TIMEOUT_MS }.map { it.key }
        stale.forEach { knownPeers.remove(it) }
        if (stale.isNotEmpty()) {
            mainHandler.post { stale.forEach { callback.onPeerDisconnected(it, type) } }
        }
    }

    // ════════════════════════════════════════
    //  网络辅助
    // ════════════════════════════════════════

    /** 组播锁:部分设备休眠时丢弃广播包,持锁提高接收可靠性(尽力而为) */
    private fun acquireMulticastLock() {
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("meschat_udp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    /**
     * 枚举所有可用接口的子网广播地址:涵盖 Wi-Fi 子网、Wi-Fi Direct P2P 组子网、
     * 以太网等。P2P 组建立后其接口(p2p-wlan0-0)自动纳入,广播即可直达组内成员。
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback || ni.interfaceAddresses.isEmpty()) continue
                ni.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { result.add(it) }
                }
            }
        }
        if (result.isEmpty()) {
            runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        }
        return result.distinctBy { it.hostAddress }
    }
}
