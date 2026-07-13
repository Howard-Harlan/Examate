package go.examate.hyp.campus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
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
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

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
    var detailId by remember { mutableStateOf("") }
    BackHandler(enabled = detailId.isNotEmpty()) { detailId = "" }

    LaunchedEffect(Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { repo.hsxsLogin(prefs) }; loggedIn = true }
            catch (e: Exception) { loginError = e.message ?: "登录失败" }
        }
    }

    CampusPageScaffold(title = "淮师心声", onBack = onBack, bottomBar = {
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HsxsTab.entries.forEach { t ->
                    val active = t == tab
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (active) Primary else Color.Transparent)
                        .clickable { tab = t; detailId = "" }.padding(vertical = 8.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(t.label, color = if (active) Color.White else TextHint, fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }) {
        if (!loggedIn) LoadingBlock()
        else if (loginError.isNotEmpty()) Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ErrorBlock(loginError)
                TextButton(onClick = { loggedIn = false; loginError = "" }) { Text("重试", color = Primary) }
            }
        }
        else when (tab) {
            HsxsTab.LOST_FOUND -> LostFoundSection(repo, prefs, scope, detailId) { detailId = it }
            HsxsTab.SURVEY -> SurveySection(repo, prefs, scope, detailId) { detailId = it }
        }
    }
}

// ═══════════════ 失物招领 ═══════════════
@Composable
private fun LostFoundSection(repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, detailId: String, onDetail: (String) -> Unit
) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showPublish by remember { mutableStateOf(false) }

    fun load() { scope.launch {
        loading = true; error = ""
        try {
            items = withContext(Dispatchers.IO) { parseDataArray(repo.getLostList(prefs)) }; loading = false
        } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
    }}
    LaunchedEffect(Unit) { load() }

    if (detailId.isNotEmpty()) { LostDetailView(detailId, repo, prefs, scope) { onDetail("") }; return }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            when { loading -> LoadingBlock(); error.isNotEmpty() -> ErrorBlock(error); items.isEmpty() -> EmptyBlock("暂无失物招领信息")
                else -> items.forEach { item -> Spacer(Modifier.height(10.dp)); LostItemCard(item) { onDetail(item.optString("id", "")) } } }
            Spacer(Modifier.height(80.dp))
        }
        FloatingActionButton(onClick = { showPublish = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = Primary
        ) { Text("＋", color = Color.White, fontSize = 20.sp) }
    }
    if (showPublish) { PublishLostDialog(repo, prefs, scope) { showPublish = false; load() } }
}

