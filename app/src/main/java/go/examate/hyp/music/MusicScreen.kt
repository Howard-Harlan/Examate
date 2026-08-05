package go.examate.hyp.music

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import go.examate.hyp.ui.anim.Motion
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.AccentPeach
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/** 音乐模块内部路由 */
private sealed class MusicRoute {
    data object Home : MusicRoute()
    data object Local : MusicRoute()
    data object History : MusicRoute()
    data object Search : MusicRoute()
    data object Mine : MusicRoute()
    data object Effects : MusicRoute()
    data object Sources : MusicRoute()
}

/**
 * 音乐主界面 — 无顶栏,悬浮按钮(仿聊天页)。
 * 主页:顶部两卡(本地音乐/历史听歌)+ 留白区;底部悬浮玻璃播放条 + 独立「我的」按钮。
 * 扫描不在主页强制,进入"本地音乐"卡后才扫。
 *
 * @param controller 播放控制器
 * @param effects 音效控制器(供"我的"页)
 * @param hasPermission 是否已授予音频权限(保留兼容,SAF 模式下恒为 true)
 * @param onRequestPermission 请求权限(保留兼容,SAF 模式下指向选文件夹)
 * @param onPickFolder 触发系统选目录(授权 + "我的"页用)
 * @param currentFolder 当前限定文件夹名称
 * @param folderTreeUri 选定文件夹的 SAF tree Uri(已持久化授权);为空表示未选
 * @param onClearFolder 清除文件夹选择
 */
