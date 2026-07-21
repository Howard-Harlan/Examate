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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.Warning

/** 第二课堂功能 Tab */
private enum class ScTab(val label: String) {
    SUMMARY("成绩汇总"), DETAIL("成绩明细")
}

/** 第二课堂页面 */
@Composable
fun ScScreen(onBack: () -> Unit) {
    val viewModel: ScViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    var tab by remember { mutableStateOf(ScTab.SUMMARY) }

    CampusPageScaffold(
        title = "第二课堂",
        onBack = onBack,
        bottomBar = {
            Spacer(Modifier.height(10.dp))
            GlassTabBar(
                tabs = ScTab.values().map { it.label },
                selectedIndex = tab.ordinal,
                onSelect = { tab = ScTab.values()[it] }
            )
            Spacer(Modifier.height(16.dp))
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                ScTab.SUMMARY -> SummarySection(state.summary, viewModel)
                ScTab.DETAIL -> DetailSection(state.detail, viewModel)
            }
        }
    }
}

// ════════════════════════════════════════
//  成绩汇总
// ════════════════════════════════════════

@Composable
private fun SummarySection(
    state: SummaryState,
    viewModel: ScViewModel
) {
    LaunchedEffect(Unit) { viewModel.loadSummary() }

    if (state.loading) {
        LoadingBlock("正在获取成绩汇总...")
        return
    }
    if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { viewModel.retrySummary() },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }
    state.summary?.let { s ->
        // 总分大卡片
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF374151), Color(0xFF4B5563))))
                .padding(24.dp)
        ) {
            Column {
                Text("第二课堂总分", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.totalScore.ifEmpty { "0.00" },
                    color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // 各模块得分(任职相关模块排到最下方)
        if (s.modules.isEmpty()) {
            EmptyBlock("暂无模块成绩数据")
        } else {
            val sortedModules = s.modules.sortedBy { m ->
                if (m.moduleName.contains("任职")) 1 else 0
            }
            SectionTitle("各模块得分(共 ${sortedModules.size} 项)")
            sortedModules.forEach { m ->
                ModuleScoreCard(m.moduleName, m.score)
                CardSpacer()
            }
        }
        Spacer(Modifier.height(6.dp))
        LiquidGlassButton(
            text = "刷新", onClick = { viewModel.refreshSummary() },
            style = ButtonStyle.Outline, height = 40.dp
        )
    }
}

/** 模块得分卡片(带进度条样式) */
@Composable
private fun ModuleScoreCard(name: String, score: String) {
    val s = score.toDoubleOrNull() ?: 0.0
    val maxScore = 5.0 // 第二课堂每个项目满分 5 分(可以超过,进度条封顶)
    val ratio = (s / maxScore).coerceIn(0.0, 1.0).toFloat()
    val barColor = when {
        s >= 5 -> Success
        s > 0 -> Warning
        else -> Error
    }

    go.examate.hyp.ui.components.FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name.ifEmpty { "未知模块" }, color = go.examate.hyp.ui.theme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(score, color = barColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // 进度条
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE5E7EB))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  成绩明细
// ════════════════════════════════════════

@Composable
private fun DetailSection(
    state: DetailState,
    viewModel: ScViewModel
) {
    SectionTitle("成绩明细", "点击下方按钮查询各项目得分")
    LiquidGlassButton(
        text = if (state.loading) "查询中..." else "查询成绩明细",
        onClick = { viewModel.loadDetail() },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (state.loading) {
        LoadingBlock()
    } else if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
    } else if (state.queried) {
        if (state.details.isEmpty()) {
            EmptyBlock("暂无成绩明细记录")
        } else {
            // 按模块分组,并将"任职"相关模块排到最后
            val byModule = state.details.groupBy { it.moduleName }
                .toList()
                .sortedBy { (module, _) -> if (module.contains("任职")) 1 else 0 }
            SectionTitle("明细列表(共 ${state.details.size} 项)")
            byModule.forEach { (module, items) ->
                Text(
                    module.ifEmpty { "未知模块" },
                    color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                )
                items.forEach { d ->
                    go.examate.hyp.ui.components.FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                d.itemName.ifEmpty { "未知项目" },
                                color = go.examate.hyp.ui.theme.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                d.score,
                                color = go.examate.hyp.ui.theme.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    CardSpacer()
                }
            }
        }
    }
}
