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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle

/**
 * 慎思页:启动入口 + AI 聊天
 *
 * - 启动胶囊:点击选择启动模式(无障碍搜题 / 截屏搜题)
 * - AI 聊天:进入独立聊天页
 */
@Composable
fun ShensiScreen(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    batteryGranted: Boolean,
    shizukuGranted: Boolean,
    onLaunchAccessibility: () -> Unit,
    onLaunchScreenshot: () -> Unit,
    onChatClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLaunchDialog by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
            .padding(bottom = 120.dp)
    ) {
        // ═══ Hero 卡片 ═══
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "Examate",
                    color = TextPrimary,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.8).sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ═══ 滚动提示卡片(logo 与权限状态之间) ═══
        TipStackCard()

        Spacer(Modifier.height(14.dp))

        // ═══ 权限状态卡片 ═══
        PermissionCard(
            overlayGranted = overlayGranted,
            accessibilityGranted = accessibilityGranted,
            batteryGranted = batteryGranted,
            shizukuGranted = shizukuGranted
        )

        Spacer(Modifier.height(18.dp))

        // ═══ 启动胶囊:点击选择启动模式 ═══
        LiquidGlassButton(
            text = "启动",
            onClick = { showLaunchDialog = true },
            style = ButtonStyle.White,
            height = 52.dp
        )

        Spacer(Modifier.height(10.dp))

        // ═══ Agents 入口 ═══
        LiquidGlassButton(
            text = "Agents",
            onClick = onChatClick,
            style = ButtonStyle.White,
            height = 52.dp
        )
    }

    // 启动模式选择弹窗
    if (showLaunchDialog) {
        PlainDialog(onDismissRequest = { showLaunchDialog = false }) {
            PlainDialogTitle("选择启动模式")
            PlainDialogContent("无障碍搜题:通过无障碍服务读取屏幕文本自动搜题。\n截屏搜题:通过截屏框选区域,由 AI 视觉识别题目。")
            PlainDialogButtons(
                buttons = listOf(
                    Triple("无障碍搜题", true) {
                        showLaunchDialog = false
                        onLaunchAccessibility()
                    },
                    Triple("截屏搜题", true) {
                        showLaunchDialog = false
                        onLaunchScreenshot()
                    }
                )
            )
        }
    }
}

private data class PermissionItem(
    val name: String,
    val granted: Boolean,
    val explanation: String
)

@Composable
private fun PermissionCard(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    batteryGranted: Boolean,
    shizukuGranted: Boolean
) {
    var showDialog by remember { mutableStateOf(false) }

    val items = remember(overlayGranted, accessibilityGranted, batteryGranted, shizukuGranted) {
        listOf(
            PermissionItem("悬浮窗权限", overlayGranted, "悬浮窗权限允许应用在其他应用上方显示悬浮球和答题窗口,这是本应用的核心功能,没有此权限无法显示悬浮界面。"),
            PermissionItem("无障碍服务", accessibilityGranted, "无障碍服务用于读取屏幕上的题目文本并执行点击操作,开启后才能自动识别题目并调用 AI 解题。"),
            PermissionItem("电池优化", batteryGranted, "关闭电池优化(设为无限制)可避免系统在后台杀掉本应用,确保悬浮窗和答题功能在长时间使用时保持稳定。"),
            PermissionItem("Shizuku", shizukuGranted, "Shizuku 用于藏匿模式,允许应用通过 ADB 级别权限检测屏幕上的 FLAG_SECURE 窗口,实现防截屏保护。需安装 Shizuku 并授权。")
        ).sortedBy { !it.granted }
    }

    val unauthCount = items.count { !it.granted }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        tint = Color.White,
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
                color = if (unauthCount == 0) Color(0xFF34C759) else Danger,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = " ›",
                color = TextHint,
                fontSize = 16.sp
            )
        }
    }

    // 权限详情弹窗(白底不透明,避免液态玻璃叠加 dim 导致可读性差)
    if (showDialog) {
        PlainDialog(onDismissRequest = { showDialog = false }) {
            PlainDialogTitle("权限状态")
            items.forEach { item ->
                PermissionDetailRow(item)
            }
            PlainDialogButtons(
                buttons = listOf(
                    Triple("知道了", true) { showDialog = false }
                )
            )
        }
    }
}

@Composable
private fun PermissionDetailRow(item: PermissionItem) {
    val green = Color(0xFF34C759)
    val red = Danger
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp, 8.dp)
                    .clip(CircleShape)
                    .background(if (item.granted) green else red)
            )
            Text(
                text = item.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (item.granted) "已授权" else "未授权",
                color = if (item.granted) green else red,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = item.explanation,
            color = TextHint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 18.dp, top = 4.dp)
        )
    }
}

private data class Tip(val title: String, val content: String)

private val Tips = listOf(
    Tip("剪切板权限", "请授权写入剪切板权限,并将省电策略改为无限制"),
    Tip("双击关闭", "双击小白球可快速关闭窗口"),
    Tip("快速启动", "推荐在控制中心添加快速启动入口"),
    Tip("藏匿模式", "藏匿模式已移至设置页的 Shizuku 设置中,需 Shizuku 授权"),
    Tip("长按退出", "长按悬浮球 3 秒可直接退出藏匿模式")
)

@Composable
private fun TipStackCard() {
    val pagerState = rememberPagerState(pageCount = { Tips.size })

    // 自动滚动:每 5 秒切下一张,最后一张回到第一张
    LaunchedEffect(pagerState) {
        while (true) {
            delay(5000)
            val next = if (pagerState.currentPage == Tips.lastIndex) 0 else pagerState.currentPage + 1
            pagerState.animateScrollToPage(next)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(120.dp),
            pageSpacing = 12.dp
        ) { page ->
            val tip = Tips[page]
            FrostedGlassCard(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                tint = Color.White.copy(alpha = 1f)
            ) {
                Column(
                    Modifier.fillMaxSize().background(Color.White).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(tip.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(tip.content, color = TextHint, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.weight(1f))
                    // 指示器移入卡片内部,弱化样式
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Tips.indices.forEach { i ->
                            Box(
                                Modifier
                                    .padding(horizontal = 2.dp)
                                    .size(if (i == pagerState.currentPage) 5.dp else 3.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i == pagerState.currentPage)
                                            TextHint.copy(alpha = 0.5f)
                                        else
                                            TextHint.copy(alpha = 0.15f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
