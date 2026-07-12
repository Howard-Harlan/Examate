package go.examate.hyp.campus.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.PayArea
import go.examate.hyp.campus.model.PayBuilding
import go.examate.hyp.campus.model.PayResult
import go.examate.hyp.campus.model.RoomElectricity
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 一卡通功能 Tab(充值合并到余额页) */
private enum class EcardTab(val label: String) {
    BALANCE("余额"), BILL("账单"), PAYFEE("缴费")
}

/** 一卡通页面 */
@Composable
fun EcardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)
    val repo = remember { CampusRepository.getInstance() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(EcardTab.BALANCE) }

    CampusPageScaffold(
        title = "一卡通",
        onBack = onBack,
        bottomBar = {
            Spacer(Modifier.height(10.dp))
            EcardTabBar(
                tabs = EcardTab.values().toList(),
                selected = tab,
                onSelect = { tab = it }
            )
            Spacer(Modifier.height(16.dp))
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                EcardTab.BALANCE -> BalanceSection(repo, prefs, scope)
                EcardTab.BILL -> BillSection(repo, prefs, scope)
                EcardTab.PAYFEE -> PayFeeSection(repo, prefs, scope)
            }
        }
    }
}

/** 一卡通 Tab 栏 */
@Composable
private fun EcardTabBar(
    tabs: List<EcardTab>,
    selected: EcardTab,
    onSelect: (EcardTab) -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { t ->
                val isActive = t == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) Primary else Color.Transparent)
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t.label,
                        color = if (isActive) Color.White else TextHint,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  余额查询 + 充值
// ════════════════════════════════════════

