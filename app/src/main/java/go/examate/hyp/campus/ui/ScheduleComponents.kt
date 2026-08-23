package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.campus.model.DEFAULT_TOTAL_WEEKS
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

// ════════════════════════════════════════
//  共享常量
// ════════════════════════════════════════

/** 星期标签(一/二/.../日),供周视图网格、添加课程页、弹窗等共用 */
internal val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

// ════════════════════════════════════════
//  公共组件
// ════════════════════════════════════════

@Composable
fun WeekQuickButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ScheduleCellBg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = TextSecondary, fontSize = 10.sp)
    }
}

/**
 * 周次多选公共组件(快捷操作 + 1-totalWeeks 周网格)
 * 供 AddCoursePage / AddCellDialog 共用
 * @param totalWeeks 学期总周数,默认 20
 */
@Composable
fun WeekMultiSelect(
    selectedWeeks: Set<Int>,
    onWeeksChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    totalWeeks: Int = DEFAULT_TOTAL_WEEKS
) {
    Column(modifier) {
        // 1-totalWeeks 周多选网格
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (1..totalWeeks).forEach { w ->
                val selected = w in selectedWeeks
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50))
                        .background(if (selected) SelectedBg else ScheduleCellBg)
                        .clickable {
                            onWeeksChange(
                                if (selected) selectedWeeks.toMutableSet().apply { remove(w) }
                                else selectedWeeks.toMutableSet().apply { add(w) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$w",
                        color = if (selected) Color.White else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 快捷操作(移到网格下方)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WeekQuickButton("全选") { onWeeksChange((1..totalWeeks).toMutableSet()) }
            WeekQuickButton("1-16") { onWeeksChange((1..16).toMutableSet()) }
            WeekQuickButton("单周") { onWeeksChange((1..totalWeeks).filter { it % 2 == 1 }.toMutableSet()) }
            WeekQuickButton("双周") { onWeeksChange((1..totalWeeks).filter { it % 2 == 0 }.toMutableSet()) }
            WeekQuickButton("清空") { onWeeksChange(mutableSetOf()) }
        }
    }
}

@Composable
fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = TextHint, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true, shape = RoundedCornerShape(10.dp)
    )
}

@Composable
fun NumberSelect(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    // 用固定宽度 Column 让标签和控件垂直对齐,避免横向并列时偏移
    // 点击中间数字弹出网格点选,大跨度切换(如 1→9)无需频繁点加减
    var showGrid by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(96.dp)) {
        Text(label, color = TextHint, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("‹", color = TextHint, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { if (value > range.first) onChange(value - 1) }.padding(2.dp))
            // 数字可点击:弹出网格点选
            Text("$value", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(20.dp).clickable { showGrid = true },
                textAlign = TextAlign.Center)
            Text("›", color = TextHint, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { if (value < range.last) onChange(value + 1) }.padding(2.dp))
        }
    }

    // 网格点选弹窗:4列布局,点一下选定,避免频繁点加减
    if (showGrid) {
        Dialog(onDismissRequest = { showGrid = false }) {
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp)
            ) {
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                // 4列网格
                val columns = 4
                val chunks = range.chunked(columns)
                chunks.forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { num ->
                            val selected = num == value
                            Box(
                                Modifier.weight(1f).size(36.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) SelectedBg else ScheduleCellBg)
                                    .clickable {
                                        onChange(num)
                                        showGrid = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$num", color = if (selected) Color.White else TextPrimary,
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        // 最后一行不足4个时补占位,保持等宽
                        repeat(columns - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  周次选择器(向上展开,显示前中后三周,可滑动)
// ════════════════════════════════════════

@Composable
internal fun WeekPickerDialog(
    currentWeek: Int,
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    // 显示以当前周为中心的 3 周,但可滑动浏览 1-totalWeeks 周
    val scrollState = rememberScrollState()
    // 初始滚动到当前周附近(每行 3 项,行高约 60dp,视口高 280dp,居中需减去 120dp)
    LaunchedEffect(Unit) {
        val rowIndex = (currentWeek - 1) / 3
        val targetOffset = (rowIndex * 60 - 120).coerceAtLeast(0)
        scrollState.scrollTo(targetOffset)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Text("选择周次", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("当前:第$currentWeek 周", color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))

            // 横向滚动的周次列表(每行 3 个)
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(scrollState)
            ) {
                (1..totalWeeks).chunked(3).forEach { weekChunk ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        weekChunk.forEach { w ->
                            val isCurrent = w == currentWeek
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrent) SelectedBg else InputBg)
                                    .clickable { onSelect(w) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "第$w 周",
                                    color = if (isCurrent) Color.White else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        // 不足 3 个时填充
                        if (weekChunk.size < 3) {
                            (0 until 3 - weekChunk.size).forEach { _ ->
                                Box(Modifier.weight(1f).height(52.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    }
}
