package go.examate.hyp.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.media.legado.AnalyzeUrl
import go.examate.hyp.media.legado.LegadoHttp
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 源导入触发器:调用 open() 弹出「从文件 / 粘贴链接」选择 */
class SourceImportLauncher(val open: () -> Unit)

/**
 * 通用源导入组件 — 阅读/观影共用。封装:
 *  选择方式(从文件/粘贴链接) → 文件选择器 或 链接下载 → 统一把 JSON 文本交给 onText。
 *
 * 用法:val importer = rememberSourceImporter { text -> ... };按钮 onClick = importer.open
 *
 * @param title 弹窗标题(如「导入书源」「导入影视源」)
 * @param linkHint 链接输入占位提示
 * @param onText 拿到 JSON 文本后的处理(入库等),在主线程调用
 */
@Composable
fun rememberSourceImporter(
    title: String = "导入源",
    linkHint: String = "https://…/sources.json",
    onText: (String) -> Unit
): SourceImportLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showChooser by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) ToastUtil.show(context, "读取失败") else onText(text)
        }
    }

    if (showChooser) {
        PlainDialog(onDismissRequest = { showChooser = false }) {
            PlainDialogTitle(title)
            PlainDialogContent("选择导入方式")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ImportChoice("从文件", Modifier.weight(1f)) { showChooser = false; filePicker.launch("*/*") }
                ImportChoice("粘贴链接", Modifier.weight(1f)) { showChooser = false; showLink = true }
            }
            PlainDialogButtons(listOf(Triple("取消", false) { showChooser = false }))
        }
    }

    if (showLink) {
        LinkImportDialog(linkHint, onDismiss = { showLink = false }) { url ->
            showLink = false
            ToastUtil.show(context, "下载中…")
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { LegadoHttp.fetch(AnalyzeUrl.UrlRequest(url.trim())).body }.getOrNull()
                }
                if (text.isNullOrBlank()) ToastUtil.show(context, "下载失败或内容为空") else onText(text)
            }
        }
    }

    return remember { SourceImportLauncher { showChooser = true } }
}

@Composable
private fun ImportChoice(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Primary.copy(0.1f)).clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun LinkImportDialog(hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("粘贴源链接")
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("输入源订阅 URL(指向 .json)", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                placeholder = { Text(hint, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        PlainDialogButtons(listOf(
            Triple("取消", false) { onDismiss() },
            Triple("导入", true) { if (url.isNotBlank()) onConfirm(url) }
        ))
    }
}
