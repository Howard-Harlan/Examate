package go.examate.hyp.ui.screens

import android.os.Build
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    shizukuGranted: Boolean,
    forbidCapture: Boolean,
    edgeAttach: Boolean,
    transparentFloat: Boolean,
    multiAiVerify: Boolean,
    providerCount: Int,
    onUserSettingsClick: () -> Unit,
    onShizukuClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onBatteryOptClick: () -> Unit,
    onForbidCaptureChanged: (Boolean) -> Unit,
    onEdgeAttachChanged: (Boolean) -> Unit,
    onTransparentFloatChanged: (Boolean) -> Unit,
    onMultiAiVerifyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)

    // 从用户设置页返回后需刷新:监听生命周期 ON_RESUME 触发重组
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 读取用户资料,拼接 profileKey 作为 remember 的 key,数据变化时重组
    val nickname = prefs.nickname
    val school = prefs.school
    val displayFields = prefs.userDisplayFields
    val avatarPath = prefs.localAvatarPath
    val profileKey = "$refreshTick|$nickname|$school|${displayFields.sorted().joinToString()}|$avatarPath"

    Column(
        modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
            .padding(bottom = 120.dp)
    ) {
        // ═══ 顶部用户信息栏(logo 栏)═══
        UserProfileBar(profileKey = profileKey, onClick = onUserSettingsClick)
        Spacer(Modifier.height(24.dp))

        // ═══ AI 设置 ═══
        SectionLabel("AI 设置")
        Spacer(Modifier.height(12.dp))
        LiquidGlassButton(
            text = "API 厂商设置",
            onClick = onApiConfigClick,
            style = ButtonStyle.Outline,
            height = 44.dp
        )
        Spacer(Modifier.height(12.dp))
        // Agent 设置:Skills + 记忆
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onAgentSettingsClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Agent 设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Skills 技能导入、记忆配置",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 多 AI 验证:点击弹出弹窗(含开关 + provider 列表)
        val allProviders = remember(refreshTick) { MyConfig.loadProviders(context) }
        val validProviders = remember(allProviders) {
            allProviders.filter { it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty() && it.modelId.isNotEmpty() }
        }
        var showMultiAiDialog by remember { mutableStateOf(false) }
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { showMultiAiDialog = true }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("多 AI 验证", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "会得到更好的结果但会增加 token 消耗,需要两个及以上 API",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        if (showMultiAiDialog) {
            MultiAiVerifyDialog(
                providers = validProviders,
                enabled = multiAiVerify,
                selectedIndices = prefs.getMultiAiVerifyIndices(),
                onEnabledChange = onMultiAiVerifyChanged,
                onSelectedChange = { indices ->
                    prefs.setMultiAiVerifyIndices(indices)
                },
                onDismiss = { showMultiAiDialog = false }
            )
        }
        Spacer(Modifier.height(24.dp))

        // ═══ 系统功能设置 ═══
        SectionLabel("系统功能设置")
        Spacer(Modifier.height(12.dp))

        // Shizuku 状态卡片
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Shizuku 状态",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (shizukuGranted) "已授权" else "未授权",
                        color = if (shizukuGranted) Color(0xFF34C759) else Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "藏匿模式依赖 Shizuku,授权后可检测屏幕 FLAG_SECURE 窗口,实现防截屏保护。",
                    color = TextHint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LiquidGlassButton(
            text = if (shizukuGranted) "启动藏匿模式" else "授权并启动藏匿模式",
            onClick = onShizukuClick,
            style = ButtonStyle.Outline,
            height = 44.dp
        )
        Spacer(Modifier.height(12.dp))
        LiquidGlassButton(
            text = "电池优化设置",
            onClick = onBatteryOptClick,
            style = ButtonStyle.Outline,
            height = 44.dp
        )
        Spacer(Modifier.height(12.dp))

        // ═══ 搜题功能设置 ═══
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onSearchSettingsClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("搜题功能设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "防截屏保护、悬浮球、悬浮菜单项",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ═══ 关于 ═══
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onAboutClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("关于", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "版本 ${go.examate.hyp.BuildConfig.VERSION_NAME} · 检查更新 · 开源技术",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 顶部用户信息栏(logo 栏):头像 + 已勾选的资料字段 + 箭头
 *
 * [profileKey] 拼接了昵称/学校/学号/展示字段,数据变化时整体重组以刷新显示。
 */
@Composable
private fun UserProfileBar(profileKey: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)

    // 根据 profileKey 缓存:本地头像 + 首字母 + 待展示字段列表
    val avatarBitmap = remember(profileKey) {
        go.examate.hyp.util.AvatarHelper.loadAvatar(context)
    }
    val avatarChar = remember(profileKey) {
        prefs.nickname.firstOrNull()?.toString() ?: "学"
    }
    val displayEntries = remember(profileKey) {
        val fields = prefs.userDisplayFields
        // 若为空,至少展示昵称
        val effective = if (fields.isEmpty()) setOf("nickname") else fields
        // 固定顺序:昵称 → 学校
        listOf("nickname", "school")
            .filter { it in effective }
            .map { id ->
                when (id) {
                    "nickname" -> DisplayEntry(prefs.nickname.ifEmpty { Build.MODEL }, isHint = false)
                    "school" -> if (prefs.school.isEmpty()) {
                        DisplayEntry("未绑定学校", isHint = true)
                    } else {
                        DisplayEntry(prefs.school, isHint = false)
                    }
                    else -> DisplayEntry("", isHint = false)
                }
            }
    }

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像:优先显示本地头像,否则 Primary 圆形背景 + 首字母
            Box(
                Modifier
                    .size(48.dp)
                    .background(Primary, CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                avatarBitmap?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } ?: run {
                    Text(
                        text = avatarChar,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            // 资料字段:首行为主标题,其余为副标题
            Column(Modifier.weight(1f)) {
                displayEntries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.text,
                        color = if (entry.isHint) TextHint else if (index == 0) TextPrimary else TextSecondary,
                        fontSize = if (index == 0) 16.sp else 13.sp,
                        fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
            // 箭头指示可点击
            Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}

/** 用户信息栏单行展示数据 */
private data class DisplayEntry(val text: String, val isHint: Boolean)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) TextPrimary else TextHint,
                fontSize = 14.sp
            )
            Text(
                subtitle,
                color = TextHint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        LiquidGlassToggle(
            selected = checked,
            onSelect = if (enabled) onCheckedChange else ({})
        )
    }
}

/**
 * 多 AI 验证弹窗:上方开关,下方可滚动 provider 列表,点击勾选。
 */
@Composable
private fun MultiAiVerifyDialog(
    providers: List<ModelProvider>,
    enabled: Boolean,
    selectedIndices: Set<Int>,
    onEnabledChange: (Boolean) -> Unit,
    onSelectedChange: (Set<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    // 本地状态:选中的索引(空集合表示"全选")
    var selected by remember { mutableStateOf(selectedIndices) }
    var switchOn by remember { mutableStateOf(enabled) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(vertical = 8.dp)
        ) {
            // 标题
            Text(
                "多 AI 验证",
                color = TextHint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )

            // 上方开关
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (switchOn) "已开启" else "已关闭",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "同时请求多个 AI,选择题投票,问答题取首个",
                        color = TextHint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                LiquidGlassToggle(
                    selected = switchOn,
                    onSelect = {
                        switchOn = it
                        onEnabledChange(it)
                    }
                )
            }

            // 分隔线
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(TextHint.copy(alpha = 0.2f))
            )

            // 列表标题
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "选择参与的 AI",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                val activeCount = if (selected.isEmpty()) providers.size else selected.size
                Text(
                    "$activeCount / ${providers.size}",
                    color = TextHint,
                    fontSize = 12.sp
                )
            }

            // 可滚动 provider 列表
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                itemsIndexed(providers) { index, provider ->
                    val isSelected = selected.isEmpty() || selected.contains(index)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 空集合=全选,点击单个时从空集合转为显式选择
                                val current = if (selected.isEmpty()) {
                                    providers.indices.toMutableSet()
                                } else {
                                    selected.toMutableSet()
                                }
                                if (current.contains(index)) current.remove(index)
                                else current.add(index)
                                // 如果变成全选,清空为空集合(表示全选)
                                selected = if (current.size == providers.size) emptySet() else current
                                onSelectedChange(selected)
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 勾选圆点
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Primary else Color(0xFFE5E7EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                provider.displayName.ifEmpty { "未命名" },
                                color = if (isSelected) TextPrimary else TextHint,
                                fontSize = 14.sp
                            )
                            Text(
                                "${provider.modelId.ifEmpty { "未知模型" }} · ${
                                    when (provider.endpointType) {
                                        "anthropic" -> "Anthropic"
                                        "responses" -> "Responses"
                                        else -> "OpenAI"
                                    }
                                }",
                                color = TextHint,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }

            // 底部按钮
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(TextHint.copy(alpha = 0.2f))
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidGlassButton(
                    text = "关闭", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确定", onClick = {
                        onSelectedChange(selected)
                        onDismiss()
                    },
                    style = ButtonStyle.White, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
