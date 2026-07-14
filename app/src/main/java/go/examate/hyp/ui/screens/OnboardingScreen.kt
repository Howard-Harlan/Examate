package go.examate.hyp.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.IconSchool
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onSetupApi: () -> Unit,
    onSkip: () -> Unit
) {
    // 进入动画状态
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    val iconAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600),
        label = "iconAlpha"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.7f,
        animationSpec = tween(600),
        label = "iconScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 800, delayMillis = 200),
        label = "contentAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEFF1F5), Color(0xFFE5E8EE))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 80.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用图标（带渐入 + 缩放动画）
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .alpha(iconAlpha)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF374151), Color(0xFF2D3540))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = IconSchool,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            Column(
                modifier = Modifier.alpha(contentAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Examate",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "智能解题 · 悬浮搜题助手",
                    fontSize = 13.sp,
                    color = TextHint,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(Modifier.height(36.dp))

            // 说明卡片（毛玻璃，承载文字内容）
            FrostedGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                cornerRadius = 24.dp
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "开始之前",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "本应用的解题、搜题等核心功能依赖大模型 API 实现。使用前请配置至少一个厂商的 API Key。",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 20.sp
                    )
                    Text(
                        text = "· API Key 仅保存在本机，不会上传\n· 你也可以暂时跳过，稍后在「设置」中配置",
                        fontSize = 12.sp,
                        color = TextHint,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "免责声明",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Danger
                    )
                    Text(
                        text = "本软件仅用于测试与学习,禁止用于考试作弊。使用本软件产生的一切后果由使用者自行承担。",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            LiquidGlassButton(
                text = "去设置 API",
                onClick = onSetupApi,
                style = ButtonStyle.Primary,
                height = 52.dp
            )

            Spacer(Modifier.height(10.dp))

            LiquidGlassButton(
                text = "暂时跳过",
                onClick = onSkip,
                style = ButtonStyle.Outline,
                height = 46.dp
            )
        }
    }
}
