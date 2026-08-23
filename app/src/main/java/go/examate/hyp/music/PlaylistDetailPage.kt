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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import go.examate.hyp.music.lx.LxSong
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil

/**
 * 歌单详情页 — 展示歌单内歌曲 + 播放全部 / 单曲播放 / 移除。
 */
@Composable
fun PlaylistDetailPage(
    controller: MusicController,
    playlistId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val isFavorites = playlistId == "__favorites__"
    val playlist = remember(tick, playlistId) {
        if (isFavorites) {
            val favs = FavoritesStore.load(context)
            PlaylistStore.UserPlaylist(
                id = "__favorites__", name = "我的收藏", source = "fav",
                songs = favs.map { qi ->
                    val rawId = if (qi.id.startsWith("lx_")) qi.id.substring(3) else qi.id
                    val parts = rawId.split("_", limit = 2)
                    go.examate.hyp.music.lx.LxSong(
                        if (parts.size == 2) parts[0] else "",
                        if (parts.size == 2) parts[1] else "",
                        qi.title, qi.artist, qi.durationMs, qi.coverUrl, qi.coverRef
                    )
                }
            )
        } else PlaylistStore.get(context, playlistId)
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 18.dp).padding(top = 60.dp, bottom = 150.dp)
    ) {
        if (playlist == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("歌单不存在", color = TextHint, fontSize = 14.sp)
            }
            return
        }

        // 播放全部
        if (playlist.songs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${playlist.songs.size} 首", color = TextHint, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(SelectedBg)
                        .clickable {
                            val items = playlist.songs.map { it.toQueueItem(context) }
                            controller.playQueue(items, 0)
                            ToastUtil.show(context, "开始播放「${playlist.name}」")
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("播放全部", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (playlist.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("歌单是空的", color = TextHint, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(playlist.songs, key = { i, s -> "${s.platform}_${s.songmid}_$i" }) { index, song ->
                    PlaylistSongRow(
                        song = song,
                        index = index + 1,
                        onPlay = {
                            val items = playlist.songs.map { it.toQueueItem(context) }
                            controller.playQueue(items, index)
                        },
                        onRemove = {
                            if (isFavorites) {
                                FavoritesStore.toggle(context, song.toQueueItem(context))
                                tick++
                            } else {
                                PlaylistStore.removeSong(context, playlistId, song.platform, song.songmid)
                                tick++
                            }
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** 歌单歌曲行:序号 + 名称/歌手 + 移除 */
@Composable
private fun PlaylistSongRow(
    song: LxSong,
    index: Int,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable { onPlay() }.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$index", color = TextHint, fontSize = 12.sp,
            modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "未知歌手" }, color = TextHint, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = TextHint, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
    }
}
