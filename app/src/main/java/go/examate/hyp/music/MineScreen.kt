package go.examate.hyp.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import go.examate.hyp.music.bili.BiliAuth
import go.examate.hyp.music.lx.LxSourceStore
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 「我的」页(= 设置)。两项:音源管理 + 音效(均独立页面)。无页内标题/头像。
 */
@Composable
fun MineScreen(
    onOpenSources: () -> Unit,
    onOpenEffects: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        MineItem(Icons.Filled.LibraryMusic, "音源管理", "本地 / 音源 / B站 启用与配置", onOpenSources) {
            Text("›", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.height(12.dp))
        MineItem(Icons.Filled.Equalizer, "音效", "均衡器 / 低音增强 / 环绕", onOpenEffects) {
            Text("›", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
    }
}

/**
 * 音源管理页 — 三段:
 *  1. 本地音乐(代办对勾启用 + 选文件夹)
 *  2. 音源(已导入 LX 脚本列表,逐项代办对勾启用 + 删除;底部「导入音源」按钮)
 *  3. B站(代办对勾启用 + 登录/登出)
 *
 * 在线源默认不内置任何内容,数据仅存本地。
 */
@Composable
fun MusicSourcesScreen(
    currentFolder: String?,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onBiliLogin: () -> Unit,
    onLxImport: () -> Unit
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        // ═══ 1. 本地音乐 ═══
        SourceCard(
            title = "本地音乐",
            desc = if (currentFolder.isNullOrBlank()) "全部设备音乐" else "文件夹:$currentFolder",
            enabled = MusicSourcePrefs.isEnabled(context, MusicSourceKind.LOCAL),
            onToggle = { MusicSourcePrefs.setEnabled(context, MusicSourceKind.LOCAL, it); tick++ },
            actionLabel = "选择文件夹",
            onAction = onPickFolder,
            secondaryLabel = if (!currentFolder.isNullOrBlank()) "全部" else null,
            onSecondary = { onClearFolder(); tick++ }
        )

        Spacer(Modifier.height(20.dp))

        // ═══ 2. 音源(LX 脚本列表) ═══
        SectionLabel("音源")
        val lxList = remember(tick) { LxSourceStore.loadAll(context) }
        if (lxList.isEmpty()) {
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text("尚未导入音源", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            lxList.forEach { src ->
                SourceCard(
                    title = src.name,
                    desc = "v${src.version}" +
                        if (src.sources.isNotEmpty()) " · 支持 ${src.sources.size} 平台" else "",
                    enabled = src.enabled,
                    onToggle = { LxSourceStore.setEnabled(context, src.id, it); tick++ },
                    actionLabel = "删除",
                    onAction = { pendingDeleteId = src.id }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        // 导入音源按钮
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(0.1f)).clickable {
                    onLxImport()
                    tick++   // 导入后立即刷新列表
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入音源", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ═══ 3. B站 ═══
        val biliOk = remember(tick) { MusicSourcePrefs.isConfigured(context, MusicSourceKind.BILI) }
        SourceCard(
            title = "B站",
            desc = if (biliOk) "已登录:${BiliAuth.uname(context).ifBlank { "B站用户" }}" else "需登录 B 站账号",
            enabled = MusicSourcePrefs.isEnabled(context, MusicSourceKind.BILI),
            onToggle = { MusicSourcePrefs.setEnabled(context, MusicSourceKind.BILI, it); tick++ },
            actionLabel = if (biliOk) "重新登录" else "登录 B 站",
            onAction = onBiliLogin,
            secondaryLabel = if (biliOk) "登出" else null,
            onSecondary = { BiliAuth.logout(context); tick++ }
        )

        Spacer(Modifier.height(20.dp))
        Text("在线源默认不内置任何内容,依赖你自己的账号/脚本,数据仅存本地。",
            color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
    }

    // 删除二次确认
    pendingDeleteId?.let { id ->
        val src = remember(tick, id) { LxSourceStore.loadAll(context).find { it.id == id } }
        ConfirmDialog(
            title = "删除音源",
            message = "确定删除「${src?.name ?: "音源"}」吗?删除后不可恢复。",
            onConfirm = {
                LxSourceStore.delete(context, id)
                pendingDeleteId = null
                tick++
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

/**
 * 通用音源卡 — 代办对勾启用 + 主操作 + (可选)次操作。
 * 比 LiquidGlassToggle 更简洁,与登录页"记住登录"对勾样式一致。
 */
@Composable
private fun SourceCard(
    title: String,
    desc: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(desc, color = TextHint, fontSize = 11.sp)
                }
                TodoCheckbox(checked = enabled, onClick = { onToggle(!enabled) })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(Primary.copy(0.12f))
                        .clickable { onAction() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text(actionLabel, color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                if (secondaryLabel != null) {
                    Box(
                        Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(0.5f))
                            .clickable { onSecondary() }.padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(secondaryLabel, color = TextSecondary, fontSize = 13.sp) }
                }
            }
        }
    }
}

/**
 * 代办对勾 — 方框 + 绿渐变填充 + 白色对勾旋转弹出(与登录页"记住登录"完全一致)。
 */
@Composable
private fun TodoCheckbox(checked: Boolean, onClick: () -> Unit) {
    val emerald = Color(0xFF10B981)
    val emeraldDark = Color(0xFF059669)
    val borderGray = Color(0xFFD1D5DB)

    val fillScale by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "todoFill"
    )
    val checkScale by animateFloatAsState(
        if (checked) 1f else 0.3f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "todoCheckScale"
    )
    val checkRot by animateFloatAsState(if (checked) 0f else 20f, spring(dampingRatio = 0.5f), label = "todoCheckRot")
    val checkAlpha by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "todoCheckAlpha")
    val borderColor by animateColorAsState(if (checked) emerald else borderGray, tween(300), label = "todoBorder")

    Box(
        Modifier.size(26.dp).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CardBg)
                .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(emerald, emeraldDark)))
            )
            Icon(
                Icons.Filled.Check, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(15.dp).graphicsLayer {
                    scaleX = checkScale; scaleY = checkScale; rotationZ = checkRot; alpha = checkAlpha
                }
            )
        }
    }
}