@Composable
private fun BalanceSection(
    repo: CampusRepository,
    prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf<EcardBalance?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var forceRefresh by remember { mutableStateOf(false) }

    // 充值表单
    var amount by remember { mutableStateOf("") }
    var queryPwd by remember { mutableStateOf("") }
    var topping by remember { mutableStateOf(false) }
    var topResult by remember { mutableStateOf<PayResult?>(null) }
    var topError by remember { mutableStateOf("") }
    // 充值:签约银行卡选择(对齐网页端"先选卡再充值"流程)
    var topupCards by remember { mutableStateOf<List<BankCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<BankCard?>(null) }
    var showCardPicker by remember { mutableStateOf(false) }
    var loadingCards by remember { mutableStateOf(false) }

    if (!loaded && !loading && error.isEmpty()) {
        loading = true
        scope.launch {
            try {
                balance = repo.getBalance(prefs, forceRefresh = forceRefresh)
            } catch (e: CampusException) {
                error = e.message ?: "查询失败"
            } catch (e: Exception) {
                error = "网络异常: ${e.message ?: "未知错误"}"
            } finally {
                loading = false
                loaded = true
                forceRefresh = false
            }
        }
    }

    if (loading && balance == null) {
        LoadingBlock("正在查询余额...")
        return
    }
    if (error.isNotEmpty() && balance == null) {
        ErrorBlock(error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { forceRefresh = true; error = ""; loaded = false },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    Column {
        balance?.let { b ->
            // 余额大卡片
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFF374151), Color(0xFF4B5563))
                        )
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Text("账户余额", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("¥", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.size(2.dp))
                        Text(
                            b.balance.ifEmpty { "0.00" },
                            color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            b.accountName.ifEmpty { "持卡人" },
                            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
                        )
                        if (b.cardNo.isNotEmpty()) {
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "· 卡号 ${b.cardNo}",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidGlassButton(
                    text = "刷新", onClick = { forceRefresh = true; loaded = false },
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = if (loadingCards) "加载中..." else "充值",
                    onClick = {
                        if (loadingCards) return@LiquidGlassButton
                        loadingCards = true
                        topError = ""; topResult = null; selectedCard = null; topupCards = emptyList()
                        scope.launch {
                            try {
                                val cards = repo.getTopupBankCards(prefs)
                                topupCards = cards
                                when {
                                    cards.isEmpty() ->
                                        topError = "没有可用的签约银行卡,请先在一卡通网页端签约银行卡"
                                    cards.size == 1 -> { selectedCard = cards[0]; topping = true }
                                    else -> showCardPicker = true
                                }
                            } catch (e: CampusException) {
                                topError = e.message ?: "获取银行卡失败"
                            } catch (e: Exception) {
                                topError = "网络异常: ${e.message ?: "未知错误"}"
                            } finally {
                                loadingCards = false
                            }
                        }
                    },
                    style = ButtonStyle.White, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
            }
            // 银行卡列表
            Spacer(Modifier.height(12.dp))
            SectionTitle("银行卡", if (b.bankCards.isEmpty()) "" else "共 ${b.bankCards.size} 张")
            if (b.bankCards.isEmpty()) {
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "未查询到银行卡信息",
                        color = TextHint, fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                b.bankCards.forEach { card ->
                    var showFull by remember { mutableStateOf(false) }
                    FrostedGlassCard(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (card.cardNumberFull.isNotEmpty()) showFull = !showFull
                        }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    card.cardType.ifEmpty { "银行卡" },
                                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                                if (card.cardNumberMasked.isNotEmpty() || card.cardNumberFull.isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    val displayNo = if (showFull && card.cardNumberFull.isNotEmpty()) {
                                        card.cardNumberFull
                                    } else {
                                        card.cardNumberMasked.ifEmpty { card.cardNumberShort }
                                    }
                                    Text(
                                        displayNo,
                                        color = TextHint, fontSize = 12.sp
                                    )
                                }
                            }
                            if (card.cardNumberFull.isNotEmpty() && !showFull) {
                                Text("点击查看", color = Primary, fontSize = 11.sp)
                            }
                        }
                    }
                    CardSpacer()
                }
            }
        }

        // 选卡/获取卡失败提示(不在弹窗内时)
        if (topError.isNotEmpty() && !topping && !showCardPicker) {
            Spacer(Modifier.height(12.dp))
            ErrorBlock(topError)
        }

        // 充值结果
        if (topResult != null) {
            topResult?.let { r ->
                Spacer(Modifier.height(14.dp))
                PayResultBox(r, "充值")
            }
        }
    }

    // 充值弹窗
    if (topping) {
        PayFormDialog(
            title = "一卡通充值",
            amount = amount,
            onAmountChange = { if (it.all { ch -> ch.isDigit() || ch == '.' }) amount = it },
            password = queryPwd,
            onPasswordChange = { queryPwd = it },
            submitting = false,
            error = topError,
            submitText = "确认充值",
            onSubmit = {
                val amt = amount.toDoubleOrNull()
                if (amt == null || amt <= 0) {
                    topError = "请输入有效金额"; topResult = null; return@PayFormDialog
                }
                if (queryPwd.isBlank()) {
                    topError = "请输入密码"; topResult = null; return@PayFormDialog
                }
                val card = selectedCard
                if (card == null || card.cardNumberFull.isEmpty()) {
                    topError = "请选择签约银行卡"; topResult = null; return@PayFormDialog
                }
                topping = false
                topError = ""; topResult = null
                scope.launch {
                    try {
                        topResult = repo.topUp(prefs, amt, queryPwd, card.cardNumberFull)
                    } catch (e: CampusException) {
                        topError = e.message ?: "充值失败"
                    } catch (e: Exception) {
                        topError = "网络异常: ${e.message ?: "未知错误"}"
                    }
                }
            },
            onDismiss = { topping = false }
        )
    }

    // 签约银行卡选择弹窗(多张卡时)
    if (showCardPicker) {
        SingleChoiceDialog(
            title = "选择签约银行卡",
            options = topupCards.map { card ->
                val label = card.cardNumberShort.ifEmpty { "尾号 " + card.cardNumberFull.takeLast(4) }
                card to label
            },
            selected = selectedCard ?: topupCards.first(),
            onConfirm = { card ->
                selectedCard = card
                showCardPicker = false
                topping = true
            },
            onDismiss = { showCardPicker = false }
        )
    }
}

