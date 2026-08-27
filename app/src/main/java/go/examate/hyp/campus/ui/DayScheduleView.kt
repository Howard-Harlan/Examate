package go.examate.hyp.campus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.campus.model.getSemesterStart
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle as JavaTextStyle

// ════════════════════════════════════════
//  日视图:某一天的课程 + 待办
// ════════════════════════════════════════

/**
 * 日视图:显示某一天的课程和待办事项
 *
 * - 日期导航已移至主页面顶栏(<> 加减按钮)
 * - 课程区:当天所有课程(按节次顺序),已结束的置灰
 * - 待办区:当天所有待办,可勾选完成;空状态时点击提示卡片即可添加
 *
 * @param date 显示的日期
 * @param entries 当前课表所有课程(内部按日期/周次过滤)
 * @param todos 当前课表所有待办(内部按日期过滤)
 * @param onAddTodo 添加待办回调
 * @param onToggleTodo 切换待办完成状态
 * @param onDeleteTodo 删除待办
 * @param onCourseClick 点击课程(进入编辑)
 */
@Composable
internal fun DayScheduleView(
    date: LocalDate,
    entries: List<ScheduleEntry>,
    todos: List<ScheduleTodo>,
    meta: ScheduleStorage.ScheduleMeta,
    onAddTodo: (ScheduleTodo) -> Unit,
    onToggleTodo: (String) -> Unit,
    onDeleteTodo: (String) -> Unit,
    onCourseClick: (ScheduleEntry) -> Unit,
    onOpenAllTodos: () -> Unit = {},
    periodTimes: List<Pair<String, String>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val isToday = date == today

    // 计算该日期是第几周的第几天(用于过滤课程)
    val semesterStart = remember(meta) { getSemesterStart(meta) }
    val weekOfDay = remember(date, semesterStart) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(semesterStart, date)
        if (days < 0) -1 else (days / 7).toInt() + 1
    }
    val dayOfWeek = date.dayOfWeek.value  // 1=周一 ... 7=周日

    // 当天的课程(按开始节次排序)
    val dayCourses = remember(entries, weekOfDay, dayOfWeek) {
        if (weekOfDay < 1) emptyList()
        else entries.filter { it.dayOfWeek == dayOfWeek && weekOfDay in it.weeks }
            .sortedBy { it.startSection }
    }

    // 当天的待办(按时间排序,无时间的排在最后)
    val dayTodos = remember(todos, date) {
        todos.filter { it.date == date.toString() }
            .sortedWith(compareBy<ScheduleTodo> { it.done }.thenBy { it.time.ifBlank { "99:99" } })
    }

    // 添加待办弹窗
    var showAddTodoDialog by remember { mutableStateOf(false) }
    // 是否进入 AI 提取模式(借鉴 TodoScreen.AddTodoSheet 的双模式切换)
    var aiExtractMode by remember { mutableStateOf(false) }
    var newTodoTitle by remember { mutableStateOf("") }
    // 时间用 TextFieldValue 以便控制光标位置(避免格式化后输入顺序错乱)
    var newTodoTime by remember { mutableStateOf(TextFieldValue("")) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp)
        ) {
            // ── 日期信息条(导航已移至顶栏,此处仅显示星期/周次) ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val weekLabel = date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, java.util.Locale.CHINESE)
                Text(
                    "$weekLabel · 第${weekOfDay.coerceAtLeast(0)}周" + if (isToday) " · 今天" else "",
                    color = if (isToday) Primary else TextHint, fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 课程区 ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("今日课程", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("共${dayCourses.size}节", color = TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))

            if (dayCourses.isEmpty()) {
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "今天没有课程",
                        color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                dayCourses.forEach { entry ->
                    // 跨节课程:取开始节上课时间 → 结束节下课时间(如 3-5 节 = 10:10-16:50)
                    val startTime = periodTimes.getOrNull(entry.startSection - 1)?.first
                    val endTimeStr = periodTimes.getOrNull(entry.endSection - 1)?.second
                    val timeStr = if (startTime != null && endTimeStr != null) "$startTime-$endTimeStr" else ""
                    // 判断是否已结束:用结束时间对比当前时间(仅今天需要判断)
                    val isFinished = if (isToday && endTimeStr != null) {
                        val endLocalTime = runCatching { java.time.LocalTime.parse(endTimeStr) }.getOrNull()
                        endLocalTime != null && endLocalTime.isBefore(java.time.LocalTime.now())
                    } else false

                    FrostedGlassCard(
                        modifier = Modifier.fillMaxWidth().clickable { onCourseClick(entry) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧色条
                            Box(
                                Modifier.size(4.dp, 40.dp).clip(RoundedCornerShape(2.dp))
                                    .background(Color(entry.color))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.courseName,
                                    color = if (isFinished) TextHint else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(2.dp))
                                val locationStr = if (entry.classroom.isNotEmpty()) " · @${entry.classroom}" else ""
                                Text(
                                    "第${entry.startSection}-${entry.endSection}节 $timeStr$locationStr" +
                                        if (isFinished) " · 已结束" else "",
                                    color = if (isFinished) TextHint else TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Text("›", color = TextHint, fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 待办区 ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("待办事项", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                // 完成进度:已完成数随勾选实时变化
                val doneCount = dayTodos.count { it.done }
                Text(
                    if (dayTodos.isEmpty()) "共0项" else "$doneCount/${dayTodos.size} 已完成",
                    color = if (doneCount == dayTodos.size && dayTodos.isNotEmpty()) Primary else TextHint,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(6.dp))
                // 全部待办入口(跳转独立管理页)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenAllTodos() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全部", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 添加待办按钮
                LiquidGlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加待办",
                    onClick = {
                        newTodoTitle = ""; newTodoTime = TextFieldValue("")
                        showAddTodoDialog = true
                    },
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            if (dayTodos.isEmpty()) {
                // 空状态:点击提示卡片即可添加(按钮已移至提示位置)
                FrostedGlassCard(
                    modifier = Modifier.fillMaxWidth().clickable {
                        newTodoTitle = ""; newTodoTime = TextFieldValue("")
                        showAddTodoDialog = true
                    }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("+ ", color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("添加待办", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                dayTodos.forEach { todo ->
                    key(todo.id) {
                        Column {
                            TodoItem(
                                todo = todo,
                                onToggle = { onToggleTodo(todo.id) },
                                onDelete = { onDeleteTodo(todo.id) }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

            }
        }
    }

    // 添加待办弹窗(借鉴 Cresto + TodoScreen.AddTodoSheet:手动 / AI 提取双模式)
    var newTodoPriority by remember { mutableIntStateOf(0) }
    if (showAddTodoDialog) {
        Dialog(onDismissRequest = {
            showAddTodoDialog = false
            aiExtractMode = false
        }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(CardBg).padding(20.dp)
            ) {
                if (aiExtractMode) {
                    // ═══ AI 提取模式:复用 TodoScreen 中的 AiExtractPanel ═══
                    AiExtractPanel(
                        onDismiss = { aiExtractMode = false },
                        onConfirm = { items ->
                            items.forEach { onAddTodo(it) }
                            showAddTodoDialog = false
                            aiExtractMode = false
                        }
                    )
                } else {
                    // ═══ 手动添加模式 ═══
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "添加待办 · ${date.monthValue}月${date.dayOfMonth}日",
                            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        // AI 提取入口(与 TodoScreen.ManualAddPanel 风格统一:边框 + 半透明底)
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Primary, RoundedCornerShape(10.dp))
                                .background(Primary.copy(0.10f))
                                .clickable { aiExtractMode = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text("AI 提取", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newTodoTitle,
                        onValueChange = { newTodoTitle = it },
                        label = { Text("待办内容", color = TextHint, fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    // 时间输入:用 TextFieldValue 固定光标到末尾,
                    // 否则格式化后光标回跳会导致输入 "25" 变成 "52"
                    OutlinedTextField(
                        value = newTodoTime,
                        onValueChange = { newValue ->
                            val digits = newValue.text.filter { it.isDigit() }.take(4)
                            val formatted = when (digits.length) {
                                0, 1, 2 -> digits
                                3 -> "${digits.take(2)}:${digits.takeLast(1)}"
                                else -> "${digits.take(2)}:${digits.takeLast(2)}"
                            }
                            newTodoTime = TextFieldValue(formatted, TextRange(formatted.length))
                        },
                        label = { Text("时间(可不填,如 08:30)", color = TextHint, fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 快捷选择节次:点击即把时间设为该节课的上课时间,便于"在某个时间段添加待办"
                    if (dayCourses.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("关联节次(可选)", color = TextHint, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dayCourses.forEach { c ->
                                val startT = periodTimes.getOrNull(c.startSection - 1)?.first
                                if (startT != null) {
                                    val selected = newTodoTime.text == startT
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) SelectedBg else ChipBg)
                                            .clickable { newTodoTime = TextFieldValue(startT, TextRange(startT.length)) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            "第${c.startSection}节 $startT",
                                            color = if (selected) Color.White else Primary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 优先级选择(借鉴 Cresto):无/低/中/高
                    Spacer(Modifier.height(12.dp))
                    Text("优先级", color = TextHint, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val opts = listOf(0 to "无", 1 to "低", 2 to "中", 3 to "高")
                        val colors = listOf(
                            Color(0xFF9CA3AF), Color(0xFF60A5FA),
                            Color(0xFFFBBF24), Color(0xFFEF4444)
                        )
                        opts.forEach { (p, label) ->
                            val sel = newTodoPriority == p
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) colors[p].copy(0.12f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (sel) colors[p] else TextHint.copy(0.2f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { newTodoPriority = p }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(colors[p]))
                                Text(
                                    label,
                                    color = if (sel) colors[p] else TextHint,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidGlassButton(
                            text = "取消", onClick = {
                                showAddTodoDialog = false
                                aiExtractMode = false
                            },
                            style = ButtonStyle.Outline, height = 40.dp,
                            modifier = Modifier.weight(1f)
                        )
                        LiquidGlassButton(
                            text = "添加", onClick = {
                                if (newTodoTitle.isNotBlank()) {
                                    onAddTodo(ScheduleTodo(
                                        id = "todo_${System.currentTimeMillis()}",
                                        title = newTodoTitle.trim(),
                                        date = date.toString(),
                                        time = newTodoTime.text.trim(),
                                        priority = newTodoPriority
                                    ))
                                }
                                showAddTodoDialog = false
                                newTodoPriority = 0
                                aiExtractMode = false
                            },
                            style = ButtonStyle.White, height = 40.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 待办事项条目(带完成动画)
 *
 * 动画设计:
 * - 勾选圆圈:背景色渐变 + 勾号缩放弹出(spring 回弹)
 * - 标题:颜色渐变 + 删除线动画展开
 * - 整卡:完成后轻微降低不透明度,视觉上"沉下去"
 */
@Composable
private fun TodoItem(
    todo: ScheduleTodo,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    // 标题颜色渐变
    val titleColor by animateColorAsState(
        targetValue = if (todo.done) TextHint else TextPrimary,
        animationSpec = tween(durationMillis = 300),
        label = "todoTitleColor"
    )
    // 整卡不透明度:完成后轻微下沉
    val cardAlpha by animateFloatAsState(
        targetValue = if (todo.done) 0.65f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "todoCardAlpha"
    )

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().alpha(cardAlpha)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 完成勾选框(方框 + 绿渐变填充弹入 + 对勾旋转弹出 + 涟漪)
            TaskCheckbox(checked = todo.done, onToggle = onToggle)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    todo.title,
                    color = titleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    // 完成后加删除线
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
            Text(
                "删除", color = TextHint, fontSize = 11.sp,
                modifier = Modifier.clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 待办完成勾选框 — 复刻 Vue 动效:
 * 方框边框灰→绿、绿色渐变填充 overshoot 弹入、白色对勾旋转弹出、选中时一次性涟漪扩散。
 */
@Composable
private fun TaskCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val emerald = Color(0xFF10B981)
    val emeraldDark = Color(0xFF059669)
    val borderGray = Color(0xFFD1D5DB)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    // 绿渐变填充:选中 overshoot 弹入,取消缩回
    val fillScale by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "fill"
    )
    // 对勾:缩放 + 旋转弹出
    val checkScale by animateFloatAsState(
        if (checked) 1f else 0.3f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "checkScale"
    )
    val checkRot by animateFloatAsState(if (checked) 0f else 20f, spring(dampingRatio = 0.5f), label = "checkRot")
    val checkAlpha by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "checkAlpha")
    // 边框:灰→绿
    val borderColor by animateColorAsState(if (checked) emerald else borderGray, tween(300), label = "border")

    // 涟漪:选中时一次性扩散浡出
    val ripple = remember { Animatable(0f) }
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
        // 方框
        Box(
            Modifier.size(22.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .clip(RoundedCornerShape(6.dp))
                .background(CardBg)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            // 绿色渐变填充(弹入)
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(emerald, emeraldDark)))
            )
            // 对勾
            Icon(
                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(15.dp).graphicsLayer {
                    scaleX = checkScale; scaleY = checkScale; rotationZ = checkRot; alpha = checkAlpha
                }
            )
        }
    }
}
