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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 统一封面加载组件 — 自动选择封面来源:
 *  1. coverUrl 是 http(s) URL → 在线封面(Coil 直接加载)
 *  2. albumId > 0 → 本地 albumart(content://media/external/audio/albumart/<id>),
 *     加载失败时回退到 3
 *  3. coverUrl 是本地文件路径 → IO 线程提取嵌入封面字节,再交 Coil 显示
 *  4. 都没有 → 占位图标
 */
@Composable
fun MusicCover(
    item: QueueItem,
    active: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 本地文件路径:异步提取嵌入封面字节(getEmbeddedPicture)
    var embeddedCover by androidx.compose.runtime.remember(item.id) {
        androidx.compose.runtime.mutableStateOf<ByteArray?>(null)
    }
    // albumart 加载失败标记:失败后回退提取嵌入封面
    var albumArtFailed by androidx.compose.runtime.remember(item.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // 需要提取嵌入封面的条件:
    // 1. coverUrl 是文件路径且(albumId<=0 或 albumart 加载失败)
    // 2. localUri 非空且没有其他封面来源(系统打开的音频文件)
    val needEmbedded = (item.coverUrl.isNotBlank() &&
        !item.coverUrl.startsWith("http") &&
        (item.albumId <= 0 || albumArtFailed)) ||
        (item.localUri != null && item.albumId <= 0 && item.coverUrl.isBlank()) ||
        (item.localUri != null && albumArtFailed)
    androidx.compose.runtime.LaunchedEffect(item.coverUrl, albumArtFailed, item.localUri) {
        if (needEmbedded && embeddedCover == null) {
            embeddedCover = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 优先用文件路径,回退到 content URI
                extractEmbeddedCover(item.coverUrl) ?: extractEmbeddedCoverFromUri(context, item.localUri)
            }
        }
    }

    // 构建封面 model
    val coverModel: Any? = when {
        item.coverUrl.startsWith("http") -> item.coverUrl
        item.albumId > 0 && !albumArtFailed ->
            android.net.Uri.parse("content://media/external/audio/albumart/${item.albumId}")
        embeddedCover != null -> embeddedCover!!
        else -> null
    }

    Box(modifier.background(if (active) Primary.copy(0.2f) else Primary.copy(0.07f)), contentAlignment = Alignment.Center) {
        if (coverModel != null) {
            // 用 rememberAsyncImagePainter 监听加载状态,albumart 失败时触发回退
            val painter = coil.compose.rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(coverModel)
                    .crossfade(true)
                    .build()
            )
            val painterState = painter.state
            // albumart URI 加载失败 → 置 albumArtFailed=true 触发嵌入封面提取
            androidx.compose.runtime.LaunchedEffect(painterState) {
                if (painterState is coil.compose.AsyncImagePainter.State.Error &&
                    item.albumId > 0 && !albumArtFailed) {
                    albumArtFailed = true
                }
            }
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize()
            )
        } else if (active && isPlaying) {
            PlayingBars()
        } else {
            Icon(IconMusic, contentDescription = null, tint = if (active) Primary else TextHint,
                modifier = Modifier.size(20.dp))
        }
    }
}

/** 从本地音频文件提取嵌入封面图片字节(MediaMetadataRetriever.getEmbeddedPicture) */
private fun extractEmbeddedCover(filePath: String): ByteArray? {
    if (filePath.isBlank()) return null
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(filePath)
        retriever.embeddedPicture
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** 从 content/file URI 提取嵌入封面字节(albumart URI 失败时的回退) */
private fun extractEmbeddedCoverFromUri(context: android.content.Context, uri: android.net.Uri?): ByteArray? {
    if (uri == null) return null
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
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
 */
@Composable
fun TrackRow(item: QueueItem, active: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) Primary.copy(0.10f) else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MusicCover(
            item = item, active = active, isPlaying = isPlaying,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = if (active) Primary else TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artistDisplay(), color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        Text(item.durationText(), color = TextHint, fontSize = 11.sp)
    }
}

/**
 * 底部悬浮液态玻璃播放条 — drawBackdrop 折射下方内容层,显示当前曲目 + 进度 + 控制。
 * 由主页/列表页共用,占满可用宽度(旁边的"我的"按钮由调用方拼接)。
 */
@Composable
fun PlayerBar(controller: MusicController, backdrop: Backdrop, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val track = controller.current
    val progress = if (track != null && track.durationMs > 0)
        controller.positionMs.toFloat() / track.durationMs else 0f

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
                onDrawSurface = { drawRect(Color.White.copy(0.26f)) }
            )
            .clickable { onClick() }
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
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(0.06f))) {
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
                Box(Modifier.size(4.dp, 15.dp).background(Color.White, RoundedCornerShape(1.dp)))
                Box(Modifier.size(4.dp, 15.dp).background(Color.White, RoundedCornerShape(1.dp)))
            }
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PlayerCtrlBtn(symbol: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = TextPrimary, fontSize = 15.sp)
    }
}
