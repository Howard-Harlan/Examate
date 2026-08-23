package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.downloader.formatBytes
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.CacheCleaner
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.launch

/**
 * 系统设置
 *
 * 聚合原设置页"系统功能设置"分组的三项(藏匿模式 / 首页设置 / 电池优化),
 * 并新增"清除缓存"。样式对齐 Agents 设置页(顶栏 + LazyColumn 分组卡片)。
 *
 * 自持有 [PermissionHelper] 实例(在 onCreate 中构造以完成 launcher 注册),
 * 藏匿模式与电池优化不再依赖 MainActivity。
 */
class SystemSettingsActivity : ComponentActivity() {

    private lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionHelper = PermissionHelper(this).apply { onCreate() }
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    SystemSettingsScreen(
                        permissionHelper = permissionHelper,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionHelper.onDestroy()
    }
}

@Composable
private fun SystemSettingsScreen(permissionHelper: PermissionHelper, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    // ON_RESUME 时重查授权状态与缓存大小(从系统授权页/其它页返回后刷新)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val shizukuGranted = remember(refreshTick) { permissionHelper.isShizukuGranted() }

    // 首页设置
    var showHomePicker by remember { mutableStateOf(false) }
    var defaultTab by remember { mutableIntStateOf(prefs.defaultHomeTab) }

    // 主题模式
    var showThemePicker by remember { mutableStateOf(false) }
    var themeMode by remember { mutableIntStateOf(prefs.themeMode) }

    // 清除缓存
    var showClearDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTick) {
        cacheSize = CacheCleaner.calculateSize(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏:返回 + 居中标题 ═══
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterStart)
                )
                Text(
                    text = "系统设置",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // ═══ 内容区 ═══
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── 藏匿模式 ──
                item { SectionHeader("藏匿模式") }
                item {
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Shizuku 状态", color = TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = if (shizukuGranted) "已授权" else "未授权",
                                    color = if (shizukuGranted) Success else Danger,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            LiquidGlassButton(
                                text = if (shizukuGranted) "启动藏匿模式" else "授权并启动藏匿模式",
                                onClick = { permissionHelper.startShizukuMode() },
                                style = ButtonStyle.Outline,
                                height = 44.dp
                            )
                        }
                    }
                }

                // ── 常规 ──
                item { SectionHeader("常规") }
                item {
                    FrostedGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showThemePicker = true }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("深色模式", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    when (themeMode) {
                                        1 -> "浅色"
                                        2 -> "深色"
                                        else -> "跟随系统"
                                    },
                                    color = TextHint,
                                    fontSize = 11.sp
                                )
                            }
                            Text("›", color = TextHint, fontSize = 20.sp)
                        }
                    }
                }
                item {
                    LiquidGlassButton(
                        text = "首页设置",
                        onClick = { showHomePicker = true },
                        style = ButtonStyle.Outline,
                        height = 44.dp
                    )
                }
                item {
                    LiquidGlassButton(
                        text = "电池优化设置",
                        onClick = { permissionHelper.requestBatteryOptimization() },
                        style = ButtonStyle.Outline,
                        height = 44.dp
                    )
                }

                // ── 存储 ──
                item { SectionHeader("存储") }
                item {
                    val subtitle = when {
                        clearing -> "正在清理…"
                        cacheSize == null -> "计算中…"
                        else -> "音乐 / 阅读 / 下载 / 图片等 · ${formatBytes(cacheSize!!)}，点按分类管理"
                    }
                    FrostedGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !clearing) {
                                    // 分类缓存管理页:音乐/阅读/下载/图片/网页/其他 分门别类清理
                                    context.startActivity(android.content.Intent(context, CacheManagerActivity::class.java))
                                }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("缓存管理", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text(subtitle, color = TextHint, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                            Text("›", color = TextHint, fontSize = 20.sp)
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    // 首页设置弹窗
    if (showHomePicker) {
        Dialog(onDismissRequest = { showHomePicker = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "默认首页",
                    color = TextHint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                listOf(0 to "慎思页", 1 to "探索页").forEach { (tab, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.defaultHomeTab = tab
                                defaultTab = tab
                                showHomePicker = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (tab == defaultTab) {
                            Text("✓", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    // 主题模式弹窗
    if (showThemePicker) {
        Dialog(onDismissRequest = { showThemePicker = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "深色模式",
                    color = TextHint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.themeMode = mode
                                themeMode = mode
                                showThemePicker = false
                                // 主题即时生效:ExamateTheme 监听 SharedPreferences,
                                // 所有在栈中的 Activity 都会自动重组刷新,无需重建
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (mode == themeMode) {
                            Text("✓", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = TextHint, fontSize = 11.sp)
    }
}
