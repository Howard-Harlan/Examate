package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.R
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val IMG_BASE = "https://hsxs.chnu.edu.cn"

private enum class HsxsTab(val label: String) { LOST_FOUND("失物招领"), SURVEY("满意度调查") }

@Composable
fun HsxsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(HsxsTab.LOST_FOUND) }
    var loggedIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { repo.hsxsLogin(prefs) }; loggedIn = true }
            catch (e: Exception) { loginError = e.message ?: "登录失败" }
        }
    }

    CampusPageScaffold(title = "淮师心声", onBack = onBack, bottomBar = {
        Spacer(Modifier.height(10.dp))
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HsxsTab.entries.forEach { t ->
                    val active = t == tab
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (active) Primary else Color.Transparent)
                        .clickable { tab = t }.padding(vertical = 8.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(t.label, color = if (active) Color.White else TextHint, fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }) {
        if (!loggedIn && loginError.isEmpty()) LoadingBlock()
        else if (loginError.isNotEmpty()) Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ErrorBlock(loginError)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { loggedIn = false; loginError = "" }) { Text("重试", color = Primary) }
            }
        }
        else when (tab) {
            HsxsTab.LOST_FOUND -> LostFoundSection(repo, prefs, scope)
            HsxsTab.SURVEY -> SurveySection(repo, prefs, scope)
        }
    }
}

// ═══════════════ 失物招领 ═══════════════
@Composable
private fun LostFoundSection(repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<JSONObject?>(null) }

    fun load() { scope.launch {
        loading = true; error = ""
        try {
            val resp = withContext(Dispatchers.IO) { repo.getLostList(prefs) }
            val arr = resp.optJSONObject("data")?.optJSONArray("data") ?: resp.optJSONArray("data")
            val list = mutableListOf<JSONObject>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            items = list; loading = false
        } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
    }}
    LaunchedEffect(Unit) { load() }

    if (selectedItem != null) { LostDetailView(selectedItem!!) { selectedItem = null }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("失物招领", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> ErrorBlock(error)
            items.isEmpty() -> EmptyBlock("暂无失物招领信息")
            else -> items.forEach { item -> Spacer(Modifier.height(10.dp)); LostItemCard(item) { selectedItem = item } }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LostItemCard(item: JSONObject, onClick: () -> Unit) {
    val content = item.optString("content", "无描述").take(100)
    val typeNum = item.optInt("type", 1)
    val typeLabel = if (typeNum == 2) "招领" else "寻物"
    val typeColor = if (typeNum == 2) Color(0xFF1D9E75) else Color(0xFFE24B4A)
    val time = item.optString("createtime", "")
    val campus = item.optJSONObject("organise_info")?.optString("name", "") ?: ""
    val userName = item.optJSONObject("user_info")?.optString("user_name", "") ?: ""
    val imagesArr = item.optJSONArray("images_arr")
    val firstImg = if (imagesArr != null && imagesArr.length() > 0) imagesArr.optString(0) else ""
    val status = item.optString("status_text", "")

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(typeLabel, color = typeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        if (status.isNotEmpty()) Text(status, color = TextHint, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(content, color = TextPrimary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (firstImg.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F2F5)), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                            tint = TextHint.copy(0.4f), modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (campus.isNotEmpty()) "📍 $campus" else "", color = TextHint, fontSize = 11.sp)
                Text(time, color = TextHint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LostDetailView(item: JSONObject, onBack: () -> Unit) {
    val content = item.optString("content", "")
    val typeNum = item.optInt("type", 1)
    val campus = item.optJSONObject("organise_info")?.optString("name", "") ?: ""
    val dept = item.optJSONObject("department_info")?.optString("name", "") ?: ""
    val userName = item.optJSONObject("user_info")?.optString("user_name", "") ?: ""
    val userPhone = item.optJSONObject("user_info")?.optString("user_phone", "") ?: ""
    val time = item.optString("createtime", "")
    val imagesArr = item.optJSONArray("images_arr")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("← 返回", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        Text(content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        if (campus.isNotEmpty()) InfoRow("校区", campus)
        if (dept.isNotEmpty()) InfoRow("部门", dept)
        if (userName.isNotEmpty()) InfoRow("联系人", userName)
        if (userPhone.isNotEmpty()) InfoRow("电话", userPhone)
        InfoRow("时间", time)
        if (imagesArr != null && imagesArr.length() > 0) {
            Spacer(Modifier.height(12.dp))
            Text("📷 包含 ${imagesArr.length()} 张图片", color = TextHint, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F2F5)).padding(12.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$label: ", color = TextHint, fontSize = 12.sp)
        Text(value, color = TextSecondary, fontSize = 12.sp)
    }
}

// ═══════════════ 满意度调查 ═══════════════
@Composable
private fun SurveySection(repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope) {
    var surveys by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { repo.getQuestionList(prefs) }
                val arr = resp.optJSONObject("data")?.optJSONArray("data") ?: resp.optJSONArray("data")
                val list = mutableListOf<JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                surveys = list; loading = false
            } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("满意度调查", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> ErrorBlock(error)
            surveys.isEmpty() -> EmptyBlock("暂无满意度调查")
            else -> surveys.forEach { survey ->
                Spacer(Modifier.height(10.dp))
                SurveyCard(survey)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SurveyCard(survey: JSONObject) {
    val title = survey.optString("title", "")
    val desc = survey.optString("desc", "").take(80)
    val endTime = survey.optString("endtime", "")
    val coverUrl = survey.optString("cover_text", "").ifEmpty {
        survey.optString("cover", "").let { if (it.startsWith("/")) "$IMG_BASE$it" else it }
    }
    val dept = survey.optJSONObject("department_info")?.optString("name", "")

    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl.isNotEmpty()) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Primary.copy(0.1f)), contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                        tint = Primary.copy(0.5f), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (desc.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(desc, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (dept != null && dept.isNotEmpty()) Text(dept, color = TextHint, fontSize = 11.sp)
                    if (endTime.isNotEmpty()) Text("截止 $endTime", color = TextHint, fontSize = 11.sp)
                }
            }
        }
    }
}
