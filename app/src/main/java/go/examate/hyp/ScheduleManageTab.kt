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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.ui.AddCoursePage
import go.examate.hyp.campus.ui.DatePickerDialog
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToastUtil
import java.time.LocalDate

// ════════════════════════════════════════
//  Tab 1: 课表管理(多套课表)
// ════════════════════════════════════════

/** 课表管理 Tab:展示所有课表,支持新建/切换/删除,点击进入编辑页 */
@Composable
internal fun ScheduleManageTab(onEditSchedule: (String) -> Unit) {
    val context = LocalContext.current
    var metas by remember { mutableStateOf(ScheduleStorage.loadScheduleMetas(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 40.dp)
    ) {
        Text("我的课表", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))

        metas.forEach { meta ->
            FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable {
                // 点击进入课表编辑页面
                onEditSchedule(meta.id)
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
                                    Modifier.clip(RoundedCornerShape(4.dp)).background(Primary).padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("当前", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        val reminderText = if (meta.reminderMinutes > 0) "课前${meta.reminderMinutes}分钟提醒" else "未开启提醒"
                        val weekendText = listOf(
                            if (meta.showSaturday) "六" else "",
                            if (meta.showSunday) "日" else ""
                        ).filter { it.isNotEmpty() }.joinToString("/").let { if (it.isEmpty()) "无周末" else "周末:$it" }
                        Text("$reminderText · $weekendText", color = TextHint, fontSize = 11.sp)
                    }
                    // 编辑图标(与"重命名"图标区分:此处用 Edit 表示进入课表整体编辑)
                    LiquidGlassIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "编辑",
                        onClick = { onEditSchedule(meta.id) },
                        modifier = Modifier.size(32.dp)
                    )
                    if (!meta.isCurrent) {
                        Spacer(Modifier.width(4.dp))
                        LiquidGlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "切换",
                            onClick = {
                                ScheduleStorage.setCurrentSchedule(context, meta.id)
                                metas = ScheduleStorage.loadScheduleMetas(context)
                                ToastUtil.show(context, "已切换到:${meta.name}")
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    if (metas.size > 1) {
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
                                        .background(Color.White).padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("删除课表", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "确定删除「${meta.name}」吗?该课表下所有课程将一并删除",
                                        color = TextSecondary, fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                                                if (ScheduleStorage.deleteScheduleMeta(context, meta.id)) {
                                                    metas = ScheduleStorage.loadScheduleMetas(context)
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
            text = "新建课表",
            onClick = { showAddDialog = true; newName = "" },
            style = ButtonStyle.Outline, height = 44.dp
        )
    }

    if (showAddDialog) {
        // 默认名称:设备当前年份 + 学期(上半年=2学期,下半年=1学期)
        // 例:7月→"2026年2学期课表",11月→"2026年1学期课表"
        if (newName.isBlank()) {
            val now = java.time.LocalDate.now()
            val semester = if (now.monthValue >= 8 || now.monthValue <= 1) 1 else 2
            newName = "${now.year}年第${semester}学期课表"
        }
        // 统一样式:FrostedGlassCard 风格弹窗
        androidx.compose.ui.window.Dialog(onDismissRequest = { showAddDialog = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).padding(20.dp)
            ) {
                Text("新建课表", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                                ScheduleStorage.addScheduleMeta(context, newName.trim())
                                metas = ScheduleStorage.loadScheduleMetas(context)
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
//  课表编辑页面:管理某一套课表的课程列表 + 学期/显示设置
// ════════════════════════════════════════

/** 课表编辑页面:管理某一套课表的课程列表 + 学期设置 + 显示设置 */
@Composable
internal fun ScheduleEditPage(metaId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val initialMeta = remember { ScheduleStorage.loadScheduleMetas(context).firstOrNull { it.id == metaId } }
    if (initialMeta == null) {
        // 找不到课表,通过 LaunchedEffect 安全返回(避免在 composition 期间触发状态变更)
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var meta by remember { mutableStateOf(initialMeta) }
    // 读取该课表的课程列表 + todos(每个 metaId 对应一份独立存储)
    var entries by remember { mutableStateOf(ScheduleStorage.loadEntries(context, metaId)) }
    var todos by remember { mutableStateOf(ScheduleStorage.loadTodos(context, metaId)) }
    // 统一添加/编辑课程入口(走 AddCoursePage)
    var showAddCoursePage by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ScheduleEntry?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // 开学日期选择器(用日历弹窗替代手动输入 yyyy-MM-dd)
    var showStartDatePicker by remember { mutableStateOf(false) }

    // 添加/编辑课程全屏页面(统一入口,与 ScheduleScreen 共用)
    if (showAddCoursePage) {
        val closeAddCourse: () -> Unit = { showAddCoursePage = false; editTarget = null }
        AddCoursePage(
            editEntry = editTarget,
            totalWeeks = meta.totalWeeks,
            onBack = closeAddCourse,
            onSave = { savedEntries ->
                if (editTarget != null) {
                    // 编辑模式:替换该课程
                    val updated = savedEntries.firstOrNull()
                    if (updated != null) {
                        val newEntries = entries.map { if (it.id == updated.id) updated else it }
                        entries = newEntries
                        ScheduleStorage.save(context, metaId, newEntries, todos)
                    }
                } else {
                    // 新增模式:可能多个时间段
                    val newEntries = entries + savedEntries
                    entries = newEntries
                    ScheduleStorage.save(context, metaId, newEntries, todos)
                }
                closeAddCourse()
            },
            onDelete = editTarget?.let { e ->
                {
                    val newEntries = entries.filterNot { it.id == e.id }
                    entries = newEntries
                    ScheduleStorage.save(context, metaId, newEntries, todos)
                    closeAddCourse()
                }
            }
        )
        return
    }

    // 用 Box 包裹,让悬浮按钮浮在内容之上
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏:重命名图标使用 DriveFileRenameOutline,与"编辑课程"图标(Edit)区分,
            // 避免用户忽视重命名功能(二者操作顺承,图标相近易混淆)
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
                // 学期设置卡片(移除"学期设置"分类标题,卡片自身已能表达)
                Spacer(Modifier.height(8.dp))
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        // 开学第一周星期一(用于推算当前周次 + 表头日期)
                        // 点击整行打开日历选择器,比手动输入 yyyy-MM-dd 更直观
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { showStartDatePicker = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("开学第一周星期一", color = TextHint, fontSize = 12.sp)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = meta.semesterStartDate ?: "未设置(按默认推断)",
                                    color = if (meta.semesterStartDate != null) TextPrimary else TextHint,
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                            }
                            Text("›", color = TextHint, fontSize = 18.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        // 总周数(周次选择器范围)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("学期总周数", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("‹", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.totalWeeks > 1) {
                                            meta = meta.copy(totalWeeks = meta.totalWeeks - 1)
                                            ScheduleStorage.updateScheduleMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                                Text("${meta.totalWeeks}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                                Text("›", color = TextHint, fontSize = 16.sp,
                                    modifier = Modifier.clickable {
                                        if (meta.totalWeeks < 30) {
                                            meta = meta.copy(totalWeeks = meta.totalWeeks + 1)
                                            ScheduleStorage.updateScheduleMeta(context, meta)
                                        }
                                    }.padding(4.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 显示设置卡片(移除"显示设置"分类标题)
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("显示周六", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            LiquidGlassToggle(
                                selected = meta.showSaturday,
                                onSelect = {
                                    meta = meta.copy(showSaturday = it)
                                    ScheduleStorage.updateScheduleMeta(context, meta)
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("显示周日", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            LiquidGlassToggle(
                                selected = meta.showSunday,
                                onSelect = {
                                    meta = meta.copy(showSunday = it)
                                    ScheduleStorage.updateScheduleMeta(context, meta)
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        // 课程卡片字体档(小/标准/大):字体会先按连堂节数自适应,再乘此缩放档
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("课程字体", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0 to "小", 1 to "标准", 2 to "大").forEach { (scale, label) ->
                                    val selected = meta.fontScale == scale
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selected) Primary else ScheduleCellBg)
                                            .clickable {
                                                meta = meta.copy(fontScale = scale)
                                                ScheduleStorage.updateScheduleMeta(context, meta)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("课程列表 (${entries.size})", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))

                if (entries.isEmpty()) {
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "暂无课程,点击右下角 + 添加",
                            color = TextHint, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    entries.forEach { entry ->
                        FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable {
                            // 点击课程 → 统一进入 AddCoursePage 编辑模式
                            editTarget = entry; showAddCoursePage = true
                        }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(4.dp, 36.dp).clip(RoundedCornerShape(2.dp))
                                        .background(Color(entry.color))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.courseName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(2.dp))
                                    val weekStr = if (entry.weeks.size > 5) "第${entry.weeks.first()}-${entry.weeks.last()}周" else "${entry.weeks.joinToString(",")}周"
                                    Text(
                                        "${DAY_LABELS_EDIT[entry.dayOfWeek - 1]} 第${entry.startSection}-${entry.endSection}节 · $weekStr",
                                        color = TextHint, fontSize = 10.sp
                                    )
                                }
                                Text("›", color = TextHint, fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // 悬浮添加按钮(在 Box 内,浮于内容之上)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)
        ) {
            LiquidGlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "添加课程",
                onClick = { editTarget = null; showAddCoursePage = true },
                modifier = Modifier.size(48.dp)
            )
        }
    }

    // 重命名弹窗(统一样式)
    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(meta.name) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showRenameDialog = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).padding(20.dp)
            ) {
                Text("重命名课表", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                                ScheduleStorage.updateScheduleMeta(context, meta)
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

    // 开学日期选择器(日历弹窗,替代手动输入)
    if (showStartDatePicker) {
        val initial = meta.semesterStartDate?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: LocalDate.now()
        DatePickerDialog(
            initialDate = initial,
            onDismiss = { showStartDatePicker = false },
            onConfirm = { date ->
                meta = meta.copy(semesterStartDate = date.toString())
                ScheduleStorage.updateScheduleMeta(context, meta)
                showStartDatePicker = false
            }
        )
    }
}

/** 课表编辑页用的星期标签(周一/周二/.../周日) */
internal val DAY_LABELS_EDIT = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