@Composable
fun MusicScreen(
    controller: MusicController,
    effects: AudioEffects,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPickFolder: () -> Unit,
    currentFolder: String?,
    onClearFolder: () -> Unit,
    onBiliLogin: () -> Unit,
    onLxImport: () -> Unit,
    folderTreeUri: android.net.Uri? = null
) {
    val context = LocalContext.current
    var route by remember { mutableStateOf<MusicRoute>(MusicRoute.Home) }
    var showNowPlaying by remember { mutableStateOf(false) }
    // 搜索关键词:主页输入后传递给搜索结果页
    var searchKeyword by remember { mutableStateOf("") }
    // 搜索页悬浮播放组件展开状态:点击缩略封面展开为完整 PlayerBar,再点击进播放页,不点击自动收回
    var searchMiniExpanded by remember { mutableStateOf(false) }

    // 播放中定时刷新进度
    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) {
            controller.refreshPosition()
            kotlinx.coroutines.delay(500)
        }
    }

    BackHandler(enabled = showNowPlaying || route !is MusicRoute.Home) {
        if (showNowPlaying) showNowPlaying = false else route = MusicRoute.Home
    }

    // 离开搜索页时重置展开状态
    LaunchedEffect(route) {
        if (route !is MusicRoute.Search) searchMiniExpanded = false
    }
    // 展开后 4 秒未点击自动收回
    LaunchedEffect(searchMiniExpanded) {
        if (searchMiniExpanded) {
            kotlinx.coroutines.delay(4000)
            searchMiniExpanded = false
        }
    }

    // 内容层 backdrop:被底部玻璃播放条折射
    val contentBackdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        // ═══ 内容层(被 contentBackdrop 捕获) ═══
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    (fadeIn(tween(Motion.NORMAL)) togetherWith fadeOut(tween(Motion.FAST)))
                },
                label = "musicRoute"
            ) { r ->
                when (r) {
                    is MusicRoute.Home -> HomeContent(
                        controller = controller,
                        onOpenLocal = { route = MusicRoute.Local },
                        onOpenHistory = { route = MusicRoute.History }
                    )
                    is MusicRoute.Local ->
                        if (folderTreeUri == null) PermissionHint(onPickFolder)
                        else LocalMusicPage(controller, currentFolder, folderTreeUri)
                    is MusicRoute.History -> HistoryPage(controller)
                    is MusicRoute.Search -> MusicSearchScreen(
                        controller = controller,
                        initialKeyword = searchKeyword,
                        onOpenSources = { route = MusicRoute.Sources },
                        onBack = { route = MusicRoute.Home }
                    )
                    is MusicRoute.Mine -> MineScreen(
                        onOpenSources = { route = MusicRoute.Sources },
                        onOpenEffects = { route = MusicRoute.Effects }
                    )
                    is MusicRoute.Sources -> MusicSourcesScreen(
                        currentFolder = currentFolder,
                        onPickFolder = onPickFolder, onClearFolder = onClearFolder,
                        onBiliLogin = onBiliLogin, onLxImport = onLxImport
                    )
                    is MusicRoute.Effects -> AudioEffectsScreen(effects)
                }
            }
        }

        // ═══ 主页悬浮搜索区(仅 Home 路由显示):返回 + 浅白搜索条 ═══
        if (route is MusicRoute.Home) {
            HomeSearchBar(
                onBack = { (context as? Activity)?.finish() },
                onSearch = { kw ->
                    searchKeyword = kw
                    route = MusicRoute.Search
                }
            )
        } else if (route !is MusicRoute.Search) {
            // 其他浏览页(本地/历史/我的/音源/音效):返回 + 搜索按钮
            // 与 HomeSearchBar 等高(48dp 内容 + 6dp*2 padding),避免切换时位置抖动
            Row(
                Modifier.statusBarsPadding().fillMaxWidth()
                    .height(60.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    onClick = { route = MusicRoute.Home },
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.weight(1f))
                LiquidGlassIconButton(
                    icon = Icons.Filled.Search, contentDescription = "搜索",
                    onClick = {
                        searchKeyword = ""
                        route = MusicRoute.Search
                    }, modifier = Modifier.size(40.dp)
                )
            }
        }

        // ═══ 底部悬浮:播放条 + 「我的」按钮(真悬浮,不占布局空间) ═══
        // Search 路由:隐藏"我的"按钮,播放条缩减为封面(点击展开 NowPlaying)
        if (route !is MusicRoute.Search) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerBar(controller = controller, backdrop = contentBackdrop, modifier = Modifier.weight(1f),
                    onClick = { showNowPlaying = true })
                LiquidGlassIconButton(
                    icon = Icons.Filled.Person, contentDescription = "我的",
                    onClick = { route = MusicRoute.Mine }, modifier = Modifier.size(56.dp),
                    tint = if (route is MusicRoute.Mine) Primary else TextPrimary
                )
            }
        } else {
            // 搜索结果页:缩略封面 → 点击展开为完整 PlayerBar → 再点击进播放页 → 不点击自动收回
            // 无歌时也显示缩略封面占位,点击直接打开播放页
            val barWidth by animateFloatAsState(
                if (searchMiniExpanded) 1f else 0f,
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
                label = "searchBarWidth"
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .navigationBarsPadding()
            ) {
                if (searchMiniExpanded) {
                    AnimatedVisibility(
                        visible = searchMiniExpanded,
                        enter = fadeIn(tween(280)) +
                            androidx.compose.animation.slideInVertically(
                                initialOffsetY = { it / 6 }, animationSpec = tween(280)
                            ),
                        exit = fadeOut(tween(200))
                    ) {
                        PlayerBar(
                            controller = controller, backdrop = contentBackdrop, modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                searchMiniExpanded = false
                                showNowPlaying = true
                            }
                        )
                    }
                } else {
                    AnimatedVisibility(
                        visible = !searchMiniExpanded,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(180))
                    ) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(listOf(AccentBlue.copy(0.85f), AccentPurple.copy(0.85f)))
                                )
                                .clickable {
                                    if (controller.current != null) {
                                        searchMiniExpanded = true
                                    } else {
                                        showNowPlaying = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val cover = controller.current?.coverUrl
                            if (!cover.isNullOrBlank()) {
                                coil.compose.AsyncImage(
                                    model = cover, contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                )
                            } else {
                                Icon(IconMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }

        // ═══ 全屏播放页(上滑覆盖) ═══
        androidx.compose.animation.AnimatedVisibility(
            visible = showNowPlaying,
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { it }, animationSpec = tween(Motion.NORMAL)
            ) + fadeIn(tween(Motion.NORMAL)),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it }, animationSpec = tween(Motion.NORMAL)
            ) + fadeOut(tween(Motion.FAST))
        ) {
            Box(Modifier.fillMaxSize()) {
                NowPlayingScreen(controller = controller, onClose = { showNowPlaying = false })
            }
        }
    }
}

