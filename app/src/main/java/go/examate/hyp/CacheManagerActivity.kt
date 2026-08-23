package go.examate.hyp

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.CacheCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 缓存管理页 — 分门别类清理。
 *
 * 按模块分类:音乐 / 阅读 / 下载 / 图片 / 网页 / 其他,
 * 每类显示实时大小,支持单项清理与一键全部清理。
 * 与系统设置的"清除缓存"入口联动(替换原来的单一清理)。
 */
class CacheManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            go.examate.hyp.ui.theme.ExamateTheme {
                go.examate.hyp.ui.components.LiquidGlassBackground {
                    CacheManagerScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
fun CacheManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 每类大小:Category → size(空表示计算中)
    val sizes = remember { mutableStateOf<Map<CacheCleaner.Category, Long?>>(emptyMap()) }
    // 计算中 / 清理中的分类集合
    val busy = remember { mutableStateOf<Set<CacheCleaner.Category>>(emptySet()) }
    var clearingAll by remember { mutableStateOf(false) }
    // 全部清理确认弹窗
    var confirmAll by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val map = mutableMapOf<CacheCleaner.Category, Long?>()
            for (cat in CacheCleaner.Category.entries) {
                map[cat] = withContext(Dispatchers.IO) { CacheCleaner.categorySize(context, cat) }
            }
            sizes.value = map
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun clearOne(cat: CacheCleaner.Category) {
        if (busy.value.contains(cat)) return
        busy.value = busy.value + cat
        scope.launch {
            withContext(Dispatchers.IO) { CacheCleaner.clearCategory(context, cat) }
            busy.value = busy.value - cat
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(go.examate.hyp.ui.theme.BgPrimary)) {
        // ═══ 顶栏 ═══
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                text = "缓存管理",
                color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ═══ 汇总 + 全部清理 ═══
        val total = sizes.value.values.filterNotNull().sum()
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(if (go.examate.hyp.ui.theme.IsDark) 0.08f else 0.55f))
                .clickable { if (total > 0) confirmAll = true }
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${formatBytes(total)}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (clearingAll) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TextHint)
                    } else {
                        Text("全部清理", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("分门别类清理,不影响账号与已下载完成文件", color = TextHint, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══ 分类列表 ═══
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(CacheCleaner.Category.entries, key = { it.key }) { cat ->
                CacheRow(
                    label = cat.label,
                    desc = cat.desc,
                    sizeText = when {
                        busy.value.contains(cat) -> "清理中…"
                        sizes.value[cat] == null -> "计算中…"
                        cat == CacheCleaner.Category.WEB -> "按需清理"
                        else -> formatBytes(sizes.value[cat] ?: 0L)
                    },
                    onClear = { clearOne(cat) }
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // 全部清理确认
    if (confirmAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("清空全部缓存", color = TextPrimary, fontSize = 15.sp) },
            text = { Text("将清理所有分类缓存(音乐/阅读/下载/图片/网页/其他),不可恢复。确定继续?", color = TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                Text(
                    "清空", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        confirmAll = false
                        clearingAll = true
                        scope.launch {
                            withContext(Dispatchers.IO) { CacheCleaner.clear(context) }
                            clearingAll = false
                            refresh()
                        }
                    }.padding(10.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消", color = TextHint, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { confirmAll = false }.padding(10.dp)
                )
            }
        )
    }
}

/** 分类行:名称 + 描述 + 大小 + 清理按钮 */
@Composable
private fun CacheRow(label: String, desc: String, sizeText: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(if (go.examate.hyp.ui.theme.IsDark) 0.08f else 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = TextHint, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(sizeText, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(Primary.copy(0.12f))
                .clickable { onClear() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("清理", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
