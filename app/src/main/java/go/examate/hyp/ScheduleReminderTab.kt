package go.examate.hyp

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import go.examate.hyp.util.ToastUtil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.ScheduleCellBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

// ════════════════════════════════════════
//  Tab 3: 提醒设置(课前分钟 / 提醒方式 / 铃声)
// ════════════════════════════════════════

@Composable
internal fun ReminderSettingsTab() {
    val context = LocalContext.current
    var meta by remember { mutableStateOf(ScheduleStorage.getCurrentScheduleMeta(context)) }
    var showStylePicker by remember { mutableStateOf(false) }
    var reminderInput by remember { mutableStateOf(meta.reminderMinutes.toString()) }

    // 系统铃声选择器
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Android 13+ 必须用带 Class 的重载,否则会抛 ClassCastException / 返回 null
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            meta = meta.copy(ringtoneUri = uri?.toString() ?: "")
            ScheduleStorage.updateScheduleMeta(context, meta)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 40.dp)
    ) {
        Text("提醒设置", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("当前课表:${meta.name}", color = TextHint, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))

        // 课前提醒分钟数(自定义填写)
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("课前提醒", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = reminderInput,
                    onValueChange = { v ->
                        val filtered = v.filter { it.isDigit() }.take(3)
                        reminderInput = filtered
                        val min = filtered.toIntOrNull() ?: 0
                        meta = meta.copy(reminderMinutes = min)
                        ScheduleStorage.updateScheduleMeta(context, meta)
                    },
                    label = { Text("分钟数(0=不提醒)", color = TextHint, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                // 快捷选项
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10, 15, 30).forEach { min ->
                        val selected = meta.reminderMinutes == min
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Primary else ScheduleCellBg)
                                .clickable {
                                    reminderInput = min.toString()
                                    meta = meta.copy(reminderMinutes = min)
                                    ScheduleStorage.updateScheduleMeta(context, meta)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (min == 0) "关" else "${min}分",
                                color = if (selected) Color.White else go.examate.hyp.ui.theme.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 提醒方式
        FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { showStylePicker = true }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("提醒方式", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(ScheduleStorage.REMINDER_STYLES.firstOrNull { it.first == meta.reminderStyle }?.second ?: "消息通知", color = TextHint, fontSize = 11.sp)
                }
                Text("›", color = TextHint, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 铃声(仅响铃模式显示)
        if (meta.reminderStyle == "ring") {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("提示铃声", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        val ringtoneName = if (meta.ringtoneUri.isEmpty()) "默认铃声" else {
                            try {
                                RingtoneManager.getRingtone(context, Uri.parse(meta.ringtoneUri))?.getTitle(context) ?: "自定义铃声"
                            } catch (_: Exception) { "自定义铃声" }
                        }
                        Text(ringtoneName, color = TextHint, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        // 打开系统铃声选择器(部分定制 ROM 可能没有,try-catch 兜底)
                        try {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                if (meta.ringtoneUri.isNotEmpty()) {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(meta.ringtoneUri))
                                }
                            }
                            ringtoneLauncher.launch(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            ToastUtil.show(context, "当前设备不支持铃声选择")
                        } catch (e: Exception) {
                            ToastUtil.show(context, "无法打开铃声选择: ${e.message}")
                        }
                    }) { Text("选择") }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (meta.ringtoneUri.isNotEmpty()) {
                LiquidGlassButton(
                    text = "清除自定义铃声",
                    onClick = {
                        meta = meta.copy(ringtoneUri = "")
                        ScheduleStorage.updateScheduleMeta(context, meta)
                    },
                    style = ButtonStyle.Outline, height = 38.dp
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showStylePicker) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showStylePicker = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).padding(20.dp)
            ) {
                Text("选择提醒方式", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                ScheduleStorage.REMINDER_STYLES.forEach { (key, label) ->
                    FrostedGlassCard(
                        modifier = Modifier.fillMaxWidth().clickable {
                            meta = meta.copy(reminderStyle = key)
                            ScheduleStorage.updateScheduleMeta(context, meta)
                            showStylePicker = false
                        }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (meta.reminderStyle == key) {
                                Text("✓", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                LiquidGlassButton(
                    text = "关闭", onClick = { showStylePicker = false },
                    style = ButtonStyle.Outline, height = 40.dp
                )
            }
        }
    }
}
