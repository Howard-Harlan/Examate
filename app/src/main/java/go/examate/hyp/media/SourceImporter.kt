package go.examate.hyp.media

import android.util.Log
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
 * @param raceValidator 竞速验证模式:多通道下载的候选载荷按到达顺序逐个交给验证(后台线程),
 *        返回 true = 采纳并立即结束(其余通道丢弃),false = 拒绝继续下一个。
 *        提供后链接/文件导入均走此模式,onText 不再被调用
 * @param onProgress 进度文案回调(下载中/验证中/失败计数),供上层提示用户
 * @param onText 拿到 JSON 文本后的处理(入库等),在主线程调用
 */
@Composable
fun rememberSourceImporter(
    title: String = "导入源",
    linkHint: String = "https://…/sources.json",
    raceValidator: ((String) -> Boolean)? = null,
    onProgress: ((String) -> Unit)? = null,
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
            if (text.isNullOrBlank()) ToastUtil.show(context, "读取失败")
            else if (raceValidator != null) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { raceValidator(text) }
                    if (!ok) ToastUtil.show(context, "导入失败:内容未通过校验")
                }
            } else onText(text)
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
            if (raceValidator != null) {
                // 竞速验证模式:候选到达一个验一个,第一个通过即成功(不等死通道,快)
                ToastUtil.show(context, "导入中:多通道下载…")
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        downloadAndValidate(url.trim(), raceValidator, onProgress)
                    }
                    if (!ok) ToastUtil.show(context, "导入失败:所有候选均未通过校验 — 请换网络重试或用文件导入")
                }
            } else {
                ToastUtil.show(context, "下载中…")
                scope.launch {
                    val texts = withContext(Dispatchers.IO) { downloadCandidates(url.trim(), null) }
                    when {
                        texts.isEmpty() -> ToastUtil.show(context, "下载失败或内容不完整(网络可能截断,请重试或换网络/用文件导入)")
                        else -> onText(texts.first())
                    }
                }
            }
        }
    }

    return remember { SourceImportLauncher { showChooser = true } }
}

private const val TAG = "SourceImporter"
private const val CHUNK = 64 * 1024

/**
 * 竞速验证下载 — 流式处理:通道按完成顺序交候选,逐个过 validator,
 * 第一个通过即返回 true(其余任务丢弃),不用等死通道(raw 链路超时不再拖慢整体)。
 */
private fun downloadAndValidate(
    url: String,
    validator: (String) -> Boolean,
    onProgress: ((String) -> Unit)?
): Boolean {
    val ghRaw = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(url)
    val urls = buildList {
        add(url)
        if (ghRaw != null) {
            val (_, user, repo, branch, path) = ghRaw.groupValues
            add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }
    }
    val executor = java.util.concurrent.Executors.newFixedThreadPool(urls.size * 2)
    val completion = java.util.concurrent.ExecutorCompletionService<String?>(executor)
    val futures: List<java.util.concurrent.Future<String?>> = try {
        urls.flatMap { u ->
            listOf(
                completion.submit(java.util.concurrent.Callable<String?> { downloadChunked(u) }),
                completion.submit(java.util.concurrent.Callable<String?> { downloadViaCurl(u) })
            )
        }
    } catch (e: Exception) {
        executor.shutdownNow(); throw e
    }
    try {
        val seen = HashSet<String>()
        val md5 = java.security.MessageDigest.getInstance("MD5")
        var failed = 0
        repeat(futures.size) {
            val f = completion.poll(30, java.util.concurrent.TimeUnit.SECONDS)
                ?: run { onProgress?.invoke("导入超时,已放弃"); return false }
            val text = runCatching { f.get() }.getOrNull()
            if (text.isNullOrBlank()) {
                failed++
                if (failed < futures.size) onProgress?.invoke("下载中…($failed 个通道失败,等待其余通道)")
            } else {
                md5.reset()
                val fp = md5.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                if (seen.add(fp)) {
                    onProgress?.invoke("获得候选载荷(${text.length} 字符),服务端校验中…")
                    val ok = runCatching { validator(text) }.getOrElse {
                        Log.w(TAG, "候选验证异常", it); false
                    }
                    if (ok) {
                        Log.i(TAG, "候选通过校验并采纳: chars=${text.length}")
                        return true
                    }
                    onProgress?.invoke("该候选未通过校验,尝试其余通道…")
                }
            }
        }
        return false
    } finally {
        futures.forEach { it.cancel(true) }
        executor.shutdownNow()
    }
}

