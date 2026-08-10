package go.examate.hyp.campus.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.InputBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.HsxsError
import go.examate.hyp.ui.theme.HsxsSuccess
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.random.Random

private enum class HsxsTab(val label: String) { LOST_FOUND("失物招领"), SURVEY("满意度调查") }

/** 组合 onBack: 详情页先回列表,列表页回上一级 */
private fun makeBackHandler(detailId: String, tab: HsxsTab, parentBack: () -> Unit, clearDetail: () -> Unit): () -> Unit = {
    if (detailId.isNotEmpty()) clearDetail()
    else parentBack()
}

@Composable
fun HsxsScreen(onBack: () -> Unit) {
    val viewModel: HsxsViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(HsxsTab.LOST_FOUND) }
    var detailId by remember { mutableStateOf("") }

    fun clearDetail() { detailId = "" }
    val backAction = remember(detailId, tab) { makeBackHandler(detailId, tab, onBack, ::clearDetail) }
    BackHandler(enabled = detailId.isNotEmpty()) { clearDetail() }

    LaunchedEffect(Unit) { viewModel.login() }

    CampusPageScaffold(title = "淮师心声", onBack = backAction, bottomBar = {
        Spacer(Modifier.height(10.dp))
        GlassTabBar(tabs = HsxsTab.entries.map { it.label }, selectedIndex = tab.ordinal,
            onSelect = { tab = HsxsTab.entries[it]; detailId = "" })
        Spacer(Modifier.height(16.dp))
    }) {
        if (!state.login.loggedIn) LoadingBlock()
        else if (state.login.loginError.isNotEmpty()) Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ErrorBlock(state.login.loginError)
                LiquidGlassButton(text = "重试", onClick = { viewModel.retryLogin() },
                    style = ButtonStyle.Outline, height = 40.dp)
            }
        }
        else when (tab) {
            HsxsTab.LOST_FOUND -> LostFoundSection(viewModel, state, detailId) { detailId = it }
            HsxsTab.SURVEY -> SurveySection(viewModel, state, detailId) { detailId = it }
        }
    }
}

// ═══════════════ 失物招领 ═══════════════
@Composable
private fun LostFoundSection(viewModel: HsxsViewModel, state: HsxsUiState,
    detailId: String, onDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()
    val lostFound = state.lostFound

    LaunchedEffect(Unit) { viewModel.loadLostList() }
    if (detailId.isNotEmpty()) { LostDetailView(detailId, repo, prefs, scope) { onDetail("") }; return }

    Box(Modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 8.dp)) {
            when { lostFound.loading -> LoadingBlock(); lostFound.error.isNotEmpty() -> ErrorBlock(lostFound.error)
                lostFound.items.isEmpty() -> EmptyBlock("暂无失物招领信息")
                else -> lostFound.items.forEach { item -> Spacer(Modifier.height(10.dp)); LostItemCard(item) { onDetail(item.optString("id", "")) } } }
            Spacer(Modifier.height(80.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ScrollAwarePublishButton(scrollState) { viewModel.setShowPublish(true) }
        }
    }
    if (lostFound.showPublish) { PublishLostDialog(repo, prefs, scope) { viewModel.setShowPublish(false); viewModel.loadLostList() } }
}

@Composable
private fun ScrollAwarePublishButton(scrollState: ScrollState, onClick: () -> Unit) {
    val isScrolling by remember { derivedStateOf { scrollState.isScrollInProgress } }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling) {
        if (isScrolling && !wasScrolling) { wasScrolling = true; scope.launch { offsetX.animateTo(120f, tween(250)) } }
        else if (!isScrolling && wasScrolling) { delay(350); if (!scrollState.isScrollInProgress) { wasScrolling = false; scope.launch { offsetX.animateTo(0f, tween(350)) } } }
    }
    val density = LocalDensity.current
    Box(Modifier.offset { IntOffset(density.run { offsetX.value.dp.toPx() }.toInt(), 0) }.width(110.dp)) {
        LiquidGlassButton(text = "发布", onClick = onClick, style = ButtonStyle.White, height = 42.dp)
    }
}

