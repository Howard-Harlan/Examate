package go.examate.hyp.meschat

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * Nostr 云端中继传输层 — Wi-Fi Aware 不可行时的兜底通道。
 *
 * 能力:
 * 1. **多中继冗余**:同时连接全部已启用中继,任一连接成功即视为在线;
 *    连接断开自动重连
 * 2. **中继管理**:系统内置 3 个稳定中继,支持自定义添加/移除/恢复默认,
 *    列表持久化到 SharedPreferences
 * 3. **频道群聊**:kind 1 + #t 频道标签
 * 4. **在线列表**:kind 1 + #t "examate-presence" 标签,供云端用户列表展示
 * 5. **直接私聊**:kind 4 NIP-04 加密(NIP-04 ECDH + AES-CBC),#p 指向对方
 * 6. **密钥匹对房间**:kind 4 + #t 房间ID,内容为配对密钥加密的载荷,
 *    本层原样透传,由控制器解密(本地/云端统一格式)
 *
 * @param context 应用上下文
 * @param callback 传输层回调(主线程触发)
 * @param relayObserver 中继连接状态观察者(connected, total),主线程触发
 */
class NostrTransport(
    private val context: Context,
    private val callback: MeshTransport.Callback,
    private val relayObserver: ((connected: Int, total: Int) -> Unit)? = null
) : MeshTransport {

    override val type = TransportType.NOSTR

    companion object {
        private const val TAG = "NostrTransport"
        private const val PREFS_NAME = "nostr_prefs"
        private const val KEY_PRIVKEY = "privkey"
        private const val KEY_RELAYS = "relays"
        /** 在线列表(云端用户)标签 */
        private const val PRESENCE_TAG = "examate-presence"
        private const val RECONNECT_DELAY_MS = 5_000L

        /** 系统内置的 3 个稳定中继(默认提供,可被用户移除后恢复) */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )

        /** 内置中继的友好名称(用于 UI 展示) */
        fun relayDisplayName(url: String): String = when (url) {
            "wss://relay.damus.io" -> "Damus Relay"
            "wss://nos.lol" -> "nos.lol"
            "wss://relay.primal.net" -> "Primal Relay"
            else -> url.removePrefix("wss://").removePrefix("ws://")
        }
    }

    private var client: OkHttpClient? = null
    /** relayUrl -> WebSocket */
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    /** 已连接(握手完成)的 relay URL 集合 */
    private val connectedUrls = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var running = false

    /** 私钥(hex) */
    private var privKey: String = ""
    /** 公钥(hex),作为 Nostr 上的 peerId */
    private var pubKey: String = ""

    /** 已订阅的频道名 */
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    /** 已订阅的密钥匹对房间 ID */
    private val subscribedRooms = ConcurrentHashMap.newKeySet<String>()
    /** 频道名 -> 订阅 ID(CLOSE 退订时按实际 ID 关闭) */
    private val channelSubIds = ConcurrentHashMap<String, String>()
    /** 房间 ID -> 订阅 ID */
    private val roomSubIds = ConcurrentHashMap<String, String>()
    /** 已知的 Nostr peer(公钥 → 首次见到的时间) */
    private val knownPeers = ConcurrentHashMap<String, Long>()
    /** 订阅 ID 计数器 */
    private var subCounter = 0

    /** 当前启用的中继列表(持久化) */
    private var relays: MutableList<String> = loadRelays()

    private val mainHandler = Handler(Looper.getMainLooper())

    // ════════════════════════════════════════
    //  中继管理
    // ════════════════════════════════════════

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadRelays(): MutableList<String> {
        val raw = prefs().getString(KEY_RELAYS, null)
        if (raw.isNullOrBlank()) return DEFAULT_RELAYS.toMutableList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.startsWith("ws") } }
                .ifEmpty { DEFAULT_RELAYS }
                .toMutableList()
        }.getOrDefault(DEFAULT_RELAYS.toMutableList())
    }

    private fun saveRelays() {
        val arr = JSONArray()
        relays.forEach { arr.put(it) }
        prefs().edit().putString(KEY_RELAYS, arr.toString()).apply()
    }

    /** 当前启用的中继列表(顺序无关) */
    fun currentRelays(): List<String> = relays.toList()

    /** 重新设置启用中继并立即重连 */
    fun setRelays(urls: List<String>) {
        val cleaned = urls.map { it.trim() }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
        relays = if (cleaned.isEmpty()) DEFAULT_RELAYS.toMutableList() else cleaned.toMutableList()
        saveRelays()
        if (running) {
            disconnectAll()
            connectRelays()
        }
        notifyRelayStatus()
    }

    /** 添加自定义中继;已存在则忽略 */
    fun addRelay(url: String): Boolean {
        val cleaned = url.trim()
        if (!cleaned.startsWith("wss://") && !cleaned.startsWith("ws://")) return false
        if (relays.contains(cleaned)) return false
        relays.add(cleaned)
        saveRelays()
        if (running) connectRelay(cleaned)
        notifyRelayStatus()
        return true
    }

    /** 移除中继(内置/自定义均可移除) */
    fun removeRelay(url: String) {
        relays.remove(url)
        saveRelays()
        runCatching { sockets.remove(url)?.close(1000, "removed") }
        connectedUrls.remove(url)
        notifyRelayStatus()
    }

    /** 恢复默认 3 个内置中继 */
    fun resetRelays() {
        setRelays(DEFAULT_RELAYS)
    }

    val connectedRelayCount: Int get() = connectedUrls.size
    val totalRelayCount: Int get() = relays.size
    val isConnected: Boolean get() = connectedUrls.isNotEmpty()
    val currentPubKey: String get() = pubKey

    private fun notifyRelayStatus() {
        mainHandler.post {
            relayObserver?.invoke(connectedRelayCount, totalRelayCount)
        }
    }

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    override fun start(): Boolean {
        if (running) return true
        return try {
            // 加载或生成密钥
            val p = prefs()
            privKey = p.getString(KEY_PRIVKEY, null) ?: run {
                val key = NostrCrypto.generatePrivateKey()
                p.edit().putString(KEY_PRIVKEY, key).apply()
                key
            }
            pubKey = NostrCrypto.getPublicKey(privKey)
            Log.i(TAG, "Nostr transport starting, pubkey=" + pubKey)

            client = OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()

            running = true
            connectRelays()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nostr transport start failed", e)
            running = false
            false
        }
    }

    override fun isAvailable(): Boolean = true

    override fun isRunning(): Boolean = running

    override fun stop() {
        running = false
        disconnectAll()
        knownPeers.clear()
        notifyRelayStatus()
    }

    private fun disconnectAll() {
        sockets.values.forEach { ws -> runCatching { ws.close(1000, "bye") } }
        sockets.clear()
        connectedUrls.clear()
    }

    /** 连接所有启用中继 */
    private fun connectRelays() {
        relays.forEach { connectRelay(it) }
    }

    private fun connectRelay(relayUrl: String) {
        if (!running) return
        if (sockets.containsKey(relayUrl)) return
        val c = client ?: return
        try {
            val request = Request.Builder().url(relayUrl).build()
            val ws = c.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!running) return
                    Log.i(TAG, "Relay connected: " + relayUrl)
                    connectedUrls.add(relayUrl)
                    notifyRelayStatus()
                    // 重新订阅所有频道与房间
                    subscribedChannels.forEach { channel ->
                        subscribeChannel(webSocket, channel)
                    }
                    subscribedRooms.forEach { roomId ->
                        subscribeRoom(webSocket, roomId)
                    }
                    subscribePresence(webSocket)
                    // 私聊回填:拉取发给自己的最近 kind4 事件,离线期间错过的私聊也能补回
                    subscribeDirectMessages(webSocket)
                    // 本机公钥只用于事件去重,不再作为"云端"幽灵用户上报给上层
                    knownPeers.putIfAbsent(pubKey, System.currentTimeMillis())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRelayMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, e: Throwable, response: Response?) {
                    Log.e(TAG, "Relay failure: " + relayUrl, e)
                    sockets.remove(relayUrl)
                    connectedUrls.remove(relayUrl)
                    notifyRelayStatus()
                    scheduleReconnect(relayUrl)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Relay closed: " + relayUrl + " (" + code + ")")
                    sockets.remove(relayUrl)
                    connectedUrls.remove(relayUrl)
                    notifyRelayStatus()
                    scheduleReconnect(relayUrl)
                }
            })
            sockets[relayUrl] = ws
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect relay: " + relayUrl, e)
        }
    }

    private fun scheduleReconnect(relayUrl: String) {
        if (!running || !relays.contains(relayUrl)) return
        mainHandler.postDelayed({ connectRelay(relayUrl) }, RECONNECT_DELAY_MS)
    }

    // ════════════════════════════════════════
    //  发送
    // ════════════════════════════════════════

    override fun sendMessage(json: String, targetPeerId: String?) {
        if (!running || !isConnected) {
            Log.w(TAG, "sendMessage: not connected, drop")
            return
        }
        try {
            val obj = JSONObject(json)
            val channel = obj.optString("channel", "")
            val isPrivate = obj.optBoolean("isPrivate", false)

            if (isPrivate && targetPeerId != null) {
                // 直接私聊:NIP-04 加密 kind 4
                val shared = NostrCrypto.sharedSecretNip04(privKey, targetPeerId) ?: return
                val encrypted = NostrCrypto.encryptNip04(shared, json)
                if (encrypted.isEmpty()) return
                val tags = JSONArray()
                    .put(JSONArray().put("p").put(targetPeerId))
                    .put(JSONArray().put("client").put("meschat"))
                publishEvent(4, tags, encrypted)
            } else if (channel.isNotBlank()) {
                // 频道群聊:kind 1 + #t
                val tags = JSONArray()
                    .put(JSONArray().put("t").put(channel))
                    .put(JSONArray().put("client").put("meschat"))
                publishEvent(1, tags, json)
            } else {
                // 无频道消息(announce 等):kind 1 无 #t
                val tags = JSONArray().put(JSONArray().put("client").put("meschat"))
                publishEvent(1, tags, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
        }
    }

    /** 发布在线列表(presence)消息,供其他云端用户发现本机 */
    fun sendPresence(json: String) {
        if (!running || !isConnected) return
        try {
            val tags = JSONArray()
                .put(JSONArray().put("t").put(PRESENCE_TAG))
                .put(JSONArray().put("client").put("meschat"))
            publishEvent(1, tags, json)
        } catch (e: Exception) {
            Log.e(TAG, "sendPresence failed", e)
        }
    }

    /** 发送密钥匹对房间消息(内容为已加密的载荷 JSON) */
    fun sendRoomMessage(roomId: String, encryptedPayloadJson: String) {
        if (!running || !isConnected) return
        try {
            val tags = JSONArray()
                .put(JSONArray().put("t").put(roomId))
                .put(JSONArray().put("client").put("meschat"))
            publishEvent(4, tags, encryptedPayloadJson)
        } catch (e: Exception) {
            Log.e(TAG, "sendRoomMessage failed", e)
        }
    }

    /** 发布事件到所有已连接中继 */
    private fun publishEvent(kind: Int, tags: JSONArray, content: String) {
        val createdAt = System.currentTimeMillis() / 1000
        val eventId = NostrCrypto.eventId(pubKey, createdAt, kind, tags, content)
        val sig = NostrCrypto.schnorrSign(NostrCrypto.fromHex(eventId), privKey)

        val event = JSONObject().apply {
            put("id", eventId)
            put("pubkey", pubKey)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", tags)
            put("content", content)
            put("sig", sig)
        }
        val eventMsg = JSONArray().put("EVENT").put(event).toString()
        sockets.values.forEach { ws -> runCatching { ws.send(eventMsg) } }
    }

    // ════════════════════════════════════════
    //  订阅
    // ════════════════════════════════════════

    /** 加入频道(订阅 relay 上该频道的消息) */
    fun joinChannel(channel: String) {
        if (subscribedChannels.add(channel)) {
            sockets.values.forEach { ws -> subscribeChannel(ws, channel) }
        }
    }

    /** 离开频道 */
    fun leaveChannel(channel: String) {
        subscribedChannels.remove(channel)
        val subId = channelSubIds.remove(channel) ?: return
        val closeMsg = JSONArray().put("CLOSE").put(subId).toString()
        sockets.values.forEach { ws -> runCatching { ws.send(closeMsg) } }
    }

    /** 订阅密钥匹对房间 */
    fun joinRoom(roomId: String) {
        if (subscribedRooms.add(roomId)) {
            sockets.values.forEach { ws -> subscribeRoom(ws, roomId) }
        }
    }

    /** 退订密钥匹对房间 */
    fun leaveRoom(roomId: String) {
        subscribedRooms.remove(roomId)
        val subId = roomSubIds.remove(roomId) ?: return
        val closeMsg = JSONArray().put("CLOSE").put(subId).toString()
        sockets.values.forEach { ws -> runCatching { ws.send(closeMsg) } }
    }

    private fun nextSubId(prefix: String): String = prefix + "_" + (subCounter++)

    private fun subscribeChannel(ws: WebSocket, channel: String) {
        val subId = channelSubIds.getOrPut(channel) { nextSubId("ch") }
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(1))
            put("#t", JSONArray().put(channel))
            put("limit", 100)
        }
        val reqMsg = JSONArray().put("REQ").put(subId).put(filter).toString()
        runCatching { ws.send(reqMsg) }
        Log.d(TAG, "Subscribed to channel: " + channel)
    }

    private fun subscribeRoom(ws: WebSocket, roomId: String) {
        val subId = roomSubIds.getOrPut(roomId) { nextSubId("room") }
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(4))
            put("#t", JSONArray().put(roomId))
            put("limit", 100)
        }
        val reqMsg = JSONArray().put("REQ").put(subId).put(filter).toString()
        runCatching { ws.send(reqMsg) }
        Log.d(TAG, "Subscribed to room: " + roomId)
    }

    private fun subscribePresence(ws: WebSocket) {
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(1))
            put("#t", JSONArray().put(PRESENCE_TAG))
            put("limit", 50)
        }
        val reqMsg = JSONArray().put("REQ").put(nextSubId("presence")).put(filter).toString()
        runCatching { ws.send(reqMsg) }
    }

    /** 订阅发给自己的 kind4 私聊(relay 会回放最近 100 条,按消息 ID 幂等去重) */
    private fun subscribeDirectMessages(ws: WebSocket) {
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(4))
            put("#p", JSONArray().put(pubKey))
            put("limit", 100)
        }
        val reqMsg = JSONArray().put("REQ").put(nextSubId("dm")).put(filter).toString()
        runCatching { ws.send(reqMsg) }
        Log.d(TAG, "Subscribed to direct messages")
    }

    // ════════════════════════════════════════
    //  接收
    // ════════════════════════════════════════

    private fun handleRelayMessage(text: String) {
        try {
            val arr = JSONArray(text)
            when (arr.optString(0)) {
                "EVENT" -> {
                    val event = arr.optJSONObject(2) ?: return
                    handleNostrEvent(event)
                }
                "EOSE" -> Log.d(TAG, "EOSE: " + arr.optString(1))
                "OK" -> Log.d(TAG, "OK: " + arr.optString(1) + " accepted=" + arr.optBoolean(2))
                "NOTICE" -> Log.w(TAG, "Relay notice: " + arr.optString(1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRelayMessage parse error", e)
        }
    }

    private fun handleNostrEvent(event: JSONObject) {
        try {
            val eventPubkey = event.optString("pubkey")
            val kind = event.optInt("kind", 0)
            val content = event.optString("content")

            // 忽略自己的消息(回环保护)
            if (eventPubkey == pubKey) return

            when (kind) {
                1 -> {
                    // 频道/announce/presence 消息,content 即原始消息 JSON
                    val msgObj = JSONObject(content)
                    val isPresence = msgObj.optString("_type", "") == "announce" &&
                        event.optJSONArray("tags")?.let { tags ->
                            (0 until tags.length()).any { i ->
                                val t = tags.optJSONArray(i)
                                t?.optString(0) == "t" && t.optString(1) == PRESENCE_TAG
                            }
                        } == true

                    if (!knownPeers.containsKey(eventPubkey)) {
                        knownPeers[eventPubkey] = System.currentTimeMillis()
                        val nickname = msgObj.optString("sender", "")
                        mainHandler.post {
                            callback.onPeerConnected(eventPubkey, nickname, type, 0)
                        }
                    }
                    if (isPresence && msgObj.optString("_type") == "announce") {
                        mainHandler.post {
                            callback.onDataReceived(content, eventPubkey, type, 0)
                        }
                        return
                    }
                    mainHandler.post {
                        callback.onDataReceived(content, eventPubkey, type, 0)
                    }
                }
                4 -> {
                    val tags = event.optJSONArray("tags")
                    val tagStrings = (0 until (tags?.length() ?: 0)).mapNotNull { i ->
                        val t = tags?.optJSONArray(i)
                        if (t?.optString(0) == "t") t.optString(1) else null
                    }
                    val pTags = (0 until (tags?.length() ?: 0)).mapNotNull { i ->
                        val t = tags?.optJSONArray(i)
                        if (t?.optString(0) == "p") t.optString(1) else null
                    }
                    // 密钥匹对房间:带 #t 房间ID,内容为加密载荷,透传给控制器解密
                    val roomId = tagStrings.firstOrNull { it in subscribedRooms }
                    if (roomId != null) {
                        mainHandler.post {
                            callback.onDataReceived(content, eventPubkey, type, 0)
                        }
                        return
                    }
                    // 直接私聊:NIP-04 加密,#p 指向我
                    if (pTags.contains(pubKey)) {
                        val secrets = NostrCrypto.sharedSecretsNip04(privKey, eventPubkey)
                        val plain = NostrCrypto.decryptNip04Any(secrets, content) ?: return
                        if (!knownPeers.containsKey(eventPubkey)) {
                            knownPeers[eventPubkey] = System.currentTimeMillis()
                            mainHandler.post {
                                callback.onPeerConnected(eventPubkey, "", type, 0)
                            }
                        }
                        mainHandler.post {
                            callback.onDataReceived(plain, eventPubkey, type, 0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleNostrEvent error", e)
        }
    }
}
