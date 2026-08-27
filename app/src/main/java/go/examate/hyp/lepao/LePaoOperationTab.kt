package go.examate.hyp.lepao

import androidx.compose.foundation.BorderStroke
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.*

/** 第一页：操作（路线→参数→预览→上传） */

@Composable
internal fun OperationTab(
    config: RunConfig, routeDistanceKm: Float,
    uploading: Boolean, uploadProgress: String, resultMsg: String?, resultOk: Boolean,
    onEditRoute: () -> Unit, onConfigChange: (RunConfig) -> Unit,
    onUploadStart: () -> Unit, onCampusRunStart: () -> Unit
) {
    var runMode by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 56.dp, bottom = 6.dp)) {

        // ═══ 1. 路线 ═══
        FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("路线", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("${config.routePoints.size} 拐点 · %.2f km".format(routeDistanceKm), color = TextHint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                LiquidGlassButton("编辑路线", onEditRoute, Modifier.fillMaxWidth(), ButtonStyle.White, 38.dp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // ═══ 2. 跑步参数 ═══
        FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("跑步参数", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)

                var selectedPace by remember { mutableIntStateOf(1) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LePaoPaceCalculator.PACE_OPTIONS.forEach { (idx, label, _) ->
                        val isSelected = selectedPace == idx
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPace = idx },
                            label = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text(LePaoPaceCalculator.PACE_OPTIONS.first { it.first == idx }.third, fontSize = 8.sp, color = TextHint)
                            }},
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary.copy(alpha = 0.15f),
                                selectedLabelColor = Primary
                            ),
                            border = BorderStroke(1.dp, if (isSelected) Primary else Primary.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Button(
                    onClick = {
                        val r = LePaoPaceCalculator.calculate(selectedPace, routeDistanceKm)
                        onConfigChange(config.copy(
                            straightSpeed = r.straightSpeed, curveSpeed = r.curveSpeed,
                            maxAccel = r.maxAccel, stepLengthM = r.stepLengthM,
                            cadenceSpm = r.cadenceSpm, gpsIntervalMs = r.gpsIntervalMs
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary.copy(alpha = 0.12f), contentColor = Primary),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f))
                ) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("一键配速", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                CollapsibleCard("详细配置") {
                    SubCollapsible("速度", true) {
                        FloatInput("直道速度", config.straightSpeed, "m/s", 2.0f..6.0f) { onConfigChange(config.copy(straightSpeed = it)) }
                        FloatInput("弯道速度", config.curveSpeed, "m/s", 1.5f..5.0f) { onConfigChange(config.copy(curveSpeed = it)) }
                        FloatInput("加速度", config.maxAccel, "m/s²", 0.1f..2.0f) { onConfigChange(config.copy(maxAccel = it)) }
                        IntInput("热身时长", config.warmupTime, "s", 0..30) { onConfigChange(config.copy(warmupTime = it)) }
                        IntInput("冷却时长", config.cooldownTime, "s", 0..20) { onConfigChange(config.copy(cooldownTime = it)) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = Primary.copy(alpha = 0.08f))
                    SubCollapsible("采样") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("采样间隔", fontSize = 13.sp, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Text("${config.gpsIntervalMs}ms", fontSize = 13.sp, color = Primary, fontWeight = FontWeight.Medium)
                        }
                        Slider(
                            value = config.gpsIntervalMs.toFloat(),
                            onValueChange = { onConfigChange(config.copy(gpsIntervalMs = (it / 500).toInt() * 500L)) },
                            valueRange = 500f..5000f,
                            steps = 8
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp), color = Primary.copy(alpha = 0.08f))
                    SubCollapsible("身体数据") {
                        FloatInput("步长", config.stepLengthM, "m", 1.0f..1.8f) { onConfigChange(config.copy(stepLengthM = it)) }
                        IntInput("步频", config.cadenceSpm, "spm", 140..180) { onConfigChange(config.copy(cadenceSpm = it)) }
                        FloatInput("体重", config.weightKg, "kg", 35f..120f) { onConfigChange(config.copy(weightKg = it)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ═══ 3. 数据预览可视化 ═══
        if (config.routePoints.size >= 2 && routeDistanceKm > 0.01f) {
            DataPreviewSection(config, routeDistanceKm)
            Spacer(Modifier.height(10.dp))
        }

        // ═══ 4. 上传操作 ═══
        FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isFree = runMode == 0
                    FilterChip(
                        selected = isFree,
                        onClick = { runMode = 0 },
                        label = { Text("自由跑", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.15f),
                            selectedLabelColor = Primary
                        ),
                        border = BorderStroke(1.dp, if (isFree) Primary else Primary.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(1f)
                    )
                    val isCampus = runMode == 1
                    FilterChip(
                        selected = isCampus,
                        onClick = { runMode = 1 },
                        label = { Text("校园跑", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.15f),
                            selectedLabelColor = Primary
                        ),
                        border = BorderStroke(1.dp, if (isCampus) Primary else Primary.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("距离 %.2fkm · 直道 %.1fm/s · 弯道 %.1fm/s".format(routeDistanceKm, config.straightSpeed, config.curveSpeed),
                    fontSize = 12.sp, color = TextSecondary)
                if (runMode == 1) { Spacer(Modifier.height(2.dp)); Text("含打卡点+传感器数据", fontSize = 11.sp, color = Primary) }
                Spacer(Modifier.height(12.dp))
                if (uploading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text(uploadProgress, fontSize = 12.sp, color = TextSecondary)
                    }
                } else {
                    val ctx = LocalContext.current
                    LiquidGlassButton(
                        if (runMode == 0) "上传自由跑" else "上传校园跑",
                        {
                            if (config.routePoints.size < 2) {
                                Toast.makeText(ctx, "路线至少需要2个拐点，请先编辑路线", Toast.LENGTH_SHORT).show()
                            } else {
                                if (runMode == 0) onUploadStart() else onCampusRunStart()
                            }
                        },
                        Modifier.fillMaxWidth(), ButtonStyle.White, 48.dp
                    )
                }
                resultMsg?.let { Spacer(Modifier.height(10.dp)); Text(it, color = if (resultOk) Success else Error, fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

/** 数据预览区 — 生成全程轨迹并可视化速度曲线/步数/传感器 */
@Composable
private fun DataPreviewSection(config: RunConfig, routeDistanceKm: Float) {
    // 用 remember 缓存预览轨迹，依赖关键参数变化才重新生成
    val previewPts = remember(config.routePoints, config.straightSpeed, config.curveSpeed, config.gpsIntervalMs, config.warmupTime, config.cooldownTime) {
        if (config.routePoints.size >= 2) {
            LePaoTrack.generateRoute(
                config.routePoints,
                targetSpeed = (config.straightSpeed + config.curveSpeed) / 2f,
                intervalS = (config.gpsIntervalMs / 1000f).coerceAtLeast(0.5f),
                warmupS = config.warmupTime.toFloat(),
                cooldownS = config.cooldownTime.toFloat()
            )
        } else emptyList()
    }

    if (previewPts.isEmpty()) return

    val distM = LePaoTrack.calcDistance(previewPts)
    val durationS = previewPts.lastOrNull()?.t?.toInt() ?: 0
    val avgSpeed = if (durationS > 0) distM / durationS else 0f
    val paceSecPerKm = if (distM > 0) (durationS * 1000f / distM).toInt() else 0
    val estSteps = (distM / config.stepLengthM).toInt()
    val paceMin = paceSecPerKm / 60
    val paceSec = paceSecPerKm % 60

    // 合理性检查 — 基于真实数据校准
    val warnings = mutableListOf<String>()
    if (avgSpeed > 4.8f) warnings.add("速度偏快(>4.8m/s)，可能触发'速度过快'判定")
    if (avgSpeed < 2.0f) warnings.add("速度偏慢(<2.0m/s)")
    if (paceSecPerKm in 1..179) warnings.add("配速<180s/km，超出安全区间")
    if (config.cadenceSpm > 180) warnings.add("步频${config.cadenceSpm}偏高(真实145~173)")
    if (config.stepLengthM < 1.2f) warnings.add("步长<1.2m偏小(真实1.41~1.71m)")

    FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, null, tint = Primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("全程数据预览", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("${previewPts.size} 轨迹点", color = TextHint, fontSize = 11.sp)
            }

            // 汇总指标
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricCard("距离", "%.2f".format(distM / 1000f), "km", Modifier.weight(1f))
                MetricCard("时长", "${durationS / 60}'${durationS % 60}\"", "", Modifier.weight(1f))
                MetricCard("均速", "%.1f".format(avgSpeed), "m/s", Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricCard("配速", "${paceMin}'${paceSec}\"", "/km", Modifier.weight(1f))
                MetricCard("步数", estSteps.toString(), "步", Modifier.weight(1f))
                MetricCard("步频", config.cadenceSpm.toString(), "spm", Modifier.weight(1f))
            }


            // 速度曲线
            Spacer(Modifier.height(4.dp))
            Text("速度曲线", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            SpeedCurveChart(previewPts, Modifier.fillMaxWidth().height(120.dp))

            // 传感器波形预览
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("传感器波形", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ax", fontSize = 9.sp, color = TrackRed)
                    Text("ay", fontSize = 9.sp, color = TrackGreen)
                    Text("az", fontSize = 9.sp, color = TrackBlue)
                }
            }
            SensorPreviewChart(Modifier.fillMaxWidth().height(50.dp))

            // 合理性警告
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                warnings.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠", color = Error, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(w, fontSize = 11.sp, color = Error)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Success, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("数据合理性检查通过", fontSize = 11.sp, color = Success)
                }
            }
        }
    }
}

