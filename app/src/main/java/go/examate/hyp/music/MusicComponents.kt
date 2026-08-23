package go.examate.hyp.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 统一封面加载组件 — 本地/在线统一走 [AlbumArtLoader.load]:
 *  在线封面(OkHttp 下载,自动补全协议相对 URL) → 本地嵌入封面 → albumart → 目录封面
 *  全部失败显示标题首字母占位。
 */
@Composable
fun MusicCover(
    item: QueueItem,
    active: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var cover by androidx.compose.runtime.remember(item.id) {
        androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(item.id) {
        cover = AlbumArtLoader.load(context, item)
    }

    Box(modifier.background(if (active) Primary.copy(0.2f) else Primary.copy(0.07f)), contentAlignment = Alignment.Center) {
        when {
            // 解析出的封面(本地/在线统一)
            cover != null -> androidx.compose.foundation.Image(
                bitmap = cover!!.asImageBitmap(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 播放中的当前曲目:律动条
            active && isPlaying -> PlayingBars()
            // 无封面占位:标题首字母,观感更接近有封面
            else -> {
                val initial = item.title.firstOrNull()?.uppercaseChar() ?: '♪'
                Text(
                    text = initial.toString(),
                    color = if (active) Primary else TextHint,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


/**
 * 律动条 — 三根随音乐跳动的竖条(rememberInfiniteTransition),播放中显示于当前曲目。
 */
@Composable
fun PlayingBars(color: Color = AccentBlue, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    val phases = listOf(0, 150, 300)
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        phases.forEach { delayMs ->
            val h by transition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = delayMs),
                    repeatMode = RepeatMode.Reverse
                ), label = "bar"
            )
            Box(
                Modifier.size(width = 3.dp, height = (14 * h).dp)
                    .clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}

/**
 * 单曲行 — 列表/历史/搜索共用(QueueItem)。当前曲目显示律动条。
 * 层级强化:每行带轻量卡片底(非播放中),行间间距由调用方提供。
 */
@Composable
fun TrackRow(
    item: QueueItem,
    active: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    sourceLabel: String? = null
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) Primary.copy(0.12f) else go.examate.hyp.ui.theme.CardBg.copy(0.55f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MusicCover(
            item = item, active = active, isPlaying = isPlaying,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, color = if (active) Primary else TextPrimary, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (!sourceLabel.isNullOrBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(go.examate.hyp.ui.theme.Primary.copy(0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(sourceLabel, color = go.examate.hyp.ui.theme.Primary, fontSize = 9.sp)
                    }
                }
                // 音质标注(落雪同款:搜索接口给出的最高可得档)— 24bit臻品/SQ无损/HQ高清
                if (item.qualityTag.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    val (bg, fg) = when (item.qualityTag) {
                        "24bit" -> Color(0xFFB45309).copy(0.16f) to Color(0xFFB45309)
                        "SQ" -> Color(0xFF7C3AED).copy(0.14f) to Color(0xFF7C3AED)
                        else -> Color(0xFF2563EB).copy(0.14f) to Color(0xFF2563EB)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(bg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(item.qualityTag, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Text(item.artistDisplay(), color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        Text(item.durationText(), color = TextHint, fontSize = 11.sp)
    }
}

/**
 * 底部悬浮液态玻璃播放条 — drawBackdrop 折射下方内容层,显示当前曲目 + 进度 + 控制。
 * 由主页/列表页共用,占满可用宽度(旁边的"我的"按钮由调用方拼接)。
 * 交互(Apple Music 式):
 *  - 点击 → 直接展开播放页
 *  - 按住拖动(含长按后拖) → 1:1 跟手驱动播放页 sheet 展开,松手未过阈值弹性收回
 */
@Composable
fun PlayerBar(
    controller: MusicController,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onSheetDrag: ((dyPx: Float) -> Unit)? = null,
    onSheetDragEnd: (() -> Unit)? = null
) {
    val track = controller.current
    val progress = if (track != null && track.durationMs > 0)
        controller.positionMs.toFloat() / track.durationMs else 0f

    // 主题色/模式在组合上下文读取,供 onDrawSurface 使用;
    // 降低透明度(加深遮罩),强化悬浮条与背景层级区分
    val playerTint = Color.White.copy(if (IsDark) 0.24f else 0.52f)
    Box(
        modifier.fillMaxWidth().height(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedRectangle(22.dp) },
                effects = {
                    vibrancy()
                    blur(8f.dp.toPx())
                    lens(18f.dp.toPx(), 18f.dp.toPx())
                },
                onDrawSurface = { drawRect(playerTint) }
            )
            .pointerInput(Unit) {
                // 按住拖动:越过迟滞阈值后进入拖拽模式,1:1 跟手回调 dy(上拉展开/下拉收回),
                // 松手由调用方按位置/方向判定展开或弹性取消
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDy = 0f
                    var started = false
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        totalDy += dy
                        if (!started && kotlin.math.abs(totalDy) > 16f && onSheetDrag != null) started = true
                        if (started) {
                            onSheetDrag!!.invoke(dy)
                            change.consume()
                        }
                    }
                    if (started) onSheetDragEnd?.invoke()
                }
            }
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面+标题:切歌时横向滑入动画(AnimatedContent 按 track id)
                androidx.compose.animation.AnimatedContent(
                    targetState = track?.id,
                    transitionSpec = {
                        (androidx.compose.animation.slideInHorizontally { it / 2 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally { -it / 2 } +
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
                    },
                    label = "trackSwitch",
                    modifier = Modifier.weight(1f)
                ) { _ ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (track != null) {
                            MusicCover(
                                item = track, active = true, isPlaying = controller.isPlaying,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                    .background(
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(AccentBlue.copy(0.7f), AccentPurple.copy(0.7f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(IconMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track?.title ?: "未在播放", color = TextPrimary, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track?.artistDisplay() ?: "点一首歌开始", color = TextSecondary, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                PlayerCtrlBtn("⏮", 32.dp) { controller.prev() }
                PlayIcon(isPlaying = controller.isPlaying) { controller.toggle() }
                PlayerCtrlBtn("⏭", 32.dp) { controller.next() }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(if (IsDark) Color.White.copy(0.15f) else Color.Black.copy(0.06f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(AccentBlue))
            }
        }
    }
}

@Composable
private fun PlayIcon(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Primary).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 深色下 Primary 提亮为浅灰,图标需反色为深色
                val iconColor = if (IsDark) Color(0xFF1A1A2E) else Color.White
                Box(Modifier.size(4.dp, 15.dp).background(iconColor, RoundedCornerShape(1.dp)))
                Box(Modifier.size(4.dp, 15.dp).background(iconColor, RoundedCornerShape(1.dp)))
            }
        } else {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = "播放",
                tint = if (IsDark) Color(0xFF1A1A2E) else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PlayerCtrlBtn(symbol: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = TextPrimary, fontSize = 15.sp)
    }
}
