package go.examate.hyp.music

import go.examate.hyp.ui.components.SearchField
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import go.examate.hyp.music.fx.PlayerFxSettingsScreen
import go.examate.hyp.music.fx.ThemeSpecPage
import go.examate.hyp.music.lx.LxSong
import go.examate.hyp.ui.anim.Motion
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.AccentPeach
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音乐模块内部路由 */
sealed class MusicRoute {
    data object Home : MusicRoute()
    data object Local : MusicRoute()
    data object Favorites : MusicRoute()
    data object History : MusicRoute()
    data object Search : MusicRoute()
    data object Mine : MusicRoute()
    data object Effects : MusicRoute()
    data object Account : MusicRoute()
    data object Appearance : MusicRoute()
    data object Feature : MusicRoute()
    data object ThemeSpec : MusicRoute()
    data object Playlists : MusicRoute()
    data class PlaylistDetail(val playlistId: String) : MusicRoute()
    data class SongListDetail(val songList: KtSongList, val isBoard: Boolean = false) : MusicRoute()
}

/**
 * 音乐主界面 — 返回栈路由(子页返回逐级回退,不再跳回主页)。
 * 主页:现代搜索区(源/类型内嵌选择)+ 本地/收藏/历史/推荐 四大分区 +
 *      底部悬浮玻璃播放条 + 独立「我的」按钮。
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
    onLxImport: () -> Unit,
    folderTreeUri: android.net.Uri? = null
) {
    val context = LocalContext.current
    // ═══ 返回栈路由:逐级弹栈,修复子页返回直接回主页的问题 ═══
    val backStack = remember { mutableStateListOf<MusicRoute>(MusicRoute.Home) }
    val route: MusicRoute = backStack.last()
    fun navigate(r: MusicRoute) { backStack.add(r) }
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
        else (context as? Activity)?.finish()
    }

    // ═══ 播放页 sheet 分数(0=收起 1=全开):播放条拖拽展开/弹性取消/下滑关闭 ═══
    val sheetFraction = remember { Animatable(0f) }
    val uiScope = rememberCoroutineScope()
    // ═══ 搜索状态(提升到主界面):首页搜索区与搜索页共享源/类型选择;持久化记住上次选择 ═══
    var searchKeyword by remember { mutableStateOf("") }
    var searchSource by remember { mutableStateOf(MusicSearchPrefs.getSource(context)) }
    var searchType by remember { mutableStateOf(MusicSearchPrefs.getType(context)) }
    // 搜索页悬浮播放组件展开状态
    var searchMiniExpanded by remember { mutableStateOf(false) }
    // 主页返回二次确认
    var showExitConfirm by remember { mutableStateOf(false) }
    // 首页滚动状态:下滑浏览时搜索区收缩为搜索按钮
    val homeListState = rememberLazyListState()
    val homeScrolled by remember {
        androidx.compose.runtime.derivedStateOf {
            homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 40
        }
    }

    // 播放中定时刷新进度(逐字歌词开启时快 tick,保证逐字点亮粒度)
    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) {
            controller.refreshPosition()
            val fast = go.examate.hyp.music.fx.PlayerFxStore.isKaraokeEnabled(context)
            kotlinx.coroutines.delay(if (fast) 120 else 500)
        }
    }

    BackHandler(enabled = sheetFraction.value > 0.05f || backStack.size > 1) {
        if (sheetFraction.value > 0.05f) {
            uiScope.launch { sheetFraction.animateTo(0f, spring(0.85f, Spring.StiffnessMedium)) }
        } else pop()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        // sheet 开合:打开从 5% 起步(出现即有形,不从 0 突现),spring 手感
        fun openPlayer() {
            uiScope.launch {
                if (sheetFraction.value < 0.01f) sheetFraction.snapTo(0.05f)
                sheetFraction.animateTo(1f, spring(0.85f, Spring.StiffnessMedium))
            }
        }
        fun closePlayer() {
            uiScope.launch { sheetFraction.animateTo(0f, spring(0.85f, Spring.StiffnessMedium)) }
        }
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
                        listState = homeListState,
                        folderTreeUri = folderTreeUri,
                        currentFolder = currentFolder,
                        onOpenLocal = { navigate(MusicRoute.Local) },
                        onOpenFavorites = { navigate(MusicRoute.Favorites) },
                        onOpenHistory = { navigate(MusicRoute.History) },
                        onOpenSongList = { navigate(MusicRoute.SongListDetail(it, false)) },
                        onOpenBoard = { navigate(MusicRoute.SongListDetail(it, true)) },
                        onPickFolder = onPickFolder
                    )
                    is MusicRoute.Local ->
                        if (folderTreeUri == null) PermissionHint(onPickFolder)
                        else LocalMusicPage(controller, currentFolder, folderTreeUri)
                    is MusicRoute.Favorites -> FavoritesPage(controller)
                    is MusicRoute.History -> HistoryPage(controller)
                    is MusicRoute.Search -> MusicSearchScreen(
                        controller = controller,
                        initialKeyword = searchKeyword,
                        initialSource = searchSource,
                        initialType = searchType,
                        onSourceChange = { searchSource = it; MusicSearchPrefs.setSource(context, it) },
                        onTypeChange = { searchType = it; MusicSearchPrefs.setType(context, it) },
                        onBack = { pop() }
                    )
                    is MusicRoute.Mine -> MineScreen(
                        onOpenAccount = { navigate(MusicRoute.Account) },
                        onOpenEffects = { navigate(MusicRoute.Effects) },
                        onOpenAppearance = { navigate(MusicRoute.Appearance) },
                        onOpenFeature = { navigate(MusicRoute.Feature) },
                        onOpenPlaylists = { navigate(MusicRoute.Playlists) }
                    )
                    is MusicRoute.Account -> AccountPage()
                    is MusicRoute.Effects -> AudioEffectsScreen(effects)
                    is MusicRoute.Appearance -> PlayerFxSettingsScreen(
                        onOpenSpec = { navigate(MusicRoute.ThemeSpec) }
                    )
                    is MusicRoute.Feature -> FeatureSettingsPage(onLxImport = onLxImport)
                    is MusicRoute.ThemeSpec -> ThemeSpecPage(onBack = { pop() })
                    is MusicRoute.Playlists -> PlaylistPage(
                        onBack = { pop() },
                        onOpenPlaylist = { id -> navigate(MusicRoute.PlaylistDetail(id)) }
                    )
                    is MusicRoute.PlaylistDetail -> PlaylistDetailPage(
                        controller = controller,
                        playlistId = r.playlistId,
                        onBack = { pop() }
                    )
                    is MusicRoute.SongListDetail -> SongListDetailScreen(
                        controller = controller,
                        songList = r.songList,
                        onBack = { pop() },
                        isBoard = r.isBoard
                    )
                }
            }
        }

        // ═══ 主页悬浮搜索区:滚动收缩 / 短常驻点击放大 / 选项聚焦才显示 ═══
        if (route is MusicRoute.Home) {
            HomeSearchBar(
                onBack = { showExitConfirm = true },
                source = searchSource,
                onSourceChange = { searchSource = it; MusicSearchPrefs.setSource(context, it) },
                type = searchType,
                onTypeChange = { searchType = it; MusicSearchPrefs.setType(context, it) },
                scrolled = homeScrolled,
                onScrollToTop = {
                    uiScope.launch { homeListState.animateScrollToItem(0) }
                },
                onSearch = { kw ->
                    searchKeyword = kw
                    navigate(MusicRoute.Search)
                }
            )
        } else if (route !is MusicRoute.Search && route !is MusicRoute.SongListDetail && route !is MusicRoute.ThemeSpec && route !is MusicRoute.Playlists) {
            // 其他浏览页(自带顶栏的 ThemeSpec 除外):悬浮返回按钮;
            // 本地/历史/收藏/我的子页(账号/功能设置/音效/外观/歌单)不显示网络搜索入口
            val noSearch = route is MusicRoute.Local || route is MusicRoute.Favorites ||
                route is MusicRoute.History || route is MusicRoute.Account ||
                route is MusicRoute.Feature || route is MusicRoute.Effects ||
                route is MusicRoute.Appearance || route is MusicRoute.Mine ||
                route is MusicRoute.Playlists || route is MusicRoute.PlaylistDetail
            Row(
                Modifier.statusBarsPadding().fillMaxWidth()
                    .height(60.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    onClick = { pop() },
                    modifier = Modifier.size(40.dp)
                )
                val topTitle = when (route) {
                    is MusicRoute.Local -> "本地音乐"
                    is MusicRoute.Favorites -> "收藏音乐"
                    is MusicRoute.History -> "最近播放"
                    is MusicRoute.Playlists -> "我的歌单"
                    is MusicRoute.PlaylistDetail -> "歌单详情"
                    else -> null
                }
                if (topTitle != null) {
                    Text(topTitle, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (!noSearch) {
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Search, contentDescription = "搜索",
                        onClick = {
                            searchKeyword = ""
                            navigate(MusicRoute.Search)
                        }, modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // ═══ 底部悬浮:播放条 + 「我的」按钮(真悬浮,不占布局空间;sheet 打开时淡出) ═══
        if (route !is MusicRoute.Search) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer { alpha = (1f - sheetFraction.value * 1.6f).coerceIn(0f, 1f) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerBar(
                    controller = controller, backdrop = contentBackdrop, modifier = Modifier.weight(1f),
                    onClick = { openPlayer() },
                    // 长按/拖动展开:1:1 跟手驱动 sheet 分数,松手按位置判定展开/弹性收回
                    onSheetDrag = { dy ->
                        uiScope.launch {
                            sheetFraction.snapTo(
                                (sheetFraction.value - dy / sheetHeightPx * 1.3f).coerceIn(0f, 1f)
                            )
                        }
                    },
                    onSheetDragEnd = {
                        uiScope.launch {
                            val target = if (sheetFraction.value > 0.45f) 1f else 0f
                            sheetFraction.animateTo(target, spring(0.82f, Spring.StiffnessMedium))
                        }
                    }
                )
                LiquidGlassIconButton(
                    icon = Icons.Filled.Person, contentDescription = "我的",
                    onClick = { navigate(MusicRoute.Mine) }, modifier = Modifier.size(56.dp),
                    tint = if (route is MusicRoute.Mine) Primary else TextPrimary
                )
            }
        } else {
            // 搜索结果页:缩略封面 → 点击展开为完整 PlayerBar → 再点击进播放页
            // (曾尝试单元素 morph 展开/收回,收回时崩溃,回退为两组件切换方案;仅缩小收起图标)
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
                                openPlayer()
                            },
                            onSheetDrag = { dy ->
                                uiScope.launch {
                                    sheetFraction.snapTo(
                                        (sheetFraction.value - dy / sheetHeightPx * 1.3f).coerceIn(0f, 1f)
                                    )
                                }
                            },
                            onSheetDragEnd = {
                                uiScope.launch {
                                    val target = if (sheetFraction.value > 0.45f) 1f else 0f
                                    sheetFraction.animateTo(target, spring(0.82f, Spring.StiffnessMedium))
                                }
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
                            Modifier.align(Alignment.CenterEnd).size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceVariant.copy(0.92f))
                                .clickable {
                                    if (controller.current != null) {
                                        searchMiniExpanded = true
                                    } else {
                                        openPlayer()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // 统一走 MusicCover(本地嵌入/酷狗真实封面/coverRef 兜底),
                            // key 按曲目 id 强制重建 — 修复收缩条切歌不更新的 bug;
                            // 无封面时 MusicCover 显示首字母占位而非空图
                            val current = controller.current
                            if (current != null) {
                                key(current.id) {
                                    MusicCover(
                                        item = current, active = true, isPlaying = controller.isPlaying,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                Icon(IconMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        // ═══ 全屏播放页 — 分数驱动 overlay:播放条长按拖动 1:1 跟手展开,弹性取消,下滑关闭。
        //   注:曾尝试底部锚定缩放 morph(渐变扩大),但播放页内含 blur(46dp) 模糊层,
        //   在被缩放/裁剪的图层里渲染会闪烁甚至崩溃,故回退为平移方案 ═══
        if (sheetFraction.value > 0.001f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = (1f - sheetFraction.value) * sheetHeightPx
                }
            ) {
                NowPlayingScreen(
                    controller = controller,
                    onClose = { closePlayer() },
                    onOpenEffects = { navigate(MusicRoute.Effects) },
                    effects = effects
                )
            }
        }

        // ═══ 主页退出二次确认 ═══
        if (showExitConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                text = { Text("为避免误触,请确认您是否想要退出", color = TextSecondary) },
                confirmButton = {
                    Text("确认退出", color = Primary, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            showExitConfirm = false
                            (context as? Activity)?.finish()
                        }.padding(horizontal = 8.dp, vertical = 8.dp))
                },
                dismissButton = {
                    Text("取消", color = TextHint,
                        modifier = Modifier.clickable { showExitConfirm = false }
                            .padding(horizontal = 8.dp, vertical = 8.dp))
                },
                containerColor = go.examate.hyp.ui.theme.DialogBg
            )
        }
    }
}

/**
 * 主页悬浮搜索区 — 按钮悬浮化,无顶栏背景/空白:
 *  - 下滑浏览时搜索框收缩,搜索按钮置于右侧;点击后向左展开为完整搜索框
 *  - 返回按钮与搜索框同行且用 expand/shrink 动画过渡,不存在 crossfade 重叠(修复图标重叠 bug)
 *  - 常驻短胶囊,点击聚焦后动态放大(widthFraction)
 *  - 源/类型选项行仅聚焦时展开,悬浮在内容上方(自带半透明底)
 *  - 源 chips 右侧渐隐 + 限时"滑动切换"提示
 */
