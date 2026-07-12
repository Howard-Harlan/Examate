package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.ui.EcardScreen
import go.examate.hyp.campus.ui.JwxtScreen
import go.examate.hyp.campus.ui.ScScreen
import go.examate.hyp.campus.ui.HsxsScreen
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.IconSchool
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 校园服务页:统一入口,管理登录态与三系统导航。
 *
 * 页面状态:
 * 1. 未绑定学校 → 引导选择学校
 * 2. 已绑定未登录 → CAS 登录表单
 * 3. 已登录 → 三系统功能入口(教务/一卡通/第二课堂)
 * 4. 进入子系统 → 对应功能页(Tab 切换 + 查询参数 + 结果展示)
 */
class CampusServiceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    CampusServiceRoot(onBack = { finish() })
                }
            }
        }
    }
}

/** 页面路由 */
private sealed class CampusRoute {
    object Home : CampusRoute()
    object Jwxt : CampusRoute()
    object Ecard : CampusRoute()
    object Sc : CampusRoute()
    object Hsxs : CampusRoute()
}

/** 登录状态 */
private enum class LoginState { Idle, Logging, LoggedIn, Failed }

@Composable
private fun CampusServiceRoot(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()

    var school by remember { mutableStateOf(prefs.school) }
    var showPicker by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<CampusRoute>(CampusRoute.Home) }
    var username by remember { mutableStateOf(prefs.campusUsername) }
    var password by remember { mutableStateOf(prefs.campusPassword) }
    var rememberLogin by remember { mutableStateOf(prefs.rememberCampusLogin) }
    // 启动时若 repo 已登录则直接显示已登录态
    var loginState by remember {
        mutableStateOf(if (repo.isLoggedIn) LoginState.LoggedIn else LoginState.Idle)
    }
    var loginError by remember { mutableStateOf("") }

    // 系统返回键:子页面返回到校园服务首页,而非退出 Activity
    BackHandler(enabled = route !is CampusRoute.Home) {
        route = CampusRoute.Home
    }

    // 自动登录:已绑定学校 + 记住登录 + 有保存的账号密码 + 未登录
    LaunchedEffect(Unit) {
        if (!repo.isLoggedIn && prefs.rememberCampusLogin &&
            prefs.campusUsername.isNotEmpty() && prefs.campusPassword.isNotEmpty() &&
            prefs.school.isNotEmpty()
        ) {
            loginState = LoginState.Logging
            loginError = ""
            try {
                repo.login(prefs.campusUsername.trim(), prefs.campusPassword)
                username = prefs.campusUsername
                password = prefs.campusPassword
                loginState = LoginState.LoggedIn
            } catch (e: CampusException) {
                loginError = e.message ?: "自动登录失败"
                loginState = LoginState.Failed
            } catch (e: Exception) {
                loginError = "网络异常: ${e.message ?: "请检查网络连接"}"
                loginState = LoginState.Failed
            }
        }
    }

    // 子路由:直接渲染对应子页面(已登录才能进入)
    when (route) {
        is CampusRoute.Jwxt -> {
            JwxtScreen(onBack = { route = CampusRoute.Home })
            return
        }
        is CampusRoute.Ecard -> {
            EcardScreen(onBack = { route = CampusRoute.Home })
            return
        }
        is CampusRoute.Sc -> {
            ScScreen(onBack = { route = CampusRoute.Home })
            return
        }
        is CampusRoute.Hsxs -> {
            HsxsScreen(onBack = { route = CampusRoute.Home })
            return
        }
        else -> { /* 主页继续往下渲染 */ }
    }

    // ═══ 主页 ═══
    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .imePadding()
            .statusBarsPadding()
    ) {
        // 顶栏
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Box(
                Modifier.size(40.dp).align(Alignment.CenterStart).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("←", color = TextPrimary, fontSize = 22.sp)
            }
            Text(
                text = "校园服务",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 内容区
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 40.dp)
        ) {
            when {
                school.isEmpty() -> {
                    // ═══ 未绑定:引导绑定(滚动) ═══
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(40.dp))
                        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = IconSchool,
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("请先绑定学校后使用校园服务", color = TextSecondary, fontSize = 14.sp)
                                Spacer(Modifier.height(18.dp))
                                LiquidGlassButton(
                                    text = "去绑定",
                                    onClick = { showPicker = true },
                                    style = ButtonStyle.White,
                                    height = 42.dp
                                )
                            }
                        }
                    }
                }

                loginState != LoginState.LoggedIn -> {
                    // ═══ 登录表单(滚动) ═══
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text(
                            "统一身份认证登录",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                        )
                        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("学号/工号", color = TextHint, fontSize = 13.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("密码", color = TextHint, fontSize = 13.sp) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "账号密码仅保存在本机,用于登录学校统一认证",
                                    color = TextHint, fontSize = 11.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                // 记住登录开关
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF3F4F6))
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("记住登录", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("下次进入自动登录", color = TextHint, fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = rememberLogin,
                                        onCheckedChange = {
                                            rememberLogin = it
                                            prefs.rememberCampusLogin = it
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Primary,
                                            uncheckedTrackColor = TextHint.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                                Spacer(Modifier.height(16.dp))

                                if (loginState == LoginState.Failed && loginError.isNotEmpty()) {
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFFFF0F0))
                                            .padding(12.dp)
                                    ) {
                                        Text(loginError, color = Color(0xFFE53935), fontSize = 12.sp, lineHeight = 17.sp)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }

                                LiquidGlassButton(
                                    text = if (loginState == LoginState.Logging) "登录中..." else "登录",
                                    onClick = {
                                        if (loginState == LoginState.Logging) return@LiquidGlassButton
                                        if (username.isBlank() || password.isBlank()) {
                                            loginError = "请输入学号和密码"
                                            loginState = LoginState.Failed
                                            return@LiquidGlassButton
                                        }
                                        loginState = LoginState.Logging
                                        loginError = ""
                                        scope.launch {
                                            try {
                                                repo.login(username.trim(), password)
                                                prefs.campusUsername = username.trim()
                                                prefs.campusPassword = password
                                                loginState = LoginState.LoggedIn
                                            } catch (e: CampusException) {
                                                loginError = e.message ?: "登录失败"
                                                loginState = LoginState.Failed
                                            } catch (e: Exception) {
                                                loginError = "网络异常: ${e.message ?: "请检查网络连接"}"
                                                loginState = LoginState.Failed
                                            }
                                        }
                                    },
                                    style = ButtonStyle.White,
                                    height = 46.dp
                                )
                            }
                        }
                    }
                }

                else -> {
                    // ═══ 已登录:功能入口在上方 ═══
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // 登录状态条
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("✓", color = Color(0xFF2E7D32), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text("已登录 $school · $username", color = Color(0xFF2E7D32), fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "功能入口",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                        )

                        FeatureEntryCard(
                            title = "教务系统",
                            subtitle = "课表 / 成绩 / 教学评价 / 选课",
                            onClick = { route = CampusRoute.Jwxt }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "一卡通",
                            subtitle = "余额 / 账单 / 充值 / 生活缴费",
                            onClick = { route = CampusRoute.Ecard }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "第二课堂",
                            subtitle = "成绩汇总 / 成绩明细",
                            onClick = { route = CampusRoute.Sc }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "淮师心声",
                            subtitle = "失物招领 / 满意度调查",
                            onClick = { route = CampusRoute.Hsxs }
                        )
                        Spacer(Modifier.height(20.dp))
                        LiquidGlassButton(
                            text = "退出登录",
                            onClick = {
                                repo.logout()
                                // 退出登录后取消记住登录,避免下次进入又自动登录
                                rememberLogin = false
                                prefs.rememberCampusLogin = false
                                loginState = LoginState.Idle
                                loginError = ""
                            },
                            style = ButtonStyle.Outline,
                            height = 42.dp
                        )
                    }
                }
            }
        }
    }

    // 学校选择弹窗
    if (showPicker) {
        SchoolPickerDialog(
            currentSchool = school,
            onPick = { picked ->
                prefs.school = picked
                school = picked
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** 功能入口卡片 */
@Composable
private fun FeatureEntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextHint, fontSize = 12.sp)
            }
            Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
    }
}

/**
 * 学校选择弹窗:白色圆角卡片,列出已对接学校,底部"取消"与"确认"并排。
 *
 * 供 [CampusServiceActivity] 与 [UserSettingsActivity] 共用。
 */
@Composable
internal fun SchoolPickerDialog(
    currentSchool: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentSchool) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(vertical = 8.dp)
        ) {
            Text(
                "选择学校",
                color = TextHint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            PrefsManager.SUPPORTED_SCHOOLS.forEach { name ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { selected = name }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (name == selected) {
                        Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(TextHint.copy(alpha = 0.3f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确认", onClick = {
                        if (selected.isNotEmpty()) onPick(selected)
                    },
                    style = ButtonStyle.White, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
