package go.examate.hyp

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.agent.MemoryManager
import go.examate.hyp.agent.SkillManager
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogContent
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.AvatarHelper
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
    var showImportDialog by remember { mutableStateOf(false) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var showPasteImportDialog by remember { mutableStateOf(false) }
    var showClearMemoriesDialog by remember { mutableStateOf(false) }
    var skillsExpanded by remember { mutableStateOf(false) }
    var memoriesExpanded by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // Agent 头像 + 聊天头像开关
    var agentAvatarBitmap by remember { mutableStateOf<Bitmap?>(AvatarHelper.loadAgentAvatar(context)) }
    var chatAvatarEnabled by remember { mutableStateOf(PrefsManager.getInstance(context).isAgentChatAvatarEnabled) }

    LaunchedEffect(Unit) {
        SkillManager.ensureBuiltinSkills(context)
        skills = SkillManager.loadAll(context)
        memories = MemoryManager.loadAll(context)
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
                    Toast.makeText(context, if (success) "导入成功" else "导入失败", Toast.LENGTH_SHORT).show()
                    skills = SkillManager.loadAll(context)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val success = AvatarHelper.saveAgentAvatar(context, uri)
            Toast.makeText(context, if (success) "Agent 头像已更新" else "保存失败", Toast.LENGTH_SHORT).show()
            if (success) agentAvatarBitmap = AvatarHelper.loadAgentAvatar(context)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ═══ 顶栏:返回 + 居中标题 ═══
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
                    text = "Agent 设置",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // ═══ 内容区 ═══
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ═══ Agent 头像 + 聊天开关(集成一块) ═══
                item {
                    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 头像预览(点击换图)
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceVariant)
                                        .clickable { avatarPicker.launch("image/*") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    agentAvatarBitmap?.let { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Agent 头像",
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } ?: run {
                                        Icon(
                                            Icons.Filled.Person,
                                            contentDescription = "默认头像",
                                            tint = TextHint,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("聊天显示头像", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("点击头像设置,开启后在气泡左侧显示", color = TextHint, fontSize = 11.sp)
                                }
                                LiquidGlassToggle(
                                    selected = chatAvatarEnabled,
                                    onSelect = { enabled ->
                                        chatAvatarEnabled = enabled
                                        PrefsManager.getInstance(context).isAgentChatAvatarEnabled = enabled
                                    }
                                )
                            }
                            // 已设置头像时显示清除按钮
                            if (agentAvatarBitmap != null) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        "清除头像",
                                        color = Danger,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                AvatarHelper.clearAgentAvatar(context)
                                                agentAvatarBitmap = null
                                                Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══ Skills 区 ═══
                item {
                    SectionHeader("Skills 技能", "已导入 ${skills.size} 个,启用 ${skills.count { it.enabled }} 个")
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
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
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
                // 导入 Skills 按钮
                item {
                    LiquidGlassButton(
                        text = "导入 Skills",
                        onClick = { showImportDialog = true },
                        style = ButtonStyle.White,
                        height = 42.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ═══ 记忆区 ═══
                item {
                    SectionHeader("记忆", "AI 会记住这些信息,共 ${memories.size} 条")
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
            }
        }

    }

    // ═══ 导入 Skill 弹窗 ═══
    if (showImportDialog) {
        PlainDialog(onDismissRequest = { showImportDialog = false }) {
            PlainDialogTitle("导入 Skill")
            PlainDialogContent("选择导入方式:")
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        showImportDialog = false
                        filePicker.launch("*/*")
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("从文件导入(.md)", color = TextPrimary, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        showImportDialog = false
                        showPasteImportDialog = true
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("粘贴文本导入", color = TextPrimary, fontSize = 14.sp)
            }
            PlainDialogButtons(
                listOf(Triple("取消", false) { showImportDialog = false })
            )
        }
    }

    if (showPasteImportDialog) {
        PasteImportDialog(
            onDismiss = { showPasteImportDialog = false },
            onImport = { name, content ->
                val success = SkillManager.importSkill(context, name, content)
                Toast.makeText(context, if (success) "导入成功" else "导入失败", Toast.LENGTH_SHORT).show()
                if (success) skills = SkillManager.loadAll(context)
                showPasteImportDialog = false
            }
        )
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
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    },
                    Triple("取消", true) { showClearMemoriesDialog = false }
                )
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextHint, fontSize = 11.sp)
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
private fun PasteImportDialog(
    onDismiss: () -> Unit,
    onImport: (name: String, content: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("粘贴导入 Skill")
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Skill 名称", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                placeholder = { Text("如:writing-helper", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("SKILL.md 内容", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                placeholder = { Text("---\nname: xxx\ndescription: xxx\n---\n# 指令内容", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                singleLine = false,
                shape = RoundedCornerShape(12.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        PlainDialogButtons(
            listOf(
                Triple("导入", true) {
                    if (name.isNotBlank() && content.isNotBlank()) onImport(name, content)
                },
                Triple("取消", false) { onDismiss() }
            )
        )
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (content: String, category: String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("通用") }
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
            Text("内容", color = TextHint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                placeholder = { Text("如:我喜欢简洁的回答风格", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                singleLine = false,
                shape = RoundedCornerShape(12.dp)
            )
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
