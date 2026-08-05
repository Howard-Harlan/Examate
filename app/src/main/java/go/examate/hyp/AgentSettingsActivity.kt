package go.examate.hyp

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import go.examate.hyp.util.ToastUtil
import go.examate.hyp.util.ReadingFont
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.agent.AgentOrchestrator
import go.examate.hyp.agent.CustomToolSpec
import go.examate.hyp.agent.CustomToolStore
import go.examate.hyp.agent.MemoryManager
import go.examate.hyp.agent.SkillManager
import go.examate.hyp.agent.ToolRegistry
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.AvatarHelper
import kotlinx.coroutines.launch
import java.io.File

class AgentSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    AgentSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun AgentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var skills by remember { mutableStateOf(listOf<SkillManager.Skill>()) }
    var memories by remember { mutableStateOf(listOf<MemoryManager.Memory>()) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var showClearMemoriesDialog by remember { mutableStateOf(false) }
    var skillsExpanded by remember { mutableStateOf(false) }
    var memoriesExpanded by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var customTools by remember { mutableStateOf(listOf<CustomToolSpec>()) }
    var builtinToolNames by remember { mutableStateOf(listOf<String>()) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var showToolDialog by remember { mutableStateOf(false) }
    var editingTool by remember { mutableStateOf<CustomToolSpec?>(null) }
    var fontPathState by remember { mutableStateOf(PrefsManager.getInstance(context).chatFontPath) }
    var fontSizeState by remember { mutableIntStateOf(PrefsManager.getInstance(context).chatFontSize) }
    var fontSizeLevel by remember { mutableIntStateOf(PrefsManager.getInstance(context).chatFontSizeLevel) }
    var bgPathState by remember { mutableStateOf(PrefsManager.getInstance(context).chatBackgroundPath) }
    var settingsTab by remember { mutableStateOf(0) }

    // 对话记忆开关(仅 agents 对话生效,悬浮搜题不产生记忆)
    var memoryEnabled by remember { mutableStateOf(PrefsManager.getInstance(context).isMemoryEnabled) }

    LaunchedEffect(Unit) {
        SkillManager.ensureBuiltinSkills(context)
        skills = SkillManager.loadAll(context)
        memories = MemoryManager.loadAll(context)
        ToolRegistry.registerDefaults(context)
        customTools = CustomToolStore.load(context)
        val customNames = customTools.map { it.name }.toSet()
        builtinToolNames = ToolRegistry.registeredTools().map { it.name }.filter { it !in customNames }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ok = ReadingFont.import(context, uri)
            ToastUtil.show(context, if (ok) "字体已导入" else "导入失败(请选 ttf/otf)")
            if (ok) fontPathState = PrefsManager.getInstance(context).chatFontPath
        }
    }
    // 聊天背景图片选择:复制到 filesDir/chat_background.jpg
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val target = java.io.File(context.filesDir, "chat_background.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                PrefsManager.getInstance(context).chatBackgroundPath = target.absolutePath
                bgPathState = target.absolutePath
                ToastUtil.show(context, "背景已设置")
            }.onFailure { ToastUtil.show(context, "设置失败: ${it.message}") }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                if (text != null) {
                    val fileName = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "imported"
                    val success = SkillManager.importSkill(context, fileName, text)
                    ToastUtil.show(context, if (success) "导入成功" else "导入失败")
                    skills = SkillManager.loadAll(context)
                }
            } catch (e: Exception) {
                ToastUtil.show(context, "导入失败: ${e.message}")
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏:返回 + 居中标题(透明背景,继承 LiquidGlassBackground 效果) ═══
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
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
                    text = "Agents 设置",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
                // API 设置:移至顶栏,紧凑玻璃按钮(样式同 Skills 导入按钮)
                Box(Modifier.align(Alignment.CenterEnd)) {
                    CompactGlassButton(
                        text = "API 设置",
                        onClick = { context.startActivity(Intent(context, ApiConfigActivity::class.java)) }
                    )
                }
            }

            // ═══ 顶部分类:技能·工具 / 样式(复刻课表设置分段栏) ═══
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("工具", "样式").forEachIndexed { index, label ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (settingsTab == index) Primary else SurfaceVariant)
                            .clickable { settingsTab = index }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (settingsTab == index) Color.White else TextHint,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ═══ 内容区 ═══
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (settingsTab == 0) {
                // ═══ Skills 区 ═══
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Skills 技能", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("已导入 ${skills.size} 个,启用 ${skills.count { it.enabled }} 个", color = TextHint, fontSize = 11.sp)
                        }
                        // 自定义 wrap content 液态玻璃按钮(避免 LiquidGlassButton 强制 fillMaxWidth)
                        CompactGlassButton(
                            text = "导入",
                            onClick = { ToastUtil.show(context, "请选择 .md 格式的 Skill 文件"); filePicker.launch("*/*") }
                        )
                    }
                }
                // 最多显示 3 个,展开后显示全部
                val visibleSkills = if (skillsExpanded) skills else skills.take(3)
                items(visibleSkills) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggle = { enabled ->
                            SkillManager.setEnabled(context, skill.id, enabled)
                            skills = SkillManager.loadAll(context)
                        },
                        onDelete = if (skill.source == "imported") ({
                            SkillManager.deleteSkill(context, skill.id)
                            skills = SkillManager.loadAll(context)
                            ToastUtil.show(context, "已删除")
                        }) else null
                    )
                }
                // 超过 3 个时显示展开/收起按钮
                if (skills.size > 3) {
                    item {
                        ExpandToggle(
                            expanded = skillsExpanded,
                            onClick = { skillsExpanded = !skillsExpanded },
                            remainingCount = skills.size - 3
                        )
                    }
                }

                // ═══ 工具区 ═══
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("工具", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("内置 ${builtinToolNames.size} 个 · 自定义 ${customTools.size} 个", color = TextHint, fontSize = 11.sp)
                        }
                        CompactGlassButton(text = "添加", onClick = { editingTool = null; showToolDialog = true })
                    }
                }
                items(customTools) { spec ->
                    CustomToolCard(
                        spec = spec,
                        onEdit = { editingTool = spec; showToolDialog = true },
                        onDelete = {
                            CustomToolStore.delete(context, spec.id)
                            customTools = CustomToolStore.load(context)
                            ToastUtil.show(context, "已删除")
                        }
                    )
                }
                // 内置工具(只读,折叠展示名称)
                item {
                    Column {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(Primary.copy(0.06f))
                                .clickable { toolsExpanded = !toolsExpanded }
                                .padding(vertical = 10.dp, horizontal = 14.dp)
                        ) {
                            Text(
                                if (toolsExpanded) "收起内置工具" else "查看内置工具 (${builtinToolNames.size})",
                                color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                        }
                        if (toolsExpanded) {
                            Spacer(Modifier.height(4.dp))
                            builtinToolNames.chunked(2).forEach { rowNames ->
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowNames.forEach { n ->
                                        Box(
                                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                                .background(Primary.copy(0.06f)).padding(horizontal = 10.dp, vertical = 7.dp)
                                        ) {
                                            Text(n, color = TextPrimary.copy(0.75f), fontSize = 12.sp)
                                        }
                                    }
                                    if (rowNames.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // ═══ 记忆区 ═══
                item {
                    SectionHeader("记忆", "仅 agents 对话生效,共 ${memories.size} 条")
                }
                // 记忆开关
                item {
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("对话记忆", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("自动捕获偏好,并在对话中注入已保存的记忆", color = TextHint, fontSize = 11.sp)
                            }
                            LiquidGlassToggle(
                                selected = memoryEnabled,
                                onSelect = {
                                    memoryEnabled = it
                                    PrefsManager.getInstance(context).isMemoryEnabled = it
                                }
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Primary.copy(0.1f))
                                .clickable { showAddMemoryDialog = true }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("＋ 添加记忆", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Danger.copy(0.1f))
                                .clickable { showClearMemoriesDialog = true }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("清空全部", color = Danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                val visibleMemories = if (memoriesExpanded) memories else memories.take(3)
                items(visibleMemories) { memory ->
                    MemoryCard(
                        memory = memory,
                        onDelete = {
                            MemoryManager.delete(context, memory.id)
                            memories = MemoryManager.loadAll(context)
                        }
                    )
                }
                if (memories.size > 3) {
                    item {
                        ExpandToggle(
                            expanded = memoriesExpanded,
                            onClick = { memoriesExpanded = !memoriesExpanded },
                            remainingCount = memories.size - 3
                        )
                    }
                }
                } else {
                    // ═══ 样式 · 阅读外观 ═══
                    item { SectionHeader("阅读外观") }
                    item {
                        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("正文字体", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(if (fontPathState.isEmpty()) "系统默认" else "已导入自定义字体", color = TextHint, fontSize = 11.sp)
                                    }
                                    if (fontPathState.isNotEmpty()) {
                                        Text("恢复默认", color = Danger, fontSize = 12.sp,
                                            modifier = Modifier.clickable {
                                                ReadingFont.clear(context); fontPathState = ""
                                                ToastUtil.show(context, "已恢复默认字体")
                                            }.padding(horizontal = 6.dp, vertical = 4.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    CompactGlassButton(text = "导入字体", onClick = { fontPicker.launch("*/*") })
                                }
                                Spacer(Modifier.height(14.dp))
                                // 正文字号:三档(偏小/常规/偏大)
                                Text("正文字号", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val levels = listOf("偏小" to 0, "常规" to 1, "偏大" to 2)
                                    levels.forEach { (label, lvl) ->
                                        val selected = fontSizeLevel == lvl
                                        Box(
                                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                                .background(if (selected) Primary else Primary.copy(0.08f))
                                                .clickable {
                                                    fontSizeLevel = lvl
                                                    PrefsManager.getInstance(context).chatFontSizeLevel = lvl
                                                    // 同步映射到 chatFontSize(偏小13/常规15/偏大17)
                                                    val sp = when (lvl) { 0 -> 13; 2 -> 17; else -> 15 }
                                                    fontSizeState = sp
                                                    PrefsManager.getInstance(context).chatFontSize = sp
                                                }.padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(label, color = if (selected) Color.White else TextSecondary,
                                                fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ═══ 样式 · 聊天背景 ═══
                    item { SectionHeader("聊天背景") }
                    item {
                        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("自定义背景", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(if (bgPathState.isEmpty()) "默认背景" else "已设置自定义背景", color = TextHint, fontSize = 11.sp)
                                }
                                if (bgPathState.isNotEmpty()) {
                                    Text("清除", color = Danger, fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            runCatching { java.io.File(bgPathState).delete() }
                                            PrefsManager.getInstance(context).chatBackgroundPath = ""
                                            bgPathState = ""
                                            ToastUtil.show(context, "已清除背景")
                                        }.padding(horizontal = 6.dp, vertical = 4.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                CompactGlassButton(text = "选择照片", onClick = { bgPicker.launch("image/*") })
                            }
                        }
                    }
                }
            }
        }

    }

    if (showAddMemoryDialog) {
        AddMemoryDialog(
            onDismiss = { showAddMemoryDialog = false },
            onAdd = { content, category ->
                MemoryManager.add(context, content, category)
                memories = MemoryManager.loadAll(context)
                showAddMemoryDialog = false
            }
        )
    }

    if (showToolDialog) {
        CustomToolDialog(
            initial = editingTool,
            onDismiss = { showToolDialog = false },
            onSave = { spec ->
                CustomToolStore.addOrUpdate(context, spec)
                customTools = CustomToolStore.load(context)
                showToolDialog = false
                ToastUtil.show(context, "已保存")
            }
        )
    }

    // 清空记忆二次确认
    if (showClearMemoriesDialog) {
        PlainDialog(onDismissRequest = { showClearMemoriesDialog = false }) {
            PlainDialogTitle("清空全部记忆")
            PlainDialogContent("确认清空所有记忆?此操作不可撤销")
            PlainDialogButtons(
                listOf(
                    Triple("清空", false) {
                        MemoryManager.clear(context)
                        memories = MemoryManager.loadAll(context)
                        showClearMemoriesDialog = false
                        ToastUtil.show(context, "已清空")
                    },
                    Triple("取消", true) { showClearMemoriesDialog = false }
                )
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = TextHint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ExpandToggle(expanded: Boolean, onClick: () -> Unit, remainingCount: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primary.copy(0.06f))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (expanded) "收起" else "展开剩余 $remainingCount 个",
            color = Primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** AI 辅助生成自定义工具配置的系统提示词 */
private const val CUSTOM_TOOL_GEN_PROMPT =
    "你是 API 工具配置助手。用户会用自然语言描述一个想接入的 HTTP 接口(可能含地址、参数、用途)。" +
    "请只输出一个 JSON 对象(不要解释、不要 markdown、不要代码块),字段如下:\n" +
    "{\"name\":\"英文小写工具名如 get_weather\",\"description\":\"一句话说明这个工具做什么、AI 何时该用\"," +
    "\"method\":\"GET 或 POST\",\"url\":\"完整请求地址,动态部分用 {参数名} 占位\"," +
    "\"bodyTemplate\":\"POST 请求体 JSON 可用 {参数名};GET 留空\"," +
    "\"responsePath\":\"从返回 JSON 提取结果的点分路径如 data.text,不确定就留空\"," +
    "\"params\":[{\"name\":\"英文参数名\",\"description\":\"参数说明\"}]}\n" +
    "根据用户描述合理推断,参数名用英文,只输出该 JSON。"

/** 解析 AI 生成的工具配置 JSON;失败返回 null(id 留空,仅用于填充表单) */
private fun parseGenSpec(text: String): CustomToolSpec? {
    val s = text.indexOf('{'); val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return try {
        val j = org.json.JSONObject(text.substring(s, e + 1))
        val params = j.optJSONArray("params")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val pn = p.optString("name")
                if (pn.isBlank()) null
                else ToolRegistry.ParamDef(pn, "string", p.optString("description"), false)
            }
        }.orEmpty()
        CustomToolSpec(
            id = "",
            name = j.optString("name"),
            description = j.optString("description"),
            method = if (j.optString("method", "GET").uppercase() == "POST") "POST" else "GET",
            url = j.optString("url"),
            headers = emptyMap(),
            bodyTemplate = j.optString("bodyTemplate"),
            responsePath = j.optString("responsePath"),
            params = params
        )
    } catch (_: Exception) { null }
}

@Composable
private fun CustomToolCard(spec: CustomToolSpec, onEdit: () -> Unit, onDelete: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(spec.description, color = TextHint, fontSize = 11.sp, maxLines = 2)
                }
                Text(spec.method, color = Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(spec.url, color = TextHint.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("编辑", color = Primary, fontSize = 12.sp,
                    modifier = Modifier.clickable { onEdit() }.padding(4.dp))
                Spacer(Modifier.width(12.dp))
                Text("删除", color = Danger, fontSize = 12.sp,
                    modifier = Modifier.clickable { onDelete() }.padding(4.dp))
            }
        }
    }
}

@Composable
private fun ToolField(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = minLines == 1, minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomToolDialog(
    initial: CustomToolSpec?,
    onDismiss: () -> Unit,
    onSave: (CustomToolSpec) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var method by remember { mutableStateOf(initial?.method ?: "GET") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var body by remember { mutableStateOf(initial?.bodyTemplate ?: "") }
    var respPath by remember { mutableStateOf(initial?.responsePath ?: "") }
    var paramsText by remember {
        mutableStateOf(initial?.params?.joinToString("\n") { "${it.name}|${it.description}" } ?: "")
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aiInput by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var genError by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Text(
            if (initial == null) "添加自定义工具" else "编辑工具",
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp, 8.dp, 24.dp, 8.dp)
        )
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(24.dp, 0.dp, 24.dp, 8.dp)
        ) {
            // ═══ AI 辅助生成:用户口述接口 → AI 生成配置并填入下方表单(可复核修改) ═══
            OutlinedTextField(
                value = aiInput, onValueChange = { aiInput = it },
                label = { Text("AI 辅助:描述工具(含接口地址/参数/用途)", fontSize = 11.sp) },
                placeholder = { Text("如:查天气,https://api.xx.com/weather?city={city},传城市名返回天气", fontSize = 11.sp) },
                singleLine = false, minLines = 2,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Primary.copy(0.12f))
                    .clickable(enabled = !generating) {
                        if (aiInput.isBlank()) { genError = "请先描述这个工具"; return@clickable }
                        genError = ""
                        scope.launch {
                            generating = true
                            try {
                                val valid = MyConfig.loadProviders(context).filter {
                                    it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty() && it.modelId.isNotEmpty()
                                }
                                if (valid.isEmpty()) {
                                    genError = "未配置可用的 AI"
                                } else {
                                    val result = AgentOrchestrator.simpleChat(
                                        context, valid.first(), aiInput, emptyList(), CUSTOM_TOOL_GEN_PROMPT
                                    )
                                    val spec = result?.let { parseGenSpec(it) }
                                    if (spec != null) {
                                        name = spec.name; desc = spec.description
                                        method = spec.method; url = spec.url
                                        body = spec.bodyTemplate; respPath = spec.responsePath
                                        paramsText = spec.params.joinToString("\n") { "${it.name}|${it.description}" }
                                    } else genError = "生成失败,请手动填写或重试"
                                }
                            } catch (e: Exception) {
                                genError = "生成失败: ${e.message}"
                            } finally {
                                generating = false
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (generating) "AI 生成中..." else "✨ 用 AI 生成配置",
                    color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
            if (genError.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(genError, color = Danger, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("以下为工具配置(可手动填写或复核 AI 生成结果):", color = TextHint, fontSize = 11.sp)
            ToolField("工具名(英文,如 weather)", name) { name = it }
            ToolField("描述(告诉 AI 何时用)", desc) { desc = it }
            Spacer(Modifier.height(8.dp))
            Text("请求方法", color = TextHint, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("GET", "POST").forEach { m ->
                    val on = method == m
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (on) Primary else Primary.copy(0.08f))
                            .clickable { method = m }.padding(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text(m, color = if (on) Color.White else Primary, fontSize = 12.sp) }
                }
            }
            ToolField("URL(可用 {参数名} 占位)", url) { url = it }
            if (method != "GET") ToolField("请求体 JSON(可用 {参数名})", body, minLines = 2) { body = it }
            ToolField("响应提取路径(点分,如 data.text;留空取全部)", respPath) { respPath = it }
            ToolField("参数(每行 名称|说明)", paramsText, minLines = 2) { paramsText = it }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp, 8.dp, 20.dp, 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE5E5EA))
                    .clickable { onDismiss() }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("取消", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentBlue)
                    .clickable {
                        if (name.isNotBlank() && url.isNotBlank()) {
                            val params = paramsText.lines().mapNotNull { line ->
                                val parts = line.split("|")
                                val pn = parts.getOrNull(0)?.trim().orEmpty()
                                if (pn.isEmpty()) null
                                else ToolRegistry.ParamDef(pn, "string", parts.getOrNull(1)?.trim().orEmpty(), false)
                            }
                            onSave(
                                CustomToolSpec(
                                    id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(), description = desc.trim(),
                                    method = method, url = url.trim(),
                                    headers = initial?.headers ?: emptyMap(),
                                    bodyTemplate = body.trim(), responsePath = respPath.trim(),
                                    params = params
                                )
                            )
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("保存", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillManager.Skill,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(skill.description, color = TextHint, fontSize = 11.sp, maxLines = 2)
                }
                LiquidGlassToggle(
                    selected = skill.enabled,
                    onSelect = onToggle
                )
            }
            Spacer(Modifier.height(6.dp))
            val preview = skill.content.take(100).replace("\n", " ")
            Text(
                if (skill.content.length > 100) "$preview..." else preview,
                color = TextHint.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            if (onDelete != null) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "删除",
                        color = Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onDelete() }.padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryManager.Memory,
    onDelete: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primary.copy(0.1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(memory.category, color = Primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "✕",
                    color = TextHint,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDelete() }.padding(4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(memory.content, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (content: String, category: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("通用") }
    var optimizing by remember { mutableStateOf(false) }
    var optimizeError by remember { mutableStateOf("") }
    val categories = listOf("通用", "偏好", "重要事项", "人物", "学习")

    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("添加记忆")
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("分类", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { cat ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (category == cat) Primary else SurfaceVariant)
                            .clickable { category = cat }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(cat, color = if (category == cat) Color.White else TextHint, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("内容", color = TextHint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                // 优化描述按钮:调用当前对话模型优化记忆描述
                Text(
                    if (optimizing) "优化中..." else "优化描述",
                    color = if (optimizing) TextHint else Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        if (content.isNotBlank() && !optimizing) {
                            optimizeError = ""
                            scope.launch {
                                optimizing = true
                                try {
                                    val providers = MyConfig.loadProviders(context)
                                    val valid = providers.filter {
                                        it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty() && it.modelId.isNotEmpty()
                                    }
                                    if (valid.isEmpty()) {
                                        optimizeError = "未配置可用的 AI"
                                    } else {
                                        val provider = valid.first()
                                        val systemPrompt = "你是记忆优化助手。用户会给你一段想要记住的描述，请你将其优化为一条简洁、清晰、结构化的记忆条目。" +
                                            "要求：1)提炼核心信息，去除冗余；2)用简洁的陈述句；3)保留所有关键事实；4)只输出优化后的文本，不要任何解释或前后缀。"
                                        val result = AgentOrchestrator.simpleChat(
                                            context, provider,
                                            "请优化这条记忆描述：\n$content",
                                            emptyList(), systemPrompt
                                        )
                                        if (!result.isNullOrBlank()) content = result.trim()
                                        else optimizeError = "优化失败"
                                    }
                                } catch (e: Exception) {
                                    optimizeError = "优化失败: ${e.message}"
                                } finally {
                                    optimizing = false
                                }
                            }
                        }
                    }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                placeholder = { Text("如:我喜欢简洁的回答风格", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                singleLine = false,
                shape = RoundedCornerShape(12.dp)
            )
            if (optimizeError.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(optimizeError, color = Danger, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        PlainDialogButtons(
            listOf(
                Triple("添加", true) {
                    if (content.isNotBlank()) onAdd(content, category)
                },
                Triple("取消", false) { onDismiss() }
            )
        )
    }
}

/**
 * 紧凑液态玻璃按钮(wrap content,不强制 fillMaxWidth)
 * 用于 Row 中与文字并排的小按钮
 */
@Composable
private fun CompactGlassButton(
    text: String,
    onClick: () -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        Modifier
            .clip(Capsule())
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = { vibrancy(); blur(8f.dp.toPx()) },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(Color.White.copy(0.5f)) }
                    )
                } else {
                    Modifier.background(Color.White.copy(0.7f))
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
