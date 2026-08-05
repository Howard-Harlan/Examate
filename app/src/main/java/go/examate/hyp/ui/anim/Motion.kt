package go.examate.hyp.ui.anim

import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * 全局动效 token:统一时长与曲线,各处引用以保证节奏一致。
 *
 * - FAST(150ms):按压、切换等小反馈
 * - NORMAL(250ms):入场、淡入淡出等常规过渡
 * - SLOW(400ms):较大范围的转场
 */
object Motion {
    const val FAST = 150
    const val NORMAL = 250
    const val SLOW = 400

    /** 标准缓动:快出慢入,自然减速 */
    val Easing = FastOutSlowInEasing
}
