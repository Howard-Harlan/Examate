package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.MAX_SECTIONS

import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.campus.model.getSemesterStart
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextSecondary
import java.time.LocalDate

// ════════════════════════════════════════
//  周视图网格
// ════════════════════════════════════════

/**
 * 周视图网格:表头(星期 + 日期)+ 时间列 + 各天列。
 *
 * - 关闭周六/周日时,剩余列用 weight 动态分配宽度,自动变宽。
 * - 表头在星期标签下方显示日期(月/日),基于 [semesterStartDate] 推算;
 *   未设置时使用默认开学日期(9月1日/2月25日),与 [inferCurrentWeek] 同步。
 * - 代办已剥离到日视图,周视图仅在表头显示"该天有代办"的小圆点标记。
 */
@Composable
internal fun WeekScheduleGrid(
    entries: List<ScheduleEntry>,
    todos: List<ScheduleTodo>,
    currentWeek: Int,
    showSaturday: Boolean = true,
    showSunday: Boolean = true,
    /** 开学第一个星期一(ISO yyyy-MM-dd),用于在表头显示每周对应日期;空则使用默认推断 */
    semesterStartDate: String? = null,
    /** 课程卡片字体缩放档:0=小 1=标准 2=大 */
    fontScale: Int = 1,
    /** 当前生效的节次时间表(上课,下课) */
    periodTimes: List<Pair<String, String>> = emptyList(),
    onCourseClick: (ScheduleEntry) -> Unit,
    onEmptyCellClick: (day: Int, section: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cellHeight = 58.dp
    val timeColWidth = 40.dp
    // 显示的节次数与时间表锁定:PERIOD_TIME.size 决定节次数
    val sectionCount = periodTimes.size.coerceAtMost(MAX_SECTIONS)

    // 构建要显示的星期列表(根据 showSaturday/showSunday 过滤)
    val visibleDays = (1..7).filter { day ->
        when (day) {
            6 -> showSaturday
            7 -> showSunday
            else -> true
        }
    }

    // 计算本周一到本周日对应的日期(用于表头显示月/日)
    // 关键:无论是否设置 semesterStartDate,都基于 currentWeek 推算,
    // 确保切换周次时表头日期同步变化
    val weekDates: Map<Int, LocalDate> = remember(currentWeek, semesterStartDate) {
        // 构造一个临时 meta 用来复用 getSemesterStart 的默认推断逻辑
        val tempMeta = ScheduleStorage.ScheduleMeta(
            id = "", name = "",
            semesterStartDate = semesterStartDate
        )
        val start = getSemesterStart(tempMeta)
        // 开学周一 = start + (currentWeek-1)*7 天
        val monday = start.plusDays((currentWeek - 1) * 7L)
        (1..7).associateWith { d -> monday.plusDays((d - 1).toLong()) }
    }

    // 按 date 分组代办,用于在表头显示标记
    val todosByDate: Map<String, List<ScheduleTodo>> = remember(todos) {
        todos.groupBy { it.date }
    }

    // 预分组:按星期分组当前周课程,避免在 visibleDays.forEach 内部反复 filter
    val entriesByDay: Map<Int, List<ScheduleEntry>> = remember(entries, currentWeek) {
        entries.filter { currentWeek in it.weeks }.groupBy { it.dayOfWeek }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // 表头:星期 + 日期 + 代办标记
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(timeColWidth).height(40.dp), contentAlignment = Alignment.Center) {
                Text("周", color = TextHint, fontSize = 10.sp)
            }
            val today = LocalDate.now()
            visibleDays.forEach { day ->
                val date = weekDates[day]
                val isToday = date != null && date == today
                val dateStr = date?.toString() ?: ""
                val hasTodo = todosByDate[dateStr]?.isNotEmpty() == true
                // 列宽用 weight 动态分配:关闭周六/周日时,剩余列自动变宽
                Column(
                    Modifier.weight(1f).height(40.dp).padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(DAY_LABELS[day - 1], color = if (isToday) Primary else TextHint,
                        fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium)
                    if (date != null) {
                        Text(
                            "${date.monthValue}/${date.dayOfMonth}",
                            color = if (isToday) Primary else TextHint,
                            fontSize = 9.sp
                        )
                    }
                    // 代办标记:小圆点
                    if (hasTodo) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.size(4.dp).clip(RoundedCornerShape(50))
                                .background(Color(0xFFE39B5B))
                        )
                    }
                }
            }
        }

        // 网格体:时间列 + 显示的天列
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(timeColWidth)) {
                for (period in 1..sectionCount) {
                    val timePair = periodTimes.getOrNull(period - 1)
                    Box(
                        Modifier.height(cellHeight).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 节次号
                            Text("$period", color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            // 上课时间
                            timePair?.first?.let {
                                Text(it, color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Medium)
                            }
                            // 下课时间(用户要求:左侧也显示下课时间)
                            timePair?.second?.let {
                                Text(it, color = TextHint, fontSize = 8.sp)
                            }
                        }
                    }
                }
            }

            // 每天列用 weight(1f) 动态分配剩余宽度,关闭周六/周日时其他列自动变宽
            visibleDays.forEach { day ->
                DayColumn(
                    day = day,
                    entries = entriesByDay[day] ?: emptyList(),
                    cellHeight = cellHeight,
                    sectionCount = sectionCount,
                    fontScale = fontScale,
                    onCourseClick = onCourseClick,
                    onEmptyCellClick = { section -> onEmptyCellClick(day, section) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: Int,
    entries: List<ScheduleEntry>,
    cellHeight: Dp,
    sectionCount: Int = MAX_SECTIONS,
    fontScale: Int = 1,
    onCourseClick: (ScheduleEntry) -> Unit,
    onEmptyCellClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        var period = 1
        while (period <= sectionCount) {
            val course = entries.find { it.startSection == period }
            if (course != null) {
                val span = (course.endSection - course.startSection + 1).coerceAtLeast(1)
                CourseCard(
                    entry = course,
                    fontScale = fontScale,
                    modifier = Modifier
                        .height(cellHeight * span)
                        .fillMaxWidth()
                        .padding(1.dp),
                    onClick = { onCourseClick(course) }
                )
                period += span
            } else {
                val p = period
                Box(
                    Modifier
                        .height(cellHeight)
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(InputBg)
                        .clickable { onEmptyCellClick(p) }
                )
                period++
            }
        }
    }
}

@Composable
private fun CourseCard(
    entry: ScheduleEntry,
    fontScale: Int = 1,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = Color(entry.color)
    val span = (entry.endSection - entry.startSection + 1)
    // span 越大(连堂节数越多)卡片越高,字体随之放大;再乘用户设置的缩放档(小/标准/大)
    val factor = when (fontScale) { 0 -> 0.9f; 2 -> 1.15f; else -> 1.0f }
    val nameSize = (when { span >= 3 -> 13f; span == 2 -> 12f; else -> 10f } * factor).sp
    val subSize = ((when { span >= 3 -> 10.5f; span == 2 -> 10f; else -> 9f }) * factor).sp

    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor.copy(0.85f))
            .clickable { onClick() }
            .padding(horizontal = 3.dp, vertical = if (span > 1) 4.dp else 2.dp),
        // 连堂课卡片高:内容垂直居中并留间距,避免"大卡片小文字挤在顶部"
        verticalArrangement = if (span > 1)
            Arrangement.spacedBy(2.dp, Alignment.CenterVertically) else Arrangement.Top
    ) {
        Text(
            entry.courseName,
            color = Color.White,
            fontSize = nameSize,
            fontWeight = FontWeight.Medium,
            maxLines = if (span > 1) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
        // 地点(教室)
        if (entry.classroom.isNotEmpty()) {
            Text(
                "@${entry.classroom}",
                color = Color.White.copy(0.9f),
                fontSize = subSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 连堂课有额外空间,再显示教师
        if (span > 1 && entry.teacher.isNotEmpty()) {
            Text(
                entry.teacher,
                color = Color.White.copy(0.75f),
                fontSize = subSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
