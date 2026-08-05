package go.examate.hyp.music

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/** 通用列表容器:顶部留出悬浮顶栏空间,底部留出播放条空间 */
@Composable
private fun TrackList(
    items: List<QueueItem>,
    controller: MusicController,
    emptyText: String,
    header: (@Composable () -> Unit)? = null
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = TextHint, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 76.dp, bottom = 150.dp
        )
    ) {
        header?.let { item { it() } }
        itemsIndexed(items, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                item = track,
                active = controller.current?.id == track.id,
                isPlaying = controller.isPlaying && controller.current?.id == track.id,
                onClick = { controller.playQueue(items, index) }
            )
        }
    }
}

/**
 * 本地音乐列表 — 基于 SAF 选定文件夹扫描。
 * @param folderPath 选定文件夹名称(仅用于显示)
 * @param folderTreeUri 选定文件夹的 SAF tree Uri(已持久化授权)
 */
@Composable
fun LocalMusicPage(controller: MusicController, folderPath: String?, folderTreeUri: android.net.Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf<List<QueueItem>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(folderTreeUri) {
        loading = true
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MusicStore.scanSafTree(context, folderTreeUri).map { it.toQueueItem() }
        }
        tracks = list
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextHint)
                Spacer(Modifier.size(8.dp)); Text("扫描本地音乐…", color = TextHint, fontSize = 13.sp)
            }
        }
        tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到本地音乐(时长需 > 30 秒)", color = TextHint, fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
        else -> TrackList(
            items = tracks, controller = controller,
            emptyText = "未找到本地音乐(时长需 > 30 秒)",
            header = {
                Text("$folderPath · ${tracks.size} 首", color = TextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
        )
    }
}

/** 历史听歌 — 最近播放。 */
@Composable
fun HistoryPage(controller: MusicController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var history by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(controller.currentIndex) {
        history = PlayHistoryStore.load(context)
    }
    TrackList(
        items = history, controller = controller,
        emptyText = "还没有播放记录",
        header = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近播放 · ${history.size}", color = TextPrimary, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    Text("清空", color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.clickable { PlayHistoryStore.clear(context); history = emptyList() }.padding(4.dp))
                }
            }
        }
    )
}
