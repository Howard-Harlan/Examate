package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.campus.ui.TodoScreen
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 待办管理独立页面(入口在课程表日视图待办区)。
 *
 * 与课程表共用 ScheduleStorage 同一数据源,增删改天然双向同步:
 * - 此页修改 → 课程表日视图自动反映
 * - 课程表日视图/AI 工具修改 → 此页自动反映(返回时重新加载)
 */
class TodoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    TodoScreen(onBack = { finish() })
                }
            }
        }
    }
}
