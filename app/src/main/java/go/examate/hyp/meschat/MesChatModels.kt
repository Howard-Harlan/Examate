package go.examate.hyp.meschat

import java.util.Date

/**
 * Mesh 聊天数据模型 — 重构后的双通道架构(局域网 UDP 直连 + Nostr 中继)。
 */

/** 私聊类型 */
enum class PrivateChatKind(val label: String) {
    /** 直接私聊:点对点,经局域网 UDP 单播或 NIP-04 加密 DM */
    DIRECT("直接私聊"),
    /** 密钥匹对:双方输入相同配对密钥,消息以该密钥加密,持有相同密钥者才能解密 */
    KEY_PAIR("密钥匹对")
}

/** Mesh 节点(对端设备,本地或云端) */
data class MeshPeer(
    val peerId: String,
    val nickname: String,
    val transport: TransportType = TransportType.NONE,
    val lastSeen: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val hopCount: Int = 0,      // 中继跳数,0=直连(或云端)
    val isBridge: Boolean = false  // 是否为桥接节点(有消息经它中继)
)

/** 消息类型 */
enum class MesMessageType { TEXT, IMAGE, VOICE, FILE }

/** 送达状态 */
enum class DeliveryState { SENDING, SENT, DELIVERED, READ, FAILED }

/** 会话类型 */
enum class ConversationType { CHANNEL, PRIVATE }

/**
 * 聊天消息 — UI 层模型。
 */
data class MesMessage(
    val id: String,
    val sender: String,
    val senderPeerId: String? = null,
    val content: String,
    val type: MesMessageType = MesMessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = false,
    val isRelay: Boolean = false,       // 经桥接节点中继的消息
    val originalSender: String? = null, // 中继时的原始发送者
    val channel: String? = null,        // 频道名(#xxx),私聊为 null
    val isPrivate: Boolean = false,
    val recipientName: String? = null,
    val deliveryState: DeliveryState? = null,
    val imagePath: String? = null,
    val voicePath: String? = null,
    val voiceDuration: Int = 0,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val filePath: String? = null,
    /** 消息实际经由的传输通道 */
    val via: TransportType = TransportType.NONE,
    /** 消息经过的跳数(0=直连/云端) */
    val hopCount: Int = 0,
    /** 密钥匹对房间 ID(仅密钥匹对私聊) */
    val roomId: String? = null
)

/**
 * 会话条目 — 频道或私聊。
 */
data class ChatConversation(
    val id: String,                       // 频道:#xxx;直接私聊:peerId;密钥匹对:"kp:"+roomId
    val type: ConversationType,
    val title: String,                    // 显示名
    val lastMessage: String = "",
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
    val peerCount: Int = 0,
    val isPasswordProtected: Boolean = false,
    val transport: TransportType = TransportType.NONE,
    /** 私聊类型(频道为 null) */
    val privateChatKind: PrivateChatKind? = null,
    /** 密钥匹对房间 ID */
    val roomId: String? = null
) {
    val isChannel get() = type == ConversationType.CHANNEL
    val isKeyPairChat get() = privateChatKind == PrivateChatKind.KEY_PAIR
}

/**
 * 传输状态汇总。
 * - lanUdp: 局域网 UDP 通道是否已启动
 * - nostr: 云端中继通道是否已启动
 * - relayConnected/relayTotal: 中继连接数(用于"中继 2/3"展示)
 */
data class TransportStatus(
    val lanUdp: Boolean = false,
    val nostr: Boolean = false,
    val relayConnected: Int = 0,
    val relayTotal: Int = 0,
    val peerCount: Int = 0,
    val localPeerCount: Int = 0,
    val nostrPeerCount: Int = 0,
    /** 兼容字段:UDP 无 NDP 数据链路,恒为 0 */
    val dataLinks: Int = 0
) {
    val activeTransports: List<TransportType>
        get() = buildList {
            if (lanUdp) add(TransportType.LAN_UDP)
            if (nostr) add(TransportType.NOSTR)
        }
    val isAnyActive get() = lanUdp || nostr
    val primaryTransport get() = activeTransports.firstOrNull() ?: TransportType.NONE
    val isDual get() = lanUdp && nostr
}

/**
 * 聊天方式。
 * - AUTO: 默认。局域网 UDP 优先(同 Wi-Fi 低延迟、不经云端),
 *   不可行时自动走 Nostr 中继兜底
 * - LAN_UDP: 仅局域网直连,不经过云端(需连同一 Wi-Fi)
 * - NOSTR: 仅云端中继,可跨网络通信
 */
enum class ChatMode(val displayName: String, val desc: String) {
    AUTO("自动", "优先局域网 UDP 直连,不可用时走 Nostr 中继"),
    LAN_UDP("仅局域网", "仅向同一 Wi-Fi 局域网广播,不经过云端"),
    NOSTR("仅云端中继", "只使用 Nostr 中继服务器,可跨网络通信")
}

/** 中继服务器条目 */
data class RelayServer(
    val url: String,
    val isBuiltin: Boolean = false
)
