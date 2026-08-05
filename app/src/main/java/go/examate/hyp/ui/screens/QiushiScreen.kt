package go.examate.hyp.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import go.examate.hyp.ui.components.IconNote
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 探索页
 * - 局域网聊天:发现同局域网设备,实时文字通信
 * - 校园服务:查询课表、成绩、校园卡等(需先绑定学校)
 * - 下载器:粘贴直链下载文件,支持断点续传
 */
@Composable
fun QiushiScreen(
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
    onWaterCardClick: () -> Unit = {},
    onScheduleClick: () -> Unit = {},
    onDownloaderClick: () -> Unit = {},
    onReadingClick: () -> Unit = {},
    onMusicClick: () -> Unit = {},
    onNotesClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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

        // ═══ 功能入口:两列方形网格(顺序:校园服务/课程表/笔记/阅读/音乐/水卡/局域网聊天/下载) ═══
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureCard(
                title = "校园服务",
                subtitle = "",
                iconVector = IconSchool,
                onClick = onCampusServiceClick
            )
            FeatureCard(
                title = "课程表",
                subtitle = "",
                iconVector = IconCalendar,
                onClick = onScheduleClick
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureCard(
                title = "笔记",
                subtitle = "",
                iconVector = IconNote,
                onClick = onNotesClick
            )
            FeatureCard(
                title = "阅读",
                subtitle = "",
                iconVector = IconBook,
                onClick = onReadingClick
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureCard(
                title = "音乐",
                subtitle = "",
                iconVector = IconMusic,
                onClick = onMusicClick
            )
            FeatureCard(
                title = "乐校通水卡",
                subtitle = "",
                iconRes = R.drawable.ic_water_card,
                onClick = onWaterCardClick
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureCard(
                title = "局域网聊天",
                subtitle = "",
                iconRes = R.drawable.ic_lan_chat,
                onClick = onLanChatClick
            )
            FeatureCard(
                title = "下载",
                subtitle = "",
                iconVector = IconDownload,
                onClick = onDownloaderClick
            )
        }
    }
}

/** 方形功能卡片:图标居上、标题居下,占父级 Row 等分宽度并保持 1:1 比例 */
@Composable
private fun RowScope.FeatureCard(
    title: String,
    subtitle: String,
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
