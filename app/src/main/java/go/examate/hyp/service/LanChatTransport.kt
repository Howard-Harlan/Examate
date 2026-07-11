package go.examate.hyp.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
    private val context: Context,
    private val callback: Callback
) {

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

    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    private var multicastLock: WifiManager.MulticastLock? = null

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

    /** 停止:发送 bye,中断线程,关闭 socket */
    fun stop() {
        running = false
        try {
            val bye = JSONObject().apply {
                put("type", "bye"); put("id", myId); put("time", System.currentTimeMillis())
            }
            val data = bye.toString().toByteArray(Charsets.UTF_8)
            socket?.send(DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT))
        } catch (e: Exception) {}
        receiveThread?.interrupt(); heartbeatThread?.interrupt()
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        executor.shutdownNow()
        try { multicastLock?.release() } catch (e: Exception) {}
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
        } catch (e: Exception) {}
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
            } catch (e: Exception) { if (!running) break }
        }
    }

    private fun heartbeatLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS) } catch (e: InterruptedException) { break }
            if (!running) break
            val now = System.currentTimeMillis()
            devices.entries.filter { now - it.value.lastSeen > DEVICE_TIMEOUT_MS }
                .map { it.key }.forEach { devices.remove(it) }
            notifyOnlineCount()
            send(buildPacket("heartbeat"))
        }
    }

    private fun send(json: JSONObject) {
        executor.execute {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                socket?.send(DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT))
            } catch (e: Exception) {}
        }
    }
}
