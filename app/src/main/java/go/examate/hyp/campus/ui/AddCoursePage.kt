package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.campus.model.COURSE_COLORS
import go.examate.hyp.campus.model.DEFAULT_TOTAL_WEEKS
import go.examate.hyp.campus.model.MAX_SECTIONS
import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.randomCourseColor
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextSecondary

// ════════════════════════════════════════
//  全屏页面:添加课程(课程信息 + 多个时间段 + 颜色编辑)
// ════════════════════════════════════════

/** 时间段数据(一天的课程安排) */
data class TimeSlot(
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2
)

/**
 * 添加/编辑课程全屏页面(统一入口)
 *
 * - 编辑模式([editEntry] != null):预填该 entry 的单一时段,可改不可加;支持删除。
 * - 新增模式([editEntry] == null):默认无时间段,由用户点"+ 添加时间段"通过 [AddTimeSlotDialog] 选择;
 *   颜色随机选择,避免多个新课程颜色都一样。
 *
 * @param totalWeeks 学期总周数(周次选择器范围),默认 20
 */
@Composable
fun AddCoursePage(
    onBack: () -> Unit,
    onSave: (List<ScheduleEntry>) -> Unit,
    /** 编辑模式:传入已有 entry 则预填(仅编辑单个时间段) */
    editEntry: ScheduleEntry? = null,
    /** 新增模式下预填的星期(1-7),点击空格子时使用 */
    initialDay: Int = 1,
    /** 新增模式下预填的开始节次,点击空格子时使用 */
    initialSection: Int = 1,
    /** 学期总周数(周次选择器范围),默认 20 */
    totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
    /** 编辑模式回调:删除该 entry */
    onDelete: (() -> Unit)? = null
) {
    // ── 课程信息 ──
    var name by remember { mutableStateOf(editEntry?.courseName ?: "") }
    var classroom by remember { mutableStateOf(editEntry?.classroom ?: "") }
    var teacher by remember { mutableStateOf(editEntry?.teacher ?: "") }
    var note by remember { mutableStateOf(editEntry?.note ?: "") }
    // 颜色:编辑模式保留原色;新增模式随机选色,避免多个新课程颜色都一样
    var selectedColor by remember {
        mutableStateOf(editEntry?.color ?: randomCourseColor())
    }

    // ── 周次 ──
    var selectedWeeks by remember {
        mutableStateOf<Set<Int>>((editEntry?.weeks ?: (1..totalWeeks).toList()).toMutableSet())
    }

    // ── 时间段列表(一门课一周可能多次) ──
    // 编辑模式:预填该 entry 的时段;新增模式:默认为空,由用户点"添加时间段"选择
    var timeSlots by remember {
        mutableStateOf(
            if (editEntry != null) mutableListOf(
                TimeSlot(editEntry.dayOfWeek, editEntry.startSection, editEntry.endSection)
            ) else mutableListOf<TimeSlot>()
        )
    }
    // 是否显示"添加时间段"对话框(让用户选星期/节次)
    var showAddSlotDialog by remember { mutableStateOf(false) }
    // 颜色选择器是否展开
    var showColorPicker by remember { mutableStateOf(false) }
    // 内容滚动状态(新增时间段时自动滚到底部)
    val scrollState = rememberScrollState()

    // 提取保存逻辑:构造 entries 列表并回调
    fun buildAndSave() {
        if (name.isBlank() || timeSlots.isEmpty()) return
        val weeks = selectedWeeks.toList().sorted().ifEmpty { (1..totalWeeks).toList() }
        val baseId = if (editEntry != null) editEntry.id else "usr_${System.currentTimeMillis()}"
        val entries = if (editEntry != null) {
            // 编辑模式:第一个时段更新原 entry,其余时段新增
            timeSlots.mapIndexed { idx, slot ->
                if (idx == 0) {
                    editEntry.copy(
                        courseName = name.trim(),
                        teacher = teacher.trim(),
                        classroom = classroom.trim(),
                        dayOfWeek = slot.dayOfWeek,
                        startSection = slot.startSection,
                        endSection = slot.endSection,
                        weeks = weeks,
                        color = selectedColor,
                        note = note.trim()
                    )
                } else {
                    ScheduleEntry(
                        id = "${baseId}_$idx",
                        courseName = name.trim(),
                        teacher = teacher.trim(),
                        classroom = classroom.trim(),
                        dayOfWeek = slot.dayOfWeek,
                        startSection = slot.startSection,
                        endSection = slot.endSection,
                        weeks = weeks,
                        color = selectedColor,
                        note = note.trim()
                    )
                }
            }
        } else {
            // 新增模式:每个时间段一个 entry
            timeSlots.mapIndexed { idx, slot ->
                ScheduleEntry(
                    id = "${baseId}_$idx",
                    courseName = name.trim(),
                    teacher = teacher.trim(),
                    classroom = classroom.trim(),
                    dayOfWeek = slot.dayOfWeek,
                    startSection = slot.startSection,
                    endSection = slot.endSection,
                    weeks = weeks,
                    color = selectedColor,
                    note = note.trim()
                )
            }
        }
        onSave(entries)
    }

    // 新增时间段时自动滚动到底部,确保用户看到新卡片
    LaunchedEffect(timeSlots.size) {
        if (timeSlots.size > 1) {
            kotlinx.coroutines.delay(80)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ── 顶栏:返回 + 标题 + 颜色 + 保存 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            )
            Text(
                if (editEntry != null) "编辑课程" else "添加课程",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            // 颜色按钮(顶栏):当前色圆点 + 展开时半透明黑底箭头保证对比度
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(50))
                    .background(Color(selectedColor))
                    .clickable { showColorPicker = !showColorPicker },
                contentAlignment = Alignment.Center
            ) {
                if (showColorPicker) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▾", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 保存按钮(顶栏,缩窄):名称为空时半透明禁用
            val canSave = name.isNotBlank() && timeSlots.isNotEmpty()
            LiquidGlassButton(
                text = "保存",
                onClick = { buildAndSave() },
                style = ButtonStyle.White, height = 32.dp,
                modifier = Modifier.width(72.dp).then(
                    if (canSave) Modifier else Modifier.alpha(0.4f)
                )
            )
        }

        // 颜色选择器(展开时显示)
        if (showColorPicker) {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    COURSE_COLORS.forEach { color ->
                        Box(
                            Modifier.size(28.dp).clip(RoundedCornerShape(50))
                                .background(Color(color))
                                .clickable {
                                    selectedColor = color
                                    showColorPicker = false
                                }
                                .then(
                                    if (selectedColor == color) Modifier.border(2.dp, TextPrimary, RoundedCornerShape(50))
                                    else Modifier
                                )
                        )
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState)
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 32.dp)
        ) {
            // ── 课程信息 ──
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    EditField("课程名称", name) { name = it }
                    Spacer(Modifier.height(8.dp))
                    EditField("上课地点(可不填)", classroom) { classroom = it }
                    Spacer(Modifier.height(8.dp))
                    EditField("授课老师(可不填)", teacher) { teacher = it }
                    Spacer(Modifier.height(8.dp))
                    EditField("备注", note) { note = it }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 周次选择 ──
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    WeekMultiSelect(
                        selectedWeeks = selectedWeeks,
                        onWeeksChange = { selectedWeeks = it },
                        totalWeeks = totalWeeks
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 时间段(编辑模式只显示1个,新增模式可多个) ──
            timeSlots.forEachIndexed { index, slot ->
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (editEntry == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("时间段 ${index + 1}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                if (timeSlots.size > 1) {
                                    // 删除时间段:加大点击区域
                                    Text("删除", color = Error, fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            timeSlots = timeSlots.toMutableList().also { it.removeAt(index) }
                                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // 星期选择
                        Text("星期", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                val selected = slot.dayOfWeek == d
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) SelectedBg else ScheduleCellBg)
                                        .clickable {
                                            timeSlots = timeSlots.toMutableList().also {
                                                it[index] = it[index].copy(dayOfWeek = d)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(DAY_LABELS[d - 1], color = if (selected) Color.White else TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 节次选择
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumberSelect("开始节次", slot.startSection, 1..MAX_SECTIONS) { newStart ->
                                timeSlots = timeSlots.toMutableList().also {
                                    val end = if (it[index].endSection < newStart) newStart else it[index].endSection
                                    it[index] = it[index].copy(startSection = newStart, endSection = end)
                                }
                            }
                            NumberSelect("结束节次", slot.endSection, 1..MAX_SECTIONS) { newEnd ->
                                timeSlots = timeSlots.toMutableList().also {
                                    it[index] = it[index].copy(endSection = newEnd.coerceAtLeast(it[index].startSection))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 添加时间段按钮(仅新增模式):点击后弹出对话框让用户选星期/节次
            if (editEntry == null) {
                LiquidGlassButton(
                    text = "+ 添加时间段",
                    onClick = { showAddSlotDialog = true },
                    style = ButtonStyle.Outline, height = 40.dp
                )
                if (timeSlots.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请添加至少一个时间段",
                        color = TextHint, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // 删除按钮(仅编辑模式,二次确认避免误删)
            if (editEntry != null && onDelete != null) {
                Spacer(Modifier.height(16.dp))
                var showDeleteConfirm by remember { mutableStateOf(false) }
                LiquidGlassButton(
                    text = "删除该课程",
                    onClick = { showDeleteConfirm = true },
                    style = ButtonStyle.Outline, height = 40.dp
                )
                if (showDeleteConfirm) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteConfirm = false }) {
                        Column(
                            Modifier.width(260.dp).clip(RoundedCornerShape(20.dp))
                                .background(CardBg).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("删除课程", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "确定删除「${editEntry.courseName}」吗?此操作不可恢复",
                                color = TextSecondary, fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiquidGlassButton(
                                    text = "取消", onClick = { showDeleteConfirm = false },
                                    style = ButtonStyle.Outline, height = 40.dp,
                                    modifier = Modifier.weight(1f)
                                )
                                LiquidGlassButton(
                                    text = "删除", onClick = { onDelete() },
                                    style = ButtonStyle.White, height = 40.dp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加时间段对话框:让用户选星期 + 开始节 + 结束节
    if (showAddSlotDialog) {
        AddTimeSlotDialog(
            initialDay = initialDay,
            onConfirm = { day, start, end ->
                timeSlots = timeSlots.toMutableList().also {
                    it.add(TimeSlot(dayOfWeek = day, startSection = start, endSection = end))
                }
                showAddSlotDialog = false
            },
            onDismiss = { showAddSlotDialog = false }
        )
    }
}

/**
 * 添加时间段对话框:用户选择星期几 + 开始节次 + 结束节次
 * 比默认填 1-2 节再点很多次 + 更高效,尤其面对第 9-10 节课
 */
@Composable
private fun AddTimeSlotDialog(
    initialDay: Int = 1,
    onConfirm: (day: Int, startSection: Int, endSection: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var day by remember { mutableStateOf(initialDay.coerceIn(1, 7)) }
    var startSec by remember { mutableStateOf(1) }
    var endSec by remember { mutableStateOf(2) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(CardBg).padding(20.dp)
        ) {
            Text("添加时间段", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            // 星期选择
            Text("星期", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { d ->
                    val selected = day == d
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) SelectedBg else ScheduleCellBg)
                            .clickable { day = d },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(DAY_LABELS[d - 1], color = if (selected) Color.White else TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 节次选择(保留加减逻辑)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NumberSelect("开始节次", startSec, 1..MAX_SECTIONS) {
                    startSec = it
                    if (endSec < startSec) endSec = startSec
                }
                NumberSelect("结束节次", endSec, 1..MAX_SECTIONS) {
                    endSec = it.coerceAtLeast(startSec)
                }
            }
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "添加", onClick = { onConfirm(day, startSec, endSec) },
                    style = ButtonStyle.White, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
