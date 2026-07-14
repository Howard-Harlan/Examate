package go.examate.hyp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    AboutScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val currentVersion = go.examate.hyp.BuildConfig.VERSION_NAME

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // 顶栏:返回 + 居中标题
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
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
                "关于",
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
                .padding(top = 16.dp, bottom = 60.dp)
        ) {
        Spacer(Modifier.height(24.dp))

        // ═══ 应用信息 ═══
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo — 使用应用图标前景(VectorDrawable,兼容painterResource)
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = go.examate.hyp.R.drawable.ic_launcher_foreground),
                        contentDescription = "logo",
                        modifier = Modifier.size(72.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Examate", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("版本 $currentVersion", color = TextHint, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ═══ 检查更新 ═══
        LiquidGlassButton(
            text = if (checking) "检查中..." else "检查更新",
            onClick = {
                if (checking) return@LiquidGlassButton
                checking = true
                scope.launch {
                    updateResult = UpdateChecker.checkUpdate(currentVersion)
                    checking = false
                    showUpdateDialog = true
                }
            },
            style = ButtonStyle.White,
            height = 44.dp
        )

        Spacer(Modifier.height(20.dp))

        // ═══ 开源技术 ═══
        Text(
            "开源技术",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                openSourceLibs.forEachIndexed { index, lib ->
                    if (index > 0) {
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(lib.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(lib.description, color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                        }
                        Text(lib.license, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        } // 内层 Column
    }

    // 更新检查结果弹窗
    if (showUpdateDialog && updateResult != null) {
        when (val result = updateResult!!) {
            is UpdateChecker.Result.NewVersion -> {
                PlainDialog(onDismissRequest = { showUpdateDialog = false }) {
                    PlainDialogTitle("发现新版本")
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text("当前版本: ${result.currentVersion}", color = TextHint, fontSize = 13.sp)
                        Text("最新版本: ${result.latestVersion}", color = TextHint, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("更新内容", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(result.releaseNotes, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    PlainDialogButtons(
                        listOf(
                            Triple("下载更新", true) {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                showUpdateDialog = false
                            },
                            Triple("稍后", false) { showUpdateDialog = false }
                        )
                    )
                }
            }
            is UpdateChecker.Result.UpToDate -> {
                PlainDialog(onDismissRequest = { showUpdateDialog = false }) {
                    PlainDialogTitle("已是最新版本")
                    PlainDialogContent("当前版本 $currentVersion 已是最新")
                    PlainDialogButtons(
                        listOf(Triple("确定", true) { showUpdateDialog = false })
                    )
                }
            }
            is UpdateChecker.Result.Error -> {
                PlainDialog(onDismissRequest = { showUpdateDialog = false }) {
                    PlainDialogTitle("检查失败")
                    PlainDialogContent(result.message)
                    PlainDialogButtons(
                        listOf(Triple("确定", true) { showUpdateDialog = false })
                    )
                }
            }
        }
    }
}

/** 开源技术清单 */
private data class OpenSourceLib(
    val name: String,
    val description: String,
    val license: String
)

private val openSourceLibs = listOf(
    OpenSourceLib("Jetpack Compose", "Android 现代声明式 UI 框架", "Apache 2.0"),
    OpenSourceLib("OkHttp", "HTTP 网络请求客户端", "Apache 2.0"),
    OpenSourceLib("Kotlin Coroutines", "异步编程与协程", "Apache 2.0"),
    OpenSourceLib("Shizuku", "免 Root 特权操作框架", "Apache 2.0"),
    OpenSourceLib("AndroidX Lifecycle", "生命周期感知组件与 ViewModel", "Apache 2.0"),
    OpenSourceLib("Material Components", "Material Design 组件库", "Apache 2.0"),
    OpenSourceLib("KaTeX", "LaTeX 数学公式渲染(WebView)", "MIT"),
    OpenSourceLib("Backdrop Liquid Glass", "液态玻璃效果组件", "Apache 2.0")
)
