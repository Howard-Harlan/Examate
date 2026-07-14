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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.PayArea
import go.examate.hyp.campus.model.PayBuilding
import go.examate.hyp.campus.model.PayResult
import go.examate.hyp.campus.model.RoomElectricity
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.Error
import go.examate.hyp.ui.theme.ErrorBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.SuccessBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.ui.theme.Warning
import java.time.LocalDate

/** 一卡通功能 Tab(充值合并到余额页) */
private enum class EcardTab(val label: String) {
    BALANCE("余额"), BILL("账单"), PAYFEE("缴费")
}

/** 一卡通页面 */
@Composable
fun EcardScreen(onBack: () -> Unit) {
    val viewModel: EcardViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(EcardTab.BALANCE) }

    CampusPageScaffold(
        title = "一卡通",
        onBack = onBack,
        bottomBar = {
            Spacer(Modifier.height(10.dp))
            GlassTabBar(
                tabs = EcardTab.values().map { it.label },
                selectedIndex = tab.ordinal,
                onSelect = { tab = EcardTab.values()[it] }
            )
            Spacer(Modifier.height(16.dp))
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                EcardTab.BALANCE -> BalanceSection(state.balance, viewModel)
                EcardTab.BILL -> BillSection(state.bill, viewModel)
                EcardTab.PAYFEE -> PayFeeSection(state.payFee, viewModel)
            }
        }
    }
}

// ════════════════════════════════════════
//  余额查询 + 充值
// ════════════════════════════════════════

