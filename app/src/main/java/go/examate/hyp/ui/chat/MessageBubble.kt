package go.examate.hyp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ActionCard
import go.examate.hyp.util.ChatMsg
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "MessageBubble"

@Composable
fun MessageBubble(
    msg: ChatMsg,
    timeFmt: SimpleDateFormat,
    context: Context,
    onQuickFollowUp: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBranch: () -> Unit = {},
    onSaveToNote: (String) -> Unit = {},
    onQiushi: (String) -> Unit = {},
    showActions: Boolean = false,
    isStreaming: Boolean = false
) {
    val isUser = msg.role == "user"
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Column(
        if (isUser) Modifier.wrapContentWidth(Alignment.End) else Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box {
            BubbleContent(
                msg = msg,
                isUser = isUser,
                isStreaming = isStreaming,
                context = context,
                onQuickFollowUp = onQuickFollowUp,
                onLongPress = {
                    if (!isStreaming) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
            )
            // 自定义长按菜单(液态玻璃风格):复制 / 保存到笔记 / 求是 / 追问(开分支) / 重新生成(后两项仅助手消息)
            MessageLongPressMenu(
                expanded = menuOpen,
                isAssistant = !isUser && !msg.isError,
                onDismiss = { menuOpen = false },
                onCopy = { copyMessageText(context, msg.content); menuOpen = false },
                onSaveToNote = { menuOpen = false; onSaveToNote(msg.content) },
                onQiushi = { menuOpen = false; onQiushi(msg.content) },
                onBranch = { menuOpen = false; onBranch() },
                onRegenerate = { menuOpen = false; onRegenerate() }
            )
        }

        // 消息操作行(复制 / 追问 / 重新生成 / 删除):仅在非流式且需要展示时出现
        if (showActions && !isStreaming) {
            MessageActionRow(
                isUser = isUser,
                onCopy = { copyMessageText(context, msg.content) },
                onBranch = onBranch,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                modifier = Modifier.padding(
                    start = if (isUser) 0.dp else 4.dp,
                    end = if (isUser) 4.dp else 0.dp,
                    top = 2.dp
                )
            )
        }

        Text(
            text = timeFmt.format(Date(msg.time)),
            color = TextSecondary.copy(alpha = 0.7f),
            fontSize = 10.sp,
            modifier = Modifier.padding(
                start = if (isUser) 0.dp else 4.dp,
                end = if (isUser) 4.dp else 0.dp,
                top = 2.dp
            )
        )
    }
}

/**
 * 自定义长按消息菜单(液态玻璃风格):
 * 复制 → 保存到笔记 → 求是 → 追问(仅助手) → 重新生成(仅助手)
 * 使用 DropdownMenu + 半透明白容器 + 阴影 + 圆角,视觉接近液态玻璃
 */
@Composable
private fun MessageLongPressMenu(
    expanded: Boolean,
    isAssistant: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSaveToNote: () -> Unit,
    onQiushi: () -> Unit,
    onBranch: () -> Unit,
    onRegenerate: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Color.White.copy(0.92f),
        shadowElevation = 12.dp
    ) {
        GlassMenuItem(icon = Icons.Filled.ContentCopy, label = "复制", onClick = onCopy)
        GlassMenuItem(icon = Icons.Filled.NoteAdd, label = "保存到笔记", onClick = onSaveToNote)
        GlassMenuItem(icon = Icons.Filled.HelpOutline, label = "求是", onClick = onQiushi)
        if (isAssistant) {
            GlassMenuItem(icon = Icons.Filled.Reply, label = "追问", onClick = onBranch)
            GlassMenuItem(icon = Icons.Filled.Refresh, label = "重新生成", onClick = onRegenerate)
        }
    }
}

/** 液态玻璃菜单项:图标 + 文字 */
@Composable
private fun GlassMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 14.sp, color = TextPrimary)
            }
        },
        onClick = onClick
    )
}

