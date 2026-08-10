package go.examate.hyp.meschat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Mesh 聊天控制器 — 连接传输层与 UI 层。
 *
 * 职责:
 * 1. 持有 [BleMeshTransport],实现其回调
 * 2. 管理会话列表([ChatConversation])和每会话消息([MesMessage])
 * 3. 将传输层 JSON 映射为 UI 模型
 * 4. 提供发送消息方法(序列化为 JSON 经传输层发出)
 * 5. 维护传输状态([TransportStatus])
 * 6. 处理 IRC 命令(/join /msg /nick 等)
 *
 * Compose 可直接观察 [conversations]、[messagesByConversation]、[transportStatus] 等状态。
 *
 * @param context 应用上下文
 * @param myNickname 本机昵称
 */
class MesChatController(
    private val context: Context,
    var myNickname: String
) {
    companion object {
        private const val TAG = "MesChatController"
        /** 默认频道:进入 Mesh 聊天即加入的附近群聊 */
        private const val DEFAULT_CHANNEL = "附近"
        /** 分片大小(字节):每片 JSON 的 chunk 部分最大字节数,控制在 UDP 4096 限制内 */
        private const val FRAGMENT_SIZE = 2048
        /** 分片组超时清理(60秒未收齐的分片组丢弃) */
        private const val FRAGMENT_TIMEOUT_MS = 60_000L
        /** 每个会话最大保留消息数(防止内存无限增长) */
        private const val MAX_MESSAGES_PER_CONVERSATION = 500

        @Volatile
        private var instance: MesChatController? = null

        /**
         * 获取已启动的单例(若存在)。
         * 用于悬浮窗服务复用 Activity 创建的 controller,避免重复启动传输层。
         */
        fun get(): MesChatController? = instance

        /**
         * 确保 controller 已启动;若已存在则直接复用。
         *
         * 设计目的:把 controller 改为可在悬浮窗生命周期内独立存活的单例,
         * Activity 与悬浮窗 Service 共用同一实例,任一方销毁都不会 stop 传输层,
         * 只有显式调用 [release] 时才停止。
         *
         * @param context 应用上下文(内部取 applicationContext 避免泄漏 Activity)
         * @param nickname 本机昵称(仅首次创建时使用)
         */
        @Synchronized
        fun ensureStarted(context: Context, nickname: String): MesChatController {
            instance?.let { return it }
            val ctrl = MesChatController(context.applicationContext, nickname)
            ctrl.start()
            instance = ctrl
            return ctrl
        }

        /** 停止传输层并清空单例。仅在确认不再需要 Mesh 聊天时调用(如悬浮窗关闭) */
        @Synchronized
        fun release() {
            instance?.stop()
            instance = null
        }
    }

    // ═══ 传输层 ═══
    // BLE 回调
    private val bleCallback = object : BleMeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String) {
            onPeerConnectedInternal(peerId, nickname, TransportType.BLE)
        }
        override fun onPeerDisconnected(peerId: String) {
            onPeerDisconnectedInternal(peerId, TransportType.BLE)
        }
        override fun onDataReceived(json: String, fromPeerId: String) {
            onDataReceivedInternal(json, fromPeerId)
        }
        override fun onError(error: String) {
            errorEvent.value = error
        }
    }

    // Wi-Fi Aware 回调
    private val wifiCallback = object : BleMeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String) {
            onPeerConnectedInternal(peerId, nickname, TransportType.WIFI_AWARE)
        }
        override fun onPeerDisconnected(peerId: String) {
            onPeerDisconnectedInternal(peerId, TransportType.WIFI_AWARE)
        }
        override fun onDataReceived(json: String, fromPeerId: String) {
            onDataReceivedInternal(json, fromPeerId)
        }
        override fun onError(error: String) {
            // Wi-Fi Aware 错误不覆盖 BLE,仅记录
            Log.w(TAG, "Wi-Fi Aware error: $error")
        }
    }

    // UDP 局域网回调
    private val udpCallback = object : BleMeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String) {
            onPeerConnectedInternal(peerId, nickname, TransportType.UDP)
        }
        override fun onPeerDisconnected(peerId: String) {
            onPeerDisconnectedInternal(peerId, TransportType.UDP)
        }
        override fun onDataReceived(json: String, fromPeerId: String) {
            onDataReceivedInternal(json, fromPeerId)
        }
        override fun onError(error: String) {
            Log.w(TAG, "UDP error: $error")
        }
    }

    // Nostr 云端回调
    private val nostrCallback = object : BleMeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String) {
            onPeerConnectedInternal(peerId, nickname, TransportType.NOSTR)
        }
        override fun onPeerDisconnected(peerId: String) {
            onPeerDisconnectedInternal(peerId, TransportType.NOSTR)
        }
        override fun onDataReceived(json: String, fromPeerId: String) {
            onDataReceivedInternal(json, fromPeerId)
        }
        override fun onError(error: String) {
            Log.w(TAG, "Nostr error: $error")
        }
    }

    private val bleTransport = BleMeshTransport(context, bleCallback)
    private val wifiTransport = WifiAwareTransport(context, bleTransport.myPeerId, wifiCallback)
    private val udpTransport = UdpLanTransport(context, bleTransport.myPeerId, udpCallback)
    private val nostrTransport = NostrTransport(context, nostrCallback)
    val myPeerId: String get() = bleTransport.myPeerId

    /** peerId -> 活跃传输通道集合(一个 peer 可能同时通过 BLE 和 Wi-Fi Aware 连接) */
    private val peerTransportMap = mutableMapOf<String, MutableSet<TransportType>>()

    /** 统一处理 peer 连接 */
    private fun onPeerConnectedInternal(peerId: String, nickname: String, transport: TransportType) {
        peerNicknames[peerId] = nickname
        val set = peerTransportMap.getOrPut(peerId) { mutableSetOf() }
        set.add(transport)
        // peer 显示的传输通道:优先 UDP(同 Wi-Fi 带宽最大延迟最低),其次 Wi-Fi Aware,最后 BLE
        val displayTransport = when {
            TransportType.UDP in set -> TransportType.UDP
            TransportType.WIFI_AWARE in set -> TransportType.WIFI_AWARE
            TransportType.BLE in set -> TransportType.BLE
            else -> TransportType.NONE
        }
        val peer = MeshPeer(
            peerId = peerId,
            nickname = nickname,
            transport = displayTransport,
            lastSeen = System.currentTimeMillis()
        )
        peers.removeAll { it.peerId == peerId }
        peers.add(peer)
        updateTransportStatus()
        sendAnnounce(peerId)
    }

    /** 统一处理 peer 断开 */
    private fun onPeerDisconnectedInternal(peerId: String, transport: TransportType) {
        val set = peerTransportMap[peerId]
        if (set == null) return
        set.remove(transport)
        if (set.isNotEmpty()) {
            // 仍有其他通道连接,只更新显示的传输通道
            val displayTransport = when {
                TransportType.UDP in set -> TransportType.UDP
                TransportType.WIFI_AWARE in set -> TransportType.WIFI_AWARE
                TransportType.BLE in set -> TransportType.BLE
                else -> TransportType.NONE
            }
            peers.forEachIndexed { idx, peer ->
                if (peer.peerId == peerId) {
                    peers[idx] = peer.copy(transport = displayTransport)
                }
            }
            updateTransportStatus()
            return
        }
        // 所有通道都断开,彻底移除
        peerTransportMap.remove(peerId)
        peers.removeAll { it.peerId == peerId }
        peerNicknames.remove(peerId)
        updateTransportStatus()
        conversations.forEachIndexed { idx, conv ->
            if (!conv.isChannel && conv.id == peerId) {
                conversations[idx] = conv.copy(transport = TransportType.NONE)
            }
        }
    }

    /** 统一处理数据接收 */
    private fun onDataReceivedInternal(json: String, fromPeerId: String) {
        try {
            val obj = JSONObject(json)
            val type = obj.optString("_type", "message")
            when (type) {
                "announce" -> handleAnnounce(obj, fromPeerId)
                "leave" -> handleLeave(obj, fromPeerId)
                "fragment" -> handleFragment(obj, fromPeerId)
                else -> handleChatMessage(obj, fromPeerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDataReceived parse error", e)
        }
    }

    /**
     * 处理分片消息:收集分片,收齐后拼接为完整 JSON 并递归处理。
     * 每次收到分片时顺带清理超时未收齐的分片组,防止内存泄漏。
     */
    private fun handleFragment(obj: JSONObject, fromPeerId: String) {
        val fragId = obj.optString("fragId")
        val total = obj.optInt("total")
        val index = obj.optInt("index")
        val chunk = obj.optString("chunk")
        if (fragId.isBlank() || total <= 0 || index < 0 || index >= total) return

        // 顺带清理超时分片(每次收分片时执行,无需独立定时器)
        cleanupFragmentBuffers()

        val buffer = fragmentBuffers.getOrPut(fragId) { FragmentBuffer(total) }
        synchronized(buffer) {
            if (buffer.chunks[index] == null) {
                buffer.chunks[index] = chunk
                buffer.received++
            }
            if (buffer.received < total) return

            // 所有分片收齐,拼接完整 JSON
            fragmentBuffers.remove(fragId)
            val completeJson = buffer.chunks.filterNotNull().joinToString("")
            onDataReceivedInternal(completeJson, fromPeerId)
        }
    }

    /** 清理超时未收齐的分片组(在 heartbeat 或定期任务中调用) */
    private fun cleanupFragmentBuffers() {
        val now = System.currentTimeMillis()
        fragmentBuffers.entries.removeAll { now - it.value.firstReceived > FRAGMENT_TIMEOUT_MS }
    }

    // ═══ Compose 可观察状态 ═══

    /** 会话列表 */
    val conversations = mutableStateListOf<ChatConversation>()

    /** 当前打开的会话 ID(null=会话列表页) */
    val currentConversationId = mutableStateOf<String?>(null)

    /** 每个会话的消息:conversationId -> messages */
    val messagesByConversation = mutableStateListOf<Pair<String, MutableList<MesMessage>>>()

    /** 传输状态:ble/wifiAware 字段反映实际启动的传输通道(而非是否有 peer 在线) */
    val transportStatus = mutableStateOf(TransportStatus(ble = true))

    /** BLE 通道是否已成功启动(独立于 peer 是否在线,用于自动降级判断与状态展示) */
    private val nostrStarted = mutableStateOf(false)
    private val bleStarted = mutableStateOf(false)

    /** Wi-Fi Aware 通道是否已成功启动 */
    private val wifiStarted = mutableStateOf(false)

    /** UDP 局域网通道是否已成功启动 */
    private val udpStarted = mutableStateOf(false)

    /** 当前聊天方式(用户手动选择),默认局域网 */
    val currentMode = mutableStateOf(ChatMode.LOCAL)

    /** 已连接的 Mesh 节点 */
    val peers = mutableStateListOf<MeshPeer>()

    /** 输入框文本 */
    val inputText = mutableStateOf("")

    /** 错误提示(一次性) */
    val errorEvent = mutableStateOf<String?>(null)

    // ═══ 内部状态 ═══
    /** 已加入的频道集合 */
    private val joinedChannels = mutableSetOf<String>()

    /** peerId -> nickname 映射 */
    private val peerNicknames = mutableMapOf<String, String>()

    // ═══ 分片重组缓冲区 ═══
    private data class FragmentBuffer(
        val total: Int,
        val chunks: Array<String?> = arrayOfNulls(total),
        var received: Int = 0,
        val firstReceived: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
    private val fragmentBuffers = java.util.concurrent.ConcurrentHashMap<String, FragmentBuffer>()

    /** 分片发送用的共享线程池(避免每次新建 Thread) */
    private val fragmentExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "meschat-fragment-sender").apply { isDaemon = true }
    }

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    /**
     * 启动传输层。
     *
     * 默认仅启动 UDP 局域网通道(无需任何运行时权限,避免审核风险),
     * BLE 与 Wi-Fi Aware 作为可选增强传输,由用户在节点面板主动开启
     * ([enableExtraTransports]),开启时才请求蓝牙/位置权限。
     */
    fun start() {
        // 加入默认频道(用于接收该频道的广播消息),但不自动打开会话
        joinChannel(DEFAULT_CHANNEL)

        // 默认仅启动 UDP(仅需 INTERNET/网络状态等普通权限)
        val udpOk = udpTransport.start()
        udpStarted.value = udpOk
        Log.i(TAG, "UDP transport started=$udpOk")

        if (!udpOk) {
            errorEvent.value = "局域网通道不可用,请确认已连接 Wi-Fi"
        }

        updateTransportStatus()
    }

    /**
     * 切换聊天方式:先关闭所有传输,再启动目标方式对应的传输。
     *
     * - LOCAL: 仅 UDP
     * - BLE: 仅 BLE Mesh(需要蓝牙/位置权限,调用方先申请)
     * - WIFI_AWARE: 仅 Wi-Fi Aware(需要位置权限,调用方先申请)
     * - CLOUD: Nostr relay
     *
     * @param mode 目标聊天方式
     * @return 是否成功启动
     */
    fun switchChatMode(mode: ChatMode): Boolean {
        // 先关闭所有传输
        bleTransport.stop()
        wifiTransport.stop()
        nostrTransport.stop()
        udpTransport.stop()
        bleStarted.value = false
        wifiStarted.value = false
        nostrStarted.value = false
        udpStarted.value = false

        // 清理所有 peer(切换方式后旧的 peer 不再可达)
        peers.clear()
        peerNicknames.clear()
        peerTransportMap.clear()
        // 清理分片缓冲
        fragmentBuffers.clear()

        val ok = when (mode) {
            ChatMode.LOCAL -> {
                val udpOk = udpTransport.start()
                udpStarted.value = udpOk
                if (!udpOk) errorEvent.value = "局域网通道不可用,请确认已连接 Wi-Fi"
                udpOk
            }
            ChatMode.BLE -> {
                val bleOk = bleTransport.start()
                bleStarted.value = bleOk
                if (!bleOk) errorEvent.value = "蓝牙不可用,请检查蓝牙是否开启"
                bleOk
            }
            ChatMode.WIFI_AWARE -> {
                val wifiOk = wifiTransport.start()
                wifiStarted.value = wifiOk
                if (!wifiOk) errorEvent.value = "Wi-Fi Aware 不可用,请检查 Wi-Fi 是否开启"
                wifiOk
            }
            ChatMode.CLOUD -> {
                val nostrOk = nostrTransport.start()
                nostrStarted.value = nostrOk
                if (nostrOk) {
                    synchronized(joinedChannels) {
                        joinedChannels.forEach { nostrTransport.joinChannel(it) }
                    }
                } else {
                    errorEvent.value = "云端通道启动失败,请检查网络连接"
                }
                nostrOk
            }
        }

        currentMode.value = mode
        Log.i(TAG, "switchChatMode → $mode, success=$ok")
        updateTransportStatus()
        return ok
    }

    /**
     * 当前模式下允许发送的最大文件大小(字节)。
     * - LOCAL(UDP): 2MB(分片传输)
     * - BLE: 2MB(BLE 带宽有限但分片可传)
     * - WIFI_AWARE: 20MB(Wi-Fi Aware 带宽高,可传大文件)
     * - CLOUD(Nostr): 50KB(relay 事件大小限制)
     */
    val maxFileSizeBytes: Int get() = when (currentMode.value) {
        ChatMode.LOCAL -> 2 * 1024 * 1024
        ChatMode.BLE -> 2 * 1024 * 1024
        ChatMode.WIFI_AWARE -> 20 * 1024 * 1024
        ChatMode.CLOUD -> 50 * 1024
    }

    fun stop() {
        bleTransport.stop()
        wifiTransport.stop()
        udpTransport.stop()
        nostrTransport.stop()
        bleStarted.value = false
        wifiStarted.value = false
        udpStarted.value = false
        nostrStarted.value = false
        fragmentExecutor.shutdownNow()
        fragmentBuffers.clear()
        updateTransportStatus()
    }

    // ════════════════════════════════════════
    //  消息处理
    // ════════════════════════════════════════

    private fun handleChatMessage(obj: JSONObject, fromPeerId: String) {
        val msgId = obj.optString("id", UUID.randomUUID().toString())
        val sender = obj.optString("sender", "未知")
        val senderPeerId = obj.optString("senderPeerId", fromPeerId)
        val content = obj.optString("content", "")
        val typeStr = obj.optString("type", "TEXT")
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val channel = obj.optString("channel", "").ifBlank { null }
        val isPrivate = obj.optBoolean("isPrivate", false)
        val isRelay = obj.optBoolean("isRelay", false)
        val originalSender = obj.optString("originalSender", "").ifBlank { null }
        val recipientPeerId = obj.optString("recipientPeerId", "").ifBlank { null }

        // Bug G 修复:私聊消息校验收件人,只有收件人是我才处理,否则丢弃(避免误广播泄露)
        if (isPrivate && recipientPeerId != null && recipientPeerId != myPeerId) {
            Log.d(TAG, "Ignore private message not for me (recipient=$recipientPeerId)")
            return
        }

        // 忽略自己回环的消息(transport 不应回环,但防御性处理)
        if (senderPeerId == myPeerId) {
            return
        }

        // Bug H 修复:频道消息仅当已加入该频道时才显示,符合 IRC 行为
        if (!isPrivate && channel != null && channel !in joinedChannels) {
            Log.d(TAG, "Ignore channel message from unjoined channel: #$channel")
            return
        }

        val msgType = runCatching { MesMessageType.valueOf(typeStr) }
            .getOrDefault(MesMessageType.TEXT)

        // 解析附件数据(base64 → 保存临时文件)
        var imagePath: String? = null
        var voicePath: String? = null
        var voiceDuration = 0
        var fileName: String? = null
        var fileSize: Long = 0
        var filePath: String? = null

        when (msgType) {
            MesMessageType.IMAGE -> {
                val imageData = obj.optString("imageData", "")
                if (imageData.isNotEmpty()) {
                    val bytes = android.util.Base64.decode(imageData, android.util.Base64.NO_WRAP)
                    imagePath = saveTempFile(bytes, "img", "jpg")
                    Log.d(TAG, "Received image: ${bytes.size}B saved to $imagePath")
                }
            }
            MesMessageType.VOICE -> {
                val voiceData = obj.optString("voiceData", "")
                voiceDuration = obj.optInt("voiceDuration", 0)
                if (voiceData.isNotEmpty()) {
                    val bytes = android.util.Base64.decode(voiceData, android.util.Base64.NO_WRAP)
                    voicePath = saveTempFile(bytes, "voice", "m4a")
                    Log.d(TAG, "Received voice: ${bytes.size}B, ${voiceDuration}s saved to $voicePath")
                }
            }
            MesMessageType.FILE -> {
                fileName = obj.optString("fileName", "未知文件")
                fileSize = obj.optLong("fileSize", 0)
                val fileData = obj.optString("fileData", "")
                if (fileData.isNotEmpty()) {
                    val ext = fileName!!.substringAfterLast('.', "bin")
                    val bytes = android.util.Base64.decode(fileData, android.util.Base64.NO_WRAP)
                    filePath = saveTempFile(bytes, "file", ext)
                    Log.d(TAG, "Received file: $fileName ${bytes.size}B")
                }
            }
            else -> {}
        }

        val msg = MesMessage(
            id = msgId,
            sender = sender,
            senderPeerId = senderPeerId,
            content = content,
            type = msgType,
            timestamp = timestamp,
            isMine = false,
            isRelay = isRelay,
            originalSender = originalSender,
            channel = channel,
            isPrivate = isPrivate,
            imagePath = imagePath,
            voicePath = voicePath,
            voiceDuration = voiceDuration,
            fileName = fileName,
            fileSize = fileSize,
            filePath = filePath,
            deliveryState = DeliveryState.DELIVERED
        )

        // 确定 conversationId
        val convId = if (isPrivate) {
            // 私聊:用发送者的 peerId 作为会话 ID
            senderPeerId
        } else {
            // 频道:#channel
            "#${channel ?: DEFAULT_CHANNEL}"
        }

        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, content, timestamp, !isPrivate)
    }

    private fun handleAnnounce(obj: JSONObject, fromPeerId: String) {
        val nickname = obj.optString("nickname", "")
        val channels = obj.optJSONArray("channels")
        if (nickname.isNotBlank()) {
            peerNicknames[fromPeerId] = nickname
            peers.forEachIndexed { idx, peer ->
                if (peer.peerId == fromPeerId) {
                    peers[idx] = peer.copy(nickname = nickname)
                }
            }
        }
    }

    private fun handleLeave(obj: JSONObject, fromPeerId: String) {
        // peer 离开,从列表移除
        peers.removeAll { it.peerId == fromPeerId }
        updateTransportStatus()
    }

    // ════════════════════════════════════════
    //  发送消息
    // ════════════════════════════════════════

    /**
     * 发送文本消息到当前会话。
     * 如果是 / 开头,先尝试解析为 IRC 命令。
     */
    fun sendText() {
        val text = inputText.value.trim()
        if (text.isEmpty()) return

        // IRC 命令处理
        if (text.startsWith("/")) {
            val handled = handleIrcCommand(text)
            if (handled) {
                inputText.value = ""
                return
            }
        }

        val convId = currentConversationId.value
        if (convId == null) return

        val conv = conversations.find { it.id == convId }
        val isChannel = conv?.isChannel == true
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel) convId else null

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = text,
            type = MesMessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = !isChannel,
            deliveryState = DeliveryState.SENDING
        )

        // 构造 JSON
        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            channel?.let { put("channel", it) }
            put("isPrivate", msg.isPrivate)
            targetPeerId?.let { put("recipientPeerId", it) }
        }.toString()

        // 发送
        if (isChannel) {
            // 频道广播:三通道同时发送,覆盖更多 peer
            bleTransport.sendMessage(json, targetPeerId = null)
            wifiTransport.sendMessage(json, targetPeerId = null)
            udpTransport.sendMessage(json, targetPeerId = null)
        } else {
            // 私聊单播:走对端所在通道;若未知则三通道都发
            val set = peerTransportMap[targetPeerId]
            if (set == null || set.isEmpty()) {
                bleTransport.sendMessage(json, targetPeerId = targetPeerId)
                wifiTransport.sendMessage(json, targetPeerId = targetPeerId)
                udpTransport.sendMessage(json, targetPeerId = targetPeerId)
            } else {
                if (TransportType.BLE in set) bleTransport.sendMessage(json, targetPeerId = targetPeerId)
                if (TransportType.WIFI_AWARE in set) wifiTransport.sendMessage(json, targetPeerId = targetPeerId)
                if (TransportType.UDP in set) udpTransport.sendMessage(json, targetPeerId = targetPeerId)
            }
        }

        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, text, msg.timestamp, isChannel)
        inputText.value = ""
    }

    /** 发送 announce(将自己的昵称和频道信息通知对端) */
    private fun sendAnnounce(targetPeerId: String? = null) {
        val json = JSONObject().apply {
            put("_type", "announce")
            put("nickname", myNickname)
            put("channels", org.json.JSONArray(joinedChannels.toList()))
        }.toString()
        if (bleStarted.value) bleTransport.sendMessage(json, targetPeerId)
        if (wifiStarted.value) wifiTransport.sendMessage(json, targetPeerId)
        if (udpStarted.value) udpTransport.sendMessage(json, targetPeerId)
        if (nostrStarted.value) nostrTransport.sendMessage(json, targetPeerId)
    }

    // ════════════════════════════════════════
    //  分片传输 + 附件发送
    // ════════════════════════════════════════

    /** 向所有已启动的传输通道发送(私聊走对端通道) */
    private fun sendToAllTransports(json: String, targetPeerId: String?) {
        if (targetPeerId == null) {
            // 频道广播:所有已启动的通道都发
            if (bleStarted.value) bleTransport.sendMessage(json, null)
            if (wifiStarted.value) wifiTransport.sendMessage(json, null)
            if (udpStarted.value) udpTransport.sendMessage(json, null)
            if (nostrStarted.value) nostrTransport.sendMessage(json, null)
        } else {
            val set = peerTransportMap[targetPeerId]
            if (set.isNullOrEmpty()) {
                // 未知对端通道,所有通道都发
                if (bleStarted.value) bleTransport.sendMessage(json, targetPeerId)
                if (wifiStarted.value) wifiTransport.sendMessage(json, targetPeerId)
                if (udpStarted.value) udpTransport.sendMessage(json, targetPeerId)
                if (nostrStarted.value) nostrTransport.sendMessage(json, targetPeerId)
            } else {
                if (TransportType.BLE in set) bleTransport.sendMessage(json, targetPeerId)
                if (TransportType.WIFI_AWARE in set) wifiTransport.sendMessage(json, targetPeerId)
                if (TransportType.UDP in set) udpTransport.sendMessage(json, targetPeerId)
                if (TransportType.NOSTR in set) nostrTransport.sendMessage(json, targetPeerId)
            }
        }
    }

    /**
     * 分片发送大消息(图片/语音/文件的 base64 数据)。
     * 小消息(≤ FRAGMENT_SIZE)直接发送,大消息自动分片。
     * 分片间加 20ms 延迟避免 UDP 拥塞。
     */
    fun sendFragmented(json: String, targetPeerId: String?) {
        val data = json.toByteArray(Charsets.UTF_8)
        if (data.size <= FRAGMENT_SIZE) {
            sendToAllTransports(json, targetPeerId)
            return
        }
        val total = (data.size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE
        val fragId = UUID.randomUUID().toString()
        Log.i(TAG, "sendFragmented: ${data.size}B → $total fragments, fragId=$fragId")
        fragmentExecutor.execute {
            for (i in 0 until total) {
                val start = i * FRAGMENT_SIZE
                val end = minOf(start + FRAGMENT_SIZE, data.size)
                val chunk = String(data, start, end - start, Charsets.UTF_8)
                val fragJson = JSONObject().apply {
                    put("_type", "fragment")
                    put("fragId", fragId)
                    put("total", total)
                    put("index", i)
                    put("chunk", chunk)
                }.toString()
                sendToAllTransports(fragJson, targetPeerId)
                if (i < total - 1) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /**
     * 发送图片消息。
     * @param imageBytes 图片字节数据(已压缩)
     */
    fun sendImage(imageBytes: ByteArray) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel) convId else null

        val localPath = saveTempFile(imageBytes, "img", "jpg")
        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = "[图片]",
            type = MesMessageType.IMAGE,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = !isChannel,
            imagePath = localPath,
            deliveryState = DeliveryState.SENDING
        )

        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            channel?.let { put("channel", it) }
            put("isPrivate", msg.isPrivate)
            targetPeerId?.let { put("recipientPeerId", it) }
            put("imageData", base64)
        }.toString()

        sendFragmented(json, targetPeerId)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[图片]", msg.timestamp, isChannel)
    }

    /**
     * 发送语音消息。
     * @param voiceBytes 语音文件字节数据
     * @param durationSec 语音时长(秒)
     */
    fun sendVoice(voiceBytes: ByteArray, durationSec: Int) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel) convId else null

        val localPath = saveTempFile(voiceBytes, "voice", "m4a")
        val base64 = android.util.Base64.encodeToString(voiceBytes, android.util.Base64.NO_WRAP)

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = "[语音]",
            type = MesMessageType.VOICE,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = !isChannel,
            voicePath = localPath,
            voiceDuration = durationSec,
            deliveryState = DeliveryState.SENDING
        )

        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            channel?.let { put("channel", it) }
            put("isPrivate", msg.isPrivate)
            targetPeerId?.let { put("recipientPeerId", it) }
            put("voiceData", base64)
            put("voiceDuration", durationSec)
        }.toString()

        sendFragmented(json, targetPeerId)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[语音]", msg.timestamp, isChannel)
    }

    /**
     * 发送文件消息。
     * @param fileName 文件名
     * @param fileBytes 文件字节数据
     */
    fun sendFile(fileName: String, fileBytes: ByteArray) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel) convId else null

        val ext = fileName.substringAfterLast('.', "bin")
        val localPath = saveTempFile(fileBytes, "file", ext)
        val base64 = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = "[文件] $fileName",
            type = MesMessageType.FILE,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = !isChannel,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            filePath = localPath,
            deliveryState = DeliveryState.SENDING
        )

        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            channel?.let { put("channel", it) }
            put("isPrivate", msg.isPrivate)
            targetPeerId?.let { put("recipientPeerId", it) }
            put("fileName", fileName)
            put("fileSize", fileBytes.size.toLong())
            put("fileData", base64)
        }.toString()

        sendFragmented(json, targetPeerId)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[文件] $fileName", msg.timestamp, isChannel)
    }

    /** 保存附件到临时文件,返回绝对路径 */
    private fun saveTempFile(bytes: ByteArray, prefix: String, ext: String): String {
        val dir = java.io.File(context.cacheDir, "meschat_attachments")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // ════════════════════════════════════════
    //  IRC 命令处理
    // ════════════════════════════════════════

    private fun handleIrcCommand(text: String): Boolean {
        val parsed = parseIrcCommand(text) ?: return false
        val (cmd, args) = parsed
        when (cmd) {
            "join" -> {
                if (args.isNotEmpty()) {
                    val ch = args[0].removePrefix("#")
                    joinChannel(ch)
                    // 通知对端
                    sendAnnounce()
                }
                return true
            }
            "leave", "part" -> {
                val ch = args.firstOrNull()?.removePrefix("#") ?: currentConversationId.value?.removePrefix("#")
                if (ch != null) {
                    leaveChannel(ch)
                }
                return true
            }
            "msg" -> {
                if (args.size >= 2) {
                    val target = args[0]
                    val message = args.drop(1).joinToString(" ")
                    startPrivateChat(target)
                    sendPrivateMessage(target, message)
                }
                return true
            }
            "nick" -> {
                if (args.isNotEmpty()) {
                    myNickname = args[0]
                    sendAnnounce()
                    // 更新自己的消息显示
                }
                return true
            }
            "who" -> {
                val peerList = peers.joinToString("\n") { "- ${it.nickname} (${it.peerId}) [${it.transport.label}]" }
                val convId = currentConversationId.value ?: "#$DEFAULT_CHANNEL"
                val systemMsg = MesMessage(
                    id = UUID.randomUUID().toString(),
                    sender = "系统",
                    content = "在线节点(${peers.size}):\n$peerList",
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    channel = convId.removePrefix("#")
                )
                addMessageToConversation(convId, systemMsg)
                return true
            }
            "me" -> {
                val action = args.joinToString(" ")
                val convId = currentConversationId.value ?: return false
                val conv = conversations.find { it.id == convId }
                val isChannel = conv?.isChannel == true
                val msg = MesMessage(
                    id = UUID.randomUUID().toString(),
                    sender = myNickname,
                    senderPeerId = myPeerId,
                    content = "* $myNickname $action",
                    type = MesMessageType.TEXT,
                    timestamp = System.currentTimeMillis(),
                    isMine = true,
                    channel = if (isChannel) convId.removePrefix("#") else null,
                    isPrivate = !isChannel,
                    deliveryState = DeliveryState.SENDING
                )
                val json = JSONObject().apply {
                    put("id", msg.id)
                    put("sender", msg.sender)
                    put("senderPeerId", msg.senderPeerId)
                    put("content", msg.content)
                    put("type", msg.type.name)
                    put("timestamp", msg.timestamp)
                    if (isChannel) put("channel", convId.removePrefix("#"))
                    put("isPrivate", msg.isPrivate)
                }.toString()
                bleTransport.sendMessage(json, if (isChannel) null else convId)
                wifiTransport.sendMessage(json, if (isChannel) null else convId)
                udpTransport.sendMessage(json, if (isChannel) null else convId)
                addMessageToConversation(convId, msg)
                return true
            }
            "help" -> {
                val convId = currentConversationId.value ?: "#$DEFAULT_CHANNEL"
                val helpText = IrcCommand.entries.joinToString("\n") { "${it.usage} — ${it.description}" }
                val systemMsg = MesMessage(
                    id = UUID.randomUUID().toString(),
                    sender = "系统",
                    content = helpText,
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    channel = convId.removePrefix("#")
                )
                addMessageToConversation(convId, systemMsg)
                return true
            }
            "mute" -> {
                // 简化:暂不实现静音
                errorEvent.value = "静音功能暂未实现"
                return true
            }
        }
        return false
    }

    // ════════════════════════════════════════
    //  会话管理
    // ════════════════════════════════════════

    /** 加入/创建频道 */
    fun joinChannel(channel: String, password: String? = null) {
        val convId = "#$channel"
        if (conversations.none { it.id == convId }) {
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = ConversationType.CHANNEL,
                    title = channel,
                    isPasswordProtected = password != null,
                    transport = TransportType.NONE
                )
            )
            messagesByConversation.add(convId to mutableListOf())
        }
        joinedChannels.add(channel)
        // Nostr 云端订阅该频道
        if (nostrStarted.value) nostrTransport.joinChannel(channel)
    }

    /** 离开频道 */
    fun leaveChannel(channel: String) {
        val convId = "#$channel"
        conversations.removeAll { it.id == convId }
        messagesByConversation.removeAll { it.first == convId }
        joinedChannels.remove(channel)
        if (nostrStarted.value) nostrTransport.leaveChannel(channel)
        if (currentConversationId.value == convId) {
            currentConversationId.value = null
        }
    }

    /** 发起私聊 */
    fun startPrivateChat(peerId: String) {
        val nickname = peerNicknames[peerId] ?: peerId
        val convId = peerId
        if (conversations.none { it.id == convId }) {
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = ConversationType.PRIVATE,
                    title = nickname,
                    transport = peerTransportMap[peerId]?.firstOrNull() ?: TransportType.NONE
                )
            )
            messagesByConversation.add(convId to mutableListOf())
        }
        currentConversationId.value = convId
    }

    /** 打开会话 */
    fun openConversation(convId: String) {
        currentConversationId.value = convId
    }

    /** 返回会话列表 */
    fun backToList() {
        currentConversationId.value = null
    }

    /** 发送私聊消息 */
    private fun sendPrivateMessage(targetPeerId: String, content: String) {
        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = content,
            type = MesMessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            isPrivate = true,
            deliveryState = DeliveryState.SENDING
        )
        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            put("isPrivate", true)
            put("recipientPeerId", targetPeerId)
        }.toString()
        bleTransport.sendMessage(json, targetPeerId)
        wifiTransport.sendMessage(json, targetPeerId)
        udpTransport.sendMessage(json, targetPeerId)
        addMessageToConversation(targetPeerId, msg)
        updateConversationPreview(targetPeerId, content, msg.timestamp, false)
    }

    // ════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════

    /** 添加消息到指定会话 */
    private fun addMessageToConversation(convId: String, msg: MesMessage) {
        val pair = messagesByConversation.find { it.first == convId }
        if (pair != null) {
            pair.second.add(msg)
            // 超出上限时裁剪旧消息(防止内存无限增长)
            if (pair.second.size > MAX_MESSAGES_PER_CONVERSATION) {
                pair.second.subList(0, pair.second.size - MAX_MESSAGES_PER_CONVERSATION).clear()
            }
            // 触发 Compose 重组:替换 list 引用
            val idx = messagesByConversation.indexOf(pair)
            messagesByConversation[idx] = convId to pair.second.toMutableList()
        } else {
            // 会话不存在,创建
            val isChannel = convId.startsWith("#")
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = if (isChannel) ConversationType.CHANNEL else ConversationType.PRIVATE,
                    title = convId.removePrefix("#"),
                    transport = TransportType.NONE
                )
            )
            messagesByConversation.add(convId to mutableListOf(msg))
        }
    }

    /** 更新会话预览 */
    private fun updateConversationPreview(convId: String, lastMsg: String, time: Long, isChannel: Boolean) {
        val idx = conversations.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            val conv = conversations[idx]
            val unreadDelta = if (currentConversationId.value == convId) 0 else 1
            conversations[idx] = conv.copy(
                lastMessage = lastMsg,
                lastMessageTime = time,
                unreadCount = if (currentConversationId.value == convId) 0 else conv.unreadCount + unreadDelta
            )
        }
    }

    /**
     * 更新传输状态。
     *
     * ble/wifiAware 字段反映"该通道是否已成功启动"(而非是否有 peer 在线),
     * 这样 UI 可以准确展示"仅 BLE / 仅 Wi-Fi Aware / 双通道 / 不可用"四种状态。
     * peerCount 仍反映已连接的节点数。
     */
    private fun updateTransportStatus() {
        transportStatus.value = TransportStatus(
            ble = bleStarted.value,
            wifiAware = wifiStarted.value,
            udp = udpStarted.value,
            nostr = nostrStarted.value,
            peerCount = peers.size
        )
    }

    /** 获取当前会话的消息列表 */
    fun getCurrentMessages(): List<MesMessage> {
        val convId = currentConversationId.value ?: return emptyList()
        return messagesByConversation.find { it.first == convId }?.second ?: emptyList()
    }

    /** 获取当前会话 */
    fun getCurrentConversation(): ChatConversation? {
        val convId = currentConversationId.value ?: return null
        return conversations.find { it.id == convId }
    }

    /** 清除当前会话的未读数 */
    fun clearUnread(convId: String) {
        val idx = conversations.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            conversations[idx] = conversations[idx].copy(unreadCount = 0)
        }
    }

    /**
     * 获取指定频道的消息列表(供悬浮窗等不依赖 [currentConversationId] 的入口使用)。
     */
    fun getChannelMessages(channel: String): List<MesMessage> {
        val convId = "#$channel"
        return messagesByConversation.find { it.first == convId }?.second ?: emptyList()
    }

    /**
     * 直接向指定频道发送当前输入框中的文本(不依赖 [currentConversationId])。
     *
     * 供悬浮窗使用:悬浮窗只展示"#附近"频道,发送时不应改动主界面的当前会话状态,
     * 因此独立提供此方法。IRC 命令(/join /msg 等)仍只在主界面 [sendText] 中支持。
     *
     * @param channel 频道名(不带 # 前缀),如 "附近"
     */
    fun sendTextToChannel(channel: String) {
        val text = inputText.value.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            errorEvent.value = "悬浮窗内不支持 / 命令,请打开主界面使用"
            return
        }
        if (channel !in joinedChannels) {
            joinChannel(channel)
        }

        val convId = "#$channel"
        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = text,
            type = MesMessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = false,
            deliveryState = DeliveryState.SENDING
        )

        val json = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            put("channel", channel)
            put("isPrivate", false)
        }.toString()

        // 频道广播:走所有已启动的通道
        bleTransport.sendMessage(json, targetPeerId = null)
        wifiTransport.sendMessage(json, targetPeerId = null)
        udpTransport.sendMessage(json, targetPeerId = null)

        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, text, msg.timestamp, true)
        inputText.value = ""
    }
}
