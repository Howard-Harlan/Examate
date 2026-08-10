package go.examate.hyp.media.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import go.examate.hyp.music.MusicController
import go.examate.hyp.music.QueueItem
import go.examate.hyp.music.ResolveResult
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 听书播放页 — 复用音乐播放组件 MusicController 播放 legado 音频源(type=1)章节。
 *
 * 每章用 TextProvider.loadContent 获取音频 URL,转成 QueueItem 交给 MusicController。
 * 听书进度通过 onProgress 回调写入 BookshelfStore(kind="audio"),
 * 不写入 PlayHistoryStore(与音乐播放记录分离)。
 */
@Composable
fun AudioPlayerScreen(
    provider: TextProvider,
    chapters: List<ReaderChapter>,
    startIndex: Int,
    bookTitle: String,
    onBack: () -> Unit,
    onProgress: ((index: Int, title: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val controller = remember { MusicController(context) }
    var currentIndex by remember { mutableIntStateOf(startIndex) }

    // 章节转 QueueItem:resolver 调 provider.loadContent 取音频 URL
    val items = remember(chapters) {
        chapters.mapIndexed { i, ch ->
            QueueItem(
                id = "audio_${i}",
                title = ch.title,
                artist = bookTitle,
                resolver = {
                    val url = provider.loadContent(i).trim()
                    if (url.isEmpty() || !url.startsWith("http")) {
                        ResolveResult.Err(if (url.isEmpty()) "未获取到音频URL" else "音频URL无效")
                    } else {
                        ResolveResult.Ok(go.examate.hyp.music.PlayRequest(Uri.parse(url)))
                    }
                }
            )
        }
    }

    // 监听 MusicController 当前索引变化,同步章节索引 + 进度回调
    LaunchedEffect(controller.currentIndex) {
        val idx = controller.currentIndex
        if (idx >= 0 && idx < chapters.size && idx != currentIndex) {
            currentIndex = idx
            onProgress?.invoke(idx, chapters[idx].title)
        }
    }
    // 定时刷新播放位置(进度条用)
    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) {
            controller.refreshPosition()
            kotlinx.coroutines.delay(500)
        }
    }

    // 首次进入自动播放
    LaunchedEffect(Unit) {
        controller.playQueue(items, startIndex)
        onProgress?.invoke(startIndex, chapters.getOrNull(startIndex)?.title ?: "")
    }

    // 退出时释放
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F1E8))) {
        // 顶栏
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text("听书", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center))
        }

        // 书名 + 章节名
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(bookTitle, color = TextHint, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(chapters.getOrNull(currentIndex)?.title ?: "", color = TextPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        }

        Spacer(Modifier.weight(1f))

        // 加载中
        if (controller.loading) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextHint)
                Spacer(Modifier.width(8.dp))
                Text("加载中…", color = TextHint, fontSize = 12.sp)
            }
        }

        // 进度条:显示已播放时长 + 播放状态
        if (controller.positionMs > 0 || controller.isPlaying) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(formatTime(controller.positionMs), color = TextHint, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(if (controller.isPlaying) "播放中" else "已暂停", color = TextHint, fontSize = 10.sp)
            }
        }

        // 播放控制 — 明确背景+边框,确保任何主题下可见
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            // 上一章
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(CardBg).border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(26.dp))
                    .clickable { controller.prev() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一章",
                    tint = TextPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(24.dp))
            // 播放/暂停
            Box(
                Modifier.size(68.dp).clip(RoundedCornerShape(34.dp))
                    .background(Primary).clickable { controller.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White, modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            // 下一章
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                    .background(CardBg).border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(26.dp))
                    .clickable { controller.next() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一章",
                    tint = TextPrimary, modifier = Modifier.size(28.dp))
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

private fun formatTime(ms: Int): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