@Composable
private fun HomeSearchBar(
    onBack: () -> Unit,
    source: String,
    onSourceChange: (String) -> Unit,
    type: MusicSearchType,
    onTypeChange: (MusicSearchType) -> Unit,
    scrolled: Boolean,
    onScrollToTop: () -> Unit,
    onSearch: (String) -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var keyword by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    // 滚动中且未聚焦/无输入 → 收缩为右侧搜索按钮
    val collapsed = scrolled && !focused && keyword.isEmpty()
    LaunchedEffect(collapsed) {
        if (collapsed) focusManager.clearFocus()
    }
    // 展开后请求焦点(延迟等布局完成)
    LaunchedEffect(focused, collapsed) {
        if (focused && !collapsed) {
            kotlinx.coroutines.delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }
    // 滑动提示:聚焦后显示 3 秒
    var showSwipeHint by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            showSwipeHint = true
            kotlinx.coroutines.delay(3000)
            showSwipeHint = false
        }
    }

    // 搜索框宽度分数:收缩→0(向右缩进隐藏);展开:未聚焦短胶囊 0.62 → 聚焦满宽
    val fieldFraction by animateFloatAsState(
        if (collapsed) 0f else if (focused) 1f else 0.62f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium),
        label = "fieldFraction"
    )

    Column(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            // 返回按钮:仅展开态(左端 shrink/fade,与右侧搜索按钮无重叠窗口)
            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(tween(180)) +
                    androidx.compose.animation.expandHorizontally(
                        expandFrom = Alignment.Start, animationSpec = tween(200)
                    ),
                exit = fadeOut(tween(120)) +
                    androidx.compose.animation.shrinkHorizontally(
                        shrinkTowards = Alignment.Start, animationSpec = tween(140)
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiquidGlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        onClick = onBack, modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            // 搜索框(右锚定) ↔ 收缩态搜索按钮:收缩时框向右缩进,展开时从按钮向左展开
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!collapsed || fieldFraction > 0.05f) {
                    SearchField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        onSearch = {
                            if (keyword.trim().isNotEmpty()) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                focused = false
                                onSearch(keyword.trim())
                            }
                        },
                        placeholder = "听你想听",
                        modifier = Modifier.fillMaxWidth(fieldFraction)
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused) focused = true
                            },
                        showLeadingIcon = true
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = collapsed,
                    enter = fadeIn(tween(200)) +
                        androidx.compose.animation.expandHorizontally(
                            expandFrom = Alignment.End, animationSpec = tween(220)
                        ),
                    exit = fadeOut(tween(120)) +
                        androidx.compose.animation.shrinkHorizontally(
                            shrinkTowards = Alignment.End, animationSpec = tween(140)
                        )
                ) {
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Search, contentDescription = "搜索",
                        onClick = {
                            onScrollToTop()
                            focused = true
                        },
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }

        // ═══ 选项行:仅搜索框聚焦时显示(悬浮在内容上方,自带半透明底保证可读) ═══
        AnimatedVisibility(
            visible = focused && !collapsed,
            enter = androidx.compose.animation.expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = androidx.compose.animation.shrinkVertically(tween(160)) + fadeOut(tween(160))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(go.examate.hyp.ui.theme.BgPrimary.copy(0.86f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).height(32.dp)) {
                    Row(
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MusicSourceDefs.selectorListDynamic(context).forEach { s ->
                            SourceChip(selected = source == s.code, label = s.alias) { onSourceChange(s.code) }
                        }
                    }
                    // 右侧渐隐:提示可横向滑动
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(26.dp).height(32.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, go.examate.hyp.ui.theme.BgPrimary)
                                )
                            )
                    )
                }
                // 限时滑动提示(3秒)
                AnimatedVisibility(visible = showSwipeHint, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
                    Text("‹ 滑动切换", color = TextHint, fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.width(6.dp))
                SearchTypeSegment(type = type, onChange = onTypeChange)
            }
        }
    }
}

