package go.examate.hyp

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.AvatarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickInput by remember { mutableStateOf(prefs.nickname) }
    var school by remember { mutableStateOf(prefs.school) }
    var displayFields by remember { mutableStateOf(prefs.userDisplayFields) }
    var showSchoolPicker by remember { mutableStateOf(false) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(AvatarHelper.loadAvatar(context)) }

    val displayName = nickInput.trim().ifEmpty { deviceModel }

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    AvatarHelper.saveAvatar(context, uri)
                }
                if (success) {
                    avatarBitmap = AvatarHelper.loadAvatar(context)
                    android.widget.Toast.makeText(context, "头像已更新", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "头像保存失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // ═══ 顶栏 ═══
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
                text = "用户设置",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp)
                .padding(bottom = 40.dp)
        ) {

            Spacer(Modifier.height(20.dp))

            // ═══ 昵称设置(含头像,集成一块) ═══
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 头像(点击修改)
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(SurfaceVariant)
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = avatarBitmap
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "头像",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "头像",
                                    tint = TextHint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = nickInput,
                                onValueChange = { nickInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(displayName, color = TextHint, fontSize = 14.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
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
                            HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
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

        } // 内层 Column
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