/**
 * 链接下载 — 多通道竞速收集候选载荷:
 *  - OkHttp 分块通道(Range 逐块硬校验 + 缓存穿透)与**系统 curl 通道**并行竞速。
 *    中间设备可能按 TLS/客户端指纹对 OkHttp(Java 栈)响应定向投毒,
 *    而系统 curl 是独立的网络栈与指纹,实测同一链路上 OkHttp 拿到残缺载荷时 curl 能拿到完整载荷。
 *  - 返回按 md5 去重、长度降序的候选列表(截断只会变短,完整载荷排最前)。
 * @param onProgress 进度回调(可空)
 */
private fun downloadCandidates(url: String, onProgress: ((String) -> Unit)?): List<String> {
    val ghRaw = Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(url)
    val urls = buildList {
        add(url)
        if (ghRaw != null) {
            val (_, user, repo, branch, path) = ghRaw.groupValues
            add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }
    }
    val executor = java.util.concurrent.Executors.newFixedThreadPool(urls.size * 2)
    val payloads = try {
        val tasks = urls.flatMap { u ->
            listOf(
                java.util.concurrent.Callable { downloadChunked(u) },
                java.util.concurrent.Callable { downloadViaCurl(u) }
            )
        }
        executor.invokeAll(tasks, 30, java.util.concurrent.TimeUnit.SECONDS)
            .mapNotNull { runCatching { it.get() }.getOrNull() }
    } finally {
        executor.shutdownNow()
    }
    // 兜底:全部通道失败时用整包长度硬校验通道再试一次
    val all = if (payloads.isEmpty()) listOfNotNull(fetchVerified(url, retries = 2)) else payloads
    if (all.isEmpty()) Log.w(TAG, "全部下载通道失败: $url")
    // md5 去重 + 长度降序(截断只会变短)
    val md5 = java.security.MessageDigest.getInstance("MD5")
    return all.distinctBy { t ->
        md5.reset()
        md5.digest(t.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }.sortedByDescending { it.length }.also {
        if (it.size > 1) Log.i(TAG, "收集到 ${it.size} 个不同候选载荷,长度=${it.map { t -> t.length }}")
        else if (it.isNotEmpty()) Log.i(TAG, "下载完成: chars=${it.first().length}")
    }
}

/**
 * 系统 curl 通道 — 与 OkHttp 完全独立的 TLS 栈/客户端指纹:
 * 部分网络中间设备会对特定客户端栈的响应定向篡改,curl 通道常能绕过。
 * 设备无 curl 时静默返回 null(不影响其它通道)。
 */
private fun downloadViaCurl(url: String): String? = runCatching {
    val sep = if (url.contains('?')) '&' else '?'
    val freshUrl = "$url${sep}t=${System.currentTimeMillis()}"
    val tmp = java.io.File.createTempFile("src_import", ".tmp")
    try {
        val proc = ProcessBuilder("curl", "-sSL", "--max-time", "15", "-o", tmp.absolutePath, freshUrl)
            .redirectErrorStream(true).start()
        if (!proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        val text = tmp.readText(Charsets.UTF_8)
        // 排除拦截页(HTML)/空载荷
        if (text.isBlank() || text.trimStart().startsWith("<")) return null
        text
    } finally {
        runCatching { tmp.delete() }
    }
}.onFailure { Log.w(TAG, "curl 通道异常: $url", it) }.getOrNull()

/**
 * Range 分块下载单通道:
 *  1. 探测:Range 0..CHUNK-1 → 206 从 Content-Range 解析总长;200 = 服务端忽略 Range,直接收整包
 *  2. 剩余区间逐块拉取(每块重试 3 次),块长硬校验 == 请求区间长
 *  3. 总长核对通过才返回;任一块不可恢复 → 本通道失败
 */
private fun downloadChunked(url: String): String? = runCatching {
    // 缓存穿透:随机参数避免命中代理的污染缓存(GitHub raw / jsdelivr 均忽略未知参数)
    val sep = if (url.contains('?')) '&' else '?'
    val freshUrl = "$url${sep}t=${System.currentTimeMillis()}"
    val probe = LegadoHttp.fetchBytes(
        AnalyzeUrl.UrlRequest(freshUrl, headers = mapOf("Range" to "bytes=0-${CHUNK - 1}"))
    )
    if (probe.code == 200) {
        // 服务端不支持 Range:整包已回,长度自洽即可用
        val body = String(probe.bytes, Charsets.UTF_8)
        if (probe.contentLength < 0 || probe.bytes.size.toLong() == probe.contentLength) body else null
    } else if (probe.code == 206) {
        val total = probe.contentRange?.substringAfter('/')?.takeIf { it != "*" }?.toLongOrNull() ?: return@runCatching null
        if (total <= 0 || probe.bytes.isEmpty() || probe.bytes.size > total) return@runCatching null
        val buf = java.io.ByteArrayOutputStream(total.toInt())
        buf.write(probe.bytes)
        var offset = probe.bytes.size.toLong()
        while (offset < total) {
            val end = minOf(offset + CHUNK - 1, total - 1)
            var chunk: ByteArray? = null
            repeat(3) { attempt ->
                if (chunk == null) {
                    chunk = runCatching {
                        val r = LegadoHttp.fetchBytes(
                            AnalyzeUrl.UrlRequest(freshUrl, headers = mapOf("Range" to "bytes=$offset-$end"))
                        )
                        if (r.code == 206 && r.bytes.size.toLong() == end - offset + 1) r.bytes else null
                    }.getOrNull()
                    if (chunk == null && attempt < 2) Thread.sleep(300)
                }
            }
            if (chunk == null) {
                Log.w(TAG, "分块失败(区间 $offset-$end 不可恢复): $url")
                return@runCatching null
            }
            buf.write(chunk)
            offset = end + 1
        }
        String(buf.toByteArray(), Charsets.UTF_8)
    } else {
        Log.w(TAG, "探测异常 code=${probe.code}: $url")
        null
    }
}.onFailure { Log.w(TAG, "分块下载异常: $url", it) }.getOrNull()?.takeIf { it.isNotBlank() }

/** 单通道下载:仅接受完整载荷(长度硬校验;无声明长度时 .js 走断尾启发式) */
private fun fetchVerified(url: String, retries: Int): String? {
    repeat(retries) { attempt ->
        val resp = runCatching { LegadoHttp.fetch(AnalyzeUrl.UrlRequest(url)) }.getOrNull()
        if (resp != null) {
            val body = resp.body
            val complete = when {
                body.isBlank() -> false
                resp.lengthVerified -> true                       // 硬校验通过
                resp.contentLength < 0 -> looksComplete(body, url) // 未声明长度 → 结构启发式
                else -> false
            }
            if (complete) return body
            Log.w(TAG, "载荷不完整: $url declared=${resp.contentLength} chars=${body.length}")
        }
        if (attempt < retries - 1) Thread.sleep(600)
    }
    return null
}

/** 内容完整性启发式:.js 脚本必须以终结符收尾(截断文件必在词中戛然而止) */
private fun looksComplete(body: String, url: String): Boolean {
    if (!url.endsWith(".js", ignoreCase = true)) return true
    return body.trimEnd().lastOrNull() in setOf(';', '}', ')', '"', '\'', '`')
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
