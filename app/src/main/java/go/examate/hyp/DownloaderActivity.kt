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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import go.examate.hyp.downloader.DownloadCenter
import go.examate.hyp.downloader.DownloadSettings
import go.examate.hyp.downloader.DownloadStatus
import go.examate.hyp.downloader.DownloadTask
import go.examate.hyp.downloader.formatBytes
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil

/**
 * 直链下载器
 *
 * 粘贴 HTTP/HTTPS 直链下载文件,支持断点续传(暂停/恢复/重试)。
 * 完成的文件导出到系统 Download/Examate 目录,可直接打开。
 */
class DownloaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DownloadCenter.init(applicationContext)
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    DownloaderRoot()
                }
            }
        }
    }
}

@Composable
private fun DownloaderRoot() {
    val context = LocalContext.current
    val onBack: () -> Unit = { (context as? Activity)?.finish() }
    val tasks by DownloadCenter.tasks.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        // 顶栏:液态玻璃返回按钮 + 居中标题 + 设置
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterStart)
            )
            Text(
                text = "下载",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
           LiquidGlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "下载设置",
                onClick = { context.startActivity(android.content.Intent(context, DownloadSettingsActivity::class.java)) },
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterEnd)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { UrlInputCard() }

            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = "暂无下载任务\n粘贴文件直链开始下载,完成后保存到系统下载目录",
                        color = TextHint,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 30.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                item {
                    Text(
                        text = "下载任务(${tasks.size})",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task)
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

// ════════════════════════════════════════
//  链接输入卡片
// ════════════════════════════════════════

@Composable
private fun UrlInputCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }

    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("下载链接", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("粘贴 HTTP/HTTPS 文件直链", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = textFieldColors(),
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                trailingIcon = {
                    Text(
                        text = "粘贴",
                        fontSize = 14.sp,
                        color = AccentBlue,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                clipboard.getText()?.text?.trim()?.let { if (it.isNotEmpty()) url = it }
                            }
                            .padding(horizontal = 8.dp)
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            LiquidGlassButton(
                text = "开始下载",
                onClick = {
                    if (url.isBlank()) {
                        ToastUtil.show(context, "请先输入下载链接")
                        return@LiquidGlassButton
                    }
                    val error = DownloadCenter.add(url)
                    if (error != null) {
                        ToastUtil.show(context, error)
                    } else {
                        url = ""
                        ToastUtil.show(context, "已加入下载队列")
                    }
                },
                style = ButtonStyle.Primary,
                height = 46.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ════════════════════════════════════════
//  任务卡片
// ════════════════════════════════════════

@Composable
private fun TaskCard(task: DownloadTask) {
    val context = LocalContext.current

    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // 文件名 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.fileName,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = statusText(task.status),
                    color = statusColor(task.status),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(10.dp))

            // 进度条
            val fraction = when {
                task.status == DownloadStatus.DONE -> 1f
                task.totalBytes > 0 -> (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Primary.copy(0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (task.status == DownloadStatus.DONE) Color(0xFF34C759) else AccentBlue)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 大小/速度 + 操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sizeText(task),
                    color = TextHint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                when (task.status) {
                    DownloadStatus.RUNNING -> ActionText("暂停") { DownloadCenter.pause(task.id) }
                    DownloadStatus.PAUSED -> ActionText("继续") { DownloadCenter.resume(task.id) }
                    DownloadStatus.FAILED -> ActionText("重试") { DownloadCenter.resume(task.id) }
                    DownloadStatus.DONE -> ActionText("打开") { DownloadCenter.openFile(context, task) }
                    DownloadStatus.PENDING -> {}
                }
                Spacer(Modifier.size(6.dp))
                ActionText("删除", color = TextHint) {
                    DownloadCenter.delete(task.id)
                    if (task.status == DownloadStatus.DONE) {
                        ToastUtil.show(context, "已删除记录,文件保留在系统下载目录")
                    }
                }
            }

            // 失败原因
            if (task.status == DownloadStatus.FAILED && task.error.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task.error,
                    color = Danger,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 小号文字操作按钮(与项目"切换账号"等文字按钮样式一致) */
@Composable
private fun ActionText(text: String, color: Color = AccentBlue, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

private fun statusText(status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.DONE -> "已完成"
}

private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.PENDING -> TextHint
    DownloadStatus.RUNNING -> AccentBlue
    DownloadStatus.PAUSED -> TextSecondary
    DownloadStatus.FAILED -> Danger
    DownloadStatus.DONE -> Color(0xFF34C759)
}

private fun sizeText(task: DownloadTask): String {
    val downloaded = formatBytes(task.downloadedBytes)
    val total = if (task.totalBytes > 0) formatBytes(task.totalBytes) else "未知大小"
    return when (task.status) {
        DownloadStatus.RUNNING -> {
            val threads = if (task.threadCount > 1) " · ${task.threadCount} 线程" else ""
            "$downloaded / $total · ${formatBytes(task.speedBps)}/s$threads"
        }
        DownloadStatus.DONE -> formatBytes(task.totalBytes.takeIf { it > 0 } ?: task.downloadedBytes)
        else -> "$downloaded / $total"
    }
}

// ════════════════════════════════════════
//  下载参数设置(aria2 风格)
// ════════════════════════════════════════

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
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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
        Text(
            text = symbol,
            fontSize = 16.sp,
            color = if (enabled) AccentBlue else TextHint,
            fontWeight = FontWeight.Medium
        )
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
