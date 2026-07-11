package go.examate.hyp

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 60.dp)
    ) {
        // 顶栏:返回 + 标题
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = TextPrimary, fontSize = 22.sp)
            }
            Spacer(Modifier.size(12.dp))
            Text("关于", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))

        // ═══ 应用信息 ═══
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo
                Box(
                    Modifier
                        .size(72.dp)
                        .background(Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("E", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text("Examate", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("版本 $currentVersion", color = TextHint, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("ex a mate · 超越一个朋友", color = TextSecondary, fontSize = 12.sp)
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
                        Divider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
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

        // ═══ 说明 ═══
        Text(
            "说明",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Examate — ex a mate,超越一个朋友。\n\n" +
                            "名字取自 \"ex a mate\",寓意不只是工具,更是一个超越普通朋友的智能学习伙伴。\n\n" +
                            "本应用为独立开发项目,集成了多种 AI 能力与校园服务功能。尊重用户隐私,所有数据均存储在本地,不会上传至任何服务器。" +
                            "AI 对话功能需要用户自行配置 API 密钥,密钥仅保存在本地。\n\n" +
                            "如遇问题或有建议,欢迎反馈。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }

    // 更新检查结果弹窗
    if (showUpdateDialog && updateResult != null) {
        when (val result = updateResult!!) {
            is UpdateChecker.Result.NewVersion -> {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("发现新版本") },
                    text = {
                        Column {
                            Text("当前版本: ${result.currentVersion}")
                            Text("最新版本: ${result.latestVersion}")
                            Spacer(Modifier.height(8.dp))
                            Text("更新内容:", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(result.releaseNotes, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            // 打开下载链接(浏览器)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            showUpdateDialog = false
                        }) {
                            Text("下载更新")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("稍后")
                        }
                    }
                )
            }
            is UpdateChecker.Result.UpToDate -> {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("已是最新版本") },
                    text = { Text("当前版本 $currentVersion 已是最新") },
                    confirmButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("确定")
                        }
                    }
                )
            }
            is UpdateChecker.Result.Error -> {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("检查失败") },
                    text = { Text(result.message) },
                    confirmButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("确定")
                        }
                    }
                )
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