@Composable
private fun BubbleContent(
    msg: ChatMsg,
    isUser: Boolean,
    isStreaming: Boolean,
    context: Context,
    onQuickFollowUp: (String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
    contentAlignment: Alignment
) {
    BoxWithConstraints(
        modifier,
        contentAlignment = contentAlignment
    ) {
        val bubbleMaxWidth = maxWidth * 0.95f
        Column(
            Modifier
                .widthIn(max = bubbleMaxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp
                    )
                )
                .background(
                    when {
                        msg.isError -> ErrorBg
                        isUser -> AccentBlue
                        else -> CardBg
                    }
                )
                .then(
                    if (!isUser && !msg.isError) {
                        Modifier.border(
                            0.5.dp,
                            Color(0x15000000),
                            RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            UserImage(msg, context)
            AssistantImage(msg)
            ToolStepsSection(msg)
            MessageContent(msg, isUser, isStreaming)
            if (msg.cards.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                msg.cards.forEach { card -> ActionCardView(card) }
            }
            if (!isUser && msg.filePath != null) {
                Spacer(Modifier.height(8.dp))
                FilePreviewCard(msg.filePath)
            }
        }
    }
}

/**
 * 工具调用展示:优先用新的 ToolStepsTimeline(链式思考容器),
 * 旧消息(toolCalls 字符串列表)回退到简陋的行展示。
 */
@Composable
private fun ToolStepsSection(msg: ChatMsg) {
    if (msg.toolSteps.isNotEmpty()) {
        ToolStepsTimeline(steps = msg.toolSteps)
        if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
    } else if (msg.toolCalls.isNotEmpty()) {
        ToolCalls(msg)
    }
}

@Composable
private fun UserImage(msg: ChatMsg, context: Context) {
    // 兼容旧单图 imageUriString;多图横向排列,单图大图展示
    val uris = msg.imageUris.ifEmpty { listOfNotNull(msg.imageUriString) }
    if (uris.isEmpty()) return
    if (uris.size == 1) {
        UserImageOne(uris[0], context, Modifier.fillMaxWidth().height(180.dp))
    } else {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            uris.forEach { UserImageOne(it, context, Modifier.size(110.dp)) }
        }
    }
    if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
}

@Composable
private fun UserImageOne(uriStr: String, context: Context, modifier: Modifier) {
    var bitmap by remember(uriStr) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uriStr) {
        bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                    val sample = (opts.outWidth / 720).coerceAtLeast(1)
                    context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { s2 ->
                        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        android.graphics.BitmapFactory.decodeStream(s2, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "图片",
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun AssistantImage(msg: ChatMsg) {
    val b64 = msg.imageBase64 ?: return
    var bitmap by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(b64) {
        bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "AI 生成图片",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth
        )
        if (msg.content.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ToolCalls(msg: ChatMsg) {
    if (msg.toolCalls.isEmpty()) return
    msg.toolCalls.forEach { call ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(ChipBg)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("•", color = AccentBlue, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                call,
                color = AccentBlue,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (msg.content.isNotEmpty()) Spacer(Modifier.height(6.dp))
}

@Composable
private fun MessageContent(msg: ChatMsg, isUser: Boolean, isStreaming: Boolean = false) {
    if (msg.content.isEmpty()) return
    if (isUser) {
        Text(
            text = msg.content,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    } else {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        MarkdownText(
            markdown = msg.content,
            textColor = if (msg.isError) Danger else TextPrimary,
            fontSize = go.examate.hyp.config.PrefsManager.getInstance(ctx).chatFontSize,
            fontFamily = go.examate.hyp.util.ReadingFont.family(ctx),
            isStreaming = isStreaming
        )
    }
}

@Composable
internal fun ActionCardView(card: ActionCard) {
    AnimatedCardAppear {
        when (card.type) {
            "ecard_balance" -> BalanceCard(card)
            "campus_schedule" -> ScheduleCard(card)
            else -> GenericActionCard(card)
        }
    }
}

/** 卡片入场微动效:淡入 + 轻微上滑(220ms),让结果卡片出现得更自然 */
@Composable
private fun AnimatedCardAppear(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "cardAppear"
    )
    val slidePx = with(LocalDensity.current) { 8.dp.toPx() }
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * slidePx
        }
    ) {
        content()
    }
}

/** 一卡通余额:承载数据的富卡片(大号金额 + 账户名) */
@Composable
private fun BalanceCard(card: ActionCard) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = Color(0xFF34C759)
    val amount = card.payload["amount"] ?: card.summary.removePrefix("¥")
    val account = card.payload["account"].orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(0.7.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .clickable { openCampusEcard(context, "balance") }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardIcon(Icons.Filled.AccountBalanceWallet, accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("一卡通余额", color = TextSecondary, fontSize = 11.sp)
            val target = amount.toFloatOrNull()
            if (target != null) {
                var play by remember(target) { mutableStateOf(false) }
                LaunchedEffect(target) { play = true }
                val shown by animateFloatAsState(
                    targetValue = if (play) target else 0f,
                    animationSpec = tween(600),
                    label = "balanceRoll"
                )
                Text("¥%.2f".format(shown), color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("¥$amount", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (account.isNotEmpty()) {
                Text(account, color = TextSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        CardChevron(accent)
    }
}

/** 今日课表:承载数据的富卡片(列出今日各节课程 + 今日代办) */
@Composable
private fun ScheduleCard(card: ActionCard) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = Color(0xFF5B7CFA)
    val weekday = card.payload["weekday"].orEmpty()
    val courses = card.payload["today"].orEmpty().split("\n").filter { it.isNotBlank() }
    val todos = card.payload["todos"].orEmpty().split("\n").filter { it.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(0.7.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .clickable {
                try {
                    context.startActivity(android.content.Intent(context, go.examate.hyp.ScheduleActivity::class.java))
                } catch (e: Exception) { Log.w(TAG, "open schedule failed", e) }
            }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Filled.CalendarMonth, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("今日课表", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (weekday.isNotEmpty()) Text(weekday, color = TextSecondary, fontSize = 11.sp)
            }
            CardChevron(accent)
        }
        if (courses.isEmpty()) {
            Text("今日无课 🎉", color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp, start = 50.dp))
        } else {
            Spacer(Modifier.height(8.dp))
            courses.take(6).forEach { c ->
                Row(
                    Modifier.padding(start = 50.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(c, color = TextPrimary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (courses.size > 6) {
                Text("…共 ${courses.size} 节", color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 50.dp, top = 3.dp))
            }
        }
        // 今日代办
        if (todos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("今日待办", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 50.dp))
            todos.take(4).forEach { t ->
                Row(
                    Modifier.padding(start = 50.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📌", fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(t, color = TextPrimary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (todos.size > 4) {
                Text("…共 ${todos.size} 项", color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 50.dp, top = 3.dp))
            }
        }
    }
}

/** 通用入口/摘要卡片:图标 + 标题 + 摘要 + 跳转箭头 */
@Composable
private fun GenericActionCard(card: ActionCard) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (iconVec, accent) = when (card.type) {
        "ecard_topup" -> Icons.Filled.Payments to AccentBlue
        "room_electricity" -> Icons.Filled.Bolt to Color(0xFFEF6C00)
        "campus_grades" -> Icons.Filled.Assessment to Color(0xFF9B7BE8)
        "campus_info" -> Icons.Filled.Person to AccentBlue
        else -> Icons.Filled.Link to AccentBlue
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(0.7.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .clickable {
                try {
                    val intent = android.content.Intent(context, go.examate.hyp.CampusServiceActivity::class.java)
                    when (card.type) {
                        "ecard_topup" -> {
                            intent.putExtra("target_route", "ecard")
                            intent.putExtra("target_tab", "balance")
                        }
                        "room_electricity" -> {
                            intent.putExtra("target_route", "ecard")
                            intent.putExtra("target_tab", "payfee")
                        }
                        "campus_grades" -> intent.putExtra("target_route", "jwxt")
                        "campus_info" -> intent.putExtra("target_route", "jwxt")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.w(TAG, "startActivity failed", e) }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CardIcon(iconVec, accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (card.summary.isNotEmpty()) {
                Text(card.summary, color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        CardChevron(accent)
    }
}

/** 卡片左侧:强调色 tint 图标方底 */
@Composable
private fun CardIcon(icon: ImageVector, accent: Color) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
    }
}

/** 卡片右侧:圆形跳转箭头 */
@Composable
private fun CardChevron(accent: Color) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text("›", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 跳转到校园服务一卡通页 */
private fun openCampusEcard(context: Context, tab: String) {
    try {
        val intent = android.content.Intent(context, go.examate.hyp.CampusServiceActivity::class.java)
        intent.putExtra("target_route", "ecard")
        intent.putExtra("target_tab", tab)
        context.startActivity(intent)
    } catch (e: Exception) { Log.w(TAG, "startActivity failed", e) }
}

/**
 * 消息页脚:分支切换器(该消息有多个子分支时)。
 * 「追问」已移至操作行/长按菜单;切换器在兄弟分支间移动激活路径。
 */
@Composable
fun MessageBranchFooter(
    message: ChatMsg,
    allMessages: List<ChatMsg>,
    activePathIds: Set<String>,
    onSwitch: (Boolean) -> Unit
) {
    val kids = remember(allMessages, message.id) {
        allMessages.filter { it.parentId == message.id }.sortedBy { it.time }
    }
    val hasBranches = kids.size > 1
    if (!hasBranches) return   // 页脚只保留分支切换器
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (message.role == "user") 0.dp else 4.dp, top = 2.dp),
        horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val idx = kids.indexOfFirst { it.id in activePathIds }.coerceAtLeast(0)
        Text("‹", color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSwitch(false) }
                .padding(horizontal = 6.dp, vertical = 1.dp))
        Text("${idx + 1}/${kids.size} 分支", color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 2.dp))
        Text("›", color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSwitch(true) }
                .padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** 文件预览卡片 — AI 生成的文件下方显示,点击打开预览 */
@Composable
private fun FilePreviewCard(filePath: String) {
    var showPreview by remember { mutableStateOf(false) }
    val file = java.io.File(filePath)
    if (!file.exists()) return

    val fileName = file.name
    val ext = fileName.substringAfterLast(".", "").lowercase()
    val icon = when (ext) {
        "html" -> "🌐"
        "md" -> "📝"
        "json" -> "📋"
        "txt" -> "📄"
        else -> "📎"
    }
    val label = when (ext) {
        "html" -> "网页"
        "md" -> "Markdown"
        "json" -> "JSON"
        "txt" -> "文本"
        else -> "文件"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ChipBg)
            .clickable { showPreview = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(fileName, color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$label · 点击预览", color = TextSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", color = AccentBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }

    if (showPreview) {
        FilePreviewDialog(filePath, onDismiss = { showPreview = false })
    }
}

/** 文件预览弹窗 — 根据文件类型用不同方式渲染,顶栏支持导出PDF/分享 */
@Composable
private fun FilePreviewDialog(filePath: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = java.io.File(filePath)
    val content = remember { try { file.readText() } catch (_: Exception) { "" } }
    val ext = file.extension.lowercase()
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 40.dp, bottom = 40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
        ) {
            // 顶栏:文件名 + 导出PDF(仅html) + 分享 + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(InputBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(file.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ext == "html") {
                    Text("导出PDF", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            val wv = webViewRef
                            if (wv == null) {
                                go.examate.hyp.util.ToastUtil.show(context, "页面未加载完成")
                            } else try {
                                val pm = context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                                pm.print(
                                    file.nameWithoutExtension,
                                    wv.createPrintDocumentAdapter(file.nameWithoutExtension),
                                    android.print.PrintAttributes.Builder().build()
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "print failed", e)
                                go.examate.hyp.util.ToastUtil.show(context, "导出失败")
                            }
                        }.padding(horizontal = 8.dp))
                }
                Text("分享", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { go.examate.hyp.util.FileShareUtil.shareFile(context, filePath) }
                        .padding(horizontal = 8.dp))
                Text("关闭", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onDismiss() }.padding(start = 8.dp))
            }

            // 内容区
            when (ext) {
                "html" -> {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                                webViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "md" -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(
                            markdown = content,
                            textColor = TextPrimary,
                            fontSize = 14
                        )
                    }
                }
                else -> {
                    // txt, json, csv, etc.
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = content,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
