package go.examate.hyp.meschat

import android.content.Context
import java.util.UUID

/**
 * 传输通道类型 — 重构后仅保留两种通道:
 * - [LAN_UDP]: Wi-Fi 局域网 UDP 广播直连,低延迟,不依赖互联网(需连同一 Wi-Fi)
 * - [NOSTR]: Nostr 中继代理,跨网络可连接,作为局域网不可行时的兜底通道
 */
enum class TransportType(val label: String) {
    LAN_UDP("局域网"),
    NOSTR("Nostr 中继"),
    NONE("未连接")
}

/**
 * 传输层统一接口 — [MesChatController] 通过该接口透明接入各传输通道。
 *
 * 实现方:
 * - [UdpLanTransport]: Wi-Fi 局域网 UDP 广播
 * - [NostrTransport]: 云端中继代理
 */
interface MeshTransport {

    /** 传输层回调(主线程触发) */
    interface Callback {
        /**
         * 对端节点上线。
         * @param hopCount 距对端的跳数,0=直连,>0=经桥接节点中继
         */
        fun onPeerConnected(peerId: String, nickname: String, via: TransportType, hopCount: Int = 0)

        /** 对端节点离线 */
        fun onPeerDisconnected(peerId: String, via: TransportType)

        /**
         * 收到一条完整消息(已分片重组、已脱掉网状信封)。
         * @param hopCount 消息经过的跳数(0=直连)
         */
        fun onDataReceived(json: String, fromPeerId: String, via: TransportType, hopCount: Int = 0)

        /** 传输层运行时错误(预检失败不应上报,由 [isAvailable] 表达) */
        fun onError(error: String)
    }

    /** 本传输通道类型 */
    val type: TransportType

    /**
     * 启动传输。预检失败(权限/硬件/网络不满足)时静默返回 false,
     * 由上层控制器决定降级策略。
     * @return true=已启动(或此前已运行);false=预检失败未启动
     */
    fun start(): Boolean

    /** 停止传输并释放资源 */
    fun stop()

    /** 传输是否正在运行 */
    fun isRunning(): Boolean

    /** 通道当前是否可用(权限已授 + 硬件支持 + 网络就绪),不实际启动 */
    fun isAvailable(): Boolean

    /**
     * 发送一条 JSON 消息。
     * @param targetPeerId 目标节点;null=广播(频道消息/配对房间消息)
     */
    fun sendMessage(json: String, targetPeerId: String? = null)
}

/**
 * 本机 Mesh 身份:持久化的 peerId。
 *
 * 独立为无传输依赖的身份对象,App 重启后身份保持稳定,
 * 避免每次启动生成新 ID 导致对端残留旧 peer/私聊身份错乱。
 */
object MeshIdentity {
    private const val PREFS_NAME = "meschat_prefs"
    private const val KEY_PEER_ID = "peer_id"

    @Volatile
    private var cached: String? = null

    /** 获取本机 peerId(8 字符随机 hex,持久化) */
    fun peerId(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_PEER_ID, null)
                ?: UUID.randomUUID().toString().replace("-", "").take(8)
                    .also { prefs.edit().putString(KEY_PEER_ID, it).apply() }
            cached = id
            return id
        }
    }
}
