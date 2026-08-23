package go.examate.hyp.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme

/**
 * 速记独立页(重构版)。
 *
 * 从笔记页拆出,成为探索页"探索边界"分类下的独立入口。
 * 底层存储 [MemoStorage] 支持心情/自选颜色/置顶/编辑。
 */
class MemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    MemoScreen(onBack = { finish() })
                }
            }
        }
    }
}