@Composable
private fun MineItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = title, tint = AccentBlue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextHint, fontSize = 11.sp)
            }
            trailing()
        }
    }
}

/**
 * 音效设置页(独立)。启用开关 + 均衡器预设 + 低音增强/环绕滑条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioEffectsScreen(effects: AudioEffects) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用音效", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("基于系统 AudioEffect,效果因设备而异", color = TextHint, fontSize = 11.sp)
                    }
                    LiquidGlassToggle(selected = effects.enabled, onSelect = { effects.updateEnabled(it) })
                }
            }
        }

        if (effects.enabled) {
            Spacer(Modifier.height(16.dp))
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("均衡器预设", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    if (effects.presetNames.isEmpty()) {
                        Text("播放歌曲后加载可用预设", color = TextHint, fontSize = 11.sp)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            effects.presetNames.forEachIndexed { i, name ->
                                PresetChip(name, selected = effects.presetIndex == i) { effects.setPreset(i) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SliderRow("低音增强", effects.bassStrength) { effects.setBass(it) }
                    Spacer(Modifier.height(14.dp))
                    SliderRow("环绕", effects.virtualStrength) { effects.setVirtual(it) }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${value * 100 / 1000}%", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..1000f,
            colors = SliderDefaults.colors(thumbColor = Primary, activeTrackColor = AccentBlue)
        )
    }
}

@Composable
private fun PresetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Primary else Primary.copy(0.08f))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text.ifBlank { "预设" }, color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/** 通用确认弹窗(简单 Box 按钮,避免 Dialog 内 backdrop 缺失) */
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
                .background(DialogBg)
                .padding(20.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(ChipBg).clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("取消", color = TextSecondary, fontSize = 14.sp) }
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(Primary).clickable { onConfirm() },
                    contentAlignment = Alignment.Center
                ) { Text("确定", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
