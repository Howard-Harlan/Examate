package go.examate.hyp.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 全屏选区遮罩 View
 *
 * - 全屏半透明黑色遮罩
 * - 手指拖动画框,框内透明(可看到底层内容)
 * - 框选完成后通过 [onSelectionComplete] 回调
 * - 重新框选时调用 [resetSelection]
 */
class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maskColor = Color.argb(120, 0, 0, 0)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B8DEF")
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = maskColor }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B8DEF")
        strokeWidth = 4f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val selectionRect = RectF()
    private val touchStart = FloatArray(2)
    private var isSelecting = false
    private var hasSelection = false

    /** 框选完成回调 */
    var onSelectionComplete: ((Rect) -> Unit)? = null

    /** 触摸事件不消费(让底层窗口接收),只有在选区内才消费 */
    var touchThrough = false

    override fun onDraw(canvas: Canvas) {
        // 1. 绘制全屏半透明遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 2. 如果有选区,清除选区内遮罩(透明)
        if (hasSelection && !selectionRect.isEmpty) {
            canvas.drawRect(selectionRect, clearPaint)
            // 3. 绘制选区边框
            canvas.drawRect(selectionRect, borderPaint)
            // 4. 绘制四角标记
            drawCornerMarks(canvas)
        }

        // 5. 如果正在拖动,绘制临时选区
        if (isSelecting && !selectionRect.isEmpty) {
            canvas.drawRect(selectionRect, clearPaint)
            canvas.drawRect(selectionRect, borderPaint)
        }
    }

    private fun drawCornerMarks(canvas: Canvas) {
        val len = 16f * resources.displayMetrics.density
        val r = selectionRect
        // 左上
        canvas.drawLine(r.left, r.top, r.left + len, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + len, cornerPaint)
        // 右上
        canvas.drawLine(r.right, r.top, r.right - len, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, cornerPaint)
        // 左下
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - len, cornerPaint)
        // 右下
        canvas.drawLine(r.right, r.bottom, r.right - len, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStart[0] = event.x
                touchStart[1] = event.y
                isSelecting = true
                hasSelection = false
                selectionRect.set(event.x, event.y, event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSelecting) {
                    val left = minOf(touchStart[0], event.x)
                    val top = minOf(touchStart[1], event.y)
                    val right = maxOf(touchStart[0], event.x)
                    val bottom = maxOf(touchStart[1], event.y)
                    selectionRect.set(left, top, right, bottom)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isSelecting) {
                    isSelecting = false
                    // 选区太小则忽略
                    if (selectionRect.width() < 30f || selectionRect.height() < 30f) {
                        hasSelection = false
                        selectionRect.setEmpty()
                    } else {
                        hasSelection = true
                    }
                    invalidate()
                    if (hasSelection) {
                        onSelectionComplete?.invoke(
                            Rect(
                                selectionRect.left.toInt(),
                                selectionRect.top.toInt(),
                                selectionRect.right.toInt(),
                                selectionRect.bottom.toInt()
                            )
                        )
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 重置选区,允许重新画框 */
    fun resetSelection() {
        hasSelection = false
        isSelecting = false
        selectionRect.setEmpty()
        invalidate()
    }

    /** 获取当前选区(屏幕坐标) */
    fun getSelectionRect(): Rect? {
        if (!hasSelection || selectionRect.isEmpty) return null
        return Rect(
            selectionRect.left.toInt(),
            selectionRect.top.toInt(),
            selectionRect.right.toInt(),
            selectionRect.bottom.toInt()
        )
    }
}
