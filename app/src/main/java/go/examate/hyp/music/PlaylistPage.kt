package go.examate.hyp.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import go.examate.hyp.music.auth.MusicAuth
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import go.examate.hyp.util.ToastUtil

/**
 * 我的歌单页 — 列表 + 新建 + 平台导入。
 * - 新建:命名后创建空歌单
 * - 导入:选择平台 + 输入歌单 ID(或粘贴分享链接解析)→ 拉取歌曲存本地
 */
@Composable
fun PlaylistPage(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    // 弹窗:新建 / 导入
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 操作菜单
    var menuForId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    val playlists = remember(tick) { PlaylistStore.loadAll(context) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 150.dp)
    ) {
        // 顶栏:返回 + 标题 + 新建 + 导入
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(60.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Text("我的歌单", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            // 新建
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(18.dp))
                    .background(Primary.copy(0.1f)).clickable { showCreateDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建歌单", tint = Primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            // 导入
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(18.dp))
                    .background(Primary.copy(0.1f)).clickable { showImportDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = "从平台导入", tint = Primary, modifier = Modifier.size(20.dp))
            }
        }

        // 内容区
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {

        LazyColumn(Modifier.fillMaxSize()) {
            if (playlists.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有歌单", color = TextHint, fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("点击右上角 + 新建,或云图标从平台导入", color = TextHint, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                items(playlists, key = { it.id }) { pl ->
                    PlaylistRow(
                        playlist = pl,
                        onClick = { onOpenPlaylist(pl.id) },
                        onMenu = { menuForId = pl.id }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        }

        // 内容区 Column 结束
    }

    // 操作菜单(重命名/刷新/删除)
    menuForId?.let { id ->
        val pl = playlists.find { it.id == id }
        if (pl != null) {
            Box {
                DropdownMenu(expanded = true, onDismissRequest = { menuForId = null }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { renameId = id; menuForId = null },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    if (pl.source != "local" && pl.sourceId.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("刷新歌单") },
                            onClick = {
                                menuForId = null
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { PlaylistStore.refresh(context, id) }
                                    ToastUtil.show(context, if (ok) "已刷新" else "刷新失败")
                                    tick++
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除歌单", color = Color(0xFFEF4444)) },
                        onClick = { deleteId = id; menuForId = null },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFEF4444)) }
                    )
                }
            }
        }
    }

    // 新建歌单弹窗
    if (showCreateDialog) {
        TextInputDialog(
            title = "新建歌单",
            hint = "输入歌单名称",
            confirmLabel = "创建",
            onConfirm = { name ->
                PlaylistStore.create(context, name)
                showCreateDialog = false
                tick++
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    // 重命名弹窗
    renameId?.let { id ->
        val pl = playlists.find { it.id == id }
        TextInputDialog(
            title = "重命名歌单",
            initial = pl?.name.orEmpty(),
            hint = "输入新名称",
            confirmLabel = "保存",
            onConfirm = { name ->
                PlaylistStore.rename(context, id, name)
                renameId = null
                tick++
            },
            onDismiss = { renameId = null }
        )
    }

    // 导入弹窗
    if (showImportDialog) {
        ImportPlaylistDialog(
            importing = importing,
            onImport = { platform, sourceId, name ->
                importing = true
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        if (platform == "bili") {
                            PlaylistStore.importFromBiliFav(context, sourceId, name)
                        } else {
                            PlaylistStore.importFromPlatform(context, platform, sourceId, name)
                        }
                    }
                    importing = false
                    if (id != null) {
                        showImportDialog = false
                        tick++
                        onOpenPlaylist(id)
                    } else {
                        ToastUtil.show(context, "导入失败:歌单不存在或不可访问")
                    }
                }
            },
            onDismiss = { if (!importing) showImportDialog = false }
        )
    }

    // 删除二次确认
    deleteId?.let { id ->
        val pl = playlists.find { it.id == id }
        ConfirmDialog(
            title = "删除歌单",
            message = "确定删除「${pl?.name ?: "歌单"}」吗?此操作不可撤销。",
            onConfirm = { PlaylistStore.delete(context, id); deleteId = null; tick++ },
            onDismiss = { deleteId = null }
        )
    }
}

