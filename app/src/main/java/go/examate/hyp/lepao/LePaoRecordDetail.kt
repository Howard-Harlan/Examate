package go.examate.hyp.lepao

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 记录详情页面 — 单击记录项打开，展示轨迹地图与详细数据。详情与轨迹缓存避免重复加载。 */
@Composable
internal fun RecordDetailPage(
    recordId: String,
    detailCache: MutableMap<String, JSONObject>,
    trackCache: MutableMap<String, List<Pair<Double, Double>>?>,
    onImportRoute: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(detailCache[recordId] == null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var trackLoading by remember { mutableStateOf(recordId in trackCache == false && trackCache[recordId] == null) }

    val detail = detailCache[recordId]
    val trackPts = trackCache[recordId]

    // 加载详情
    LaunchedEffect(recordId) {
        if (detailCache[recordId] == null) {
            loading = true
            errorMsg = null
            scope.launch(Dispatchers.IO) {
                try {
                    val prefs = LePaoPrefs.load(context)
                    val api = LePaoApi(
                        prefs.optString("uid"),
                        prefs.optString("token"),
                        prefs.optString("school_id", LePaoPrefs.DEFAULT_SCHOOL_ID),
                        prefs.optString("student_num")
                    )
                    val resp = api.recordDetail(recordId)
                    withContext(Dispatchers.Main) {
                        if (resp.optInt("status") == 1) {
                            detailCache[recordId] = resp.optJSONObject("data")
                        } else {
                            errorMsg = resp.optString("info", "获取详情失败")
                        }
                        loading = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMsg = "网络错误: ${e.message}"
                        loading = false
                    }
                }
            }
        }
    }

    // 加载轨迹
    val fileUrl = detail?.optString("file", "") ?: ""
    LaunchedEffect(fileUrl) {
        if (fileUrl.isNotEmpty() && fileUrl != "1" && recordId !in trackCache) {
            trackLoading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val pts = LePaoTrack.downloadAndParseTrack(fileUrl, targetCount = 120)
                    withContext(Dispatchers.Main) {
                        trackCache[recordId] = pts
                        trackLoading = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        trackCache[recordId] = null
                        trackLoading = false
                    }
                }
            }
        }
    }


    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 56.dp, bottom = 24.dp)
    ) {
        when {
            loading -> {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            }
            errorMsg != null -> {
                Text(errorMsg!!, color = Error, fontSize = 14.sp)
            }
            detail != null -> {
                val d = detail!!
                val distance = d.optString("distance", "0")
                val usedTimeS = d.optString("time", d.optString("used_time", "0")).toIntOrNull() ?: 0
                val startTs = d.optLong("start_time", 0) * 1000
                val dateStr = if (startTs > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startTs)) else "--"
                val stepNum = d.optString("step_num", "0")
                val runType = if (d.optString("type") == "1") "校园跑" else "自由跑"
                val status = d.optString("record_status", "")
                val failedReason = d.optString("record_failed_reason", "")
                val isValid = status == "1" || (status == "0" && failedReason.isEmpty())
                val statusText = when {
                    isValid -> "有效"
                    failedReason.isNotEmpty() -> failedReason
                    else -> "异常"
                }
                val logNum = d.optString("log_num", "0")
                val avgSpeed = if (usedTimeS > 0) distance.toFloatOrNull()?.let { it * 1000f / usedTimeS } ?: 0f else 0f
                val paceSec = if (avgSpeed > 0) (1000f / avgSpeed).toInt() else 0

                // 基本信息
                FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(runType, color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(statusText, color = if (isValid) Success else Error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetricCard("距离", distance, "km", Modifier.weight(1f))
                            MetricCard("时长", "${usedTimeS / 60}'${usedTimeS % 60}\"", "", Modifier.weight(1f))
                            MetricCard("均速", "%.1f".format(avgSpeed), "m/s", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetricCard("配速", "${paceSec / 60}'${paceSec % 60}\"", "/km", Modifier.weight(1f))
                            MetricCard("步数", stepNum, "步", Modifier.weight(1f))
                            MetricCard("打卡", logNum, "点", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dateStr, fontSize = 12.sp, color = TextHint)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 轨迹地图
                FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("跑步轨迹", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        when {
                            trackLoading -> {
                                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), color = Primary, strokeWidth = 2.dp)
                                }
                            }
                            trackPts != null && trackPts.size >= 2 -> {
                                Text("${trackPts.size} 个轨迹点", fontSize = 11.sp, color = TextHint)
                                RouteCanvasMini(trackPts)
                            }
                            trackPts != null -> {
                                Text("轨迹数据不足", fontSize = 12.sp, color = TextHint)
                            }
                            else -> {
                                Text("该记录无轨迹文件", fontSize = 12.sp, color = TextHint)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 导入为路线按钮
                if (trackPts != null && trackPts.size >= 2) {
                    LiquidGlassButton(
                        "导入为路线",
                        { onImportRoute(recordId) },
                        Modifier.fillMaxWidth(), ButtonStyle.White, 44.dp
                    )
                }
            }
        }
    }

}
