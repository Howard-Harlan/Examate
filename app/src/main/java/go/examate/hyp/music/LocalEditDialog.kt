package go.examate.hyp.music

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.music.lx.PlatformSearcher
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地曲目编辑弹窗(长按歌曲触发):
 *  - 修改标题 / 艺术家 / 专辑
 *  - 更换内嵌封面(相册选图)
 *  - 编辑内嵌歌词(可一键调官方源获取歌词后嵌入)
 * 保存经 [LocalTagWriter] 写入 ID3v2.3(仅 MP3;其他格式提示暂不支持)。
 */
@Composable
fun LocalTrackEditDialog(
    track: QueueItem,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf("") }   // 空 = 不修改
    var newCover by remember(track.id) { mutableStateOf<ByteArray?>(null) }
    var lyric by remember(track.id) { mutableStateOf<String?>(null) }  // null = 未加载
    var fetchingLyric by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 打开时加载现有歌词(还原为 LRC 文本供编辑)
    LaunchedEffect(track.id) {
        val lines = LyricParser.fetch(track, context)
        lyric = if (lines.isEmpty()) "" else lines.joinToString("\n") { line ->
            val s = (line.timeMs / 1000).toInt()
            "[%02d:%02d.%02d]%s".format(s / 60, s % 60, (line.timeMs % 1000) / 10, line.text)
        }
    }

    // 相册选封面
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                newCover = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            // 压缩到 ≤800px,避免封面过大撑爆标签
                            val raw = input.readBytes()
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                            var sample = 1
                            while (maxOf(opts.outWidth, opts.outHeight) / sample > 800) sample *= 2
                            val bmp = BitmapFactory.decodeByteArray(
                                raw, 0, raw.size,
                                BitmapFactory.Options().apply { inSampleSize = sample }
                            ) ?: return@runCatching raw
                            val out = java.io.ByteArrayOutputStream()
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                            bmp.recycle()
                            out.toByteArray()
                        }
                    }.getOrNull()
                }
                if (newCover == null) ToastUtil.show(context, "封面读取失败")
            }
        }
    }

    // 官方源获取歌词:多平台搜索匹配 → 官方歌词 API
    fun fetchOfficialLyric() {
        if (fetchingLyric) return
        fetchingLyric = true
        scope.launch {
            val lrc = withContext(Dispatchers.IO) {
                for (platform in listOf("tx", "wy", "kw", "kg")) {
                    val songs = runCatching {
                        PlatformSearcher.search(context, platform, "$title $artist".trim())
                    }.getOrDefault(emptyList())
                    val best = songs.firstOrNull() ?: continue
                    val text = OfficialLyricApi.fetch(best.platform, best.songmid)
                    if (!text.isNullOrBlank()) return@withContext text
                }
                null
            }
            fetchingLyric = false
            if (lrc != null) {
                lyric = lrc.trim()
                ToastUtil.show(context, "已获取歌词,保存后嵌入歌曲")
            } else {
                ToastUtil.show(context, "官方源未找到该歌曲歌词")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DialogBg)
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("编辑歌曲信息", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("支持 MP3(ID3)写入;FLAC/M4A 等格式暂不支持保存", color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))

            EditField("歌曲名称", title) { title = it }
            Spacer(Modifier.height(10.dp))
            EditField("艺术家", artist) { artist = it }
            Spacer(Modifier.height(10.dp))
            EditField("专辑(留空不修改)", album) { album = it }
            Spacer(Modifier.height(14.dp))

            // 封面
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                        .background(Primary.copy(0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = newCover?.let {
                        remember(it) { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(64.dp)
                        )
                    } else {
                        MusicCover(
                            item = track,
                            modifier = Modifier.fillMaxWidth().height(64.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("内嵌封面", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(Primary.copy(0.1f))
                            .clickable { pickImage.launch("image/*") }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = Primary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("更换封面", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // 歌词
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("内嵌歌词(LRC)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Primary.copy(0.1f))
                        .clickable { fetchOfficialLyric() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (fetchingLyric) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Primary)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(if (fetchingLyric) "获取中…" else "官方源获取", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ChipBg.copy(0.5f))
                    .padding(10.dp)
            ) {
                if (lyric == null) {
                    Text("加载歌词中…", color = TextHint, fontSize = 12.sp)
                } else {
                    BasicTextField(
                        value = lyric ?: "",
                        onValueChange = { lyric = it },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 操作
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                        .background(ChipBg).clickable(enabled = !saving) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = TextSecondary, fontSize = 14.sp) }
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                        .background(Primary.copy(if (saving) 0.5f else 1f))
                        .clickable(enabled = !saving) {
                            saving = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val uri = track.localUri ?: return@withContext false
                                    LocalTagWriter.write(
                                        context, uri,
                                        LocalTagWriter.Patch(
                                            title = title.ifBlank { null },
                                            artist = artist.ifBlank { null },
                                            album = album.ifBlank { null },
                                            coverBytes = newCover,
                                            lyricText = lyric?.takeIf { it.isNotBlank() }
                                        )
                                    )
                                }
                                saving = false
                                if (ok) {
                                    ToastUtil.show(context, "已保存歌曲信息")
                                    onSaved()
                                    onDismiss()
                                } else {
                                    ToastUtil.show(context, "保存失败:该格式暂不支持写入(仅 MP3)")
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("保存", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 编辑输入行 */
@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(Primary),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ChipBg.copy(0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}
