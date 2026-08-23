package go.examate.hyp

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil

// ════════════════════════════════════════
//  Tab 2: 时间设置(多套时间表,架构同课表管理)
// ════════════════════════════════════════

/** 时间设置 Tab:展示所有时间表,支持新建/切换/删除,点击进入编辑页 */
@Composable
internal fun TimeSettingsTab(onEditTimeTable: (String) -> Unit) {
    val context = LocalContext.current
    var metas by remember { mutableStateOf(ScheduleStorage.loadTimeTableMetas(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 40.dp)
    ) {
        Text("我的时间表", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))

        metas.forEach { meta ->
            FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable {
                onEditTimeTable(meta.id)
            }) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(meta.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (meta.isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(4.dp)).background(go.examate.hyp.ui.theme.SelectedBg).padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("当前", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("${meta.sectionCount} 节 · 每节 ${meta.classDurationMinutes} 分钟", color = TextHint, fontSize = 11.sp)
                    }
                    // 编辑图标(进入时间表编辑页,与课表管理一栏保持一致用 Edit)
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "编辑",
                        onClick = { onEditTimeTable(meta.id) },
                        modifier = Modifier.size(32.dp)
                    )
                    if (!meta.isCurrent) {
                        Spacer(Modifier.width(4.dp))
                        LiquidGlassIconButton(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = "置顶",
                            onClick = {
                                ScheduleStorage.setCurrentTimeTable(context, meta.id)
                                metas = ScheduleStorage.loadTimeTableMetas(context)
                                ToastUtil.show(context, "已切换到:${meta.name}")
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    if (metas.size > 1 && !meta.isCurrent) {
                        Spacer(Modifier.width(4.dp))
                        var showDeleteConfirm by remember(meta.id) { mutableStateOf(false) }
                        LiquidGlassIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "删除",
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.size(36.dp)
                        )
                        if (showDeleteConfirm) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteConfirm = false }) {
                                Column(
                                    Modifier.width(260.dp).clip(RoundedCornerShape(20.dp))
                                        .background(CardBg).padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("删除时间表", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "确定删除「${meta.name}」吗?",
                                        color = TextSecondary, fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LiquidGlassButton(
                                            text = "取消", onClick = { showDeleteConfirm = false },
                                            style = ButtonStyle.Outline, height = 40.dp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        LiquidGlassButton(
                                            text = "删除", onClick = {
                                                if (ScheduleStorage.deleteTimeTableMeta(context, meta.id)) {
                                                    metas = ScheduleStorage.loadTimeTableMetas(context)
                                                }
                                                showDeleteConfirm = false
                                            },
                                            style = ButtonStyle.White, height = 40.dp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        LiquidGlassButton(
            text = "新建时间表",
            onClick = { showAddDialog = true; newName = "" },
            style = ButtonStyle.Outline, height = 44.dp
        )
    }

    if (showAddDialog) {
        if (newName.isBlank()) {
            newName = "自定义时间表"
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAddDialog = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(CardBg).padding(20.dp)
            ) {
                Text("新建时间表", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidGlassButton(
                        text = "取消", onClick = { showAddDialog = false },
                        style = ButtonStyle.Outline, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "创建", onClick = {
                            if (newName.isNotBlank()) {
                                ScheduleStorage.addTimeTableMeta(context, newName.trim())
                                metas = ScheduleStorage.loadTimeTableMetas(context)
                            }
                            showAddDialog = false
                        },
                        style = ButtonStyle.White, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════
//  时间表编辑页面:管理某一套时间表的节次时间 + 节数 + 课时长
// ════════════════════════════════════════

/** 时间表编辑页面:编辑某一套时间表的节数、单节课时长、各节次时间 */
@Composable
internal fun TimeTableEditPage(metaId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val initialMeta = remember { ScheduleStorage.loadTimeTableMetas(context).firstOrNull { it.id == metaId } }
    if (initialMeta == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var meta by remember { mutableStateOf(initialMeta) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏(与课表编辑页一致)
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
                    text = meta.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                LiquidGlassIconButton(
                    icon = Icons.Filled.DriveFileRenameOutline,
                    contentDescription = "重命名",
                    onClick = { showRenameDialog = true },
                    modifier = Modifier.size(36.dp)
                )
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 80.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                // 节数 + 单节课时长 设置卡片
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        // 节数 stepper(默认 12,可增减;范围 1~20)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("课程节数", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("‹", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.sectionCount > 1) {
                                            val newCount = meta.sectionCount - 1
                                            // 删除最后一节的 periods
                                            val newPeriods = meta.periods.dropLast(1)
                                            meta = meta.copy(sectionCount = newCount, periods = newPeriods)
                                            ScheduleStorage.updateTimeTableMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                                Text("${meta.sectionCount}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                                Text("›", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.sectionCount < 20) {
                                            val newCount = meta.sectionCount + 1
                                            // 新增一节:基于上一节结束时间 + 10 分钟课间 + 课时长 推算
                                            val newPeriods = meta.periods.toMutableList()
                                            val newPeriod = if (meta.periods.isNotEmpty()) {
                                                val lastEnd = meta.periods.last().second
                                                val newStart = shiftTime(lastEnd, 10) // +10 分钟课间
                                                val newEnd = shiftTime(newStart, meta.classDurationMinutes)
                                                newStart to newEnd
                                            } else {
                                                "08:00" to shiftTime("08:00", meta.classDurationMinutes)
                                            }
                                            newPeriods.add(newPeriod)
                                            meta = meta.copy(sectionCount = newCount, periods = newPeriods)
                                            ScheduleStorage.updateTimeTableMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // 单节课时长 stepper(默认 45 分钟,范围 30~120)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("单节课时长", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("‹", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.classDurationMinutes > 30) {
                                            meta = meta.copy(classDurationMinutes = meta.classDurationMinutes - 5)
                                            ScheduleStorage.updateTimeTableMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                                Text("${meta.classDurationMinutes}分钟", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
                                Text("›", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.classDurationMinutes < 120) {
                                            meta = meta.copy(classDurationMinutes = meta.classDurationMinutes + 5)
                                            ScheduleStorage.updateTimeTableMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("节次时间(可自定义)", color = TextHint, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))

                // 各节次时间编辑(只显示 sectionCount 个)
                for (i in 0 until meta.sectionCount) {
                    val period = meta.periods.getOrNull(i) ?: ("" to "")
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("第${i + 1}节", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(50.dp))
                            TimeInput(period.first, { v ->
                                val newPeriods = meta.periods.toMutableList().also {
                                    while (it.size <= i) it.add("" to "")
                                    it[i] = v to it[i].second
                                }
                                meta = meta.copy(periods = newPeriods)
                            }, Modifier.weight(1f))
                            Text("—", color = TextHint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
                            TimeInput(period.second, { v ->
                                val newPeriods = meta.periods.toMutableList().also {
                                    while (it.size <= i) it.add("" to "")
                                    it[i] = it[i].first to v
                                }
                                meta = meta.copy(periods = newPeriods)
                            }, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(16.dp))
                LiquidGlassButton(
                    text = "保存",
                    onClick = {
                        ScheduleStorage.updateTimeTableMeta(context, meta)
                        ToastUtil.show(context, "已保存")
                    },
                    style = ButtonStyle.White, height = 44.dp
                )
            }
        }
    }

    // 重命名弹窗
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(meta.name) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showRenameDialog = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(CardBg).padding(20.dp)
            ) {
                Text("重命名时间表", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidGlassButton(
                        text = "取消", onClick = { showRenameDialog = false },
                        style = ButtonStyle.Outline, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "保存", onClick = {
                            if (renameText.isNotBlank()) {
                                meta = meta.copy(name = renameText.trim())
                                ScheduleStorage.updateTimeTableMeta(context, meta)
                            }
                            showRenameDialog = false
                        },
                        style = ButtonStyle.White, height = 40.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 时间输入框(HH:MM 自动格式化)
 *
 * 关键实现:用 [TextFieldValue] 显式维护光标位置。
 * 若只用 String,格式化后重组会把光标重置到开头,导致后续输入被插到最前面
 * (例如输入 "25" 会变成 "52")。这里每次都把 selection 固定到文本末尾。
 */
@Composable
private fun TimeInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // 内部维护 TextFieldValue,光标始终在末尾
    var fieldValue by remember(value) {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { newValue ->
            // 只保留数字,自动格式化为 HH:MM,冒号由程序插入不会被删除
            val digits = newValue.text.filter { it.isDigit() }.take(4)
            val formatted = when (digits.length) {
                0, 1, 2 -> digits
                3 -> "${digits.take(2)}:${digits.takeLast(1)}"
                else -> "${digits.take(2)}:${digits.takeLast(2)}"
            }
            // 光标固定到末尾,避免格式化后光标回跳导致输入顺序错乱
            fieldValue = TextFieldValue(formatted, TextRange(formatted.length))
            onValueChange(formatted)
        },
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center)
    )
}

/**
 * 将 HH:MM 时间字符串加/减指定分钟数(仅加法用到这里,但保留通用)。
 * 用于新增节次时基于上一节结束时间 + 课间推算下一节开始时间。
 */
private fun shiftTime(time: String, addMinutes: Int): String {
    val parts = time.split(":")
    if (parts.size != 2) return time
    val h = parts[0].toIntOrNull() ?: return time
    val m = parts[1].toIntOrNull() ?: return time
    val total = h * 60 + m + addMinutes
    val newH = (total / 60).coerceIn(0, 23)
    val newM = total % 60
    return "%02d:%02d".format(newH, newM)
}
