package go.examate.hyp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.ScheduleEntry
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.ScheduleTodo
import go.examate.hyp.notes.NoteStorage
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Divider
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.CountdownStore
import go.examate.hyp.util.HomeSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 首页"Daily 卡片"区 — 可自定义添加/移除显示源。
 *
 * 卡片整体可点(进入对应功能),待办项可单独勾选完成。
 */
@Composable
fun HomeSourceSection(
    onOpenTodo: () -> Unit,
    onOpenNotes: (String?) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenMemo: () -> Unit = {}
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    val enabledIds by produceState(initialValue = emptySet<String>(), tick) {
        value = HomeSourceStore.enabledIds(context)
    }

    Column(Modifier.fillMaxWidth()) {
        // ═══ 区头 ═══
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Daily", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "＋ 添加",
                color = AccentBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        // ═══ 已启用卡片 ═══
        val defs = HomeSourceStore.ALL.filter { it.id in enabledIds }
        if (defs.isEmpty()) {
            FrostedGlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有卡片", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("点击右上角「添加」选择显示源", color = TextHint, fontSize = 11.sp)
                }
            }
        } else {
            val left = defs.filterIndexed { i, _ -> i % 2 == 0 }
            val right = defs.filterIndexed { i, _ -> i % 2 == 1 }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    left.forEach { def ->
                        SourceCard(
                            modifier = Modifier.fillMaxWidth(),
                            def = def,
                            onRemove = { HomeSourceStore.remove(context, def.id); tick++ },
                            onOpenTodo = onOpenTodo,
                            onOpenNotes = onOpenNotes,
                            onOpenSchedule = onOpenSchedule,
                            onOpenMemo = onOpenMemo
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    right.forEach { def ->
                        SourceCard(
                            modifier = Modifier.fillMaxWidth(),
                            def = def,
                            onRemove = { HomeSourceStore.remove(context, def.id); tick++ },
                            onOpenTodo = onOpenTodo,
                            onOpenNotes = onOpenNotes,
                            onOpenSchedule = onOpenSchedule,
                            onOpenMemo = onOpenMemo
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    // ═══ 添加显示源弹窗 ═══
    if (showAddDialog) {
        PlainDialog(onDismissRequest = { showAddDialog = false }) {
            PlainDialogTitle("添加显示源")
            HomeSourceStore.ALL.filter { it.id !in enabledIds }.forEach { def ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            HomeSourceStore.add(context, def.id)
                            tick++
                            showAddDialog = false
                        }
                        .padding(horizontal = 24.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) { Text(def.emoji, fontSize = 15.sp) }
                    Spacer(Modifier.width(12.dp))
                    Text(def.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("＋", color = AccentBlue, fontSize = 16.sp)
                }
            }
            if (HomeSourceStore.ALL.all { it.id in enabledIds }) {
                Text("所有显示源都已添加", color = TextHint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            PlainDialogButtons(
                buttons = listOf(Triple("完成", true) { showAddDialog = false })
            )
        }
    }
}

/** 单个显示源卡片:头部 + 数据区;整卡可点进入对应功能 */
@Composable
private fun SourceCard(
    modifier: Modifier = Modifier,
    def: HomeSourceStore.SourceDef,
    onRemove: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenNotes: (String?) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenMemo: () -> Unit
) {
    val context = LocalContext.current
    val accent = when (def.id) {
        "todo" -> Color(0xFF5B8DEF)
        "countdown" -> Color(0xFF9B7BE8)
        "notes" -> Color(0xFF6EC9B4)
        else -> Color(0xFFE39B5B)
    }

    FrostedGlassCard(
        modifier = modifier
            .clickable {
                when (def.id) {
                    "todo" -> onOpenTodo()
                    "schedule" -> onOpenSchedule()
                    else -> Unit
                }
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            // 头部:彩色圆点 + 标题 + 移除
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(def.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "✕",
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onRemove() }
                        .padding(6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Spacer(Modifier.height(10.dp))
            // 数据区
            when (def.id) {
                "todo" -> TodoCardBody(context)
                "countdown" -> CountdownCardBody(context, onOpenMemo)
                "notes" -> NotesCardBody(context, onOpenNotes)
                "schedule" -> ScheduleCardBody(context)
            }
        }
    }
}

// ════════════════════════════════════════
//  待办卡片(数据同步 ScheduleStorage)
// ════════════════════════════════════════

@Composable
private fun TodoCardBody(context: android.content.Context) {
    var tick by remember { mutableIntStateOf(0) }
    val storageKey = remember { ScheduleStorage.getCurrentScheduleMeta(context).id }
    val todos by produceState(initialValue = emptyList<ScheduleTodo>(), tick) {
        value = withContext(Dispatchers.IO) {
            ScheduleStorage.loadTodos(context, storageKey)
                .filter { !it.done }
                .sortedBy { "${it.date}${it.time}" }
        }
    }
    val todayStr = LocalDate.now().toString()
    val todayTodos = todos.filter { it.date == todayStr }
    val upcoming = todos.filter { it.date > todayStr }.take(3)
    val display = (todayTodos + upcoming).distinctBy { it.id }.take(3)

    if (display.isEmpty()) {
        Text("今天没有待办,好好休息 ✨", color = TextHint, fontSize = 12.sp)
    } else {
        display.forEach { todo ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val entries = ScheduleStorage.loadEntries(context, storageKey)
                        val updated = ScheduleStorage.loadTodos(context, storageKey).map {
                            if (it.id == todo.id) it.copy(done = true) else it
                        }
                        ScheduleStorage.save(context, storageKey, entries, updated)
                        tick++
                    }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(15.dp).clip(CircleShape).background(Success.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Success, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    todo.title,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (todo.date == todayStr) "今天" else todo.date.substring(5),
                    color = TextHint,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ════════════════════════════════════════
//  倒数日卡片
// ════════════════════════════════════════

@Composable
private fun CountdownCardBody(context: android.content.Context, onOpenMemo: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val items by produceState(initialValue = emptyList<CountdownStore.Countdown>(), tick) {
        value = withContext(Dispatchers.IO) { CountdownStore.list(context) }
    }

    if (items.isEmpty()) {
        Text(
            "还没有倒数日,点击去速记添加",
            color = TextHint, fontSize = 12.sp,
            modifier = Modifier.clickable { onOpenMemo() }
        )
    } else {
        items.take(3).forEach { c ->
            val d = c.daysLeft()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenMemo() }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(c.emoji, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(c.name, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    when {
                        d == 0L -> "就是今天"
                        d > 0 -> "还有 ${d} 天"
                        else -> "已过 ${-d} 天"
                    },
                    color = if (d == 0L) Danger else TextHint,
                    fontSize = 10.sp,
                    fontWeight = if (d == 0L) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}


// ════════════════════════════════════════
//  最近笔记卡片
// ════════════════════════════════════════

@Composable
private fun NotesCardBody(context: android.content.Context, onOpenNotes: (String?) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val notes by produceState(initialValue = emptyList<NoteStorage.Note>(), tick) {
        value = withContext(Dispatchers.IO) { NoteStorage.listNotes(context).take(2) }
    }
    if (notes.isEmpty()) {
        Text("还没有笔记,去 Deep Learning 里写一篇吧", color = TextHint, fontSize = 12.sp)
    } else {
        notes.forEach { n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenNotes(n.id) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(TextHint))
                Spacer(Modifier.width(8.dp))
                Text(n.title.ifEmpty { "无标题" }, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("›", color = TextHint, fontSize = 13.sp)
            }
        }
    }
}

// ════════════════════════════════════════
//  今日课程卡片
// ════════════════════════════════════════

@Composable
private fun ScheduleCardBody(context: android.content.Context) {
    var tick by remember { mutableIntStateOf(0) }
    val storageKey = remember { ScheduleStorage.getCurrentScheduleMeta(context).id }
    val courses by produceState(initialValue = emptyList<ScheduleEntry>(), tick) {
        value = withContext(Dispatchers.IO) {
            val day = LocalDate.now().dayOfWeek.value
            ScheduleStorage.loadEntries(context, storageKey)
                .filter { it.dayOfWeek == day }
                .sortedBy { it.startSection }
        }
    }
    if (courses.isEmpty()) {
        Text("今天没有课程 🎉", color = TextHint, fontSize = 12.sp)
    } else {
        courses.take(3).forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(c.color)))
                Spacer(Modifier.width(8.dp))
                Text(c.courseName, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${c.startSection}-${c.endSection}节", color = TextHint, fontSize = 10.sp)
            }
        }
    }
}
