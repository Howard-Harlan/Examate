package go.examate.hyp.customview

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import go.examate.hyp.service.MyAccessibilityService
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RectangleDrawableConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val redPaint: Paint by lazy {
        Paint().apply {
            color = Color.GRAY  // 扫描内容标记由红色改为灰色
            style = Paint.Style.FILL
            alpha = 63
        }
    }
    private val yellowPaint: Paint by lazy {
        Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            alpha = 31
        }
    }
    private val bluePaint: Paint by lazy {
        Paint().apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 1f
            alpha = 63
        }
    }
    private val whitePaint: Paint by lazy {
        Paint().apply {
            color = Color.WHITE
            strokeWidth = 30f  // 线宽
        }
    }
    private val blueLinePaint: Paint by lazy {
        Paint().apply {
            color = Color.BLUE
            strokeWidth = 30f  // 线宽
        }
    }

    private val rectanglesToDraw: ArrayList<Rect> = ArrayList()
    private val rectanglesToHint: ArrayList<Rect> = ArrayList()

    // 缩放手势
    private val screenHeight: Int
    private val screenWidth: Int
    private var initialWidth: Float = 0f
    private var initialHeight: Float = 0f
    private var initialDistanceX: Float = 0f
    private var initialDistanceY: Float = 0f
    private var isResizing: Boolean = false
    var isResizingDown: Boolean = false  // 双指缩放完但两根手指未都抬起
    var blueLine: Boolean = false

    init {
        // 获取屏幕尺寸
        val displayMetrics: DisplayMetrics = Resources.getSystem().displayMetrics
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 属于
        for (r in rectanglesToDraw) {
            canvas.drawRect(r, redPaint)
            canvas.drawRect(r, bluePaint)  // 边界
        }
        // 交集
        for (r in rectanglesToHint) {
            canvas.drawRect(r, yellowPaint)
            canvas.drawRect(r, bluePaint)  // 边界
        }
        // 四角指示图标
        val lineLength = 100
        val linePaint = if (blueLine) blueLinePaint else whitePaint
        canvas.drawLine(0f, 0f, lineLength.toFloat(), 0f, linePaint)  // 左上
        canvas.drawLine(0f, 0f, 0f, lineLength.toFloat(), linePaint)
        canvas.drawLine(0f, height.toFloat(), lineLength.toFloat(), height.toFloat(), linePaint)  // 左下
        canvas.drawLine(0f, height.toFloat(), 0f, (height - lineLength).toFloat(), linePaint)
        canvas.drawLine((width - lineLength).toFloat(), 0f, width.toFloat(), 0f, linePaint)  // 右上
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), lineLength.toFloat(), linePaint)
        canvas.drawLine((width - lineLength).toFloat(), height.toFloat(), width.toFloat(), height.toFloat(), linePaint)  // 右下
        canvas.drawLine(width.toFloat(), (height - lineLength).toFloat(), width.toFloat(), height.toFloat(), linePaint)
    }

    fun UpdateRectangleDraw(): StringBuilder {
        // 遍历选区内的节点
        val point = IntArray(2)
        this.getLocationOnScreen(point)
        val zoneLeft = point[0]
        val zoneTop = point[1]
        val zoneRight = zoneLeft + width
        val zoneBottom = zoneTop + height
        val rectZone = Rect(zoneLeft, zoneTop, zoneRight, zoneBottom)
        val sb = StringBuilder()
        MyAccessibilityService.getRectanglesZoneContains(rectZone, rectanglesToDraw, rectanglesToHint, sb)

        // 坐标转换
        ScreenToViewCoordinates(this, rectanglesToDraw)
        ScreenToViewCoordinates(this, rectanglesToHint)

        // 重绘
        invalidate()

        return sb
    }

    fun ClearRectangleDraw() {
        rectanglesToDraw.clear()
        rectanglesToHint.clear()
        invalidate()
    }

    // 屏幕坐标转换为控件坐标
    private fun ScreenToViewCoordinates(targetView: ViewGroup, rectList: List<Rect>) {
        val location = IntArray(2)
        targetView.getLocationOnScreen(location)
        for (r in rectList) {
            // targetView.offsetDescendantRectToMyCoords(targetView, r);
            r.left -= location[0]
            r.top -= location[1]
            r.right -= location[0]
            r.bottom -= location[1]
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount == 2) {  // 检测双指触摸
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 第二根手指按下时开始缩放
                    initialWidth = width.toFloat()
                    initialHeight = height.toFloat()
                    initialDistanceX = getDistance(event, 0, 1).x
                    initialDistanceY = getDistance(event, 0, 1).y
                    isResizing = true
                    blueLine = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        // 根据手指移动调整宽高
                        val newDistanceX = getDistance(event, 0, 1).x
                        val newDistanceY = getDistance(event, 0, 1).y

                        // 计算新尺寸
                        val newWidth = (initialWidth + (newDistanceX - initialDistanceX)).toInt()
                        val newHeight = (initialHeight + (newDistanceY - initialDistanceY)).toInt()

                        // 应用新尺寸
                        val params = layoutParams
                        params.width = min(max(newWidth, 300), screenWidth)
                        params.height = min(max(newHeight, 150), screenHeight)
                        layoutParams = params

                        // 可选：invalidate 以新尺寸重绘
                        // invalidate();
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 其中一根手指抬起时停止缩放
                    isResizing = false
                    isResizingDown = true
                    blueLine = false
                    invalidate()
                }
            }
        }
        return true  // 消费触摸事件
    }

    private fun getDistance(event: MotionEvent, pointerIndex1: Int, pointerIndex2: Int): PointF {
        val x = abs(event.getX(pointerIndex1) - event.getX(pointerIndex2))
        val y = abs(event.getY(pointerIndex1) - event.getY(pointerIndex2))
        return PointF(x, y)
    }
}
