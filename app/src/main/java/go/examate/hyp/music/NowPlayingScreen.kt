package go.examate.hyp.music

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import go.examate.hyp.music.fx.PlayerFxLayer
import go.examate.hyp.music.fx.PlayerFxStore
import go.examate.hyp.music.fx.WebPlayerView
import go.examate.hyp.music.lx.KugouCandidate
import go.examate.hyp.music.lx.KugouLyricApi
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放页弹出面板类型 */
private enum class PlayerSheet { TOOLS, SPEED, SLEEP, QUEUE, EFFECTS, LYRIC_SEARCH }

/**
 * 全屏播放页 — 沉浸主题播放器(单套 UI,无"有/无封面"分支)。
 *
 * 交互体系(本次升级):
 *  - 顶栏下滑手势关闭(拖动跟手,松手按位移判定关闭/回弹)
 *  - 播放模式切换:列表循环/单曲循环/随机/顺序(控制行左侧,循环图标)
 *  - 歌词沉浸:切到歌词页时全部操作按钮隐藏;对齐调节仅点击歌词行后浮现(4秒自动隐)
 *  - 控制卡下缩:卡区域向下拖动 → 只留歌曲信息+进度条(上拖/点击恢复)
 *  - 播放列表:控制行右侧按钮,底部面板展示队列、点选跳播
 *  - 倍速/定时/音效改为主题化底部面板(替代系统 DropdownMenu)
 *  - 歌词偏移调节:提前/延后 0.5s,按曲目持久化
 *
 * 视觉:主题优先、封面弱化;歌词 Apple Music 效果;动效遵循 Apple Design 规范。
 */
