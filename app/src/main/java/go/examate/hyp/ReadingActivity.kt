package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import go.examate.hyp.media.MediaSourceStore
import go.examate.hyp.media.legado.BookSourceStore
import go.examate.hyp.media.legado.ContentRuleStore
import go.examate.hyp.media.rememberSourceImporter
import go.examate.hyp.media.reading.ReadingNavHost
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.util.ToastUtil

/**
 * 阅读 — 小说 / 漫画,兼容 legado(阅读3.0)书源。
 * 源导入(文件/链接二选一)复用通用组件 rememberSourceImporter;含 bookSourceUrl 走 legado,否则回退旧简单源。
 */
class ReadingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // deep link: 外部(如 agent open_book 工具)传 bookId 直接续读
        val initialBookId = intent.getStringExtra("bookId")
        // 系统打开文本文件:从 ACTION_VIEW intent 取 URI,拷入私有目录后交给阅读器
        val pendingUri = if (intent?.action == android.content.Intent.ACTION_VIEW) intent.data else null
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    val context = LocalContext.current
                    var refreshTick by remember { mutableIntStateOf(0) }
                    var localFile by remember { mutableStateOf<java.io.File?>(null) }

                    // 导入本地书籍:支持 txt/md/html/htm/log/csv/json/xml/epub 等常见文本格式
                    val fileOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                            // 优先用 ContentResolver 查文件显示名,回退到 lastPathSegment
                            val name = runCatching {
                                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                                }
                            }.getOrNull()?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "local_${System.currentTimeMillis()}.txt"
                            val dst = java.io.File(java.io.File(context.filesDir, "reader_local").apply { mkdirs() }, name)
                            val ok = runCatching {
                                context.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                            }.isSuccess
                            if (ok) localFile = dst else ToastUtil.show(context, "文件导入失败")
                        }
                    }

                    // 系统打开文本文件:外部通过 ACTION_VIEW 传入 URI,拷入私有目录后进入阅读器
                    androidx.compose.runtime.LaunchedEffect(pendingUri) {
                        val uri = pendingUri ?: return@LaunchedEffect
                        val name = runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                            }
                        }.getOrNull()?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "local_${System.currentTimeMillis()}.txt"
                        val dst = java.io.File(java.io.File(context.filesDir, "reader_local").apply { mkdirs() }, name)
                        val ok = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                        }.isSuccess
                        if (ok) localFile = dst else ToastUtil.show(context, "文件打开失败")
                    }

                    // 通用源导入(文件/链接);含 bookSourceUrl 走书源库,否则旧简单源
                    val importer = rememberSourceImporter(title = "导入书源") { text ->
                        if (text.contains("bookSourceUrl")) {
                            val n = BookSourceStore.importFromJson(context, text)
                            ToastUtil.show(context, if (n > 0) "已导入 $n 个书源" else "导入失败:非有效书源 JSON")
                            if (n > 0) refreshTick++
                        } else {
                            val n = MediaSourceStore.importFromJson(context, text)
                            ToastUtil.show(context, if (n > 0) "已导入 $n 个源" else "导入失败:请选择 legado 书源 JSON")
                            if (n > 0) refreshTick++
                        }
                    }

                    // 净化规则导入:读取文件后交给 ContentRuleStore
                    val ruleOpener = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) {
                            val text = runCatching {
                                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            }.getOrNull()
                            if (text.isNullOrBlank()) {
                                ToastUtil.show(context, "读取失败")
                            } else {
                                val n = ContentRuleStore.importFromJson(context, text)
                                ToastUtil.show(context, if (n > 0) "已导入 $n 条净化规则" else "导入失败:非有效规则 JSON")
                            }
                        }
                    }

                    Box(Modifier.statusBarsPadding()) {
                        ReadingNavHost(
                            refreshTick = refreshTick,
                            pendingLocalFile = localFile,
                            onImportSource = importer.open,
                            onOpenLocalFile = {
                                // 支持常见文本格式:txt/md/html/htm/log/csv/json/xml/epub
                                fileOpener.launch(arrayOf(
                                    "text/*",
                                    "application/epub+zip",
                                    "application/x-html+zip",
                                    "application/xml",
                                    "application/json",
                                    "application/octet-stream"
                                ))
                            },
                            onImportRule = { ruleOpener.launch("*/*") },
                            initialBookId = initialBookId
                        )
                    }
                }
            }
        }
    }
}
