package go.examate.hyp.lepao

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 步道乐跑轨迹绘制颜色常量 */
val TrackBlue = Color(64, 158, 255)
val TrackGreen = Color(76, 175, 80)
val TrackRed = Color(244, 67, 54)

/** 步道乐跑通用 UI 组件集合（从 LePaoMainPage.kt 拆出） */

@Composable
internal fun RouteCanvasMini(pts: List<Pair<Double, Double>>) {
    val trackBg = if (IsDark) Color(40, 42, 46) else Color(230, 234, 238)
    val trackOutline = if (IsDark) Color(60, 62, 66) else Color.White
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (pts.size < 2) { drawRect(trackBg); return@Canvas }
        val pad = 30f
        val lats = pts.map { it.first }; val lngs = pts.map { it.second }
        val latR = (lats.max() - lats.min()).coerceAtLeast(0.001)
        val lngR = (lngs.max() - lngs.min()).coerceAtLeast(0.001)
        fun x(lng: Double): Float = pad + ((lng - lngs.min()) / lngR * (size.width - 2 * pad)).toFloat()
        fun y(lat: Double): Float = size.height - pad - ((lat - lats.min()) / latR * (size.height - 2 * pad)).toFloat()
        drawRect(trackBg)
        val path = Path()
        path.moveTo(x(pts[0].second), y(pts[0].first))
        for (i in 1 until pts.size) path.lineTo(x(pts[i].second), y(pts[i].first))
        drawPath(path, trackOutline, style = Stroke(width = 8f))
        drawPath(path, TrackBlue, style = Stroke(width = 5f))
        drawCircle(TrackGreen, radius = 8f, center = Offset(x(pts[0].second), y(pts[0].first)))
        drawCircle(TrackRed, radius = 8f, center = Offset(x(pts.last().second), y(pts.last().first)))
    }
}

@Composable
internal fun CollapsibleCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▾" else "›", color = TextHint, fontSize = 16.sp)
            }
            if (expanded) { Spacer(Modifier.height(10.dp)); content() }
        }
    }
}

@Composable
internal fun FloatInput(label: String, value: Float, unit: String, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text("%.1f".format(value), fontSize = 13.sp, color = Primary, fontWeight = FontWeight.Medium)
            Text(" $unit", fontSize = 11.sp, color = TextHint)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.height(28.dp))
    }
}

@Composable
internal fun IntSelector(label: String, value: Int, options: List<Int>, suffix: String, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.width(8.dp))
        options.forEach { opt ->
            FilterChip(selected = value == opt, onClick = { onSelect(opt) },
                label = { Text("$opt$suffix", fontSize = 12.sp) },
                modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
internal fun IntInput(label: String, value: Int, unit: String, range: IntRange, onValueChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.Medium)
            Text(" $unit", fontSize = 11.sp, color = TextHint)
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.height(28.dp))
    }
}

@Composable
internal fun MetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(CardBg)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Primary)
            Row { Text(unit, fontSize = 11.sp, color = TextHint); Spacer(Modifier.width(4.dp)); Text(label, fontSize = 11.sp, color = TextSecondary) }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 数据预览可视化
// ════════════════════════════════════════════════════════════════

