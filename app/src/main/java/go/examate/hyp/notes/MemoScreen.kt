package go.examate.hyp.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import go.examate.hyp.campus.ui.DatePickerDialog
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.ImageViewerDialog
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.CountdownStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** 速记卡片背景色系 — 莫兰迪低饱和 8 色,用户可自选 */
private val MemoColors = listOf(
    Color(0xFFFFF9C4), Color(0xFFC8E6C9), Color(0xFFBBDEFB), Color(0xFFFFCDD2),
    Color(0xFFE1BEE7), Color(0xFFFFCCBC), Color(0xFFB2DFDB), Color(0xFFF0F4C3)
)

/** 卡片轻微旋转角度(±2°),营造手贴随性感 */
private val MemoRotations = listOf(-2f, 1.5f, -1f, 2f, -1.5f, 1f, -2f, 1.5f)

/** 画布缩放范围 */
private const val CANVAS_MIN_SCALE = 0.5f
private const val CANVAS_MAX_SCALE = 2.5f

/**
 * 速记页(画布版) — 可缩放灵感画布 + 图片 + 标签 + 倒数日 + 统计。
 *
 * - 画布:双指缩放/平移,双击复位;便利贴可自由拖动堆叠
 * - 卡片:紧凑卡片型,支持附图预览;标签取代旧版心情系统
 * - 底部快速输入:文本 + 图片 + 标签,速记/倒数日切换
 */
