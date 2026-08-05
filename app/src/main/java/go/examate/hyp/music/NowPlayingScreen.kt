package go.examate.hyp.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 全屏播放页(Now Playing)。
 * 封面/歌词双视图:点击封面区域切换到歌词,点击歌词切回封面。
 * 歌词自动滚动到当前播放行,高亮当前行。
 * 控制按钮使用半透明白底圆形,不依赖 backdrop,确保在任意层级可见。
 */
@Composable
fun NowPlayingScreen(controller: MusicController, onClose: () -> Unit) {
    val track = controller.current
    val isPlaying = controller.isPlaying
    val errorMessage = controller.errorMessage

    // 封面/歌词视图切换
    var showLyric by remember { mutableStateOf(false) }

    // 歌词状态
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()

    // 切歌时加载歌词
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(track?.id) {
        lyrics = emptyList()
        if (track != null) {
            lyricLoading = true
            lyrics = LyricParser.fetch(track, context)
            lyricLoading = false
        }
    }

    // 歌词自动滚动到当前行(仅在 idx 变化时滚动,避免 500ms 一次的无意义动画调度)
    var lastLyricIdx by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(controller.positionMs, lyrics) {
        if (lyrics.isNotEmpty()) {
            val idx = LyricParser.findCurrentIndex(lyrics, controller.positionMs.toLong())
            if (idx >= 0 && idx != lastLyricIdx) {
                lastLyricIdx = idx
                lyricListState.animateScrollToItem(idx, scrollOffset = -200)
            }
        }
    }

    // 封面旋转:播放时持续转,暂停冻结
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, track?.id) {
        while (isPlaying) {
            rotation.animateTo(rotation.value + 360f, tween(14000, easing = LinearEasing))
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFF3F1FA), Color(0xFFEAF0FB)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)
        ) {
            // 顶栏:收起按钮
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                NpCircleBtn(
                    icon = Icons.Filled.KeyboardArrowDown, contentDescription = "收起",
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterStart),
                    size = 40, iconSize = 24
                )
            }

            Spacer(Modifier.height(12.dp))
            // 封面/歌词区域(点击切换)
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showLyric) {
                    // 歌词视图
                    LyricView(
                        lyrics = lyrics,
                        loading = lyricLoading,
                        positionMs = controller.positionMs.toLong(),
                        listState = lyricListState,
                        onBackToCover = { showLyric = false }
                    )
                } else {
                    // 封面唱片视图(点击切换到歌词)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clickable { showLyric = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { rotationZ = rotation.value }
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(AccentBlue.copy(0.85f), AccentPurple.copy(0.85f)))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (track != null) {
                                MusicCover(
                                    item = track, active = true, isPlaying = controller.isPlaying,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            }
                            // 唱片中心孔
                            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFF3F1FA)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(14.dp).clip(CircleShape).background(Primary.copy(0.3f)))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // 标题/艺术家
            Text(track?.title ?: "未在播放", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(track?.artistDisplay() ?: "点一首歌开始", color = TextSecondary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            // 错误信息显示(不占太多空间)
            if (errorMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ $errorMessage", color = Color(0xFFE57373), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(12.dp))
            // 进度条
            val dur = (track?.durationMs ?: 0L).coerceAtLeast(1L)
            val pos = controller.positionMs.toFloat().coerceIn(0f, dur.toFloat())
            Slider(
                value = pos, onValueChange = { controller.seekTo(it.toInt()) },
                valueRange = 0f..dur.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = AccentBlue)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(controller.positionMs), color = TextSecondary, fontSize = 11.sp)
                Text(fmt(dur.toInt()), color = TextSecondary, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            // 控制按钮
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NpCircleBtn(
                    icon = Icons.Filled.SkipPrevious, contentDescription = "上一首",
                    onClick = { controller.prev() }, size = 52, iconSize = 28
                )
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Primary).clickable { controller.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        Icon(Icons.Filled.Pause, contentDescription = "暂停", tint = Color.White, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
                NpCircleBtn(
                    icon = Icons.Filled.SkipNext, contentDescription = "下一首",
                    onClick = { controller.next() }, size = 52, iconSize = 28
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/**
 * 歌词视图 — 自动滚动到当前行,高亮当前行,点击空白切回封面。
 */
@Composable
private fun LyricView(
    lyrics: List<LyricLine>,
    loading: Boolean,
    positionMs: Long,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBackToCover: () -> Unit
) {
    val currentIndex = LyricParser.findCurrentIndex(lyrics, positionMs)
    Box(
        Modifier.fillMaxSize().clickable { onBackToCover() },
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Text("加载歌词中...", color = TextHint, fontSize = 14.sp)
            lyrics.isEmpty() -> Text("暂无歌词", color = TextHint, fontSize = 14.sp)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 200.dp)
            ) {
                items(lyrics.size) { i ->
                    val isActive = i == currentIndex
                    Text(
                        text = lyrics[i].text,
                        color = if (isActive) Primary else TextHint,
                        fontSize = if (isActive) 17.sp else 14.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 播放页圆形按钮 — 半透明白底 + 模糊,不依赖 backdrop,确保始终可见 */
@Composable
private fun NpCircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 40,
    iconSize: Int = 22
) {
    Box(
        modifier.size(size.dp).clip(CircleShape)
            .background(Color.White.copy(0.55f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(iconSize.dp))
    }
}

private fun fmt(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