/** 速度曲线图 — Canvas 绘制 speed vs distance */
@Composable
internal fun SpeedCurveChart(pts: List<LePaoTrack.TrackPoint>, modifier: Modifier = Modifier) {
    if (pts.isEmpty()) return
    // 下采样：超过200点时取间隔采样，曲线更平滑
    val displayPts = if (pts.size > 200) {
        val step = pts.size / 200
        pts.filterIndexed { i, _ -> i % step == 0 } + pts.last()
    } else pts
    val maxSpeed = displayPts.maxOf { it.speed }.coerceAtLeast(1f)
    val avgSpeed = displayPts.map { it.speed }.average().toFloat()

    // 计算累计距离作为横坐标
    val cumDist = mutableListOf<Float>()
    var cum = 0f
    for (i in displayPts.indices) {
        if (i > 0) {
            val prev = displayPts[i - 1]; val curr = displayPts[i]
            val dLat = (curr.lat - prev.lat) * 111320.0
            val dLng = (curr.lng - prev.lng) * 111320.0 * cos(Math.toRadians(curr.lat))
            cum += sqrt(dLat * dLat + dLng * dLng).toFloat()
        }
        cumDist.add(cum)
    }
    val maxDist = cumDist.last().coerceAtLeast(1f)

    val chartBg = if (IsDark) Color(40, 42, 46) else Color(245, 247, 250)
    val gridColor = if (IsDark) Color(60, 62, 66) else Color(220, 225, 230)
    val labelColor = if (IsDark) android.graphics.Color.rgb(160, 170, 180) else android.graphics.Color.rgb(150, 160, 170)

    Canvas(modifier) {
        val padL = 28f; val padB = 18f; val padT = 8f; val padR = 8f
        val w = size.width - padL - padR
        val h = size.height - padT - padB

        // 背景网格
        drawRect(chartBg)
        for (i in 0..4) {
            val y = padT + h * i / 4f
            drawLine(gridColor, Offset(padL, y), Offset(size.width - padR, y), 1f)
        }

        // 平均线
        val avgY = padT + h * (1f - avgSpeed / maxSpeed)
        drawLine(Color(255, 152, 0, 120), Offset(padL, avgY), Offset(size.width - padR, avgY), 1.5f)

        // 速度曲线 + 填充（横坐标=累计距离）
        val path = Path()
        val fillPath = Path()
        displayPts.forEachIndexed { i, p ->
            val x = padL + (cumDist[i] / maxDist) * w
            val y = padT + (1f - p.speed / maxSpeed) * h
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, padT + h) }
            path.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(padL + w, padT + h)
        fillPath.close()
        drawPath(fillPath, TrackBlue.copy(alpha = 0.16f))
        drawPath(path, TrackBlue, style = Stroke(width = 2.5f))

        // 轴标签
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = labelColor; textSize = 18f; isAntiAlias = true
            }
            canvas.nativeCanvas.drawText("%.1f".format(maxSpeed), 2f, padT + 10f, paint)
            canvas.nativeCanvas.drawText("0", 2f, padT + h, paint)
            canvas.nativeCanvas.drawText("${(maxDist / 1000f).toInt()}km", padL + w - 35f, size.height - 2f, paint)
        }
    }
}

/** 传感器波形预览 — 显示 ax/ay/az 正弦波模式 */
@Composable
internal fun SensorPreviewChart(modifier: Modifier = Modifier) {
    val sensorBg = if (IsDark) Color(40, 42, 46) else Color(245, 247, 250)
    val sensorMid = if (IsDark) Color(60, 62, 66) else Color(220, 225, 230)
    Canvas(modifier) {
        val midY = size.height / 2f
        drawRect(sensorBg)
        drawLine(sensorMid, Offset(0f, midY), Offset(size.width, midY), 1f)

        val n = 60
        val axPath = Path(); val ayPath = Path(); val azPath = Path()
        for (i in 0 until n) {
            val x = i.toFloat() / n * size.width
            val ax = midY + (0.3f * sin(i * 0.31f) * 15f)
            val ay = midY + (0.4f * sin(i * 0.62f) * 15f)
            val az = midY + (0.2f * cos(i * 0.31f) * 15f)
            if (i == 0) { axPath.moveTo(x, ax); ayPath.moveTo(x, ay); azPath.moveTo(x, az) }
            else { axPath.lineTo(x, ax); ayPath.lineTo(x, ay); azPath.lineTo(x, az) }
        }
        drawPath(axPath, TrackRed, style = Stroke(width = 1.5f))
        drawPath(ayPath, TrackGreen, style = Stroke(width = 1.5f))
        drawPath(azPath, TrackBlue, style = Stroke(width = 1.5f))
    }
}

/** 分组折叠项 — 用于详细配置内部分组 */
@Composable
internal fun SubCollapsible(title: String, defaultExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "▾" else "›", color = TextHint, fontSize = 14.sp)
        }
        if (expanded) {
            content()
        }
    }
}