@Composable
fun NowPlayingScreen(
    controller: MusicController,
    onClose: () -> Unit,
    onOpenEffects: () -> Unit = {},
    effects: AudioEffects? = null
) {
    val track = controller.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isFav by remember(track?.id) {
        mutableStateOf(track != null && FavoritesStore.isFavorite(context, track.id))
    }

    // ═══ 封面 Bitmap(切歌重载) → 动态主题 ═══
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track?.id) {
        coverBitmap = null
        if (track != null) coverBitmap = AlbumArtLoader.load(context, track)
    }
    val colors = rememberPlayerColors(coverBitmap)
    val fxEnabled = remember { PlayerFxStore.isEnabled(context) }
    // ═══ 播放页主题:原生默认预设 / 网页播放器预设(规范见 PLAYER_THEME_SPEC.md) ═══
    val themeId = remember { PlayerFxStore.selectedPlayerThemeId(context) }
    val webThemeEntry = remember(themeId) {
        if (themeId != PlayerFxStore.THEME_NATIVE) PlayerFxStore.playerThemeEntry(context, themeId) else null
    }

    // 歌词(开启逐字歌词时同步合成逐字时间轴,渲染层直接用)
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    // 歌词导入刷新 token:搜索导入后 +1 触发重新 fetch(命中缓存)
    var lyricImportTick by remember { mutableStateOf(0) }
    val karaokeOn = remember { PlayerFxStore.isKaraokeEnabled(context) }
    LaunchedEffect(track?.id, lyricImportTick) {
        lyrics = emptyList()
        if (track != null) {
            lyricLoading = true
            val fetched = LyricParser.fetch(track, context)
            lyrics = if (karaokeOn) LyricParser.withSyntheticWords(fetched) else fetched
            lyricLoading = false
        }
    }

    // 主题页/歌词页横滑;歌词页进入沉浸模式(全部操作按钮隐藏)
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val lyricMode = pagerState.currentPage == 1
    // 对齐调节:仅点击歌词行后浮现,4 秒未操作自动隐去
    var showLyricOffset by remember { mutableStateOf(false) }
    LaunchedEffect(showLyricOffset) {
        if (showLyricOffset) { delay(4000); showLyricOffset = false }
    }

    // 面板状态 + 播放模式提示
    var openSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var modeToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modeToast) {
        if (modeToast != null) { delay(1200); modeToast = null }
    }

    // 下滑关闭:顶栏拖动,跟手位移,松手判定
    val dismissOffset = remember { Animatable(0f) }

    // 3D 歌单墙:双指缩放进入(需在特效设置中开启)
    val wall3dEnabled = remember { PlayerFxStore.isWall3DEnabled(context) }
    var showWall3D by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalPlayerColors provides colors) {
        Box(Modifier.fillMaxSize()) {
            // ═══ 1. 主题背景(视觉主体)+ 封面氛围层(弱化) ═══
            ImmersiveBackground(coverBitmap = coverBitmap)

            // ═══ 2. 特效层(WebView;启用时才创建) ═══
            if (fxEnabled && !showWall3D) {
                PlayerFxLayer(
                    controller = controller,
                    colors = colors,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ═══ 3. 内容层:网页播放器预设整页替换;否则原生内容(跟手下移 + 淡出) ═══
            if (webThemeEntry != null) {
                WebPlayerView(
                    controller = controller,
                    colors = colors,
                    lyrics = lyrics,
                    onClose = onClose,
                    onOpenQueue = { openSheet = PlayerSheet.QUEUE }
                )
            } else {
            Column(
                Modifier.fillMaxSize().statusBarsPadding()
                    .graphicsLayer {
                        translationY = dismissOffset.value * 0.6f
                        alpha = 1f - (dismissOffset.value / 1400f).coerceIn(0f, 0.6f)
                    }
                    .padding(horizontal = 22.dp)
            ) {
                // 顶栏:收起 + 收藏(整行支持下滑手势关闭)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, drag ->
                                    if (drag > 0) {
                                        scope.launch {
                                            dismissOffset.snapTo((dismissOffset.value + drag).coerceAtLeast(0f))
                                        }
                                        change.consume()
                                    }
                                },
                                onDragEnd = {
                                    if (dismissOffset.value > 150f) onClose()
                                    else scope.launch {
                                        dismissOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { dismissOffset.animateTo(0f, spring(dampingRatio = 0.8f)) }
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NpPressBtn(
                        icon = Icons.Filled.KeyboardArrowDown, contentDescription = "收起",
                        onClick = onClose, btnSize = 40, iconSize = 24
                    )
                    Spacer(Modifier.weight(1f))
                    NpPressBtn(
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFav) "已收藏" else "收藏",
                        onClick = { if (track != null) isFav = FavoritesStore.toggle(context, track) },
                        btnSize = 40, iconSize = 22
                    )
                }

                // 舞台:主题页(弱化封面/主题律动) ↔ 歌词页(Apple Music 效果 + 偏移调节)
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { page ->
                    if (page == 0) {
                        ThemeStage(
                            track = track,
                            coverBitmap = coverBitmap,
                            isPlaying = controller.isPlaying,
                            onCoverClick = { if (wall3dEnabled) showWall3D = true }
                        )
                    } else {
                        AppleMusicLyrics(
                            controller = controller,
                            lyrics = lyrics,
                            loading = lyricLoading,
                            showOffset = showLyricOffset,
                            onLineActivated = { showLyricOffset = true },
                            onSearchLyric = { openSheet = PlayerSheet.LYRIC_SEARCH }
                        )
                    }
                }

                // ═══ 玻璃内容卡:歌词沉浸时全部操作按钮隐藏;卡区域可向下拖拽下缩 ═══
                AnimatedVisibility(
                    visible = !lyricMode,
                    enter = fadeIn(tween(220)) +
                        androidx.compose.animation.expandVertically(animationSpec = tween(240)),
                    exit = fadeOut(tween(160)) +
                        androidx.compose.animation.shrinkVertically(animationSpec = tween(180))
                ) {
                    GlassControlCard(
                        controller = controller,

                        onOpenTools = { openSheet = PlayerSheet.TOOLS },
                        onOpenQueue = { openSheet = PlayerSheet.QUEUE }
                    )
                }
                Spacer(Modifier.navigationBarsPadding())
            }
            }

            // ═══ 4. 主题化底部面板(更多操作菜单/倍速/定时/播放列表/音效) ═══
            ToolsSheet(
                visible = openSheet == PlayerSheet.TOOLS,
                controller = controller,
                isFav = isFav,
                onToggleFav = { if (track != null) isFav = FavoritesStore.toggle(context, track) },
                onModeChange = { mode ->
                    controller.changePlayMode(mode)
                    modeToast = mode.label
                },
                onOpenSpeed = { openSheet = PlayerSheet.SPEED },
                onOpenSleep = { openSheet = PlayerSheet.SLEEP },
                onOpenFx = { openSheet = PlayerSheet.EFFECTS },
                onDismiss = { openSheet = null }
            )
            SpeedSheet(visible = openSheet == PlayerSheet.SPEED, controller = controller, onDismiss = { openSheet = PlayerSheet.TOOLS })
            SleepSheet(visible = openSheet == PlayerSheet.SLEEP, controller = controller, onDismiss = { openSheet = PlayerSheet.TOOLS })
            QueueSheet(visible = openSheet == PlayerSheet.QUEUE, controller = controller, onDismiss = { openSheet = null })
            EffectsSheet(
                visible = openSheet == PlayerSheet.EFFECTS,
                effects = effects,
                onOpenEffectsSettings = onOpenEffects,
                onDismiss = { openSheet = PlayerSheet.TOOLS }
            )
            SpeedSheet(visible = openSheet == PlayerSheet.SPEED, controller = controller, onDismiss = { openSheet = null })
            SleepSheet(visible = openSheet == PlayerSheet.SLEEP, controller = controller, onDismiss = { openSheet = null })
            QueueSheet(visible = openSheet == PlayerSheet.QUEUE, controller = controller, onDismiss = { openSheet = null })
            EffectsSheet(
                visible = openSheet == PlayerSheet.EFFECTS,
                effects = effects,
                onOpenEffectsSettings = onOpenEffects,
                onDismiss = { openSheet = null }
            )
            LyricSearchSheet(
                visible = openSheet == PlayerSheet.LYRIC_SEARCH,
                track = track,
                onImported = { lyricImportTick++ },
                onDismiss = { openSheet = null }
            )

            // ═══ 5. 播放模式切换提示 ═══
            AnimatedVisibility(
                visible = modeToast != null,
                enter = fadeIn(tween(180)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(220)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(0.55f))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(modeToast ?: "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ═══ 6. 3D 歌单墙(点击封面进入;返回按钮退出) ═══
            if (wall3dEnabled) {
                AnimatedVisibility(
                    visible = showWall3D,
                    enter = fadeIn(tween(450)),
                    exit = fadeOut(tween(280))
                ) {
                    go.examate.hyp.music.fx.Wall3DCardView(
                        controller = controller,
                        onBack = { showWall3D = false }
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════
//  背景:主题优先、封面弱化
// ════════════════════════════════════════════════

/**
 * 沉浸式主题背景:Palette 派生垂直渐变(视觉主体)+ 封面氛围层(重模糊+降饱和+低透明)+ 底部暗角。
 */
@Composable
private fun ImmersiveBackground(coverBitmap: Bitmap?) {
    val colors = LocalPlayerColors.current
    val top by animateColorAsState(
        targetValue = colors.fallbackTop,
        animationSpec = tween(600), label = "bgTop"
    )
    val bottom by animateColorAsState(
        targetValue = colors.fallbackBottom,
        animationSpec = tween(600), label = "bgBottom"
    )
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
        if (coverBitmap != null) {
            Crossfade(targetState = coverBitmap, animationSpec = tween(650), label = "coverAmbient") { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) }),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.4f }
                        .blur(46.dp)
                )
            }
        }
        // 底部暗角仅封面背景模式叠加(浅色主题保持明亮,不破坏浅色封面场景美感)
        if (colors.isCoverBacked) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(0.42f)),
                        startY = 600f
                    )
                )
            )
        }
    }
}

