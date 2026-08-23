package go.examate.hyp.meschat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Mesh 聊天全屏页面 — 局域网 UDP 直连(主) + Nostr 中继(兜底) + P2P 直连(AP 隔离补充)。
 *
 * 权限策略:
 * - 局域网通道为 UDP 广播,无需任何运行时权限,连上 Wi-Fi 即用
 * - 默认 AUTO 模式:局域网不可用时自动走 Nostr 中继
 * - P2P 直连(可选)发现对端需附近设备/位置权限,进入 P2P 页面时按需申请
 * - 录音权限仅在使用语音消息时按需申请
 *
 * 页面导航:
 * - 主页([MesChatListScreen]):频道 Tab + 用户 Tab + 网络状态面板(模式/中继管理)
 * - 聊天页([MesChatScreen]):消息气泡 + 输入栏 + 附件面板
 */
class MesChatActivity : ComponentActivity() {

    var controller: MesChatController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val nickname = PrefsManager.getInstance(this).nickname.ifBlank { "匿名用户" }

        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    MesChatRoot(
                        activity = this,
                        initialNickname = nickname
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Controller 为引用计数单例:Activity 与悬浮窗 Service 各持有一份。
        // 无条件 release(引用计数 -1),悬浮窗仍激活时计数不为零,传输层不会停止。
        MesChatController.release()
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun MesChatRoot(
    activity: MesChatActivity,
    initialNickname: String
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MesChatController?>(null) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showKeyPairDialog by remember { mutableStateOf(false) }
    // 懒加载更早历史时的加载态
    var loadingOlder by remember { mutableStateOf(false) }

    // 创建 controller(AUTO 模式:局域网可用走 UDP,不可用自动降级云端)
    LaunchedEffect(Unit) {
        if (controller == null) {
            val ctrl = MesChatController.ensureStarted(context, initialNickname)
            ctrl.currentConversationId.value = null
            ctrl.awarenessGranted.value = true
            controller = ctrl
            activity.controller = ctrl
        }
    }

    val ctrl = controller
    if (ctrl == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
        return
    }

    val scope = rememberCoroutineScope()

    // 图片选择器(局域网按 512KB 压缩保证清晰度;云端中继压缩到 relay 上限内)
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val maxBytes = if (ctrl.isCurrentTargetNostrOnly()) 48_000 else 512_000
                val bytes = withContext(Dispatchers.IO) { compressImage(context, uri, maxBytes) }
                if (bytes.isNotEmpty()) {
                    ctrl.sendImage(bytes)
                } else {
                    ToastUtil.show(context, "图片加载失败")
                }
            }
        }
    }

    // 文件选择器
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val (name, bytes) = withContext(Dispatchers.IO) { readFileFromUri(context, uri) }
                if (bytes.isNotEmpty()) {
                    if (!ctrl.attachmentAllowed(bytes.size.toLong(), ctrl.currentConversationId.value?.takeIf { !it.startsWith("#") })) {
                        val maxBytes = ctrl.maxFileSizeBytes
                        val limitText = if (maxBytes >= 1024 * 1024) (maxBytes / 1024 / 1024).toString() + "MB" else (maxBytes / 1024).toString() + "KB"
                        ToastUtil.show(context, "文件过大(当前限制 " + limitText + "),请选择更小的文件")
                        return@launch
                    }
                    ctrl.sendFile(name, bytes)
                } else {
                    ToastUtil.show(context, "文件读取失败")
                }
            }
        }
    }

    // 观察 controller 状态
    val currentConvId by ctrl.currentConversationId
    val currentConv = ctrl.getCurrentConversation()
    val currentMode by ctrl.currentMode
    val transportStatus by ctrl.transportStatus
    val awarenessGranted by ctrl.awarenessGranted

    // 错误事件处理
    val errorEvent by ctrl.errorEvent
    LaunchedEffect(errorEvent) {
        errorEvent?.let {
            ToastUtil.show(context, it)
            ctrl.errorEvent.value = null
        }
    }

    /** 手动补启局域网通道(如 Wi-Fi 刚连接) */
    val requestAwarePermissions: () -> Unit = {
        ctrl.refreshLanUdp()
        if (ctrl.transportStatus.value.lanUdp) {
            ToastUtil.show(context, "局域网通道已启用")
        } else {
            ToastUtil.show(context, "局域网通道未就绪,请检查 Wi-Fi 连接")
        }
    }

    /** 切换聊天方式 */
    val onSwitchMode: (ChatMode) -> Unit = lambda@{ mode ->
        if (mode == ctrl.currentMode.value) return@lambda
        ctrl.switchChatMode(mode)
    }

    // ═══ P2P 直连(路由器 AP 隔离时的补充通道) ═══
    val p2pHelper = remember { WifiP2pHelper(context) }
    var showP2pDialog by remember { mutableStateOf(false) }
    var showP2pPermRationale by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { p2pHelper.release() }
    }
    val p2pPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            showP2pDialog = true
        } else {
            ToastUtil.show(context, "未授权,P2P 直连不可用;UDP 广播与云端通道不受影响")
        }
    }
    val openP2pDirect: () -> Unit = {
        p2pHelper.ensureStarted()
        if (WifiP2pHelper.hasPermissions(context)) {
            showP2pDialog = true
        } else {
            showP2pPermRationale = true
        }
    }

    if (currentConvId == null || currentConv == null) {
        // ═══ 主页 ═══
        MesChatListScreen(
            conversations = ctrl.conversations,
            transportStatus = transportStatus,
            peers = ctrl.peers,
            myName = initialNickname,
            currentMode = currentMode,
            relays = ctrl.relays,
            builtinRelays = ctrl.builtinRelays,
            awarenessGranted = awarenessGranted,
            isBridging = ctrl.isBridging,
            onSwitchMode = onSwitchMode,
            onEnableLocalMesh = requestAwarePermissions,
            onP2pDirect = openP2pDirect,
            onBack = { activity.finish() },
            onOpenConversation = { conv ->
                ctrl.openConversation(conv.id)
            },
            onJoinChannel = { showJoinDialog = true },
            onStartPrivateChat = { peer ->
                ctrl.startPrivateChat(peer.peerId)
            },
            onStartKeyPairChat = { showKeyPairDialog = true },
            onSetRelays = { urls -> ctrl.setRelays(urls) },
            onAddRelay = { url -> ctrl.addRelay(url) },
            onRemoveRelay = { url -> ctrl.removeRelay(url) },
            onResetRelays = { ctrl.resetRelays() },
            onFloatingMode = {
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, MesChatFloatingService::class.java))
                    activity.finish()
                } else {
                    ToastUtil.show(context, "请先授权悬浮窗权限")
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.packageName)
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        ToastUtil.show(context, "无法打开悬浮窗设置页,请到系统设置授权")
                    }
                }
            }
        )
    } else {
        // ═══ 聊天页 ═══
        val convId = currentConvId!!
        LaunchedEffect(convId) {
            ctrl.clearUnread(convId)
        }

        MesChatScreen(
            conversation = currentConv,
            messages = ctrl.getCurrentMessages(),
            transportStatus = transportStatus,
            inputText = ctrl.inputText.value,
            currentMode = currentMode,
            onSwitchMode = onSwitchMode,
            onBack = { ctrl.backToList() },
            onInputChange = { ctrl.inputText.value = it },
            onSendText = { ctrl.sendText() },
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRecordVoice = {},
            onPickFile = {
                filePicker.launch("*/*")
            },
            onPlayVoice = {},
            onOpenImage = {},
            onOpenFile = {},
            onSendVoice = { bytes, duration ->
                ctrl.sendVoice(bytes, duration)
            },
            hasMoreHistory = ctrl.hasMoreOlder[convId] ?: false,
            isLoadingOlder = loadingOlder,
            onLoadOlder = {
                if (!loadingOlder) {
                    loadingOlder = true
                    ctrl.loadOlderHistory(convId)
                    loadingOlder = false
                }
            }
        )
    }

    // ═══ 频道加入对话框 ═══
    if (showJoinDialog) {
        JoinChannelDialog(
            onDismiss = { showJoinDialog = false },
            onJoinChannel = { channel, password ->
                ctrl.joinChannel(channel, password)
                ctrl.openConversation("#" + channel)
                showJoinDialog = false
            }
        )
    }

    // ═══ 密钥匹对对话框 ═══
    if (showKeyPairDialog) {
        KeyPairChatDialog(
            onDismiss = { showKeyPairDialog = false },
            onConfirm = { key ->
                val roomId = ctrl.createKeyPairChat(key)
                ctrl.openKeyPairChat(roomId)
                showKeyPairDialog = false
            }
        )
    }

    // ═══ P2P 直连权限说明 ═══
    if (showP2pPermRationale) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showP2pPermRationale = false },
            title = {
                Text("启用 P2P 直连", fontWeight = FontWeight.SemiBold, color = TextPrimary)
            },
            text = {
                Text(
                    "路由器开启 AP 隔离时,P2P 直连可让设备点对点组网、绕过路由器通信。" +
                        "系统要求授予\"" + (if (android.os.Build.VERSION.SDK_INT >= 33) "附近设备" else "位置") +
                        "\"权限,仅用于发现附近的 Wi-Fi Direct 设备,不会获取你的位置坐标。" +
                        "不授权不影响 UDP 广播与云端中继。",
                    color = TextHint, fontSize = 13.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Text(
                    "同意并继续",
                    color = Primary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable {
                            showP2pPermRationale = false
                            p2pPermLauncher.launch(WifiP2pHelper.requiredPermissions())
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "暂不",
                    color = TextHint,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { showP2pPermRationale = false }.padding(8.dp)
                )
            }
        )
    }

    // ═══ P2P 直连面板 ═══
    if (showP2pDialog) {
        WifiP2pDirectDialog(
            helper = p2pHelper,
            onDismiss = { showP2pDialog = false }
        )
    }
}

