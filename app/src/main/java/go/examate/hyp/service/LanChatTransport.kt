package go.examate.hyp.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.screens.LanChatMessage
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 局域网聊天传输层:封装 UDP 广播收发、设备发现与心跳管理。
 *
 * 协议(JSON):
 *  - hello/heartbeat:设备上线与保活,收到 hello 时回应一次 hello
 *  - msg:聊天消息
 *  - bye:设备下线
 *
 * 由 [LanChatService](悬浮窗)和 [go.examate.hyp.LanChatActivity](全屏页)共用。
 * 回调可能在工作线程触发,UI 侧自行切主线程更新状态。
 */
class LanChatTransport(
    context: Context,
    private val callback: Callback
) {
    private val context = context.applicationContext

    interface Callback {
        /** 收到他人消息(子线程触发) */
        fun onMessageReceived(msg: LanChatMessage)
        /** 在线人数变化(子线程触发),count 含自己 */
        fun onOnlineCountChanged(count: Int)
        /** 错误信息(子线程触发),调用方需在主线程展示 */
        fun onError(message: String)
    }

    companion object {
        private const val PORT = 19876
        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val DEVICE_TIMEOUT_MS = 30_000L
        private const val MAX_MSG_LEN = 500
        private const val TAG = "LanChatTransport"
    }

    private data class DeviceInfo(val name: String, val lastSeen: Long)

    /** 自己的唯一 ID */
    private val myId = UUID.randomUUID().toString()

    /** 自己的昵称:取自设置,空则用设备型号 */
    val myName: String by lazy {
        val nick = PrefsManager.getInstance(context).nickname
        if (nick.isNotEmpty()) nick else Build.MODEL
    }

    /** 已发现的在线设备(id -> 信息) */
    private val devices = ConcurrentHashMap<String, DeviceInfo>()

    /** 在线设备名称列表(含自己),供 UI 展示 */
    fun getOnlineDeviceNames(): List<String> {
        val names = devices.values.map { it.name }.toMutableList()
        names.add(0, myName)
        return names
    }

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    private var multicastLock: WifiManager.MulticastLock? = null

    /** 传输层是否在运行(start 成功且未 stop);供调用方判断是否需重建 */
    val isRunning: Boolean get() = running

    /** 启动 UDP(开 socket、收发线程、心跳),并发送 hello */
    fun start() {
        acquireMulticastLock()
        try {
            // 先设置 SO_REUSEADDR 再 bind,避免重启时 TIME_WAIT 导致端口绑定失败
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                broadcast = true
            }
        } catch (e: Exception) {
            callback.onError("端口 $PORT 启动失败: ${e.message}")
            return
        }
        running = true
        receiveThread = Thread({ receiveLoop() }, "lan-chat-recv").also { it.start() }
        heartbeatThread = Thread({ heartbeatLoop() }, "lan-chat-hb").also { it.start() }
        send(buildPacket("hello"))
        notifyOnlineCount()
    }

    /** 发送聊天消息(含长度校验,超 [MAX_MSG_LEN] 字回调 onError) */
    fun sendMessage(text: String) {
        if (text.length > MAX_MSG_LEN) {
            callback.onError("消息过长,最多 $MAX_MSG_LEN 字")
            return
        }
        val now = System.currentTimeMillis()
        send(JSONObject().apply {
            put("type", "msg"); put("id", myId); put("name", myName)
            put("text", text); put("time", now)
        })
    }

    /** 停止:发送 bye(经 executor 异步,避免主线程网络IO),中断线程,关闭 socket */
    fun stop() {
        running = false
        try {
            send(JSONObject().apply {
                put("type", "bye"); put("id", myId); put("time", System.currentTimeMillis())
            })
        } catch (e: Exception) { Log.w(TAG, "send bye failed", e) }
        // 温和关闭:拒绝新任务但让 bye 发完;最多等 300ms(UDP 发送极快)
        executor.shutdown()
        try { executor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        receiveThread?.interrupt(); heartbeatThread?.interrupt()
        try { socket?.close() } catch (e: Exception) { Log.w(TAG, "socket close failed", e) }
        socket = null
        try { multicastLock?.release() } catch (e: Exception) { Log.w(TAG, "multicastLock release failed", e) }
        multicastLock = null
        devices.clear()
    }

    // ═══ 内部实现 ═══

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("lan_chat").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "acquireMulticastLock failed", e) }
    }

    private fun buildPacket(type: String): JSONObject = JSONObject().apply {
        put("type", type); put("id", myId); put("name", myName)
        put("time", System.currentTimeMillis())
    }

    private fun onPacketReceived(json: JSONObject) {
        val type = json.optString("type")
        val id = json.optString("id")
        if (id == myId || id.isEmpty()) return
        val now = System.currentTimeMillis()
        when (type) {
            "hello", "heartbeat" -> {
                val name = json.optString("name", "未知")
                val existing = devices[id]
                if (existing == null) {
                    devices[id] = DeviceInfo(name, now)
                    if (type == "hello") send(buildPacket("hello"))
                } else {
                    devices[id] = existing.copy(lastSeen = now)
                }
                notifyOnlineCount()
            }
            "msg" -> {
                devices[id]?.let { devices[id] = it.copy(lastSeen = now) }
                val text = json.optString("text")
                val sender = json.optString("name", "未知")
                if (text.isNotEmpty()) {
                    callback.onMessageReceived(LanChatMessage(text, false, sender, json.optLong("time", now)))
                    notifyOnlineCount()
                }
            }
            "bye" -> {
                devices.remove(id)
                notifyOnlineCount()
            }
        }
    }

    private fun notifyOnlineCount() {
        callback.onOnlineCountChanged(devices.size + 1)
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                onPacketReceived(JSONObject(String(packet.data, 0, packet.length)))
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
            // 整体 try-catch:避免 stop() 竞态下(如 executor 已关闭)抛未捕获异常导致闪退
            try {
                val now = System.currentTimeMillis()
                devices.entries.filter { now - it.value.lastSeen > DEVICE_TIMEOUT_MS }
                    .map { it.key }.forEach { devices.remove(it) }
                notifyOnlineCount()
                send(buildPacket("heartbeat"))
            } catch (e: Exception) {
                Log.w(TAG, "heartbeatLoop error", e)
                if (!running) break
            }
        }
    }

    private fun send(json: JSONObject) {
        // stop() 后 executor 已关闭:静默丢弃,避免 RejectedExecutionException 击穿后台线程
        if (executor.isShutdown) return
        try {
            executor.execute {
                try {
                    val data = json.toString().toByteArray(Charsets.UTF_8)
                    socket?.send(DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT))
                } catch (e: Exception) { Log.w(TAG, "send failed", e) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "send rejected: executor stopped")
        }
    }
}
