package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.campus.model.PERIOD_TIMES
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.CardBg

// ════════════════════════════════════════
//  导入方式选择弹窗
// ════════════════════════════════════════

@Composable
internal fun ImportMethodDialog(
    onDismiss: () -> Unit,
    onFileImport: () -> Unit,
    onJwxtImport: () -> Unit,
    onWebImport: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(20.dp)
        ) {
            Text("导入课表", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            ImportOptionItem(title = "Excel / CSV 文件") { onFileImport() }
            Spacer(Modifier.height(8.dp))
            // 已有教务导入:统一显示"已有教务导入",由用户点击后进入流程
            ImportOptionItem(title = "已有教务导入") { onJwxtImport() }
            Spacer(Modifier.height(8.dp))
            ImportOptionItem(title = "教务网站导入(测试)") { onWebImport() }
            Spacer(Modifier.height(12.dp))
            Text("取消", color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp),
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ImportOptionItem(title: String, onClick: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(14.dp)
        )
    }
}

// ════════════════════════════════════════
//  弹窗:添加课程到空格子(代办已移到日视图)
// ════════════════════════════════════════

/**
 * 点击空格子弹窗:确认在该格子添加课程
 *
 * 代办系统已剥离到日视图,周视图空格子仅用于添加课程。
 */
@Composable
internal fun AddCellDialog(
    day: Int,
    section: Int,
    onAddCourse: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        // 卡片变窄:不用 fillMaxWidth,用固定宽度 220dp,视觉更聚焦
        Column(
            Modifier.width(220.dp).clip(RoundedCornerShape(20.dp))
                .background(CardBg).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 文案:"星期一 第1节",避免"第一第1节"这种重复表述
            // DAY_LABELS = "一/二/.../日",所以用"星期${DAY_LABELS[day-1]}"
            Text(
                "星期${DAY_LABELS[day - 1]} 第${section}节",
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            // 上课时间作为副信息
            PERIOD_TIMES.getOrNull(section - 1)?.first?.let { start ->
                Text(start, color = TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            // 直接添加课程(进入统一 AddCoursePage)
            LiquidGlassButton(
                text = "添加课程", onClick = onAddCourse,
                style = ButtonStyle.White, height = 42.dp, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("取消", color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(8.dp),
                textAlign = TextAlign.Center)
        }
    }
}
