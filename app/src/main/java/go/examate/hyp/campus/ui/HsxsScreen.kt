package go.examate.hyp.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

// ════════════════════════════════════════════════
//  淮师心声 — 失物招领 & 满意度调查
// ════════════════════════════════════════════════

private enum class HsxsTab(val label: String) {
    LOST_FOUND("失物招领"), SURVEY("满意度调查")
}

@Composable
fun HsxsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(HsxsTab.LOST_FOUND) }

    CampusPageScaffold(
        title = "淮师心声",
        onBack = onBack,
        bottomBar = {
            Spacer(Modifier.height(10.dp))
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HsxsTab.entries.forEach { t ->
                        val active = t == tab
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (active) Primary else Color.Transparent)
                                .clickable { tab = t }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(t.label, color = if (active) Color.White else TextHint,
                                fontSize = 12.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    ) {
        when (tab) {
            HsxsTab.LOST_FOUND -> LostFoundSection(repo, prefs, scope)
            HsxsTab.SURVEY -> SurveySection(repo, prefs, scope)
        }
    }
}

// ════════════════════════════════════════════════
//  失物招领
// ════════════════════════════════════════════════

@Composable
private fun LostFoundSection(repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showPublish by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.getLostList(prefs) }
                val rows = r.optJSONArray("rows") ?: r.optJSONArray("data")
                val list = mutableListOf<JSONObject>()
                if (rows != null) for (i in 0 until rows.length()) list.add(rows.getJSONObject(i))
                items = list; loading = false
            } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("失物招领", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("＋ 发布", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { showPublish = true }.padding(4.dp))
        }
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> { ErrorBlock(error); Spacer(Modifier.height(8.dp)); }
            items.isEmpty() -> EmptyBlock("暂无失物招领信息")
            else -> items.forEach { item -> LostItemCard(item); Spacer(Modifier.height(10.dp)) }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showPublish) {
        PublishLostDialog(repo, prefs, scope) {
            showPublish = false
            loading = true; error = ""
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { repo.getLostList(prefs) }
                    val rows = r.optJSONArray("rows") ?: r.optJSONArray("data")
                    val list = mutableListOf<JSONObject>()
                    if (rows != null) for (i in 0 until rows.length()) list.add(rows.getJSONObject(i))
                    items = list; loading = false
                } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
            }
        }
    }
}

