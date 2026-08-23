package go.examate.hyp.campus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import go.examate.hyp.campus.JiaoCaiGoApi
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 教材清单页面 — 登录 jiaocaigo.com 后查询本学期各课程所需教材。
 *
 * 未登录:手机号+短信验证码登录流程。
 * 已登录:显示学期课程列表,点击课程展开教材清单;
 *       点击书名或 ISBN 可快捷复制,便于去其他渠道购买。
 */
@Composable
fun TextbookListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(JiaoCaiGoApi.loadToken(context)) }

    Column(Modifier.fillMaxSize().background(BgPrimary).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "教材清单",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (token.isNotEmpty()) {
                LiquidGlassIconButton(
                    icon = Icons.Filled.ExitToApp,
                    contentDescription = "退出登录",
                    onClick = {
                        JiaoCaiGoApi.clearToken(context)
                        token = ""
                    },
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)
        ) {
            if (token.isEmpty()) {
                LoginBlock(onLogin = { token = it })
            } else {
                CourseListBlock(token = token)
            }
        }
    }
}

// ══════════════════════════════════════
//  登录块
// ══════════════════════════════════════

@Composable
private fun LoginBlock(onLogin: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf(JiaoCaiGoApi.loadPhone(context)) }
    var verifystr by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FrostedGlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("教材清单登录", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("使用教材购小程序账号(手机号+短信验证码)登录", color = TextHint, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                    placeholder = { Text("手机号", color = TextHint, fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !sending && !logging,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(12.dp))

                if (codeSent) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        placeholder = { Text("短信验证码", color = TextHint, fontSize = 13.sp) },
                        singleLine = true,
                        enabled = !logging,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = Error, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }

                if (!codeSent) {
                    LiquidGlassButton(
                        text = if (sending) "发送中..." else "发送验证码",
                        onClick = {
                            if (sending || phone.length != 11) return@LiquidGlassButton
                            sending = true; error = ""
                            scope.launch {
                                try {
                                    verifystr = withContext(Dispatchers.IO) { JiaoCaiGoApi.sendCode(phone) }
                                    codeSent = true
                                } catch (e: Exception) { error = e.message ?: "发送失败" }
                                sending = false
                            }
                        },
                        style = ButtonStyle.White,
                        modifier = Modifier.fillMaxWidth(),
                        height = 46.dp
                    )
                } else {
                    LiquidGlassButton(
                        text = if (logging) "登录中..." else "登录",
                        onClick = {
                            if (logging || code.length < 4) return@LiquidGlassButton
                            logging = true; error = ""
                            scope.launch {
                                try {
                                    val t = withContext(Dispatchers.IO) { JiaoCaiGoApi.checkCode(phone, code, verifystr) }
                                    JiaoCaiGoApi.saveToken(context, t, phone)
                                    onLogin(t)
                                } catch (e: Exception) { error = e.message ?: "登录失败" }
                                logging = false
                            }
                        },

                        style = ButtonStyle.White,
                        modifier = Modifier.fillMaxWidth(),
                        height = 46.dp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════
//  课程列表块
// ══════════════════════════════════════

@Composable
private fun CourseListBlock(token: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<JiaoCaiGoApi.Course>>(emptyList()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val materialsCache = remember { mutableStateMapOf<String, List<JiaoCaiGoApi.Material>>() }
    var loadingCourseId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val (t, cs) = withContext(Dispatchers.IO) { JiaoCaiGoApi.getCourses(token) }
            term = t
            courses = cs
            if (cs.isEmpty()) error = "本学期暂无课程数据"
        } catch (e: JiaoCaiGoApi.JiaoCaiGoException) {
            error = e.message ?: "加载失败"
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
        }
        loading = false
    }

    if (loading) {
        LoadingBlock("正在加载课程列表...")
    } else if (error.isNotEmpty()) {
        ErrorBlock(error)
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FrostedGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(term.ifBlank { "本学期" }, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("共 ${courses.size} 门课程", color = TextHint, fontSize = 12.sp)
                    }
                }
            }
            items(courses, key = { it.id }) { course ->
                CourseCard(
                    course = course,
                    expanded = expandedId == course.id,
                    materials = materialsCache[course.id],
                    loadingMaterials = loadingCourseId == course.id,
                    onToggle = {
                        expandedId = if (expandedId == course.id) null else course.id
                        if (expandedId == course.id && !materialsCache.containsKey(course.id)) {
                            loadingCourseId = course.id
                            scope.launch {
                                try {
                                    val ms = withContext(Dispatchers.IO) { JiaoCaiGoApi.getMaterials(token, course.id) }
                                    materialsCache[course.id] = ms
                                } catch (e: Exception) {
                                    materialsCache[course.id] = emptyList()
                                    ToastUtil.show(context, e.message ?: "加载教材失败")
                                }
                                loadingCourseId = null
                            }
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun CourseCard(
    course: JiaoCaiGoApi.Course,
    expanded: Boolean,
    materials: List<JiaoCaiGoApi.Material>?,
    loadingMaterials: Boolean,
    onToggle: () -> Unit
) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(course.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    val sub = buildString {
                        if (course.code.isNotEmpty()) append(course.code)
                        if (course.nature.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(course.nature)
                        }
                    }
                    if (sub.isNotEmpty()) Text(sub, color = TextHint, fontSize = 11.sp)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextHint, modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                when {
                    loadingMaterials -> {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("加载教材中...", color = TextHint, fontSize = 12.sp)
                        }
                    }
                    materials == null -> {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("加载失败", color = TextHint, fontSize = 12.sp)
                        }
                    }
                    materials.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("该课程暂无教材", color = TextHint, fontSize = 12.sp)
                        }
                    }
                    else -> {
                        materials.forEach { m -> MaterialRow(m) }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════
//  教材行:封面 + 信息(书名/ISBN 可点击复制)
// ══════════════════════════════════════

@Composable
private fun MaterialRow(material: JiaoCaiGoApi.Material) {
    val context = LocalContext.current

    fun copy(text: String, label: String) {
        if (text.isBlank()) return
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        ToastUtil.show(context, "已复制${label}: $text")
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (material.cover.isNotEmpty()) {
            Box(
                Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(4.dp))
                    .background(CardBg)
            ) {
                AsyncImage(
                    model = material.cover,
                    contentDescription = material.name,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            // 书名(可复制)
            Row(
                Modifier.clickable { copy(material.name, "书名") }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    material.name.ifBlank { "未知教材" },
                    color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制书名", tint = TextHint, modifier = Modifier.size(14.dp))
            }
            if (material.author.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("作者: ${material.author}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (material.press.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text("出版社: ${material.press}", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // ISBN(可复制)
            if (material.isbn.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Row(
                    Modifier.clickable { copy(material.isbn, "ISBN") }.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ISBN: ${material.isbn}",
                        color = AccentBlue, fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制ISBN", tint = TextHint, modifier = Modifier.size(12.dp))
                }
            }
            val priceText = if (material.price > 0) "¥${material.price}" else ""
            val reserveText = material.reserveName.ifBlank { "" }
            val trailing = listOf(priceText, reserveText).filter { it.isNotEmpty() }.joinToString(" · ")
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(trailing, color = TextHint, fontSize = 11.sp)
            }
        }
    }
}


@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = TextHint.copy(alpha = 0.3f)
)