@Composable
fun MemoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    var memosTick by remember { mutableIntStateOf(0) }
    val memos by produceState(initialValue = emptyList<MemoStorage.Memo>(), memosTick) {
        value = withContext(Dispatchers.IO) { MemoStorage.listMemos(context) }
    }

    // 编辑目标:null=不弹窗
    var editing by remember { mutableStateOf<MemoStorage.Memo?>(null) }
    // 查看目标:null=不弹窗;点击卡片先看全文,编辑入口在查看弹窗内
    var viewing by remember { mutableStateOf<MemoStorage.Memo?>(null) }

    // 页面切换:false=速记画布,true=统计页
    var showStats by remember { mutableStateOf(false) }

    // ═══ 倒数日(与速记融合,作为画布中的卡片) ═══
    var countdownTick by remember { mutableIntStateOf(0) }
    val countdowns by produceState(initialValue = emptyList<CountdownStore.Countdown>(), countdownTick) {
        value = withContext(Dispatchers.IO) { CountdownStore.list(context) }
    }
    var countdownName by remember { mutableStateOf("") }
    var countdownDate by remember { mutableStateOf(LocalDate.now()) }
    var editCountdownTarget by remember { mutableStateOf<CountdownStore.Countdown?>(null) }
    var showCountdownDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var deleteCountdownTarget by remember { mutableStateOf<CountdownStore.Countdown?>(null) }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (showStats) {
                MemoStatsView(memos, countdowns)
            } else {
                MemoBoard(
                    memos = memos,
                    countdowns = countdowns,
                    timeFormat = timeFormat,
                    onMemoClick = { viewing = it },
                    onCountdownClick = {
                        countdownName = it.name
                        countdownDate = runCatching { LocalDate.parse(it.date) }.getOrDefault(LocalDate.now())
                        editCountdownTarget = it
                        showCountdownDialog = true
                    },
                    onMemoMoved = { memo, x, y ->
                        MemoStorage.updatePosition(context, memo.id, x, y)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                QuickAddBar(
                    onSendMemo = { text, tag, images ->
                        MemoStorage.createMemo(context, text, tag, images = images)
                        memosTick++
                    },
                    onAddCountdown = { name, date ->
                        CountdownStore.add(context, name.ifBlank { "倒数日" }, date)
                        countdownTick++
                    }
                )
            }
        }

        // 返回(左上):统计页时先回到画布,画布时才退出
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = {
                    if (showStats) showStats = false else onBack()
                },
                modifier = Modifier.size(40.dp)
            )
        }
        // 统计(右上):仅在画布页显示
        if (!showStats) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.BarChart,
                    contentDescription = "统计",
                    onClick = { showStats = true },
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }

    // ═══ 全文查看弹窗(点击卡片默认动作) ═══
    viewing?.let { memo ->
        MemoViewerDialog(
            memo = memo,
            timeText = timeFormat.format(Date(memo.updatedAt)),
            onDismiss = { viewing = null },
            onEdit = {
                viewing = null
                editing = memo
            },
            onDelete = {
                MemoStorage.deleteMemo(context, memo.id)
                viewing = null
                memosTick++
            },
            onTogglePin = {
                MemoStorage.setPinned(context, memo.id, !memo.pinned)
                viewing = null
                memosTick++
            }
        )
    }

    // ═══ 速记编辑弹窗 ═══
    editing?.let { memo ->
        MemoEditDialog(
            memo = memo,
            onDismiss = { editing = null },
            onSave = { content, tag, color, images ->
                MemoStorage.updateMemo(context, memo.copy(content = content, tag = tag, colorIndex = color, images = images))
                memosTick++
                editing = null
            },
            onDelete = {
                MemoStorage.deleteMemo(context, memo.id)
                memosTick++
                editing = null
            },
            onTogglePin = {
                MemoStorage.setPinned(context, memo.id, !memo.pinned)
                memosTick++
                editing = null
            }
        )
    }

    // ═══ 倒数日添加/编辑弹窗 ═══
    if (showCountdownDialog) {
        CountdownEditDialog(
            initialName = countdownName,
            initialDate = countdownDate,
            onDismiss = { showCountdownDialog = false },
            onPickDate = { showDatePicker = true },
            onSave = { name, date ->
                val target = editCountdownTarget
                if (target == null) {
                    CountdownStore.add(context, name.ifBlank { "倒数日" }, date)
                } else {
                    CountdownStore.update(context, target.copy(name = name.ifBlank { "倒数日" }, date = date))
                }
                countdownTick++
                showCountdownDialog = false
            }
        )
    }
    if (showDatePicker) {
        DatePickerDialog(
            initialDate = countdownDate,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                countdownDate = it
                showDatePicker = false
            }
        )
    }
    deleteCountdownTarget?.let { c ->
        Dialog(onDismissRequest = { deleteCountdownTarget = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardBg)
                    .padding(20.dp)
            ) {
                Text("删除倒数日", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text("确定删除「${c.name}」吗?", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceVariant)
                            .clickable { deleteCountdownTarget = null }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("取消", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SelectedBg)
                            .clickable {
                                CountdownStore.remove(context, c.id)
                                deleteCountdownTarget = null
                                countdownTick++
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("删除", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  画布:双指缩放/平移 + 随意堆叠的卡片
// ════════════════════════════════════════

@Composable
private fun MemoBoard(
    memos: List<MemoStorage.Memo>,
    countdowns: List<CountdownStore.Countdown>,
    timeFormat: SimpleDateFormat,
    onMemoClick: (MemoStorage.Memo) -> Unit,
    onCountdownClick: (CountdownStore.Countdown) -> Unit,
    onMemoMoved: (MemoStorage.Memo, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (memos.isEmpty() && countdowns.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("随心贴", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("记下此刻的灵感与待办,或添加一个倒数日", color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text("双指缩放画布 · 双击复位", color = TextHint, fontSize = 10.sp)
            }
        }
        return
    }

    // ═══ 无边画布:双指缩放/平移,双击复位 ═══
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(CANVAS_MIN_SCALE, CANVAS_MAX_SCALE)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                )
            }
    ) {
        // 画布内容:应用缩放/平移变换,卡片坐标保持画布坐标系
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        ) {
            // 倒数日卡片(默认铺在顶部区域,作为"时间锚点")
            countdowns.take(8).forEachIndexed { i, c ->
                val col = i % 2
                val row = i / 2
                val jx = ((c.id.hashCode() % 20) - 10).dp
                val jy = ((c.id.hashCode() / 7 % 14) - 7).dp
                DragCard(
                    baseX = 16.dp + 166.dp * col + jx,
                    baseY = 12.dp + 130.dp * row + jy,
                    rotation = MemoRotations[i % MemoRotations.size],
                    content = { CountdownStickyCard(c, onClick = { onCountdownClick(c) }) }
                )
            }
            // 便利贴随后铺开(最新的更靠下,自然压在倒数日之上);拖动位置持久化
            memos.forEachIndexed { i, memo ->
                val hasPos = memo.offsetX != 0f || memo.offsetY != 0f
                val col = i % 2
                val row = i / 2
                val jx = ((memo.id.hashCode() % 20) - 10).dp
                val jy = ((memo.id.hashCode() / 7 % 14) - 7).dp
                DragCard(
                    baseX = if (hasPos) 0.dp else (16.dp + 166.dp * col + jx),
                    baseY = if (hasPos) 0.dp else (12.dp + 150.dp * row + jy),
                    rotation = MemoRotations[memo.colorIndex % MemoRotations.size],
                    initialDrag = if (hasPos) IntOffset(memo.offsetX.toInt(), memo.offsetY.toInt()) else IntOffset.Zero,
                    onDragEnd = { off -> onMemoMoved(memo, off.x.toFloat(), off.y.toFloat()) },
                    content = {
                        MemoStickerCard(
                            memo = memo,
                            timeText = timeFormat.format(Date(memo.updatedAt)),
                            onClick = { onMemoClick(memo) }
                        )
                    }
                )
            }
        }
    }
}

/** 可拖动的卡片容器:支持自由堆叠,可持久化拖动位置 */
@Composable
private fun DragCard(
    baseX: androidx.compose.ui.unit.Dp,
    baseY: androidx.compose.ui.unit.Dp,
    rotation: Float,
    initialDrag: IntOffset = IntOffset.Zero,
    onDragEnd: ((IntOffset) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var drag by remember { mutableStateOf(initialDrag) }
    Box(Modifier.offset(x = baseX, y = baseY)) {
        Box(
            Modifier
                .offset { drag }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            drag = IntOffset(
                                drag.x + dragAmount.x.toInt(),
                                drag.y + dragAmount.y.toInt()
                            )
                        },
                        onDragEnd = { onDragEnd?.invoke(drag) }
                    )
                }
        ) {
            Box(Modifier.rotate(rotation)) {
                content()
            }
        }
    }
}