/** 主题舞台(第一页):弱化封面卡(存在才显示);无封面为主题律动光环 */
@Composable
private fun ThemeStage(
    track: QueueItem?,
    coverBitmap: Bitmap?,
    isPlaying: Boolean,
    onCoverClick: () -> Unit = {}
) {
    val colors = LocalPlayerColors.current
    val scale by animateFloatAsState(
        if (isPlaying) 1f else 0.94f,
        SpringSpec(dampingRatio = 0.8f, stiffness = 260f),
        label = "stageScale"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (coverBitmap != null) {
            Crossfade(targetState = coverBitmap, animationSpec = tween(450), label = "stageCover") { bmp ->
                Box(
                    Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
                        .clickable { onCoverClick() }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            shadowElevation = 28f
                            shape = RoundedCornerShape(28.dp)
                            clip = true
                        }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = track?.title,
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.82f) }),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            val breath by animateFloatAsState(
                if (isPlaying) 1f else 0.9f,
                SpringSpec(dampingRatio = 1f, stiffness = 180f),
                label = "orbBreath"
            )
            Box(
                Modifier.size(200.dp)
                    .graphicsLayer { scaleX = breath; scaleY = breath; alpha = 0.85f }
                    .clip(RoundedCornerShape(100.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colors.accent.copy(0.55f),
                                colors.accent.copy(0.12f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

// ════════════════════════════════════════════════
//  Apple Music 式歌词(+ 偏移调节)
// ════════════════════════════════════════════════

/**
 * Apple Music 歌词效果 + 歌词偏移调节 + 逐字歌词:
 *  - 当前行 scale 0.82→1.0 + 字号按外观设置(高光歌词大小),spring(damping 1.0)
 *  - 逐字歌词(外观开关):当前行逐字点亮 — 已唱字全亮/正在唱的字放大(放大倍率可调)/
 *    未唱字按非播放透明度压暗;时间轴由 LyricParser.withSyntheticWords 合成
 *  - 非播放行透明度按外观设置(带距离梯度淡化);点击行跳播并触发对齐调节浮现
 *  - 对齐控制(showOffset):底部浮层 提前/延后 0.5s,按曲目持久化(LyricOffsetStore)
 */
@Composable
private fun AppleMusicLyrics(
    controller: MusicController,
    lyrics: List<LyricLine>,
    loading: Boolean,
    showOffset: Boolean,
    onLineActivated: () -> Unit,
    onSearchLyric: () -> Unit = {}
) {
    val context = LocalContext.current
    val colors = LocalPlayerColors.current
    val listState = rememberLazyListState()
    val itemId = controller.current?.id ?: ""
    var offsetMs by remember(itemId) { mutableLongStateOf(LyricOffsetStore.get(context, itemId)) }

    // 外观设置:高光字号 / 逐字放大倍率 / 非播放透明度 / 逐字开关
    val karaokeOn = remember { PlayerFxStore.isKaraokeEnabled(context) }
    val activeSize = remember { PlayerFxStore.lyricActiveSize(context) }
    val karaokeScale = remember { PlayerFxStore.lyricKaraokeScale(context) }
    val inactiveAlpha = remember { PlayerFxStore.lyricInactiveAlpha(context) }
    // 节奏自适应:快歌小幅浮动、慢歌大幅浮动(相邻行间隔中位数映射 0.35~1.0)
    val bounce = remember(lyrics) { LyricParser.paceBounce(lyrics) }

    // 偏移生效:当前行判定 position + offset
    val positionMs = controller.positionMs.toLong() + offsetMs
    val currentIndex = LyricParser.findCurrentIndex(lyrics, positionMs)

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex, scrollOffset = -260)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载歌词中…", color = colors.hint, fontSize = 14.sp)
            }
            lyrics.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无歌词", color = colors.hint, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(colors.accent.copy(0.16f))
                            .clickable(onClick = onSearchLyric)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Search,
                                contentDescription = "搜索歌词",
                                tint = colors.accent, modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("搜索歌词", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            else -> {
                // 当前行上移量(dp→px),低刚度弹簧实现预判性平滑过渡
                val liftPx = with(LocalDensity.current) { 7.dp.toPx() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 200.dp)
                ) {
                    itemsIndexed(lyrics, key = { i, _ -> i }) { i, line ->
                        val active = i == currentIndex
                        // 当前行向上微移;低刚度弹簧让切换有预判性的平滑过渡(非生硬瞬切)
                        val ty by animateFloatAsState(
                            if (active) -liftPx else 0f,
                            spring(dampingRatio = 0.82f, stiffness = 140f),
                            label = "lyricY_$i"
                        )
                        val dist = kotlin.math.abs(i - currentIndex)
                        val alpha = if (active) 1f else (inactiveAlpha / (1 + dist * 0.55f)).coerceAtLeast(0.1f)
                        val rowModifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = ty
                                this.alpha = alpha
                            }
                            // 点击跳播(歌词时间 - 偏移)并浮现对齐调节
                            .clickable {
                                controller.seekTo((line.timeMs - offsetMs).toInt().coerceAtLeast(0))
                                onLineActivated()
                            }
                            .padding(vertical = 9.dp, horizontal = 12.dp)
                        if (active && karaokeOn && line.words.isNotEmpty()) {
                            // 逐字点亮:已唱全亮 / 正在唱的字放大(幅度随节奏收敛) / 未唱压暗
                            KaraokeLine(
                                words = line.words,
                                positionMs = positionMs,
                                fontSize = activeSize,
                                highlightScale = 1f + (karaokeScale - 1f) * bounce,
                                unsungAlpha = inactiveAlpha,
                                modifier = rowModifier
                            )
                        } else {
                            // 统一字号,高亮靠上移/字重/透明度表达 — 行高不变避免跳动
                            Text(
                                text = line.text,
                                color = colors.primary,
                                fontSize = activeSize.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = rowModifier
                            )
                        }
                    }
                }
            }
        }

        // ═══ 对齐调节浮层:仅点击歌词行后浮现,4 秒自动隐去 ═══
        AnimatedVisibility(
            visible = showOffset,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)) + slideInVertically(
                initialOffsetY = { it / 2 }, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(220)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(200))
        ) {
            Row(
                Modifier.padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(0.38f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LyricOffsetBtn("提前 0.5s") {
                    offsetMs += 500
                    LyricOffsetStore.set(context, itemId, offsetMs)
                    onLineActivated()
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .clickable {
                            offsetMs = 0
                            LyricOffsetStore.set(context, itemId, 0)
                            onLineActivated()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        if (offsetMs == 0L) "歌词对齐" else "偏移 %+.1fs".format(offsetMs / 1000f),
                        color = if (offsetMs == 0L) Color.White.copy(0.6f) else Color.White,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                LyricOffsetBtn("延后 0.5s") {
                    offsetMs -= 500
                    LyricOffsetStore.set(context, itemId, offsetMs)
                    onLineActivated()
                }
            }
        }
    }
}

/**
 * 逐字歌词行 — 当前行逐字点亮(Apple Music 效果):
 *  - 已唱字:全亮;正在唱的字:弹簧放大 + **圆形光晕**(径向渐变圆斑,边缘自然衰减);
 *    未唱字:压暗
 *  - scale 用低刚度弹簧(有动量、无硬切),alpha/光晕独立动画跟随 — 避免离散状态突变的生硬感
 *  - 只用于当前行(其余行仍是单个 Text,控制组合开销);光晕只作用于单个字,开销可控
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KaraokeLine(
    words: List<LyricWord>,
    positionMs: Long,
    fontSize: Float,
    highlightScale: Float,
    unsungAlpha: Float,
    modifier: Modifier
) {
    val colors = LocalPlayerColors.current
    // 低刚度弹簧:点亮放大带一点动量、收尾柔和(无过冲,UI 场景不加 bounce)
    val charSpring = SpringSpec<Float>(dampingRatio = 0.85f, stiffness = 210f)
    val alphaSpec = tween<Float>(240, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f))
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center
    ) {
        words.forEachIndexed { wi, w ->
            val singing = positionMs >= w.startMs && positionMs < w.startMs + w.durMs
            val sung = positionMs >= w.startMs + w.durMs
            val scale by animateFloatAsState(
                if (singing) highlightScale else 1f, charSpring, label = "kChar_$wi"
            )
            val alpha by animateFloatAsState(
                if (sung || singing) 1f else unsungAlpha, alphaSpec, label = "kAlpha_$wi"
            )
            // 光晕强度跟随点亮动画淡入(正在唱的字才有)
            val glow by animateFloatAsState(
                if (singing) 0.85f else 0f, alphaSpec, label = "kGlow_$wi"
            )
            val density = LocalDensity.current
            // 圆形光晕:径向渐变圆斑(边缘自然衰减),颜色跟随主题强调色
            val glowSize = with(density) { fontSize.sp.toDp() } * 2.2f
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            ) {
                if (glow > 0.01f) {
                    Box(
                        Modifier.size(glowSize)
                            .graphicsLayer { this.alpha = glow }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        colors.accent.copy(0.5f),
                                        colors.accent.copy(0.16f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
                Text(
                    text = w.text,
                    color = colors.primary,
                    fontSize = fontSize.sp,
                    fontWeight = if (singing) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                )
            }
        }
    }
}

/** 歌词偏移小按钮 */
@Composable
private fun LyricOffsetBtn(label: String, onClick: () -> Unit) {
    val colors = LocalPlayerColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "offsetBtn")
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(0.14f))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = colors.secondary, fontSize = 11.sp)
    }
}

