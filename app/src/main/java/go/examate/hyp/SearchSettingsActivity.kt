package go.examate.hyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
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
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassBackground
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.Divider as DividerColor
import go.examate.hyp.ui.theme.ExamateTheme
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
    var memoryEnabled by remember { mutableStateOf(prefs.isMemoryEnabled) }

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

        // AI 对话设置
        Text(
            "AI 对话",
            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ToggleRow(
                    title = "对话记忆",
                    subtitle = "自动记住用户偏好(默认关闭,待优化)",
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        prefs.isMemoryEnabled = it
                    }
                )
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
                    "browse" to "浏览"
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
