package go.examate.hyp.campus.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.WebViewActivity
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 快捷服务页:卡片选择式入口,聚合无需登录的轻量功能。
 *
 * 当前包含:
 * - 校历:应用内浏览器打开教务处校历下载页
 *
 * 后续可继续在此追加快捷功能卡片,结构与校园服务主页一致。
 */
@Composable
fun QuickServiceScreen(onBack: () -> Unit, onClassContact: () -> Unit = {}) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "快捷服务",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        // 卡片列表
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            QuickServiceEntryCard(
                title = "校历",
                onClick = {
                    context.startActivity(
                        Intent(context, WebViewActivity::class.java).apply {
                            putExtra(WebViewActivity.EXTRA_URL, "https://jwc.chnu.edu.cn/xzzq/sdxl")
                            putExtra(WebViewActivity.EXTRA_TITLE, "校历")
                        }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            QuickServiceEntryCard(
                title = "班级通讯录",
                onClick = onClassContact
            )
        }
    }
}

/** 快捷服务入口卡片(与校园服务主页 FeatureEntryCard 样式一致) */
@Composable
private fun QuickServiceEntryCard(
    title: String,
    onClick: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}
