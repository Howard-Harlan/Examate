package go.examate.hyp.meschat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Mesh 聊天全屏页面 — 默认 UDP 局域网传输,BLE/Wi-Fi Aware 可选增强。
 *
 * 默认仅使用 UDP 局域网通道(无需运行时权限,审核友好),
 * BLE Mesh 与 Wi-Fi Aware 作为可选增强传输,由用户在节点面板主动开启。
 *
 * 页面导航:
 * - 会话列表页([MesChatListScreen]):频道 + 私聊 + 传输状态
 * - 聊天页([MesChatScreen]):消息气泡 + 输入栏 + 附件面板
 */
class MesChatActivity : ComponentActivity() {

    var controller: MesChatController? = null

    /** 增强传输(BLE/Wi-Fi Aware)所需权限,仅在用户主动开启时申请 */
    val extraPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

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
        // Controller 现为单例,由悬浮窗服务/Activity 共享。
        // 仅当悬浮窗服务未运行时才释放,避免悬浮窗里收发消息中断。
        if (!MesChatFloatingService.isActive) {
            MesChatController.release()
        }
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
    // 用户主动开启增强传输时,展示权限说明对话框
    var showExtraPermRationale by remember { mutableStateOf(false) }
    // 待切换的目标模式(权限通过后执行)
    var pendingMode by remember { mutableStateOf<ChatMode?>(null) }

    // 增强传输权限请求(仅在用户主动开启时触发)
    val extraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ctrl = controller ?: return@rememberLauncherForActivityResult
        if (result.values.all { it }) {
            pendingMode?.let { ctrl.switchChatMode(it) }
        } else {
            ToastUtil.show(context, "未授予所需权限,无法切换到此传输模式")
        }
        pendingMode = null
    }

    // 直接创建 controller(UDP 默认可用,无需运行时权限)
    LaunchedEffect(Unit) {
        if (controller == null) {
            val ctrl = MesChatController.ensureStarted(context, initialNickname)
            ctrl.currentConversationId.value = null
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

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { compressImage(context, uri) }
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
                    val maxBytes = ctrl.maxFileSizeBytes
                    if (bytes.size > maxBytes) {
                        ToastUtil.show(context, "文件过大(当前模式限制 ${maxBytes / 1024}KB),请选择更小的文件")
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
    // 当前聊天方式(Compose 可观察)
    val currentMode by ctrl.currentMode

    // 错误事件处理
    val errorEvent by ctrl.errorEvent
    LaunchedEffect(errorEvent) {
        errorEvent?.let {
            ToastUtil.show(context, it)
            ctrl.errorEvent.value = null
        }
    }

    /** 切换聊天方式 */
    val onSwitchMode: (ChatMode) -> Unit = lambda@{ mode ->
        if (mode == ctrl.currentMode.value) return@lambda
        when (mode) {
            ChatMode.LOCAL -> {
                ctrl.switchChatMode(ChatMode.LOCAL)
            }
            ChatMode.BLE, ChatMode.WIFI_AWARE -> {
                // 蓝牙/Wi-Fi Aware 需要位置/蓝牙/附近设备权限
                val granted = activity.extraPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) {
                    ctrl.switchChatMode(mode)
                } else {
                    // 先记住目标模式,权限通过后切换
                    pendingMode = mode
                    showExtraPermRationale = true
                }
            }
            ChatMode.CLOUD -> {
                ctrl.switchChatMode(ChatMode.CLOUD)
            }
        }
    }

    if (currentConvId == null || currentConv == null) {
        // ═══ 会话列表页 ═══
        MesChatListScreen(
            conversations = ctrl.conversations,
            transportStatus = ctrl.transportStatus.value,
            peers = ctrl.peers,
            myName = initialNickname,
            currentMode = currentMode,
            onSwitchMode = onSwitchMode,
            onBack = { activity.finish() },
            onOpenConversation = { conv ->
                ctrl.openConversation(conv.id)
            },
            onJoinChannel = { showJoinDialog = true },
            onStartPrivateChat = { peer ->
                ctrl.startPrivateChat(peer.peerId)
            },
            onFloatingMode = {
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, MesChatFloatingService::class.java))
                    activity.finish()
                } else {
                    ToastUtil.show(context, "请先授权悬浮窗权限")
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
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
            transportStatus = ctrl.transportStatus.value,
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
            }
        )
    }

    // ═══ 频道加入对话框 ═══
    if (showJoinDialog) {
        JoinChannelDialog(
            onDismiss = { showJoinDialog = false },
            onJoinChannel = { channel, password ->
                ctrl.joinChannel(channel, password)
                ctrl.openConversation("#$channel")
                showJoinDialog = false
            }
        )
    }

    // ═══ 增强传输权限说明对话框 ═══
    if (showExtraPermRationale) {
        MesChatPermissionRationaleDialog(
            permissions = activity.extraPermissions,
            onConfirm = {
                showExtraPermRationale = false
                extraPermLauncher.launch(activity.extraPermissions)
            },
            onDeny = {
                showExtraPermRationale = false
                pendingMode = null
                ToastUtil.show(context, "未授予权限,无法切换到蓝牙Mesh模式(局域网仍可用)")
            }
        )
    }
}

