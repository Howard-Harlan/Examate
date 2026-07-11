package go.examate.hyp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.agent.MemoryManager
import go.examate.hyp.agent.SkillManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.ExamateTheme
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
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
    var refreshTick by remember { mutableIntStateOf(0) }

    // 首次运行初始化内置 Skills
    LaunchedEffect(Unit) {
        SkillManager.ensureBuiltinSkills(context)
        skills = SkillManager.loadAll(context)
        memories = MemoryManager.loadAll(context)
    }

    // 文件选择器:导入 SKILL.md
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

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // ═══ 顶栏 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Text("←", color = TextPrimary, fontSize = 22.sp)
            }
            Text("Agent 设置", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Primary)
                    .clickable { showImportDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("导入 Skill", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══ Skills 区 ═══
            item {
                SectionHeader("🧩 Skills 技能", "已导入 ${skills.size} 个,启用 ${skills.count { it.enabled }} 个")
            }
            items(skills) { skill ->
                SkillCard(
                    skill = skill,
                    onToggle = { enabled ->
                        SkillManager.setEnabled(context, skill.id, enabled)
                        skills = SkillManager.loadAll(context)
                    },
                    onDelete = {
                        SkillManager.deleteSkill(context, skill.id)
                        skills = SkillManager.loadAll(context)
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // ═══ 记忆区 ═══
            item {
                SectionHeader("🧠 记忆", "AI 会记住这些信息,共 ${memories.size} 条")
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
                            .background(Color(0xFFFF3B30).copy(0.1f))
                            .clickable {
                                MemoryManager.clear(context)
                                memories = MemoryManager.loadAll(context)
                                Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("清空全部", color = Color(0xFFFF3B30), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            items(memories) { memory ->
                MemoryCard(
                    memory = memory,
                    onDelete = {
                        MemoryManager.delete(context, memory.id)
                        memories = MemoryManager.loadAll(context)
                    }
                )
            }

            // ═══ 说明区 ═══
            item {
                Spacer(Modifier.height(8.dp))
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📖 说明", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Skills 采用 SKILL.md 格式(Anthropic Agent Skills 标准),\n" +
                            "包含 YAML 元数据(name/description)和 Markdown 指令。\n\n" +
                            "已启用的 Skills 会注入 AI 系统提示词,让 AI 按特定方式工作。\n\n" +
                            "记忆是 AI 的长期记忆,存储用户偏好和重要信息,每次对话都会参考。",
                            color = TextHint, fontSize = 12.sp, lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }

    // ═══ 导入 Skill 弹窗 ═══
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入 Skill") },
            text = {
                Column {
                    Text("选择导入方式:", color = TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        showImportDialog = false
                        filePicker.launch("*/*")
                    }) { Text("📂 从文件导入(.md)") }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        showImportDialog = false
                        // 跳转到粘贴文本导入
                        showPasteImportDialog = true
                    }) { Text("📋 粘贴文本导入") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }

    // 粘贴文本导入
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

    // ═══ 添加记忆弹窗 ═══
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
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextHint, fontSize = 11.sp)
    }
}

@Composable
private fun SkillCard(
    skill: SkillManager.Skill,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(skill.description, color = TextHint, fontSize = 11.sp, maxLines = 2)
                }
                // 启用开关
                Box(
                    Modifier
                        .size(width = 44.dp, height = 24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (skill.enabled) Primary else Color(0xFFE0E0E0))
                        .clickable { onToggle(!skill.enabled) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = if (skill.enabled) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 预览内容(前 2 行)
            val preview = skill.content.take(100).replace("\n", " ")
            Text(
                if (skill.content.length > 100) "$preview..." else preview,
                color = TextHint.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "删除",
                    color = Color(0xFFFF3B30),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onDelete() }.padding(4.dp)
                )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入 Skill") },
        text = {
            Column {
                Text("Skill 名称:", color = TextPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("如:writing-helper", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("SKILL.md 内容:", color = TextPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    placeholder = { Text("---\nname: xxx\ndescription: xxx\n---\n# 指令内容", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && content.isNotBlank()) onImport(name, content) }
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAdd: (content: String, category: String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("通用") }
    val categories = listOf("通用", "偏好", "重要事项", "人物", "学习")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加记忆") },
        text = {
            Column {
                Text("分类:", color = TextPrimary, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (category == cat) Primary else Color(0xFFE0E0E0))
                                .clickable { category = cat }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(cat, color = if (category == cat) Color.White else TextHint, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("内容:", color = TextPrimary, fontSize = 13.sp)
                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    placeholder = { Text("如:我喜欢简洁的回答风格", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (content.isNotBlank()) onAdd(content, category) }
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