@Composable
private fun BalanceSection(state: BalanceState, vm: EcardViewModel) {
    LaunchedEffect(Unit) { vm.loadBalance() }

    if (state.loading && state.balance == null) {
        LoadingBlock("正在查询余额...")
        return
    }
    if (state.error.isNotEmpty() && state.balance == null) {
        ErrorBlock(state.error)
        Spacer(Modifier.height(10.dp))
        LiquidGlassButton(
            text = "重试", onClick = { vm.retryBalance() },
            style = ButtonStyle.Outline, height = 40.dp
        )
        return
    }

    Column {
        state.balance?.let { b ->
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
                    text = "刷新", onClick = { vm.refreshBalance() },
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = if (state.loadingCards) "加载中..." else "充值",
                    onClick = { vm.loadTopupCards() },
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
        if (state.topError.isNotEmpty() && !state.topping && !state.showCardPicker) {
            Spacer(Modifier.height(12.dp))
            ErrorBlock(state.topError)
        }

        // 充值结果
        if (state.topResult != null) {
            state.topResult?.let { r ->
                Spacer(Modifier.height(14.dp))
                PayResultBox(r, "充值")
            }
        }
    }

    // 充值弹窗
    if (state.topping) {
        PayFormDialog(
            title = "一卡通充值",
            amount = state.amount,
            onAmountChange = { if (it.all { ch -> ch.isDigit() || ch == '.' }) vm.setTopAmount(it) },
            password = state.queryPwd,
            onPasswordChange = { vm.setTopQueryPwd(it) },
            submitting = false,
            error = state.topError,
            submitText = "确认充值",
            onSubmit = { vm.submitTopUp() },
            onDismiss = { vm.dismissTopping() }
        )
    }

    // 签约银行卡选择弹窗(多张卡时)
    if (state.showCardPicker) {
        SingleChoiceDialog(
            title = "选择签约银行卡",
            options = state.topupCards.map { card ->
                val label = card.cardNumberShort.ifEmpty { "尾号 " + card.cardNumberFull.takeLast(4) }
                card to label
            },
            selected = state.selectedCard ?: state.topupCards.first(),
            onConfirm = { card -> vm.selectTopupCard(card) },
            onDismiss = { vm.dismissCardPicker() }
        )
    }
}

// ════════════════════════════════════════
//  账单查询(日期范围 + 类型)
// ════════════════════════════════════════

@Composable
private fun BillSection(state: BillState, vm: EcardViewModel) {
    SectionTitle("查询条件", "选择日期范围与记录类型")
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            QueryParamRow(label = "开始日期", value = state.startDate,
                onClick = { vm.showStartDatePicker(true) })
            QueryParamRow(label = "结束日期", value = state.endDate,
                onClick = { vm.showEndDatePicker(true) })
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            QueryParamRow(
                label = "记录类型",
                value = RECORD_TYPE_OPTIONS.first { it.first == state.recordType }.second,
                onClick = { vm.showBillTypePicker(true) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    LiquidGlassButton(
        text = if (state.loading) "查询中..." else "查询账单",
        onClick = { vm.queryBills() },
        style = ButtonStyle.White, height = 44.dp
    )
    Spacer(Modifier.height(14.dp))

    if (state.loading) {
        LoadingBlock()
    } else if (state.error.isNotEmpty()) {
        ErrorBlock(state.error)
    } else if (state.queried) {
        if (state.bills.isEmpty()) {
            EmptyBlock("该时间范围内暂无账单记录")
        } else {
            val (totalExpense, totalIncome) = summarize(state.bills)
            SectionTitle("账单汇总(共 ${state.bills.size} 条)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ErrorBg)
                        .padding(14.dp)
                ) {
                    Column {
                        Text("支出", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("¥$totalExpense", color = Error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SuccessBg)
                        .padding(14.dp)
                ) {
                    Column {
                        Text("充值", color = TextHint, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("¥$totalIncome", color = Success, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionTitle("账单明细")
            state.bills.forEach { r ->
                BillItemCard(r)
                CardSpacer()
            }
        }
    }

    if (state.showTypePicker) {
        SingleChoiceDialog(
            title = "选择记录类型",
            options = RECORD_TYPE_OPTIONS,
            selected = state.recordType,
            onConfirm = { vm.setBillRecordType(it) },
            onDismiss = { vm.showBillTypePicker(false) }
        )
    }

    if (state.datePickerField.isNotEmpty()) {
        val initialMillis = try {
            LocalDate.parse(if (state.datePickerField == "start") state.startDate else state.endDate)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { java.time.LocalDate.now().minusDays(30).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = {
                if (state.datePickerField == "start") vm.showStartDatePicker(false)
                else vm.showEndDatePicker(false)
            },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val dt = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        if (state.datePickerField == "start") vm.setBillStartDate(dt.toString())
                        else vm.setBillEndDate(dt.toString())
                    }
                    if (state.datePickerField == "start") vm.showStartDatePicker(false)
                    else vm.showEndDatePicker(false)
                }) { Text("确定", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (state.datePickerField == "start") vm.showStartDatePicker(false)
                    else vm.showEndDatePicker(false)
                }) { Text("取消", color = TextSecondary) }
            }
        ) {
            DatePicker(state = pickerState)
        }
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
                color = if (isExpense) Error else Success,
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
private fun PayFeeSection(state: PayFeeState, vm: EcardViewModel) {
    Column {
        SectionTitle("生活缴费", "选择校区/楼栋/房间后缴费")
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                QueryParamRow(
                    label = "缴费项目",
                    value = PAY_ITEMS.first { it.first == state.itemNum }.second,
                    onClick = { vm.showItemPicker(true) }
                )
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "校区",
                    value = state.area?.name ?: "请选择",
                    onClick = { vm.onAreaClick() }
                )
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "楼栋",
                    value = state.building?.name ?: "请选择",
                    onClick = { vm.onBuildingClick() }
                )
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                QueryParamRow(
                    label = "房间",
                    value = state.room?.name ?: "请选择",
                    onClick = { vm.onRoomClick() }
                )
            }
        }

        if (state.loadingAreas || state.loadingBuildings || state.loadingRooms) {
            Spacer(Modifier.height(10.dp))
            LoadingBlock("正在查询...")
        }
        if (state.listError.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ErrorBlock(state.listError)
        }
        Spacer(Modifier.height(12.dp))

        // 房间电费查询(仅当房间已选择时显示)
        if (state.area != null && state.building != null && state.room != null) {
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
                        text = if (state.elecLoading) "查询中..." else "查询电费",
                        onClick = { vm.queryElectricity() },
                        style = ButtonStyle.Outline, height = 42.dp
                    )
                    if (state.elecLoading) {
                        Spacer(Modifier.height(10.dp))
                        LoadingBlock("正在查询电费...")
                    } else if (state.elecError.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ErrorBlock(state.elecError)
                    } else if (state.elecResult != null) {
                        state.elecResult?.let { e ->
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(SuccessBg)
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Text("剩余金额", color = TextHint, fontSize = 11.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text("¥${e.balance}", color = Success, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                                        Text("${e.dailyUsed} 度", color = Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    value = state.amount,
                    onValueChange = { if (it.all { ch -> ch.isDigit() || ch == '.' }) vm.setPayAmount(it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                    placeholder = { Text("请输入金额", color = TextHint, fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("输入密码", color = TextHint, fontSize = 12.sp)
                OutlinedTextField(
                    value = state.queryPwd,
                    onValueChange = { vm.setPayPwd(it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                    placeholder = { Text("输入密码", color = TextHint, fontSize = 13.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(16.dp))
                LiquidGlassButton(
                    text = if (state.submitting) "缴费中..." else "确认缴费",
                    onClick = { vm.submitPayFee() },
                    style = ButtonStyle.White, height = 46.dp
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (state.submitting) {
            LoadingBlock("正在提交缴费...")
        } else if (state.submitError.isNotEmpty()) {
            ErrorBlock(state.submitError)
        } else if (state.result != null) {
            state.result?.let { r -> PayResultBox(r, "缴费") }
        }
    }

    // 选择弹窗
    if (state.showItemPicker) {
        SingleChoiceDialog(
            "选择缴费项目", PAY_ITEMS, state.itemNum,
            { vm.setItemNum(it) },
            { vm.showItemPicker(false) }
        )
    }
    if (state.showAreaPicker) {
        val opts = state.areas.map { it.id to it.name }
        SingleChoiceDialog(
            "选择校区", opts, state.area?.id ?: "",
            { id -> vm.selectArea(id) },
            { vm.dismissAreaPicker() }
        )
    }
    if (state.showBuildingPicker) {
        val opts = state.buildings.map { it.id to it.name }
        SingleChoiceDialog(
            "选择楼栋", opts, state.building?.id ?: "",
            { id -> vm.selectBuilding(id) },
            { vm.dismissBuildingPicker() }
        )
    }
    if (state.showRoomPicker) {
        // 房间较多:使用可搜索 + 可滚动列表弹窗
        SearchableListDialog(
            title = "选择房间",
            items = state.rooms,
            selectedId = state.room?.id ?: "",
            onConfirm = { id -> vm.selectRoom(id) },
            onDismiss = { vm.dismissRoomPicker() }
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
                            .background(SurfaceVariant)
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
                Text(error, color = Error, fontSize = 12.sp, lineHeight = 17.sp)
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
    val bgColor = if (r.success) SuccessBg else ErrorBg
    val fgColor = if (r.success) Success else Error
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