// ════════════════════════════════════════
//  P2P 直连面板
// ════════════════════════════════════════

/**
 * P2P 直连对话框:未组网时列出附近设备供连接;已组网时展示组状态并可断开。
 * 组建立后,组内设备经局域网通道自动出现,无需在此页停留。
 */
@Composable
private fun WifiP2pDirectDialog(
    helper: WifiP2pHelper,
    onDismiss: () -> Unit
) {
    val peers = helper.discoveredPeers
    val groupFormed by helper.groupFormed
    val groupSummary by helper.groupSummary
    val isDiscovering by helper.isDiscovering
    val notice by helper.notice

    // 打开即开始发现附近设备
    LaunchedEffect(Unit) {
        if (!groupFormed) helper.startDiscovery()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .padding(20.dp)
        ) {
            Text("P2P 直连", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "路由器隔离设备间通信时使用:两台设备点对点组网,绕过路由器。组内消息仍走局域网通道,可与 Wi-Fi 并存。",
                color = TextHint, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(14.dp))

            if (groupFormed) {
                // ═══ 已组网:状态 + 断开 ═══
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Success))
                    Spacer(Modifier.width(8.dp))
                    Text("P2P 组已建立", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                groupSummary?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = TextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "组内设备将通过局域网通道自动出现,可关闭此页正常聊天",
                    color = TextHint, fontSize = 11.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Danger.copy(alpha = 0.12f))
                            .clickable { helper.disconnectGroup() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("断开 P2P 组", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SelectedBg)
                            .clickable { onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("完成", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                // ═══ 未组网:发现 + 连接 ═══
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDiscovering) SurfaceVariant else SelectedBg)
                            .clickable(enabled = !isDiscovering) { helper.startDiscovery() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isDiscovering) "发现中…" else "重新发现",
                            color = if (isDiscovering) TextHint else Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    if (isDiscovering) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            color = Primary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (peers.isEmpty()) {
                    Text(
                        if (isDiscovering) "正在搜索附近设备…" else "暂无附近设备,对方也需打开此页面",
                        color = TextHint, fontSize = 12.sp
                    )
                } else {
                    peers.forEach { device ->
                        val name = device.deviceName.ifBlank {
                            "设备 " + device.deviceAddress.takeLast(6)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceVariant)
                                .clickable { helper.connect(device) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    device.deviceAddress,
                                    color = TextHint, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text("连接", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                notice?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = TextHint, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceVariant)
                            .clickable { onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("关闭", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}


// ════════════════════════════════════════
//  附件处理辅助函数
// ════════════════════════════════════════

/**
 * 压缩图片到指定字节数以内。
 * 先按最大边 1600px 缩放,再逐步降低 JPEG 质量直到 ≤ maxBytes。
 * 局域网 UDP 直连可保留较高画质(512KB);云端中继受事件上限约束(48KB)。
 */
private fun compressImage(context: Context, uri: Uri, maxBytes: Int): ByteArray {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return ByteArray(0)
        val bitmap = BitmapFactory.decodeStream(input)
        input.close()
        if (bitmap == null) return ByteArray(0)

        val maxEdge = 1600
        val scaled = if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        var quality = 90
        var bytes = ByteArrayOutputStream().use { baos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos); baos.toByteArray()
        }
        while (bytes.size > maxBytes && quality > 20) {
            quality -= 10
            bytes = ByteArrayOutputStream().use { baos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos); baos.toByteArray()
            }
        }
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        return bytes
    } catch (e: Exception) {
        return ByteArray(0)
    }
}

/** 从 URI 读取文件,返回文件名和字节数组 */
private fun readFileFromUri(context: Context, uri: Uri): Pair<String, ByteArray> {
    var fileName = "未知文件"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: return fileName to ByteArray(0)
        val bytes = input.readBytes()
        input.close()
        return fileName to bytes
    } catch (e: Exception) {
        return fileName to ByteArray(0)
    }
}
