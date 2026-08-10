package go.examate.hyp.campus.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.WebViewActivity
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.SzsScoreItem
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智慧思政页面 — 功能列表入口。
 *
 * 子功能:
 * 1. 综合测评成绩明细(jgf 系统,已验证可用)
 * 2. 综测测评(hbsz-h5 系统,对应 oshall/hbsz-h5/evaluating)
 * 3. 离返校登记(hbsz-h5,对应 ?path=holidayLeave)
 * 4. 寒暑假留校登记(hbsz-h5,对应 ?path=stayInSchool)
 */
@Composable
fun SzsScreen(
    onBack: () -> Unit,
    onScore: () -> Unit,
    onEvaluating: () -> Unit = {},
    onHolidayLeave: () -> Unit = {},
    onStayInSchool: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadingToken by remember { androidx.compose.runtime.mutableStateOf(false) }

    /** 打开 hbsz-h5 页面:先获取 JWT token,再通过 WebView 打开(双保险认证) */
    fun openHbszPage(url: String, title: String) {
        val repo = CampusRepository.getInstance()
        if (!repo.isLoggedIn) {
            ToastUtil.show(context, "请先登录校园服务")
            return
        }
        if (loadingToken) return
        loadingToken = true
        scope.launch {
            // 获取 JWT token(智慧思政 H5 基于 Jeecg-Boot,需要 X-Access-Token)
            val token = repo.getHbszToken()
            // 同时注入 CAS cookie(双保险:cookie 用于 CAS SSO 跳转,token 用于 H5 API 调用)
            repo.injectCookiesToWebView()
            loadingToken = false
            // 直接打开目标 H5 URL(不走 CAS service 中转,避免重定向链断裂)
            // token 已通过 WebView 注入到 localStorage,H5 前端可直接读取
            context.startActivity(Intent(context, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
                putExtra(WebViewActivity.EXTRA_TITLE, title)
                if (!token.isNullOrEmpty()) {
                    putExtra(WebViewActivity.EXTRA_INJECT_TOKEN, token)
                    putExtra(WebViewActivity.EXTRA_INJECT_HOST, "oshall.chnu.edu.cn")
                }
            })
        }
    }

    CampusPageScaffold(title = "智慧思政", onBack = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SzsFeatureCard(
                title = "综合测评成绩",
                subtitle = "德育 / 专业 / 体育等成绩明细",
                onClick = onScore
            )
            SzsFeatureCard(
                title = "综测测评",
                subtitle = "综测批次及得分查询",
                onClick = {
                    openHbszPage("https://oshall.chnu.edu.cn/hbsz-h5/evaluating", "综测测评")
                }
            )
            SzsFeatureCard(
                title = "离返校登记",
                subtitle = "假期离校 / 返校登记记录",
                onClick = {
                    openHbszPage("https://oshall.chnu.edu.cn/hbsz-h5?path=holidayLeave", "离返校登记")
                }
            )
            SzsFeatureCard(
                title = "寒暑假留校登记",
                subtitle = "留校申请及审批记录",
                onClick = {
                    openHbszPage("https://oshall.chnu.edu.cn/hbsz-h5?path=stayInSchool", "留校登记")
                }
            )
        }
    }
}

/** 智慧思政功能入口卡片 */
@Composable
private fun SzsFeatureCard(title: String, subtitle: String, onClick: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(subtitle, color = TextHint, fontSize = 12.sp)
                }
            }
            Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}

// ════════════════════════════════════════
//  综合测评成绩明细(jgf 系统,原有实现)
// ════════════════════════════════════════

/**
 * 综合测评成绩明细页面(jgf 系统)。
 * 数据包含:德育/专业/体育成绩,劳动教育,体质等多项指标。
 */
@Composable
fun SzsScoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<SzsScoreItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            items = withContext(Dispatchers.IO) { repo.getChangeScore(prefs) }
            if (items.isEmpty()) {
                error = "暂无综合测评成绩数据"
            }
        } catch (e: CampusException) {
            error = e.message ?: "加载失败"
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
        }
        loading = false
    }

    CampusPageScaffold(title = "综合测评成绩", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (loading) {
                LoadingBlock("正在查询综合测评成绩...")
            } else if (error.isNotEmpty()) {
                ErrorBlock(error)
            } else {
                items.forEach { item -> ScoreDetailCard(item); Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 综合测评成绩卡片:学生信息 + 各项成绩 */
@Composable
private fun ScoreDetailCard(item: SzsScoreItem) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // 测评批次
            Text(item.testBatchName.ifEmpty { "综合测评成绩" },
                color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            // 学生信息
            Text("${item.studentName} · ${item.className}",
                color = TextHint, fontSize = 12.sp)
            if (item.college.isNotEmpty() || item.major.isNotEmpty()) {
                Text("${item.college} · ${item.major}", color = TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))

            // 成绩项(3列网格布局)
            val scores = listOf(
                "德育" to item.dyScore,
                "专业" to item.zyScore,
                "体育" to item.tyScore,
                "劳动教育" to item.nlfzEdu,
                "体质" to item.tzEdu,
                "应购书本" to item.ygpbEdu,
                "党员科技" to item.dykjScore,
                "党员申报" to item.dyDeclareScore
            ).filter { it.second.isNotEmpty() && it.second != "0" && it.second != "0.0" && it.second != "0.00" }

            scores.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (label, value) ->
                        ScoreCell(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    // 补齐3列
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 单个成绩单元格 */
@Composable
private fun ScoreCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = AccentBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

// ════════════════════════════════════════
//  综测测评/离返校/留校登记 — 改用 WebView 打开(注入 CAS cookie 实现 SSO)
//  原 native 实现已移除,避免 API 路径猜测导致的登录失败问题
// ════════════════════════════════════════