/**
 * Mesh 聊天权限说明对话框。
 *
 * 在调起系统权限申请前,向用户逐项说明所需权限及用途,符合应用分发平台
 * "显著披露权限用途"的合规要求,同时提升用户授权意愿。
 */
@Composable
private fun MesChatPermissionRationaleDialog(
    permissions: Array<String>,
    onConfirm: () -> Unit,
    onDeny: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDeny,
        title = {
            Text(
                "Mesh 聊天所需权限",
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = go.examate.hyp.ui.theme.TextPrimary
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                permissions.forEach { perm ->
                    val (name, reason) = permissionDescription(perm)
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Text("• ", color = go.examate.hyp.ui.theme.Primary, fontSize = 13.sp)
                        androidx.compose.foundation.layout.Column {
                            Text(
                                name,
                                color = go.examate.hyp.ui.theme.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Text(
                                reason,
                                color = go.examate.hyp.ui.theme.TextHint,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "上述权限仅用于附近设备发现与局域网通信,不会收集你的位置信息或上传任何数据。",
                    color = go.examate.hyp.ui.theme.TextHint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Text(
                "同意并继续",
                color = go.examate.hyp.ui.theme.Primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onConfirm() }.padding(8.dp)
            )
        },
        dismissButton = {
            Text(
                "暂不",
                color = go.examate.hyp.ui.theme.TextHint,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onDeny() }.padding(8.dp)
            )
        }
    )
}

/** 权限名称与用途说明 */
private fun permissionDescription(perm: String): Pair<String, String> = when (perm) {
    Manifest.permission.ACCESS_FINE_LOCATION ->
        "位置信息" to "用于扫描附近的蓝牙/Wi-Fi Aware 设备(不会获取或上传你的位置坐标)"
    Manifest.permission.BLUETOOTH_SCAN ->
        "蓝牙扫描" to "发现附近开启 Mesh 聊天的设备"
    Manifest.permission.BLUETOOTH_CONNECT ->
        "蓝牙连接" to "与附近设备建立连接收发消息"
    Manifest.permission.BLUETOOTH_ADVERTISE ->
        "蓝牙广播" to "让附近设备能发现本机"
    Manifest.permission.NEARBY_WIFI_DEVICES ->
        "附近 Wi-Fi 设备" to "通过 Wi-Fi Aware 发现附近设备(无需连同一 Wi-Fi)"
    else -> perm to "Mesh 聊天所需"
}

// ════════════════════════════════════════
//  附件处理辅助函数
// ════════════════════════════════════════

/**
 * 压缩图片到 50KB 以内(适配 UDP 分片传输)。
 * 先按最大边 1024px 缩放,再逐步降低 JPEG 质量直到 ≤ 50KB。
 */
private fun compressImage(context: android.content.Context, uri: Uri): ByteArray {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return ByteArray(0)
        val bitmap = BitmapFactory.decodeStream(input)
        input.close()
        if (bitmap == null) return ByteArray(0)

        // 按最大边 1024px 缩放
        val maxEdge = 1024
        val scaled = if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        // 逐步降低质量压缩到 50KB 以内
        var quality = 80
        var bytes = ByteArrayOutputStream().use { baos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos); baos.toByteArray()
        }
        while (bytes.size > 50_000 && quality > 15) {
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
private fun readFileFromUri(context: android.content.Context, uri: Uri): Pair<String, ByteArray> {
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