/** 音源选择 chip(选中渐变填充) — 首页搜索区与搜索页共用 */
@Composable
fun SourceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Brush.linearGradient(listOf(AccentBlue, AccentPurple))
                else Brush.linearGradient(listOf(SurfaceVariant.copy(0.7f), SurfaceVariant.copy(0.7f)))
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/** 歌曲/歌单胶囊分段切换 — 首页搜索区与搜索页共用 */
@Composable
fun SearchTypeSegment(type: MusicSearchType, onChange: (MusicSearchType) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(SurfaceVariant.copy(0.7f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MusicSearchType.entries.forEach { t ->
            val selected = type == t
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (selected) SelectedBg else Color.Transparent)
                    .clickable { onChange(t) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t.label, color = if (selected) Color.White else TextSecondary,
                    fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

/**
 * 主页内容:本地 / 收藏 / 历史 / 推荐 四大分区。
 * 推荐分区 = 推荐歌单(官方源,带缓存) + 推荐歌曲(官方源榜单,带缓存)。
 * listState 由主界面持有,供搜索区滚动收缩联动。
 */
@Composable
private fun HomeContent(
    controller: MusicController,
    listState: LazyListState,
    folderTreeUri: android.net.Uri?,
    currentFolder: String?,
    onOpenLocal: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSongList: (KtSongList) -> Unit,
    onOpenBoard: (KtSongList) -> Unit,
    onPickFolder: () -> Unit
) {
    val homeContext = LocalContext.current
    // 收藏/历史数量(SharedPreferences,读取廉价)
    var favCount by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    LaunchedEffect(controller.currentIndex) {
        favCount = FavoritesStore.load(homeContext).size
        history = PlayHistoryStore.load(homeContext)
    }
    // 推荐歌单(1h 缓存,支持换一换)
    var recommend by remember { mutableStateOf<List<KtSongList>>(emptyList()) }
    var recommendLoaded by remember { mutableStateOf(false) }
    var recommendRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        recommend = withContext(Dispatchers.IO) { SongListApi.recommendCached(homeContext) }
        recommendLoaded = true
    }
    // 推荐歌曲(官方源榜单池合并,1h 缓存;支持刷新随机换榜单)
    var recommendSongs by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var songsLoaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var recommendPage by remember { mutableIntStateOf(0) }
    var recommendLoadingMore by remember { mutableStateOf(false) }
    val homeScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        recommendSongs = withContext(Dispatchers.IO) {
            RecommendApi.recommendSongsCached(homeContext).map { it.toQueueItem(homeContext) }
        }
        songsLoaded = true
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding()
            .padding(horizontal = 16.dp).padding(top = 64.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ═══ 分区一/二:本地 + 收藏(双卡) ═══
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EntryCard(
                    title = "本地音乐",
                    subtitle = if (folderTreeUri == null) "选择文件夹开始" else (currentFolder ?: "已选文件夹"),
                    gradient = listOf(AccentMint, AccentBlue),
                    modifier = Modifier.weight(1f),
                    onClick = { if (folderTreeUri == null) onPickFolder() else onOpenLocal() }
                )
                EntryCard(
                    title = "收藏音乐",
                    subtitle = if (favCount > 0) "$favCount 首" else "还没有收藏",
                    gradient = listOf(AccentPurple, AccentPeach),
                    modifier = Modifier.weight(1f), onClick = onOpenFavorites
                )
            }
        }

        // ═══ 分区三:历史(入口卡 + 内联最近 3 首) ═══
        item {
            HistorySectionCard(history = history, onOpenHistory = onOpenHistory, controller = controller)
        }

        // ═══ 分区四:推荐歌单(换一换刷新) ═══
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("推荐歌单", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant.copy(0.7f))
                        .clickable(enabled = !recommendRefreshing && recommendLoaded) {
                            recommendRefreshing = true
                            homeScope.launch {
                                recommend = withContext(Dispatchers.IO) {
                                    SongListApi.recommendFresh(homeContext)
                                }
                                recommendRefreshing = false
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Refresh, contentDescription = "换一换",
                            tint = TextSecondary, modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (recommendRefreshing) "切换中…" else "换一换",
                            color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            when {
                !recommendLoaded -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("加载歌单…", color = TextHint, fontSize = 12.sp)
                }
                recommend.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("歌单暂不可用", color = TextHint, fontSize = 12.sp)
                }
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recommend, key = { it.id }) { sl ->
                        RecommendCard(sl) { onOpenSongList(sl) }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("推荐歌曲", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // 换一换:随机抽取榜单池重新拉取(刷新切换推荐来源)
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant.copy(0.7f))
                        .clickable(enabled = !refreshing && songsLoaded) {
                            refreshing = true
                            homeScope.launch {
                                val fresh = withContext(Dispatchers.IO) {
                                    RecommendApi.recommendSongsFresh(homeContext)
                                }
                                recommendSongs = fresh.map { it.toQueueItem(homeContext) }
                                refreshing = false
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Refresh, contentDescription = "换一换",
                            tint = TextSecondary, modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (refreshing) "切换中…" else "换一换",
                            color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            when {
                !songsLoaded -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("加载推荐歌曲…", color = TextHint, fontSize = 12.sp)
                }
                recommendSongs.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("推荐歌曲暂不可用", color = TextHint, fontSize = 12.sp)
                }
                else -> Column {
                    recommendSongs.forEachIndexed { index, item ->
                        TrackRow(
                            item = item,
                            active = controller.current?.id == item.id,
                            isPlaying = controller.isPlaying && controller.current?.id == item.id,
                            onClick = { controller.playQueue(recommendSongs, index) },
                            sourceLabel = MusicSourceDefs.aliasOf(item.id.removePrefix("lx_").substringBefore('_'))
                        )
                    }
                    // 加载更多
                    if (recommendLoadingMore) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text("加载中…", color = TextHint, fontSize = 12.sp)
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                                .background(SurfaceVariant.copy(0.7f)).clickable {
                                    recommendLoadingMore = true
                                    homeScope.launch {
                                        val existingKeys = recommendSongs.map {
                                            (it.title + "|" + it.artistDisplay()).lowercase().replace(" ", "")
                                        }.toSet()
                                        val more = withContext(Dispatchers.IO) {
                                            RecommendApi.recommendSongsMore(homeContext, existingKeys, recommendPage + 1)
                                        }
                                        recommendSongs = recommendSongs + more.map { it.toQueueItem(homeContext) }
                                        recommendPage++
                                        recommendLoadingMore = false
                                    }
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("加载更多", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 历史分区卡:标题 + 内联最近 3 首,整卡点击进入历史页 */
@Composable
private fun HistorySectionCard(
    history: List<QueueItem>,
    onOpenHistory: () -> Unit,
    controller: MusicController
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(SurfaceVariant.copy(0.55f)).clickable { onOpenHistory() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近播放", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Text("${history.size} 首 ›", color = TextHint, fontSize = 11.sp)
            }
        }
        if (history.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("播放记录会出现在这里", color = TextHint, fontSize = 12.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            history.take(3).forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { controller.playQueue(history, index) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MusicCover(
                        item = item,
                        active = controller.current?.id == item.id,
                        isPlaying = controller.isPlaying && controller.current?.id == item.id,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = TextPrimary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artistDisplay(), color = TextHint, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(item.durationText(), color = TextHint, fontSize = 11.sp)
                }
            }
        }
    }
}

/** 推荐歌单卡片(竖版:封面 + 名称 + 播放量) */
@Composable
private fun RecommendCard(sl: KtSongList, onClick: () -> Unit) {
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).clickable { onClick() },
    ) {
        Box(
            Modifier.size(width = 132.dp, height = 132.dp).clip(RoundedCornerShape(14.dp))
                .background(Primary.copy(0.14f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                bitmap = rememberRecommendCover(sl.img),
                contentDescription = sl.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 播放量角标
            if (sl.playCount > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .background(Color.Black.copy(0.35f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(playCountText(sl.playCount), color = Color.White, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(sl.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun rememberRecommendCover(url: String): androidx.compose.ui.graphics.ImageBitmap {
    val primary = Primary
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            if (url.isBlank()) null
            else runCatching { java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    return bitmap?.asImageBitmap() ?: remember(url, primary) {
        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(primary.copy(0.2f).toArgb()) }
            .asImageBitmap()
    }
}

private fun playCountText(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}

/** 入口卡(渐变封面 + 标题) */
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
                Text(subtitle, color = Color.White.copy(0.85f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 权限引导页 */
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
            lineHeight = 19.sp, textAlign = TextAlign.Center)
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
