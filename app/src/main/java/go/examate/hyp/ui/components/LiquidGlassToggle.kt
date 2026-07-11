package go.examate.hyp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import go.examate.hyp.ui.components.glass.DampedDragAnimation
import kotlinx.coroutines.flow.collectLatest

/**
 * 液态玻璃开关(移植自 backdrop 库 demo 的 LiquidToggle)
 *
 * 关键修复:dampedDragAnimation.modifier 应用到整个开关区域(64x28),
 * 点击轨道任意位置都能触发 onDragStart→onDragStopped 切换,不再只限于滑块。
 *
 * 特性:
 * - track 用独立 trackBackdrop + layerBackdrop 捕获轨道
 * - 滑块用 drawBackdrop(rememberCombinedBackdrop(父backdrop, trackBackdrop)) 真玻璃
 * - DampedDragAnimation: 拖拽阻尼 + 速度追踪 + 按下/释放缩放
 * - effects: blur(8dp*(1-progress)) + lens(5*progress, 10*progress, chromaticAberration)
 * - Highlight.Ambient + Shadow + InnerShadow + layerBlock 速度形变
 * - onDrawSurface: White(1-progress) 按下时变透明
 *
 * 通过 LocalGlassBackdrop 获取 backdrop(由 LiquidGlassContainer 提供)
 */
@Composable
fun LiquidGlassToggle(
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = LocalGlassBackdrop.current
        ?: error("LiquidGlassToggle 必须在 LiquidGlassContainer 内使用")
    val accentColor = Color(0xFF34C759)
    val trackColor = Color(0xFF787878).copy(0.2f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected) 1f else 0f) }

    // 当前是否处于"开"状态:直接读 fraction(delegate,每次取最新值)
    // 注意:不能用 val 缓存,否则 remember 闭包捕获的是首次值

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    // 有拖动:根据当前位置决定开关
                    val target = if (targetValue >= 0.5f) 1f else 0f
                    fraction = target
                    onSelect(target == 1f)
                    didDrag = false
                } else {
                    // 纯点击:切换(直接读 fraction 当前值判断方向)
                    val target = if (fraction >= 0.5f) 0f else 1f
                    fraction = target
                    onSelect(target == 1f)
                }
            },
            onDrag = { size, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction = if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                           else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { f ->
            dampedDragAnimation.updateValue(f)
        }
    }
    LaunchedEffect(selected) {
        snapshotFlow { selected }.collectLatest { isSelected ->
            val target = if (isSelected) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    val trackBackdrop = rememberLayerBackdrop()

    // ═══ 外层 Box:整个开关区域(64x28)都接收手势 ═══
    // dampedDragAnimation.modifier 应用到这里,点击轨道任意位置都能切换
    Box(
        modifier
            .size(64.dp, 28.dp)
            .semantics { role = Role.Switch }
            .then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        // ═══ 轨道:trackBackdrop 捕获,颜色渐变 ═══
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val f = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64.dp, 28.dp)
        )

        // ═══ 滑块:drawBackdrop(combinedBackdrop) 真玻璃 ═══
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX = if (isLtr) lerp(padding, padding + dragWidth, f)
                                   else lerp(-padding, -(padding + dragWidth), f)
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val sx = lerp(2f / 3f, 0.75f, progress)
                            val sy = lerp(0f, 0.75f, progress)
                            scale(sx, sy) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40.dp, 24.dp)
        )
    }
}
