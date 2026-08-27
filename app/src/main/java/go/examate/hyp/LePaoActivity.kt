package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.lepao.LePaoScreen
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 步道乐跑 Activity 入口
 */
class LePaoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    LePaoScreen(onBack = { finish() })
                }
            }
        }
    }
}
