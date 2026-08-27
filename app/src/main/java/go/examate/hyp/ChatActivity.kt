package go.examate.hyp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import go.examate.hyp.ui.anim.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.chat.ActionCardView
import go.examate.hyp.ui.chat.ConversationGraph
import go.examate.hyp.ui.chat.DynamicInputBar
import go.examate.hyp.ui.chat.HistoryPanel
import go.examate.hyp.ui.chat.MessageBubble
import go.examate.hyp.ui.chat.MessageBranchFooter
import go.examate.hyp.ui.chat.ModelSelector
import go.examate.hyp.ui.chat.ToolStepsTimeline
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.GlassChipTint
import go.examate.hyp.ui.theme.GlassInputTint
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ChatHistoryManager
import go.examate.hyp.util.ChatMsg
import java.text.SimpleDateFormat
import java.util.Locale

class ChatActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAUNCH_MODE = "launch_mode"
        const val MODE_CHAT_ONLY = 2
    }

    /** 权限助手:搜题快捷按钮复用深层学习页同款启动链 */
    private lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 初始化权限助手(内部会注册 launcher 与 Shizuku 监听)
        permissionHelper = PermissionHelper(this).apply { onCreate() }
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    ChatScreen(permissionHelper = permissionHelper, onBack = { finish() })
                }
            }
        }
    }

    override fun onDestroy() {
        permissionHelper.onDestroy()
        super.onDestroy()
    }
}

