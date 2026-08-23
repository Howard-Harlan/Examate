package go.examate.hyp.music
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/** 通用列表容器:顶部留出悬浮顶栏空间,底部留出播放条空间;可选长按回调(本地编辑) */
@Composable
private fun TrackList(
    items: List<QueueItem>,
    controller: MusicController,
    emptyText: String,
    header: (@Composable () -> Unit)? = null,
    onLongClick: ((QueueItem) -> Unit)? = null
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
            start = 14.dp, end = 14.dp, top = 68.dp, bottom = 150.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        header?.let { item { it() } }
        itemsIndexed(items, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                item = track,
                active = controller.current?.id == track.id,
                isPlaying = controller.isPlaying && controller.current?.id == track.id,
                onClick = { controller.playQueue(items, index) },
                onLongClick = onLongClick?.let { cb -> { cb(track) } }
            )
        }
    }
}

/**
 * 本地音乐列表 — 基于 SAF 选定文件夹扫描。
 * 页内搜索框只过滤当前列表(不走网络搜索)。
 * 长按歌曲 → 编辑歌曲信息(名称/封面/歌词,经 LocalTagWriter 写入)。
 * @param folderPath 选定文件夹名称(仅用于显示)
 * @param folderTreeUri 选定文件夹的 SAF tree Uri(已持久化授权)
 */
@Composable
fun LocalMusicPage(controller: MusicController, folderPath: String?, folderTreeUri: android.net.Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<QueueItem?>(null) }
    // 页内搜索:仅过滤本地列表,不调网络
    var query by remember { mutableStateOf("") }
    val filtered = remember(tracks, query) {
        if (query.isBlank()) tracks
        else tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }

    androidx.compose.runtime.LaunchedEffect(folderTreeUri, reloadKey) {
        loading = true
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MusicStore.scanSafTree(context, folderTreeUri).map { it.toQueueItem() }
        }
        tracks = list
        loading = false
    }

    // 长按编辑弹窗
    editing?.let { item ->
        LocalTrackEditDialog(
            track = item,
            onDismiss = { editing = null },
            onSaved = { reloadKey++ }   // 保存后重新扫描,刷新标题/封面等
        )
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
            items = filtered, controller = controller,

            emptyText = if (query.isNotBlank()) "没有匹配的本地歌曲" else "未找到本地音乐(时长需 > 30 秒)",
            onLongClick = { editing = it },
            header = {
                go.examate.hyp.ui.components.SearchField(
                    value = query, onValueChange = { query = it }, onSearch = {},
                    placeholder = "搜索本地音乐", modifier = Modifier.fillMaxWidth(),
                    showLeadingIcon = true
                )

            }
        )
    }
}

/** 历史听歌 — 最近播放;页内搜索只过滤历史记录(不走网络)。 */
@Composable
fun HistoryPage(controller: MusicController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var history by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(controller.currentIndex) {
        history = PlayHistoryStore.load(context)
    }
    val filtered = remember(history, query) {
        if (query.isBlank()) history
        else history.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }
    TrackList(
        items = filtered, controller = controller,
        emptyText = if (query.isNotBlank()) "没有匹配的播放记录" else "还没有播放记录",
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                go.examate.hyp.ui.components.SearchField(
                    value = query, onValueChange = { query = it }, onSearch = {},
                    placeholder = "搜索播放记录", modifier = Modifier.weight(1f),
                    showLeadingIcon = true
                )
                if (history.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("清空", color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.clickable { showClearDialog = true }.padding(4.dp))
                }
            }
        }
    )
    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空播放记录", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("确定清空全部播放记录吗?此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                Text("清空", color = Primary, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        PlayHistoryStore.clear(context)
                        history = emptyList()
                        showClearDialog = false
                    }.padding(horizontal = 8.dp, vertical = 8.dp))
            },
            dismissButton = {
                Text("取消", color = TextHint,
                    modifier = Modifier.clickable { showClearDialog = false }
                        .padding(horizontal = 8.dp, vertical = 8.dp))
            },
            containerColor = go.examate.hyp.ui.theme.DialogBg
        )
    }
}

/** 收藏音乐 — 收藏的歌曲列表(可播放,带 resolver 重建);页内搜索只过滤收藏(不走网络)。 */
@Composable
fun FavoritesPage(controller: MusicController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var favorites by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(controller.currentIndex) {
        favorites = FavoritesStore.load(context)
    }
    val filtered = remember(favorites, query) {
        if (query.isBlank()) favorites
        else favorites.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }
    TrackList(
        items = filtered, controller = controller,

        emptyText = if (query.isNotBlank()) "没有匹配的收藏歌曲" else "还没有收藏的歌曲",
        header = {
            go.examate.hyp.ui.components.SearchField(
                value = query, onValueChange = { query = it }, onSearch = {},
                placeholder = "搜索收藏音乐", modifier = Modifier.fillMaxWidth(),
                showLeadingIcon = true
            )
        }
    )
}

/**
 * 功能设置页已移至 MineScreen.kt(合并播放设置/音源管理/音频独占)。
 */

