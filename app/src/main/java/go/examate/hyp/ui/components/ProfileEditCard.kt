package go.examate.hyp.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.AvatarHelper
import go.examate.hyp.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 个人资料编辑卡片 — 头像 + 昵称 + 生日,用户设置与欢迎页共用。
 *
 * 自包含:内部持有头像/昵称/生日的本地状态与图片选择 launcher,
 * 变更即时写入 PrefsManager(头像经 AvatarHelper),无需调用方管理状态。
 *
 * @param compact true 时用于欢迎页(略紧凑,去掉"保存/清空"按钮行,昵称随输入即存)
 */
@Composable
fun ProfileEditCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager.getInstance(context) }

    var nickInput by remember { mutableStateOf(prefs.nickname) }
    var birthday by remember { mutableStateOf(prefs.birthday) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(AvatarHelper.loadAvatar(context)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showClearBirthdayConfirm by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { AvatarHelper.saveAvatar(context, uri) }
                if (ok) {
                    avatarBitmap = AvatarHelper.loadAvatar(context)
                    ToastUtil.show(context, "头像已更新")
                } else ToastUtil.show(context, "头像保存失败")
            }
        }
    }

    FrostedGlassCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像(点击换图)
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(SurfaceVariant)
                        .clickable { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = avatarBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(Icons.Filled.Person, contentDescription = "头像", tint = TextHint, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = {
                        nickInput = it
                        if (compact) prefs.nickname = it.trim()  // 欢迎页随输入即存
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("设置昵称", color = TextHint, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 生日行(点击弹年月日滚轮选择器)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentBlue.copy(0.06f))
                    .clickable { showDatePicker = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Cake, contentDescription = "生日", tint = AccentBlue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("生日", color = TextHint, fontSize = 11.sp)
                    Text(
                        text = if (birthday.isEmpty()) "未设置" else "$birthday   ${zodiacOf(birthday)}",
                        color = if (birthday.isEmpty()) TextHint else TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 已设置生日时显示清空按钮(点击弹二次确认)
                if (birthday.isNotEmpty()) {
                    Text(
                        "清空", color = Danger, fontSize = 12.sp,
                        modifier = Modifier.clickable { showClearBirthdayConfirm = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Light)
            }

            if (!compact) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassButton(
                        text = "保存",
                        onClick = {
                            prefs.nickname = nickInput.trim()
                            ToastUtil.show(context, "已保存")
                        },
                        style = ButtonStyle.White,
                        height = 42.dp,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "清空",
                        onClick = { nickInput = ""; prefs.nickname = "" },
                        style = ButtonStyle.Outline,
                        height = 42.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 生日选择弹窗:年/月/日三列滚轮
    if (showDatePicker) {
        // 默认:已设置则用已设置日期,否则当前年份减20年的1月1日
        val initial = birthday.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.of(LocalDate.now().year - 20, 1, 1)
        BirthdayPickerDialog(
            initialDate = initial,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                birthday = date.toString()
                prefs.birthday = birthday
                showDatePicker = false
            }
        )
    }

    // 清空生日二次确认
    if (showClearBirthdayConfirm) {
        ConfirmDialog(
            title = "清空生日",
            message = "确定清空生日信息吗?将同时移除星座显示。",
            onConfirm = {
                birthday = ""
                prefs.birthday = ""
                showClearBirthdayConfirm = false
                ToastUtil.show(context, "已清空生日")
            },
            onDismiss = { showClearBirthdayConfirm = false }
        )
    }
}

/**
 * 生日选择弹窗 — 年/月/日三列滚轮(类似 iOS picker),样式符合项目设计。
 */
@Composable
private fun BirthdayPickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val now = LocalDate.now()
    // 年份范围:1920 ~ 当前年份
    val years = (1920..now.year).toList()
    val months = (1..12).toList()
    var selectedYear by remember { mutableStateOf(initialDate.year) }
    var selectedMonth by remember { mutableStateOf(initialDate.monthValue) }
    var selectedDay by remember { mutableStateOf(initialDate.dayOfMonth) }

    // 当前年月的天数(切换年月时自动校正日)
    val daysInMonth = java.time.YearMonth.of(selectedYear, selectedMonth).lengthOfMonth()
    val days = (1..daysInMonth).toList()
    // 切换年月后若 selectedDay 超出范围则校正
    LaunchedEffect(selectedYear, selectedMonth) {
        if (selectedDay > daysInMonth) selectedDay = daysInMonth
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(20.dp)
        ) {
            Text("选择生日", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            // 三列滚轮:年 / 月 / 日
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelPicker(
                    items = years.map { "${it}年" },
                    initialIndex = years.indexOf(selectedYear).coerceAtLeast(0),
                    onSelected = { idx -> selectedYear = years[idx] },
                    modifier = Modifier.weight(1f),
                    label = "年"
                )
                WheelPicker(
                    items = months.map { "${it}月" },
                    initialIndex = months.indexOf(selectedMonth).coerceAtLeast(0),
                    onSelected = { idx -> selectedMonth = months[idx] },
                    modifier = Modifier.weight(1f),
                    label = "月"
                )
                WheelPicker(
                    items = days.map { "${it}日" },
                    initialIndex = days.indexOf(selectedDay).coerceAtLeast(0),
                    onSelected = { idx -> selectedDay = days[idx] },
                    modifier = Modifier.weight(1f),
                    label = "日"
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确定",
                    onClick = { onConfirm(LocalDate.of(selectedYear, selectedMonth, selectedDay)) },
                    style = ButtonStyle.White, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 滚轮选择器列 — 居中高亮选中项,上下渐隐。
 */
@Composable
private fun WheelPicker(
    items: List<String>,
    initialIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String = ""
) {
    val state = rememberLazyListState(initialIndex)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemHeight = 36.dp
    val itemHeightPx = with(density) { itemHeight.toPx() }
    // 可视区显示 5 行(上下各 2 行渐隐)
    val visibleCount = 5

    // 滚动停止后取中心项
    LaunchedEffect(state) {
        snapshotFlow {
            val center = state.firstVisibleItemIndex + (state.firstVisibleItemScrollOffset / itemHeightPx).roundToInt().coerceIn(0, 1)
            center
        }.collect { centerIdx ->
            val safeIdx = centerIdx.coerceIn(0, items.lastIndex)
            if (items.getOrNull(safeIdx) != null) onSelected(safeIdx)
        }
    }

    Box(
        modifier.height(itemHeight * visibleCount)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant.copy(0.3f)),
        contentAlignment = Alignment.Center
    ) {
        // 中心选中条高亮背景
        Box(
            Modifier.fillMaxWidth().height(itemHeight)
                .background(AccentBlue.copy(0.1f))
                .align(Alignment.Center)
        )
        LazyColumn(
            state = state,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = itemHeight * 2, bottom = itemHeight * 2
            )
        ) {
            items(items.size) { idx ->
                val isCenter = runCatching {
                    state.firstVisibleItemIndex == idx ||
                        (state.firstVisibleItemIndex + 1 == idx && state.firstVisibleItemScrollOffset > itemHeightPx / 2)
                }.getOrDefault(false)
                Text(
                    items[idx],
                    color = if (isCenter) TextPrimary else TextHint,
                    fontSize = 15.sp,
                    fontWeight = if (isCenter) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.height(itemHeight).padding(vertical = 8.dp)
                )
            }
        }
    }
}

/** 通用确认弹窗 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassButton(
                    text = "取消", onClick = onDismiss,
                    style = ButtonStyle.Outline, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "确定", onClick = onConfirm,
                    style = ButtonStyle.White, height = 40.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 根据生日 yyyy-MM-dd 计算星座(带 emoji);解析失败返回空串 */
fun zodiacOf(birthday: String): String {
    val d = runCatching { LocalDate.parse(birthday) }.getOrNull() ?: return ""
    val m = d.monthValue
    val day = d.dayOfMonth
    return when {
        (m == 3 && day >= 21) || (m == 4 && day <= 19) -> "♈ 白羊座"
        (m == 4 && day >= 20) || (m == 5 && day <= 20) -> "♉ 金牛座"
        (m == 5 && day >= 21) || (m == 6 && day <= 21) -> "♊ 双子座"
        (m == 6 && day >= 22) || (m == 7 && day <= 22) -> "♋ 巨蟹座"
        (m == 7 && day >= 23) || (m == 8 && day <= 22) -> "♌ 狮子座"
        (m == 8 && day >= 23) || (m == 9 && day <= 22) -> "♍ 处女座"
        (m == 9 && day >= 23) || (m == 10 && day <= 23) -> "♎ 天秤座"
        (m == 10 && day >= 24) || (m == 11 && day <= 22) -> "♏ 天蝎座"
        (m == 11 && day >= 23) || (m == 12 && day <= 21) -> "♐ 射手座"
        (m == 12 && day >= 22) || (m == 1 && day <= 19) -> "♑ 摩羯座"
        (m == 1 && day >= 20) || (m == 2 && day <= 18) -> "♒ 水瓶座"
        else -> "♓ 双鱼座"
    }
}
