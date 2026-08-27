package go.examate.hyp.lepao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.*
import org.json.JSONObject
import java.util.Locale

/** 第二页：数据（成绩+记录） — 从 LePaoMainPage.kt 拆出 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DataTab(
    termScore: JSONObject?,
    loadingRecords: Boolean,
    userRecords: List<JSONObject>,
    importingRoute: Boolean,
    importMsg: String?,
    onRefresh: () -> Unit,
    onSaveRecordAsRoute: (String) -> Unit,
    onClearImportMsg: () -> Unit,
    onRecordClick: (String) -> Unit
) {
    var confirmRecord by remember { mutableStateOf<JSONObject?>(null) }


    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 56.dp, bottom = 6.dp)) {
        // 成绩
        FrostedGlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本学期成绩", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRefresh, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.Refresh, "刷新", tint = TextHint, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (loadingRecords) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Primary, strokeWidth = 2.dp)
                    }
                } else if (termScore != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("次数", termScore.optString("total_score_num", "0"), "次", Modifier.weight(1f))
                        MetricCard("距离", termScore.optString("total_score_distance", "0"), "km", Modifier.weight(1f))
                    }
                } else { Text("暂无成绩数据", color = TextHint, fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 导入状态提示
        if (importMsg != null) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(if (importMsg.startsWith("✓")) Success.copy(alpha = 0.1f) else Error.copy(alpha = 0.1f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (importingRoute) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(importMsg, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearImportMsg) { Text("关闭", fontSize = 11.sp) }
                }
            }
        }

        // 记录列表
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("跑步记录 (${userRecords.size})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Default.Refresh, "刷新", tint = TextHint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("长按记录可导入为路线", fontSize = 10.sp, color = TextHint)
        Spacer(Modifier.height(6.dp))

        if (loadingRecords) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Primary)
            }
        } else if (userRecords.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(CardBg)) {
                Text("暂无跑步记录", color = TextHint, fontSize = 12.sp, modifier = Modifier.padding(18.dp))
            }
        } else {
            userRecords.take(50).forEach { rec ->
                val recordId = rec.optString("id")
                val distance = rec.optString("distance", "0")
                val startTimeSec = rec.optLong("start_time", 0) * 1000
                val dateStr = if (startTimeSec > 0) java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(startTimeSec)) else "--"
                val usedTimeS = rec.optString("used_time", "0").toIntOrNull() ?: 0
                val timeStr = "${usedTimeS / 60}'${usedTimeS % 60}\""
                val status = rec.optString("record_status", "0")
                val runType = if (rec.optString("type") == "1") "校园跑" else "自由跑"
                val isValid = status == "1"
                val reason = when (status) { "23" -> "时间异常"; "11" -> "数据异常"; else -> "" }
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 5.dp)
                        .combinedClickable(
                            onClick = { onRecordClick(recordId) },
                            onLongClick = { confirmRecord = rec }
                        ),
                    shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(CardBg)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).background(if (isValid) Success.copy(alpha = 0.12f) else Error.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (isValid) "✓" else "✗", color = if (isValid) Success else Error, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$distance km · $timeStr · $runType", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(1.dp))
                            Row { Text(dateStr, fontSize = 10.sp, color = TextHint); if (reason.isNotEmpty()) { Spacer(Modifier.width(4.dp)); Text(reason, fontSize = 10.sp, color = Error) } }
                        }
                        Text("#$recordId", fontSize = 10.sp, color = TextHint)
                    }
                }
            }
            if (userRecords.size > 50) Text("仅显示最近50条", fontSize = 10.sp, color = TextHint, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
        }
        // 给悬浮底栏留空间
        Spacer(Modifier.height(80.dp))
    }

    // 长按确认对话框
    confirmRecord?.let { rec ->
        val rid = rec.optString("id")
        val dist = rec.optString("distance", "0")
        AlertDialog(
            onDismissRequest = { confirmRecord = null },
            title = { Text("导入为路线", fontWeight = FontWeight.Medium) },
            text = { Text("将记录 #$rid ($dist km) 的轨迹保存为跑步路线？\n上传时将在此路线基础上微调。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onSaveRecordAsRoute(rid)
                    confirmRecord = null
                }) { Text("导入", color = Primary, fontWeight = FontWeight.Medium) }
            },
            dismissButton = { TextButton(onClick = { confirmRecord = null }) { Text("取消") } }
        )
    }
}
