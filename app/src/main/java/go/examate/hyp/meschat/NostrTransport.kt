package go.examate.hyp.meschat

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Nostr 云端传输通道 — 通过 WebSocket 连接 Nostr relay 收发消息。
 *
 * 与 BLE/Wi-Fi Aware/UDP 不同,Nostr 是云端中继传输:
 * - 频道消息用 kind 1 事件,tag "t" 标记频道名
 * - 私聊暂不支持(NIP-04 加密待后续实现)
 * - 私钥持久化到 SharedPreferences,公钥作为 peerId
 *
 * 默认连接 relay: wss://relay.damus.io
 */
class NostrTransport(
    private val context: Context,
    private val callback: BleMeshTransport.Callback
) {
    companion object {
        private const val TAG = "NostrTransport"
        private const val PREFS_NAME = "nostr_prefs"
        private const val KEY_PRIVKEY = "privkey"
        /** 默认 relay 列表 */
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nostr.wine",
            "wss://relay.snort.social"
        )
    }

    private var client: OkHttpClient? = null
    private val webSockets = mutableListOf<WebSocket>()
    @Volatile private var running = false
    @Volatile private var connected = false

    /** 私钥(hex) */
    private var privKey: String = ""
    /** 公钥(hex),作为 peerId */
    private var pubKey: String = ""

    /** 已订阅的频道 */
    private val subscribedChannels = mutableSetOf<String>()
    /** 已知的 Nostr peer(公钥 → 首次见到的时间) */
    private val knownPeers = ConcurrentHashMap<String, Long>()
    /** 订阅 ID 计数器 */
    private var subCounter = 0

    /**
     * 启动 Nostr 传输:加载密钥,连接 relay,订阅已加入频道。
     * @return 是否成功启动
     */
    fun start(): Boolean {
        if (running) return true
        try {
            // 加载或生成密钥
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            privKey = prefs.getString(KEY_PRIVKEY, null) ?: run {
                val key = NostrCrypto.generatePrivateKey()
                prefs.edit().putString(KEY_PRIVKEY, key).apply()
                key
            }
            pubKey = NostrCrypto.getPublicKey(privKey)
            Log.i(TAG, "Nostr transport starting, pubkey=$pubKey")

            client = OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

            running = true
            connectRelays()

            // 延迟通知 peer "上线"
            Thread {
                Thread.sleep(1000)
                if (running && connected) {
                    callback.onPeerConnected(pubKey, "云端")
                }
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Nostr transport start failed", e)
            running = false
            return false
        }
    }

    /** 连接所有 relay */
    private fun connectRelays() {
        val c = client ?: return
        DEFAULT_RELAYS.forEach { relayUrl ->
            try {
                val request = Request.Builder().url(relayUrl).build()
                val ws = c.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "Relay connected: $relayUrl")
                        connected = true
                        // 重新订阅所有频道
                        synchronized(subscribedChannels) {
                            subscribedChannels.forEach { channel ->
                                subscribeChannel(webSocket, channel)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleRelayMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, e: Throwable, response: Response?) {
                        Log.e(TAG, "Relay failure: $relayUrl", e)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "Relay closed: $relayUrl ($code)")
                    }
                })
                synchronized(webSockets) { webSockets.add(ws) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect relay: $relayUrl", e)
            }
        }
    }

    /** 停止 Nostr 传输 */
    fun stop() {
        running = false
        connected = false
        synchronized(webSockets) {
            webSockets.forEach { runCatching { it.close(1000, "bye") } }
            webSockets.clear()
        }
        knownPeers.clear()
        callback.onPeerDisconnected(pubKey)
    }

    /**
     * 发送消息(包装为 Nostr 事件发布到 relay)。
     * @param json 原始消息 JSON
     * @param targetPeerId 目标 peer(私聊,暂不支持,忽略)
     */
    fun sendMessage(json: String, targetPeerId: String? = null) {
        if (!running || !connected) {
            Log.w(TAG, "sendMessage: not connected, drop")
            return
        }
        try {
            val obj = JSONObject(json)
            val channel = obj.optString("channel", "")
            val isPrivate = obj.optBoolean("isPrivate", false)
            // 私聊暂不支持(需要 NIP-04 加密)
            if (isPrivate) {
                Log.w(TAG, "sendMessage: private messages not supported via Nostr yet")
                return
            }

            // 创建 Nostr 事件
            val tags = JSONArray()
            if (channel.isNotBlank()) {
                tags.put(JSONArray().put("t").put(channel))
            }
            // 标记来源,便于接收方识别
            tags.put(JSONArray().put("client").put("meschat"))

            val createdAt = System.currentTimeMillis() / 1000
            val eventId = NostrCrypto.eventId(pubKey, createdAt, 1, tags, json)
            val sig = NostrCrypto.schnorrSign(NostrCrypto.fromHex(eventId), privKey)

            val event = JSONObject().apply {
                put("id", eventId)
                put("pubkey", pubKey)
                put("created_at", createdAt)
                put("kind", 1)
                put("tags", tags)
                put("content", json)
                put("sig", sig)
            }

            val eventMsg = JSONArray().put("EVENT").put(event).toString()
            synchronized(webSockets) {
                webSockets.forEach { it.send(eventMsg) }
            }
            Log.d(TAG, "Sent Nostr event to channel=$channel")
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    /** 加入频道(订阅 relay 上该频道的消息) */
    fun joinChannel(channel: String) {
        if (!running) return
        synchronized(subscribedChannels) {
            if (channel in subscribedChannels) return
            subscribedChannels.add(channel)
        }
        synchronized(webSockets) {
            webSockets.forEach { subscribeChannel(it, channel) }
        }
    }

    /** 离开频道 */
    fun leaveChannel(channel: String) {
        synchronized(subscribedChannels) { subscribedChannels.remove(channel) }
        // Nostr relay 的 CLOSE 消息可取消订阅
        val subId = "meschat_${channel.hashCode()}"
        val closeMsg = JSONArray().put("CLOSE").put(subId).toString()
        synchronized(webSockets) { webSockets.forEach { it.send(closeMsg) } }
    }

    /** 订阅指定频道的消息 */
    private fun subscribeChannel(ws: WebSocket, channel: String) {
        val subId = "meschat_${channel.hashCode()}"
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(1))
            put("#t", JSONArray().put(channel))
            put("limit", 100)
        }
        val reqMsg = JSONArray().put("REQ").put(subId).put(filter).toString()
        ws.send(reqMsg)
        Log.d(TAG, "Subscribed to channel: $channel (subId=$subId)")
    }

    /**
     * 处理 relay 返回的消息。
     * 主要处理 EVENT 消息(包含其他用户发布的消息)。
     */
    private fun handleRelayMessage(text: String) {
        try {
            val arr = JSONArray(text)
            val msgType = arr.optString(0)
            when (msgType) {
                "EVENT" -> {
                    // ["EVENT", subId, event]
                    val event = arr.optJSONObject(2) ?: return
                    handleNostrEvent(event)
                }
                "EOSE" -> {
                    // ["EOSE", subId] — 订阅完成,历史消息已发送
                    Log.d(TAG, "EOSE: ${arr.optString(1)}")
                }
                "OK" -> {
                    // ["OK", eventId, accepted, message] — 事件发布结果
                    Log.d(TAG, "OK: ${arr.optString(1)} accepted=${arr.optBoolean(2)}")
                }
                "NOTICE" -> {
                    Log.w(TAG, "Relay notice: ${arr.optString(1)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRelayMessage parse error", e)
        }
    }

    /**
     * 处理收到的 Nostr 事件:提取 content(原始消息 JSON),回调给上层。
     */
    private fun handleNostrEvent(event: JSONObject) {
        try {
            val eventPubkey = event.optString("pubkey")
            val content = event.optString("content")

            // 忽略自己的消息(回环保护)
            if (eventPubkey == pubKey) return

            // 解析 content 中的原始消息 JSON
            val msgObj = JSONObject(content)
            val senderPeerId = msgObj.optString("senderPeerId", eventPubkey)

            // 如果是新的 peer,通知连接
            if (!knownPeers.containsKey(eventPubkey)) {
                knownPeers[eventPubkey] = System.currentTimeMillis()
                val nickname = msgObj.optString("sender", "云端用户")
                callback.onPeerConnected(eventPubkey, nickname)
            }

            // 回调消息
            callback.onDataReceived(content, eventPubkey)
        } catch (e: Exception) {
            Log.e(TAG, "handleNostrEvent error", e)
        }
    }

    /** Nostr 是否已连接 */
    val isConnected: Boolean get() = connected

    /** 当前公钥(peerId) */
    val currentPubKey: String get() = pubKey
}