// ════════════════════════════════════════════════
//  玻璃内容卡:标题 / 进度 / 控制 / 工具
// ════════════════════════════════════════════════

/**
 * 玻璃内容卡 — 标题 / 进度 / 控制 / 工具入口:
 *  - 卡区域向下拖动 → 下缩态(只留歌曲信息 + 进度条);上拖或点击指示条恢复
 *  - 卡面叠加深色遮罩,保证白字/白图标在浅色主题上仍清晰(可读性)
 *  - 所有扩展操作(播放模式/倍速/定时/音效/收藏…)收进底部菜单面板,卡面只留核心控制
 */
@Composable
private fun GlassControlCard(
    controller: MusicController,

    onOpenTools: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val colors = LocalPlayerColors.current
    val context = LocalContext.current
    val track = controller.current

    var collapsed by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surface.copy(alpha = colors.surface.alpha.coerceAtMost(0.3f)))
            .pointerInput(Unit) {
                // 下拖下缩 / 上拖恢复(迟滞 26px 防止误触)
                detectVerticalDragGestures(
                    onVerticalDrag = { change, drag ->
                        if (drag > 26f && !collapsed) { collapsed = true; change.consume() }
                        else if (drag < -26f && collapsed) { collapsed = false; change.consume() }
                    },
                    onDragEnd = {}, onDragCancel = {}
                )
            }
            .padding(horizontal = 20.dp, vertical = if (collapsed) 8.dp else 12.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 歌曲名行:歌名+艺术家 在左,[歌单][收藏][更多]按钮在右(仅非下缩态显示)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PlayerMeta(track = controller.current, errorMessage = controller.errorMessage)
                }
                if (!collapsed) {
                    // 歌单按钮
                    NpPressBtn(
                        icon = Icons.Filled.QueueMusic, contentDescription = "播放列表",
                        onClick = onOpenQueue, btnSize = 40, iconSize = 22
                    )
                    Spacer(Modifier.width(6.dp))

                    // 更多操作按钮(打开底部菜单面板,所有扩展操作收进面板,可持续扩展)
                    NpPressBtn(
                        icon = Icons.Filled.UnfoldMore,
                        contentDescription = "更多操作",
                        onClick = onOpenTools, btnSize = 40, iconSize = 22
                    )
                }
            }
            Spacer(Modifier.height(if (collapsed) 4.dp else 10.dp))
            PlayerProgress(controller = controller)
            // 下缩态:只留歌曲信息 + 进度条,点击指示条恢复
            AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(tween(180)) + androidx.compose.animation.expandVertically(tween(200)),
                exit = fadeOut(tween(140)) + androidx.compose.animation.shrinkVertically(tween(160))
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp).height(16.dp)
                        .clickable { collapsed = false },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(0.4f)))
                }
            }
            AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(tween(200)) + androidx.compose.animation.expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + androidx.compose.animation.shrinkVertically(tween(160))
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(4.dp))
                    // 控制行:上一首 / 播放 / 下一首
                    PlayerControls(controller = controller)
                }
            }
        }
    }
}

