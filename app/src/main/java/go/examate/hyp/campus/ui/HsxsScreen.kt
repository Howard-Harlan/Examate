package go.examate.hyp.campus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

    // 导航: null=列表, JSONObject=详情
    var detailItem by remember { mutableStateOf<JSONObject?>(null) }

    // 系统返回键处理: 详情→列表, 列表→校园服务
    BackHandler(enabled = detailItem != null) { detailItem = null }

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
                        .clickable { tab = t; detailItem = null }.padding(vertical = 8.dp), contentAlignment = Alignment.Center
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
            HsxsTab.LOST_FOUND -> LostFoundSection(repo, prefs, scope, detailItem) { detailItem = it }
            HsxsTab.SURVEY -> SurveySection(repo, prefs, scope, detailItem) { detailItem = it }
        }
    }
}

// ═══════════════ 失物招领 ═══════════════
@Composable
private fun LostFoundSection(
    repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope,
    detailItem: JSONObject?, onDetail: (JSONObject?) -> Unit
) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showPublish by remember { mutableStateOf(false) }

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

    // 详情页
    if (detailItem != null) {
        LostDetailView(detailItem) { onDetail(null) }
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            when {
                loading -> LoadingBlock()
                error.isNotEmpty() -> ErrorBlock(error)
                items.isEmpty() -> EmptyBlock("暂无失物招领信息")
                else -> items.forEach { item -> Spacer(Modifier.height(10.dp)); LostItemCard(item) { onDetail(item) } }
            }
            Spacer(Modifier.height(80.dp)) // 给 FAB 留空间
        }

        // 发布按钮(FAB)
        FloatingActionButton(
            onClick = { showPublish = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Primary
        ) { Text("＋", color = Color.White, fontSize = 20.sp) }
    }

    if (showPublish) {
        PublishLostDialog(repo, prefs, scope) {
            showPublish = false
            load()
        }
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
    val imagesArr = item.optJSONArray("images_arr")
    val firstImg = if (imagesArr != null && imagesArr.length() > 0) imagesArr.optString(0) else ""

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(typeLabel, color = typeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(content, color = TextPrimary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (firstImg.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(fixImgUrl(firstImg)).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
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
            for (i in 0 until imagesArr.length()) {
                val imgUrl = imagesArr.optString(i)
                if (imgUrl.isNotEmpty()) {
                    AsyncImage(
                        model = fixImgUrl(imgUrl),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
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

// ═══════════════ 发布弹窗 ═══════════════
@Composable
private fun PublishLostDialog(repo: CampusRepository, prefs: PrefsManager,
                               scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    var type by remember { mutableStateOf("1") }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!submitting) onDone() }) {
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("发布失物招领", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("1" to "寻物", "2" to "招领").forEach { (v, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { type = v }) {
                            RadioButton(selected = type == v, onClick = { type = v })
                            Text(l, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("描述内容") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 6)
                if (result.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(result, color = if (result.contains("成功")) Color(0xFF1D9E75) else Color(0xFFE24B4A), fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (!submitting) onDone() }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (content.isBlank()) { result = "请填写内容"; return@Button }
                            submitting = true
                            scope.launch {
                                try {
                                    val body = mutableMapOf("content" to content, "type" to type)
                                    withContext(Dispatchers.IO) { repo.publishLost(prefs, body) }
                                    result = "发布成功"; onDone()
                                } catch (e: Exception) { result = "发布失败: ${e.message}"; submitting = false }
                            }
                        },
                        enabled = !submitting,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text(if (submitting) "发布中..." else "发布", color = Color.White) }
                }
            }
        }
    }
}

// ═══════════════ 满意度调查 ═══════════════
@Composable
private fun SurveySection(
    repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope,
    detailItem: JSONObject?, onDetail: (JSONObject?) -> Unit
) {
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

    // 详情页
    if (detailItem != null) {
        SurveyDetailView(detailItem, repo, prefs, scope) { onDetail(null) }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> ErrorBlock(error)
            surveys.isEmpty() -> EmptyBlock("暂无满意度调查")
            else -> surveys.forEach { survey ->
                Spacer(Modifier.height(10.dp))
                SurveyCard(survey) { onDetail(survey) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SurveyCard(survey: JSONObject, onClick: () -> Unit) {
    val title = survey.optString("title", "")
    val desc = survey.optString("desc", "").take(80)
    val endTime = survey.optString("endtime", "")
    val coverUrl = survey.optString("cover_text", "").ifEmpty {
        survey.optString("cover", "").let { if (it.startsWith("/")) "$IMG_BASE$it" else it }
    }
    val dept = survey.optJSONObject("department_info")?.optString("name", "")

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(fixImgUrl(coverUrl)).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
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

@Composable
private fun SurveyDetailView(survey: JSONObject, repo: CampusRepository, prefs: PrefsManager,
                              scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit) {
    val title = survey.optString("title", "")
    val desc = survey.optString("desc", "")
    val endTime = survey.optString("endtime", "")
    val dept = survey.optJSONObject("department_info")?.optString("name", "") ?: ""
    val coverUrl = survey.optString("cover_text", "").ifEmpty {
        survey.optString("cover", "").let { if (it.startsWith("/")) "$IMG_BASE$it" else it }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("← 返回", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        if (coverUrl.isNotEmpty()) {
            AsyncImage(model = fixImgUrl(coverUrl), contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.FillWidth)
            Spacer(Modifier.height(12.dp))
        }
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (desc.isNotEmpty()) { Text(desc, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp) }
        Spacer(Modifier.height(10.dp))
        if (dept.isNotEmpty()) InfoRow("部门", dept)
        if (endTime.isNotEmpty()) InfoRow("截止时间", endTime)
        Spacer(Modifier.height(16.dp))
        // TODO: 问卷题目需要 getQuestionDetail API 返回题目列表后再实现答题UI
        Text("问卷详情加载中...", color = TextHint, fontSize = 12.sp)
        Spacer(Modifier.height(30.dp))
    }
}

/** 处理图片URL: 相对路径拼基础域名,完整URL直接使用 */
private fun fixImgUrl(url: String): String = when {
    url.startsWith("http") -> url
    url.startsWith("/") -> "$IMG_BASE$url"
    else -> url
}
