package go.examate.hyp.meschat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Aware (NAN) 传输层 — 与 [BleMeshTransport] 对等的另一种局域网传输通道。
 *
 * 职责:
 * 1. Wi-Fi Aware attach:获取 [WifiAwareSession]
 * 2. 同时 publish + subscribe:发现附近使用相同 service name 的设备
 * 3. 消息传递:通过 [DiscoverySessionCallback.onMessageReceived] 收发数据
 * 4. 消息分片与重组:与 BLE 使用相同的二进制分片格式,处理大于单条消息限制的数据
 *
 * 分片格式(与 BleMeshTransport 一致):
 * - msgHash(4B, Int): SHA-256 前 4 字节
 * - totalFragments(2B, Short): 总片数
 * - fragmentIndex(2B, Short): 当前片号
 * - data(剩余): 当前片数据
 *
 * @param context 应用上下文
 * @param peerId 本机 Peer ID(与 BLE 共用,确保对端识别为同一节点)
 * @param callback 传输层回调(主线程触发)
 */
class WifiAwareTransport(
    private val context: Context,
    private val peerId: String,
    private val callback: BleMeshTransport.Callback
) {
    companion object {
        private const val TAG = "WifiAwareTransport"
        private const val SERVICE_NAME = "examate-meschat"
        private const val FRAGMENT_HEADER_SIZE = 8
        /** Wi-Fi Aware 单条消息有效载荷上限(保守值,兼容绝大多数设备) */
        private const val MAX_MSG_PAYLOAD = 200
        /** peer 心跳/存活超时(毫秒),超时未收到任何消息则认为离线 */
        private const val PEER_TIMEOUT_MS = 60_000L
        /** 心跳检查间隔 */
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val awareManager: WifiAwareManager? =
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    /** 本机 Peer ID(由外部传入,与 BLE 共用) */
    val myPeerId: String = peerId

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    @Volatile private var isRunning = false

    /** peerHandle -> PeerContext */
    private val peers = ConcurrentHashMap<PeerHandle, PeerContext>()

    /** peerId -> peerHandle 映射(便于按 peerId 发送) */
    private val peerIdToHandle = ConcurrentHashMap<String, PeerHandle>()

    /** 分片重组缓冲(与 BLE 实现一致) */
    private val reassemblyBuffers = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val reassemblyMeta = ConcurrentHashMap<String, Triple<Int, String, Long>>()
    private val reassemblyTimeoutMs = 30_000L

    /** 单线程发送执行器,保证消息按序提交 */
    private var sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "WifiAwareTransport-sender").apply { isDaemon = true }
    }

    /** 心跳 Handler(复用主线程 Looper) */
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            sweepStalePeers()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 单个 peer 的上下文 */
    private data class PeerContext(
        val handle: PeerHandle,
        var peerId: String? = null,
        var nickname: String? = null,
        var lastSeen: Long = System.currentTimeMillis()
    )

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    /**
     * 启动 Wi-Fi Aware 传输。
     *
     * 预检失败(权限缺失/设备不支持/Wi-Fi 不可用)时静默返回 false,不调用 [callback.onError],
     * 由上层 [MesChatController] 决定是否提示用户(实现 BLE/Wi-Fi Aware 自动降级)。
     * 仅运行时错误(如 attach 失败)才通过 [callback.onError] 上报。
     *
     * @return true=已成功启动(或此前已运行);false=预检失败未启动
     */
    fun start(): Boolean {
        if (isRunning) return true
        if (!hasPermissions()) return false
        if (awareManager == null) return false
        if (!awareManager.isAvailable) return false
        // 重建发送执行器(上次 stop 可能已 shutdown)
        if (sendExecutor.isShutdown) {
            sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "WifiAwareTransport-sender").apply { isDaemon = true }
            }
        }
        isRunning = true
        attach()
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        return true
    }

    /** 检查 Wi-Fi Aware 通道是否可用(权限已授 + 设备支持 + Wi-Fi 已开),不实际启动 */
    fun isAvailable(): Boolean {
        if (!hasPermissions()) return false
        if (awareManager == null) return false
        if (!awareManager.isAvailable) return false
        return true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        publishSession?.close()
        subscribeSession?.close()
        session?.close()
        publishSession = null
        subscribeSession = null
        session = null
        peers.clear()
        peerIdToHandle.clear()
        reassemblyBuffers.clear()
        reassemblyMeta.clear()
        sendExecutor.shutdownNow()
    }

    fun isRunning(): Boolean = isRunning

    // ════════════════════════════════════════
    //  权限检查
    // ════════════════════════════════════════

    private fun hasPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ════════════════════════════════════════
    //  Attach + Publish + Subscribe
    // ════════════════════════════════════════

    private fun attach() {
        try {
            awareManager?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "Wi-Fi Aware attached, peerId=$peerId")
                    this@WifiAwareTransport.session = session
                    startPublish(session)
                    startSubscribe(session)
                }

                override fun onAttachFailed() {
                    Log.e(TAG, "Wi-Fi Aware attach failed")
                    mainHandler.post { callback.onError("Wi-Fi Aware 连接失败") }
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "attach error", e)
            callback.onError("Wi-Fi Aware 启动异常: ${e.message}")
        }
    }

    private fun startPublish(session: WifiAwareSession) {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            // 在 serviceSpecificInfo 中携带 peerId,发现方即可直接拿到
            .setServiceSpecificInfo(peerId.toByteArray(Charsets.UTF_8))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d(TAG, "Publish started")
                this@WifiAwareTransport.publishSession = session
            }

            override fun onMessageReceived(
                peerHandle: PeerHandle,
                message: ByteArray
            ) {
                handleIncomingFragment(message, peerHandle)
            }
        }, mainHandler)
    }

    private fun startSubscribe(session: WifiAwareSession) {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.d(TAG, "Subscribe started")
                this@WifiAwareTransport.subscribeSession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: MutableList<ByteArray>
            ) {
                // 从 serviceSpecificInfo 解析对端 peerId
                val discoveredPeerId = runCatching {
                    String(serviceSpecificInfo, Charsets.UTF_8)
                }.getOrNull()?.takeIf { it.isNotBlank() }

                Log.d(TAG, "Service discovered: peerId=$discoveredPeerId")
                registerPeer(peerHandle, discoveredPeerId)
            }

            override fun onMessageReceived(
                peerHandle: PeerHandle,
                message: ByteArray
            ) {
                handleIncomingFragment(message, peerHandle)
            }
        }, mainHandler)
    }

    // ════════════════════════════════════════
    //  Peer 管理
    // ════════════════════════════════════════

    private fun registerPeer(handle: PeerHandle, peerId: String?) {
        val existing = peers[handle]
        if (existing == null) {
            peers[handle] = PeerContext(handle = handle, peerId = peerId)
        } else {
            existing.lastSeen = System.currentTimeMillis()
            if (peerId != null) existing.peerId = peerId
        }

        if (peerId != null) {
            peerIdToHandle[peerId] = handle
            // 首次发现:发送 hello 交换身份
            sendHello(handle)
            // 通知上层 peer 已连接
            val ctx = peers[handle]!!
            val nickname = ctx.nickname ?: peerId
            mainHandler.post {
                callback.onPeerConnected(peerId, nickname)
            }
        }
    }

    private fun removePeer(handle: PeerHandle) {
        val ctx = peers.remove(handle)
        val pid = ctx?.peerId
        if (pid != null) peerIdToHandle.remove(pid)
        if (ctx != null && pid != null) {
            mainHandler.post {
                callback.onPeerDisconnected(pid)
            }
        }
    }

    /** 清理超时未通信的 peer */
    private fun sweepStalePeers() {
        val now = System.currentTimeMillis()
        val stale = peers.entries
            .filter { now - it.value.lastSeen > PEER_TIMEOUT_MS }
            .map { it.key }
        stale.forEach { handle ->
            Log.d(TAG, "Sweeping stale peer: ${peers[handle]?.peerId}")
            removePeer(handle)
        }
        sweepStaleReassembly()
    }

    // ════════════════════════════════════════
    //  消息收发
    // ════════════════════════════════════════

    /**
     * 发送 JSON 消息。
     * @param targetPeerId 目标 peerId;null=广播给所有已发现 peer
     */
    fun sendMessage(json: String, targetPeerId: String? = null) {
        val data = json.toByteArray(Charsets.UTF_8)
        val targets = if (targetPeerId != null) {
            val handle = peerIdToHandle[targetPeerId]
            if (handle != null) listOf(handle) else emptyList()
        } else {
            peers.keys.toList()
        }
        if (targets.isEmpty()) return
        sendExecutor.execute {
            targets.forEach { handle ->
                sendFragmentedData(data, handle)
            }
        }
    }

    /** 发送 hello(建立"连接"后交换 peerId) */
    private fun sendHello(handle: PeerHandle) {
        val helloJson = """{"_type":"hello","peerId":"$peerId"}"""
        val data = helloJson.toByteArray(Charsets.UTF_8)
        sendExecutor.execute {
            sendFragmentedData(data, handle)
        }
    }

    /** 分片发送(后台线程) */
    private fun sendFragmentedData(data: ByteArray, handle: PeerHandle) {
        val msgHash = computeMsgHash(data)
        val totalFragments = (data.size + MAX_MSG_PAYLOAD - 1) / MAX_MSG_PAYLOAD
        if (totalFragments > 65535) {
            Log.e(TAG, "Message too large: ${data.size} bytes")
            return
        }

        var consecutiveFail = 0
        for (i in 0 until totalFragments) {
            val offset = i * MAX_MSG_PAYLOAD
            val end = minOf(offset + MAX_MSG_PAYLOAD, data.size)
            val fragmentData = data.copyOfRange(offset, end)
            val packet = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + fragmentData.size)
                .putInt(msgHash)
                .putShort(totalFragments.toShort())
                .putShort(i.toShort())
                .put(fragmentData)
                .array()
            val ok = writePacket(packet, handle)
            if (!ok) {
                consecutiveFail++
                if (consecutiveFail >= 3) {
                    Log.e(TAG, "Aborting fragmented send: repeated failures")
                    return
                }
            } else {
                consecutiveFail = 0
            }
            if (totalFragments > 1) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** 通过 Wi-Fi Aware discovery session 发送单包。返回是否成功提交(不抛异常) */
    private fun writePacket(packet: ByteArray, handle: PeerHandle): Boolean {
        // publish 和 subscribe session 都可以发消息,优先用 publish
        val session = publishSession ?: subscribeSession ?: return false
        // messageId 用递增 int,用于 onMessageSendSucceeded/Failed
        val messageId = nextMessageId()
        return try {
            // sendMessage 返回 messageId(int),不抛异常即视为提交成功
            session.sendMessage(handle, messageId, packet)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            false
        }
    }

    @Volatile private var msgIdCounter = 0
    private fun nextMessageId(): Int {
        return synchronized(this) { ++msgIdCounter }
    }

    // ════════════════════════════════════════
    //  分片重组(与 BleMeshTransport 逻辑一致)
    // ════════════════════════════════════════

    private fun handleIncomingFragment(packet: ByteArray, fromHandle: PeerHandle) {
        if (packet.size < FRAGMENT_HEADER_SIZE) {
            Log.w(TAG, "Fragment too small: ${packet.size}")
            return
        }
        val buffer = ByteBuffer.wrap(packet)
        val msgHash = buffer.int
        val totalFragments = buffer.short.toInt() and 0xFFFF
        val fragmentIndex = buffer.short.toInt() and 0xFFFF
        val fragmentData = ByteArray(packet.size - FRAGMENT_HEADER_SIZE)
        buffer.get(fragmentData)

        if (totalFragments == 0 || fragmentIndex < 0 || fragmentIndex >= totalFragments) {
            Log.w(TAG, "Invalid fragment: total=$totalFragments idx=$fragmentIndex")
            return
        }

        val hashKey = msgHash.toString()
        val ctx = peers[fromHandle]
        val fromPeerId = ctx?.peerId ?: fromHandle.hashCode().toString()

        // 刷新 peer 最后活跃时间
        ctx?.lastSeen = System.currentTimeMillis()

        sweepStaleReassembly()

        val fragMap = reassemblyBuffers.computeIfAbsent(hashKey) { ConcurrentHashMap() }
        fragMap[fragmentIndex] = fragmentData
        reassemblyMeta[hashKey] = Triple(totalFragments, fromPeerId, System.currentTimeMillis())

        if (fragMap.size >= totalFragments) {
            val taken = reassemblyBuffers.remove(hashKey)
            if (taken != null) {
                reassemblyMeta.remove(hashKey)
                val baos = java.io.ByteArrayOutputStream()
                var complete = true
                for (i in 0 until totalFragments) {
                    val frag = taken[i]
                    if (frag == null) {
                        complete = false
                        break
                    }
                    baos.write(frag)
                }
                if (!complete) {
                    Log.w(TAG, "Reassembly incomplete for $hashKey")
                    return
                }
                val json = baos.toString("UTF-8")
                processReceivedJson(json, fromHandle, fromPeerId)
            }
        }
    }

    private fun processReceivedJson(json: String, fromHandle: PeerHandle, fromPeerId: String) {
        try {
            val obj = org.json.JSONObject(json)
            val type = obj.optString("_type", "")
            if (type == "hello") {
                val helloPeerId = obj.optString("peerId", "")
                if (helloPeerId.isNotBlank()) {
                    val ctx = peers[fromHandle]
                    if (ctx != null) {
                        val isFirstHello = ctx.peerId == null
                        ctx.peerId = helloPeerId
                        ctx.nickname = helloPeerId
                        peerIdToHandle[helloPeerId] = fromHandle
                        if (isFirstHello) {
                            mainHandler.post {
                                callback.onPeerConnected(helloPeerId, helloPeerId)
                            }
                        }
                        // 回复 hello(双向交换 peerId)
                        if (ctx.peerId != peerId) {
                            sendHello(fromHandle)
                        }
                    }
                }
            } else {
                mainHandler.post {
                    callback.onDataReceived(json, fromPeerId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processReceivedJson error", e)
        }
    }

    /** 清理超时的分片重组缓冲 */
    private fun sweepStaleReassembly() {
        if (reassemblyMeta.isEmpty()) return
        val now = System.currentTimeMillis()
        val staleKeys = reassemblyMeta.entries
            .filter { now - it.value.third > reassemblyTimeoutMs }
            .map { it.key }
        if (staleKeys.isEmpty()) return
        staleKeys.forEach { key ->
            reassemblyBuffers.remove(key)
            reassemblyMeta.remove(key)
            Log.w(TAG, "Swept stale reassembly buffer: $key")
        }
    }

    private fun computeMsgHash(data: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return ByteBuffer.wrap(digest, 0, 4).int
    }
}