@Composable
private fun ChatScreen(permissionHelper: PermissionHelper, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    // providers 保持可刷新:从设置页返回后重新加载,并在切换模型时回写配置
    var providers by remember { mutableStateOf(MyConfig.loadProviders(context)) }
    // 从 API 设置/Agent 设置返回时热刷新厂商列表(含刚保存的模型切换)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                providers = MyConfig.loadProviders(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val historyTimeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val backdrop = LocalGlassBackdrop.current
    val onQuickFollowUp = remember {
        { source: String -> vm.updateInputText("关于这段内容:\n${source.take(800)}\n\n") }
    }

    // 图谱开启时:系统返回键关闭图谱面板,而非退出页面
    BackHandler(enabled = ui.graphMode) { vm.closeGraph() }
    // 历史面板展开时:返回键先收起面板
    BackHandler(enabled = ui.showHistory && !ui.graphMode) { vm.dismissHistory() }

    // ═══ 生图模式:开启后发送直达生图管线,不进 Agent ═══
    var imageGenMode by rememberSaveable { mutableStateOf(false) }
    val toggleImageGen: () -> Unit = {
        imageGenMode = !imageGenMode
        // 生图只吃文字描述,进入时清掉待发图片
        if (imageGenMode && ui.pendingImageUris.isNotEmpty()) vm.clearPendingImages()
    }
    // 求是:把当前输入(或最近一条 AI 回复)丢进独立的求是悬浮窗
    val onQiushiQuick: () -> Unit = {
        val content = ui.inputText.trim().ifEmpty {
            ui.messages.lastOrNull { it.role == "assistant" && !it.isError }?.content.orEmpty()
        }
        if (content.isNotBlank()) {
            val intent = android.content.Intent(context, go.examate.hyp.service.QiushiFloatingService::class.java)
            intent.putExtra(go.examate.hyp.service.QiushiFloatingService.EXTRA_CONTENT, content)
            context.startService(intent)
        }
    }
    // 搜题:复用深层学习页同款模式选择弹窗 + PermissionHelper 启动链
    var showSearchLaunch by remember { mutableStateOf(false) }
    val onSearchQuick: () -> Unit = { showSearchLaunch = true }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addPendingImages(uris.map { it.toString() })
    }
    var cameraUriStr by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUriStr?.let { vm.addPendingImages(listOf(it)) }
    }
    val onTakePhoto = {
        val file = java.io.File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUriStr = uri.toString()
        cameraLauncher.launch(uri)
    }
    // 文件附件:拷入工作区,并在输入框提示 AI 用 read_file 读取
    val chatFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val rel = go.examate.hyp.agent.FileTools.importToWorkspace(context, uri)
            if (rel != null) {
                go.examate.hyp.util.ToastUtil.show(context, "已上传到工作区: $rel")
                vm.updateInputText((ui.inputText.trimEnd() + "\n[已上传文件到工作区: $rel,请用 read_file 读取]").trim())
            } else go.examate.hyp.util.ToastUtil.show(context, "文件上传失败")
        }
    }

    LaunchedEffect(Unit) {
        if (ui.messages.isEmpty()) {
            val sessions = ChatHistoryManager.loadAll(context)
            val latest = sessions.firstOrNull()
            if (latest != null && latest.messages.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - latest.updateTime
                if (elapsed < 30 * 60 * 1000L) {
                    vm.selectSession(latest)
                }
            }
        }
    }

    // reverseLayout=true 时,index 0 在底部,新消息自动出现在底部
    LaunchedEffect(ui.currentSession.id) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(ui.messages.size, ui.activeLeafId) {
        vm.saveCurrentSession(context)
    }

    // 聊天图片持久目录清理:删除未被任何历史会话引用且超过 7 天的文件
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val referenced = ChatHistoryManager.loadAll(context)
                .flatMap { it.messages }
                .flatMap { m -> m.imageUris.ifEmpty { listOfNotNull(m.imageUriString) } }
                .filter { it.startsWith("/") }
                .toSet()
            go.examate.hyp.util.ChatMediaStore.cleanup(context, referenced)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
    ) {
        // ═══ 聊天背景图(用户可在 Agent 设置中配置;为空时使用默认纯色) ═══
        val bgPath = remember { PrefsManager.getInstance(context).chatBackgroundPath }
        if (!bgPath.isBlank() && java.io.File(bgPath).exists()) {
            val bitmap = remember(bgPath) {
                runCatching {
                    // 先读尺寸,按屏幕宽度计算采样率,避免大图 OOM
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(bgPath, bounds)
                    val reqW = context.resources.displayMetrics.widthPixels
                    var sample = 1
                    while (bounds.outWidth / sample / 2 >= reqW) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeFile(bgPath, opts)
                }.getOrNull()
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }

        // ═══ 消息列表(reverseLayout:消息从底部开始,新消息自动出现在底部) ═══
        // 已入场动画的消息 id(在列表层记忆,滚动回来不重播入场动画)
        val animatedIds = remember { mutableSetOf<String>() }
        // 激活路径在组合期计算并缓存(避免每次重组都全树遍历)
        val path = remember(ui.messages, ui.activeLeafId) { ui.activePath() }
        val reversedPath = remember(path) { path.reversed() }
        val activePathIds = remember(path) { path.mapTo(HashSet()) { it.id } }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 0.dp, end = 0.dp,
                top = 64.dp,   // 顶栏高度+留白
                bottom = 90.dp // 输入栏高度+留白
            )
        ) {
            if (ui.messages.isEmpty() && ui.streamingText.isEmpty() && !ui.isLoading) {
                // ★ 欢迎空状态:极简标识 + 未配置 API 引导
                item(key = "welcome") {
                    ChatWelcomeState(
                        providersEmpty = providers.isEmpty(),
                        onGoConfig = {
                            context.startActivity(android.content.Intent(context, ApiConfigActivity::class.java))
                        }
                    )
                }
            } else {
                // ★ 底部:流式文本 或 加载指示器
                if (ui.streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        // 流式打字光标:每 ~530ms 闪烁一次
                        val blink by produceState(true) { while (true) { delay(530); value = !value } }
                        MessageBubble(
                            ChatMsg("assistant", ui.streamingText + if (blink) "  ▍" else "", false, System.currentTimeMillis()),
                            timeFmt, context, onQuickFollowUp = onQuickFollowUp,
                            isStreaming = true
                        )
                    }
                } else if (ui.isLoading) {
                    item(key = "loading") {
                        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            // ★ 链式思考容器:加载中实时展示工具调用步骤
                            if (ui.currentToolSteps.isNotEmpty()) {
                                ToolStepsTimeline(
                                    steps = ui.currentToolSteps,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = TextHint)
                            if (ui.loadingStatus.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(ui.loadingStatus, color = TextHint, fontSize = 12.sp)
                            }
                        }
                    }
                }
                // ★ 工具进度卡片
                ui.toolProgressCards.forEachIndexed { index, card ->
                    item(key = "card_$index") { ActionCardView(card) }
                }
                // ★ 消息列表:渲染当前激活路径(reversed:最新在底部)
                itemsIndexed(reversedPath, key = { _, it -> it.id }) { index, message ->
                    // 仅最后一条消息(且非加载/流式状态)显示操作行
                    val isLast = index == 0
                    // 时间戳分组:与上一条(更新的)消息间隔超过 5 分钟才显示,减少视觉噪音
                    val showTime = index == 0 ||
                        (reversedPath[index - 1].time - message.time) > 5 * 60_000L
                    // 入场动画:仅消息首次出现时播淡入+上滑;滚动回来不重播
                    val playEntrance = remember(message.id) { animatedIds.add(message.id) }
                    val appear = remember(message.id) { Animatable(if (playEntrance) 0f else 1f) }
                    val slidePx = with(LocalDensity.current) { 10.dp.toPx() }
                    LaunchedEffect(message.id) {
                        if (playEntrance) appear.animateTo(1f, tween(Motion.NORMAL, easing = Motion.Easing))
                    }
                    // animateItem:切换分支/非破坏式重生导致激活路径变化时平滑位移
                    Column(
                        Modifier
                            .animateItem()
                            .graphicsLayer {
                                alpha = appear.value
                                translationY = (1f - appear.value) * slidePx
                            }
                    ) {
                        MessageBubble(
                            message, timeFmt, context,
                            showTimestamp = showTime,
                            onQuickFollowUp = onQuickFollowUp,
                            onRegenerate = { vm.regenerate(message.id, providers, context) },
                            onDelete = { vm.deleteMessage(message.id) },
                            onBranch = { vm.startBranchFrom(message.id) },
                            onSaveToNote = { content ->
                                val ctx = context
                                kotlin.concurrent.thread {
                                    // 保存为真正的笔记(进入笔记页面),而非落一个孤立文件
                                    val title = content.lineSequence().firstOrNull { it.isNotBlank() }
                                        ?.trim()?.take(20) ?: "AI 对话笔记"
                                    go.examate.hyp.notes.NoteStorage.createNote(ctx, title, content)
                                    go.examate.hyp.util.ToastUtil.show(ctx, "已保存到笔记")
                                }
                            },
                            onQiushi = { content ->
                                val intent = android.content.Intent(context, go.examate.hyp.service.QiushiFloatingService::class.java)
                                intent.putExtra(go.examate.hyp.service.QiushiFloatingService.EXTRA_CONTENT, content)
                                context.startService(intent)
                            },
                            showActions = isLast && !ui.isLoading && ui.streamingText.isEmpty()
                        )
                        // 分支页脚:分支切换器 + 追问(开分支)。生成中隐藏,避免中途改动树结构
                        if (!ui.isLoading && ui.streamingText.isEmpty()) {
                            MessageBranchFooter(
                                message = message,
                                allMessages = ui.messages,
                                activePathIds = activePathIds,
                                onSwitch = { forward -> vm.switchBranch(message.id, forward) }
                            )
                        }
                    }
                }
            }
        }

        // ═══ 顶栏:悬浮液态玻璃(单行:返回 / 模型选择 / 历史 / 设置) ═══
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.42f,
                ambientHighlight = true
            )
            Spacer(Modifier.width(6.dp))
            // 模型选择(液态玻璃胶囊包裹;主题色在组合上下文读取,供 onDrawSurface 使用)
            val chipTint = GlassChipTint
            val inputTint = GlassInputTint
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(12f) },
                            highlight = { Highlight.Ambient },
                            onDrawSurface = { drawRect(chipTint) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(20.dp)).background(inputTint)
                    }
                )
            ) {
                ModelSelector(
                    providers = providers,
                    selectedIndex = ui.modelIndex,
                    onSelect = { vm.loadModelIndex(it) }
                )
            }
            Spacer(Modifier.weight(1f))
            // 分支图谱按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.Filled.AccountTree,
                contentDescription = "分支图谱",
                onClick = { vm.toggleGraphMode() },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.42f,
                ambientHighlight = true
            )
            Spacer(Modifier.width(6.dp))
            // 历史按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.Filled.Menu,
                contentDescription = "历史",
                onClick = { vm.loadHistory(context) },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.42f,
                ambientHighlight = true
            )
            Spacer(Modifier.width(6.dp))
            // 设置按钮 — 液态玻璃胶囊
            LiquidGlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "设置",
                onClick = {
                    context.startActivity(android.content.Intent(context, AgentSettingsActivity::class.java))
                },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.42f,
                ambientHighlight = true
            )
        }

        // ═══ 回到底部按钮:向上滚动查看历史消息时出现 ═══
        val scope = rememberCoroutineScope()
        val showJump by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 2 }
        }
        AnimatedVisibility(
            visible = showJump,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 104.dp),
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            LiquidGlassIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "回到底部",
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.size(40.dp),
                surfaceAlpha = 0.42f,
                ambientHighlight = true
            )
        }

        // ═══ 底部输入栏(开分支时上方显示提示横幅) ═══
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (ui.branchAnchorId != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⊃ 正在从此处开分支提问", color = AccentBlue, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("取消", color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.clickable { vm.cancelBranch() }.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Box(Modifier.padding(bottom = 6.dp)) {
                DynamicInputBar(
                    text = ui.inputText,
                    onTextChange = { vm.updateInputText(it) },
                    imageUris = ui.pendingImageUris,
                    onPickGallery = { galleryPicker.launch("image/*") },
                    onTakePhoto = onTakePhoto,
                    onPickFile = { chatFilePicker.launch(arrayOf("*/*")) },
                    onRemoveImageAt = { vm.removePendingImageAt(it) },
                    canSend = if (imageGenMode) {
                        ui.inputText.isNotBlank() && !ui.isLoading && providers.isNotEmpty()
                    } else {
                        (ui.inputText.isNotBlank() || ui.pendingImageUris.isNotEmpty()) && !ui.isLoading && providers.isNotEmpty()
                    },
                    isLoading = ui.isLoading,
                    onSend = {
                        val text = ui.inputText.trim()
                        if (imageGenMode) {
                            if (text.isEmpty()) return@DynamicInputBar
                            vm.generateImage(context, text)
                        } else {
                            if (text.isEmpty() && ui.pendingImageUris.isEmpty()) return@DynamicInputBar
                            vm.send(text, ui.pendingImageUris.map { Uri.parse(it) }, providers, context)
                        }
                    },
                    onStop = { vm.stopSend() },
                    imageGenMode = imageGenMode,
                    onToggleImageGen = toggleImageGen,
                    onQiushi = onQiushiQuick,
                    onSearch = onSearchQuick
                )
            }
        }
    }

    AnimatedVisibility(
        visible = ui.showHistory,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut()
    ) {
        HistoryPanel(
            sessions = ui.historyList,
            timeFmt = historyTimeFmt,
            currentSessionId = ui.currentSession.id,
            onNewChat = { vm.clearMessages() },
            onSessionClick = { session -> vm.selectSession(session) },
            onDelete = { id -> vm.deleteSession(context, id) },
            onDismiss = { vm.dismissHistory() }
        )
    }

    // ═══ 搜题启动模式弹窗(与深层学习页同一组件) ═══
    if (showSearchLaunch) {
        go.examate.hyp.ui.components.SearchLaunchDialog(
            onDismiss = { showSearchLaunch = false },
            onLaunchAccessibility = { permissionHelper.startManualMode() },
            onLaunchScreenshot = { permissionHelper.startScreenshotMode() }
        )
    }

    // ═══ 知识图谱视图(全屏覆盖):点击节点聚焦回线性视图 ═══
    AnimatedVisibility(
        visible = ui.graphMode,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        ConversationGraph(
            messages = ui.messages,
            activePathIds = remember(ui.messages, ui.activeLeafId) { ui.activePath().mapTo(HashSet()) { it.id } },
            onNodeClick = { vm.focusNode(it) },
            onClose = { vm.closeGraph() }
        )
    }
}

// ════════════════════════════════════════
//  欢迎空状态
// ════════════════════════════════════════

/**
 * 欢迎空状态 — 极简:仅标识;未配置模型 API 时展示"去配置"引导,
 * 避免发送按钮静默禁用让用户摸不着头脑。
 */
@Composable
private fun ChatWelcomeState(
    providersEmpty: Boolean,
    onGoConfig: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 110.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✦", color = AccentBlue, fontSize = 34.sp)
        Spacer(Modifier.height(14.dp))
        Text("智源奇点", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)

        if (providersEmpty) {
            Spacer(Modifier.height(22.dp))
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(GlassChipTint)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有配置模型 API,发送前先完成配置", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "去配置 API",
                    color = AccentBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentBlue.copy(alpha = 0.12f))
                        .clickable { onGoConfig() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
