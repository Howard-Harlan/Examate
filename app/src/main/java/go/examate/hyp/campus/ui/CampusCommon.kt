package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.window.Dialog
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/** 学年选项(近 5 年) */
val YEAR_OPTIONS: List<String> = run {
    val now = java.time.LocalDate.now()
    val startYear = if (now.monthValue >= 9) now.year else now.year - 1
    (0 until 5).map { i -> "${startYear - i}-${startYear - i + 1}" }
}

/** 学期选项 */
val SEMESTER_OPTIONS = listOf("1" to "第一学期", "2" to "第二学期")

/** 一卡通记录类型 */
val RECORD_TYPE_OPTIONS = listOf(
    "" to "全部",
    "1" to "消费",
    "2" to "充值",
    "3" to "补助",
    "4" to "互转"
)

// ════════════════════════════════════════
//  通用页面骨架
// ════════════════════════════════════════

/**
 * 校园服务子页面通用骨架:顶栏(返回 + 标题)+ 内容区 + 可选底部栏。
 *
 * 内容区占满剩余空间([weight] 1f),底部栏(如 Tab)固定在页面底部。
 */
@Composable
fun CampusPageScaffold(
    title: String,
    onBack: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏:液态玻璃悬浮返回按钮
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterStart)
            )
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // 内容区(占满剩余空间)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp)
        ) {
            content()
        }
        // 底部栏
        bottomBar?.invoke()
    }
}

// ════════════════════════════════════════
//  通用 Tab 栏(液态玻璃风格)
// ════════════════════════════════════════

@Composable
fun GlassTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedGlassCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp)) {
            tabs.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (active) Primary else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) Color.White else TextHint,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  区块标题
// ════════════════════════════════════════

@Composable
fun SectionTitle(text: String, subtitle: String = "") {
    Column(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
        Text(
            text,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ════════════════════════════════════════
//  查询参数行:标签 + 可点击的值(弹出选择)
// ════════════════════════════════════════

@Composable
fun QueryParamRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("›", color = TextHint, fontSize = 16.sp)
    }
}

// ════════════════════════════════════════
//  通用选择弹窗(单选)
// ════════════════════════════════════════

@Composable
fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit
) {
    var cur by remember { mutableStateOf(selected) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardBg)
                .padding(vertical = 8.dp)
        ) {
            Text(
                title,
                color = TextHint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            // 选项区:限高 + 可滚动,避免选项过多把取消/确认按钮挤出屏幕
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { cur = value }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (value == cur) {
                            Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(TextHint.copy(alpha = 0.3f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确认", onClick = { onConfirm(cur); onDismiss() },
                    style = ButtonStyle.White, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  加载/错误/空状态
// ════════════════════════════════════════

@Composable
fun LoadingBlock(text: String = "正在查询...") {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(text, color = TextHint, fontSize = 12.sp)
        }
    }
}

@Composable
fun ErrorBlock(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorBg)
            .padding(14.dp)
    ) {
        Text(message, color = Error, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
fun EmptyBlock(text: String = "暂无数据") {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextHint, fontSize = 13.sp)
    }
}

// ════════════════════════════════════════
//  信息卡片(键值对展示)
// ════════════════════════════════════════

/** 信息卡片:一组键值对,纵向排列 */
@Composable
fun InfoCard(
    title: String? = null,
    items: List<Pair<String, String>>
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, color = TextHint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
            }
            items.forEachIndexed { idx, (k, v) ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(k, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    Text(v, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 列表项卡片(标题 + 副信息) */
@Composable
fun ListItemCard(
    title: String,
    subtitles: List<String>,
    trailing: String? = null,
    trailingColor: Color = TextPrimary
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Text(trailing, color = trailingColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            subtitles.forEach { s ->
                if (s.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(s, color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 分隔线(卡片间) */
@Composable
fun CardSpacer() {
    Spacer(Modifier.height(10.dp))
}
