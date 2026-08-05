package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.SzsScoreItem
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智慧思政页面 — 综合测评成绩明细。
 *
 * 从 jgf.chnu.edu.cn/hbsf-zhsz-plat/ChangeScore/List 获取成绩数据,原生列表展示。
 * 数据包含:德育/专业/体育成绩,劳动教育,体质等多项指标。
 */
@Composable
fun SzsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<SzsScoreItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { repo.getChangeScore(prefs) }
            if (items.isEmpty()) {
                error = "暂无综合测评成绩数据"
            }
        } catch (e: CampusException) {
            error = e.message ?: "加载失败"
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
        }
        loading = false
    }

    CampusPageScaffold(title = "智慧思政", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (loading) {
                LoadingBlock("正在查询综合测评成绩...")
            } else if (error.isNotEmpty()) {
                ErrorBlock(error)
            } else {
                items.forEach { item -> ScoreDetailCard(item); Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 综合测评成绩卡片:学生信息 + 各项成绩 */
@Composable
private fun ScoreDetailCard(item: SzsScoreItem) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // 测评批次
            Text(item.testBatchName.ifEmpty { "综合测评成绩" },
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            // 学生信息
            Text("${item.studentName} · ${item.className}",
                color = TextHint, fontSize = 12.sp)
            if (item.college.isNotEmpty() || item.major.isNotEmpty()) {
                Text("${item.college} · ${item.major}", color = TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))

            // 成绩项(3列网格布局)
            val scores = listOf(
                "德育" to item.dyScore,
                "专业" to item.zyScore,
                "体育" to item.tyScore,
                "劳动教育" to item.nlfzEdu,
                "体质" to item.tzEdu,
                "应购书本" to item.ygpbEdu,
                "党员科技" to item.dykjScore,
                "党员申报" to item.dyDeclareScore
            ).filter { it.second.isNotEmpty() && it.second != "0" && it.second != "0.0" && it.second != "0.00" }

            scores.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (label, value) ->
                        ScoreCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    // 补齐3列
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 单个成绩单元格 */
@Composable
private fun ScoreCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = AccentBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}
