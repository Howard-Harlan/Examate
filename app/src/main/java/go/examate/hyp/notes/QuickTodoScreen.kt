package go.examate.hyp.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 速记代办子页 — 与课程表代办共用 [ScheduleStorage] 同一数据源,天然双向同步。
 *
 * 展示今天及未来 7 天的代办,按日期分组;支持新增 / 勾选完成 / 删除。
 */
@Composable
fun QuickTodoScreen(onBack: () -> Unit, showTopBar: Boolean = true) {
    val context = LocalContext.current
    // 当前课表 id(与课程表代办页同一存储键,确保数据一致)
    val storageId = remember { ScheduleStorage.getCurrentScheduleMeta(context).id }
    // 今天起共 8 天的窗口
    val window = remember { (0..7).map { LocalDate.now().plusDays(it.toLong()) } }

    // 刷新计数:增删改后自增,触发重新加载
    var refreshTick by remember { mutableIntStateOf(0) }
    val todos = remember { mutableStateListOf<ScheduleTodo>() }
    LaunchedEffect(refreshTick) {
        todos.clear()
        window.forEach { d -> todos.addAll(ScheduleStorage.todosOn(context, d)) }
    }

    // 新增区输入状态
    var titleText by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val timeRegex = remember { Regex("^([01]\\d|2[0-3]):[0-5]\\d$") }
    val canAdd = titleText.isNotBlank()

    fun doAdd() {
        val t = titleText.trim()
        if (t.isBlank()) return
        val time = timeText.trim().let { if (it.matches(timeRegex)) it else "" }
        val todo = ScheduleTodo(
            id = "qt_${System.currentTimeMillis()}_${(1000..9999).random()}",
            title = t,
            date = selectedDate.toString(),
            time = time
        )
        ScheduleStorage.addTodo(context, todo)
        titleText = ""
        timeText = ""
        selectedDate = LocalDate.now()
        refreshTick++
    }

    fun doToggle(id: String) {
        val entries = ScheduleStorage.loadEntries(context, storageId)
        val newTodos = ScheduleStorage.loadTodos(context, storageId)
            .map { if (it.id == id) it.copy(done = !it.done) else it }
        ScheduleStorage.save(context, storageId, entries, newTodos)
        refreshTick++
    }

    fun doDelete(id: String) {
        val entries = ScheduleStorage.loadEntries(context, storageId)
        val newTodos = ScheduleStorage.loadTodos(context, storageId).filterNot { it.id == id }
        ScheduleStorage.save(context, storageId, entries, newTodos)
        refreshTick++
    }

    // 按日期分组的扁平列表(分组标题 + 代办条目)
    val flat = buildList {
        window.forEach { d ->
            val list = todos.filter { it.date == d.toString() }
            if (list.isNotEmpty()) {
                add(TodoListEntry.Header(d, dateLabel(d)))
                list.forEach { add(TodoListEntry.Item(it)) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = if (showTopBar) 72.dp else 0.dp)) {
            // ═══ 新增代办区 ═══
            FrostedGlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        placeholder = { Text("代办内容...", color = TextHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Spacer(Modifier.height(10.dp))
                    // 日期选择:今天..未来7天 横向芯片
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        window.forEach { d ->
                            val selected = d == selectedDate
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AccentBlue else Color.White.copy(0.6f))
                                    .clickable { selectedDate = d }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    dateLabel(d),
                                    color = if (selected) Color.White else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = timeText,
                            onValueChange = { timeText = it },
                            placeholder = { Text("时间 HH:mm(可选)", color = TextHint) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                        )
                        Spacer(Modifier.width(10.dp))
                        // 添加按钮
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (canAdd) AccentBlue else AccentBlue.copy(alpha = 0.4f))
                                .clickable(enabled = canAdd) { doAdd() }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("添加", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ═══ 代办列表(按日期分组) ═══
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    flat,
                    key = { e ->
                        when (e) {
                            is TodoListEntry.Header -> "h_${e.date}"
                            is TodoListEntry.Item -> "t_${e.todo.id}"
                        }
                    }
                ) { e ->
                    when (e) {
                        is TodoListEntry.Header -> SectionTitle(e.label)
                        is TodoListEntry.Item -> TodoRow(
                            todo = e.todo,
                            onToggle = { doToggle(e.todo.id) },
                            onDelete = { doDelete(e.todo.id) }
                        )
                    }
                }
                if (flat.isEmpty()) {
                    item(key = "empty") { EmptyHint() }
                }
            }
        }

        // ═══ 顶栏:返回 + 标题 ═══
        if (showTopBar) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("速记代办", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── 分组列表条目类型 ──

private sealed interface TodoListEntry {
    data class Header(val date: LocalDate, val label: String) : TodoListEntry
    data class Item(val todo: ScheduleTodo) : TodoListEntry
}

// ── 子组件 ──

/** 分组标题:TextPrimary, 13sp, Bold */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** 空状态提示 */
@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        Text("暂无代办,快添加一条吧", color = TextHint, fontSize = 13.sp)
    }
}

/** 代办条目行:勾选框 + 标题/时间 + 删除 */
@Composable
private fun TodoRow(todo: ScheduleTodo, onToggle: () -> Unit, onDelete: () -> Unit) {
    val titleColor by animateColorAsState(
        targetValue = if (todo.done) TextHint else TextPrimary,
        animationSpec = tween(300),
        label = "qtTitleColor"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (todo.done) 0.65f else 1f,
        animationSpec = tween(300),
        label = "qtCardAlpha"
    )

    FrostedGlassCard(Modifier.fillMaxWidth().alpha(cardAlpha)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickCheckbox(checked = todo.done, onToggle = onToggle)
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
            Text(
                "删除",
                color = Danger,
                fontSize = 11.sp,
                modifier = Modifier.clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 灰色对勾勾选框 — 复刻项目 [TaskCheckbox] 动效,配色改为灰色渐变(0xFF6B7280)。
 * 边框灰→灰、灰色渐变填充弹入、白色对勾淡入。
 */
@Composable
private fun QuickCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val gray = Color(0xFF6B7280)
    val grayDark = Color(0xFF4B5563)
    val borderGray = Color(0xFFD1D5DB)

    val borderColor by animateColorAsState(
        targetValue = if (checked) gray else borderGray,
        animationSpec = tween(300),
        label = "qtBorderColor"
    )
    val fillScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "qtFillScale"
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(180),
        label = "qtCheckAlpha"
    )

    Box(
        Modifier.size(38.dp).clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            // 灰色渐变填充(弹入)
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(gray, grayDark)))
            )
            // 白色对勾
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp).graphicsLayer { alpha = checkAlpha }
            )
        }
    }
}

// ── 工具函数 ──

/** 日期显示:今天 / 明天 / 后天 / MM月dd日 */
private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(today, date).toInt()
    return when (days) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}