/** 标题/艺术家/错误行(切歌 slide+fade);文字色随主题(封面→白字,浅色→深字) */
@Composable
private fun PlayerMeta(track: QueueItem?, errorMessage: String) {
    val colors = LocalPlayerColors.current
    androidx.compose.animation.AnimatedContent(
        targetState = track?.id,
        transitionSpec = {
            androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { it / 6 }, animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(280)) togetherWith
                androidx.compose.animation.slideOutHorizontally(
                    targetOffsetX = { -it / 6 }, animationSpec = tween(180, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(180))
        },
        label = "metaSwitch"
    ) { _ ->
        Column(Modifier.fillMaxWidth()) {
            Text(
                track?.title ?: "未在播放",
                color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                track?.artistDisplay() ?: "点一首歌开始",
                color = colors.secondary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (errorMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ $errorMessage", color = Color(0xFFFFB4A9), fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 进度条:1:1 跟手拖动,拖动中 thumb 放大,松手 seek;色彩随主题自适应 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgress(controller: MusicController) {
    val colors = LocalPlayerColors.current
    val track = controller.current
    val dur = (track?.durationMs ?: 0L).coerceAtLeast(1L)
    val pos = controller.positionMs.toFloat().coerceIn(0f, dur.toFloat())
    var dragPos by remember { mutableStateOf<Float?>(null) }
    val displayPos = (dragPos ?: pos).coerceIn(0f, dur.toFloat())
    val dragging = dragPos != null
    val thumbScale by animateFloatAsState(
        if (dragging) 1.35f else 1f, tween(150, easing = FastOutSlowInEasing), label = "thumb"
    )

    Slider(
        value = displayPos,
        onValueChange = { dragPos = it },
        onValueChangeFinished = {
            controller.seekTo((dragPos ?: pos).toInt())
            dragPos = null
        },
        valueRange = 0f..dur.toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = colors.primary,
            activeTrackColor = colors.primary.copy(0.9f),
            inactiveTrackColor = colors.primary.copy(0.22f)
        ),
        thumb = {
            Box(
                Modifier.size((14 * thumbScale).dp).clip(RoundedCornerShape(7.dp))
                    .background(colors.primary)
            )
        }
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmt(displayPos.toInt()), color = colors.hint, fontSize = 11.sp)
        Text(fmt(dur.toInt()), color = colors.hint, fontSize = 11.sp)
    }
}

/** 控制行:上一首 / 播放 / 下一首(播放模式与列表已移至歌名行/展开区) */
@Composable
private fun PlayerControls(
    controller: MusicController
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NpPressBtn(
            icon = Icons.Filled.SkipPrevious, contentDescription = "上一首",
            onClick = { controller.prev() }, btnSize = 52, iconSize = 30
        )
        PlayPauseButton(isPlaying = controller.isPlaying, onClick = { controller.toggle() })
        NpPressBtn(
            icon = Icons.Filled.SkipNext, contentDescription = "下一首",
            onClick = { controller.next() }, btnSize = 52, iconSize = 30
        )
    }
}

/** 主播放键 — 按下即缩放,播放/暂停图标交叉淡切;浅色场景轻描边+轻阴影保立体感(无封面浅色背景上白圆键需要边缘定义) */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val colors = LocalPlayerColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f, tween(120, easing = FastOutSlowInEasing), label = "playPress"
    )
    val lightScene = !colors.isCoverBacked && !IsDark
    Box(
        Modifier.size(72.dp)
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                if (lightScene) { shadowElevation = 8f; shape = RoundedCornerShape(36.dp); clip = false }
            }
            .clip(RoundedCornerShape(36.dp))
            .background(Color.White)
            .then(
                if (lightScene) Modifier.border(1.dp, Color.Black.copy(0.06f), RoundedCornerShape(36.dp))
                else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = isPlaying, animationSpec = tween(160), label = "playIcon") { playing ->
            if (playing) {
                Icon(Icons.Filled.Pause, contentDescription = "暂停", tint = Color(0xFF1A1A2E), modifier = Modifier.size(34.dp))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color(0xFF1A1A2E), modifier = Modifier.size(38.dp))
            }
        }
    }
}


