package go.examate.hyp.lepao

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.request.ImageRequest
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.*

/** 路线编辑器（地理坐标绑定，缩放/平移路线跟随） — 从 LePaoMainPage.kt 拆出 */

@Composable
internal fun FreehandRouteEditor(
    initialPoints: List<Pair<Double, Double>>,
    onSave: (List<Pair<Double, Double>>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDrawMode by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }


    val routeGeo = remember { mutableStateListOf<Pair<Double, Double>>() }

    BackHandler { onCancel() }

    LaunchedEffect(initialPoints) {
        if (routeGeo.isEmpty() && initialPoints.isNotEmpty()) {
            routeGeo.addAll(initialPoints)
        }
    }

    val initCenter = if (initialPoints.size >= 2) {
        val lats = initialPoints.map { it.first }; val lngs = initialPoints.map { it.second }
        Pair((lats.min() + lats.max()) / 2, (lngs.min() + lngs.max()) / 2)
    } else Pair(33.984926, 116.807700)
    val initSpan = if (initialPoints.size >= 2) {
        val lats = initialPoints.map { it.first }; val lngs = initialPoints.map { it.second }
        maxOf((lats.max() - lats.min()) * 1.5, 0.004) to maxOf((lngs.max() - lngs.min()) * 1.5, 0.004)
    } else 0.006 to 0.006

    var mapCenterLat by remember { mutableStateOf(initCenter.first) }
    var mapCenterLng by remember { mutableStateOf(initCenter.second) }
    var spanLat by remember { mutableStateOf(initSpan.first) }
    var spanLng by remember { mutableStateOf(initSpan.second) }

    val tileBitmaps = remember { mutableStateMapOf<Triple<Int, Int, Int>, Bitmap>() }
    val imageLoader = remember { ImageLoader(context) }

    fun curBounds(): Triple<Double, Double, Pair<Double, Double>> =
        Triple(mapCenterLat - spanLat / 2, mapCenterLat + spanLat / 2,
            (mapCenterLng - spanLng / 2) to (mapCenterLng + spanLng / 2))

    fun screenToGeo(x: Float, y: Float): Pair<Double, Double> {
        val b = curBounds()
        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
        val latMin = b.first; val latMax = b.second
        val lngMin = b.third.first; val lngMax = b.third.second
        val fx = (x / w).toDouble().coerceIn(0.0, 1.0)
        val fy = (y / h).toDouble().coerceIn(0.0, 1.0)
        return Pair(latMax - fy * (latMax - latMin), lngMin + fx * (lngMax - lngMin))
    }

    fun geoToScreen(lat: Double, lng: Double, width: Float, height: Float): Offset {
        val b = curBounds()
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val latMin = b.first; val latMax = b.second
        val lngMin = b.third.first; val lngMax = b.third.second
        val fx = ((lng - lngMin) / (lngMax - lngMin).coerceAtLeast(0.0000001)).toFloat()
        val fy = ((latMax - lat) / (latMax - latMin).coerceAtLeast(0.0000001)).toFloat()
        return Offset(fx * w, fy * h)
    }

    // 瓦片加载循环
    LaunchedEffect(canvasSize) {
        if (canvasSize == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        var lastZoom = -1
        while (true) {
            val b = curBounds()
            val bLatMin = b.first; val bLatMax = b.second; val bLngMin = b.third.first; val bLngMax = b.third.second
            val curZoom = ((ln(360.0 / spanLat * 2) / ln(2.0)).toInt()).coerceIn(5, 18)

            val minTx = floor(LePaoGeoUtils.lngToTileX(bLngMin, curZoom)).toInt() - 1
            val maxTx = floor(LePaoGeoUtils.lngToTileX(bLngMax, curZoom)).toInt() + 1
            val minTy = floor(LePaoGeoUtils.latToTileY(bLatMax, curZoom)).toInt() - 1
            val maxTy = floor(LePaoGeoUtils.latToTileY(bLatMin, curZoom)).toInt() + 1

            var needLoad = false
            for (tx in minTx..maxTx) for (ty in minTy..maxTy) {
                val key = Triple(curZoom, tx, ty)
                if (key !in tileBitmaps) {
                    needLoad = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sub = (tx + ty) % 4 + 1
                            val url = "https://webrd0${sub}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=8&x=$tx&y=$ty&z=$curZoom"
                            val drawable = imageLoader.execute(ImageRequest.Builder(context).data(url).build()).drawable
                            if (drawable is android.graphics.drawable.BitmapDrawable) tileBitmaps[key] = drawable.bitmap
                        } catch (_: Exception) {}
                    }
                }
            }
            if (lastZoom != curZoom && lastZoom != -1) {
                tileBitmaps.keys.removeAll { it.first != curZoom }
            }
            lastZoom = curZoom
            kotlinx.coroutines.delay(if (needLoad) 150L else 300L)
        }
    }

    Box(Modifier.fillMaxSize().background(BgPrimary)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(isDrawMode) {
                    if (isDrawMode) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val geo = screenToGeo(change.position.x, change.position.y)
                            routeGeo.add(geo)
                        }
                    } else {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                            val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                            mapCenterLng -= (pan.x / w).toDouble() * spanLng
                            mapCenterLat += (pan.y / h).toDouble() * spanLat
                            if (zoomChange != 1f) {
                                spanLat = (spanLat / zoomChange).coerceIn(0.0003, 5.0)
                                spanLng = (spanLng / zoomChange).coerceIn(0.0003, 5.0)
                            }
                        }
                    }
                }
        ) {
            val b = curBounds()
            val bLatMin = b.first; val bLatMax = b.second; val bLngMinVal = b.third.first; val bLngMaxVal = b.third.second
            val drawZoom = ((ln(360.0 / spanLat * 2) / ln(2.0)).toInt()).coerceIn(5, 18)

            for ((key, bitmap) in tileBitmaps) {
                if (key.first != drawZoom) continue
                val tx = key.second; val ty = key.third
                val tLngMin = LePaoGeoUtils.tileXToLng(tx.toDouble(), drawZoom); val tLngMax = LePaoGeoUtils.tileXToLng((tx + 1).toDouble(), drawZoom)
                val tLatMax = LePaoGeoUtils.tileYToLat(ty.toDouble(), drawZoom); val tLatMin = LePaoGeoUtils.tileYToLat((ty + 1).toDouble(), drawZoom)
                val left = ((tLngMin - bLngMinVal) / (bLngMaxVal - bLngMinVal) * size.width).toFloat()
                val right = ((tLngMax - bLngMinVal) / (bLngMaxVal - bLngMinVal) * size.width).toFloat()
                val top = ((bLatMax - tLatMax) / (bLatMax - bLatMin) * size.height).toFloat()
                val bottom = ((bLatMax - tLatMin) / (bLatMax - bLatMin) * size.height).toFloat()
                drawImage(bitmap.asImageBitmap(),
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((right - left).toInt().coerceAtLeast(1), (bottom - top).toInt().coerceAtLeast(1)))
            }

            if (routeGeo.size >= 2) {
                val screenPts = routeGeo.map { geoToScreen(it.first, it.second, size.width, size.height) }
                val path = Path()
                path.moveTo(screenPts[0].x, screenPts[0].y)
                for (i in 1 until screenPts.size) path.lineTo(screenPts[i].x, screenPts[i].y)
                drawPath(path, Color.White, style = Stroke(width = 10f))
                drawPath(path, TrackBlue, style = Stroke(width = 6f))

                val start = screenPts.first()
                drawCircle(TrackGreen, 12f, start)
                drawCircle(Color.White, 12f, start, style = Stroke(3f))
                val end = screenPts.last()
                drawCircle(TrackRed, 12f, end)
                drawCircle(Color.White, 12f, end, style = Stroke(3f))
            } else if (routeGeo.size == 1) {
                val p = geoToScreen(routeGeo[0].first, routeGeo[0].second, size.width, size.height)
                drawCircle(TrackBlue, 8f, p)
            }
        }

        // 顶栏：悬浮在地图之上
        Surface(
            Modifier.align(Alignment.TopCenter),
            shadowElevation = 4.dp, color = BgPrimary
        ) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onCancel, Modifier.size(40.dp))
                Text(if (isDrawMode) "绘制路线（手指拖动画线）" else "拖动地图查看",
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }

        // 底部操作栏：悬浮在地图之上
        Surface(
            Modifier.align(Alignment.BottomCenter),
            shadowElevation = 8.dp, color = BgPrimary
        ) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${routeGeo.size}点 · %.0fm".format(
                    LePaoGeoUtils.calcGeoDistance(routeGeo)
                ), color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (isDrawMode) {
                    LiquidGlassButton("清空", { routeGeo.clear() },
                        Modifier.width(80.dp), ButtonStyle.Outline, 44.dp)
                    LiquidGlassButton("完成", { isDrawMode = false },
                        Modifier.width(80.dp), ButtonStyle.White, 44.dp)
                } else {
                    LiquidGlassButton("绘制", { isDrawMode = true },
                        Modifier.width(80.dp), ButtonStyle.Outline, 44.dp)
                    LiquidGlassButton("保存", {
                        if (routeGeo.size < 2) return@LiquidGlassButton
                        onSave(routeGeo.toList())
                    }, Modifier.width(80.dp), ButtonStyle.White, 44.dp)
                }
            }
        }
    }
}
