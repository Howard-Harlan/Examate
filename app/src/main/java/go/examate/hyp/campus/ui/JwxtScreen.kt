package go.examate.hyp.campus.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.ScheduleItem
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.SuccessBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.Warning
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 教务系统功能 Tab */
private enum class JwxtTab(val label: String) {
    SCHEDULE("课表"), GRADE("成绩查询"), EVALUATION("教学评价"), COURSE("选课结果"), XELECTION("选课")
}

/** 教务系统页面 */
@Composable
fun JwxtScreen(onBack: () -> Unit) {
    val vm: JwxtViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    var tab by remember { mutableStateOf(JwxtTab.SCHEDULE) }

    CampusPageScaffold(
        title = "教务系统",
        onBack = onBack,
        bottomBar = {
            Spacer(Modifier.height(10.dp))
            GlassTabBar(
                tabs = JwxtTab.values().map { it.label },
                selectedIndex = tab.ordinal,
                onSelect = { tab = JwxtTab.values()[it] }
            )
            Spacer(Modifier.height(16.dp))
        }
    ) {
        // 所有页面统一外层垂直滚动(课表不再使用 HorizontalPager)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                JwxtTab.SCHEDULE -> ScheduleSection(state.schedule, vm)
                JwxtTab.GRADE -> GradeSection(state.grade, vm)
                JwxtTab.EVALUATION -> EvaluationSection(state.evaluation, vm)
                JwxtTab.COURSE -> SelectedCourseSection(state.selectedCourse, vm)
                JwxtTab.XELECTION -> XkSection(state.xk, vm)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  课表查询:左右滑动切换日期(右滑=昨天,左滑=明天)
// ════════════════════════════════════════════════════════════════

/** 课表:日期切换按钮 + 当天课程列表 */
@Composable
private fun ScheduleSection(state: ScheduleState, vm: JwxtViewModel) {
    LaunchedEffect(Unit) { vm.loadSchedule() }

    if (state.loading) {
        LoadingBlock("正在获取课表...")
        return
    }
    if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { vm.retrySchedule() },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    val schedule = state.items
    val currentDate = state.currentDate

    val semesterStart = remember(schedule) { inferSemesterStart() }
    val weekNum = weekNumberOf(currentDate, semesterStart)
    val dayOfWeekName = currentDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)

    // 日期切换栏:‹ 日期+周次 ›
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .clickable { vm.changeDate(currentDate.minusDays(1)) },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextPrimary, fontSize = 22.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentDate.format(DateTimeFormatter.ofPattern("MM月dd日")),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (weekNum > 0) "$dayOfWeekName · 第${weekNum}周" else dayOfWeekName,
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .clickable { vm.changeDate(currentDate.plusDays(1)) },
                contentAlignment = Alignment.Center
            ) {
                Text("›", color = TextPrimary, fontSize = 22.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // 当天课程列表
    val dayCourses = remember(schedule, currentDate, weekNum) {
        filterByDate(schedule, currentDate, weekNum)
    }
    if (dayCourses.isEmpty()) {
        EmptyBlock(
            if (currentDate == LocalDate.now()) "今天没有课程" else "当天没有课程"
        )
    } else {
        SectionTitle("当天课程(共 ${dayCourses.size} 节)")
        dayCourses.forEach { it ->
            ListItemCard(
                title = it.courseName,
                subtitles = listOf(
                    "第 ${it.section} 节 · ${it.classroom}",
                    "教师:${it.teacher} · ${it.weeks}"
                )
            )
            CardSpacer()
        }
    }
    Spacer(Modifier.height(6.dp))
    LiquidGlassButton(
        text = "返回今天", onClick = { vm.changeDate(LocalDate.now()) },
        style = ButtonStyle.Outline, height = 40.dp
    )
}

/** 推断学期开始日期(第一学期 9/1,第二学期 2/25) */
private fun inferSemesterStart(): LocalDate {
    val now = LocalDate.now()
    return if (now.monthValue >= 8) {
        LocalDate.of(now.year, 9, 1)
    } else {
        LocalDate.of(now.year, 2, 25)
    }
}

/** 计算给定日期对应的学期周次 */
private fun weekNumberOf(date: LocalDate, semesterStart: LocalDate?): Int {
    if (semesterStart == null) return 0
    val days = java.time.temporal.ChronoUnit.DAYS.between(semesterStart, date)
    if (days < 0) return 0
    return (days / 7).toInt() + 1
}

/** 根据日期筛选当天课程 */
private fun filterByDate(schedule: List<ScheduleItem>, date: LocalDate, weekNum: Int): List<ScheduleItem> {
    val dayOfWeek = date.dayOfWeek.value // 1=周一,7=周日
    val dayNames = mapOf(
        1 to listOf("星期一", "周一", "1"),
        2 to listOf("星期二", "周二", "2"),
        3 to listOf("星期三", "周三", "3"),
        4 to listOf("星期四", "周四", "4"),
        5 to listOf("星期五", "周五", "5"),
        6 to listOf("星期六", "周六", "6"),
        7 to listOf("星期日", "周日", "日", "7")
    )
    val dayKeywords = dayNames[dayOfWeek] ?: emptyList()
    return schedule.filter { item ->
        val dayMatch = dayKeywords.any { kw -> item.day.contains(kw) } || item.day.isEmpty()
        val weekMatch = weekNum <= 0 || matchWeeks(item.weeks, weekNum)
        dayMatch && weekMatch
    }.sortedBy { it.section }
}

/** 解析周次字符串,判断给定周次是否在范围内 */
private fun matchWeeks(weeksStr: String, week: Int): Boolean {
    if (weeksStr.isEmpty()) return true
    val cleaned = weeksStr.replace(Regex("[^0-9,\\-]"), "")
    if (cleaned.isEmpty()) return true
    cleaned.split(",").forEach { part ->
        val trimmed = part.trim()
        if (trimmed.contains("-")) {
            val range = trimmed.split("-")
            val start = range.getOrNull(0)?.toIntOrNull()
            val end = range.getOrNull(1)?.toIntOrNull()
            if (start != null && end != null && week in start..end) return true
        } else {
            val n = trimmed.toIntOrNull()
            if (n == week) return true
        }
    }
    return false
}

// ════════════════════════════════════════
//  成绩查询
// ════════════════════════════════════════

@Composable
private fun GradeSection(state: GradeState, vm: JwxtViewModel) {
    var showYearPicker by remember { mutableStateOf(false) }
    var showSemesterPicker by remember { mutableStateOf(false) }

    val xn = state.xn
    val xq = state.xq
    val loading = state.loading
    val error = state.error
    val grades = state.items
    val queried = state.queried

    SectionTitle("查询条件")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            QueryParamRow("学年", xn) { showYearPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            QueryParamRow("学期", SEMESTER_OPTIONS.first { it.first == xq }.second) { showSemesterPicker = true }
        }
    }
    Spacer(Modifier.height(10.dp))
    LiquidGlassButton(
        text = if (loading) "查询中..." else "查询成绩",
        onClick = { vm.queryGrades() },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (loading) {
        LoadingBlock()
    } else if (error.isNotEmpty()) {
        ErrorBlock(error)
    } else if (queried) {
        if (grades.isEmpty()) {
            EmptyBlock("本学期暂无成绩记录")
        } else {
            // 学期汇总:总学分 / 加权 GPA
            val (totalCredit, weightedGpa) = summarizeGrade(grades)
            SectionTitle("学期汇总(共 ${grades.size} 门)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEEF2FF))
                        .padding(14.dp)
                ) {
                    Column {
                        Text("总学分", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(totalCredit, color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SuccessBg)
                        .padding(14.dp)
                ) {
                    Column {
                        Text("加权绩点", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(weightedGpa, color = Success, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionTitle("成绩明细")
            grades.forEach { g ->
                GradeCard(g)
                CardSpacer()
            }
        }
    }

    if (showYearPicker) {
        SingleChoiceDialog("选择学年", YEAR_OPTIONS.map { it to it }, xn,
            { vm.setGradeXn(it); showYearPicker = false }, { showYearPicker = false })
    }
    if (showSemesterPicker) {
        SingleChoiceDialog("选择学期", SEMESTER_OPTIONS, xq,
            { vm.setGradeXq(it); showSemesterPicker = false }, { showSemesterPicker = false })
    }
}

/** 成绩卡片 */
@Composable
private fun GradeCard(g: Grade) {
    val scoreVal = g.score.toDoubleOrNull()
    val scoreColor = when {
        scoreVal == null -> TextPrimary
        scoreVal >= 85 -> Success
        scoreVal >= 60 -> Warning
        else -> Error
    }
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    g.courseName.ifEmpty { "未知课程" },
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    g.score.ifEmpty { "-" },
                    color = scoreColor, fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            val subtitles = mutableListOf<String>()
            if (g.credit.isNotEmpty()) subtitles.add("学分:${g.credit}")
            if (g.gpa.isNotEmpty()) subtitles.add("绩点:${g.gpa}")
            if (g.semester.isNotEmpty()) subtitles.add(g.semester)
            subtitles.forEach { s ->
                Text(s, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** 总学分与加权绩点(学分×绩点 之和 / 学分之和) */
private fun summarizeGrade(grades: List<Grade>): Pair<String, String> {
    var creditSum = 0.0
    var weighted = 0.0
    grades.forEach { g ->
        val c = g.credit.toDoubleOrNull() ?: 0.0
        val gp = g.gpa.toDoubleOrNull()
        if (c > 0 && gp != null) {
            creditSum += c
            weighted += c * gp
        }
    }
    val gpa = if (creditSum > 0) "%.2f".format(weighted / creditSum) else "0.00"
    return "%.1f".format(creditSum) to gpa
}

// ════════════════════════════════════════
//  教学评价
// ════════════════════════════════════════

@Composable
private fun EvaluationSection(state: EvaluationState, vm: JwxtViewModel) {
    LaunchedEffect(Unit) { vm.loadEvaluations() }

    if (state.loading) {
        LoadingBlock("正在获取教学评价...")
        return
    }
    if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { vm.loadEvaluations(forceRefresh = true) },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }
    val evaluations = state.items
    if (evaluations.isEmpty()) {
        EmptyBlock("暂无教学评价任务")
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "刷新", onClick = { vm.loadEvaluations(forceRefresh = true) },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    val (pending, done) = evaluations.partition { it.submitStatus.isEmpty() || it.submitStatus.contains("未") }

    if (pending.isNotEmpty()) {
        SectionTitle("待评价(共 ${pending.size} 项)")
        pending.forEach { e ->
            EvaluationCard(e, isPending = true)
            CardSpacer()
        }
    }
    if (done.isNotEmpty()) {
        if (pending.isNotEmpty()) Spacer(Modifier.height(8.dp))
        SectionTitle("已评价(共 ${done.size} 项)")
        done.forEach { e ->
            EvaluationCard(e, isPending = false)
            CardSpacer()
        }
    }
    Spacer(Modifier.height(6.dp))
    LiquidGlassButton(
        text = "刷新", onClick = { vm.loadEvaluations(forceRefresh = true) },
        style = ButtonStyle.Outline, height = 40.dp
    )
}

/** 教学评价卡片 */
@Composable
private fun EvaluationCard(e: EvaluationItem, isPending: Boolean) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.courseName.ifEmpty { "未知课程" },
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isPending) Color(0xFFFFF3E0) else SuccessBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (isPending) "待评价" else e.submitStatus.ifEmpty { "已评价" },
                        color = if (isPending) Warning else Success,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val subtitles = mutableListOf<String>()
            if (e.teacher.isNotEmpty()) subtitles.add("教师:${e.teacher}")
            if (e.classTime.isNotEmpty()) subtitles.add("时间:${e.classTime}")
            if (e.classroom.isNotEmpty()) subtitles.add("地点:${e.classroom}")
            if (e.college.isNotEmpty()) subtitles.add("学院:${e.college}")
            if (e.courseType.isNotEmpty()) subtitles.add("性质:${e.courseType}")
            if (!isPending && e.score.isNotEmpty()) subtitles.add("评分:${e.score}")
            subtitles.forEach { s ->
                Text(s, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ════════════════════════════════════════
//  选课结果
// ════════════════════════════════════════

@Composable
private fun SelectedCourseSection(state: SelectedCourseState, vm: JwxtViewModel) {
    var showYearPicker by remember { mutableStateOf(false) }
    var showSemesterPicker by remember { mutableStateOf(false) }

    val xn = state.xn
    val xq = state.xq
    val loading = state.loading
    val error = state.error
    val courses = state.items
    val queried = state.queried

    SectionTitle("查询条件")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            QueryParamRow("学年", xn) { showYearPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            QueryParamRow("学期", SEMESTER_OPTIONS.first { it.first == xq }.second) { showSemesterPicker = true }
        }
    }
    Spacer(Modifier.height(10.dp))
    LiquidGlassButton(
        text = if (loading) "查询中..." else "查询选课结果",
        onClick = { vm.querySelectedCourses() },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (loading) {
        LoadingBlock()
    } else if (error.isNotEmpty()) {
        ErrorBlock(error)
    } else if (queried) {
        if (courses.isEmpty()) {
            EmptyBlock("本学期暂无选课记录")
        } else {
            val byType = courses.groupBy { it.courseType.ifEmpty { "其他" } }
            SectionTitle("选课结果(共 ${courses.size} 门)")
            byType.forEach { (type, items) ->
                Text(
                    type,
                    color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                )
                items.forEach { c ->
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.courseName.ifEmpty { "未知课程" },
                                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (c.selected) SuccessBg else ErrorBg)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        if (c.selected) "已选" else "未选",
                                        color = if (c.selected) Success else Error,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            val subtitles = mutableListOf<String>()
                            if (c.teacher.isNotEmpty()) subtitles.add("教师:${c.teacher}")
                            if (c.credit.isNotEmpty()) subtitles.add("学分:${c.credit}")
                            if (c.classTime.isNotEmpty()) subtitles.add("时间:${c.classTime}")
                            if (c.classroom.isNotEmpty()) subtitles.add("地点:${c.classroom}")
                            subtitles.forEach { s ->
                                Text(s, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    CardSpacer()
                }
            }
        }
    }

    if (showYearPicker) {
        SingleChoiceDialog("选择学年", YEAR_OPTIONS.map { it to it }, xn,
            { vm.setSelectedCourseXn(it); showYearPicker = false }, { showYearPicker = false })
    }
    if (showSemesterPicker) {
        SingleChoiceDialog("选择学期", SEMESTER_OPTIONS, xq,
            { vm.setSelectedCourseXq(it); showSemesterPicker = false }, { showSemesterPicker = false })
    }
}

// ════════════════════════════════════════
//  选课
// ════════════════════════════════════════

/** 选课:自动查询选课状态,展示学分/可选课程/已选课程 */
@Composable
private fun XkSection(state: XkState, vm: JwxtViewModel) {
    LaunchedEffect(Unit) { vm.loadXkInfo() }

    if (state.loading) {
        LoadingBlock("正在获取选课信息...")
        return
    }
    if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { vm.refreshXk() },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    val s = state.status
    if (s == null || s.isXk != "1") {
        EmptyBlock("当前不在选课时间段")
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "刷新", onClick = { vm.refreshXk() },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    // 选课学分信息:应修 / 已修 / 历年
    state.credits?.let { c ->
        SectionTitle("选课学分")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEEF2FF))
                    .padding(14.dp)
            ) {
                Column {
                    Text("应修学分", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.requiredCredits.ifEmpty { "-" },
                        color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SuccessBg)
                    .padding(14.dp)
            ) {
                Column {
                    Text("已修学分", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.obtainedCredits.ifEmpty { "-" },
                        color = Success, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFF3E0))
                    .padding(14.dp)
            ) {
                Column {
                    Text("历年学分", color = TextHint, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        c.historyCredits.ifEmpty { "-" },
                        color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    val available = state.available
    val chosen = state.chosen
    val operatingKey = state.operatingKey

    // 可选课程
    SectionTitle("可选课程(共 ${available.size} 门)")
    if (available.isEmpty()) {
        EmptyBlock("暂无可选课程")
    } else {
        available.forEach { c ->
            AvailableCourseCard(
                course = c,
                isChosen = false,
                operating = operatingKey == "a_${c.jxbId}",
                onOperate = { vm.chooseCourse(c) }
            )
            CardSpacer()
        }
    }

    Spacer(Modifier.height(8.dp))

    // 已选课程
    SectionTitle("已选课程(共 ${chosen.size} 门)")
    if (chosen.isEmpty()) {
        EmptyBlock("暂无已选课程")
    } else {
        chosen.forEach { c ->
            AvailableCourseCard(
                course = c,
                isChosen = true,
                operating = operatingKey == "c_${c.jxbId}",
                onOperate = { vm.cancelCourse(c) }
            )
            CardSpacer()
        }
    }

    Spacer(Modifier.height(6.dp))
    LiquidGlassButton(
        text = "刷新", onClick = { vm.refreshXk() },
        style = ButtonStyle.Outline, height = 40.dp
    )
}

/** 选课课程卡片(可选/已选共用) */
@Composable
private fun AvailableCourseCard(
    course: AvailableCourse,
    isChosen: Boolean,
    operating: Boolean,
    onOperate: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.courseName.ifEmpty { "未知课程" },
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "余量 ${course.capacity}",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            val subtitles = mutableListOf<String>()
            if (course.teacher.isNotEmpty()) subtitles.add("教师:${course.teacher}")
            if (course.credit.isNotEmpty()) subtitles.add("学分:${course.credit}")
            if (course.classTime.isNotEmpty()) subtitles.add("时间:${course.classTime}")
            if (course.classroom.isNotEmpty()) subtitles.add("地点:${course.classroom}")
            subtitles.forEach { s ->
                Text(s, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            LiquidGlassButton(
                text = when {
                    operating -> if (isChosen) "退课中..." else "选课中..."
                    isChosen -> "退课"
                    else -> "选课"
                },
                onClick = onOperate,
                style = if (isChosen) ButtonStyle.Outline else ButtonStyle.Primary,
                height = 36.dp
            )
        }
    }
}
