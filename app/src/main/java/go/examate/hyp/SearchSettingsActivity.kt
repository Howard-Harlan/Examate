package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.config.MyConfig
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.entity.ModelProvider
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
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 搜题功能设置:防截屏、悬浮球、悬浮菜单等开关集中管理
 */
class SearchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExamateTheme {
                LiquidGlassBackground {
                    SearchSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }

    var forbidCapture by remember { mutableStateOf(prefs.isForbidCapture) }
    var edgeAttach by remember { mutableStateOf(prefs.isEdgeAttach) }
    var transparentFloat by remember { mutableStateOf(prefs.isTransparentFloat) }
    var floatingMenuItems by remember { mutableStateOf(prefs.floatingMenuItems) }

    // 多 AI 验证
    val allProviders = remember { MyConfig.loadProviders(context) }
    val validProviders = remember(allProviders) {
        allProviders.filter { it.apiKey.isNotEmpty() && it.requestUrl.isNotEmpty() && it.modelId.isNotEmpty() }
    }
    var multiAiVerify by remember { mutableStateOf(prefs.isMultiAiVerify) }
    var multiAiSelected by remember { mutableStateOf(prefs.getMultiAiVerifyIndices()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
    ) {
        // 顶栏
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
                "搜题功能设置",
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
                .padding(top = 16.dp, bottom = 60.dp)
        ) {
        Spacer(Modifier.height(24.dp))

        // 多 AI 验证
        Text(
            "多 AI 验证",
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ToggleRow(
                    title = if (multiAiVerify) "已开启" else "已关闭",
                    subtitle = "同时请求多个 AI,选择题投票,问答题取首个(增加 token 消耗)",
                    checked = multiAiVerify,
                    onCheckedChange = {
                        multiAiVerify = it
                        prefs.isMultiAiVerify = it
                    }
                )
                if (multiAiVerify && validProviders.isNotEmpty()) {
                    HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择参与的 AI", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        val activeCount = if (multiAiSelected.isEmpty()) validProviders.size else multiAiSelected.size
                        Text("$activeCount / ${validProviders.size}", color = TextHint, fontSize = 12.sp)
                    }
                    validProviders.forEachIndexed { index, provider ->
                        val isSelected = multiAiSelected.isEmpty() || multiAiSelected.contains(index)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val current = if (multiAiSelected.isEmpty()) {
                                    validProviders.indices.toMutableSet()
                                } else {
                                    multiAiSelected.toMutableSet()
                                }
                                if (current.contains(index)) current.remove(index)
                                else current.add(index)
                                multiAiSelected = if (current.size == validProviders.size) emptySet() else current
                                prefs.setMultiAiVerifyIndices(multiAiSelected)
                            }.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(CircleShape)
                                    .background(if (isSelected) Primary else Color(0xFFE5E7EB)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(provider.displayName.ifEmpty { "未命名" }, color = if (isSelected) TextPrimary else TextHint, fontSize = 13.sp)
                                Text(provider.modelId.ifEmpty { "未知模型" }, color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 悬浮球设置
        Text(
            "悬浮球",
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ToggleRow(
                    title = "防截屏保护",
                    subtitle = "防止屏幕截图泄露内容",
                    checked = forbidCapture,
                    onCheckedChange = {
                        forbidCapture = it
                        prefs.isForbidCapture = it
                    }
                )
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
                ToggleRow(
                    title = "悬浮球吸边",
                    subtitle = "自动吸附至屏幕边缘",
                    checked = edgeAttach,
                    onCheckedChange = {
                        edgeAttach = it
                        prefs.isEdgeAttach = it
                    }
                )
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
                ToggleRow(
                    title = "半透明悬浮球",
                    subtitle = "降低悬浮球视觉干扰",
                    checked = transparentFloat,
                    onCheckedChange = {
                        transparentFloat = it
                        prefs.isTransparentFloat = it
                    }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 悬浮菜单功能勾选
        Text(
            "悬浮菜单项",
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                val menuOptions = listOf(
                    "select" to "框选",
                    "ask" to "问问AI",
                    "auto" to "自动答题",
                    "browse" to "浏览",
                    "chat" to "聊天"
                )
                menuOptions.forEachIndexed { index, (id, label) ->
                    if (index > 0) {
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    ToggleRow(
                        title = label,
                        subtitle = if (id == "auto") "(仅无障碍模式)" else "",
                        checked = floatingMenuItems.contains(id),
                        onCheckedChange = { checked ->
                            val current = floatingMenuItems.toMutableSet()
                            if (checked) current.add(id) else current.remove(id)
                            floatingMenuItems = current
                            prefs.floatingMenuItems = current
                        }
                    )
                }
            }
        }
        } // 内层 Column
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        LiquidGlassToggle(selected = checked, onSelect = onCheckedChange)
    }
}
