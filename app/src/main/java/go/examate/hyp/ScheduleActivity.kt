package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.campus.ui.ScheduleScreen
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 课程表独立页面(探索页入口)。
 *
 * 支持多种导入方式:Excel/CSV 文件、教务系统(已适配/网页识别)。
 * 数据本地持久化,一次导入后不随 App 重启丢失。
 */
class ScheduleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    ScheduleScreen(onBack = { finish() })
                }
            }
        }
    }
}
