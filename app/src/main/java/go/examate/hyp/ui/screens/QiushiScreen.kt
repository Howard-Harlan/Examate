package go.examate.hyp.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.R
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.IconCalendar
import go.examate.hyp.ui.components.IconBook
import go.examate.hyp.ui.components.IconMusic
import go.examate.hyp.ui.components.IconDownload
import go.examate.hyp.ui.components.IconSchool
import go.examate.hyp.ui.components.IconRun
import go.examate.hyp.ui.components.IconNote
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 探索页 — 分类布局,分区可折叠。
 *
 * - 校内服务:校园服务 / 课程表 / 乐校通水卡 / 速记
 * - 探索边界:局域网聊天 / 阅读 / 音乐 / 下载
 */
@Composable
fun QiushiScreen(
    onMesChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit = {},
    onLePaoClick: () -> Unit = {},
    onScheduleClick: () -> Unit = {},
    onDownloaderClick: () -> Unit = {},
    onReadingClick: () -> Unit = {},
    onMusicClick: () -> Unit = {},
    onMemoClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var campusExpanded by remember { mutableStateOf(true) }
    var exploreExpanded by remember { mutableStateOf(true) }

    Column(
        modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
            .padding(bottom = 120.dp)
    ) {
        Text(
            text = "Explore",
            color = TextPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.8).sp
        )
        Spacer(Modifier.height(20.dp))

        // ═══ 校内服务 ═══
        SectionHeader("校内服务", expanded = campusExpanded, onToggle = { campusExpanded = !campusExpanded })
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().animateContentSize(
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            )
        ) {
            if (campusExpanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureCard(title = "校园服务", iconVector = IconSchool, onClick = onCampusServiceClick)
                    FeatureCard(title = "课程表", iconVector = IconCalendar, onClick = onScheduleClick)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureCard(title = "乐校通水卡", iconRes = R.drawable.ic_water_card, onClick = onWaterCardClick)
                    FeatureCard(title = "速记", iconVector = IconNote, onClick = onMemoClick)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureCard(title = "步道乐跑", iconVector = IconRun, onClick = onLePaoClick)
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(26.dp))

        // ═══ 探索边界 ═══
        SectionHeader("探索边界", expanded = exploreExpanded, onToggle = { exploreExpanded = !exploreExpanded })
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().animateContentSize(
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            )
        ) {
            if (exploreExpanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureCard(title = "局域网聊天", iconRes = R.drawable.ic_mes_chat, onClick = onMesChatClick)
                    FeatureCard(title = "阅读", iconVector = IconBook, onClick = onReadingClick)
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FeatureCard(title = "音乐", iconVector = IconMusic, onClick = onMusicClick)
                    FeatureCard(title = "下载", iconVector = IconDownload, onClick = onDownloaderClick)
                }
            }
        }
    }
}

/** 分类小标题:整行可点折叠/展开,箭头随状态旋转 */
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(Primary.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.size(8.dp))
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            "›",
            color = TextHint,
            fontSize = 14.sp,
            modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 90f else 0f }
        )
    }
}

/** 方形功能卡片:图标居上、标题居下,占父级 Row 等分宽度并保持 1:1 比例 */
@Composable
private fun RowScope.FeatureCard(
    title: String,
    subtitle: String = "",
    iconRes: Int = 0,
    iconVector: ImageVector? = null,
    onClick: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(Primary.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = title,
                        tint = Primary,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = title,
                        tint = Primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