@Composable
private fun LostItemCard(item: JSONObject, onClick: () -> Unit) {
    val content = item.optString("content", "").take(100)
    val typeNum = item.optInt("type", 1)
    val typeLabel = if (typeNum == 2) "招领" else "失物"
    val typeColor = if (typeNum == 2) HsxsSuccess else HsxsError
    val time = item.optString("createtime", "")
    val campus = item.optJSONObject("organise_info")?.optString("name", "") ?: ""
    val imagesArr = item.optJSONArray("images_arr")
    val firstImg = if (imagesArr != null && imagesArr.length() > 0) imagesArr.optString(0) else ""

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row { Text(typeLabel, color = typeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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

// ═══════════════ 帖子详情(小红书风格: 图片在上, 文字在下) ═══════════════
@Composable
private fun LostDetailView(id: String, repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit
) {
    var item by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var currentImg by remember { mutableStateOf(0) }
    LaunchedEffect(id) {
        scope.launch {
            try { val resp = withContext(Dispatchers.IO) { repo.getLostDetail(prefs, id) }; item = resp.optJSONObject("data") ?: resp; loading = false }
            catch (_: Exception) { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (loading) LoadingBlock()
        else if (item == null) Text("加载失败", color = TextHint, modifier = Modifier.padding(40.dp))
        else {
            val it = item!!
            val imagesArr = it.optJSONArray("images_arr")
            val hasImages = imagesArr != null && imagesArr.length() > 0

            // ── 图片区域(如果有图) ──
            if (hasImages) {
                Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).background(InputBg)) {
                    AsyncImage(model = fixImgUrl(imagesArr.optString(currentImg)), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.FillWidth)
                    // 图片指示器
                    if (imagesArr.length() > 1) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0 until imagesArr.length()) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == currentImg) Primary else Color.White.copy(0.7f)))
                            }
                        }
                        // 左右点击切换
                        if (currentImg > 0) Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(36.dp).clip(CircleShape)
                            .background(Color.Black.copy(0.3f)).clickable { currentImg-- }, contentAlignment = Alignment.Center
                        ) { Text("◀", color = Color.White, fontSize = 14.sp) }
                        if (currentImg < imagesArr.length() - 1) Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(36.dp).clip(CircleShape)
                            .background(Color.Black.copy(0.3f)).clickable { currentImg++ }, contentAlignment = Alignment.Center
                        ) { Text("▶", color = Color.White, fontSize = 14.sp) }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))

                // ── 标签行 ──
                val typeNum = it.optInt("type", 1)
                val typeLabel = if (typeNum == 2) "招领" else "失物"
                val typeColor = if (typeNum == 2) HsxsSuccess else HsxsError
                val status = it.optString("status_text", "")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoChip(typeLabel, typeColor)
                        if (status.isNotEmpty()) InfoChip(status, TextHint)
                    }
                    Text(it.optString("createtime", "").take(10), color = TextHint, fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))

                // ── 正文(保留换行, 增强可读性) ──
                val rawContent = it.optString("content", "")
                Text(rawContent, color = TextPrimary, fontSize = 15.sp, lineHeight = 24.sp,
                    letterSpacing = 0.3.sp)

                Spacer(Modifier.height(20.dp))

                // ── 信息卡片 ──
                val campus = it.optJSONObject("organise_info")?.optString("name", "")
                val dept = it.optJSONObject("department_info")?.optString("name", "")
                val userName = it.optJSONObject("user_info")?.optString("user_name", "")
                val userPhone = it.optJSONObject("user_info")?.optString("user_phone", "")
                val appTime = it.optString("applytime", "")

                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (!campus.isNullOrEmpty()) InfoRow("校区", campus)
                        if (!dept.isNullOrEmpty()) InfoRow("部门", dept)
                        if (!userName.isNullOrEmpty()) InfoRow("联系人", userName)
                        if (!userPhone.isNullOrEmpty()) InfoRow("电话", userPhone)
                        InfoRow("时间", it.optString("createtime", ""))
                        if (appTime.isNotEmpty()) InfoRow("发布时间", appTime)
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$label  ", color = TextHint, fontSize = 13.sp)
        Text(value, color = TextSecondary, fontSize = 13.sp)
    }
}