@Composable
private fun LostItemCard(item: JSONObject) {
    val title = item.optString("title", "无标题")
    val type = item.optString("type", "")
    val typeLabel = if (type == "lost") "寻物" else "招领"
    val typeColor = if (type == "lost") Color(0xFFE24B4A) else Color(0xFF1D9E75)
    val desc = item.optString("description", "").take(80)
    val location = item.optString("location", "")
    val time = item.optString("publishTime", item.optString("createTime", ""))
    val contact = item.optString("contactName", item.optString("contactPhone", ""))

    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(typeLabel, color = typeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(typeColor.copy(0.1f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (desc.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(desc, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (location.isNotEmpty() || contact.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (location.isNotEmpty()) Text("📍 $location", color = TextHint, fontSize = 11.sp)
                    if (contact.isNotEmpty()) Text(contact, color = TextHint, fontSize = 11.sp)
                }
            }
            if (time.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(time, color = TextHint, fontSize = 10.sp) }
        }
    }
}

// ════════════════════════════════════════════════
//  发布失物招领弹窗
// ════════════════════════════════════════════════

@Composable
private fun PublishLostDialog(repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    var type by remember { mutableStateOf("lost") }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { if (!submitting) onDone() }) {
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("发布失物招领", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("lost" to "寻物", "found" to "招领").forEach { (v, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { type = v }) {
                            RadioButton(selected = type == v, onClick = { type = v })
                            Text(l, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("物品分类") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("详细描述") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 4)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = contactName, onValueChange = { contactName = it }, label = { Text("联系人") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = contactPhone, onValueChange = { contactPhone = it }, label = { Text("联系电话") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                if (result.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(result, color = if (result.contains("成功")) Color(0xFF1D9E75) else Color(0xFFE24B4A), fontSize = 12.sp) }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (!submitting) onDone() }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isBlank()) { result = "请填写标题"; return@Button }
                            submitting = true
                            scope.launch {
                                try {
                                    val r = withContext(Dispatchers.IO) { repo.publishLost(prefs, title, type, category, description, location, contactName, contactPhone) }
                                    val msg = r.optString("msg", r.optString("message", ""))
                                    if (msg.contains("成功") || r.optString("code", "") == "0") { result = "发布成功"; onDone() }
                                    else result = msg.ifEmpty { "发布失败" }
                                } catch (e: Exception) { result = "发布失败: ${e.message}" }
                                submitting = false
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

// ════════════════════════════════════════════════
//  满意度调查
// ════════════════════════════════════════════════

@Composable
private fun SurveySection(repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope) {
    var surveys by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selectedSurvey by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { repo.getQuestionList(prefs) }
                val rows = r.optJSONArray("rows") ?: r.optJSONArray("data")
                val list = mutableListOf<JSONObject>()
                if (rows != null) for (i in 0 until rows.length()) list.add(rows.getJSONObject(i))
                surveys = list; loading = false
            } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
        }
    }

    if (selectedSurvey != null) {
        SurveyDetailView(selectedSurvey!!, repo, prefs, scope) { selectedSurvey = null }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("满意度调查", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> ErrorBlock(error)
            surveys.isEmpty() -> EmptyBlock("暂无满意度调查")
            else -> surveys.forEach { survey -> SurveyCard(survey) { selectedSurvey = survey }; Spacer(Modifier.height(10.dp)) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SurveyCard(survey: JSONObject, onClick: () -> Unit) {
    val title = survey.optString("title", "未命名问卷")
    val desc = survey.optString("description", "").take(60)
    val endTime = survey.optString("endTime", "")
    val count = survey.optInt("answerCount", 0)
    val status = survey.optString("status", "")

    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val statusColor = if (status == "open") Color(0xFF1D9E75) else TextHint
                Text(if (status == "open") "进行中" else "已结束", color = statusColor, fontSize = 11.sp)
            }
            if (desc.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(desc, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$count 人已参与", color = TextHint, fontSize = 11.sp)
                if (endTime.isNotEmpty()) Text("截止 $endTime", color = TextHint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SurveyDetailView(survey: JSONObject, repo: CampusRepository, prefs: PrefsManager, scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf<MutableMap<String, String>>(mutableMapOf()) }
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf("") }
    val id = survey.optString("id", "")

    LaunchedEffect(id) {
        scope.launch {
            try {
                detail = withContext(Dispatchers.IO) { repo.getQuestionDetail(prefs, id) }
                loading = false
            } catch (e: Exception) { error = e.message ?: "加载失败"; loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回列表", color = Primary, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(4.dp))
        }
        when {
            loading -> LoadingBlock()
            error.isNotEmpty() -> ErrorBlock(error)
            detail != null -> {
                val questions = detail!!.optJSONArray("questions") ?: detail!!.optJSONArray("questionList")
                val qList = mutableListOf<JSONObject>()
                if (questions != null) for (i in 0 until questions.length()) qList.add(questions.getJSONObject(i))

                Text(detail!!.optString("title", "问卷详情"), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (detail!!.optString("description", "").isNotEmpty()) {
                    Spacer(Modifier.height(4.dp)); Text(detail!!.optString("description"), color = TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))

                qList.forEachIndexed { idx, q ->
                    val qTitle = q.optString("title", q.optString("content", "题目${idx + 1}"))
                    val qType = q.optString("type", "radio")
                    val options = q.optJSONArray("options")
                    val qId = q.optString("id", idx.toString())

                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${idx + 1}. $qTitle", color = TextPrimary, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            when (qType) {
                                "radio" -> {
                                    val opts = mutableListOf<String>()
                                    if (options != null) for (i in 0 until options.length()) opts.add(options.optString(i))
                                    opts.forEach { opt ->
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { answers = answers.toMutableMap().also { it[qId] = opt } }.padding(vertical = 4.dp)) {
                                            RadioButton(selected = answers[qId] == opt, onClick = { answers = answers.toMutableMap().also { it[qId] = opt } })
                                            Text(opt, fontSize = 13.sp, color = TextSecondary)
                                        }
                                    }
                                }
                                "checkbox" -> {
                                    val opts = mutableListOf<String>()
                                    if (options != null) for (i in 0 until options.length()) opts.add(options.optString(i))
                                    opts.forEach { opt ->
                                        val current = answers[qId]?.split(",")?.toSet().orEmpty()
                                        val checked = opt in current
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable {
                                                val ns = current.toMutableSet(); if (checked) ns.remove(opt) else ns.add(opt)
                                                answers = answers.toMutableMap().also { it[qId] = ns.joinToString(",") }
                                            }.padding(vertical = 4.dp)) {
                                            Checkbox(checked = checked, onCheckedChange = {
                                                val ns = current.toMutableSet(); if (it) ns.add(opt) else ns.remove(opt)
                                                answers = answers.toMutableMap().also { a -> a[qId] = ns.joinToString(",") }
                                            })
                                            Text(opt, fontSize = 13.sp, color = TextSecondary)
                                        }
                                    }
                                }
                                else -> OutlinedTextField(
                                    value = answers[qId] ?: "", onValueChange = { answers = answers.toMutableMap().also { a -> a[qId] = it } },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                                    placeholder = { Text("请输入回答", fontSize = 13.sp) }, maxLines = 3
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        submitting = true
                        val ansJson = JSONObject()
                        answers.forEach { (k, v) -> ansJson.put(k, v) }
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { repo.submitAnswer(prefs, id, ansJson) }
                                val msg = r.optString("msg", r.optString("message", ""))
                                submitResult = if (msg.contains("成功")) "提交成功" else msg.ifEmpty { "提交失败" }
                            } catch (e: Exception) { submitResult = "提交失败: ${e.message}" }
                            submitting = false
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text(if (submitting) "提交中..." else "提交问卷", color = Color.White) }

                if (submitResult.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(submitResult, color = if (submitResult.contains("成功")) Color(0xFF1D9E75) else Color(0xFFE24B4A), fontSize = 13.sp)
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}
