package go.examate.hyp.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.util.AvatarHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun OnboardingScreen(
    onSetupApi: () -> Unit,
    onSkip: () -> Unit
) {
    // Examate 文字从左到右逐渐出现进度(0→1)
    val revealProgress = remember { Animatable(0f) }
    // 内容渐入进度
    val contentAlpha = remember { Animatable(0f) }
    // 礼花粒子是否激活
    var fireworksActive by remember { mutableStateOf(false) }

    // 进入时判断一次:若用户已设置过昵称/生日/头像任一项,欢迎页不再展示资料卡(避免输入中闪动)
    val context = LocalContext.current
    val showProfileCard = remember {
        val prefs = PrefsManager.getInstance(context)
        prefs.nickname.isEmpty() && prefs.birthday.isEmpty() && !AvatarHelper.hasAvatar(context)
    }

    LaunchedEffect(Unit) {
        delay(150)
        // 下方内容并行提前渐入:文字进行到约一半时即开始,不再等 reveal 完全结束(更快呈现)
        launch {
            delay(500)
            contentAlpha.animateTo(1f, tween(durationMillis = 500))
        }
        // 文字从左到右逐渐出现(1200ms, FastOutSlowInEasing 曲线更顺滑)
        revealProgress.animateTo(1f, tween(durationMillis = 1200, easing = FastOutSlowInEasing))
        // 文字完全出现后触发礼花
        fireworksActive = true
        // 礼花持续 1.8s 后关闭
        delay(1800)
        fireworksActive = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgPrimary, CardBg)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 28.dp)
                .padding(top = 88.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ═══ Examate 文字(从左到右逐渐出现) ═══
            RevealText(
                text = "Examate",
                progress = revealProgress.value,
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                letterSpacing = (-0.5).sp
            )

            Spacer(Modifier.height(24.dp))

            // 说明卡片(圆角:移除 .background 避免方形背景覆盖圆角;改用 alpha 渐入)
            FrostedGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha.value },
                cornerRadius = 24.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "开始之前",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "解题、搜题等核心功能依赖大模型 API,使用前请配置至少一个厂商的 API Key。密钥仅存本机、不上传;也可暂时跳过,稍后在「设置」中配置。",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 19.sp
                    )
                    Text(
                        text = "免责声明:本软件仅用于测试与学习,禁止用于考试作弊,一切后果由使用者自行承担。",
                        fontSize = 12.sp,
                        color = Danger,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 个人资料(头像+昵称+生日):仅在尚未设置任何一项时展示,一进来就能设置(紧凑模式,随输入即存)
            if (showProfileCard) {
                Box(Modifier.graphicsLayer { alpha = contentAlpha.value }) {
                    go.examate.hyp.ui.components.ProfileEditCard(compact = true)
                }
                Spacer(Modifier.height(18.dp))
            }

            LiquidGlassButton(
                text = "去设置 API",
                onClick = onSetupApi,
                style = ButtonStyle.Primary,
                height = 50.dp
            )

            Spacer(Modifier.height(10.dp))

            LiquidGlassButton(
                text = "暂时跳过",
                onClick = onSkip,
                style = ButtonStyle.Outline,
                height = 44.dp
            )
        }

        // 礼花粒子层(覆盖整个屏幕)
        if (fireworksActive) {
            FireworksOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 从左到右逐渐显示的文字。
 *
 * 性能优化:固定宽高的 Box + drawWithContent + clipRect 在绘制阶段裁剪,
 * 避免每帧修改 Modifier.width 触发布局重算(原实现卡顿的主因)。
 */
@Composable
private fun RevealText(
    text: String,
    progress: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: Color,
    letterSpacing: androidx.compose.ui.unit.TextUnit
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        color = color
    )
    // 预测量文字尺寸(固定容器尺寸,避免每帧重布局)
    val layoutResult = remember(text, style) { textMeasurer.measure(text, style) }
    val textWidthPx = layoutResult.size.width.toFloat()
    val textHeightPx = layoutResult.size.height.toFloat()
    val textWidthDp = with(density) { textWidthPx.toDp() }
    val textHeightDp = with(density) { textHeightPx.toDp() }

    Box(
        modifier = Modifier
            .height(textHeightDp)
            .width(textWidthDp),
        contentAlignment = Alignment.CenterStart
    ) {
        // drawWithContent + clipRect 在绘制阶段裁剪,固定容器尺寸,不触发每帧重布局
        Text(
            text = text,
            style = style,
            modifier = Modifier
                .drawWithContent {
                    // 仅绘制左侧 progress 比例的宽度
                    val revealedWidth = size.width * progress
                    clipRect(right = revealedWidth) {
                        this@drawWithContent.drawContent()
                    }
                }
        )
    }
}