@Composable
private fun LostItemCard(item: JSONObject, onClick: () -> Unit) {
    val content = item.optString("content", "").take(100)
    val typeNum = item.optInt("type", 1)
    val typeLabel = if (typeNum == 2) "招领" else "寻物"
    val typeColor = if (typeNum == 2) Color(0xFF1D9E75) else Color(0xFFE24B4A)
    val time = item.optString("createtime", "")
    val campus = item.optJSONObject("organise_info")?.optString("name", "") ?: ""
    val imagesArr = item.optJSONArray("images_arr")
    val firstImg = if (imagesArr != null && imagesArr.length() > 0) imagesArr.optString(0) else ""

    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(typeLabel, color = typeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(4.dp))
                    Text(content, color = TextPrimary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (firstImg.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(fixImgUrl(firstImg)).crossfade(true).build(),
                        contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
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
private fun LostDetailView(id: String, repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit
) {
    var item by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        scope.launch {
            try { item = withContext(Dispatchers.IO) { repo.getLostDetail(prefs, id) }; loading = false }
            catch (_: Exception) { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("← 返回", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        if (loading) LoadingBlock()
        else if (item == null) Text("加载失败", color = TextHint)
        else {
            val it = item!!
            Text(it.optString("content", ""), color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            InfoRow("校区", it.optJSONObject("organise_info")?.optString("name", "") ?: "")
            InfoRow("部门", it.optJSONObject("department_info")?.optString("name", "") ?: "")
            InfoRow("联系人", it.optJSONObject("user_info")?.optString("user_name", "") ?: "")
            InfoRow("电话", it.optJSONObject("user_info")?.optString("user_phone", "") ?: "")
            InfoRow("时间", it.optString("createtime", ""))
            val imgs = it.optJSONArray("images_arr")
            if (imgs != null && imgs.length() > 0) {
                Spacer(Modifier.height(12.dp))
                for (i in 0 until imgs.length()) {
                    AsyncImage(model = fixImgUrl(imgs.optString(i)), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$label: ", color = TextHint, fontSize = 12.sp)
        Text(value, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun PublishLostDialog(repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit
) {
    var type by remember { mutableStateOf("1") }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!submitting) onDone() }) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("发布失物招领", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("1" to "寻物", "2" to "招领").forEach { (v, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { type = v }) {
                            RadioButton(selected = type == v, onClick = { type = v })
                            Text(l, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("描述内容") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 5)
                Spacer(Modifier.height(6.dp))
                Text("⚠ 图片上传暂未支持,请在网页端添加", color = TextHint, fontSize = 11.sp)
                if (result.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(result, color = if (result.contains("成功")) Color(0xFF1D9E75) else Color(0xFFE24B4A), fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (!submitting) onDone() }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (content.isBlank()) { result = "请填写内容"; return@Button }
                        submitting = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { repo.publishLost(prefs, mapOf("content" to content, "type" to type)) }
                                result = "发布成功"; onDone()
                            } catch (e: Exception) { result = "发布失败: ${e.message}"; submitting = false }
                        }
                    }, enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text(if (submitting) "发布中..." else "发布", color = Color.White) }
                }
            }
        }
    }
}

// ═══════════════ 满意度调查 ═══════════════
@Composable
private fun SurveySection(repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, detailId: String, onDetail: (String) -> Unit
) {
    var surveys by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            try { surveys = withContext(Dispatchers.IO) { parseDataArray(repo.getQuestionList(prefs)) }; loading = false }
            catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
        }
    }

    if (detailId.isNotEmpty()) { SurveyDetailView(detailId, repo, prefs, scope) { onDetail("") }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        when { loading -> LoadingBlock(); error.isNotEmpty() -> ErrorBlock(error); surveys.isEmpty() -> EmptyBlock("暂无满意度调查")
            else -> surveys.forEach { survey -> Spacer(Modifier.height(10.dp)); SurveyCard(survey) { onDetail(survey.optString("id", "")) } } }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SurveyCard(survey: JSONObject, onClick: () -> Unit) {
    val title = survey.optString("title", "")
    val desc = survey.optString("desc", "").take(80)
    val endTime = survey.optString("endtime", "")
    val coverUrl = survey.optString("cover_text", "").ifEmpty { survey.optString("cover", "") }
    val dept = survey.optJSONObject("department_info")?.optString("name", "")

    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (coverUrl.isNotEmpty()) {
                AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(fixImgUrl(coverUrl)).crossfade(true).build(),
                    contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
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
private fun SurveyDetailView(id: String, repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit
) {
    var survey by remember { mutableStateOf<JSONObject?>(null) }
    var questions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var answers by remember { mutableStateOf<MutableMap<Int, Any>>(mutableMapOf()) }
    var submitting by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        scope.launch {
            try {
                survey = withContext(Dispatchers.IO) { repo.getQuestionDetail(prefs, id) }.optJSONObject("data")
                questions = parseDataArray(withContext(Dispatchers.IO) { repo.getSurveyQuestions(prefs, id) })
                loading = false
            } catch (_: Exception) { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("← 返回", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(vertical = 10.dp))
        if (loading) LoadingBlock()
        else if (survey == null) Text("加载失败", color = TextHint)
        else {
            val s = survey!!
            val coverUrl = s.optString("cover_text", "").ifEmpty { s.optString("cover", "") }
            if (coverUrl.isNotEmpty()) {
                AsyncImage(model = fixImgUrl(coverUrl), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth)
                Spacer(Modifier.height(12.dp))
            }
            Text(s.optString("title", ""), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(s.optString("desc", ""), color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))

            // 问卷题目
            questions.forEachIndexed { idx, q ->
                val qType = q.optInt("type", 1)
                val qTitle = q.optString("title", "")
                val qId = q.optInt("id", idx)
                val contentData = q.optJSONObject("content_data")
                val options = mutableListOf<Pair<String, String>>()
                contentData?.keys()?.forEach { key ->
                    val obj = contentData.optJSONObject(key)
                    if (obj != null) options.add(obj.optString("name", "") to key)
                }
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${idx + 1}. $qTitle", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        if (qType == 1) {
                            // 单选
                            options.forEach { (label, key) ->
                                Row(Modifier.fillMaxWidth().clickable { answers = answers.toMutableMap().also { it[qId] = key.toIntOrNull() ?: key } }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = answers[qId]?.toString() == key, onClick = { answers = answers.toMutableMap().also { it[qId] = key.toIntOrNull() ?: key } })
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        } else if (qType == 2) {
                            // 多选
                            val selected = (answers[qId] as? List<*>)?.map { it.toString() } ?: emptyList()
                            options.forEach { (label, key) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    val newList = selected.toMutableList()
                                    if (newList.contains(key)) newList.remove(key) else newList.add(key)
                                    answers = answers.toMutableMap().also { it[qId] = newList }
                                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = selected.contains(key), onCheckedChange = {
                                        val newList = selected.toMutableList()
                                        if (it) newList.add(key) else newList.remove(key)
                                        answers = answers.toMutableMap().also { a -> a[qId] = newList }
                                    })
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (resultMsg.isNotEmpty()) { Text(resultMsg, color = if (resultMsg.contains("成功")) Color(0xFF1D9E75) else Color(0xFFE24B4A), fontSize = 12.sp); Spacer(Modifier.height(8.dp)) }
            Button(onClick = {
                submitting = true
                scope.launch {
                    try {
                        val ansJson = JSONObject()
                        answers.forEach { (qId, v) ->
                            ansJson.put(qId.toString(), if (v is List<*>) JSONArray(v) else v)
                        }
                        withContext(Dispatchers.IO) { repo.submitAnswer(prefs, id, ansJson) }
                        resultMsg = "提交成功"
                    } catch (e: Exception) { resultMsg = "提交失败: ${e.message}" }
                    submitting = false
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !submitting && questions.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text(if (submitting) "提交中..." else "提交答案", color = Color.White, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** 解析 API 返回的 data.data 数组 */
private fun parseDataArray(json: JSONObject): List<JSONObject> {
    val arr = json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

private fun fixImgUrl(url: String): String = when {
    url.startsWith("http") -> url
    url.startsWith("/") -> "$IMG_BASE$url"
    else -> url
}
