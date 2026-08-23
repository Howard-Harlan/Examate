package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextSecondary

class ScheduleSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    ScheduleSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

/**
 * 课表设置主壳:顶栏 + 三个子 Tab(课表管理 / 时间设置 / 提醒设置)
 *
 * 各 Tab 实现拆分至独立文件:
 * - [ScheduleManageTab] / [ScheduleEditPage] → ScheduleManageTab.kt
 * - [TimeSettingsTab] / [TimeTableEditPage] → ScheduleTimeTab.kt
 * - [ReminderSettingsTab] → ScheduleReminderTab.kt
 */
@Composable
private fun ScheduleSettingsScreen(onBack: () -> Unit) {
    // 三个子 Tab:课表管理 / 时间设置 / 提醒设置
    var subTab by remember { mutableStateOf(0) }
    // 课表编辑目标(进入课程编辑页面)
    var editingMetaId by remember { mutableStateOf<String?>(null) }
    // 时间表编辑目标(进入时间表编辑页面)
    var editingTimeTableId by remember { mutableStateOf<String?>(null) }

    // 时间表编辑页面(优先级高于课表编辑,二者不会同时进入)
    if (editingTimeTableId != null) {
        TimeTableEditPage(
            metaId = editingTimeTableId!!,
            onBack = { editingTimeTableId = null }
        )
        return
    }

    if (editingMetaId != null) {
        ScheduleEditPage(
            metaId = editingMetaId!!,
            onBack = { editingMetaId = null }
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏
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
            Text(
                text = "课表设置",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }

        // 子 Tab 切换栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("课表管理", "时间设置", "提醒设置").forEachIndexed { index, label ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (subTab == index) SelectedBg else ScheduleCellBg)
                        .clickable { subTab = index }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (subTab == index) Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        when (subTab) {
            0 -> ScheduleManageTab(onEditSchedule = { editingMetaId = it })
            1 -> TimeSettingsTab(onEditTimeTable = { editingTimeTableId = it })
            2 -> ReminderSettingsTab()
        }
    }
}