/** 歌单封面:有 coverUrl 加载网络图片,否则首字母占位 */
@Composable
private fun PlaylistCover(coverUrl: String, name: String) {
    var cover by remember(coverUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(coverUrl) {
        cover = if (coverUrl.isBlank()) null
        else withContext(Dispatchers.IO) {
            runCatching {
                java.net.URL(coverUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    cover?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } ?: Text(
        name.firstOrNull()?.toString() ?: "♪",
        color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold
    )
}

/** 歌单行:封面 + 名称/歌曲数 + 菜单 */
@Composable
private fun PlaylistRow(
    playlist: PlaylistStore.UserPlaylist,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面:导入歌单用真实封面,本地歌单首字母占位
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                    .background(Primary.copy(0.12f)),
                contentAlignment = Alignment.Center
            ) {
                PlaylistCover(coverUrl = playlist.coverUrl, name = playlist.name)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val src = if (playlist.source == "local") "本地" else MusicAuth.aliasOf(playlist.source)
                Text("${playlist.songCount} 首 · $src", color = TextHint, fontSize = 11.sp)
            }
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { onMenu() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = TextHint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 通用文本输入弹窗 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String = "",
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(DialogBg).padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(ChipBg).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (text.isEmpty()) Text(hint, color = TextHint, fontSize = 13.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(ChipBg).clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = TextSecondary, fontSize = 14.sp) }
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(Primary).clickable { onConfirm(text.trim()) },
                    contentAlignment = Alignment.Center
                ) { Text(confirmLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** 导入弹窗:平台选择 + 歌单 ID/链接输入 + 可选自定义名称 */
@Composable
private fun ImportPlaylistDialog(
    importing: Boolean,
    onImport: (platform: String, sourceId: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var idText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf("wy") }
    // 尝试从粘贴的链接解析 platform + id
    fun tryParseLink(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        // 网易: music.163.com/playlist?id=123 或 #/playlist?id=123
        Regex("163\\.com.*[?&/]playlist[?/].*?id=(\\d+)").find(trimmed)?.let {
            return "wy" to it.groupValues[1]
        }
        // QQ: y.qq.com/n/ryqq/playlist/xxxx
        Regex("y\\.qq\\.com.*playlist/(\\w+)").find(trimmed)?.let {
            return "tx" to it.groupValues[1]
        }
        // 酷狗: kugou.com/special/single/xxxxx
        Regex("kugou\\.com.*special/single/(\\w+)").find(trimmed)?.let {
            return "kg" to it.groupValues[1]
        }
        // 酷我: kuwo.cn/playlists/xxx
        Regex("kuwo\\.cn.*playlists?/(\\w+)").find(trimmed)?.let {
            return "kw" to it.groupValues[1]
        }
        // B站收藏夹: bilibili.com/medialist/detail/ml{fid}
        Regex("bilibili\\.com.*medialist.*ml(\\d+)").find(trimmed)?.let {
            return "bili" to it.groupValues[1]
        }
        // B站收藏夹: space.bilibili.com/xxx/favlist?fid={fid}
        Regex("bilibili\\.com.*favlist.*[?&]fid=(\\d+)").find(trimmed)?.let {
            return "bili" to it.groupValues[1]
        }
        return null
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(DialogBg).padding(20.dp)
        ) {
            Text("从平台导入", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text("粘贴歌单分享链接,或直接输入歌单 ID",
                color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            // 平台选择
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ChipBg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("wy" to "网易", "tx" to "QQ", "kg" to "酷狗", "kw" to "酷我", "mg" to "咪咕", "bili" to "B站").forEach { (code, label) ->
                    val sel = selectedPlatform == code
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) SelectedBg else Color.Transparent)
                            .clickable { selectedPlatform = code }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (sel) Color.White else TextSecondary,
                            fontSize = 12.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // ID/链接输入
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(ChipBg).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (idText.isEmpty()) Text("歌单链接或 ID", color = TextHint, fontSize = 13.sp)
                BasicTextField(
                    value = idText,
                    onValueChange = { idText = it },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            // 名称(可选)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(ChipBg).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (nameText.isEmpty()) Text("歌单名称(可选,留空用原名)", color = TextHint, fontSize = 13.sp)
                BasicTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(Primary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(ChipBg).clickable(enabled = !importing) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = TextSecondary, fontSize = 14.sp) }
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(SelectedBg.copy(if (importing) 0.5f else 1f))
                        .clickable(enabled = !importing) {
                            val parsed = tryParseLink(idText)
                            val platform = parsed?.first ?: selectedPlatform
                            val sourceId = parsed?.second ?: idText.trim()
                            if (sourceId.isNotBlank()) onImport(platform, sourceId, nameText.trim())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (importing) "导入中…" else "导入",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 通用确认弹窗(复用 MineScreen 的 ConfirmDialog 模式) */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(DialogBg).padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(ChipBg).clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = TextSecondary, fontSize = 14.sp) }
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(Primary).clickable { onConfirm() },
                    contentAlignment = Alignment.Center
                ) { Text("确定", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
