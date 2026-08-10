package go.examate.hyp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

@Composable
fun ApiConfigScreen(
    initialProviders: List<ModelProvider>,
    onBack: () -> Unit,
    onSave: (List<ModelProvider>) -> Unit,
    onOpenTutorial: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onFetchModels: (String, String, (List<String>) -> Unit, (String) -> Unit) -> Unit,
    onToast: (String) -> Unit
) {
    val providers = remember { mutableStateListOf(*initialProviders.toTypedArray()) }
    var editingIndex by remember { mutableIntStateOf(-1) }       // 单击:编辑基本信息
    var contentEditingIndex by remember { mutableIntStateOf(-1) } // 长按→编辑按钮:编辑内容信息
    var actionModeIndex by remember { mutableIntStateOf(-1) }     // 长按:显示编辑/排序操作栏

    // ═══ 拖拽排序状态 ═══
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 80.dp.toPx() } // 估算行高
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // ═══ 修改检测 + 返回保存确认 ═══
    // 对比当前列表与初始列表,判断是否有修改
    fun hasChanges(): Boolean {
        if (providers.size != initialProviders.size) return true
        return providers.zip(initialProviders).any { (a, b) ->
            a.displayName != b.displayName || a.modelId != b.modelId ||
                a.requestUrl != b.requestUrl || a.apiKey != b.apiKey ||
                a.endpointType != b.endpointType ||
                a.label != b.label || a.barColor != b.barColor || a.visible != b.visible ||
                a.capabilities != b.capabilities
        }
    }
    var showSaveConfirm by remember { mutableStateOf(false) }

    fun doSave() {
        val valid = providers.filter { it.displayName.isNotEmpty() || it.modelId.isNotEmpty() }
        onSave(valid)
    }
    fun handleBack() {
        if (hasChanges()) showSaveConfirm = true else onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶部:标题栏(教程+编辑共处胶囊) ═══
            ApiConfigTopBar(
                modifier = Modifier,
                onBack = { handleBack() },
                onTutorial = { onOpenTutorial() }
            )

            // ═══ 列表(单击=基本信息,长按=显示操作栏,长按拖动=排序) ═══
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                providers.forEachIndexed { index, provider ->
                    item(key = "provider_$index") {
                        ProviderRow(
                            provider = provider,
                            isDragging = index == draggingIndex,
                            dragOffsetY = if (index == draggingIndex) dragOffsetY else 0f,
                            showActions = actionModeIndex == index,
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetY = 0f
                            },
                            onDrag = { dragAmount ->
                                dragOffsetY += dragAmount
                                var target = draggingIndex
                                while (dragOffsetY > itemHeightPx / 2 && target < providers.size - 1) {
                                    val item = providers.removeAt(target)
                                    providers.add(target + 1, item)
                                    target++
                                    dragOffsetY -= itemHeightPx
                                }
                                while (dragOffsetY < -itemHeightPx / 2 && target > 0) {
                                    val item = providers.removeAt(target)
                                    providers.add(target - 1, item)
                                    target--
                                    dragOffsetY += itemHeightPx
                                }
                                draggingIndex = target
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetY = 0f
                            },
                            onClick = {
                                if (actionModeIndex == index) {
                                    actionModeIndex = -1
                                } else {
                                    actionModeIndex = -1
                                    editingIndex = index
                                }
                            },
                            onLongClick = { actionModeIndex = index },
                            onEditContent = { contentEditingIndex = index; actionModeIndex = -1 },
                            onDismissActions = { actionModeIndex = -1 }
                        )
                    }
                }
            }
        }

        // ═══ 底部胶囊底栏:新增 + 保存(液态玻璃) ═══
        ApiConfigBottomBar(
            onAddCustom = {
                providers.add(ModelProvider("自定义厂商", "", "", "", false, "", "自定义"))
                editingIndex = providers.size - 1
            },
            onSave = { doSave() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        )
    }

    // ═══ 返回时保存确认弹窗 ═══
    if (showSaveConfirm) {
        PlainDialog(onDismissRequest = { showSaveConfirm = false }) {
            PlainDialogTitle("保存修改")
            PlainDialogContent("API 配置已修改,是否保存?")
            PlainDialogButtons(
                buttons = listOf(
                    Triple("不保存", false) { showSaveConfirm = false; onBack() },
                    Triple("保存", true) { showSaveConfirm = false; doSave() }
                )
            )
        }
    }

    // ═══ 单击:编辑基本信息 ═══
    if (editingIndex in providers.indices) {
        EditProviderDialog(
            provider = providers[editingIndex],
            onDismiss = { editingIndex = -1 },
            onConfirm = { updated ->
                providers[editingIndex] = updated
                editingIndex = -1
            },
            onDelete = { onDeleteIndex ->
                providers.removeAt(onDeleteIndex)
                editingIndex = -1
            },
            editingIndex = editingIndex,
            onFetchModels = onFetchModels,
            onOpenUrl = onOpenUrl,
            onToast = onToast
        )
    }

    // ═══ 长按:编辑内容信息(标签/颜色/显隐性) ═══
    if (contentEditingIndex in providers.indices) {
        ContentEditDialog(
            provider = providers[contentEditingIndex],
            onDismiss = { contentEditingIndex = -1 },
            onApply = { updated ->
                providers[contentEditingIndex] = updated
                contentEditingIndex = -1
            }
        )
    }
}