// ═══════════════ 发布弹窗(校区改用统一选择器) ═══════════════
@Composable
private fun PublishLostDialog(repo: CampusRepository, prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit
) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf("1") }
    var phone by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var campusList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedCampusId by remember { mutableStateOf("") }
    var selectedCampusName by remember { mutableStateOf("") }
    var showCampusPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri = it }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { repo.getUserInfo(prefs) }
                val data = info.optJSONObject("data")
                phone = data?.optString("user_phone", "") ?: ""
            } catch (_: Exception) { }
            try {
                val resp = withContext(Dispatchers.IO) { repo.getOrganiseList(prefs) }
                val arr = resp.optJSONArray("data")
                if (arr != null) {
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.optJSONObject(i)
                        val vid = obj?.optString("value", obj?.optString("id", "")) ?: ""
                        val vname = obj?.optString("name", "") ?: ""
                        if (vid.isNotEmpty()) vid to vname else null
                    }
                    campusList = list
                    if (list.isNotEmpty()) { selectedCampusId = list[0].first; selectedCampusName = list[0].second }
                }
            } catch (_: Exception) { }
        }
    }

    if (showCampusPicker) {
        SingleChoiceDialog("选择校区", campusList, selectedCampusId,
            onConfirm = { id ->
                selectedCampusId = id
                selectedCampusName = campusList.firstOrNull { it.first == id }?.second ?: ""
            },
            onDismiss = { showCampusPicker = false })
    }

    Dialog(onDismissRequest = { if (!submitting) onDone() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)) {
            Text("发布失物招领", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                OutlinedTextField(value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("手机号", color = TextHint, fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Spacer(Modifier.height(10.dp))
                Text("类型", color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf("1" to "失物", "2" to "招领").forEach { (v, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { type = v }) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(if (type == v) Primary else SurfaceVariant), contentAlignment = Alignment.Center) {
                                if (type == v) Box(Modifier.size(8.dp).clip(CircleShape).background(CardBg))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(l, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 校区 → 统一 QueryParamRow + SingleChoiceDialog
                QueryParamRow(label = "校区", value = selectedCampusName.ifEmpty { "选择校区" },
                    onClick = { showCampusPicker = true })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("描述内容", color = TextHint, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 5, shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceVariant.copy(0.5f))
                        .clickable { imagePicker.launch("image/*") }, contentAlignment = Alignment.Center
                    ) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("＋", color = TextHint, fontSize = 18.sp); Text("图片", color = TextHint, fontSize = 9.sp) } }
                    imageUri?.let { uri ->
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceVariant)) {
                            AsyncImage(model = ImageRequest.Builder(ctx).data(uri).build(), contentDescription = "预览",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color.Black.copy(0.5f))
                                .clickable { imageUri = null }, contentAlignment = Alignment.Center
                            ) { Text("×", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
                if (result.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(result, color = if (result.contains("成功")) HsxsSuccess else HsxsError, fontSize = 12.sp) }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().background(InputBg).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("取消", color = TextSecondary, fontSize = 14.sp,
                    modifier = Modifier.clickable { if (!submitting) onDone() }.padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.width(8.dp))
                LiquidGlassButton(text = if (submitting) "发布中..." else "发布", onClick = {
                    if (submitting) return@LiquidGlassButton
                    if (phone.isBlank()) { result = "请填写手机号"; return@LiquidGlassButton }
                    if (selectedCampusId.isEmpty()) { result = "请选择校区"; return@LiquidGlassButton }
                    if (content.isBlank()) { result = "请填写描述内容"; return@LiquidGlassButton }
                    submitting = true; result = ""
                    scope.launch {
                        try {
                            val body = mutableMapOf("user_phone" to phone, "type" to type, "campus" to selectedCampusId, "content" to content)
                            val uri = imageUri
                            if (uri != null) {
                                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.readBytes() }
                                if (bytes != null) {
                                    val path = withContext(Dispatchers.IO) { repo.uploadImage(prefs, "upload_${System.currentTimeMillis()}.jpg", bytes) }
                                    body["images"] = path
                                }
                            }
                            withContext(Dispatchers.IO) { repo.publishLost(prefs, body) }
                            result = "发布成功"; onDone()
                        } catch (e: Exception) { result = "发布失败: ${e.message}"; submitting = false }
                    }
                }, style = ButtonStyle.White, height = 38.dp)
            }
        }
    }
}