// ════════════════════════════════════════════════
//  主题化底部面板(替代系统 DropdownMenu)
// ════════════════════════════════════════════════

/** 通用底部面板:遮罩 + 上滑圆角卡片 */
@Composable
private fun PlayerSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160))
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.38f)))
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200, easing = FastOutSlowInEasing))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(DialogBg)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .navigationBarsPadding()
                    // 键盘弹出时面板整体上移(歌词搜索等含输入框的面板不被键盘遮挡)
                    .imePadding()
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(14.dp))
                            .background(Primary.copy(0.1f)).clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "关闭", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

/**
 * 更多操作菜单面板 — 所有扩展操作的统一入口(可持续扩展):
 * 播放模式 / 倍速 / 定时停止 / 音效 / 收藏 / 播放列表。
 * 每项以行列表形式呈现(图标 + 标题 + 当前值/状态),点击执行操作或打开子面板。
 */
@Composable
private fun ToolsSheet(
    visible: Boolean,
    controller: MusicController,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onModeChange: (PlayMode) -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenFx: () -> Unit,
    onDismiss: () -> Unit
) {
    PlayerSheet(visible = visible, title = "更多操作", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            // 播放模式
            val modeIcon = when (controller.playMode) {
                PlayMode.LIST_LOOP -> Icons.Filled.Repeat
                PlayMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
                PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                PlayMode.ORDER -> Icons.AutoMirrored.Filled.PlaylistPlay
            }
            ToolMenuItem(
                icon = modeIcon,
                title = "播放模式",
                value = controller.playMode.label,
                onClick = {
                    val modes = PlayMode.entries
                    val next = modes[(modes.indexOf(controller.playMode) + 1) % modes.size]
                    onModeChange(next)
                }
            )
            // 倍速
            ToolMenuItem(
                icon = Icons.Filled.Speed,
                title = "播放倍速",
                value = "${controller.speed}x",
                onClick = { onOpenSpeed() }
            )
            // 定时停止
            val sleeping = controller.sleepRemainingMs > 0
            ToolMenuItem(
                icon = if (sleeping) Icons.Filled.Timer else Icons.Outlined.Timer,
                title = "定时停止",
                value = if (sleeping) controller.sleepText() else "关闭",
                valueHighlight = sleeping,
                onClick = { onOpenSleep() }
            )
            // 音效
            ToolMenuItem(
                icon = Icons.Filled.Equalizer,
                title = "音效",
                value = "调节",
                onClick = { onOpenFx() }
            )
            // 收藏
            ToolMenuItem(
                icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                title = "收藏",
                value = if (isFav) "已收藏" else "",
                valueHighlight = isFav,
                onClick = onToggleFav
            )
        }
    }
}

