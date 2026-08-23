package go.examate.hyp.meschat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Mesh 聊天控制器 — 连接传输层与 UI 层(重构后:局域网 UDP 直连 + Nostr 中继)。
 *
 * 职责:
 * 1. 持有 [UdpLanTransport] 与 [NostrTransport],统一双通道收发
 * 2. **自动路由**:同一局域网时走 UDP 直连,不可行时走 Nostr 中继兜底;
 *    对单个 peer 按其可达通道选择最优路径
 * 3. 管理中继服务器列表(内置 3 个稳定中继 + 自定义,持久化)
 * 4. 管理会话列表([ChatConversation])与每会话消息([MesMessage])
 * 5. 频道群聊、直接私聊、密钥匹对私聊(配对密钥加密)
 * 6. 维护在线用户列表(本地网状节点 + 云端用户)与传输状态([TransportStatus])
 * 7. 处理 IRC 命令(/join /msg /nick 等)
 *
 * @param context 应用上下文
 * @param myNickname 本机昵称
 */
class MesChatController(
    private val context: Context,
    private val initialNickname: String
) {

    /**
     * 本机昵称:实时读取项目内用户设置(用户设置页改名后即时生效),
     * 设置名为空时回退创建时传入的昵称,再回退"匿名用户"。
     */
    val myNickname: String
        get() {
            val stored = runCatching {
                go.examate.hyp.config.PrefsManager.getInstance(context).nickname
            }.getOrNull()
            if (!stored.isNullOrBlank()) return stored
            return initialNickname.ifBlank { "匿名用户" }
        }
    companion object {
        private const val TAG = "MesChatController"
        /** 默认频道:进入 Mesh 聊天即加入的附近群聊 */
        private const val DEFAULT_CHANNEL = "附近"
        /** 分片大小(字节):大附件按该大小切分为多条消息,降低单条传输压力 */
        private const val FRAGMENT_SIZE = 2048
        /** 分片组超时清理(60秒未收齐的分片组丢弃) */
        private const val FRAGMENT_TIMEOUT_MS = 60_000L
        /** 每个会话最大保留消息数 */
        private const val MAX_MESSAGES_PER_CONVERSATION = 500
        /** 最近消息 ID 去重缓存上限 */
        private const val MAX_RECENT_MSG_IDS = 300
        /** 云端在线列表(presence)广播间隔 */
        private const val PRESENCE_INTERVAL_MS = 60_000L
        // ── 消息持久化 + 离线历史补拉(同步窗口 10 分钟) ──
        /** 历史补拉同步窗口(毫秒):只回放 10 分钟以内的消息 */
        private const val HISTORY_SYNC_WINDOW_MS = 10 * 60_000L
        /** 设备间时钟偏差容忍(毫秒):窗口校验时放宽 ±5 分钟 */
        private const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60_000L
        /** 历史补拉请求的最小间隔(毫秒) */
        private const val HISTORY_REQUEST_MIN_INTERVAL_MS = 30_000L
        /** 同一请求方的最小应答间隔(毫秒,防刷) */
        private const val HISTORY_SERVE_MIN_INTERVAL_MS = 30_000L
        /** 单个应答块的最大字节数(发现消息回退路径也送得动) */
        private const val MAX_HIST_CHUNK_BYTES = 48 * 1024
        /** 单次补拉最多发送的应答块数(防放大) */
        private const val MAX_HIST_CHUNKS = 20
        /** 懒加载每页条数(本地历史 + 手动补拉) */
        private const val HISTORY_PAGE_SIZE = 50
        /** 自动同步每次只拉最新条数(避免突然全量拉取) */
        private const val SYNC_LATEST_COUNT = 30

        @Volatile
        private var instance: MesChatController? = null

        /** 持有者引用计数:Activity 与悬浮窗 Service 各持有一份,全部释放后才停止传输层 */
        private var refCount = 0

        /** 获取已启动的单例(若存在) */
        fun get(): MesChatController? = instance

        /**
         * 确保 controller 已启动;已存在则直接复用并引用计数 +1。
         * Activity 与悬浮窗 Service 共用同一实例,全部持有者释放(计数归零)才 stop 传输层。
         */
        @Synchronized
        fun ensureStarted(context: Context, nickname: String): MesChatController {
            val ctrl = instance ?: MesChatController(context.applicationContext, nickname).also {
                it.start()
                instance = it
            }
            refCount++
            return ctrl
        }

        /** 持有者释放:引用计数 -1,归零时停止传输层并清空单例 */
        @Synchronized
        fun release() {
            if (refCount > 0) refCount--
            if (refCount <= 0) {
                instance?.stop()
                instance = null
                refCount = 0
            }
        }
    }

    // ═══ 传输层 ═══

    val myPeerId: String = MeshIdentity.peerId(context)

    private val lanCallback = object : MeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String, via: TransportType, hopCount: Int) {
            onPeerConnectedInternal(peerId, nickname, via, hopCount)
        }
        override fun onPeerDisconnected(peerId: String, via: TransportType) {
            onPeerDisconnectedInternal(peerId, via)
        }
        override fun onDataReceived(json: String, fromPeerId: String, via: TransportType, hopCount: Int) {
            onDataReceivedInternal(json, fromPeerId, via, hopCount)
        }
        override fun onError(error: String) {
            Log.w(TAG, "LAN UDP error: " + error)
        }
    }

    private val nostrCallback = object : MeshTransport.Callback {
        override fun onPeerConnected(peerId: String, nickname: String, via: TransportType, hopCount: Int) {
            onPeerConnectedInternal(peerId, nickname, via, hopCount)
        }
        override fun onPeerDisconnected(peerId: String, via: TransportType) {
            onPeerDisconnectedInternal(peerId, via)
        }
        override fun onDataReceived(json: String, fromPeerId: String, via: TransportType, hopCount: Int) {
            onDataReceivedInternal(json, fromPeerId, via, hopCount)
        }
        override fun onError(error: String) {
            Log.w(TAG, "Nostr error: " + error)
        }
    }

    private val lanTransport = UdpLanTransport(context, myPeerId, lanCallback)
    private val nostrTransport = NostrTransport(context, nostrCallback) { connected, total ->
        transportStatus.value = transportStatus.value.copy(relayConnected = connected, relayTotal = total)
    }

    /** peerId -> 活跃传输通道集合(同一 peer 可能同时经本地与云端可达) */
    private val peerTransportMap = mutableMapOf<String, MutableSet<TransportType>>()

    /** peerId -> 本地网状跳数 */
    private val peerHops = mutableMapOf<String, Int>()

    /** peerId -> 最后活跃时间(用于云端用户过期回收;本地节点由传输层心跳管理) */
    private val peerLastSeen = mutableMapOf<String, Long>()

    /** 云端用户无任何活动超过该时长(毫秒)视为离线(presence 广播周期 60s 的 3 倍) */
    private val CLOUD_PEER_TIMEOUT_MS = 180_000L

    // ═══ Compose 可观察状态 ═══

    /** 会话列表(频道 + 私聊) */
    val conversations = mutableStateListOf<ChatConversation>()

    /** 当前打开的会话 ID(null=主页) */
    val currentConversationId = mutableStateOf<String?>(null)

    /** 每个会话的消息:conversationId -> messages */
    val messagesByConversation = mutableStateListOf<Pair<String, MutableList<MesMessage>>>()

    /** 传输状态 */
    val transportStatus = mutableStateOf(TransportStatus())

    /** 当前聊天方式,默认自动(本地优先、云端兜底) */
    val currentMode = mutableStateOf(ChatMode.AUTO)

    /** 已连接的节点(本地 + 云端) */
    val peers = mutableStateListOf<MeshPeer>()

    /** 当前启用的中继服务器列表 */
    val relays = mutableStateListOf<String>()

    /** 局域网通道是否已就绪(UDP 无需运行时权限,由 Activity 更新) */
    val awarenessGranted = mutableStateOf(false)

    /** 输入框文本(主界面) */
    val inputText = mutableStateOf("")

    /** 错误提示(一次性) */
    val errorEvent = mutableStateOf<String?>(null)

    // ═══ 内部状态 ═══

    private val lanStarted = mutableStateOf(false)
    private val nostrStarted = mutableStateOf(false)

    /** 已加入的频道集合 */
    private val joinedChannels = mutableSetOf<String>()

    /** peerId -> nickname 映射 */
    private val peerNicknames = mutableMapOf<String, String>()

    /** 密钥匹对会话:roomId -> session */
    private val keyPairSessions = mutableMapOf<String, KeyPairSession>()

    /** 密钥匹对会话 */
    private data class KeyPairSession(val roomId: String, val key: ByteArray, val title: String)

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

    /** 分片发送用的共享线程池 */
    private val fragmentExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "meschat-fragment-sender").apply { isDaemon = true }
    }

    /** presence 广播 Handler */
    private val presenceHandler = Handler(Looper.getMainLooper())
    private val presenceRunnable = object : Runnable {
        override fun run() {
            if (nostrStarted.value) sendAnnounce(null)
            if (lanStarted.value) requestHistoryForAllChannels()
            sweepCloudPeers()
            updateTransportStatus()
            presenceHandler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    /** 回收长时间无活动的云端用户(本地节点由 UDP 心跳负责) */
    private fun sweepCloudPeers() {
        val now = System.currentTimeMillis()
        val stale = peers
            .filter { it.transport == TransportType.NOSTR && now - (peerLastSeen[it.peerId] ?: now) > CLOUD_PEER_TIMEOUT_MS }
            .map { it.peerId }
        stale.forEach { id ->
            peerTransportMap.remove(id)
            peerLastSeen.remove(id)
            peerNicknames.remove(id)
            peerHops.remove(id)
            peers.removeAll { it.peerId == id }
            conversations.forEachIndexed { idx, conv ->
                if (!conv.isChannel && conv.id == id) {
                    conversations[idx] = conv.copy(transport = TransportType.NONE)
                }
            }
        }
        if (stale.isNotEmpty()) {
            Log.i(TAG, "Swept " + stale.size + " stale cloud peers")
            updateTransportStatus()
        }
    }

    // ═══ 消息持久化 + 历史补拉 ═══
    /** 每会话 JSONL 持久化存储(全量保存;同步只取 10 分钟窗口) */
    private val messageStore = MessageStore(context)
    private val lastHistoryRequestAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** "peerId:channel" -> 上次应答时间(防刷) */
    private val lastHistoryServeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** convId -> 已载入内存的最早消息时间戳(懒加载游标) */
    private val historyFrontiers = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** convId -> 本地是否还有更早历史(可观察,驱动 UI 懒加载指示) */
    val hasMoreOlder = mutableStateMapOf<String, Boolean>()

    // ═══ 消息去重 ═══
    private val recentMsgIds = java.util.ArrayDeque<String>()
    private val recentMsgIdSet = java.util.HashSet<String>()

    /** 消息 id 是否已处理过(多通道/洪泛会导致同一消息重复到达,按 id 幂等) */
    private fun isDuplicateMessage(msgId: String): Boolean {
        synchronized(recentMsgIds) {
            if (!recentMsgIdSet.add(msgId)) return true
            recentMsgIds.addLast(msgId)
            while (recentMsgIds.size > MAX_RECENT_MSG_IDS) {
                recentMsgIdSet.remove(recentMsgIds.removeFirst())
            }
        }
        return false
    }

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    /**
     * 启动:加入默认频道、加载中继列表与配对会话,按当前模式(默认 AUTO)启动传输。
     * AUTO 下局域网 UDP 随 Wi-Fi 连接即用;不可用时静默降级为仅 Nostr,
     * 用户可随时通过 [refreshLanUdp] 补启局域网通道。
     */
    fun start() {
        joinChannel(DEFAULT_CHANNEL)
        restoreJoinedChannels()
        relays.clear()
        relays.addAll(nostrTransport.currentRelays())
        loadKeyPairSessions()
        loadPersistedHistory()
        cleanupStaleAttachments()
        applyMode(currentMode.value)
        presenceHandler.postDelayed(presenceRunnable, 5_000L)
    }

    /** 清理 7 天前的附件临时文件(图片/语音/文件预览),防止缓存目录无限增长 */
    private fun cleanupStaleAttachments() {
        fragmentExecutor.execute {
            val dir = java.io.File(context.cacheDir, "meschat_attachments")
            if (!dir.exists()) return@execute
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    fun stop() {
        presenceHandler.removeCallbacksAndMessages(null)
        lanTransport.stop()
        nostrTransport.stop()
        lanStarted.value = false
        nostrStarted.value = false
        fragmentExecutor.shutdownNow()
        fragmentBuffers.clear()
        updateTransportStatus()
    }

    /** 切换聊天方式 */
    fun switchChatMode(mode: ChatMode): Boolean {
        currentMode.value = mode
        return applyMode(mode)
    }

    /** 补启局域网 UDP 通道(如 Wi-Fi 刚连接) */
    fun refreshLanUdp() {
        awarenessGranted.value = lanTransport.isAvailable()
        if (currentMode.value == ChatMode.AUTO || currentMode.value == ChatMode.LAN_UDP) {
            if (!lanStarted.value) {
                lanStarted.value = lanTransport.start()
                if (!lanStarted.value && currentMode.value == ChatMode.LAN_UDP) {
                    errorEvent.value = "局域网通道不可用:请确保已连接 Wi-Fi"
                }
                updateTransportStatus()
            }
        }
    }

    /** 按模式启停传输 */
    private fun applyMode(mode: ChatMode): Boolean {
        var ok = true
        val wantLan = mode == ChatMode.AUTO || mode == ChatMode.LAN_UDP
        val wantNostr = mode == ChatMode.AUTO || mode == ChatMode.NOSTR

        if (wantLan) {
            if (!lanStarted.value) {
                lanStarted.value = lanTransport.start()
                if (lanStarted.value) {
                    presenceHandler.postDelayed({ requestHistoryForAllChannels() }, 1_500L)
                } else if (mode == ChatMode.LAN_UDP) {
                    errorEvent.value = "局域网通道不可用:请确保已连接 Wi-Fi"
                    ok = false
                }
            }
        } else if (lanStarted.value) {
            lanTransport.stop()
            lanStarted.value = false
            dropPeersOf(TransportType.LAN_UDP)
        }

        if (wantNostr) {
            if (!nostrStarted.value) {
                nostrStarted.value = nostrTransport.start()
                if (nostrStarted.value) {
                    resubscribeNostr()
                    sendAnnounce(null)
                } else if (mode == ChatMode.NOSTR) {
                    errorEvent.value = "云端通道启动失败,请检查网络连接"
                    ok = false
                }
            }
        } else if (nostrStarted.value) {
            nostrTransport.stop()
            nostrStarted.value = false
            dropPeersOf(TransportType.NOSTR)
        }

        awarenessGranted.value = lanTransport.isAvailable()
        updateTransportStatus()
        Log.i(TAG, "applyMode -> " + mode + " lan=" + lanStarted.value + " nostr=" + nostrStarted.value)
        return ok
    }

    /** 移除仅经指定通道可达的 peer */
    private fun dropPeersOf(transport: TransportType) {
        val toRemove = peerTransportMap.entries
            .filter {
                it.value.remove(transport)
                it.value.isEmpty()
            }
            .map { it.key }
        toRemove.forEach { id ->
            peerTransportMap.remove(id)
            peers.removeAll { it.peerId == id }
            peerNicknames.remove(id)
            peerHops.remove(id)
        }
        conversations.forEachIndexed { idx, conv ->
            if (!conv.isChannel && conv.id in toRemove && conv.privateChatKind != PrivateChatKind.KEY_PAIR) {
                conversations[idx] = conv.copy(transport = TransportType.NONE)
            }
        }
        updateTransportStatus()
    }

    private fun resubscribeNostr() {
        synchronized(joinedChannels) {
            joinedChannels.forEach { nostrTransport.joinChannel(it) }
        }
        keyPairSessions.keys.forEach { nostrTransport.joinRoom(it) }
    }

    /** 云端中继单条事件大小上限(relay 限制,保守值) */
    val nostrMaxBytes: Int get() = 50 * 1024

    /**
     * 当前模式允许发送的最大文件大小(字节)。
     * - 局域网:UDP 广播直连,受分片发送节奏约束,上限收敛防 OOM
     * - 自动:局域网优先,兜底云端
     * - 云端:受 relay 事件大小限制
     *
     * 注意:当前附件以 base64 内嵌进 JSON 传输,内存峰值约为文件大小的 4~5 倍
     * (字节数组 + base64 字符串 + JSON 序列化副本),因此上限按手机内存预算
     * 收敛到 80MB/30MB,避免超大文件直接 OOM。未来改为分块流式传输后可放宽。
     */
    val maxFileSizeBytes: Int get() = when (currentMode.value) {
        ChatMode.AUTO -> 30 * 1024 * 1024
        ChatMode.LAN_UDP -> 5 * 1024 * 1024
        ChatMode.NOSTR -> nostrMaxBytes
    }

    /**
     * 附件大小是否被允许发送。
     * 云端中继路径(仅云端模式,或目标仅云端可达)受 relay 事件上限约束。
     */
    fun attachmentAllowed(size: Long, targetPeerId: String?): Boolean {
        if (currentMode.value == ChatMode.NOSTR) return size <= nostrMaxBytes
        if (targetPeerId != null) {
            val set = peerTransportMap[targetPeerId]
            if (set != null && TransportType.NOSTR in set && TransportType.LAN_UDP !in set) {
                return size <= nostrMaxBytes
            }
        }
        return size <= maxFileSizeBytes
    }

    /** 当前会话的目标是否仅云端可达(供图片自适应压缩用) */
    fun isCurrentTargetNostrOnly(): Boolean {
        if (currentMode.value == ChatMode.NOSTR) return true
        val convId = currentConversationId.value ?: return false
        if (convId.startsWith("#") || convId.startsWith("kp:")) return false
        val set = peerTransportMap[convId]
        return set != null && TransportType.NOSTR in set && TransportType.LAN_UDP !in set
    }

    // ════════════════════════════════════════
    //  中继服务器管理
    // ════════════════════════════════════════

    /** 系统内置的 3 个稳定中继 */
    val builtinRelays: List<String> get() = NostrTransport.DEFAULT_RELAYS

    fun setRelays(urls: List<String>) {
        nostrTransport.setRelays(urls)
        syncRelaysList()
        updateTransportStatus()
    }

    fun addRelay(url: String): Boolean {
        val ok = nostrTransport.addRelay(url)
        if (ok) {
            syncRelaysList()
            updateTransportStatus()
        }
        return ok
    }

    fun removeRelay(url: String) {
        nostrTransport.removeRelay(url)
        syncRelaysList()
        updateTransportStatus()
    }

    fun resetRelays() {
        nostrTransport.resetRelays()
        syncRelaysList()
        updateTransportStatus()
    }

    private fun syncRelaysList() {
        relays.clear()
        relays.addAll(nostrTransport.currentRelays())
    }

    // ════════════════════════════════════════
    //  Peer 管理
    // ════════════════════════════════════════

    private fun onPeerConnectedInternal(peerId: String, nickname: String, transport: TransportType, hopCount: Int) {
        if (peerId == myPeerId) return
        // 防御:过滤自身 Nostr 公钥,避免幽灵用户出现在列表
        if (peerId == nostrTransport.currentPubKey) return
        peerLastSeen[peerId] = System.currentTimeMillis()
        val displayName = nickname.ifBlank { peerId }
        peerNicknames[peerId] = displayName
        val set = peerTransportMap.getOrPut(peerId) { mutableSetOf() }
        set.add(transport)
        if (transport == TransportType.LAN_UDP) {
            val old = peerHops[peerId]
            if (old == null || hopCount < old) peerHops[peerId] = hopCount
        }
        val displayTransport = when {
            TransportType.LAN_UDP in set -> TransportType.LAN_UDP
            TransportType.NOSTR in set -> TransportType.NOSTR
            else -> TransportType.NONE
        }
        val peer = MeshPeer(
            peerId = peerId,
            nickname = displayName,
            transport = displayTransport,
            lastSeen = System.currentTimeMillis(),
            hopCount = peerHops[peerId] ?: 0,
            isBridge = false // UDP 直连无桥接概念
        )
        peers.removeAll { it.peerId == peerId }
        peers.add(peer)
        updateTransportStatus()
        // 通知对端我的昵称(本地单播 / 云端 presence)
        sendAnnounce(peerId)
        // 本地邻居出现:顺带补拉各频道最近历史(节流由 requestChannelHistory 保证)
        if (transport == TransportType.LAN_UDP) {
            presenceHandler.postDelayed({ requestHistoryForAllChannels() }, 800L)
        }
    }

    private fun onPeerDisconnectedInternal(peerId: String, transport: TransportType) {
        val set = peerTransportMap[peerId]
        if (set == null) return
        set.remove(transport)
        if (set.isNotEmpty()) {
            // 仍有其他通道可达,更新展示通道与跳数
            val displayTransport = when {
                TransportType.LAN_UDP in set -> TransportType.LAN_UDP
                TransportType.NOSTR in set -> TransportType.NOSTR
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
        peerTransportMap.remove(peerId)
        peers.removeAll { it.peerId == peerId }
        peerNicknames.remove(peerId)
        peerHops.remove(peerId)
        peerLastSeen.remove(peerId)
        updateTransportStatus()
        conversations.forEachIndexed { idx, conv ->
            if (!conv.isChannel && conv.id == peerId) {
                conversations[idx] = conv.copy(transport = TransportType.NONE)
            }
        }
    }

    /** 统一处理数据接收 */
    private fun onDataReceivedInternal(json: String, fromPeerId: String, via: TransportType, hopCount: Int) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("_type", "message")) {
                "announce" -> handleAnnounce(obj, fromPeerId)
                "leave" -> handleLeave(obj, fromPeerId)
                "fragment" -> handleFragment(obj, fromPeerId, via, hopCount)
                "keypair" -> handleKeyPairMessage(obj, fromPeerId, via, hopCount)
                "hist_req" -> handleHistoryRequest(obj, fromPeerId)
                "hist_resp" -> handleHistoryResponse(obj, fromPeerId, via, hopCount)
                else -> handleChatMessage(obj, fromPeerId, via, hopCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDataReceived parse error", e)
        }
    }

    // ════════════════════════════════════════
    //  消息接收
    // ════════════════════════════════════════

    /** 处理分片消息:收集分片,收齐后拼接为完整 JSON 并递归处理 */
    private fun handleFragment(obj: JSONObject, fromPeerId: String, via: TransportType, hopCount: Int) {
        val fragId = obj.optString("fragId")
        val total = obj.optInt("total")
        val index = obj.optInt("index")
        val chunk = obj.optString("chunk")
        if (fragId.isBlank() || total <= 0 || index < 0 || index >= total) return

        cleanupFragmentBuffers()

        val buffer = fragmentBuffers.getOrPut(fragId) { FragmentBuffer(total) }
        synchronized(buffer) {
            if (buffer.chunks[index] == null) {
                buffer.chunks[index] = chunk
                buffer.received++
            }
            if (buffer.received < total) return

            fragmentBuffers.remove(fragId)
            val completeJson = buffer.chunks.filterNotNull().joinToString("")
            onDataReceivedInternal(completeJson, fromPeerId, via, hopCount)
        }
    }

    private fun cleanupFragmentBuffers() {
        val now = System.currentTimeMillis()
        fragmentBuffers.entries.removeAll { now - it.value.firstReceived > FRAGMENT_TIMEOUT_MS }
    }

    // ════════════════════════════════════════
    //  频道消息缓存 + 离线历史补拉
    // ════════════════════════════════════════

    /** 向网内广播历史补拉请求(仅局域网;云端有 relay 历史,无需此机制) */
    private fun requestChannelHistory(channel: String) {
        if (!lanStarted.value) return
        val now = System.currentTimeMillis()
        val last = lastHistoryRequestAt[channel] ?: 0L
        if (now - last < HISTORY_REQUEST_MIN_INTERVAL_MS) return
        lastHistoryRequestAt[channel] = now
        val json = JSONObject().apply {
            put("_type", "hist_req")
            put("origin", myPeerId)
            put("channel", channel)
            put("window", HISTORY_SYNC_WINDOW_MS)
            put("limit", SYNC_LATEST_COUNT)
        }.toString()
        lanTransport.sendMessage(json, null)
        Log.d(TAG, "Request history for channel: " + channel)
    }

    /** 为所有已加入频道请求历史(节流由 [requestChannelHistory] 保证) */
    private fun requestHistoryForAllChannels() {
        if (!lanStarted.value) return
        synchronized(joinedChannels) {
            joinedChannels.forEach { requestChannelHistory(it) }
        }
    }

    /**
     * 收到历史补拉请求:
     * - 安全:仅应答已知节点(peerTransportMap 中存在的对端),按 peer+频道 限流
     * - 数据:从持久化存储读取最近 10 分钟窗口,分块应答(每块 ≤48KB,最多 20 块)
     */
    private fun handleHistoryRequest(obj: JSONObject, fromPeerId: String) {
        val channel = obj.optString("channel", "")
        if (channel.isBlank() || channel !in joinedChannels) return
        if (fromPeerId == myPeerId) return
        // 仅应答已知节点,防止陌生请求方探测历史
        val peerSet = peerTransportMap[fromPeerId] ?: return
        if (TransportType.LAN_UDP !in peerSet) return
        // 同一节点同一频道的应答限流
        val key = fromPeerId + ":" + channel
        val now = System.currentTimeMillis()
        val last = lastHistoryServeAt[key] ?: 0L
        if (now - last < HISTORY_SERVE_MIN_INTERVAL_MS) return
        lastHistoryServeAt[key] = now

        val windowMs = obj.optLong("window", HISTORY_SYNC_WINDOW_MS)
            .coerceIn(60_000L, HISTORY_SYNC_WINDOW_MS)
        val limit = obj.optInt("limit", SYNC_LATEST_COUNT).coerceIn(1, 100)
        val before = obj.optLong("before", 0L)
        val sinceMs = System.currentTimeMillis() - windowMs
        // 后台线程读取持久化存储,主线程发送
        fragmentExecutor.execute {
            val convId = "#" + channel
            val snapshot = if (before > 0L) {
                // 游标请求:更早于 before 的消息,且仍在同步窗口内
                messageStore.readRange(convId, before, limit)
                    .filter { line ->
                        runCatching { JSONObject(line).optLong("timestamp", 0L) }
                            .getOrDefault(0L) >= sinceMs
                    }
            } else {
                // 自动同步:只给最新 limit 条(懒加载,避免全量拉取)
                messageStore.readNewestSince(convId, sinceMs, limit)
            }
            if (snapshot.isEmpty()) return@execute
            // 分块:每块 ≤48KB(发现消息回退路径可承载;大照片单独成块走 NDP)
            var chunk = JSONArray()
            var chunkBytes = 0
            var chunksSent = 0
            fun flush() {
                if (chunk.length() == 0 || chunksSent >= MAX_HIST_CHUNKS) {
                    chunk = JSONArray()
                    chunkBytes = 0
                    return
                }
                val resp = JSONObject().apply {
                    put("_type", "hist_resp")
                    put("origin", myPeerId)
                    put("target", fromPeerId)
                    put("channel", channel)
                    if (before > 0L) put("before", before)
                    put("messages", chunk)
                }.toString()
                lanTransport.sendMessage(resp, fromPeerId)
                chunksSent++
                chunk = JSONArray()
                chunkBytes = 0
            }
            for (json in snapshot) {
                val size = json.toByteArray(Charsets.UTF_8).size
                if (chunkBytes > 0 && chunkBytes + size > MAX_HIST_CHUNK_BYTES) flush()
                if (chunksSent >= MAX_HIST_CHUNKS) break
                chunk.put(JSONObject(json))
                chunkBytes += size
            }
            flush()
            Log.d(TAG, "History response to " + fromPeerId + " for " + channel + ": " + chunksSent + " chunks")
        }
    }

    /**
     * 收到历史应答:
     * - 安全:仅接受已知节点应答;逐条校验频道与时间戳窗口(±时钟偏差),防止注入过期/伪造消息
     * - 去重:按消息 ID 幂等,多节点重复应答无害
     */
    private fun handleHistoryResponse(obj: JSONObject, fromPeerId: String, via: TransportType, hopCount: Int) {
        if (peerTransportMap[fromPeerId] == null) return
        val channel = obj.optString("channel", "")
        if (channel.isBlank() || channel !in joinedChannels) return
        val msgs = obj.optJSONArray("messages") ?: return
        val isOlderPull = obj.optLong("before", 0L) > 0L
        val convId = "#" + channel
        val mine = myPeerId
        val now = System.currentTimeMillis()
        val accepted = mutableListOf<MesMessage>()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            // 时间戳窗口校验:超出 [now-window-偏差, now+偏差] 的消息一律丢弃
            val ts = m.optLong("timestamp", 0L)
            if (ts <= 0L) continue
            if (ts < now - HISTORY_SYNC_WINDOW_MS - CLOCK_SKEW_TOLERANCE_MS) continue
            if (ts > now + CLOCK_SKEW_TOLERANCE_MS) continue
            // 频道一致性校验(消息自报频道必须与应答频道一致)
            val msgChannel = m.optString("channel", "")
            if (msgChannel != channel) continue
            if (isOlderPull) {
                // 游标补拉:解析后按时间排序插入,不产生未读、不打乱顺序
                parseStoredMessage(m.toString(), mine)?.let { accepted.add(it) }
            } else {
                // 自动同步(离线错过的消息):正常回放,产生未读提醒
                onDataReceivedInternal(m.toString(), fromPeerId, via, hopCount)
            }
        }
        if (isOlderPull && accepted.isNotEmpty()) {
            mergePersistedMessages(convId, accepted)
            // 更新游标:继续向前翻页
            val oldest = accepted.minOf { it.timestamp }
            val current = historyFrontiers[convId] ?: Long.MAX_VALUE
            if (oldest < current) historyFrontiers[convId] = oldest
            hasMoreOlder[convId] = accepted.size >= HISTORY_PAGE_SIZE
            Log.d(TAG, "Merged " + accepted.size + " older messages for " + channel)
        }
    }

    /** 密钥匹对消息:用本地保存的配对密钥解密,失败(密钥不符)静默丢弃 */
    private fun handleKeyPairMessage(obj: JSONObject, fromPeerId: String, via: TransportType, hopCount: Int) {
        val roomId = obj.optString("roomId", "")
        val session = keyPairSessions[roomId] ?: return
        try {
            val iv = Base64.decode(obj.optString("iv", ""), Base64.NO_WRAP)
            val ct = Base64.decode(obj.optString("data", ""), Base64.NO_WRAP)
            val plain = NostrCrypto.roomDecrypt(session.key, iv, ct) ?: return
            val inner = JSONObject(plain)
            handleChatMessage(inner, fromPeerId, via, hopCount)
        } catch (e: Exception) {
            Log.e(TAG, "handleKeyPairMessage error", e)
        }
    }

    private fun handleChatMessage(obj: JSONObject, fromPeerId: String, via: TransportType, hopCount: Int) {
        // 消息去重:同一消息可能经多通道/洪泛重复到达,按 id 幂等
        val msgIdRaw = obj.optString("id", "")
        if (msgIdRaw.isNotEmpty() && isDuplicateMessage(msgIdRaw)) return
        val msgId = msgIdRaw.ifEmpty { UUID.randomUUID().toString() }
        val sender = obj.optString("sender", "未知")
        val senderPeerId = obj.optString("senderPeerId", fromPeerId)
        val content = obj.optString("content", "")
        val typeStr = obj.optString("type", "TEXT")
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val channel = obj.optString("channel", "").ifBlank { null }
        val isPrivate = obj.optBoolean("isPrivate", false)
        val roomId = obj.optString("roomId", "").ifBlank { null }
        val recipientPeerId = obj.optString("recipientPeerId", "").ifBlank { null }

        // 私聊校验收件人:只有收件人是我才处理(密钥匹对消息无收件人,凭密钥解密)
        if (isPrivate && recipientPeerId != null && recipientPeerId != myPeerId) return
        // 忽略自己回环的消息
        if (senderPeerId == myPeerId) return
        // 频道消息仅当已加入该频道时才显示
        if (!isPrivate && channel != null && channel !in joinedChannels) return

        val msgType = runCatching { MesMessageType.valueOf(typeStr) }
            .getOrDefault(MesMessageType.TEXT)

        // 未知发送者(如超出路由通告范围的远端节点)自动注册
        if (peerTransportMap[senderPeerId] == null) {
            onPeerConnectedInternal(senderPeerId, sender, via, hopCount)
        } else {
            peerLastSeen[senderPeerId] = System.currentTimeMillis()
            peers.forEachIndexed { idx, peer ->
                if (peer.peerId == senderPeerId) {
                    peers[idx] = peer.copy(lastSeen = System.currentTimeMillis())
                }
            }
        }

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
                    val bytes = Base64.decode(imageData, Base64.NO_WRAP)
                    imagePath = saveTempFile(bytes, "img", "jpg")
                }
            }
            MesMessageType.VOICE -> {
                val voiceData = obj.optString("voiceData", "")
                voiceDuration = obj.optInt("voiceDuration", 0)
                if (voiceData.isNotEmpty()) {
                    val bytes = Base64.decode(voiceData, Base64.NO_WRAP)
                    voicePath = saveTempFile(bytes, "voice", "m4a")
                }
            }
            MesMessageType.FILE -> {
                fileName = obj.optString("fileName", "未知文件")
                fileSize = obj.optLong("fileSize", 0)
                val fileData = obj.optString("fileData", "")
                if (fileData.isNotEmpty()) {
                    val ext = fileName.substringAfterLast('.', "bin")
                    val bytes = Base64.decode(fileData, Base64.NO_WRAP)
                    filePath = saveTempFile(bytes, "file", ext)
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
            isRelay = hopCount > 0,
            originalSender = if (hopCount > 0) sender else null,
            channel = channel,
            isPrivate = isPrivate,
            imagePath = imagePath,
            voicePath = voicePath,
            voiceDuration = voiceDuration,
            fileName = fileName,
            fileSize = fileSize,
            filePath = filePath,
            deliveryState = DeliveryState.DELIVERED,
            via = via,
            hopCount = hopCount,
            roomId = roomId
        )

        // 确定 conversationId
        val convId = when {
            isPrivate && roomId != null -> "kp:" + roomId
            isPrivate -> senderPeerId
            else -> "#" + (channel ?: DEFAULT_CHANNEL)
        }

        // 持久化到本机存储(全量保存;跨设备同步只取 10 分钟窗口)
        messageStore.append(convId, obj.toString())

        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, content, timestamp, !isPrivate)
    }

    private fun handleAnnounce(obj: JSONObject, fromPeerId: String) {
        peerLastSeen[fromPeerId] = System.currentTimeMillis()
        val nickname = obj.optString("nickname", "")
        if (nickname.isNotBlank()) {
            peerNicknames[fromPeerId] = nickname
            peers.forEachIndexed { idx, peer ->
                if (peer.peerId == fromPeerId) {
                    peers[idx] = peer.copy(nickname = nickname, lastSeen = System.currentTimeMillis())
                }
            }
        }
    }

    private fun handleLeave(obj: JSONObject, fromPeerId: String) {
        peers.removeAll { it.peerId == fromPeerId }
        updateTransportStatus()
    }

    // ════════════════════════════════════════
    //  发送
    // ════════════════════════════════════════

    /**
     * 发送文本消息到当前会话。
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

        val convId = currentConversationId.value ?: return
        inputText.value = ""
        sendTextToConversation(convId, text)
    }

    /**
     * 向指定会话发送文本(不依赖 [currentConversationId],供悬浮窗等入口使用)。
     * 支持频道 / 直接私聊 / 密钥匹对会话。
     */
    fun sendTextToConversation(convId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (clean.startsWith("/")) {
            errorEvent.value = "此入口不支持 / 命令,请打开主界面使用"
            return
        }
        var conv = conversations.find { it.id == convId }
        if (conv == null) {
            if (convId.startsWith("#")) {
                joinChannel(convId.removePrefix("#"))
                conv = conversations.find { it.id == convId }
            } else {
                return
            }
        }

        // 密钥匹对会话:加密后广播
        if (conv?.isKeyPairChat == true) {
            sendKeyPairMessage(conv.roomId ?: return, clean)
            return
        }

        val isChannel = conv?.isChannel == true
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel) convId else null

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = clean,
            type = MesMessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
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
            channel?.let { put("channel", it) }
            put("isPrivate", msg.isPrivate)
            targetPeerId?.let { put("recipientPeerId", it) }
        }.toString()

        sendToBestTransports(json, targetPeerId)
        messageStore.append(convId, json)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, clean, msg.timestamp, isChannel)
    }

    /** 发送 announce(昵称与频道信息;本地单播 / 云端 presence 广播) */
    private fun sendAnnounce(targetPeerId: String?) {
        val json = JSONObject().apply {
            put("_type", "announce")
            put("senderPeerId", myPeerId)
            put("nickname", myNickname)
            put("channels", JSONArray(joinedChannels.toList()))
        }.toString()
        if (lanStarted.value) lanTransport.sendMessage(json, targetPeerId)
        if (targetPeerId == null && nostrStarted.value) nostrTransport.sendPresence(json)
    }

    /**
     * 按目标选择最优通道发送:
     * - 广播:本地洪泛 + 云端订阅(双通道都发)
     * - 定向:对端局域网可达走 UDP 单播,否则走 Nostr(NIP-04 加密)
     */
    private fun sendToBestTransports(json: String, targetPeerId: String?) {
        if (targetPeerId == null) {
            if (lanStarted.value) lanTransport.sendMessage(json, null)
            if (nostrStarted.value) nostrTransport.sendMessage(json, null)
        } else {
            val set = peerTransportMap[targetPeerId]
            if (set.isNullOrEmpty()) {
                if (lanStarted.value) lanTransport.sendMessage(json, targetPeerId)
                if (nostrStarted.value) nostrTransport.sendMessage(json, targetPeerId)
            } else {
                if (TransportType.LAN_UDP in set) {
                    lanTransport.sendMessage(json, targetPeerId)
                } else if (TransportType.NOSTR in set) {
                    nostrTransport.sendMessage(json, targetPeerId)
                } else if (lanStarted.value) {
                    lanTransport.sendMessage(json, targetPeerId)
                }
            }
        }
    }

    /** 分片发送大消息(图片/语音/文件的 base64 数据) */
    fun sendFragmented(json: String, targetPeerId: String?) {
        val data = json.toByteArray(Charsets.UTF_8)
        if (data.size <= FRAGMENT_SIZE) {
            sendToBestTransports(json, targetPeerId)
            return
        }
        val total = (data.size + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE
        val fragId = UUID.randomUUID().toString()
        Log.i(TAG, "sendFragmented: " + data.size + "B -> " + total + " fragments")
        fragmentExecutor.execute {
            for (i in 0 until total) {
                val start = i * FRAGMENT_SIZE
                val end = minOf(start + FRAGMENT_SIZE, data.size)
                val chunk = String(data, start, end - start, Charsets.UTF_8)
                val fragJson = JSONObject().apply {
                    put("_type", "fragment")
                    put("senderPeerId", myPeerId)
                    put("fragId", fragId)
                    put("total", total)
                    put("index", i)
                    put("chunk", chunk)
                }.toString()
                sendToBestTransports(fragJson, targetPeerId)
                if (i < total - 1) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /** 发送图片消息 */
    fun sendImage(imageBytes: ByteArray) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel && !conv.isKeyPairChat) convId else null
        if (!attachmentAllowed(imageBytes.size.toLong(), targetPeerId)) {
            errorEvent.value = "云端中继单条消息上限 " + nostrMaxBytes / 1024 + "KB,大文件请切换仅局域网模式传输"
            return
        }

        val localPath = saveTempFile(imageBytes, "img", "jpg")
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

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
            deliveryState = DeliveryState.SENDING,
            roomId = conv.roomId
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
            conv.roomId?.let { put("roomId", it) }
            put("imageData", base64)
        }.toString()

        if (conv.isKeyPairChat) {
            keypairWrapAndSend(conv.roomId ?: return, json)
        } else {
            sendFragmented(json, targetPeerId)
        }
        messageStore.append(convId, json)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[图片]", msg.timestamp, isChannel)
    }

    /** 发送语音消息 */
    fun sendVoice(voiceBytes: ByteArray, durationSec: Int) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel && !conv.isKeyPairChat) convId else null
        if (!attachmentAllowed(voiceBytes.size.toLong(), targetPeerId)) {
            errorEvent.value = "语音消息超出云端中继限制(" + nostrMaxBytes / 1024 + "KB),请缩短录音或使用文本消息"
            return
        }

        val localPath = saveTempFile(voiceBytes, "voice", "m4a")
        val base64 = Base64.encodeToString(voiceBytes, Base64.NO_WRAP)

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
            deliveryState = DeliveryState.SENDING,
            roomId = conv.roomId
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
            conv.roomId?.let { put("roomId", it) }
            put("voiceData", base64)
            put("voiceDuration", durationSec)
        }.toString()

        if (conv.isKeyPairChat) {
            keypairWrapAndSend(conv.roomId ?: return, json)
        } else {
            sendFragmented(json, targetPeerId)
        }
        messageStore.append(convId, json)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[语音]", msg.timestamp, isChannel)
    }

    /** 发送文件消息 */
    fun sendFile(fileName: String, fileBytes: ByteArray) {
        val convId = currentConversationId.value ?: return
        val conv = conversations.find { it.id == convId } ?: return
        val isChannel = conv.isChannel
        val channel = if (isChannel) convId.removePrefix("#") else null
        val targetPeerId = if (!isChannel && !conv.isKeyPairChat) convId else null
        if (!attachmentAllowed(fileBytes.size.toLong(), targetPeerId)) {
            errorEvent.value = "云端中继单条消息上限 " + nostrMaxBytes / 1024 + "KB,大文件请切换仅局域网模式传输"
            return
        }

        val ext = fileName.substringAfterLast('.', "bin")
        val localPath = saveTempFile(fileBytes, "file", ext)
        val base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = "[文件] " + fileName,
            type = MesMessageType.FILE,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            channel = channel,
            isPrivate = !isChannel,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            filePath = localPath,
            deliveryState = DeliveryState.SENDING,
            roomId = conv.roomId
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
            conv.roomId?.let { put("roomId", it) }
            put("fileName", fileName)
            put("fileSize", fileBytes.size.toLong())
            put("fileData", base64)
        }.toString()

        if (conv.isKeyPairChat) {
            keypairWrapAndSend(conv.roomId ?: return, json)
        } else {
            sendFragmented(json, targetPeerId)
        }
        messageStore.append(convId, json)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, "[文件] " + fileName, msg.timestamp, isChannel)
    }

    /** 密钥匹对消息加密封装后广播(本地洪泛 + 云端房间订阅) */
    private fun keypairWrapAndSend(roomId: String, innerJson: String) {
        val session = keyPairSessions[roomId] ?: return
        val enc = NostrCrypto.roomEncrypt(session.key, innerJson) ?: return
        val wrapper = JSONObject().apply {
            put("_type", "keypair")
            put("roomId", roomId)
            put("iv", Base64.encodeToString(enc.first, Base64.NO_WRAP))
            put("data", Base64.encodeToString(enc.second, Base64.NO_WRAP))
        }.toString()
        if (lanStarted.value) lanTransport.sendMessage(wrapper, null)
        if (nostrStarted.value) nostrTransport.sendRoomMessage(roomId, wrapper)
    }

    /** 发送密钥匹对文本消息 */
    private fun sendKeyPairMessage(roomId: String, text: String) {
        val session = keyPairSessions[roomId] ?: return
        val convId = "kp:" + roomId
        val msg = MesMessage(
            id = UUID.randomUUID().toString(),
            sender = myNickname,
            senderPeerId = myPeerId,
            content = text,
            type = MesMessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isMine = true,
            isPrivate = true,
            deliveryState = DeliveryState.SENDING,
            roomId = roomId
        )
        val inner = JSONObject().apply {
            put("id", msg.id)
            put("sender", msg.sender)
            put("senderPeerId", msg.senderPeerId)
            put("content", msg.content)
            put("type", msg.type.name)
            put("timestamp", msg.timestamp)
            put("isPrivate", true)
            put("roomId", roomId)
        }.toString()
        keypairWrapAndSend(roomId, inner)
        messageStore.append(convId, inner)
        addMessageToConversation(convId, msg)
        updateConversationPreview(convId, text, msg.timestamp, false)
    }

    private fun saveTempFile(bytes: ByteArray, prefix: String, ext: String): String {
        val dir = java.io.File(context.cacheDir, "meschat_attachments")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, prefix + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8) + "." + ext)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // ════════════════════════════════════════
    //  密钥匹对私聊
    // ════════════════════════════════════════

    /** 创建/加入密钥匹对会话(输入相同配对密钥的双方进入同一加密房间) */
    fun createKeyPairChat(key: String): String {
        val roomId = NostrCrypto.roomIdFromKey(key)
        val session = KeyPairSession(roomId, NostrCrypto.deriveKey(key), "配对·" + roomId.take(6))
        keyPairSessions[roomId] = session
        persistKeyPairSessions()
        val convId = "kp:" + roomId
        if (conversations.none { it.id == convId }) {
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = ConversationType.PRIVATE,
                    title = session.title,
                    isPasswordProtected = true,
                    privateChatKind = PrivateChatKind.KEY_PAIR,
                    roomId = roomId
                )
            )
            messagesByConversation.add(convId to mutableListOf())
        }
        if (nostrStarted.value) nostrTransport.joinRoom(roomId)
        return roomId
    }

    /** 是否已持有某配对房间密钥 */
    fun hasKeyPairSession(roomId: String): Boolean = keyPairSessions.containsKey(roomId)

    private fun persistKeyPairSessions() {
        val obj = JSONObject()
        keyPairSessions.values.forEach { s ->
            obj.put(s.roomId, java.util.Base64.getEncoder().encodeToString(s.key))
        }
        context.getSharedPreferences("meschat_prefs", Context.MODE_PRIVATE)
            .edit().putString("pair_keys", obj.toString()).apply()
    }

    private fun loadKeyPairSessions() {
        val raw = context.getSharedPreferences("meschat_prefs", Context.MODE_PRIVATE)
            .getString("pair_keys", null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { roomId ->
                val keyB64 = obj.optString(roomId, "")
                val key = java.util.Base64.getDecoder().decode(keyB64)
                keyPairSessions[roomId] = KeyPairSession(roomId, key, "配对·" + roomId.take(6))
                val convId = "kp:" + roomId
                if (conversations.none { it.id == convId }) {
                    conversations.add(
                        ChatConversation(
                            id = convId,
                            type = ConversationType.PRIVATE,
                            title = "配对·" + roomId.take(6),
                            isPasswordProtected = true,
                            privateChatKind = PrivateChatKind.KEY_PAIR,
                            roomId = roomId
                        )
                    )
                    messagesByConversation.add(convId to mutableListOf())
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  持久化:频道列表 + 历史恢复
    // ════════════════════════════════════════

    private fun persistJoinedChannels() {
        val arr = JSONArray()
        synchronized(joinedChannels) {
            joinedChannels.forEach { arr.put(it) }
        }
        context.getSharedPreferences("meschat_prefs", Context.MODE_PRIVATE)
            .edit().putString("joined_channels", arr.toString()).apply()
    }

    private fun restoreJoinedChannels() {
        val raw = context.getSharedPreferences("meschat_prefs", Context.MODE_PRIVATE)
            .getString("joined_channels", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val ch = arr.optString(i, "")
                if (ch.isNotBlank() && ch != DEFAULT_CHANNEL && ch !in joinedChannels) {
                    joinChannel(ch)
                }
            }
        }
    }

    /**
     * 启动时从持久化存储恢复各会话最近一页历史(懒加载:更早的等用户上滑再读)。
     * 后台读取,主线程合并;不产生未读数。
     */
    private fun loadPersistedHistory() {
        val convIds = messageStore.listConvIds()
        if (convIds.isEmpty()) return
        val mine = myPeerId
        fragmentExecutor.execute {
            convIds.forEach { convId ->
                val lines = messageStore.readRecent(convId, HISTORY_PAGE_SIZE)
                if (lines.isEmpty()) {
                    presenceHandler.post { hasMoreOlder[convId] = false }
                    return@forEach
                }
                val msgs = lines.mapNotNull { parseStoredMessage(it, mine) }
                if (msgs.isEmpty()) return@forEach
                presenceHandler.post {
                    mergePersistedMessages(convId, msgs)
                    historyFrontiers[convId] = msgs.minOf { it.timestamp }
                    hasMoreOlder[convId] = lines.size >= HISTORY_PAGE_SIZE
                }
            }
        }
    }

    /**
     * 懒加载更早一页历史(用户上滑到顶触发):
     * - 先读本地持久化存储(本机保存的全部历史,不受 10 分钟窗口限制)
     * - 本地读尽后,若是频道且局域网可用,再按游标向网内请求更早的历史
     *   (网内同步受 10 分钟窗口约束,窗口外的消息只存在于各设备本地)
     */
    fun loadOlderHistory(convId: String) {
        if (hasMoreOlder[convId] != true) return
        val before = historyFrontiers[convId] ?: Long.MAX_VALUE
        val mine = myPeerId
        fragmentExecutor.execute {
            val lines = messageStore.readRange(convId, before, HISTORY_PAGE_SIZE)
            val msgs = lines.mapNotNull { parseStoredMessage(it, mine) }
            presenceHandler.post {
                if (msgs.isEmpty()) {
                    hasMoreOlder[convId] = false
                    // 本地读尽:频道且局域网可用时,尝试向网内要更早历史
                    if (convId.startsWith("#")) {
                        requestOlderChannelHistory(convId.removePrefix("#"))
                    }
                } else {
                    mergePersistedMessages(convId, msgs)
                    historyFrontiers[convId] = msgs.minOf { it.timestamp }
                    hasMoreOlder[convId] = msgs.size >= HISTORY_PAGE_SIZE
                }
            }
        }
    }

    /** 按游标请求更早的频道历史(受网内 10 分钟窗口约束;60 秒节流) */
    private fun requestOlderChannelHistory(channel: String) {
        if (!lanStarted.value) return
        val key = "older:" + channel
        val now = System.currentTimeMillis()
        val last = lastHistoryRequestAt.getOrDefault(key, 0L)
        if (now - last < 60_000L) return
        lastHistoryRequestAt[key] = now
        val before = historyFrontiers["#" + channel] ?: return
        val json = JSONObject().apply {
            put("_type", "hist_req")
            put("origin", myPeerId)
            put("channel", channel)
            put("before", before)
            put("limit", HISTORY_PAGE_SIZE)
        }.toString()
        lanTransport.sendMessage(json, null)
        Log.d(TAG, "Request older history for channel: " + channel + " before=" + before)
    }

    /** 合并恢复的历史(去重 + 按时间排序;不产生未读数) */
    private fun mergePersistedMessages(convId: String, msgs: List<MesMessage>) {
        // 重启后私聊会话不存在,按存储重建
        if (conversations.none { it.id == convId }) {
            val isChannel = convId.startsWith("#")
            val isKeyPair = convId.startsWith("kp:")
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = if (isChannel) ConversationType.CHANNEL else ConversationType.PRIVATE,
                    title = convId.removePrefix("#").removePrefix("kp:"),
                    isPasswordProtected = isKeyPair,
                    privateChatKind = if (isChannel) null else {
                        if (isKeyPair) PrivateChatKind.KEY_PAIR else PrivateChatKind.DIRECT
                    },
                    roomId = if (isKeyPair) convId.removePrefix("kp:") else null
                )
            )
        }
        val idx = messagesByConversation.indexOfFirst { it.first == convId }
        val existing = if (idx >= 0) messagesByConversation[idx].second else mutableListOf()
        val existingIds = existing.map { it.id }.toHashSet()
        val fresh = msgs.filter { it.id !in existingIds }
        if (fresh.isEmpty()) return
        val merged = (existing + fresh).sortedBy { it.timestamp }
        if (idx >= 0) {
            messagesByConversation[idx] = convId to merged.toMutableList()
        } else {
            messagesByConversation.add(convId to merged.toMutableList())
        }
    }

    /** 解析持久化行 → 消息模型(无副作用,不触发未读/去重) */
    private fun parseStoredMessage(json: String, myPeerId: String): MesMessage? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("_type", "message") != "message") return null
            val id = obj.optString("id", "").ifBlank { return null }
            val senderPeerId = obj.optString("senderPeerId", "")
            val isMine = senderPeerId == myPeerId
            MesMessage(
                id = id,
                sender = obj.optString("sender", "未知"),
                senderPeerId = senderPeerId.ifBlank { null },
                content = obj.optString("content", ""),
                type = runCatching { MesMessageType.valueOf(obj.optString("type", "TEXT")) }
                    .getOrDefault(MesMessageType.TEXT),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                isMine = isMine,
                channel = obj.optString("channel", "").ifBlank { null },
                isPrivate = obj.optBoolean("isPrivate", false),
                voiceDuration = obj.optInt("voiceDuration", 0),
                fileName = obj.optString("fileName", "").ifBlank { null },
                fileSize = obj.optLong("fileSize", 0),
                deliveryState = if (isMine) DeliveryState.SENT else DeliveryState.DELIVERED,
                roomId = obj.optString("roomId", "").ifBlank { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ════════════════════════════════════════
    //  会话管理
    // ════════════════════════════════════════

    /** 加入/创建频道 */
    fun joinChannel(channel: String, password: String? = null) {
        val convId = "#" + channel
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
        persistJoinedChannels()
        // Nostr 云端订阅该频道
        if (nostrStarted.value) nostrTransport.joinChannel(channel)
        // 通知对端
        sendAnnounce(null)
        // 局域网:向网内请求该频道最近 10 分钟历史(补拉离线期间错过的消息)
        presenceHandler.postDelayed({ requestChannelHistory(channel) }, 800L)
    }

    /** 离开频道 */
    fun leaveChannel(channel: String) {
        val convId = "#" + channel
        conversations.removeAll { it.id == convId }
        messagesByConversation.removeAll { it.first == convId }
        joinedChannels.remove(channel)
        persistJoinedChannels()
        if (nostrStarted.value) nostrTransport.leaveChannel(channel)
        if (currentConversationId.value == convId) {
            currentConversationId.value = null
        }
        // 广播离开
        val json = JSONObject().apply {
            put("_type", "leave")
            put("senderPeerId", myPeerId)
            put("channel", channel)
        }.toString()
        sendToBestTransports(json, null)
    }

    /** 确保私聊会话存在(不切换当前会话,供悬浮窗使用) */
    fun ensurePrivateChat(peerId: String) {
        val nickname = peerNicknames[peerId] ?: peerId
        val convId = peerId
        if (conversations.none { it.id == convId }) {
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = ConversationType.PRIVATE,
                    title = nickname,
                    transport = peerTransportMap[peerId]?.firstOrNull() ?: TransportType.NONE,
                    privateChatKind = PrivateChatKind.DIRECT
                )
            )
            messagesByConversation.add(convId to mutableListOf())
        }
    }

    /** 发起直接私聊并打开会话 */
    fun startPrivateChat(peerId: String) {
        ensurePrivateChat(peerId)
        currentConversationId.value = peerId
    }

    /** 打开密钥匹对会话 */
    fun openKeyPairChat(roomId: String) {
        val convId = "kp:" + roomId
        currentConversationId.value = convId
    }

    /** 打开会话 */
    fun openConversation(convId: String) {
        currentConversationId.value = convId
    }

    /** 返回主页(会话列表) */
    fun backToList() {
        currentConversationId.value = null
    }

    // ════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════

    /** 添加消息到指定会话 */
    private fun addMessageToConversation(convId: String, msg: MesMessage) {
        val pair = messagesByConversation.find { it.first == convId }
        if (pair != null) {
            pair.second.add(msg)
            if (pair.second.size > MAX_MESSAGES_PER_CONVERSATION) {
                pair.second.subList(0, pair.second.size - MAX_MESSAGES_PER_CONVERSATION).clear()
            }
            val idx = messagesByConversation.indexOf(pair)
            messagesByConversation[idx] = convId to pair.second.toMutableList()
        } else {
            val isChannel = convId.startsWith("#")
            conversations.add(
                ChatConversation(
                    id = convId,
                    type = if (isChannel) ConversationType.CHANNEL else ConversationType.PRIVATE,
                    title = convId.removePrefix("#").removePrefix("kp:"),
                    transport = TransportType.NONE,
                    unreadCount = if (currentConversationId.value == convId) 0 else 1,
                    privateChatKind = if (!isChannel) PrivateChatKind.DIRECT else null
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

    /** 更新传输状态 */
    private fun updateTransportStatus() {
        transportStatus.value = TransportStatus(
            lanUdp = lanStarted.value,
            nostr = nostrStarted.value,
            relayConnected = nostrTransport.connectedRelayCount,
            relayTotal = nostrTransport.totalRelayCount,
            peerCount = peers.size,
            localPeerCount = peers.count { it.transport == TransportType.LAN_UDP },
            nostrPeerCount = peers.count { it.transport == TransportType.NOSTR },
            dataLinks = 0
        )
    }

    /** UDP 直连无桥接概念,恒为 false(保留字段避免 UI 层改动) */
    val isBridging: Boolean get() = false

    /** UDP 直连无桥接概念 */
    fun isPeerBridge(peerId: String): Boolean = false

    /** 当前会话的消息列表 */
    fun getCurrentMessages(): List<MesMessage> {
        val convId = currentConversationId.value ?: return emptyList()
        return messagesByConversation.find { it.first == convId }?.second ?: emptyList()
    }

    /** 任意会话的消息列表(供悬浮窗使用) */
    fun getConversationMessages(convId: String): List<MesMessage> =
        messagesByConversation.find { it.first == convId }?.second ?: emptyList()

    /** 当前会话 */
    fun getCurrentConversation(): ChatConversation? {
        val convId = currentConversationId.value ?: return null
        return conversations.find { it.id == convId }
    }

    /** 清除会话未读数 */
    fun clearUnread(convId: String) {
        val idx = conversations.indexOfFirst { it.id == convId }
        if (idx >= 0) {
            conversations[idx] = conversations[idx].copy(unreadCount = 0)
        }
    }

    /** 全部未读数(悬浮窗角标) */
    val totalUnread: Int get() = conversations.sumOf { it.unreadCount }

    /**
     * 直接向指定频道发送输入框文本(悬浮窗兼容入口,内部走 [sendTextToConversation])。
     */
    fun sendTextToChannel(channel: String) {
        val text = inputText.value.trim()
        if (text.isEmpty()) return
        inputText.value = ""
        sendTextToConversation("#" + channel, text)
    }

    /** 获取指定频道的消息列表(兼容入口) */
    fun getChannelMessages(channel: String): List<MesMessage> =
        getConversationMessages("#" + channel)

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
                    sendAnnounce(null)
                }
                return true
            }
            "leave", "part" -> {
                val ch = args.firstOrNull()?.removePrefix("#")
                    ?: currentConversationId.value?.removePrefix("#")
                if (ch != null) leaveChannel(ch)
                return true
            }
            "msg" -> {
                if (args.size >= 2) {
                    val target = args[0]
                    val message = args.drop(1).joinToString(" ")
                    val peerId = peers.firstOrNull {
                        it.nickname == target || it.peerId == target
                    }?.peerId ?: target
                    startPrivateChat(peerId)
                    sendTextToConversation(peerId, message)
                }
                return true
            }
            "nick" -> {
                if (args.isNotEmpty()) {
                    runCatching {
                        go.examate.hyp.config.PrefsManager.getInstance(context).nickname = args[0]
                    }
                    sendAnnounce(null)
                }
                return true
            }
            "who" -> {
                val peerList = peers.joinToString("\n") {
                    "- " + it.nickname + " [" + it.transport.label +
                        if (it.hopCount > 0) " · " + it.hopCount + "跳" else "" + "]"
                }
                val convId = currentConversationId.value ?: "#" + DEFAULT_CHANNEL
                val systemMsg = MesMessage(
                    id = UUID.randomUUID().toString(),
                    sender = "系统",
                    content = "在线节点(" + peers.size + "):\n" + peerList,
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
                    content = "* " + myNickname + " " + action,
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
                    if (!isChannel) put("recipientPeerId", convId)
                }.toString()
                sendToBestTransports(json, if (isChannel) null else convId)
                addMessageToConversation(convId, msg)
                return true
            }
            "help" -> {
                val convId = currentConversationId.value ?: "#" + DEFAULT_CHANNEL
                val helpText = IrcCommand.entries.joinToString("\n") { it.usage + " — " + it.description }
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
                errorEvent.value = "静音功能暂未实现"
                return true
            }
        }
        return false
    }
}
