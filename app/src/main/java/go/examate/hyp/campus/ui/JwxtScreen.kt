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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.campus.model.AvailableCourse
import go.examate.hyp.campus.model.EmptyClassroom
import go.examate.hyp.campus.model.EvaluationItem
import go.examate.hyp.campus.model.EvaluationQuestion
import go.examate.hyp.campus.model.Grade
import go.examate.hyp.campus.model.GradeDetail
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
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

/** 教务系统功能 Tab(移除课表,合并选课+选课结果为"选课") */
private enum class JwxtTab(val label: String) {
    GRADE("成绩查询"), EVALUATION("教学评价"), XUANKE("选课"), CLASSROOM("空闲教室")
}

/** 教务系统页面 */
@Composable
fun JwxtScreen(onBack: () -> Unit) {
    val vm: JwxtViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(JwxtTab.GRADE) }

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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                JwxtTab.GRADE -> GradeSection(state.grade, vm)
                JwxtTab.EVALUATION -> EvaluationSection(state.evaluation, vm)
                JwxtTab.XUANKE -> XuankeSection(state.xk, state.selectedCourse, vm)
                JwxtTab.CLASSROOM -> ClassroomSection(state.classroom, vm)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  选课(合并:选课操作 + 选课结果)
// ════════════════════════════════════════════════════════════════

/**
 * 选课页面:上方选课操作(可选/已选),下方选课结果查询。
 * 通过子 Tab 切换"选课操作"和"选课结果"。
 */