/** 工具菜单项行:图标 + 标题 + 当前值(右对齐,可高亮) */
@Composable
private fun ToolMenuItem(
    icon: ImageVector,
    title: String,
    value: String,
    valueHighlight: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalPlayerColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = colors.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value,
                color = if (valueHighlight) colors.primary else TextHint,
                fontSize = 13.sp,
                fontWeight = if (valueHighlight) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

/** 倍速面板 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(visible: Boolean, controller: MusicController, onDismiss: () -> Unit) {
    PlayerSheet(visible = visible, title = "播放倍速", onDismiss = onDismiss) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                val sel = controller.speed == r
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Primary else Primary.copy(0.08f))
                        .clickable { controller.changeSpeed(r); onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (r == 1f) "正常" else "${r}x",
                        color = if (sel) Color.White else TextSecondary,
                        fontSize = 14.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 定时面板 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepSheet(visible: Boolean, controller: MusicController, onDismiss: () -> Unit) {
    PlayerSheet(visible = visible, title = "定时停止", onDismiss = onDismiss) {
        if (controller.sleepRemainingMs > 0) {
            Text(
                "剩余 ${controller.sleepText()}",
                color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(15, 30, 60).forEach { m ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Primary.copy(0.08f))
                        .clickable { controller.scheduleSleep(m); onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$m 分钟", color = TextSecondary, fontSize = 14.sp)
                }
            }
            if (controller.sleepRemainingMs > 0) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Primary.copy(0.08f))
                        .clickable { controller.cancelSleep(); onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("取消定时", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 播放列表面板:队列展示 + 点选跳播 */
@Composable
private fun QueueSheet(visible: Boolean, controller: MusicController, onDismiss: () -> Unit) {
    val queue = controller.queueItems
    PlayerSheet(visible = visible, title = "播放列表 · ${queue.size} 首", onDismiss = onDismiss) {
        if (queue.isEmpty()) {
            Text("队列为空,去挑一首歌吧", color = TextHint, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center)
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                itemsIndexed(queue, key = { i, it -> "${it.id}_$i" }) { index, item ->
                    val active = controller.current?.id == item.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (active) Primary.copy(0.12f) else Color.Transparent)
                            .clickable { controller.playIndex(index); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MusicCover(
                            item = item, active = active, isPlaying = controller.isPlaying,
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = if (active) Primary else TextPrimary, fontSize = 13.sp,
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
}

/**
 * 歌词搜索面板:暂无歌词时,按 歌名+歌手 关键词在酷狗歌词库搜索候选,
 * 选定后下载 LRC、写入 [MusicDataCache](键 = [LyricParser.cacheKeyFor]),
 * 然后回调 [onImported] 触发播放页重新 fetch(命中刚写入的缓存)。
 */
@Composable
private fun LyricSearchSheet(
    visible: Boolean,
    track: QueueItem?,
    onImported: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 预填关键词:歌名 + 空格 + 歌手(去重空格)
    val prefill = remember(track?.id) {
        val t = track?.title.orEmpty(); val a = track?.artist.orEmpty()
        listOf(t, a).filter { it.isNotBlank() }.joinToString(" ").trim()
    }
    var keyword by remember(visible, track?.id) { mutableStateOf(prefill) }
    val results = remember(visible, track?.id) { mutableStateListOf<KugouCandidate>() }
    var searching by remember(visible, track?.id) { mutableStateOf(false) }
    var importing by remember(visible, track?.id) { mutableStateOf(false) }
    var hint by remember(visible, track?.id) { mutableStateOf("") }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        searching = true; hint = ""; results.clear()
        scope.launch {
            val list = withContext(Dispatchers.IO) { KugouLyricApi.searchCandidates(kw) }
            results.clear(); results.addAll(list)
            if (list.isEmpty()) hint = "未找到歌词,换个关键词试试"
            searching = false
        }
    }

    // 面板打开时自动触发一次预填关键词搜索
    LaunchedEffect(visible, prefill) {
        if (visible && prefill.isNotEmpty()) doSearch()
    }

    PlayerSheet(visible = visible, title = "搜索歌词", onDismiss = onDismiss) {
        // 搜索框:回车即搜
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(ChipBg.copy(0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = keyword,
                onValueChange = { keyword = it },
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                modifier = Modifier.weight(1f)
            )
            if (keyword.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { keyword = "" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "清空", tint = TextHint, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 状态提示
        when {
            searching -> Text("搜索中…", color = TextHint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            importing -> Text("导入中…", color = Primary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            hint.isNotEmpty() -> Text(hint, color = TextHint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            results.isEmpty() && prefill.isEmpty() -> Text(
                "输入歌名或歌手搜索歌词", color = TextHint, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        // 候选列表
        if (results.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                itemsIndexed(results, key = { _, c -> "${c.id}_${c.accesskey}" }) { _, c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !importing) {
                                importing = true
                                scope.launch {
                                    val lrc = withContext(Dispatchers.IO) { KugouLyricApi.downloadLyric(c) }
                                    if (!lrc.isNullOrBlank() && track != null) {
                                        MusicDataCache.put(context, LyricParser.cacheKeyFor(track), lrc)
                                        onImported()
                                        onDismiss()
                                    } else {
                                        hint = "歌词获取失败,请重试其他候选"
                                    }
                                    importing = false
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { "未知曲目" }, color = TextPrimary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.singer.ifBlank { "未知歌手" }, color = TextHint, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (c.durationMs > 0) {
                            val s = (c.durationMs / 1000).toInt()
                            Text("%d:%02d".format(s / 60, s % 60), color = TextHint, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 音效面板:启用开关 + 内置音效配置列表 */
@Composable
private fun EffectsSheet(
    visible: Boolean,
    effects: AudioEffects?,
    onOpenEffectsSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    PlayerSheet(visible = visible, title = "音效", onDismiss = onDismiss) {
        if (effects == null) {
            Text("音效不可用", color = TextHint, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), textAlign = TextAlign.Center)
        } else {
            // 启用开关
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Primary.copy(0.06f))
                    .clickable { effects.updateEnabled(!effects.enabled) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用音效", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("基于系统 AudioEffect,效果因设备而异", color = TextHint, fontSize = 11.sp)
                }
                Icon(
                    if (effects.enabled) Icons.Filled.Check else Icons.Filled.Equalizer,
                    contentDescription = null,
                    tint = if (effects.enabled) Primary else TextHint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            // 内置音效配置列表
            LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                // "原声(关闭配置)" 选项
                item(key = "fx_none") {
                    FxProfileRow(
                        name = "原声", desc = "不应用内置配置",
                        selected = effects.fxProfileId == AudioEffects.FX_NONE,
                        onClick = { effects.setFxProfile(AudioEffects.FX_NONE) }
                    )
                }
                itemsIndexed(AudioEffects.builtinProfiles, key = { _, p -> p.id }) { _, p ->
                    FxProfileRow(
                        name = p.name, desc = p.desc,
                        selected = effects.fxProfileId == p.id,
                        onClick = {
                            effects.setFxProfile(p.id)
                            if (!effects.enabled) effects.updateEnabled(true)
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Primary.copy(0.08f))
                    .clickable { onDismiss(); onOpenEffectsSettings() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("更多调节(低音/环绕/均衡器)", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 音效配置行 */
@Composable
private fun FxProfileRow(name: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Primary.copy(0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) Primary else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
        }
    }
}

// ════════════════════════════════════════════════
//  按钮
// ════════════════════════════════════════════════

/** 圆形玻璃按钮 — 按下即缩放(pointer-down 即时反馈);统一深色态玻璃样式(面/图标色来自主题) */
@Composable
private fun NpPressBtn(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    btnSize: Int = 40,
    iconSize: Int = 22,
    tint: Color? = null
) {
    val colors = LocalPlayerColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f, tween(120, easing = FastOutSlowInEasing), label = "btnPress"
    )
    Box(
        Modifier.size(btnSize.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape((btnSize / 2).dp))
            .background(colors.btnFace)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint ?: colors.btnIcon, modifier = Modifier.size(iconSize.dp))
    }
}


private fun fmt(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
