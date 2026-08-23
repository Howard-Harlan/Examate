package go.examate.hyp.music.fx

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网页主题规范页 — 以页面形式呈现 PLAYER_THEME_SPEC.md,支持一键复制与导出为文件。
 * Markdown 轻量渲染:#/## 标题、- 列表、| 表格、`代码`行内样式剥离。
 */
@Composable
fun ThemeSpecPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var spec by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        spec = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("player_fx/PLAYER_THEME_SPEC.md").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrDefault("")
        }
    }

    // 导出:SAF 创建 .md 文件写入规范原文
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(spec.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            ToastUtil.show(context, if (ok) "规范已导出" else "导出失败")
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏与音乐模块其他子页的悬浮返回按钮位置保持一致:
        // statusBarsPadding + 60dp 行高垂直居中,返回按钮(40dp)与右侧功能按钮同一水平线
        Row(
            Modifier.statusBarsPadding().fillMaxWidth()
                .height(60.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("网页主题规范", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            SpecActionBtn(icon = Icons.Filled.ContentCopy, label = "复制") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("player_theme_spec", spec))
                ToastUtil.show(context, "规范已复制到剪贴板")
            }
            Spacer(Modifier.width(8.dp))
            SpecActionBtn(icon = Icons.Filled.FileDownload, label = "导出") {
                exporter.launch("PLAYER_THEME_SPEC.md")
            }
        }

        val blocks = remember(spec) { spec.lines() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 8.dp, bottom = 150.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(blocks) { line -> SpecLine(line) }
        }
    }
}

/** 单行 Markdown 轻量渲染 */
@Composable
private fun SpecLine(raw: String) {
    val line = raw.trimEnd()
    when {
        line.isBlank() -> Spacer(Modifier.height(6.dp))
        line.startsWith("# ") -> Text(
            stripCode(line.removePrefix("# ")), color = TextPrimary,
            fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)
        )
        line.startsWith("## ") -> Text(
            stripCode(line.removePrefix("## ")), color = Primary,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
        line.startsWith("- ") -> Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
            Text("•", color = TextHint, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text(stripCode(line.removePrefix("- ")), color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        }
        line.startsWith("|") -> {
            // 表格分隔行(---)跳过;其余行以单元格横排呈现
            if (!line.contains("-")) {
                val cells = line.split('|').map { stripCode(it.trim()) }.filter { it.isNotEmpty() }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(SurfaceVariant.copy(0.45f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cells.forEachIndexed { i, c ->
                        Text(
                            c,
                            color = if (i == 0) TextPrimary else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                            fontFamily = if (c.startsWith("{") || c.startsWith("\"")) FontFamily.Monospace else FontFamily.Default,
                            modifier = Modifier.weight(if (i == cells.size - 1) 2.2f else 1f, fill = false)
                        )
                    }
                }
            }
        }
        else -> Text(stripCode(line), color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** 剥离行内代码反引号(保留内容) */
private fun stripCode(s: String): String = s.replace("`", "")

/** 顶部操作小按钮(复制/导出) */
@Composable
private fun SpecActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(Primary.copy(0.1f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = Primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
