package go.examate.hyp.campus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.widget.ScheduleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ════════════════════════════════════════
//  待办管理页(参考 Cresto 设计,适配本项目液态玻璃风格)
//  与课程表共用 ScheduleStorage 同一数据源,天然双向同步
// ════════════════════════════════════════

/** 优先级配色 */
private val PriorityColors = listOf(
    Color(0xFF9CA3AF), // 0 无
    Color(0xFF60A5FA), // 1 低 蓝
    Color(0xFFFBBF24), // 2 中 黄
    Color(0xFFEF4444)  // 3 高 红
)
private val PriorityLabels = listOf("", "低", "中", "高")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val storageKey = remember { ScheduleStorage.getCurrentScheduleMeta(context).id }

    // 全部待办(从存储加载)
    var allTodos by remember { mutableStateOf(ScheduleStorage.loadTodos(context, storageKey)) }
    // 选中的日期(默认今天)
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    // 添加面板
    var showAddSheet by remember { mutableStateOf(false) }

    // 重新加载(从课程表页返回或增删后调用)
    fun reload() {
        allTodos = ScheduleStorage.loadTodos(context, storageKey)
        ScheduleWidgetProvider.refreshAll(context)
    }

    fun addTodo(todo: ScheduleTodo) {
        ScheduleStorage.addTodo(context, todo)
        reload()
    }

    fun toggleTodo(id: String) {
        val updated = allTodos.map { if (it.id == id) it.copy(done = !it.done) else it }
        val entries = ScheduleStorage.loadEntries(context, storageKey)
        ScheduleStorage.save(context, storageKey, entries, updated)
        allTodos = updated
        ScheduleWidgetProvider.refreshAll(context)
    }

    fun deleteTodo(id: String) {
        val updated = allTodos.filterNot { it.id == id }
        val entries = ScheduleStorage.loadEntries(context, storageKey)
        ScheduleStorage.save(context, storageKey, entries, updated)
        allTodos = updated
        ScheduleWidgetProvider.refreshAll(context)
    }

    fun updateTodo(updated: ScheduleTodo) {
        val list = allTodos.map { if (it.id == updated.id) updated else it }
        val entries = ScheduleStorage.loadEntries(context, storageKey)
        ScheduleStorage.save(context, storageKey, entries, list)
        allTodos = list
        ScheduleWidgetProvider.refreshAll(context)
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏 ═══
            TodoTopBar(
                todoCount = allTodos.size,
                doneCount = allTodos.count { it.done },
                onBack = onBack,
                onAdd = { showAddSheet = true }
            )

            // ═══ 日期选择条(横向滚动,前后7天) ═══
            DateStrip(
                selectedDate = selectedDate,
                todos = allTodos,
                onSelect = { selectedDate = it }
            )

            // ═══ 当日待办列表 ═══
            val dayTodos = remember(allTodos, selectedDate) {
                allTodos.filter { it.date == selectedDate.toString() }
                    .sortedWith(
                        compareBy<ScheduleTodo> { it.done }
                            .thenByDescending { it.priority }
                            .thenBy { it.time.ifBlank { "99:99" } }
                    )
            }

            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dayTodos.isEmpty()) {
                    item { EmptyTodoState { showAddSheet = true } }
                } else {
                    items(dayTodos, key = { it.id }) { todo ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            TodoCard(
                                todo = todo,
                                onToggle = { toggleTodo(todo.id) },
                                onDelete = { deleteTodo(todo.id) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        // ═══ 添加待办底部面板 ═══
        if (showAddSheet) {
            AddTodoSheet(
                defaultDate = selectedDate,
                onDismiss = { showAddSheet = false },
                onAdd = { todo ->
                    addTodo(todo)
                    showAddSheet = false
                    selectedDate = LocalDate.parse(todo.date)
                },
                onAddBatch = { todos ->
                    todos.forEach { addTodo(it) }
                    showAddSheet = false
                    if (todos.isNotEmpty()) selectedDate = LocalDate.parse(todos.first().date)
                }
            )
        }
    }
}

// ════════════════════════════════════════
//  顶栏
// ════════════════════════════════════════

@Composable
private fun TodoTopBar(
    todoCount: Int,
    doneCount: Int,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidGlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("待办", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val progress = if (todoCount == 0) 0f else doneCount.toFloat() / todoCount
            val progressText = if (todoCount == 0) "暂无待办" else "$doneCount/$todoCount 已完成"
            Text(progressText, color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            // 进度条
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .clip(CircleShape)
                    .background(TextHint.copy(0.15f))
            ) {
                val animProgress by animateFloatAsState(progress, tween(400), label = "progress")
                Box(
                    Modifier.fillMaxWidth(animProgress).height(3.dp)
                        .clip(CircleShape)
                        .background(
                            if (progress == 1f && todoCount > 0) Success
                            else AccentBlue
                        )
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        LiquidGlassIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "添加待办",
            onClick = onAdd,
            modifier = Modifier.size(40.dp)
        )
    }
}

// ════════════════════════════════════════
//  日期选择条(前后 7 / 14 天 + 月份分割 + 自定义跳转,显示待办数量标记)
// ════════════════════════════════════════

@Composable
private fun DateStrip(
    selectedDate: LocalDate,
    todos: List<ScheduleTodo>,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val dates = remember(today) {
        (-7..21).map { today.plusDays(it.toLong()) }
    }
    val todosByDate = remember(todos) { todos.groupBy { it.date } }
    val scrollState = rememberScrollState()
    var showDatePicker by remember { mutableStateOf(false) }

    // 进入页面时,把"今天"滚到靠左的位置(便于看到未来日历)
    val density = LocalDensity.current
    LaunchedEffect(scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            // 每个 chip ≈ 52dp(44 宽 + 8 间距),7 个 chip ≈ 364dp
            val px = with(density) { 364.dp.toPx().toInt() }
            scrollState.scrollTo(px.coerceAtMost(scrollState.maxValue))
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dates.forEach { date ->
            val isSelected = date == selectedDate
            val isToday = date == today
            val isPast = date.isBefore(today)
            val dayTodoCount = todosByDate[date.toString()]?.count { !it.done } ?: 0
            // 月初插入月份标签
            val showMonthLabel = date.dayOfMonth == 1

            if (showMonthLabel) {
                Box(
                    Modifier
                        .width(36.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${date.monthValue}月",
                        color = TextHint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            FrostedGlassCard(
                modifier = Modifier
                    .then(
                        if (isSelected) Modifier.border(1.5.dp, AccentBlue, RoundedCornerShape(16.dp))
                        else Modifier
                    )
            ) {
                Column(
                    Modifier
                        .width(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(date) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.CHINESE),
                        color = when {
                            isSelected -> AccentBlue
                            isPast -> TextHint.copy(0.5f)
                            else -> TextHint
                        },
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        date.dayOfMonth.toString(),
                        color = when {
                            isSelected -> AccentBlue
                            isPast -> TextHint.copy(0.7f)
                            else -> TextPrimary
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    // 未完成待办数量标记
                    Box(
                        Modifier.size(16.dp).clip(CircleShape)
                            .background(
                                if (dayTodoCount > 0) AccentBlue.copy(0.15f) else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayTodoCount > 0) {
                            Text(
                                dayTodoCount.toString(),
                                color = AccentBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Box(Modifier.size(3.dp).clip(CircleShape).background(TextHint.copy(0.2f)))
                        }
                    }
                }
            }
        }

        // 末尾的"自定义日期"入口
        FrostedGlassCard(
            modifier = Modifier.clickable { showDatePicker = true }
        ) {
            Column(
                Modifier
                    .width(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = "选择日期",
                    tint = TextHint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("更多", color = TextHint, fontSize = 9.sp)
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                onSelect(it)
                showDatePicker = false
            }
        )
    }
}

// ════════════════════════════════════════
//  空状态
// ════════════════════════════════════════

@Composable
private fun EmptyTodoState(onAdd: () -> Unit) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth().clickable { onAdd() }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("这一天暂无待办", color = TextHint, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text("+ 添加待办", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ════════════════════════════════════════
//  待办卡片(复用 TaskCheckbox 动效)
// ════════════════════════════════════════

@Composable
private fun TodoCard(
    todo: ScheduleTodo,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val titleColor by animateColorAsState(
        if (todo.done) TextHint else TextPrimary, tween(300), label = "titleColor"
    )
    val cardAlpha by animateFloatAsState(
        if (todo.done) 0.6f else 1f, tween(300), label = "cardAlpha"
    )
    val priorityColor = PriorityColors[todo.priority.coerceIn(0, 3)]

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().alpha(cardAlpha)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 优先级色条 + 标签
            if (todo.priority > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(3.dp).height(20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(priorityColor)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        PriorityLabels[todo.priority],
                        color = priorityColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            TaskCheckbox(checked = todo.done, onToggle = onToggle)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    todo.title,
                    color = titleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (todo.time.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(todo.time, color = TextHint, fontSize = 10.sp)
                }
            }
            // 删除按钮
            Icon(
                Icons.Filled.Close,
                contentDescription = "删除",
                tint = TextHint,
                modifier = Modifier.size(16.dp).clickable { onDelete() }.padding(4.dp)
            )
        }
    }
}

// ════════════════════════════════════════
//  完成勾选框(复刻 Vue 动效:边框灰→绿、绿色渐变填充弹入、对勾旋转弹出、涟漪)
// ════════════════════════════════════════

@Composable
private fun TaskCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val emerald = Color(0xFF10B981)
    val emeraldDark = Color(0xFF059669)
    val borderGray = Color(0xFFD1D5DB)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    val fillScale by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "fill"
    )
    val checkScale by animateFloatAsState(
        if (checked) 1f else 0.3f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "checkScale"
    )
    val checkRot by animateFloatAsState(if (checked) 0f else 20f, spring(dampingRatio = 0.5f), label = "checkRot")
    val checkAlpha by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "checkAlpha")
    val borderColor by animateColorAsState(if (checked) emerald else borderGray, tween(300), label = "border")

    val ripple = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(checked) {
        if (checked) { ripple.snapTo(0f); ripple.animateTo(1f, tween(600)) } else ripple.snapTo(0f)
    }

    Box(
        Modifier.size(38.dp).clickable(interaction, indication = null) { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        val rv = ripple.value
        if (rv > 0f && rv < 1f) {
            Canvas(Modifier.size(38.dp)) {
                drawCircle(color = emerald.copy(alpha = 0.4f * (1f - rv)), radius = size.minDimension / 2f * rv)
            }
        }
        Box(
            Modifier.size(22.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clip(RoundedCornerShape(6.dp))
                .background(CardBg)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(emerald, emeraldDark)))
            )
            Icon(
                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(15.dp).graphicsLayer {
                    scaleX = checkScale; scaleY = checkScale; rotationZ = checkRot; alpha = checkAlpha
                }
            )
        }
    }
}

// ════════════════════════════════════════
//  添加待办底部面板
// ════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTodoSheet(
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onAdd: (ScheduleTodo) -> Unit,
    onAddBatch: (List<ScheduleTodo>) -> Unit = {}
) {
    // 模式:手动添加 / AI 提取
    var aiMode by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DialogBg
    ) {
        if (aiMode) {
            AiExtractPanel(
                onDismiss = { aiMode = false },
                onConfirm = { items ->
                    onAddBatch(items)
                    onDismiss()
                }
            )
        } else {
            ManualAddPanel(
                defaultDate = defaultDate,
                onSwitchAi = { aiMode = true },
                onAdd = onAdd,
                onDismiss = onDismiss
            )
        }
    }
}

/** 手动添加面板 */
@Composable
private fun ManualAddPanel(
    defaultDate: LocalDate,
    onSwitchAi: () -> Unit,
    onAdd: (ScheduleTodo) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(defaultDate) }
    var time by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("添加待办", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // AI 提取入口(与项目 chip 风格统一:边框 + 半透明底)
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, AccentBlue, RoundedCornerShape(10.dp))
                    .background(AccentBlue.copy(0.10f))
                    .clickable { onSwitchAi() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                Text("AI 提取", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(16.dp))

        // 标题输入
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("待办内容") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = TextHint.copy(0.3f)
            )
        )
        Spacer(Modifier.height(12.dp))

        // 时间输入(可选)
        OutlinedTextField(
            value = time,
            onValueChange = { v ->
                val filtered = v.filter { it.isDigit() || it == ':' }.take(5)
                time = filtered
            },
            label = { Text("时间(可不填,如 08:30)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = TextHint.copy(0.3f)
            )
        )
        Spacer(Modifier.height(12.dp))

        // 日期选择(快捷按钮 + 自定义日历)
        Text("日期", color = TextHint, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val today = LocalDate.now()
            val quickDates = listOf(
                "昨天" to today.minusDays(1),
                "今天" to today,
                "明天" to today.plusDays(1),
                "后天" to today.plusDays(2)
            )
            quickDates.forEach { (label, d) ->
                DateChip(label = label, selected = date == d) { date = d }
            }
            // 自定义日期 chip:显示当前选中日期(非快捷),或"选择日期"
            val isQuick = quickDates.any { it.second == date }
            val customLabel = if (!isQuick) "${date.monthValue}/${date.dayOfMonth}" else "选择日期"
            DateChip(label = customLabel, selected = !isQuick) { showDatePicker = true }
        }
        Spacer(Modifier.height(16.dp))

        // 优先级选择
        Text("优先级", color = TextHint, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriorityLabels.forEachIndexed { idx, label ->
                if (idx == 0) return@forEachIndexed
                PriorityChip(
                    label = label,
                    color = PriorityColors[idx],
                    selected = priority == idx
                ) { priority = if (priority == idx) 0 else idx }
            }
            PriorityChip(
                label = "无",
                color = TextHint,
                selected = priority == 0
            ) { priority = 0 }
        }
        Spacer(Modifier.height(20.dp))

        LiquidGlassButton(
            text = "添加",
            onClick = {
                if (title.isBlank()) return@LiquidGlassButton
                onAdd(
                    ScheduleTodo(
                        id = "usr_${System.currentTimeMillis()}",
                        title = title.trim(),
                        date = date.toString(),
                        time = time.trim(),
                        done = false,
                        priority = priority
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            initialDate = date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            }
        )
    }
}

/**
 * AI 提取面板(借鉴 Cresto AiInputBox + AiTodoReviewContainer):
 * 文字输入或选图 → AI 提取 → 预览确认 → 批量入库
 *
 * 供 TodoScreen 与课表日视图的添加待办弹窗共用。
 */
@Composable
internal fun AiExtractPanel(
    onDismiss: () -> Unit,
    onConfirm: (List<ScheduleTodo>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var extracted by remember { mutableStateOf<List<TodoAiExtractor.ExtractedTodo>>(emptyList()) }

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true; errorMsg = null
            val images = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            }
            val result = TodoAiExtractor.extractFromImage(context, images, inputText)
            isLoading = false
            if (result.isEmpty()) errorMsg = "未提取到待办,换个图片或补充文字试试"
            else extracted = result
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextHint,
                modifier = Modifier.size(20.dp).clickable { onDismiss() }.padding(2.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("AI 提取待办", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text("输入文字或选择图片,AI 自动识别待办事项", color = TextHint, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))

        // 文字输入
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("粘贴或输入待办相关文字") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = TextHint.copy(0.3f)
            )
        )
        Spacer(Modifier.height(10.dp))

        // 操作按钮:从文字提取 / 选图提取
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidGlassButton(
                text = "从文字提取",
                onClick = {
                    if (inputText.isBlank()) return@LiquidGlassButton
                    scope.launch {
                        isLoading = true; errorMsg = null; extracted = emptyList()
                        val result = TodoAiExtractor.extractFromText(context, inputText)
                        isLoading = false
                        if (result.isEmpty()) errorMsg = "未提取到待办,换个描述试试"
                        else extracted = result
                    }
                },
                modifier = Modifier.weight(1f)
            )
            LiquidGlassButton(
                text = "从图片提取",
                onClick = { imagePicker.launch("image/*") },
                modifier = Modifier.weight(1f)
            )
        }

        // 加载中提示
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentBlue)
                Spacer(Modifier.width(8.dp))
                Text("正在提取…", color = TextHint, fontSize = 12.sp)
            }
        }

        // 错误提示
        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = Danger, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        }

        // 提取结果预览(借鉴 Cresto AiTodoReviewContainer)
        if (extracted.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("识别到 ${extracted.size} 条待办,确认后添加", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            // 预览列表(可勾选保留/删除)
            val reviewList = remember(extracted) {
                mutableStateListOf<ExtractedReviewItem>().apply {
                    addAll(extracted.map { ExtractedReviewItem(it, true) })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reviewList.forEachIndexed { idx, item ->
                    ExtractedTodoRow(
                        item = item,
                        onToggleKeep = { reviewList[idx] = item.copy(keep = !item.keep) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LiquidGlassButton(
                text = "添加 ${reviewList.count { it.keep }} 条待办",
                onClick = {
                    val todos = reviewList.filter { it.keep }.map { it.todo }
                    if (todos.isNotEmpty()) {
                        onConfirm(
                            todos.mapIndexed { i, e ->
                                ScheduleTodo(
                                    id = "ai_${System.currentTimeMillis()}_$i",
                                    title = e.title,
                                    date = e.date,
                                    time = e.time,
                                    done = false,
                                    priority = e.priority
                                )
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class ExtractedReviewItem(
    val todo: TodoAiExtractor.ExtractedTodo,
    val keep: Boolean
)

@Composable
private fun ExtractedTodoRow(item: ExtractedReviewItem, onToggleKeep: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().alpha(if (item.keep) 1f else 0.45f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 保留勾选
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (item.keep) AccentBlue else Color.Transparent)
                    .border(1.5.dp, if (item.keep) AccentBlue else TextHint.copy(0.3f), RoundedCornerShape(6.dp))
                    .clickable { onToggleKeep() },
                contentAlignment = Alignment.Center
            ) {
                if (item.keep) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.todo.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val dateStr = runCatching { LocalDate.parse(item.todo.date) }.getOrNull()
                val info = buildString {
                    if (dateStr != null) append("${dateStr.monthValue}月${dateStr.dayOfMonth}日")
                    if (item.todo.time.isNotEmpty()) append(" ${item.todo.time}")
                    if (item.todo.priority > 0) append(" · ${PriorityLabels[item.todo.priority]}优先级")
                }
                if (info.isNotEmpty()) Text(info, color = TextHint, fontSize = 10.sp)
            }
            if (item.todo.priority > 0) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PriorityColors[item.todo.priority]))
            }
        }
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentBlue.copy(0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) AccentBlue else TextHint.copy(0.2f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) AccentBlue else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PriorityChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) color.copy(0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) color else TextHint.copy(0.2f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            label,
            color = if (selected) color else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