// ═══════════════════════════════════════════
//  单个 AI 内容编辑弹窗:标签 / 颜色 / 显隐性
// ═══════════════════════════════════════════

@Composable
private fun ContentEditDialog(
    provider: ModelProvider,
    onDismiss: () -> Unit,
    onApply: (ModelProvider) -> Unit
) {
    var label by remember { mutableStateOf(provider.label) }
    var selectedColor by remember { mutableStateOf(provider.barColor) }
    var visible by remember { mutableStateOf(provider.visible) }

    val presetColors = listOf(
        "" to "默认",
        "#5B8DEF" to "蓝",
        "#1D9E75" to "绿",
        "#E24B4A" to "红",
        "#F59E0B" to "橙",
        "#8B5CF6" to "紫",
        "#6B7280" to "灰"
    )

    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("编辑 - ${provider.displayName.ifEmpty { "未命名" }}")
        Column(Modifier.padding(horizontal = 24.dp)) {
            // 标签
            Text("自定义标签", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = label, onValueChange = { label = it },
                placeholder = { Text("如:主力/备用/免费", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))

            // 竖条颜色
            Text("竖条颜色", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetColors.forEach { (hex, name) ->
                    val color = if (hex.isNotEmpty()) {
                        try { Color(android.graphics.Color.parseColor(hex)) }
                        catch (_: Exception) { TextHint }
                    } else TextHint
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == hex) {
                                Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(name, color = TextHint, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 显隐性
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示在列表中", color = TextPrimary, fontSize = 13.sp)
                    Text("关闭后不出现在 agents 回复和悬浮窗解题列表", color = TextHint, fontSize = 10.sp)
                }
                LiquidGlassToggle(selected = visible, onSelect = { visible = it })
            }
        }
        Spacer(Modifier.height(12.dp))
        PlainDialogButtons(
            listOf(
                Triple("取消", false) { onDismiss() },
                Triple("保存", true) {
                    onApply(provider.copy(label = label, barColor = selectedColor, visible = visible))
                }
            )
        )
    }
}

// ═══════════════════════════════════════════
//  顶栏:返回 + 标题 + 教程胶囊(液态玻璃)
// ═══════════════════════════════════════════

@Composable
private fun ApiConfigTopBar(
    onBack: () -> Unit,
    onTutorial: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
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
            "API 设置",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center)
        )
        // 教程按钮:液态玻璃胶囊
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .clip(Capsule())
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(8f.dp.toPx()) },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(Color.White.copy(0.5f)) }
                        )
                    } else {
                        Modifier.background(Color.White.copy(0.7f))
                    }
                )
                .clickable { onTutorial() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("教程", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ModelProvider,
    isDragging: Boolean,
    dragOffsetY: Float,
    showActions: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditContent: () -> Unit,
    onDismissActions: () -> Unit
) {
    val tag = provider.tag ?: ""
    val barColor = run {
        val custom = provider.barColor
        if (custom.isNotEmpty()) {
            try { Color(android.graphics.Color.parseColor(custom)) }
            catch (_: Exception) {
                when (tag) { "稳定" -> Primary; "免费" -> TextSecondary; else -> TextHint }
            }
        } else {
            when (tag) { "稳定" -> Primary; "免费" -> TextSecondary; else -> TextHint }
        }
    }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isDragging) translationY = dragOffsetY
                alpha = if (showActions) 0.92f else 1f
            }
            .pointerInput(Unit) {
                var hasMoved = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { hasMoved = false },
                    onDrag = { change, dragAmount ->
                        if (!hasMoved) {
                            hasMoved = true
                            onDragStart()
                        }
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                    onDragEnd = {
                        if (hasMoved) onDragEnd() else onLongClick()
                    },
                    onDragCancel = {
                        if (hasMoved) onDragCancel()
                    }
                )
            },
        cornerRadius = 16.dp
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (showActions) onDismissActions() else onClick() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.displayName,
                            color = if (provider.visible) TextPrimary else TextHint,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (provider.label.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(barColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(provider.label, color = barColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (!provider.visible) {
                            Spacer(Modifier.width(6.dp))
                            Text("隐藏", color = TextHint, fontSize = 10.sp)
                        }
                    }
                    Text(
                        provider.modelId,
                        color = TextHint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!showActions) {
                    Text(
                        "⠿",
                        color = TextHint.copy(alpha = 0.5f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text("›", color = TextHint, fontSize = 20.sp)
                }
            }

            // ═══ 长按操作栏:编辑 / 排序 ═══
            if (showActions) {
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 编辑按钮:内容信息(标签/颜色/显隐性)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primary)
                            .clickable { onEditContent() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("编辑", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    // 排序按钮:提示长按拖动排序
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TextSecondary)
                            .clickable { onDismissActions() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("排序", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * API 配置页底部胶囊底栏:新增 + 保存(真液态玻璃)
 */
@Composable
private fun ApiConfigBottomBar(
    onAddCustom: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = LocalGlassBackdrop.current
    val containerColor = Color(0xFFFAFAFA).copy(0.2f)

    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                } else {
                    Modifier.background(Color.White.copy(0.92f), Capsule())
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // 新增按钮
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onAddCustom() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "＋ 新增",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        // 分隔线
        Box(
            Modifier
                .width(1.dp)
                .height(28.dp)
                .background(Color(0xFFD1D5DB).copy(alpha = 0.5f))
        )
        // 保存按钮
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onSave() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "保存",
                color = Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun EditProviderDialog(
    provider: ModelProvider,
    editingIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (ModelProvider) -> Unit,
    onDelete: (Int) -> Unit,
    onFetchModels: (String, String, (List<String>) -> Unit, (String) -> Unit) -> Unit,
    onOpenUrl: (String) -> Unit,
    onToast: (String) -> Unit
) {
    var name by remember { mutableStateOf(provider.displayName) }
    var modelId by remember { mutableStateOf(provider.modelId) }
    // 完整 API 地址(含 /v1/chat/completions)
    var apiUrl by remember { mutableStateOf(provider.requestUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    // 接口格式:OpenAI(chat) / OpenAI Responses / Anthropic
    var endpointType by remember { mutableStateOf(provider.endpointType.ifEmpty { ModelProvider.inferEndpointType(apiUrl) }) }
    var caps by remember { mutableStateOf(provider.capabilities) }
    var fetching by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 接口格式选项:OpenAI(chat/responses 兼容)/ Anthropic
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("API 配置")
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(24.dp, 0.dp, 24.dp, 12.dp)) {
            FieldLabel("厂商名称")
            OutlinedTextField(
                value = name,
                onValueChange = { name = truncateToWidth(it, 14) },
                placeholder = { Text("如 DeepSeek") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ═══ API 地址 ═══
            FieldLabel("API 地址")
            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                placeholder = { Text("https://api.example.com/v1/chat/completions") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ═══ API Key ═══
            FieldLabel("API Key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("sk-...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // 获取 API Key 快捷跳转:provider 已有 registerUrl 时显示
            val regUrl = provider.registerUrl
            if (!regUrl.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "获取 API Key →",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onOpenUrl(regUrl) }
                )
            }

            if (!provider.inviteCode.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    provider.inviteCode,
                    color = TextHint,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(12.dp))

            // ═══ 模型 ID(通过选择模型填入) ═══
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模型 ID",
                    Modifier.weight(1f),
                    color = TextHint,
                    fontSize = 11.sp
                )
                Text(
                    if (fetching) "选择中..." else "选择模型",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        val u = apiUrl.trim()
                        val k = apiKey.trim()
                        when {
                            u.isEmpty() -> onToast("请先填写 API 地址")
                            k.isEmpty() -> onToast("请先填写 API Key")
                            else -> {
                                fetching = true
                                onFetchModels(
                                    u, k,
                                    { fetching = false; modelList = it },
                                    { fetching = false; onToast(it) }
                                )
                            }
                        }
                    }
                )
            }
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                placeholder = { Text("如 deepseek-chat") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ═══ 模型能力(多模态按需路由) ═══
            FieldLabel("模型能力")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ModelProvider.CAP_CHAT to "聊天",
                    ModelProvider.CAP_VISION to "多模态",
                    ModelProvider.CAP_IMAGE_GEN to "生图"
                ).forEach { (cap, capLabel) ->
                    val on = caps.contains(cap)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) Primary else SurfaceVariant)
                            .clickable { caps = if (on) caps - cap else caps + cap }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(capLabel, color = if (on) Color.White else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("多模态=能理解图片,生图=文生图;发图/生图时自动路由到对应能力的模型", color = TextHint, fontSize = 10.sp)
        }

        // 按钮区:[删除] [间距] [取消] [保存]
        PlainDialogButtons(
            buttons = listOf(
                Triple("删除", false) { showDeleteConfirm = true },
                Triple("取消", false) { onDismiss() },
                Triple("保存", true) {
                    // 规范化 URL:用户可能只填了 baseUrl(到 /v1)或完整地址
                    // 若不含 /chat/completions、/responses、/messages 路径,根据 endpointType 自动补全
                    val normalizedUrl = runCatching {
                        val u = apiUrl.trim()
                        val hasPath = u.contains("/chat/completions") || u.contains("/responses") || u.contains("/messages")
                        if (hasPath) u else ModelProvider.buildFullUrl(ModelProvider.extractBaseUrl(u), endpointType)
                    }.getOrDefault(apiUrl.trim())
                    onConfirm(
                        ModelProvider(
                            name.trim(),
                            modelId.trim(),
                            normalizedUrl,
                            apiKey.trim(),
                            provider.enableThinking,
                            provider.registerUrl ?: "",
                            provider.tag ?: "",
                            provider.inviteCode ?: "",
                            endpointType,
                            provider.label,
                            provider.barColor,
                            provider.visible,
                            caps
                        )
                    )
                }
            )
        )
    }

    // ═══ 删除二次确认 ═══
    if (showDeleteConfirm) {
        PlainDialog(onDismissRequest = { showDeleteConfirm = false }) {
            PlainDialogTitle("确认删除")
            PlainDialogContent("确定要删除该厂商配置吗?此操作不可撤销。")
            PlainDialogButtons(
                buttons = listOf(
                    Triple("取消", false) { showDeleteConfirm = false },
                    Triple("删除", true) {
                        showDeleteConfirm = false
                        onDelete(editingIndex)
                    }
                )
            )
        }
    }

    // ═══ 模型选择列表 ═══
    modelList?.let { models ->
        PlainDialog(onDismissRequest = { modelList = null }) {
            PlainDialogTitle("选择模型 (${models.size} 个可用)")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp, 0.dp, 24.dp, 12.dp)) {
                models.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                modelId = m
                                modelList = null
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = m == modelId,
                            onClick = {
                                modelId = m
                                modelList = null
                            }
                        )
                        Text(
                            m,
                            Modifier.padding(start = 8.dp),
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            PlainDialogButtons(
                buttons = listOf(
                    Triple("取消", false) { modelList = null }
                )
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = TextHint,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

/**
 * 计算字符串显示宽度:汉字/全角字符=2,英文/数字/半角符号=1。
 * 用于厂商名称长度限制(上限 14,即最多 7 个汉字或 14 个英文字符)。
 */
private fun displayWidth(s: String): Int {
    var w = 0
    for (c in s) {
        w += if (c.code > 0x2E80) 2 else 1  // CJK 统一表意文字及更靠后的全角字符算 2
    }
    return w
}

/** 截断字符串使其显示宽度不超过 max */
private fun truncateToWidth(s: String, max: Int): String {
    val sb = StringBuilder()
    var w = 0
    for (c in s) {
        val cw = if (c.code > 0x2E80) 2 else 1
        if (w + cw > max) break
        sb.append(c)
        w += cw
    }
    return sb.toString()
}
