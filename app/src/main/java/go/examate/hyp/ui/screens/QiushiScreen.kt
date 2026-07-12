package go.examate.hyp.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.R
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.IconSchool
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 探索页
 * - 局域网聊天:发现同局域网设备,实时文字通信
 * - 校园服务:查询课表、成绩、校园卡等(需先绑定学校)
 */
@Composable
fun QiushiScreen(
    onLanChatClick: () -> Unit,
    onCampusServiceClick: () -> Unit,
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

        // ═══ 局域网聊天 ═══
        FeatureCard(
            title = "局域网聊天",
            subtitle = "",
            iconRes = R.drawable.ic_lan_chat,
            onClick = onLanChatClick
        )

        Spacer(Modifier.height(14.dp))

        // ═══ 校园服务 ═══
        FeatureCard(
            title = "校园服务",
            subtitle = "",
            iconVector = IconSchool,
            onClick = onCampusServiceClick
        )
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    iconRes: Int = 0,
    iconVector: ImageVector? = null,
    onClick: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Primary.copy(0.1f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = title,
                        tint = Primary,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = title,
                        tint = Primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}