// ═══════════════ 满意度调查 ═══════════════
@Composable
private fun SurveySection(viewModel: HsxsViewModel, state: HsxsUiState,
    detailId: String, onDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()
    val survey = state.survey

    LaunchedEffect(Unit) { viewModel.loadSurveyList() }
    if (detailId.isNotEmpty()) { SurveyDetailView(detailId, repo, prefs, scope) { onDetail("") }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        when { survey.loading -> LoadingBlock(); survey.error.isNotEmpty() -> ErrorBlock(survey.error)
            survey.items.isEmpty() -> EmptyBlock("暂无满意度调查")
            else -> survey.items.forEach { s -> Spacer(Modifier.height(10.dp)); SurveyCard(s) { onDetail(s.optString("id", "")) } } }
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

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
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
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        scope.launch {
            try {
                survey = withContext(Dispatchers.IO) { repo.getQuestionDetail(prefs, id) }.optJSONObject("data")
                questions = parseDataArray(withContext(Dispatchers.IO) { repo.getSurveyQuestions(prefs, id) })
                loading = false
            } catch (_: Exception) { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (loading) LoadingBlock()
        else if (survey == null) Text("加载失败", color = TextHint, modifier = Modifier.padding(40.dp))
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

            // 一键填写按钮(默认全选A, 随机分散几个选项避免无效提交)
            if (!shown && questions.isNotEmpty()) {
                LiquidGlassButton(text = "一键填写", onClick = {
                    val newAnswers = mutableMapOf<Int, Any>()
                    questions.forEachIndexed { idx, q ->
                        val qId = q.optInt("id", idx)
                        val contentData = q.optJSONObject("content_data")
                        val keys = contentData?.keys()?.asSequence()?.toList() ?: return@forEachIndexed
                        if (keys.isEmpty()) return@forEachIndexed
                        if (q.optInt("type", 1) == 1) {
                            // 单选: 大多数选第1个, 随机变几个
                            val pick = if (Random.nextInt(questions.size) <= 1) Random.nextInt(keys.size) else 0
                            newAnswers[qId] = keys[pick].toIntOrNull() ?: keys[pick]
                        } else {
                            // 多选: 默认全选, 随机去掉几个
                            val picked = keys.take(Random.nextInt(1, keys.size + 1)).toList()
                            if (picked.size <= 1) newAnswers[qId] = picked.first().toIntOrNull() ?: picked.first()
                            else newAnswers[qId] = picked.map { it.toIntOrNull() ?: it }
                        }
                    }
                    answers = newAnswers; shown = true
                }, style = ButtonStyle.Outline, height = 38.dp)
                Spacer(Modifier.height(8.dp))
            }

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
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${idx + 1}. $qTitle", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        if (qType == 1) {
                            options.forEach { (label, key) ->
                                Row(Modifier.fillMaxWidth().clickable { answers = answers.toMutableMap().also { it[qId] = key.toIntOrNull() ?: key } }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    val selected = answers[qId]?.toString() == key
                                    Box(Modifier.size(20.dp).clip(CircleShape)
                                        .background(if (selected) Primary else SurfaceVariant)
                                        .clickable { answers = answers.toMutableMap().also { it[qId] = key.toIntOrNull() ?: key } },
                                        contentAlignment = Alignment.Center
                                    ) { if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(CardBg)) }
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        } else if (qType == 2) {
                            val selected = (answers[qId] as? List<*>)?.map { it.toString() } ?: emptyList()
                            options.forEach { (label, key) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    val newList = selected.toMutableList()
                                    if (newList.contains(key)) newList.remove(key) else newList.add(key)
                                    answers = answers.toMutableMap().also { it[qId] = newList }
                                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val checked = selected.contains(key)
                                    Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                                        .background(if (checked) Primary else SurfaceVariant)
                                        .clickable {
                                            val newList = selected.toMutableList()
                                            if (newList.contains(key)) newList.remove(key) else newList.add(key)
                                            answers = answers.toMutableMap().also { a -> a[qId] = newList }
                                        }, contentAlignment = Alignment.Center
                                    ) { if (checked) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (resultMsg.isNotEmpty()) {
                Text(resultMsg, color = if (resultMsg.contains("成功")) HsxsSuccess else HsxsError, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            LiquidGlassButton(text = if (submitting) "提交中..." else "提交问卷", onClick = {
                if (submitting || questions.isEmpty()) return@LiquidGlassButton
                submitting = true
                scope.launch {
                    try {
                        val ansJson = JSONObject()
                        answers.forEach { (qId, v) -> ansJson.put(qId.toString(), if (v is List<*>) JSONArray(v) else v) }
                        withContext(Dispatchers.IO) { repo.submitAnswer(prefs, id, ansJson) }
                        resultMsg = "提交成功"
                    } catch (e: Exception) { resultMsg = "提交失败: ${e.message}" }
                    submitting = false
                }
            }, style = ButtonStyle.White, height = 42.dp)
        }
        Spacer(Modifier.height(30.dp))
    }
}

private fun parseDataArray(json: JSONObject): List<JSONObject> {
    val arr = json.optJSONObject("data")?.optJSONArray("data") ?: json.optJSONArray("data") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

private fun fixImgUrl(url: String): String = when {
    url.startsWith("http") -> url
    url.startsWith("/") -> "${go.examate.hyp.campus.ChnuConfig.HSXS_BASE_URL}$url"
    else -> url
}
