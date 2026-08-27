package go.examate.hyp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ExamateTheme

import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * PDF 预览页面 — 使用原生 [PdfRenderer] 渲染 PDF,无需第三方依赖。
 *
 * 功能:
 * 1. OkHttp 下载 PDF 到 [cacheDir](不保存到手机可见目录)
 * 2. [PdfRenderer] 逐页渲染为 Bitmap(3x 分辨率保证清晰度)
 * 3. 双指缩放 + 拖动浏览
 * 4. 保存到本地(通过 SAF 选择目录)
 *
 * @param url PDF 文件 URL(由 [WebViewActivity] 拦截后传入)
 */
class PdfPreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PDF_URL = "extra_pdf_url"
        const val EXTRA_PDF_TITLE = "extra_pdf_title"
    }

    private var pdfUrl: String = ""
    private var cacheFile: File? = null

    // SAF 保存:用户选择目录后写入
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null && cacheFile != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    cacheFile!!.inputStream().use { it.copyTo(out) }
                }
                ToastUtil.show(this, "已保存")
            }.onFailure {
                ToastUtil.show(this, "保存失败: ${it.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pdfUrl = intent.getStringExtra(EXTRA_PDF_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_PDF_TITLE) ?: "PDF预览"

        setContent {
            ExamateTheme {
                PdfPreviewScreen(
                    url = pdfUrl,
                    title = title,
                    cacheDir = cacheDir,
                    onBack = { finish() },
                    onSave = {
                        // 先确保已下载到缓存,再调起 SAF 选择保存位置
                        val cached = ensureCached(url = pdfUrl, cacheDir = cacheDir)
                        cacheFile = cached
                        val fileName = pdfUrl.substringAfterLast("/").substringBefore("?")
                            .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                        saveLauncher.launch(fileName)
                    }
                )
            }
        }
    }

    /** 确保 PDF 已下载到缓存(保存功能复用) */
    private fun ensureCached(url: String, cacheDir: File): File {
        val file = File(File(cacheDir, "pdf_cache").apply { mkdirs() }, "pdf_${url.hashCode()}.pdf")
        if (!file.exists() || file.length() == 0L) {
            val client = OkHttpClient()
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                file.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            }
        }
        return file
    }
}

@Composable
private fun PdfPreviewScreen(
    url: String,
    title: String,
    cacheDir: File,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    // 全局缩放与平移
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(url) {
        loading = true
        error = ""
        try {
            pages = withContext(Dispatchers.IO) {
                downloadAndRenderPdf(url, cacheDir)
            }
            if (pages.isEmpty()) {
                error = "PDF 渲染失败或文件为空"
            }
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(BgPrimary)) {
        // ═══ 顶栏(液态玻璃按钮) ═══
        Box(
            Modifier.fillMaxWidth().background(CardBg).statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text(
                title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
            if (!loading && error.isEmpty()) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.SaveAlt,
                    contentDescription = "保存到本地",
                    onClick = onSave,
                    modifier = Modifier.size(40.dp).align(Alignment.CenterEnd)
                )
            }
        }

        // ═══ 内容区 ═══
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentBlue)
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载PDF...", color = TextSecondary, fontSize = 14.sp)
                }
            }
        } else if (error.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("已缓存到临时目录,不会保存到手机", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            // PDF 页面列表(支持双指缩放 + 拖动)
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offset = Offset(offset.x + pan.x, offset.y + pan.y)
                    }
                }
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pages, key = { it.hashCode() }) { bitmap ->
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 下载 PDF 到缓存目录并用 [PdfRenderer] 渲染所有页面。
 *
 * 文件存入 [cacheDir]/pdf_cache/ 下(非外部存储,不会出现在手机文件管理器中)。
 * 渲染分辨率 3x,保证缩放后仍清晰。
 */
private fun downloadAndRenderPdf(url: String, cacheDir: File): List<Bitmap> {
    val client = OkHttpClient()
    val cacheFile = File(File(cacheDir, "pdf_cache").apply { mkdirs() }, "pdf_${url.hashCode()}.pdf")

    if (!cacheFile.exists() || cacheFile.length() == 0L) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            cacheFile.outputStream().use { output ->
                resp.body?.byteStream()?.copyTo(output)
            }
        }
    }

    val pages = mutableListOf<Bitmap>()
    val pfd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfRenderer(pfd).use { renderer ->
        for (i in 0 until renderer.pageCount) {
            renderer.openPage(i).use { page ->
                // 3x 分辨率渲染,缩放后仍清晰
                val renderScale = 3
                val width = page.width * renderScale
                val height = page.height * renderScale
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pages.add(bitmap)
            }
        }
    }
    pfd.close()
    return pages
}
