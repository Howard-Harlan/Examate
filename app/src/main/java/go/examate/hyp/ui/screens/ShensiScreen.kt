package go.examate.hyp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.FestivalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 慎思页(首页)。
 *
 * 布局:
 * - Hero 区 "Examate":纯文字排版,右侧显示节假日状态
 * - 两张大卡片:Deep Learning / All in Agent(纯文字、无动效)
 * - 我的卡片区(可自定义显示源)
 */
@Composable
fun ShensiScreen(
    onDeepLearningClick: () -> Unit,
    onChatClick: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenNotes: (String?) -> Unit,
    onOpenSchedule: () -> Unit,
    onMemoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val festivals by produceState(initialValue = emptyList<FestivalManager.Festival>(), context) {
        value = withContext(Dispatchers.IO) { FestivalManager.activeFestivals(context) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
            .padding(bottom = 120.dp)
    ) {
        // ═══ Hero 区(纯文字,右侧节假日状态) ═══
        HeroCard(festivals)

        Spacer(Modifier.height(24.dp))

        // ═══ 大卡片:Deep Learning(纯文字,无动效) ═══
        BigFeatureCard(
            titleEn = "Deep Learning",
            titleZh = "深度学习",
            accent = AccentBlue,
            onClick = onDeepLearningClick
        )

        Spacer(Modifier.height(14.dp))

        // ═══ 大卡片:All in Agent(纯文字,无动效) ═══
        BigFeatureCard(
            titleEn = "All in Agent",
            titleZh = "智源奇点",
            accent = AccentPurple,
            onClick = onChatClick
        )

        Spacer(Modifier.height(26.dp))

        // ═══ 我的卡片(可自定义显示源) ═══
        HomeSourceSection(
            onOpenTodo = onOpenTodo,
            onOpenNotes = onOpenNotes,
            onOpenSchedule = onOpenSchedule,
            onOpenMemo = onMemoClick
        )
    }
}

/**
 * Hero 区 — 纯文字排版,无卡片、无背景色。
 * 左侧 "Examate" 标题,右侧显示节假日状态(多事件轮播),平衡首页视觉中心。
 */
@Composable
private fun HeroCard(festivals: List<FestivalManager.Festival>) {
    // 多事件轮播:单次最多显示两个,总事件循环切换
    var offset by remember { mutableIntStateOf(0) }
    LaunchedEffect(festivals.size) {
        offset = 0
        if (festivals.size > 2) {
            while (true) {
                delay(3000)
                offset = (offset + 1) % festivals.size
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Examate",
            color = TextPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.8).sp,
            modifier = Modifier.weight(1f)
        )
        // 节假日状态:右侧对齐,带剩余天数
        if (festivals.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End) {
                val shown = if (festivals.size <= 2) festivals else listOf(
                    festivals[offset % festivals.size],
                    festivals[(offset + 1) % festivals.size]
                )
                shown.forEach { f ->
                    val days = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.now(), f.end
                    ) + 1
                    val daysText = if (days <= 0) "今天" else "剩 ${days} 天"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.emoji, fontSize = 13.sp)
                        Spacer(Modifier.width(5.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                f.name,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                daysText,
                                color = TextHint,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * 首页大卡片 — 纯文字,无背景动效,高度与 Hero 相近(修长)。
 */
@Composable
private fun BigFeatureCard(
    titleEn: String,
    titleZh: String,
    accent: Color,
    onClick: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    titleEn,
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.4).sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    titleZh,
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text("›", color = TextHint, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
    }
}