/** 紧凑卡片型便利贴:文本最多 4 行,首图作为缩略预览 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoStickerCard(
    memo: MemoStorage.Memo,
    timeText: String,
    onClick: () -> Unit
) {
    val bgColor = MemoColors[memo.colorIndex % MemoColors.size]
    Column(
        Modifier
            .width(150.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp), spotColor = Color.Black.copy(0.10f))
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 头部:标签 + 置顶
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (memo.tag.isNotEmpty()) {
                Text(
                    "${MemoStorage.tagEmoji(memo.tag)} ${MemoStorage.tagName(memo.tag)}",
                    color = Color(0xFF8D8D8D),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (memo.pinned) Text("📌", fontSize = 11.sp)
        }
        // 首图预览
        if (memo.images.isNotEmpty()) {
            val ctx = LocalContext.current
            AsyncImage(
                model = File(MemoStorage.resolveImageFile(ctx, memo.images.first()).absolutePath),
                contentDescription = "附图",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.height(8.dp))
        }
        if (memo.content.isNotBlank()) {
            Text(
                memo.content,
                color = Color(0xFF424242),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (memo.images.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text("＋${memo.images.size - 1} 张图", color = Color(0xFF757575), fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            timeText,
            color = Color(0xFF757575),
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CountdownStickyCard(c: CountdownStore.Countdown, onClick: () -> Unit) {
    val days = c.daysLeft()
    Column(
        Modifier
            .width(150.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp), spotColor = Color.Black.copy(0.10f))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF5B8DEF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.emoji, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
            Text(c.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                days == 0L -> "就是今天"
                days > 0 -> "$days"
                else -> "${-days}"
            },
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                days == 0L -> "🎉"
                days > 0 -> "天后"
                else -> "天前"
            },
            color = Color.White.copy(0.85f),
            fontSize = 11.sp
        )
    }
}

// ════════════════════════════════════════
//  统计页:概览 + 记录节奏 + 标签分布 + 倒数日
// ════════════════════════════════════════

@Composable
private fun MemoStatsView(
    memos: List<MemoStorage.Memo>,
    countdowns: List<CountdownStore.Countdown>
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 68.dp, bottom = 24.dp)
    ) {
        val now = System.currentTimeMillis()
        val weekCutoff = now - 7L * 24 * 3600 * 1000
        val weekMemos = memos.filter { it.updatedAt >= weekCutoff }

        // ═══ 概览卡片 ═══
        FrostedGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("概览", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatsMetric("速记总数", "${memos.size}", AccentBlue)
                    StatsMetric("本周新增", "${weekMemos.size}", Primary)
                    StatsMetric("倒数日", "${countdowns.size}", Color(0xFF5B8DEF))
                }
                Spacer(Modifier.height(12.dp))
                val latest = memos.maxByOrNull { it.updatedAt }
                if (latest != null) {
                    val timeStr = remember(latest.updatedAt) {
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(latest.updatedAt))
                    }
                    Text("最近:$timeStr", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        latest.content.take(40) + if (latest.content.length > 40) "…" else "",
                        color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ═══ 记录节奏:近 14 天每日条数 ═══
        FrostedGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("记录节奏", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("最近 14 天,共 ${weekMemos.size} 条(近 7 天)", color = TextHint, fontSize = 11.sp)
                Spacer(Modifier.height(14.dp))
                val days = (13 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
                val counts = days.map { day ->
                    memos.count { java.time.Instant.ofEpochMilli(it.updatedAt)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate() == day }
                }
                val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    days.zip(counts).forEachIndexed { i, (day, count) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .width(10.dp)
                                    .height(if (count == 0) 4.dp else (6 + 52f * count / max).dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (count == 0) SurfaceVariant else Primary.copy(alpha = 0.35f + 0.65f * count / max))
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (i == days.size - 1) "今" else "${day.dayOfMonth}",
                                color = TextHint,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ═══ 标签分布卡片 ═══
        FrostedGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("标签分布", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                val tagged = memos.filter { it.tag.isNotEmpty() }
                Text("共 ${memos.size} 条速记,其中 ${tagged.size} 条标注了标签", color = TextHint, fontSize = 11.sp)
                Spacer(Modifier.height(14.dp))

                if (tagged.isEmpty()) {
                    Text("还没有标注标签的记录,输入时选个标签让回顾更清晰", color = TextHint, fontSize = 12.sp)
                } else {
                    val counts = MemoStorage.Tags.map { it to tagged.count { m -> m.tag == it.first } }
                    val max = counts.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    counts.filter { it.second > 0 }.forEach { (tag, count) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${tag.second} ${tag.third}", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(SurfaceVariant)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((count.toFloat() / max).coerceIn(0.05f, 1f))
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(MemoStorage.tagColor(tag.first))
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("$count", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ═══ 倒数日列表(即将到来) ═══
        if (countdowns.isNotEmpty()) {
            FrostedGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("倒数日", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    val sorted = countdowns.sortedBy { it.daysLeft() }
                    sorted.take(5).forEach { c ->
                        val days = c.daysLeft()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(c.name, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    days == 0L -> "今天"
                                    days > 0 -> "${days}天后"
                                    else -> "${-days}天前"
                                },
                                color = if (days >= 0) AccentBlue else TextHint,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ═══ 小建议(基于记录节奏) ═══
        FrostedGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("小建议", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    rhythmSuggestion(memos),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/** 概览中的单个指标 */
