package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.alipay.sdk.app.PayTask
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.SuccessBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.watercard.Page
import go.examate.hyp.watercard.PayEvent
import go.examate.hyp.watercard.WaterCardViewModel
import go.examate.hyp.watercard.WaterCardUiState
import go.examate.hyp.watercard.YxtBill

class WaterCardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    WaterCardRoot()
                }
            }
        }
    }
}

@Composable
private fun WaterCardRoot(vm: WaterCardViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 监听支付事件：拉起支付宝/微信 SDK
    LaunchedEffect(Unit) {
        vm.payEvent.collect { event ->
            when (event) {
                is PayEvent.Alipay -> {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                // PayTask 需 Activity Context；showLoading=true 显示 SDK 自带加载 UI
                                PayTask(context as android.app.Activity).payV2(event.orderString, true)
                            } catch (e: Exception) {
                                android.util.Log.e("WaterCard", "支付宝拉起失败", e)
                                mapOf("resultStatus" to "4000", "memo" to (e.message ?: "拉起支付宝失败"))
                            }
                        }
                        val status = result["resultStatus"] ?: ""
                        val memo = result["memo"] ?: ""
                        // 9000=成功, 8000=处理中(待确认), 6001=用户取消, 4000=失败, 6002=网络错误
                        val success = status == "9000" || status == "8000"
                        vm.onPayResult(success, if (success) "" else "支付未完成[$status] $memo".trim())
                    }
                }
                is PayEvent.Wechat -> {
                    // 微信支付：用微信 SDK 拉起
                    try {
                        val api = com.tencent.mm.opensdk.openapi.WXAPIFactory.createWXAPI(
                            context, event.appId, true
                        )
                        api.registerApp(event.appId)
                        val req = com.tencent.mm.opensdk.modelpay.PayReq().apply {
                            appId = event.appId
                            partnerId = event.partnerId
                            prepayId = event.prepayId
                            nonceStr = event.nonceStr
                            timeStamp = event.timeStamp
                            packageValue = "Sign=WXPay"
                            sign = event.sign
                        }
                        val ok = api.sendReq(req)
                        android.util.Log.d("WaterCard", "微信拉起 sendReq 结果: $ok")
                        if (!ok) {
                            vm.onPayResult(false, "拉起微信失败(未安装微信或参数错误)")
                        }
                        // 支付结果通过 WXEntryActivity 回调处理
                    } catch (e: Exception) {
                        android.util.Log.e("WaterCard", "微信拉起失败", e)
                        vm.onPayResult(false, "微信拉起异常: ${e.message}")
                    }
                }
            }
        }
    }

    when (state.page) {
        Page.LOGIN -> LoginPage(state, vm)
        Page.MAIN -> MainPage(state, vm)
    }
}

// ════════════════════════════════════════════════
//  登录页
// ════════════════════════════════════════════════

