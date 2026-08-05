package go.examate.hyp.meschat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

/**
 * Mesh 聊天全屏页面 — 基于 BLE Mesh 传输。
 *
 * 取代旧版 [go.examate.hyp.LanChatActivity](UDP 局域网聊天),
 * 采用 BLE Mesh 实现,无需同一 Wi-Fi 即可通信。
 *
 * 页面导航:
 * - 会话列表页([MesChatListScreen]):频道 + 私聊 + 传输状态
 * - 聊天页([MesChatScreen]):消息气泡 + 输入栏 + 附件面板
 */
class MesChatActivity : ComponentActivity() {

    var controller: MesChatController? = null

    val requiredPermissions: Array<String>
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
    var hasPermission by remember {
        mutableStateOf(
            activity.requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var controller by remember { mutableStateOf<MesChatController?>(null) }
    var showJoinDialog by remember { mutableStateOf(false) }
    // 是否展示权限说明对话框(首次且未授权时展示)
    var showPermRationale by remember {
        mutableStateOf(
            !hasPermission &&
            !activity.requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.all { it }
        if (!hasPermission) {
            ToastUtil.show(context, "需要蓝牙和定位权限才能使用 Mesh 聊天")
        }
    }

    // 请求权限(仅在用户确认说明对话框后触发)
    LaunchedEffect(showPermRationale) {
        if (!showPermRationale && !hasPermission) {
            permissionLauncher.launch(activity.requiredPermissions)
        }
    }

    // 权限通过后创建 controller(单例,跨 Activity/悬浮窗复用)
    LaunchedEffect(hasPermission) {
        if (hasPermission && controller == null) {
            val ctrl = MesChatController.ensureStarted(context, initialNickname)
            // 任务A.1:进入 MesChatActivity 默认显示会话列表页(不自动打开附近会话)
            ctrl.currentConversationId.value = null
            controller = ctrl
            activity.controller = ctrl
        }
    }

    val ctrl = controller
    if (ctrl == null) {
        // 等待权限:展示权限说明对话框(若需要)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
        if (showPermRationale) {
            MesChatPermissionRationaleDialog(
                permissions = activity.requiredPermissions,
                onConfirm = { showPermRationale = false },
                onDeny = {
                    showPermRationale = false
                    ToastUtil.show(context, "未授予权限,无法使用 Mesh 聊天")
                }
            )
        }
        return
    }

    // 观察 controller 状态
    val currentConvId by ctrl.currentConversationId
    val currentConv = ctrl.getCurrentConversation()

    // 错误事件处理
    val errorEvent by ctrl.errorEvent
    LaunchedEffect(errorEvent) {
        errorEvent?.let {
            ToastUtil.show(context, it)
            ctrl.errorEvent.value = null
        }
    }

    if (currentConvId == null || currentConv == null) {
        // ═══ 会话列表页 ═══
        MesChatListScreen(
            conversations = ctrl.conversations,
            transportStatus = ctrl.transportStatus.value,
            peers = ctrl.peers,
            myName = initialNickname,
            onBack = { activity.finish() },
            onOpenConversation = { conv ->
                ctrl.openConversation(conv.id)
            },
            onJoinChannel = { showJoinDialog = true },
            onStartPrivateChat = { peer ->
                ctrl.startPrivateChat(peer.peerId)
            },
            onFloatingMode = {
                // 任务A.2:启动悬浮窗服务,在悬浮窗内显示附近聊天
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, MesChatFloatingService::class.java))
                    // 关闭 Activity,让悬浮窗接管(单例 controller 仍在后台运行)
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
        // 清除未读
        val convId = currentConvId!!  // 此分支中 currentConvId 非 null
        LaunchedEffect(convId) {
            ctrl.clearUnread(convId)
        }

        MesChatScreen(
            conversation = currentConv,
            messages = ctrl.getCurrentMessages(),
            transportStatus = ctrl.transportStatus.value,
            inputText = ctrl.inputText.value,
            onBack = { ctrl.backToList() },
            onInputChange = { ctrl.inputText.value = it },
            onSendText = { ctrl.sendText() },
            onPickImage = {
                ToastUtil.show(context, "图片消息开发中")
            },
            onRecordVoice = {
                ToastUtil.show(context, "语音消息开发中")
            },
            onPickFile = {
                ToastUtil.show(context, "文件消息开发中")
            },
            onPlayVoice = { msg ->
                ToastUtil.show(context, "语音播放开发中")
            },
            onOpenImage = { msg ->
                ToastUtil.show(context, "图片查看开发中")
            },
            onOpenFile = { msg ->
                ToastUtil.show(context, "文件查看开发中")
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