/**
 * 主页悬浮搜索条 — [返回] [浅白搜索框]
 * 输入关键词后回车或点搜索按钮跳转到搜索结果页。
 * 高度固定 60dp(48dp 搜索框 + 6dp*2 padding),与其他路由顶栏等高避免抖动。
 */
@Composable
private fun HomeSearchBar(
    onBack: () -> Unit,
    onSearch: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var keyword by remember { mutableStateOf("") }

    Row(
        Modifier.statusBarsPadding().fillMaxWidth()
            .height(60.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidGlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
            onClick = onBack, modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(8.dp))
        // 浅白搜索框(占满,无音源 chip)
        Box(
            Modifier.weight(1f).height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.9f))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("听你想听", color = TextHint, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = Primary
                    ),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (keyword.trim().isNotEmpty()) {
                            keyboard?.hide()
                            onSearch(keyword.trim())
                        }
                    })
                )
                // 搜索按钮(输入框内右侧,常态显示;无内容时暗淡不可搜)
                val canSearch = keyword.trim().isNotEmpty()
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .clickable(enabled = canSearch) {
                            keyboard?.hide()
                            onSearch(keyword.trim())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Search, contentDescription = "搜索",
                        tint = if (canSearch) Primary else TextHint.copy(0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 主页内容:两张入口卡(本地音乐/历史听歌)+ 下方留白区(暂留)。
 */
@Composable
private fun HomeContent(
    controller: MusicController,
    onOpenLocal: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 72.dp)
    ) {
        // ═══ 顶部两卡 ═══
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EntryCard(
                title = "本地音乐",
                gradient = listOf(AccentBlue, AccentPurple),
                modifier = Modifier.weight(1f), onClick = onOpenLocal
            )
            EntryCard(
                title = "最近播放",
                gradient = listOf(AccentMint, AccentPeach),
                modifier = Modifier.weight(1f), onClick = onOpenHistory
            )
        }

        Spacer(Modifier.height(20.dp))

        // ═══ 下方留白区(暂留,后续扩展:歌单/推荐/在线源) ═══
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = 96.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(IconMusic, contentDescription = null, tint = TextSecondary.copy(0.5f), modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text("更多功能即将上线", color = TextSecondary, fontSize = 13.sp)
                Text("歌单 · 推荐 · 在线音源", color = TextSecondary.copy(0.6f), fontSize = 11.sp)
            }
        }
    }
}

/** 入口卡(渐变封面 + 标题),方形。 */
@Composable
private fun EntryCard(
    title: String,
    subtitle: String = "",
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier.aspectRatio(1.35f).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(gradient))
            .clickable { onClick() }.padding(16.dp)
    ) {
        Icon(IconMusic, contentDescription = null, tint = Color.White.copy(0.9f),
            modifier = Modifier.size(30.dp).align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = Color.White.copy(0.85f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PermissionHint(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Primary.copy(0.1f)), contentAlignment = Alignment.Center) {
            Icon(IconMusic, contentDescription = null, tint = Primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("选择本地音乐文件夹", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("为保护隐私,本应用不会申请全盘音频访问权限。\n请选择存放音乐的文件夹,应用将仅扫描该文件夹下的音频。",
            color = TextSecondary, fontSize = 12.sp,
            lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Primary).clickable { onRequest() }
            .padding(horizontal = 24.dp, vertical = 11.dp)) {
            Text("选择文件夹", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 运行时权限名(按 API 版本) */
val audioPermission: String
    get() = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE
