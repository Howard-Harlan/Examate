package go.examate.hyp.media.reading

import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.agent.AgentTools
import go.examate.hyp.config.MyConfig
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.media.reader.ReaderChapter
import go.examate.hyp.media.reader.TextProvider
import go.examate.hyp.ui.chat.ModelSelector
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.components.MarkdownText
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.GlassCardTint
import go.examate.hyp.ui.theme.GlassChipTint
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.HttpUtils
import go.examate.hyp.util.ReadingFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI 写作入口模式(仅用于入口标识,页面内部统一走 4 阶段工作流)。
 * - FullCreation: 书架入口,默认从角色设定开始
 * - IfLine: 章节目录入口,注入原书信息作为上下文
 */
sealed class WriterMode(val label: String) {
    data object FullCreation : WriterMode("全文创作")
    data object IfLine : WriterMode("IF线创作")
}

/**
 * 知识图谱式写作工作流的 4 个阶段,顺序推进,每阶段产出累积到上下文。
 */
enum class WriterStage(val label: String, val hint: String) {
    RoleSetup("角色设定", "描述主角的身份、性格、背景…"),
    Outline("大纲思路", "描述故事主题、主线走向…"),
    ChapterWriting("分章写作", "指定章节,如:第1章 开端…"),
    PlotDesign("章节情节", "指定章节,设计冲突与转折…")
}

private data class WriterMsg(
    val role: String,        // "user" / "assistant"
    val content: String,
    val stage: WriterStage
)

/**
 * AI 写作页面 — 类似 agents 聊天的知识图谱写作工作流。
 *
 * 4 阶段渐进式创作:角色设定 → 大纲思路 → 分章写作 → 章节情节。
 * 每阶段对话累积到上下文,AI 基于前序产出保持一致性。
 * 顶栏悬浮模型选择(ModelSelector + 真玻璃胶囊),底部阶段切换 + 输入栏。
 */
