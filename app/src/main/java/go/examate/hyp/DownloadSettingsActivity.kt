package go.examate.hyp

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.downloader.DownloadSettings
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil

/**
 * 下载设置 — 独立页面(原为 DownloaderActivity 内的弹窗,改为整页,风格与其他设置页一致)。
 * 分段数 / 并发数 / 重试次数 / 全局限速 / User-Agent;保存写回 DownloadSettings。
 */
class DownloadSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    DownloadSettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun DownloadSettingsScreen() {
    val context = LocalContext.current
    val onBack: () -> Unit = { (context as? Activity)?.finish() }

    var segmentCount by remember { mutableIntStateOf(DownloadSettings.segmentCount) }
    var maxConcurrent by remember { mutableIntStateOf(DownloadSettings.maxConcurrentTasks) }
    var speedLimit by remember { mutableStateOf(DownloadSettings.speedLimitKb.toString()) }
    var maxRetries by remember { mutableIntStateOf(DownloadSettings.maxRetries) }
    var userAgent by remember { mutableStateOf(DownloadSettings.userAgent) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        // 顶栏:返回 + 居中标题
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                text = "下载设置",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 40.dp)
        ) {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    StepperRow(
                        label = "单任务分段数",
                        hint = "越大越快,过高易被服务器限流",
                        value = segmentCount,
                        range = 1..DownloadSettings.MAX_SEGMENT_COUNT,
                        onChange = { segmentCount = it }
                    )
                    StepperRow(
                        label = "同时下载任务数",
                        hint = "超出的任务进入排队",
                        value = maxConcurrent,
                        range = 1..DownloadSettings.MAX_CONCURRENT_TASKS,
                        onChange = { maxConcurrent = it }
                    )
                    StepperRow(
                        label = "分段失败重试次数",
                        hint = "每次重试间隔递增",
                        value = maxRetries,
                        range = 0..10,
                        onChange = { maxRetries = it }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("全局限速 (KB/s,0 = 不限速)", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = speedLimit,
                        onValueChange = { input -> speedLimit = input.filter { it.isDigit() }.take(7) },
                        placeholder = { Text("0", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = textFieldColors(),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp)
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("User-Agent", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        maxLines = 3,
                        colors = textFieldColors(),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "设置对新任务生效;正在下载的任务需暂停后继续。",
                        fontSize = 11.sp,
                        color = TextHint,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidGlassButton(
                    text = "恢复默认",
                    onClick = {
                        segmentCount = 8
                        maxConcurrent = 3
                        speedLimit = "0"
                        maxRetries = 3
                        userAgent = DownloadSettings.DEFAULT_UA
                    },
                    style = go.examate.hyp.ui.components.ButtonStyle.Outline,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "保存",
                    onClick = {
                        DownloadSettings.segmentCount = segmentCount
                        DownloadSettings.maxConcurrentTasks = maxConcurrent
                        DownloadSettings.speedLimitKb = speedLimit.toIntOrNull() ?: 0
                        DownloadSettings.maxRetries = maxRetries
                        DownloadSettings.userAgent = userAgent
                        ToastUtil.show(context, "已保存")
                        onBack()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 数值步进行:标签 + 说明 + [-] 值 [+] */
@Composable
private fun StepperRow(
    label: String,
    hint: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 11.sp, color = TextHint, lineHeight = 15.sp)
        }
        StepperButton("−", enabled = value > range.first) { onChange((value - 1).coerceIn(range)) }
        Text(
            text = value.toString(),
            fontSize = 15.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp).size(width = 32.dp, height = 20.dp)
        )
        StepperButton("+", enabled = value < range.last) { onChange((value + 1).coerceIn(range)) }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) AccentBlue.copy(0.12f) else Primary.copy(0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 16.sp, color = if (enabled) AccentBlue else TextHint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun textFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(0.7f),
    unfocusedContainerColor = Color.White.copy(0.5f),
    focusedIndicatorColor = AccentBlue,
    unfocusedIndicatorColor = Color(0xFFD1D5DB),
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentBlue,
    focusedPlaceholderColor = TextHint,
    unfocusedPlaceholderColor = TextHint
)