@Composable
private fun XuankeSection(xkState: XkState, courseState: SelectedCourseState, vm: JwxtViewModel) {
    var subTab by remember { mutableStateOf(0) } // 0=选课操作, 1=选课结果

    // 子 Tab 切换栏
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf("选课操作", "选课结果").forEachIndexed { index, label ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (subTab == index) Primary else SurfaceVariant)
                    .clickable { subTab = index }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (subTab == index) Color.White else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    when (subTab) {
        0 -> XkSection(xkState, vm)
        1 -> SelectedCourseSection(courseState, vm)
    }
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
            SectionTitle("成绩明细(点击查看详情)")
            grades.forEach { g ->
                GradeCard(g, onClick = { vm.loadGradeDetail(g) })
                CardSpacer()
            }
        }
    }

    // 成绩明细弹窗 — 包含 error 状态防止闪退
    if (state.detail != null || state.detailLoading || state.detailError.isNotEmpty()) {
        GradeDetailDialog(
            grade = state.detailGrade,
            detail = state.detail,
            loading = state.detailLoading,
            error = state.detailError,
            onDismiss = { vm.clearGradeDetail() }
        )
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
private fun GradeCard(g: Grade, onClick: () -> Unit = {}) {
    val scoreVal = g.score.toDoubleOrNull()
    val scoreColor = when {
        scoreVal == null -> TextPrimary
        scoreVal >= 85 -> Success
        scoreVal >= 60 -> Warning
        else -> Error
    }
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
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
            if (g.courseCode.isNotEmpty()) subtitles.add("代码:${g.courseCode}")
            subtitles.forEach { s ->
                Text(s, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (g.jxbId.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("点击查看成绩构成 →", color = Primary, fontSize = 11.sp)
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
        SectionTitle("待评价(共 ${pending.size} 项,点击进行评价)")
        pending.forEach { e ->
            EvaluationCard(e, isPending = true, onClick = {
                if (e.wjbm.isNotEmpty()) vm.loadEvaluationQuestions(e.wjbm)
            })
            CardSpacer()
        }
    }
    if (done.isNotEmpty()) {
        if (pending.isNotEmpty()) Spacer(Modifier.height(8.dp))
        SectionTitle("已评价(共 ${done.size} 项,点击查看详情)")
        done.forEach { e ->
            EvaluationCard(e, isPending = false, onClick = {
                // 已评价的也显示详情弹窗
                vm.showEvalDetail(e)
            })
            CardSpacer()
        }
    }
    Spacer(Modifier.height(6.dp))
    LiquidGlassButton(
        text = "刷新", onClick = { vm.loadEvaluations(forceRefresh = true) },
        style = ButtonStyle.Outline, height = 40.dp
    )

    // 评价表单弹窗
    if (state.questions.isNotEmpty() || state.questionLoading) {
        EvaluationFormDialog(
            questions = state.questions,
            scores = state.evalScores,
            submitting = state.submitting,
            submitError = state.submitError,
            onScoreChange = { id, score -> vm.setEvalScore(id, score) },
            onSubmit = { vm.submitEvaluation() },
            onDismiss = { vm.clearEvalQuestions() }
        )
    }
    // 已评价详情弹窗
    state.detailItem?.let { e ->
        EvalDetailDialog(item = e, onDismiss = { vm.clearEvalDetail() })
    }
}

/** 教学评价卡片 */
@Composable
private fun EvaluationCard(e: EvaluationItem, isPending: Boolean, onClick: () -> Unit = {}) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
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
            if (isPending && e.wjbm.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("点击进行评价 →", color = Primary, fontSize = 11.sp)
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
                            if (c.examMethod.isNotEmpty()) subtitles.add("考核:${c.examMethod}")
                            if (c.weeks.isNotEmpty()) subtitles.add("周次:${c.weeks}")
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

// ════════════════════════════════════════
//  成绩明细弹窗
// ════════════════════════════════════════

@Composable
private fun GradeDetailDialog(
    grade: Grade?,
    detail: GradeDetail?,
    loading: Boolean,
    error: String,
    onDismiss: () -> Unit
) {
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("成绩构成明细")
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(28.dp), color = Primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (error.isNotEmpty()) {
                    Text(error, color = Error, fontSize = 13.sp)
                } else if (detail != null || grade != null) {
                    // 课程基本信息(从 Grade 列表数据)
                    grade?.let { g ->
                        Text(g.courseName.ifEmpty { "未知课程" }, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (g.credit.isNotEmpty()) Text("学分:${g.credit}", color = TextSecondary, fontSize = 12.sp)
                            if (g.gpa.isNotEmpty()) Text("绩点:${g.gpa}", color = TextSecondary, fontSize = 12.sp)
                            if (g.examType.isNotEmpty()) Text(g.examType, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    // 成绩构成
                    val d = detail
                    if (d != null && (d.regularScore.isNotEmpty() || d.finalExamScore.isNotEmpty() || d.totalScore.isNotEmpty())) {
                        // 总分大卡片
                        if (d.totalScore.isNotEmpty()) {
                            val scoreVal = d.totalScore.toDoubleOrNull()
                            val scoreColor = when { scoreVal == null -> Primary; scoreVal >= 85 -> Success; scoreVal >= 60 -> Warning; else -> Error }
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SuccessBg).padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("总评", color = TextHint, fontSize = 11.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(d.totalScore, color = scoreColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        // 平时分和期末分
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (d.regularScore.isNotEmpty()) {
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEEF2FF)).padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("平时成绩", color = TextHint, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text(d.regularScore, color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (d.finalExamScore.isNotEmpty()) {
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF3E0)).padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("期末成绩", color = TextHint, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text(d.finalExamScore, color = Warning, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        // 其他子项
                        val otherSubItems = d.subItems.filterKeys { it !in listOf("平时成绩","期末成绩","总评") }
                        if (otherSubItems.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("其他构成", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            otherSubItems.forEach { (k,v) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(k, color = TextSecondary, fontSize = 13.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text(v, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    } else if (d != null && d.subItems.isNotEmpty()) {
                        // 回退:显示所有子项
                        d.subItems.forEach { (k,v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(k, color = TextSecondary, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text(v, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else {
                        Text("暂无成绩构成明细", color = TextHint, fontSize = 13.sp)
                    }
                }
        }
        PlainDialogButtons(listOf(Triple("关闭", false) { onDismiss() }))
    }
}

// ════════════════════════════════════════
//  空闲教室
// ════════════════════════════════════════

@Composable
private fun ClassroomSection(state: ClassroomState, vm: JwxtViewModel) {
    LaunchedEffect(Unit) { vm.loadBuildings() }

    var showCampusPicker by remember { mutableStateOf(false) }
    var showRoomTypePicker by remember { mutableStateOf(false) }
    var showBuildingPicker by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showDaysPicker by remember { mutableStateOf(false) }
    var showSectionPicker by remember { mutableStateOf(false) }

    SectionTitle("查询条件")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // 校区 - 动态选项
            val campusLabel = state.campusOptions.firstOrNull { it.id == state.selectedCampusId }?.name ?: state.selectedCampusId.ifEmpty { "全部" }
            QueryParamRow("校区", campusLabel) { showCampusPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            // 楼号 - 动态选项
            val buildingLabel = state.buildingOptions.firstOrNull { it.id == state.selectedBuildingId }?.name ?: state.selectedBuildingId.ifEmpty { "全部" }
            QueryParamRow("楼号", buildingLabel) { showBuildingPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            // 场地类别 - 动态选项
            val roomTypeLabel = state.roomTypeOptions.firstOrNull { it.id == state.selectedRoomType }?.name ?: state.selectedRoomType.ifEmpty { "全部" }
            QueryParamRow("场地类别", roomTypeLabel) { showRoomTypePicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            // 周次范围
            val weekLabel = if (state.startWeek.isNotEmpty() || state.endWeek.isNotEmpty())
                "第${state.startWeek}周-第${state.endWeek}周" else "不限"
            QueryParamRow("周次范围", weekLabel) { showWeekPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            // 星期
            val daysLabel = state.weekDays.ifEmpty { "不限" }
            QueryParamRow("星期", daysLabel) { showDaysPicker = true }
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
            // 节次
            val sectionLabel = state.sectionRange.ifEmpty { "不限" }
            QueryParamRow("节次", sectionLabel) { showSectionPicker = true }
        }
    }
    Spacer(Modifier.height(10.dp))
    LiquidGlassButton(
        text = if (state.loading) "查询中..." else "查询空闲教室",
        onClick = { vm.queryEmptyClassrooms() },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (state.loading) {
        LoadingBlock("正在查询空闲教室...")
    } else if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
    } else if (state.loaded) {
        if (state.items.isEmpty()) {
            EmptyBlock("未找到空闲教室")
        } else {
            val byBuilding = state.items.groupBy { it.buildingName.ifEmpty { "未知教学楼" } }
            SectionTitle("空闲教室(共 ${state.items.size} 间)")
            byBuilding.forEach { (building, rooms) ->
                Text(
                    building,
                    color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                )
                rooms.forEach { room ->
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    room.classroomName.ifEmpty { "未知教室" },
                                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (room.capacity.isNotEmpty()) {
                                    Text("${room.capacity}座", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                            val subtitles = mutableListOf<String>()
                            if (room.roomType.isNotEmpty()) subtitles.add("类型:${room.roomType}")
                            if (room.campus.isNotEmpty()) subtitles.add("校区:${room.campus}")
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

    // 弹窗 - 完全使用动态解析的选项
    if (showCampusPicker) {
        val options = state.campusOptions.map { it.id to it.name }
        SingleChoiceDialog("选择校区", options, state.selectedCampusId,
            { vm.setClassroomCampus(it); showCampusPicker = false },
            { showCampusPicker = false })
    }
    if (showBuildingPicker) {
        val options = state.buildingOptions.map { it.id to it.name }
        SingleChoiceDialog("楼号", options, state.selectedBuildingId,
            { vm.setClassroomBuilding(it); showBuildingPicker = false },
            { showBuildingPicker = false })
    }
    if (showRoomTypePicker) {
        val options = state.roomTypeOptions.map { it.id to it.name }
        SingleChoiceDialog("场地类别", options, state.selectedRoomType,
            { vm.setClassroomRoomType(it); showRoomTypePicker = false },
            { showRoomTypePicker = false })
    }
    if (showWeekPicker) {
        val weeks = (1..20).map { "$it" to "第${it}周" }
        SingleChoiceDialog("起始周次", weeks, state.startWeek,
            { vm.setClassroomStartWeek(it); vm.setClassroomEndWeek(it); showWeekPicker = false },
            { showWeekPicker = false })
    }
    if (showDaysPicker) {
        val days = listOf("1" to "周一", "2" to "周二", "3" to "周三", "4" to "周四", "5" to "周五", "6" to "周六", "7" to "周日")
        SingleChoiceDialog("星期", listOf("" to "不限") + days, state.weekDays,
            { vm.setClassroomWeekDays(it); showDaysPicker = false },
            { showDaysPicker = false })
    }
    if (showSectionPicker) {
        val sections = listOf("" to "不限", "1-2" to "第1-2节", "3-4" to "第3-4节", "5-6" to "第5-6节",
            "7-8" to "第7-8节", "9-10" to "第9-10节", "1-4" to "上午", "5-8" to "下午", "9-12" to "晚上")
        SingleChoiceDialog("节次", sections, state.sectionRange,
            { vm.setClassroomSectionRange(it); showSectionPicker = false },
            { showSectionPicker = false })
    }
}

// ════════════════════════════════════════
//  评价表单弹窗(待评价)
// ════════════════════════════════════════

@Composable
private fun EvaluationFormDialog(
    questions: List<EvaluationQuestion>,
    scores: Map<String, Double>,
    submitting: Boolean,
    submitError: String,
    onScoreChange: (String, Double) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("教学评价")
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
                Text("请对以下指标进行评价", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))

                if (questions.isEmpty()) {
                    Text("正在加载评价指标...", color = TextHint, fontSize = 13.sp)
                } else {
                    questions.forEach { q ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(
                                q.name.ifEmpty { "指标${q.id}" },
                                color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            val currentScore = scores[q.id] ?: q.selectedScore
                            val commonScores = listOf(100.0, 95.0, 90.0, 85.0, 80.0, 75.0, 70.0, 60.0)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                commonScores.filter { it <= q.maxScore }.take(5).forEach { s ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (currentScore > 0 && currentScore == s) Primary
                                                else SurfaceVariant
                                            )
                                            .clickable { onScoreChange(q.id, s) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            s.toInt().toString(),
                                            color = if (currentScore > 0 && currentScore == s) Color.White else TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            if (currentScore > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text("已选: ${currentScore.toInt()}分", color = Primary, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    }
                }

                if (submitError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(submitError, color = Error, fontSize = 12.sp)
                }
        }
        PlainDialogButtons(listOf(
            Triple("取消", false) { onDismiss() },
            Triple(if (submitting) "提交中..." else "提交评价", true) { onSubmit() }
        ))
    }
}

// ════════════════════════════════════════
//  评价详情弹窗(已评价课程)
// ════════════════════════════════════════

@Composable
private fun EvalDetailDialog(item: EvaluationItem, onDismiss: () -> Unit) {
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("评价详情")
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
                // 课程名 + 评分
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.courseName.ifEmpty { "未知课程" }, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        if (item.teacher.isNotEmpty()) Text("教师: ${item.teacher}", color = TextSecondary, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 评分大卡片
                val scoreVal = item.score.toDoubleOrNull()
                val scoreColor = when { scoreVal == null -> Primary; scoreVal >= 85 -> Success; scoreVal >= 60 -> Warning; else -> Error }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SuccessBg).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("综合评价得分", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(item.score.ifEmpty { "-" }, color = scoreColor, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        if (item.submitStatus.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp)).background(SuccessBg).padding(horizontal = 8.dp, vertical = 2.dp)
                            ) { Text(item.submitStatus, color = Success, fontSize = 11.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))

                // 详细信息
                val info = mutableListOf<Pair<String,String>>()
                if (item.college.isNotEmpty()) info.add("开课学院" to item.college)
                if (item.courseType.isNotEmpty()) info.add("课程性质" to item.courseType)
                if (item.classTime.isNotEmpty()) info.add("上课时间" to item.classTime)
                if (item.classroom.isNotEmpty()) info.add("上课地点" to item.classroom)
                if (item.schoolYear.isNotEmpty()) info.add("学年学期" to item.schoolYear)
                info.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(k, color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(v, color = TextPrimary, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("注: 详细指标分解需在评教期间查看", color = TextHint, fontSize = 11.sp)
        }
        PlainDialogButtons(listOf(Triple("关闭", false) { onDismiss() }))
    }
}
