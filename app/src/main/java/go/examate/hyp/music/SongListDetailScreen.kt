package go.examate.hyp.music

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单/榜单详情页(参照落雪 SongList 视图):
 * 顶部信息(封面/名称/作者/播放量/描述) + 歌曲列表(播放/加入队列)。
 * isBoard=true 时按排行榜解析(LeaderboardApi),否则按歌单解析(SongListApi)。
 *
 * 分页懒加载:kw 歌单 / mg 歌单 / kg 榜单支持分页,滚动接近末尾自动加载下一页(按 songmid 去重)。
 */
@Composable
fun SongListDetailScreen(
    controller: MusicController,
    songList: KtSongList,
    onBack: () -> Unit,
    isBoard: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<KtSongList?>(null) }
    var songs by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    // 平台是否支持分页:kw 歌单(pl.svc pn)/ mg 歌单(pageNo)/ kg 榜单(rank/song page)
    val canPage = (!isBoard && (songList.platform == "kw" || songList.platform == "mg")) ||
        (isBoard && songList.platform == "kg")
    // 每页数量:与 API 请求的 rn/pagesize 一致(kw/kg=100,mg=30)
    val pageSize = if (isBoard) PAGE_SIZE else SongListApi.pageSizeFor(songList.platform, false)

    suspend fun fetch(pageIdx: Int): KtSongListDetail? = withContext(Dispatchers.IO) {
        if (pageIdx == 1) {
            // 第一页走缓存(歌单 24h / 榜单 6h)
            if (isBoard) LeaderboardApi.detailCached(context, songList.platform, songList.id)
            else SongListApi.detailCached(context, songList.platform, songList.id)
        } else {
            if (isBoard) LeaderboardApi.detail(songList.platform, songList.id, pageIdx)
            else SongListApi.detail(songList.platform, songList.id, pageIdx)
        }
    }

    LaunchedEffect(songList.id) {
        loading = true
        page = 1
        val d = fetch(1)
        info = d?.info
        songs = d?.songs?.map { it.toQueueItem(context) } ?: emptyList()
        hasMore = canPage && (d?.songs?.size ?: 0) >= pageSize
        loading = false
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loading) return
        loadingMore = true
        scope.launch {
            val nextPage = page + 1
            val d = fetch(nextPage)
            val newSongs = d?.songs?.map { it.toQueueItem(context) } ?: emptyList()
            if (newSongs.isNotEmpty()) {
                val existing = songs.mapTo(HashSet()) { it.id }
                songs = songs + newSongs.filter { it.id !in existing }
                page = nextPage
            }
            hasMore = canPage && newSongs.size >= pageSize
            loadingMore = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .background(go.examate.hyp.ui.theme.BgPrimary)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(info?.name ?: songList.name, color = TextPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载歌单…", color = TextHint, fontSize = 13.sp)
            }
            info == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("歌单加载失败", color = TextHint, fontSize = 13.sp)
            }
            else -> {
                val d = info!!
                val queue = songs
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 14.dp, end = 14.dp, top = 6.dp, bottom = 100.dp
                    )
                ) {
                    // 歌单信息头
                    item(key = "header") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.size(96.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Primary.copy(0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                SongListCover(url = d.img, name = d.name)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = TextPrimary, fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    listOfNotNull(
                                        d.author.ifBlank { null }?.let { "创建者: $it" },
                                        if (d.playCount > 0) "${formatCount(d.playCount)} 次收藏" else null,
                                        if (queue.isNotEmpty()) "${queue.size} 首" else null
                                    ).joinToString(" · "),
                                    color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (d.desc.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(d.desc, color = TextHint, fontSize = 11.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp)).background(SelectedBg)
                                            .clickable { controller.playQueue(queue, 0) }
                                            .padding(horizontal = 18.dp, vertical = 8.dp)
                                    ) {
                                        Text("播放全部", color = androidx.compose.ui.graphics.Color.White,
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp)).background(ChipBg)
                                            .clickable {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        PlaylistStore.importFromQueueItems(context, d.name, d.img, songList.platform, songList.id, queue)
                                                    }
                                                    android.widget.Toast.makeText(context, "已收藏「${d.name}」", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(horizontal = 18.dp, vertical = 8.dp)
                                    ) {
                                        Text("收藏", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    // 歌曲列表
                    itemsIndexed(queue, key = { _, it -> it.id }) { index, item ->
                        TrackRow(
                            item = item,
                            active = controller.current?.id == item.id,
                            isPlaying = controller.isPlaying && controller.current?.id == item.id,
                            onClick = { controller.playQueue(queue, index) },
                            sourceLabel = MusicSourceDefs.aliasOf(d.platform)
                        )
                    }
                    // 分页懒加载:接近末尾自动加载下一页
                    if (hasMore) {
                        item(key = "load_more") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TextHint)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (loadingMore) "加载中…" else "上滑加载更多", color = TextHint, fontSize = 12.sp)
                                }
                                LaunchedEffect(Unit) { loadMore() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 每页数量(与 API 请求的 rn/pagesize 一致) */
private const val PAGE_SIZE = 100

/** 歌单封面(简单 IO 加载,失败显示首字母占位) */
@Composable
private fun SongListCover(url: String, name: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            if (url.isBlank()) null
            else runCatching {
                java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Text(
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "♪",
            color = Primary, fontSize = 28.sp, fontWeight = FontWeight.Bold
        )
    }
}

/** LxSong → QueueItem(播放统一走 PlayResolver:官方账号播放 / 音源解析脚本,按设置路线) */
fun go.examate.hyp.music.lx.LxSong.toQueueItem(context: Context): QueueItem {
    val song = this
    // 音质标注(落雪同款):取搜索接口给出的最高可得档
    val tag = when {
        "flac24bit" in qualities -> "24bit"
        "flac" in qualities -> "SQ"
        "320k" in qualities -> "HQ"
        else -> ""
    }
    return QueueItem(
        id = "lx_${platform}_${songmid}", title = name, artist = artist,
        durationMs = durationMs, coverUrl = picUrl, coverRef = albumId,
        qualityTag = tag,
        resolver = {
            PlayResolver.resolve(context, song)
        }
    )
}

/** 播放量格式化 */
private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}