@Composable
private fun StatsMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextHint, fontSize = 11.sp)
    }
}

/** 根据记录节奏给出建议文案 */
private fun rhythmSuggestion(memos: List<MemoStorage.Memo>): String {
    if (memos.isEmpty()) return "还没有记录。灵感稍纵即逝,随手贴一张卡片,回头再看会很值。"
    // 连续记录天数(从今天/昨天往回数)
    val today = LocalDate.now()
    var streak = 0
    var cursor = today
    val dates = memos.map {
        java.time.Instant.ofEpochMilli(it.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }.toSet()
    if (today !in dates) cursor = today.minusDays(1) // 今天还没记,从昨天起算
    while (cursor in dates) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return when {
        streak >= 7 -> "已连续记录 $streak 天,这份坚持本身就是作品。继续保持这个节奏!"
        streak >= 2 -> "已连续记录 $streak 天,节奏正在养成。碎片想法随手贴上来,别让它们溜走。"
        else -> "记录还在断断续续。不用追求长篇,一句一行都是思考的痕迹,从今天开始就好。"
    }
}

// ════════════════════════════════════════
//  全文查看弹窗:点击卡片的默认动作
// ════════════════════════════════════════

/**
 * 速记全文查看 — 卡片显示面积有限,点击先看全文再决定编辑。
 * 附图两列网格,点击进入全屏查看(可保存到相册)。
 */
@Composable
private fun MemoViewerDialog(
    memo: MemoStorage.Memo,
    timeText: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    val context = LocalContext.current
    var viewingImage by remember { mutableStateOf<File?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .padding(20.dp)
        ) {
            // 头部:分类 + 时间 + 置顶标记
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memo.tag.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MemoStorage.tagColor(memo.tag).copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${MemoStorage.tagEmoji(memo.tag)} ${MemoStorage.tagName(memo.tag)}",
                            color = TextSecondary, fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (memo.pinned) Text("📌", fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(timeText, color = TextHint, fontSize = 11.sp)
            }

            // 全文内容
            if (memo.content.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    memo.content,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }

            // 附图网格(两列)
            if (memo.images.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                memo.images.chunked(2).forEach { rowImages ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowImages.forEach { rel ->
                            AsyncImage(
                                model = File(MemoStorage.resolveImageFile(context, rel).absolutePath),
                                contentDescription = "附图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewingImage = MemoStorage.resolveImageFile(context, rel) }
                            )
                        }
                        if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 底部操作
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onDelete() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text("删除", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onTogglePin() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        if (memo.pinned) "取消置顶" else "置顶",
                        color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("关闭", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SelectedBg)
                        .clickable { onEdit() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("编辑", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // 全屏图片查看(含保存到相册)
    viewingImage?.let { file ->
        ImageViewerDialog(model = file, onDismiss = { viewingImage = null })
    }
}

// ════════════════════════════════════════
//  倒数日编辑弹窗
// ════════════════════════════════════════

@Composable
private fun CountdownEditDialog(
    initialName: String,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onPickDate: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var date by remember { mutableStateOf(initialDate) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .padding(20.dp)
        ) {
            Text(if (initialName.isBlank()) "添加倒数日" else "编辑倒数日", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("如:考研 / 生日 / 纪念日", color = TextHint) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Primary.copy(alpha = 0.4f),
                    unfocusedContainerColor = SurfaceVariant,
                    focusedContainerColor = SurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceVariant)
                    .clickable { onPickDate() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📅", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(date.toString(), color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("选择日期", color = AccentBlue, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("取消", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SelectedBg)
                        .clickable { onSave(name.trim(), date.toString()) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("保存", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  底部快速输入:左侧 ＋ 展开(图片/想法/记录/倒数日)
// ════════════════════════════════════════

/**
 * 快速输入栏 — 核心是"零负担输入":
 * - 收起态只有一行:[＋][输入框][发送],默认按「记录」归档,直接打字就能发
 * - 点 ＋ 展开选项:图片 / 想法 / 记录 / 倒数日
 * - 选「倒数日」时输入行切换为:日期在前 + 名称 + 添加
 */
@Composable
private fun QuickAddBar(
    onSendMemo: (text: String, tag: String, images: List<String>) -> Unit,
    onAddCountdown: (String, String) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var currentTag by remember { mutableStateOf(MemoStorage.DEFAULT_TAG) }
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    // 倒数日模式:由展开面板的「倒数日」选项触发,发送或取消后回到文本模式
    var countdownMode by remember { mutableStateOf(false) }
    var cdName by remember { mutableStateOf("") }
    var cdDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            MemoStorage.copyImage(context, uri)?.let { rel ->
                pendingImages = pendingImages + rel
                expanded = true
            }
        }
    }

    FrostedGlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
        ) {
            // ═══ 展开面板:图片 / 想法 / 记录 / 倒数日 ═══
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 图片
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SurfaceVariant)
                            .clickable { imagePicker.launch(arrayOf("image/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = "添加图片", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    // 分类:想法 / 记录
                    MemoStorage.Tags.forEach { tag ->
                        val selected = !countdownMode && currentTag == tag.first
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) SelectedBg else SurfaceVariant)
                                .clickable {
                                    currentTag = tag.first
                                    countdownMode = false
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(
                                "${tag.second} ${tag.third}",
                                fontSize = 11.sp,
                                color = if (selected) Color.White else TextSecondary,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 倒数日
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (countdownMode) SelectedBg else SurfaceVariant)
                            .clickable { countdownMode = !countdownMode }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "⏰ 倒数日",
                            fontSize = 11.sp,
                            color = if (countdownMode) Color.White else TextSecondary,
                            fontWeight = if (countdownMode) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ═══ 待发送图片缩略图 ═══
            if (pendingImages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pendingImages) { rel ->
                        Box {
                            AsyncImage(
                                model = File(MemoStorage.resolveImageFile(context, rel).absolutePath),
                                contentDescription = "待添加图片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { pendingImages = pendingImages - rel },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (countdownMode) {
                // ═══ 倒数日输入:日期在前,名称在后 ═══
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Primary.copy(alpha = 0.10f))
                            .clickable { showDatePicker = true }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📅 ${cdDate}", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = cdName,
                        onValueChange = { cdName = it },
                        placeholder = { Text("名称 如:考研/生日", color = TextHint, fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Primary.copy(alpha = 0.5f),
                            unfocusedContainerColor = Primary.copy(alpha = 0.05f),
                            focusedContainerColor = Primary.copy(alpha = 0.10f),
                            cursorColor = Primary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SelectedBg)
                            .clickable {
                                onAddCountdown(cdName.trim(), cdDate.toString())
                                cdName = ""
                                countdownMode = false
                                expanded = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加倒数日", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                // ═══ 文本输入:[＋][输入框][发送] ═══
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 左侧 + 按钮(液态玻璃风格,点击展开选项)
                    val addInteraction = remember { MutableInteractionSource() }
                    val addPressed by addInteraction.collectIsPressedAsState()
                    val addScale by animateFloatAsState(
                        if (addPressed) 0.85f else 1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "addScale"
                    )
                    val addRotation by animateFloatAsState(
                        if (expanded) 45f else 0f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                        label = "addRot"
                    )
                    Box(
                        Modifier
                            .size(38.dp)
                            .graphicsLayer { scaleX = addScale; scaleY = addScale; rotationZ = addRotation }
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (expanded) AccentBlue.copy(0.12f) else SurfaceVariant)
                            .clickable(interactionSource = addInteraction, indication = null) { expanded = !expanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "展开选项", tint = AccentBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSend = input.isNotBlank() || pendingImages.isNotEmpty()
                    // 输入框(与 all in agents 统一风格)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("记下此刻…", color = TextHint, fontSize = 14.sp) },
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = AccentBlue.copy(0.45f),
                            unfocusedBorderColor = SurfaceVariant,
                            cursorColor = AccentBlue
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (canSend) {
                                onSendMemo(input.trim(), currentTag, pendingImages)
                                input = ""
                                pendingImages = emptyList()
                            }
                        }),
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    // 发送按钮(渐变填充 + 按压回弹)
                    val targetColor = if (canSend) AccentBlue else AccentBlue.copy(0.35f)
                    val containerColor by animateColorAsState(targetColor, tween(220), label = "sendColor")
                    val sendInteraction = remember { MutableInteractionSource() }
                    val sendPressed by sendInteraction.collectIsPressedAsState()
                    val pressScale by animateFloatAsState(
                        if (sendPressed && canSend) 0.85f else 1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "sendPress"
                    )
                    val readyScale by animateFloatAsState(
                        if (canSend) 1f else 0.8f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                        label = "sendReady"
                    )
                    val gradient = Brush.linearGradient(listOf(lerp(containerColor, Color.White, 0.22f), containerColor))
                    Box(
                        Modifier
                            .size(40.dp)
                            .graphicsLayer { scaleX = pressScale * readyScale; scaleY = pressScale * readyScale }
                            .clip(RoundedCornerShape(20.dp))
                            .background(gradient)
                            .then(
                                if (canSend) Modifier.clickable(interactionSource = sendInteraction, indication = null) {
                                    onSendMemo(input.trim(), currentTag, pendingImages)
                                    input = ""
                                    pendingImages = emptyList()
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            initialDate = cdDate,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                cdDate = it
                showDatePicker = false
            }
        )
    }
}

// ════════════════════════════════════════
//  编辑弹窗:内容 + 标签 + 颜色 + 附图管理
// ════════════════════════════════════════

@Composable
private fun MemoEditDialog(
    memo: MemoStorage.Memo,
    onDismiss: () -> Unit,
    onSave: (content: String, tag: String, colorIndex: Int, images: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var content by remember { mutableStateOf(memo.content) }
    var tag by remember { mutableStateOf(memo.tag) }
    var colorIndex by remember { mutableIntStateOf(memo.colorIndex) }
    var images by remember { mutableStateOf(memo.images) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            MemoStorage.copyImage(context, uri)?.let { images = images + it }
        }
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
            Text("编辑速记", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("此刻的想法…", color = TextHint) },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Primary.copy(alpha = 0.4f),
                    unfocusedContainerColor = SurfaceVariant,
                    focusedContainerColor = SurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 附图管理:缩略图 + 添加按钮
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images) { rel ->
                        Box {
                            AsyncImage(
                                model = File(MemoStorage.resolveImageFile(context, rel).absolutePath),
                                contentDescription = "附图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { images = images - rel },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceVariant)
                    .clickable { imagePicker.launch(arrayOf("image/*")) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加图片", color = TextSecondary, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))

            // 标签选择
            Text("标签", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MemoStorage.Tags) { t ->
                    val selected = tag == t.first
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) SelectedBg else SurfaceVariant)
                            .clickable { tag = if (selected) "" else t.first }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "${t.second} ${t.third}",
                            fontSize = 11.sp,
                            color = if (selected) Color.White else TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 颜色选择
            Text("颜色", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MemoColors.forEachIndexed { index, c ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(c)
                            .clickable { colorIndex = index }
                            .then(
                                if (colorIndex == index) Modifier.background(SelectedBg, CircleShape).padding(4.dp)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (colorIndex == index) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(c))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 底部操作
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceVariant)
                            .clickable { onDelete() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("删除", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (onTogglePin != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceVariant)
                            .clickable { onTogglePin() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(if (memo.pinned) "取消置顶" else "置顶", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("取消", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SelectedBg)
                        .clickable { onSave(content.trim(), tag, colorIndex, images) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("保存", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
