package go.examate.hyp.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

/**
 * Deep Learning 学习页 — 搜题 + 笔记。
 *
 * 布局与首页最初的样式一致:
 * - 标准顶栏(与项目其它页面位置统一,返回按钮左侧 40dp)
 * - 搜题大卡片:点击弹启动模式选择(无障碍/截屏)
 * - 权限状态:独立一张卡片,点击展开权限详情(与旧版一致)
 * - 笔记大卡片:进入笔记页
 */
@Composable
fun DeepLearningScreen(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    batteryGranted: Boolean,
    shizukuGranted: Boolean,
    onLaunchAccessibility: () -> Unit,
    onLaunchScreenshot: () -> Unit,
    onNotesClick: () -> Unit,
    onBack: () -> Unit
) {
    var showLaunchDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val permissions = listOf(
        Triple("悬浮窗权限", overlayGranted, "悬浮窗权限允许应用在其他应用上方显示悬浮球和答题窗口,这是本应用的核心功能,没有此权限无法显示悬浮界面。"),
        Triple("无障碍服务", accessibilityGranted, "无障碍服务用于读取屏幕上的题目文本并执行点击操作,开启后才能自动识别题目并调用 AI 解题。"),
        Triple("电池优化", batteryGranted, "关闭电池优化(设为无限制)可避免系统在后台杀掉本应用,确保悬浮窗和答题功能在长时间使用时保持稳定。"),
        Triple("Shizuku", shizukuGranted, "Shizuku 用于藏匿模式,允许应用通过 ADB 级别权限检测屏幕上的 FLAG_SECURE 窗口,实现防截屏保护。需安装 Shizuku 并授权。")
    )
    val unauthCount = permissions.count { !it.second }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // ═══ 顶栏:返回按钮(与其它页面一致,10dp 边距、不随内容区缩进) ═══
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
        }

        // ═══ 内容区 ═══
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // ═══ 权限状态卡片(搜题上方,点击展开详情,与旧版一致) ═══
            FrostedGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPermissionDialog = true },
                elevation = 0.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "权限状态",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (unauthCount == 0) "全部已授权" else "$unauthCount 项未授权",
                        color = if (unauthCount == 0) Success else Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(" ›", color = TextHint, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ═══ 搜题大卡片 ═══
            FrostedGlassCard(
                modifier = Modifier.fillMaxWidth().clickable { showLaunchDialog = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.size(14.dp))
                    Text("搜题", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("›", color = TextHint, fontSize = 22.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // ═══ 笔记大卡片 ═══
            FrostedGlassCard(
                modifier = Modifier.fillMaxWidth().clickable { onNotesClick() }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.size(14.dp))
                    Text("笔记", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("›", color = TextHint, fontSize = 22.sp)
                }
            }
        }
    }

    // ═══ 启动模式选择弹窗(与旧版一致) ═══
    if (showLaunchDialog) {
        PlainDialog(onDismissRequest = { showLaunchDialog = false }) {
            PlainDialogTitle("选择启动模式")
            PlainDialogContent("无障碍搜题:通过无障碍服务读取屏幕文本自动搜题。\n截屏搜题:通过截屏框选区域,由 AI 视觉识别题目。")
            Row(
                Modifier
                    .padding(20.dp, 8.dp, 20.dp, 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LaunchDialogButton("无障碍", isAccent = true, Modifier.weight(1f)) {
                    showLaunchDialog = false
                    onLaunchAccessibility()
                }
                LaunchDialogButton("取消", isAccent = false, Modifier.weight(1f)) {
                    showLaunchDialog = false
                }
                LaunchDialogButton("截屏", isAccent = true, Modifier.weight(1f)) {
                    showLaunchDialog = false
                    onLaunchScreenshot()
                }
            }
        }
    }

    // ═══ 权限详情弹窗(与旧版一致) ═══
    if (showPermissionDialog) {
        PlainDialog(onDismissRequest = { showPermissionDialog = false }) {
            PlainDialogTitle("权限状态")
            permissions.forEach { (name, granted, explanation) ->
                PermissionDetailRow(name, granted, explanation)
            }
            PlainDialogButtons(
                buttons = listOf(
                    Triple("知道了", true) { showPermissionDialog = false }
                )
            )
        }
    }
}

@Composable
private fun PermissionDetailRow(name: String, granted: Boolean, explanation: String) {
    val green = Success
    val red = Danger
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp, 8.dp)
                    .clip(CircleShape)
                    .background(if (granted) green else red)
            )
            Text(
                text = name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (granted) "已授权" else "未授权",
                color = if (granted) green else red,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = explanation,
            color = TextHint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 18.dp, top = 4.dp)
        )
    }
}

/** 启动模式弹窗按钮:等宽排列,取消按钮用浅灰色 */
@Composable
private fun LaunchDialogButton(
    text: String,
    isAccent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isAccent) AccentBlue else ChipBg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isAccent) Color.White else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
