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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.campus.ui.CampusRoute
import go.examate.hyp.campus.ui.CampusServiceViewModel
import go.examate.hyp.campus.ui.EcardScreen
import go.examate.hyp.campus.ui.HsxsScreen
import go.examate.hyp.campus.ui.JwxtScreen
import go.examate.hyp.campus.ui.LoginState
import go.examate.hyp.campus.ui.ScScreen
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.IconSchool
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.SuccessBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

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

@Composable
private fun CampusServiceRoot(onBack: () -> Unit) {
    val viewModel: CampusServiceViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val school = state.school
    val showPicker = state.showPicker
    val route = state.route
    val username = state.username
    val password = state.password
    val rememberLogin = state.rememberLogin
    val loginState = state.loginState
    val loginError = state.loginError
    val bannerDismissed = state.loginBannerDismissed
    val studentName = state.studentName

    // 系统返回键:子页面返回到校园服务首页,而非退出 Activity
    BackHandler(enabled = route !is CampusRoute.Home) {
        viewModel.setRoute(CampusRoute.Home)
    }

    // 自动登录:已绑定学校 + 记住登录 + 有保存的账号密码 + 未登录
    LaunchedEffect(Unit) {
        viewModel.autoLogin()
    }

    // 子路由:直接渲染对应子页面(已登录才能进入)
    when (route) {
        is CampusRoute.Jwxt -> {
            JwxtScreen(onBack = { viewModel.setRoute(CampusRoute.Home) })
            return
        }
        is CampusRoute.Ecard -> {
            EcardScreen(onBack = { viewModel.setRoute(CampusRoute.Home) })
            return
        }
        is CampusRoute.Sc -> {
            ScScreen(onBack = { viewModel.setRoute(CampusRoute.Home) })
            return
        }
        is CampusRoute.Hsxs -> {
            HsxsScreen(onBack = { viewModel.setRoute(CampusRoute.Home) })
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
        // 顶栏:返回按钮 + 标题
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterStart)
            )
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
                                    onClick = { viewModel.setShowPicker(true) },
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
                                    onValueChange = { viewModel.setUsername(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("学号/工号", color = TextHint, fontSize = 13.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { viewModel.setPassword(it) },
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
                                        .background(SurfaceVariant)
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
                                            viewModel.setRememberLogin(it)
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
                                            .background(ErrorBg)
                                            .padding(12.dp)
                                    ) {
                                        Text(loginError, color = Error, fontSize = 12.sp, lineHeight = 17.sp)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }

                                LiquidGlassButton(
                                    text = if (loginState == LoginState.Logging) "登录中..." else "登录",
                                    onClick = {
                                        viewModel.login(username, password)
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
                        // 登录状态条(可关闭,显示学生姓名)
                        if (!bannerDismissed) {
                            val displayName = if (studentName.isNotEmpty()) studentName else username
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SuccessBg)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("✓", color = Success, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(8.dp))
                                Text("已登录 $school · $displayName", color = Success, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = Success,
                                    modifier = Modifier.size(16.dp).clickable { viewModel.dismissLoginBanner() }
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        FeatureEntryCard(
                            title = "教务系统",
                            subtitle = "课表 / 成绩 / 教学评价 / 选课",
                            onClick = { viewModel.setRoute(CampusRoute.Jwxt) }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "一卡通",
                            subtitle = "余额 / 账单 / 充值 / 生活缴费",
                            onClick = { viewModel.setRoute(CampusRoute.Ecard) }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "第二课堂",
                            subtitle = "成绩汇总 / 成绩明细",
                            onClick = { viewModel.setRoute(CampusRoute.Sc) }
                        )
                        Spacer(Modifier.height(12.dp))
                        FeatureEntryCard(
                            title = "淮师心声",
                            subtitle = "失物招领 / 满意度调查",
                            onClick = { viewModel.setRoute(CampusRoute.Hsxs) }
                        )
                        Spacer(Modifier.height(20.dp))
                        LiquidGlassButton(
                            text = "退出登录",
                            onClick = { viewModel.logout() },
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
                viewModel.setSchool(picked)
                viewModel.setShowPicker(false)
            },
            onDismiss = { viewModel.setShowPicker(false) }
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
