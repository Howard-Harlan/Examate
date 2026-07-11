package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

class UserSettingsActivity : ComponentActivity() {

    private val prefs by lazy { PrefsManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    UserSettingsScreen(
                        prefs = prefs,
                        deviceModel = android.os.Build.MODEL,
                        onBack = { finish() },
                        onNicknameSaved = { name ->
                            prefs.nickname = name
                            android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UserSettingsScreen(
    prefs: PrefsManager,
    deviceModel: String,
    onBack: () -> Unit,
    onNicknameSaved: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var nickInput by remember { mutableStateOf(prefs.nickname) }
    var school by remember { mutableStateOf(prefs.school) }
    var displayFields by remember { mutableStateOf(prefs.userDisplayFields) }
    var showSchoolPicker by remember { mutableStateOf(false) }

    val displayName = nickInput.trim().ifEmpty { deviceModel }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp)
            .padding(bottom = 40.dp)
    ) {
        // ═══ 顶栏 ═══
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Text("←", color = TextPrimary, fontSize = 22.sp)
            }
            Text(
                text = "用户设置",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(28.dp))

        // ═══ 昵称设置(预览 + 输入合并) ═══
        Text(
            "昵称",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = { nickInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入昵称(留空使用设备型号)", color = TextHint, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "当前显示:$displayName",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "设备型号:$deviceModel · 昵称用于局域网聊天显示",
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassButton(
                        text = "保存",
                        onClick = { onNicknameSaved(nickInput.trim()) },
                        style = ButtonStyle.White,
                        height = 42.dp,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "清空",
                        onClick = { nickInput = "" },
                        style = ButtonStyle.Outline,
                        height = 42.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ═══ 学校绑定 ═══
        Text(
            "学校绑定",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { showSchoolPicker = true }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("已绑定学校", color = TextHint, fontSize = 12.sp)
                    Text(
                        text = school.ifEmpty { "未绑定" },
                        color = if (school.isEmpty()) TextHint else TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text("›", color = TextHint, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
        }
        // 已绑定时,页内显示取消绑定按钮
        if (school.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LiquidGlassButton(
                text = "取消绑定",
                onClick = {
                    prefs.school = ""
                    prefs.campusUsername = ""
                    prefs.campusPassword = ""
                    school = ""
                    android.widget.Toast.makeText(
                        context,
                        "已取消绑定",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                style = ButtonStyle.Outline,
                height = 40.dp
            )
        }

        Spacer(Modifier.height(24.dp))

        // ═══ 信息栏展示字段勾选 ═══
        Text(
            "信息栏展示字段",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Text(
            "选择在设置页用户信息栏展示的内容",
            color = TextHint,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                val fieldOptions = listOf(
                    "nickname" to "昵称",
                    "school" to "学校"
                )
                fieldOptions.forEachIndexed { index, (id, label) ->
                    if (index > 0) {
                        Divider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    UserToggleRow(
                        title = label,
                        subtitle = "",
                        checked = displayFields.contains(id),
                        onCheckedChange = { checked ->
                            val current = displayFields.toMutableSet()
                            if (checked) current.add(id) else current.remove(id)
                            displayFields = current
                            prefs.userDisplayFields = current
                        }
                    )
                }
            }
        }
    }

    // 学校选择弹窗(取消/确认并排,不再含取消绑定项)
    if (showSchoolPicker) {
        SchoolPickerDialog(
            currentSchool = school,
            onPick = { picked ->
                prefs.school = picked
                school = picked
                showSchoolPicker = false
            },
            onDismiss = { showSchoolPicker = false }
        )
    }
}

/** 用户设置页用的开关行(与 SettingsScreen 的 ToggleRow 风格一致) */
@Composable
private fun UserToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        LiquidGlassToggle(selected = checked, onSelect = onCheckedChange)
    }
}
