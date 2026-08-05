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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import java.time.LocalDate

// ════════════════════════════════════════
//  日期选择器弹窗(7 列等宽,星期标题均匀分布)
// ════════════════════════════════════════

/**
 * 自定义日历弹窗 — 7 列等宽网格,星期标题均匀分布
 *
 * 项目里统一使用此组件选择日期(开学日期、账单日期等),
 * 避免 Material3 DatePicker 星期标题间距不均的问题。
 *
 * @param initialDate 初始选中日期,默认设备今天(节省调用方传参)
 * @param onConfirm 确认回调(用户点击"确定"时触发)
 * @param onDismiss 关闭回调(点击取消/外部时触发)
 */
@Composable
fun DatePickerDialog(
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    var selectedDate by remember { mutableStateOf(initialDate) }
    var viewMonth by remember { mutableStateOf(initialDate.withDayOfMonth(1)) }
    val today = LocalDate.now()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp)
        ) {
            // 月份导航
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { viewMonth = viewMonth.minusMonths(1) },
                    contentAlignment = Alignment.Center
                ) { Text("‹", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Text(
                    "${viewMonth.year}年${viewMonth.monthValue}月",
                    color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { viewMonth = viewMonth.plusMonths(1) },
                    contentAlignment = Alignment.Center
                ) { Text("›", color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(16.dp))

            // 星期标题 — 7 列等宽,均匀分布
            val weekDays = listOf("日", "一", "二", "三", "四", "五", "六")
            Row(Modifier.fillMaxWidth()) {
                weekDays.forEach { day ->
                    Text(
                        text = day,
                        color = TextHint,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 日期网格 — 7 列等宽
            val firstDay = viewMonth.withDayOfMonth(1)
            val daysInMonth = viewMonth.lengthOfMonth()
            val startOffset = firstDay.dayOfWeek.value % 7  // 周日=0, 周一=1...

            val totalCells = ((startOffset + daysInMonth + 6) / 7) * 7
            val rows = totalCells / 7

            Column(Modifier.fillMaxWidth()) {
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val index = row * 7 + col
                            val dayNum = index - startOffset + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = viewMonth.withDayOfMonth(dayNum)
                                val isSelected = date == selectedDate
                                val isToday = date == today
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Primary else Color.Transparent)
                                        .clickable { selectedDate = date },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$dayNum",
                                        color = when {
                                            isSelected -> Color.White
                                            isToday -> Primary
                                            else -> TextPrimary
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = if (isToday || isSelected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            } else {
                                Box(Modifier.weight(1f).height(40.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 底部按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确定", onClick = { onConfirm(selectedDate) },
                    style = ButtonStyle.White, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