@Composable
fun AiWriterScreen(
    mode: WriterMode,
    seedText: String,
    bookName: String,
    chapters: List<ReaderChapter>,
    provider: TextProvider?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 深色模式降白:顶部/输入玻璃面 38%~48%→12%
    val isDark = IsDark
    val scope = rememberCoroutineScope()
    val backdrop = LocalGlassBackdrop.current
    val keyboard = LocalSoftwareKeyboardController.current

    var stage by remember { mutableStateOf(WriterStage.RoleSetup) }
    val messages = remember { mutableStateListOf<WriterMsg>() }
    var inputText by remember { mutableStateOf(seedText) }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf(listOf<ModelProvider>()) }
    var modelIndex by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val fontLevel = remember { ReaderPrefs.getFontLevel(context) }
    val bgColor = remember { ReaderPrefs.getBgTheme(context) }
    val customFont = remember { ReaderPrefs.isCustomFontEnabled(context) }

    LaunchedEffect(Unit) {
        providers = MyConfig.providersFor(context, ModelProvider.CAP_CHAT)
        // IF 线入口:注入原书章节列表作为上下文提示
        if (mode is WriterMode.IfLine && bookName.isNotBlank()) {
            val chList = chapters.take(15).joinToString("\n") { "• ${it.title}" }
            val total = if (chapters.size > 15) "\n…共${chapters.size}章" else ""
            messages.add(WriterMsg(
                "assistant",
                "本次创作基于《$bookName》的 IF 线(独立故事分支)。\n\n原书章节:\n$chList$total\n\n请先在「角色设定」阶段描述你要改写的关键转折或 IF 设定,我们一起开始创作。",
                WriterStage.RoleSetup
            ))
        }
    }

    // 新消息或流式更新时自动滚动到底部
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotBlank()) {
            listState.animateScrollToItem((messages.size + if (streamingText.isNotBlank()) 1 else 0))
        }
    }

    val currentProvider = providers.getOrNull(modelIndex)

    fun send() {
        val text = inputText.trim()
        if (text.isBlank() || isGenerating) return
        val p = currentProvider ?: run {
            Toast.makeText(context, "未配置可用模型", Toast.LENGTH_SHORT).show()
            return
        }
        keyboard?.hide()
        messages.add(WriterMsg("user", text, stage))
        inputText = ""
        isGenerating = true
        streamingText = ""

        scope.launch {
            val history = messages.map { it.role to it.content }
            val systemPrompt = buildSystemPrompt(stage, mode, bookName)
            var doneCalled = false
            try {
                HttpUtils.sendChatWithHistoryStreamFlow(
                    p.requestUrl, p.modelId, history,
                    p.apiKey, systemPrompt, p.enableThinking, p.endpointType
                ).collect { sse ->
                    when (sse) {
                        is HttpUtils.SseEvent.Token -> streamingText += sse.text
                        is HttpUtils.SseEvent.Error -> {
                            Toast.makeText(context, "生成失败: ${sse.message}", Toast.LENGTH_SHORT).show()
                            isGenerating = false; streamingText = ""; doneCalled = true
                        }
                        is HttpUtils.SseEvent.Done -> {
                            if (streamingText.isNotBlank()) {
                                messages.add(WriterMsg("assistant", streamingText, stage))
                            }
                            streamingText = ""; isGenerating = false; doneCalled = true
                        }
                        else -> {}
                    }
                }
                if (!doneCalled) {
                    if (streamingText.isNotBlank()) {
                        messages.add(WriterMsg("assistant", streamingText, stage))
                    }
                    streamingText = ""; isGenerating = false
                }
            } catch (e: Exception) {
                Toast.makeText(context, "生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
                isGenerating = false; streamingText = ""
            }
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        // ═══ 消息列表 ═══
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 68.dp, bottom = 140.dp, start = 14.dp, end = 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(
                    msg = msg,
                    fontLevel = fontLevel,
                    bgColor = bgColor,
                    customFont = customFont,
                    context = context
                )
            }
            // 流式输出中的文本
            if (streamingText.isNotBlank() || isGenerating) {
                item {
                    Box(
                        Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(16.dp))
                            .background(GlassCardTint).padding(14.dp)
                    ) {
                        if (streamingText.isBlank() && isGenerating) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextHint)
                                Spacer(Modifier.width(8.dp))
                                Text("AI 创作中…", color = TextHint, fontSize = 13.sp)
                            }
                        } else {
                            MarkdownText(
                                markdown = streamingText,
                                modifier = Modifier.fillMaxWidth(),
                                textColor = ReaderPrefs.textColor(bgColor),
                                fontSize = ReaderPrefs.fontSizeSp(fontLevel),
                                fontFamily = if (customFont) ReadingFont.family(context) else null,
                                isStreaming = true
                            )
                        }
                    }
                }
            }
            // 空状态引导
            if (messages.isEmpty() && streamingText.isBlank()) {
                item {
                    Column(
                        Modifier.fillMaxSize().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("知识图谱写作工作流", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text("角色设定 → 大纲思路 → 分章写作 → 章节情节", color = TextHint, fontSize = 12.sp)
                        Spacer(Modifier.height(20.dp))
                        Text(stage.hint, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }

        // ═══ 顶栏:真悬浮(返回 / 模型选择 / 保存) ═══
        Row(
            Modifier.statusBarsPadding().fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(6.dp))
            // 模型选择(液态玻璃胶囊)
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(12f) },
                            onDrawSurface = { drawRect(Color.White.copy(if (isDark) 0.12f else 0.48f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(20.dp)).background(GlassChipTint)
                    }
                )
            ) {
                ModelSelector(
                    providers = providers,
                    selectedIndex = modelIndex,
                    onSelect = { modelIndex = it }
                )
            }
            Spacer(Modifier.weight(1f))
            // 保存最新产出
            LiquidGlassIconButton(
                icon = Icons.Filled.Save, contentDescription = "保存",
                onClick = {
                    val lastOutput = messages.lastOrNull { it.role == "assistant" }?.content
                    if (lastOutput.isNullOrBlank()) {
                        Toast.makeText(context, "暂无可保存内容", Toast.LENGTH_SHORT).show()
                        return@LiquidGlassIconButton
                    }
                    scope.launch {
                        val title = titleForFile(mode, bookName)
                        val args = JSONObject().apply {
                            put("path", "AI写作/$title.md")
                            put("content", messages.joinToString("\n\n---\n\n") { 
                                "## ${if (it.role == "user") "输入" else it.stage.label}\n\n${it.content}" 
                            })
                        }
                        val result = withContext(Dispatchers.IO) {
                            AgentTools.execute(context, AgentTools.ToolCall("create_file", args))
                        }
                        Toast.makeText(context, if (result.success) "已保存: AI写作/$title.md" else "保存失败", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.size(40.dp)
            )
        }

        // ═══ 底部:阶段切换 + 输入栏(液态悬浮) ═══
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // 阶段切换胶囊
            Box(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(10f) },
                            onDrawSurface = { drawRect(Color.White.copy(if (isDark) 0.12f else 0.4f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(22.dp)).background(GlassChipTint)
                    }
                )
            ) {
                Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WriterStage.entries.forEach { s ->
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (stage == s) SelectedBg else Color.Transparent)
                                .clickable { stage = s }.padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.label, color = if (stage == s) Color.White else TextSecondary,
                                fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 输入栏(液态玻璃)
            Row(
                Modifier.then(
                    if (backdrop != null) {
                        Modifier.fillMaxWidth().drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(26.dp) },
                            effects = { vibrancy(); blur(18f) },
                            onDrawSurface = { drawRect(Color.White.copy(if (isDark) 0.12f else 0.38f)) }
                        )
                    } else {
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(GlassChipTint)
                    }
                ).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stage.hint, color = TextHint, fontSize = 14.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (inputText.isBlank() || isGenerating) Primary.copy(0.3f) else Primary)
                        .clickable(enabled = inputText.isNotBlank() && !isGenerating) { send() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: WriterMsg,
    fontLevel: Int,
    bgColor: Int,
    customFont: Boolean,
    context: android.content.Context
) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier.fillMaxWidth(if (isUser) 0.8f else 0.88f)
                .clip(RoundedCornerShape(16.dp))
                // 对方气泡主题化:浅色半透明白/深色深灰面,避免深色下白底黑字
                .background(if (isUser) Primary.copy(0.12f) else GlassCardTint)
                .padding(14.dp)
        ) {
            Column {
                // 阶段标签
                Text(
                    msg.stage.label,
                    color = if (isUser) Primary else TextHint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (isUser) {
                    Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    MarkdownText(
                        markdown = msg.content,
                        modifier = Modifier.fillMaxWidth(),
                        textColor = ReaderPrefs.textColor(bgColor),
                        fontSize = ReaderPrefs.fontSizeSp(fontLevel),
                        fontFamily = if (customFont) ReadingFont.family(context) else null
                    )
                }
            }
        }
    }
}