/**
 * 礼花粒子爆炸效果。
 * 从屏幕中心(或 Examate 文字位置附近)发射多个粒子,向四周扩散并逐渐消失。
 * 仅在 [OnboardingScreen] 中 fireworksActive 为 true 时被组合,故无需 active 参数。
 */
@Composable
private fun FireworksOverlay(modifier: Modifier = Modifier) {
    // 粒子按相对坐标存储(0~1),渲染时乘以实际画布尺寸
    val particles = remember { generateFireworkParticles() }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1600, easing = LinearEasing))
    }

    Canvas(modifier = modifier) {
        if (particles.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val p = progress.value
        particles.forEach { particle ->
            // 当前位置 = 起点(相对坐标×画布尺寸) + 方向 × 距离 × 进度
            val startX = particle.startX * w
            val startY = particle.startY * h
            val distance = particle.distance * min(w, h)
            val currentX = startX + particle.dx * distance * p
            // 加重力(向下加速)
            val currentY = startY + particle.dy * distance * p + 0.5f * 0.15f * h * p * p
            // alpha:前 70% 不透明,后 30% 逐渐消失
            val alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
            // 半径:radius 存的是 dp,乘 density 转 px(高密度屏不再偏小);再随进度略缩小
            val radius = particle.radius * density * (1f - p * 0.3f)
            drawCircle(
                color = particle.color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = radius,
                center = Offset(currentX, currentY)
            )
        }
    }
}

/** 单个礼花粒子(起点用相对坐标 0~1 存储) */
private data class FireworkParticle(
    val startX: Float,   // 爆炸中心 X(相对坐标 0~1)
    val startY: Float,   // 爆炸中心 Y(相对坐标 0~1)
    val dx: Float,       // 方向单位向量 X
    val dy: Float,       // 方向单位向量 Y
    val distance: Float, // 飞行距离(相对短边比例)
    val radius: Float,   // 粒子半径(dp,渲染时乘 density)
    val color: Color
)

/** 礼花配色 */
private val FIREWORK_COLORS = listOf(
    Color(0xFFFF6B6B), // 红
    Color(0xFFFFD93D), // 黄
    Color(0xFF6BCB77), // 绿
    Color(0xFF4D96FF), // 蓝
    Color(0xFFC780FA), // 紫
    Color(0xFFFF9F43), // 橙
    Color(0xFF48DBFB)  // 青
)

/**
 * 生成多波礼花粒子。
 * 在屏幕上半部分随机选 3 个爆炸点(相对坐标),每点发射约 28 个粒子向四周扩散。
 */
private fun generateFireworkParticles(): List<FireworkParticle> {
    val random = Random(System.currentTimeMillis())
    val particles = mutableListOf<FireworkParticle>()
    // 3 个爆炸点(相对坐标,集中在上半部分)
    val centers = listOf(
        Offset(0.5f, 0.22f), // 中上(Examate 文字附近)
        Offset(0.25f, 0.18f),
        Offset(0.75f, 0.26f)
    )
    centers.forEach { center ->
        val count = 28
        for (i in 0 until count) {
            val angle = (360f / count) * i + random.nextFloat() * 10f
            val rad = Math.toRadians(angle.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            val distance = 0.18f + random.nextFloat() * 0.18f
            val radius = 2f + random.nextFloat() * 2.5f   // dp,渲染时乘 density
            val color = FIREWORK_COLORS[random.nextInt(FIREWORK_COLORS.size)]
            particles.add(
                FireworkParticle(
                    startX = center.x,
                    startY = center.y,
                    dx = dx,
                    dy = dy,
                    distance = distance,
                    radius = radius,
                    color = color
                )
            )
        }
    }
    return particles
}
