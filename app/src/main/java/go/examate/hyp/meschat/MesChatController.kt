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

    private val bleTransport = BleMeshTransport(context, bleCallback)
    private val wifiTransport = WifiAwareTransport(context, bleTransport.myPeerId, wifiCallback)
    val myPeerId: String get() = bleTransport.myPeerId

    /** peerId -> 活跃传输通道集合(一个 peer 可能同时通过 BLE 和 Wi-Fi Aware 连接) */
    private val peerTransportMap = mutableMapOf<String, MutableSet<TransportType>>()

    /** 统一处理 peer 连接 */
    private fun onPeerConnectedInternal(peerId: String, nickname: String, transport: TransportType) {
        peerNicknames[peerId] = nickname
        val set = peerTransportMap.getOrPut(peerId) { mutableSetOf() }
        set.add(transport)
        // peer 显示的传输通道:优先 Wi-Fi Aware(带宽大),其次 BLE
        val displayTransport = if (TransportType.WIFI_AWARE in set) TransportType.WIFI_AWARE
            else if (TransportType.BLE in set) TransportType.BLE
            else TransportType.NONE
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
            val displayTransport = if (TransportType.WIFI_AWARE in set) TransportType.WIFI_AWARE
                else if (TransportType.BLE in set) TransportType.BLE
                else TransportType.NONE
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
                else -> handleChatMessage(obj, fromPeerId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDataReceived parse error", e)
        }
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
    private val bleStarted = mutableStateOf(false)

    /** Wi-Fi Aware 通道是否已成功启动 */
    private val wifiStarted = mutableStateOf(false)

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

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    /**
     * 启动传输层。
     *
     * 自动降级策略(任务B):
     * - 分别检查 BLE / Wi-Fi Aware 可用性,各自独立启动,互不影响
     * - 任一通道预检失败都不弹错误(由 transport 层静默返回 false)
     * - 仅当两个通道都启动失败时,才向用户提示"蓝牙和 Wi-Fi 均不可用"
     * - 不再自动打开附近会话,默认显示会话列表页(由 UI 自行决定打开哪个会话)
     */
    fun start() {
        // 加入默认频道(用于接收该频道的广播消息),但不自动打开会话
        joinChannel(DEFAULT_CHANNEL)

        // 自动降级:分别尝试启动 BLE 与 Wi-Fi Aware,预检失败不报错
        val bleOk = bleTransport.start()
        bleStarted.value = bleOk
        Log.i(TAG, "BLE transport started=$bleOk")

        val wifiOk = wifiTransport.start()
        wifiStarted.value = wifiOk
        Log.i(TAG, "Wi-Fi Aware transport started=$wifiOk")

        if (!bleOk && !wifiOk) {
            // 两个通道都不可用,给用户明确提示
            errorEvent.value = "蓝牙与 Wi-Fi 均不可用,无法收发附近消息。请开启蓝牙或 Wi-Fi 后重试"
        }

        updateTransportStatus()
    }

    fun stop() {
        bleTransport.stop()
        wifiTransport.stop()
        bleStarted.value = false
        wifiStarted.value = false
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
            // 频道广播:双通道同时发送,覆盖更多 peer
            bleTransport.sendMessage(json, targetPeerId = null)
            wifiTransport.sendMessage(json, targetPeerId = null)
        } else {
            // 私聊单播:走对端所在通道;若未知则双通道都发
            val set = peerTransportMap[targetPeerId]
            if (set == null || set.isEmpty()) {
                bleTransport.sendMessage(json, targetPeerId = targetPeerId)
                wifiTransport.sendMessage(json, targetPeerId = targetPeerId)
            } else {
                if (TransportType.BLE in set) bleTransport.sendMessage(json, targetPeerId = targetPeerId)
                if (TransportType.WIFI_AWARE in set) wifiTransport.sendMessage(json, targetPeerId = targetPeerId)
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
        bleTransport.sendMessage(json, targetPeerId)
        wifiTransport.sendMessage(json, targetPeerId)
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
    }

    /** 离开频道 */
    fun leaveChannel(channel: String) {
        val convId = "#$channel"
        conversations.removeAll { it.id == convId }
        messagesByConversation.removeAll { it.first == convId }
        joinedChannels.remove(channel)
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
            nostr = false,
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

        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, text, msg.timestamp, true)
        inputText.value = ""
    }
}