// ═══ 系统提示词 ═══

private fun buildSystemPrompt(stage: WriterStage, mode: WriterMode, bookName: String): String {
    val base = "你是一位资深小说创作助手,正在与用户进行渐进式写作工作流。" +
        "工作流分4阶段:角色设定 → 大纲思路 → 分章写作 → 章节情节。" +
        "请参考对话历史中前序阶段的产出,保持人物、设定、情节一致性。" +
        "用 Markdown 格式输出,内容详实、有文学性。"
    val stagePrompt = when (stage) {
        WriterStage.RoleSetup -> "\n\n当前阶段【角色设定】:帮用户塑造主角与配角的身份、性格、背景、动机、成长弧线。输出角色卡片(姓名/身份/性格/背景/动机/弧线)。"
        WriterStage.Outline -> "\n\n当前阶段【大纲思路】:基于已设定的角色,梳理故事主线、支线、世界观、分章纲要。输出结构化大纲。"
        WriterStage.ChapterWriting -> "\n\n当前阶段【分章写作】:基于大纲逐章展开正文。保持文风一致、情节连贯、细节丰富。直接输出章节正文。"
        WriterStage.PlotDesign -> "\n\n当前阶段【章节情节】:针对具体章节设计冲突、转折、伏笔、关键对话。输出情节梗概。"
    }
    val ifHint = if (mode is WriterMode.IfLine && bookName.isNotBlank()) {
        "\n\n本次为《$bookName》的 IF 线创作(独立故事分支),保持核心人物设定,按用户设定的 IF 转折改变剧情走向。"
    } else ""
    return base + stagePrompt + ifHint
}

private fun titleForFile(mode: WriterMode, bookName: String): String {
    val prefix = if (mode is WriterMode.IfLine) "IF线_${bookName.ifBlank { "未命名" }}" else "创作_${System.currentTimeMillis()}"
    return prefix.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md"
}