// ════════════════════════════════════════
//  账单查询(日期范围 + 类型)
// ════════════════════════════════════════

@Composable
private fun BillSection(
    repo: CampusRepository,
    prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val today = LocalDate.now()
    var startDate by remember { mutableStateOf(today.minusDays(30).toString()) }
    var endDate by remember { mutableStateOf(today.toString()) }
    var recordType by remember { mutableStateOf("") }
    var showTypePicker by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var bills by remember { mutableStateOf<List<BillRecord>>(emptyList()) }
    var queried by remember { mutableStateOf(false) }

    SectionTitle("查询条件", "选择日期范围与记录类型")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("开始日期", color = TextHint, fontSize = 12.sp)
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("YYYY-MM-DD", color = TextHint, fontSize = 13.sp) },
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("结束日期", color = TextHint, fontSize = 12.sp)
            OutlinedTextField(
                value = endDate,
                onValueChange = { endDate = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("YYYY-MM-DD", color = TextHint, fontSize = 13.sp) },
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(10.dp))
            Divider(color = DividerColor, thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            QueryParamRow(
                label = "记录类型",
                value = RECORD_TYPE_OPTIONS.first { it.first == recordType }.second,
                onClick = { showTypePicker = true }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    LiquidGlassButton(
        text = if (loading) "查询中..." else "查询账单",
        onClick = {
            if (loading) return@LiquidGlassButton
            loading = true; error = ""; queried = true
            scope.launch {
                try {
                    bills = repo.getBillRecords(prefs, startDate, endDate, recordType)
                } catch (e: CampusException) {
                    error = e.message ?: "查询失败"
                } catch (e: Exception) {
                    error = "网络异常: ${e.message ?: "未知错误"}"
                } finally {
                    loading = false
                }
            }
        },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (loading) {
        LoadingBlock()
    } else if (error.isNotEmpty()) {
        ErrorBlock(error)
    } else if (queried) {
        if (bills.isEmpty()) {
            EmptyBlock("该时间范围内暂无账单记录")
        } else {
            val (totalExpense, totalIncome) = summarize(bills)
            SectionTitle("账单汇总(共 ${bills.size} 条)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFF0F0))
                        .padding(14.dp)
                ) {
                    Column {
                        Text("支出", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("¥$totalExpense", color = Color(0xFFE53935), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE8F5E9))
                        .padding(14.dp)
                ) {
                    Column {
                        Text("充值", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("¥$totalIncome", color = Color(0xFF2E7D32), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionTitle("账单明细")
            bills.forEach { r ->
                BillItemCard(r)
                CardSpacer()
            }
        }
    }

    if (showTypePicker) {
        SingleChoiceDialog(
            title = "选择记录类型",
            options = RECORD_TYPE_OPTIONS,
            selected = recordType,
            onConfirm = { recordType = it; showTypePicker = false },
            onDismiss = { showTypePicker = false }
        )
    }
}

/** 账单项卡片 */
@Composable
private fun BillItemCard(r: BillRecord) {
    val amount = r.amount.toDoubleOrNull() ?: 0.0
    val isExpense = amount < 0 || r.description.contains("消费")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.description.ifEmpty { r.type }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(r.time, color = TextHint, fontSize = 11.sp)
            }
            Text(
                "${if (amount >= 0) "+" else ""}${r.amount}",
                color = if (isExpense) Color(0xFFE53935) else Color(0xFF2E7D32),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 计算支出与充值总额 */
private fun summarize(bills: List<BillRecord>): Pair<String, String> {
    var expense = 0.0
    var income = 0.0
    bills.forEach { r ->
        val amt = r.amount.toDoubleOrNull() ?: 0.0
        if (amt < 0) expense += -amt
        else income += amt
    }
    return "%.2f".format(expense) to "%.2f".format(income)
}

// ════════════════════════════════════════
//  生活缴费(宿舍电费等,多步选择)
// ════════════════════════════════════════

/** 缴费项目 */
private val PAY_ITEMS = listOf(
    "2" to "常工电控缴费",
    "3" to "宿舍照明电费"
)

@Composable
private fun PayFeeSection(
    repo: CampusRepository,
    prefs: PrefsManager,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var itemNum by remember { mutableStateOf("2") }
    var showItemPicker by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf<PayArea?>(null) }
    var building by remember { mutableStateOf<PayBuilding?>(null) }
    var room by remember { mutableStateOf<PayBuilding?>(null) }
    var amount by remember { mutableStateOf("") }
    var queryPwd by remember { mutableStateOf("") }

    var loadingAreas by remember { mutableStateOf(false) }
    var loadingBuildings by remember { mutableStateOf(false) }
    var loadingRooms by remember { mutableStateOf(false) }
    var areas by remember { mutableStateOf<List<PayArea>>(emptyList()) }
    var buildings by remember { mutableStateOf<List<PayBuilding>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<PayBuilding>>(emptyList()) }
    var listError by remember { mutableStateOf("") }
    var showAreaPicker by remember { mutableStateOf(false) }
    var showBuildingPicker by remember { mutableStateOf(false) }
    var showRoomPicker by remember { mutableStateOf(false) }

    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PayResult?>(null) }

    // 房间电费查询
    var elecLoading by remember { mutableStateOf(false) }
    var elecError by remember { mutableStateOf("") }
    var elecResult by remember { mutableStateOf<RoomElectricity?>(null) }

    Column {
        SectionTitle("生活缴费", "选择校区/楼栋/房间后缴费")
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                QueryParamRow(
                    label = "缴费项目",
                    value = PAY_ITEMS.first { it.first == itemNum }.second,
                    onClick = { showItemPicker = true }
                )
                Divider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "校区",
                    value = area?.name ?: "请选择",
                    onClick = {
                        if (loadingAreas) return@QueryParamRow
                        if (areas.isEmpty()) {
                            loadingAreas = true; listError = ""
                            scope.launch {
                                try {
                                    areas = repo.getPayAreas(prefs, itemNum)
                                } catch (e: CampusException) {
                                    listError = e.message ?: "查询失败"
                                } catch (e: Exception) {
                                    listError = "网络异常: ${e.message ?: "未知错误"}"
                                } finally {
                                    loadingAreas = false
                                }
                            }
                        }
                        showAreaPicker = true
                    }
                )
                Divider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "楼栋",
                    value = building?.name ?: "请选择",
                    onClick = {
                        if (area == null) return@QueryParamRow
                        if (loadingBuildings) return@QueryParamRow
                        if (buildings.isEmpty() || building == null) {
                            loadingBuildings = true; listError = ""
                            scope.launch {
                                try {
                                    buildings = repo.getBuildings(prefs, area!!.id, itemNum)
                                } catch (e: CampusException) {
                                    listError = e.message ?: "查询失败"
                                } catch (e: Exception) {
                                    listError = "网络异常: ${e.message ?: "未知错误"}"
                                } finally {
                                    loadingBuildings = false
                                }
                            }
                        }
                        showBuildingPicker = true
                    }
                )
                Divider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "房间",
                    value = room?.name ?: "请选择",
                    onClick = {
                        if (building == null) return@QueryParamRow
                        if (loadingRooms) return@QueryParamRow
                        if (rooms.isEmpty() || room == null) {
                            loadingRooms = true; listError = ""
                            scope.launch {
                                try {
                                    rooms = repo.getRooms(prefs, area!!.id, building!!.id, itemNum)
                                } catch (e: CampusException) {
                                    listError = e.message ?: "查询失败"
                                } catch (e: Exception) {
                                    listError = "网络异常: ${e.message ?: "未知错误"}"
                                } finally {
                                    loadingRooms = false
                                }
                            }
                        }
                        showRoomPicker = true
                    }
                )
            }
        }

        if (loadingAreas || loadingBuildings || loadingRooms) {
            Spacer(Modifier.height(10.dp))
            LoadingBlock("正在查询...")
        }
        if (listError.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ErrorBlock(listError)
        }
        Spacer(Modifier.height(12.dp))

        // 房间电费查询(仅当房间已选择时显示)
        if (area != null && building != null && room != null) {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("房间电费", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "查询当前房间剩余电费与昨日用电",
                        color = TextHint, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    LiquidGlassButton(
                        text = if (elecLoading) "查询中..." else "查询电费",
                        onClick = {
                            if (elecLoading) return@LiquidGlassButton
                            elecLoading = true; elecError = ""; elecResult = null
                            scope.launch {
                                try {
                                    elecResult = repo.getRoomElectricity(
                                        prefs, area!!.id, building!!.id, room!!.id, itemNum
                                    )
                                } catch (e: CampusException) {
                                    elecError = e.message ?: "查询失败"
                                } catch (e: Exception) {
                                    elecError = "网络异常: ${e.message ?: "未知错误"}"
                                } finally {
                                    elecLoading = false
                                }
                            }
                        },
                        style = ButtonStyle.Outline, height = 42.dp
                    )
                    if (elecLoading) {
                        Spacer(Modifier.height(10.dp))
                        LoadingBlock("正在查询电费...")
                    } else if (elecError.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ErrorBlock(elecError)
                    } else if (elecResult != null) {
                        elecResult?.let { e ->
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFE8F5E9))
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Text("剩余金额", color = TextHint, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text("¥${e.balance}", color = Color(0xFF2E7D32), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFFFF8E1))
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Text("昨日用电", color = TextHint, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text("${e.dailyUsed} 度", color = Color(0xFFEF6C00), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 金额 + 查询密码
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("缴费金额(元)", color = TextHint, fontSize = 12.sp)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.all { ch -> ch.isDigit() || ch == '.' }) amount = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                    placeholder = { Text("请输入金额", color = TextHint, fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("输入密码", color = TextHint, fontSize = 12.sp)
                OutlinedTextField(
                    value = queryPwd,
                    onValueChange = { queryPwd = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                    placeholder = { Text("输入密码", color = TextHint, fontSize = 13.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(16.dp))
                LiquidGlassButton(
                    text = if (submitting) "缴费中..." else "确认缴费",
                    onClick = {
                        if (submitting) return@LiquidGlassButton
                        if (area == null || building == null || room == null) {
                            submitError = "请先选择校区、楼栋和房间"; result = null; return@LiquidGlassButton
                        }
                        val amt = amount.toDoubleOrNull()
                        if (amt == null || amt <= 0) {
                            submitError = "请输入有效金额"; result = null; return@LiquidGlassButton
                        }
                        if (queryPwd.isBlank()) {
                            submitError = "请输入密码"; result = null; return@LiquidGlassButton
                        }
                        submitting = true; submitError = ""; result = null
                        scope.launch {
                            try {
                                result = repo.payFee(
                                    prefs, area!!.id, building!!.id, room!!.id,
                                    amt, queryPwd, itemNum
                                )
                            } catch (e: CampusException) {
                                submitError = e.message ?: "缴费失败"
                            } catch (e: Exception) {
                                submitError = "网络异常: ${e.message ?: "未知错误"}"
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    style = ButtonStyle.White, height = 46.dp
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (submitting) {
            LoadingBlock("正在提交缴费...")
        } else if (submitError.isNotEmpty()) {
            ErrorBlock(submitError)
        } else if (result != null) {
            result?.let { r -> PayResultBox(r, "缴费") }
        }
    }

    // 选择弹窗
    if (showItemPicker) {
        SingleChoiceDialog(
            "选择缴费项目", PAY_ITEMS, itemNum,
            { itemNum = it; showItemPicker = false; area = null; building = null; room = null; areas = emptyList(); buildings = emptyList(); rooms = emptyList() },
            { showItemPicker = false }
        )
    }
    if (showAreaPicker) {
        val opts = areas.map { it.id to it.name }
        SingleChoiceDialog(
            "选择校区", opts, area?.id ?: "",
            { id ->
                area = areas.firstOrNull { it.id == id }
                building = null; room = null; buildings = emptyList(); rooms = emptyList()
                showAreaPicker = false
            },
            { showAreaPicker = false }
        )
    }
    if (showBuildingPicker) {
        val opts = buildings.map { it.id to it.name }
        SingleChoiceDialog(
            "选择楼栋", opts, building?.id ?: "",
            { id ->
                building = buildings.firstOrNull { it.id == id }
                room = null; rooms = emptyList()
                showBuildingPicker = false
            },
            { showBuildingPicker = false }
        )
    }
    if (showRoomPicker) {
        // 房间较多:使用可搜索 + 可滚动列表弹窗
        SearchableListDialog(
            title = "选择房间",
            items = rooms,
            selectedId = room?.id ?: "",
            onConfirm = { id ->
                room = rooms.firstOrNull { it.id == id }
                elecResult = null; elecError = ""
                showRoomPicker = false
            },
            onDismiss = { showRoomPicker = false }
        )
    }
}

// ════════════════════════════════════════
//  通用:可搜索列表弹窗(房间较多时)/ 金额密码表单弹窗 / 结果展示
// ════════════════════════════════════════

/**
 * 可搜索 + 可滚动的列表选择弹窗,适用于房间等选项较多的场景。
 */
@Composable
private fun SearchableListDialog(
    title: String,
    items: List<PayBuilding>,
    selectedId: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(items.let { "" }) }
    var cur by remember { mutableStateOf(selectedId) }
    val filtered = remember(query, items) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { it.name.contains(q) || it.id.contains(q) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(vertical = 8.dp)
        ) {
            Text(
                title,
                color = TextHint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜索房间号/名称", color = TextHint, fontSize = 13.sp) },
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            // 列表区域可滚动,限制最大高度
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
            ) {
                items(filtered) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { cur = item.id }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.name.ifEmpty { item.id }, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (item.id == cur) {
                            Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "无匹配房间",
                            color = TextHint, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
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
                    text = "确认", onClick = { onConfirm(cur) },
                    style = ButtonStyle.White, height = 42.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 充值/缴费表单弹窗(金额 + 查询密码 + 快捷金额)。
 */
@Composable
private fun PayFormDialog(
    title: String,
    amount: String,
    onAmountChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    submitting: Boolean,
    error: String,
    submitText: String,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Text("金额(元)", color = TextHint, fontSize = 12.sp)
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("如 50.00", color = TextHint, fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("10", "20", "50", "100").forEach { q ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF3F4F6))
                            .clickable { onAmountChange(q) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("¥$q", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("输入密码", color = TextHint, fontSize = 12.sp)
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("输入密码", color = TextHint, fontSize = 13.sp) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(10.dp)
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Color(0xFFE53935), fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(16.dp))
            LiquidGlassButton(
                text = if (submitting) "处理中..." else submitText,
                onClick = onSubmit,
                style = ButtonStyle.White, height = 46.dp
            )
        }
    }
}

/** 充值/缴费结果展示卡片 */
@Composable
private fun PayResultBox(r: PayResult, label: String) {
    val bgColor = if (r.success) Color(0xFFE8F5E9) else Color(0xFFFFF0F0)
    val fgColor = if (r.success) Color(0xFF2E7D32) else Color(0xFFE53935)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (r.success) "✓ " else "✗ ",
                    color = fgColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (r.success) "${label}成功" else "${label}失败",
                    color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(6.dp))
            if (r.amount.isNotEmpty()) {
                Text("金额:¥${r.amount}", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(3.dp))
            }
            Text(
                r.message.ifEmpty { if (r.success) "已提交" else "请稍后重试" },
                color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }
}
