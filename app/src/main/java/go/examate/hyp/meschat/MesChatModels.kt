package go.examate.hyp.meschat

import java.util.Date

/**
 * Mesh 聊天数据模型 — 与 bitchat 底层对接的 UI 层数据结构。
 * 后续移植 bitchat 传输层后,这些模型由 transport 回调填充。
 */

/** 传输通道类型 */
enum class TransportType(val label: String) {
    BLE("蓝牙"),
    WIFI_AWARE("Wi-Fi Aware"),
    NOSTR("Nostr"),
    NONE("未连接")
}

/** Mesh 节点(对端设备) */
data class MeshPeer(
    val peerId: String,
    val nickname: String,
    val transport: TransportType = TransportType.NONE,
    val lastSeen: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val hopCount: Int = 0  // 中继跳数,0=直连
)

/** 消息类型 */
enum class MesMessageType { TEXT, IMAGE, VOICE, FILE }

/** 送达状态 */
enum class DeliveryState { SENDING, SENT, DELIVERED, READ, FAILED }

/** 会话类型 */
enum class ConversationType { CHANNEL, PRIVATE }

/**
 * 聊天消息 — UI 层模型,字段对应 bitchat BitchatMessage 的子集。
 * 后续 transport 层接入后,从 BitchatMessage 映射到此模型。
 */
data class MesMessage(
    val id: String,
    val sender: String,
    val senderPeerId: String? = null,
    val content: String,
    val type: MesMessageType = MesMessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = false,
    val isRelay: Boolean = false,       // 中继消息
    val originalSender: String? = null,  // 中继时的原始发送者
    val channel: String? = null,         // 频道名(#xxx),私聊为 null
    val isPrivate: Boolean = false,
    val recipientName: String? = null,
    val deliveryState: DeliveryState? = null,
    val imagePath: String? = null,       // 图片消息的本地路径
    val voicePath: String? = null,       // 语音消息的本地路径
    val voiceDuration: Int = 0,           // 语音时长(秒)
    val fileName: String? = null,         // 文件消息的文件名
    val fileSize: Long = 0                // 文件大小(字节)
)

/**
 * 会话条目 — 频道或私聊,用于会话列表页展示。
 */
data class ChatConversation(
    val id: String,                       // 频道:#xxx;私聊:peerId
    val type: ConversationType,
    val title: String,                    // 显示名
    val lastMessage: String = "",         // 最后一条消息预览
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
    val peerCount: Int = 0,               // 频道成员数;私聊固定 1
    val isPasswordProtected: Boolean = false,
    val transport: TransportType = TransportType.NONE  // 私聊对端的传输通道
) {
    val isChannel get() = type == ConversationType.CHANNEL
}

/** 传输状态汇总 */
data class TransportStatus(
    val ble: Boolean = false,
    val wifiAware: Boolean = false,
    val nostr: Boolean = false,
    val peerCount: Int = 0
) {
    val activeTransports: List<TransportType>
        get() = buildList {
            if (ble) add(TransportType.BLE)
            if (wifiAware) add(TransportType.WIFI_AWARE)
            if (nostr) add(TransportType.NOSTR)
        }
    val isAnyActive get() = ble || wifiAware || nostr
    val primaryTransport get() = activeTransports.firstOrNull() ?: TransportType.NONE
}