@Composable
private fun LoginPage(state: go.examate.hyp.watercard.WaterCardUiState, vm: WaterCardViewModel) {
    // 快速登录态：已保存学校/账号/密码，只需输验证码
    val quickMode = vm.isQuickLoginMode()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (quickMode) {
            Text("欢迎回来,请输入验证码登录", fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.height(28.dp))
        } else {
            Spacer(Modifier.height(28.dp))
        }

        if (quickMode) {
            // ─── 快速登录态：学校/账号/密码折叠为只读摘要卡片 ───
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    state.selectedSchool?.let { school ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("学校", fontSize = 12.sp, color = TextHint, modifier = Modifier.width(44.dp))
                            Text(school.schoolName, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("账号", fontSize = 12.sp, color = TextHint, modifier = Modifier.width(44.dp))
                        Text(state.userName, fontSize = 14.sp, color = TextPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("密码", fontSize = 12.sp, color = TextHint, modifier = Modifier.width(44.dp))
                        Text("•".repeat(state.password.length.coerceAtMost(12)), fontSize = 14.sp, color = TextPrimary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.switchAccount() }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("切换账号", fontSize = 12.sp, color = AccentBlue, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        } else {
            // ─── 完整登录态：学校搜索 ───
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("学校", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.schoolKeyword,
                        onValueChange = { vm.updateSchoolKeyword(it) },
                        placeholder = { Text("输入学校名称", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = textFieldColors(),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        trailingIcon = {
                            Text(
                                "搜索",
                                fontSize = 14.sp,
                                color = AccentBlue,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { vm.searchSchool() }.padding(horizontal = 8.dp)
                            )
                        }
                    )
                    // 学校列表
                    if (state.schoolList.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        state.schoolList.forEach { school ->
                            val selected = state.selectedSchool?.schoolName == school.schoolName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AccentBlue.copy(0.1f) else Color.Transparent)
                                    .clickable { vm.selectSchool(school) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    school.schoolName,
                                    fontSize = 14.sp,
                                    color = if (selected) AccentBlue else TextPrimary,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                                )
                                if (selected) {
                                    Spacer(Modifier.weight(1f))
                                    Text("已选", fontSize = 12.sp, color = AccentBlue)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 账号
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("账号", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.userName,
                        onValueChange = { vm.updateUserName(it) },
                        placeholder = { Text("请输入账号/手机号", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = textFieldColors(),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 密码
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("密码", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { vm.updatePassword(it) },
                        placeholder = { Text("请输入登录密码", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = textFieldColors(),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 验证码（V3 和 V4 平台都用图片验证码，两种模式都需要）
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("图形验证码", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.captchaInput,
                        onValueChange = { vm.updateCaptchaInput(it) },
                        placeholder = { Text("输入验证码", fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = textFieldColors(),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp)
                    )
                    Spacer(Modifier.width(10.dp))
                    // 验证码图片（V3 和 V4 通用）
                    Box(
                        modifier = Modifier
                            .size(width = 100.dp, height = 46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .clickable { vm.refreshCaptcha() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.captchaBase64.isNotBlank()) {
                            CaptchaImage(state.captchaBase64)
                        } else {
                            Text("点击获取", fontSize = 11.sp, color = TextHint)
                        }
                    }
                }
            }
        }

        // 错误提示
        if (state.loginError.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner(state.loginError)
        }

        Spacer(Modifier.height(20.dp))

        // 登录按钮
        if (state.loginLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = AccentBlue
                )
                Spacer(Modifier.width(10.dp))
                Text("正在登录...", fontSize = 14.sp, color = TextSecondary)
            }
        } else {
            LiquidGlassButton(
                text = "登录",
                onClick = { vm.login() },
                style = ButtonStyle.White,
                height = 50.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "水卡服务由乐校通提供,本应用仅做界面展示",
            fontSize = 11.sp,
            color = TextHint
        )
    }
}

/** 验证码图片（base64 data URI → Bitmap） */
@Composable
private fun CaptchaImage(dataUri: String) {
    val bitmap = remember(dataUri) {
        try {
            val base64Data = if (dataUri.startsWith("data:")) {
                dataUri.substringAfter("base64,")
            } else dataUri
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "验证码",
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ════════════════════════════════════════════════
//  主页(余额 + 充值 + 账单)
// ════════════════════════════════════════════════

@Composable
private fun MainPage(state: go.examate.hyp.watercard.WaterCardUiState, vm: WaterCardViewModel) {
    LaunchedEffect(Unit) { vm.refreshAll() }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏(无标题文字,仅返回+刷新按钮)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "退出",
                onClick = { vm.logout(clearCredentials = false) }
            )
            Spacer(Modifier.weight(1f))
            LiquidGlassIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "刷新",
                onClick = { vm.refreshAll() }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 18.dp, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 余额卡片
            item { BalanceCard(state, vm) }

            // 充值卡片
            item { RechargeCard(state, vm) }

            // 账单标题(点击后才加载/展开账单)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (!state.bills.loaded && !state.bills.loading) vm.loadBills()
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("账单记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.bills.loaded) "点击刷新" else "点击查看",
                            fontSize = 12.sp,
                            color = TextHint
                        )
                    }
                    if (state.bills.loaded && state.bills.items.isNotEmpty()) {
                        Text("共 ${state.bills.items.size} 条", fontSize = 12.sp, color = TextHint)
                    }
                }
            }

            // 账单列表(仅加载过才显示)
            if (state.bills.loaded || state.bills.loading) {
                if (state.bills.loading && state.bills.items.isEmpty()) {
                    item { LoadingBlock("正在查询账单...") }
                } else if (state.bills.error.isNotBlank()) {
                    item { ErrorBanner(state.bills.error) }
                } else if (state.bills.items.isEmpty()) {
                    item { EmptyBlock("暂无账单记录") }
                } else {
                    items(state.bills.items) { bill -> BillItemCard(bill) }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun BalanceCard(state: go.examate.hyp.watercard.WaterCardUiState, vm: WaterCardViewModel) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth(), tint = AccentBlue.copy(0.06f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("钱包余额", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                if (state.wallet?.loading == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = AccentBlue
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 主余额:大号数字 + 元符号
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "¥",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = AccentBlue.copy(0.7f),
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    state.wallet?.balance ?: "0.00",
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
            }
            // 赠送余额 / 总余额(小字次要信息)
            if (!state.wallet?.giftBalance.isNullOrBlank() || !state.wallet?.totalBalance.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = Color.White.copy(0.3f), thickness = 0.5.dp
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (!state.wallet?.giftBalance.isNullOrBlank()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("赠送余额", fontSize = 10.sp, color = TextHint)
                            Text("¥${state.wallet?.giftBalance}", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (!state.wallet?.totalBalance.isNullOrBlank()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("总余额", fontSize = 10.sp, color = TextHint)
                            Text("¥${state.wallet?.totalBalance}", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (state.wallet?.error?.isNotBlank() == true) {
                Spacer(Modifier.height(6.dp))
                Text(state.wallet.error, fontSize = 11.sp, color = Error)
            }
        }
    }
}

@Composable
private fun RechargeCard(state: go.examate.hyp.watercard.WaterCardUiState, vm: WaterCardViewModel) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("充值", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(14.dp))

            // 预设金额
            val presetAmounts = listOf("10", "20", "50", "100")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetAmounts.forEach { amt ->
                    val selected = state.rechargeAmount == amt
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) AccentBlue.copy(0.12f) else Color.White.copy(0.4f))
                            .clickable { vm.updateRechargeAmount(amt) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "¥$amt",
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) AccentBlue else TextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 自定义金额
            OutlinedTextField(
                value = state.rechargeAmount,
                onValueChange = { vm.updateRechargeAmount(it.filter { c -> c.isDigit() or (c == '.') }) },
                placeholder = { Text("输入自定义金额", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = textFieldColors(),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                leadingIcon = { Text("¥", color = TextSecondary) }
            )

            Spacer(Modifier.height(14.dp))

            // 支付方式选择（支付宝在前,微信在后;选中时用各自特征色）
            Text("支付方式", fontSize = 13.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                go.examate.hyp.watercard.PayMethod.values().forEach { method ->
                    val selected = state.payMethod == method
                    // 支付宝特征色:蓝色 #1677FF;微信特征色:绿色 #07C160
                    val brandColor = when (method) {
                        go.examate.hyp.watercard.PayMethod.ALIPAY -> Color(0xFF1677FF)
                        go.examate.hyp.watercard.PayMethod.WECHAT -> Color(0xFF07C160)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) brandColor.copy(0.12f) else Color.White.copy(0.4f))
                            .clickable { vm.selectPayMethod(method) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            method.label,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) brandColor else TextPrimary
                        )
                    }
                }
            }

            // 错误提示
            if (state.rechargeError.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(state.rechargeError)
            }

            // 充值结果（下单成功后等待支付/支付中）
            state.rechargeResult?.let { order ->
                Spacer(Modifier.height(10.dp))
                FrostedGlassCard(modifier = Modifier.fillMaxWidth(), tint = SuccessBg) {
                    Column(Modifier.padding(14.dp)) {
                        Text("正在拉起支付宝...", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Success)
                        Spacer(Modifier.height(4.dp))
                        Text("订单号: ${order.tradeNo}", fontSize = 11.sp, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text("如未自动拉起,请确认已安装支付宝或检查网络", fontSize = 11.sp, color = TextHint)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 充值按钮
            if (state.rechargeLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在创建订单...", fontSize = 14.sp, color = TextSecondary)
                }
            } else {
                LiquidGlassButton(
                    text = "立即充值",
                    onClick = { vm.recharge() },
                    style = ButtonStyle.White,
                    height = 48.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BillItemCard(bill: YxtBill) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 金额图标
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (bill.isIncome) SuccessBg else ErrorBg
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (bill.isIncome) "↓" else "↑",
                    fontSize = 18.sp,
                    color = if (bill.isIncome) Success else Error
                )
            }
            Spacer(Modifier.width(14.dp))
            // 标题 + 时间
            Column(modifier = Modifier.weight(1f)) {
                Text(bill.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(Modifier.height(3.dp))
                Text(bill.time, fontSize = 11.sp, color = TextHint)
            }
            // 金额
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (bill.isIncome) "+" else "-"}¥${bill.amountText}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (bill.isIncome) Success else TextPrimary
                )
                if (bill.status.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(bill.status, fontSize = 10.sp, color = TextHint)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════
//  通用 UI 组件
// ════════════════════════════════════════════════

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorBg)
            .padding(14.dp)
    ) {
        Text(message, fontSize = 13.sp, color = Error)
    }
}

@Composable
private fun LoadingBlock(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = TextSecondary)
    }
}

@Composable
private fun EmptyBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = TextHint)
    }
}

@Composable
private fun textFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(0.7f),
    unfocusedContainerColor = Color.White.copy(0.5f),
    focusedIndicatorColor = AccentBlue,
    unfocusedIndicatorColor = Color(0xFFD1D5DB),
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentBlue,
    focusedPlaceholderColor = TextHint,
    unfocusedPlaceholderColor = TextHint
)
