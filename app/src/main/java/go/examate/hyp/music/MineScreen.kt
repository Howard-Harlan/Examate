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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import go.examate.hyp.music.auth.MusicAuth
import go.examate.hyp.music.bili.BiliAuth
import go.examate.hyp.music.lx.LxSourceStore
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 「我的」页 — 入口收敛(播放/音源/缓存已合并至功能设置,缓存清理由系统设置统一管理):
 * 账号 / 功能设置 / 音效 / 外观与特效。
 */
@Composable
fun MineScreen(
    onOpenAccount: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenFeature: () -> Unit,
    onOpenPlaylists: () -> Unit = {}
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    // 摘要:账号登录数,直接显示在入口卡上
    val loginCount = remember(tick) {
        var n = 0
        if (BiliAuth.isLoggedIn(context)) n++
        listOf("wy", "tx", "kg", "kw", "mg").forEach { if (MusicAuth.isLoggedIn(context, it)) n++ }
        n
    }
    // 摘要:歌单数
    val playlistCount = remember(tick) { PlaylistStore.loadAll(context).size }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        MineItem(Icons.Filled.QueueMusic, "我的歌单", "$playlistCount 个歌单", onOpenPlaylists)
        Spacer(Modifier.height(12.dp))
        MineItem(Icons.Filled.Person, "账号", "已登录 $loginCount 个账号", onOpenAccount)
        Spacer(Modifier.height(12.dp))
        MineItem(Icons.Filled.Tune, "功能设置", "播放路线 / 音质 / 音源 / 音频独占", onOpenFeature)
        Spacer(Modifier.height(12.dp))
        MineItem(Icons.Filled.Equalizer, "音效", "内置音效 / 均衡器 / 低音 / 环绕", onOpenEffects)
        Spacer(Modifier.height(12.dp))
        MineItem(Icons.Filled.Palette, "外观与特效", "播放页视觉效果 / 自定义播放器", onOpenAppearance)
    }
}

/**
 * 账号页 — 六平台登录卡 + B站搜索开关(官方播放路线需先登录对应平台)。
 */
@Composable
fun AccountPage() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {

        Text("官方播放路线需先登录对应平台(模拟登录拿数据);登录态仅存本地。",
            color = TextHint, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))

        // B站
        val biliOk = remember(tick) { BiliAuth.isLoggedIn(context) }
        AccountCard(
            title = "B站(小毕音乐)",
            desc = if (biliOk) "已登录:${BiliAuth.uname(context).ifBlank { "B站用户" }}" else "无需登录即可播放,登录可同步进度",
            loggedIn = biliOk,
            onLogin = {
                context.startActivity(android.content.Intent(context, go.examate.hyp.music.bili.BiliLoginActivity::class.java))
            },
            onLogout = { BiliAuth.logout(context); tick++ }
        )
        Spacer(Modifier.height(10.dp))
        // 五大音乐平台
        val platforms = listOf(
            "wy" to go.examate.hyp.music.auth.NeteaseLoginActivity::class.java,
            "tx" to go.examate.hyp.music.auth.TxLoginActivity::class.java,
            "kg" to go.examate.hyp.music.auth.KugouLoginActivity::class.java,
            "kw" to go.examate.hyp.music.auth.KuwoLoginActivity::class.java,
            "mg" to go.examate.hyp.music.auth.MiguLoginActivity::class.java
        )
        platforms.forEach { (platform, activityClass) ->
            val ok = remember(tick) { MusicAuth.isLoggedIn(context, platform) }
            val nick = remember(tick) { MusicAuth.nick(context, platform) }
            AccountCard(
                title = MusicAuth.aliasOf(platform),
                desc = if (ok) "已登录${if (nick.isNotBlank()) ":$nick" else ""}" else "官方播放需先登录",
                loggedIn = ok,
                onLogin = { context.startActivity(android.content.Intent(context, activityClass)) },
                onLogout = { MusicAuth.logout(context, platform); tick++ }
            )
            Spacer(Modifier.height(10.dp))
        }
        // B站搜索显示开关
        var biliSearch by remember { mutableStateOf(AccountPrefs.isBiliSearchEnabled(context)) }
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    biliSearch = !biliSearch
                    AccountPrefs.setBiliSearchEnabled(context, biliSearch)
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("搜索中显示 B站", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("开启后出现在搜索源与聚合搜索中(需已登录B站)", color = TextHint, fontSize = 11.sp)
                }
                Switch(checked = biliSearch, onCheckedChange = {
                    biliSearch = it
                    AccountPrefs.setBiliSearchEnabled(context, it)
                })
            }
        }
    }
}

/**
 * 功能设置页 — 合并播放设置 / 音源管理 / 音频独占(无标题与解释文字)。
 */
@Composable
fun FeatureSettingsPage(onLxImport: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 52.dp, bottom = 150.dp)
    ) {
        // ═══ 播放路线 + 音质 ═══
        SectionLabel("播放路线")
        PlaySettingsSection()

        // ═══ 音源管理 ═══
        Spacer(Modifier.height(16.dp))
        SectionLabel("音源管理")
        val lxList = remember(tick, go.examate.hyp.music.lx.LxSourceStore.version) { LxSourceStore.loadAll(context) }
        val currentActiveId = remember(tick, go.examate.hyp.music.lx.LxSourceStore.version) { LxSourceStore.activeSource(context)?.id }
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
                    active = src.id == currentActiveId,
                    onSelect = { LxSourceStore.setActive(context, src.id); tick++ },
                    onToggle = { LxSourceStore.setEnabled(context, src.id, it); tick++ },
                    actionLabel = "删除",
                    onAction = { pendingDeleteId = src.id }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Primary.copy(0.1f)).clickable {
                    onLxImport()
                    tick++
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

        // ═══ 音频独占(系统常见样式:FrostedGlassCard + Switch) ═══
        Spacer(Modifier.height(16.dp))
        SectionLabel("音频独占")
        var exclusive by remember { mutableStateOf(MusicFeaturePrefs.isAudioExclusive(context)) }
        FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    exclusive = !exclusive
                    MusicFeaturePrefs.setAudioExclusive(context, exclusive)
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("音频独占", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Switch(checked = exclusive, onCheckedChange = {
                    exclusive = it
                    MusicFeaturePrefs.setAudioExclusive(context, it)
                })
            }
        }
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

/** 播放设置段:播放路线二选一 + 播放音质(无解释文字) */
@Composable
private fun PlaySettingsSection() {
    val context = LocalContext.current
    var route by remember { mutableStateOf(PlayRoutePrefs.getRoute(context)) }
    var quality by remember { mutableStateOf(PlayRoutePrefs.getQuality(context)) }

    PlayRoute.entries.forEach { r ->
        val selected = route == r
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) SelectedBg else SurfaceVariant.copy(0.6f))
                .clickable {
                    route = r
                    PlayRoutePrefs.setRoute(context, r)
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(r.label, color = if (selected) Color.White else TextPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("播放音质", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(9.dp)).background(ChipBg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("128k", "320k", "flac", "flac24bit").forEach { q ->
                    val sel = quality == q
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp))
                            .background(if (sel) SelectedBg else Color.Transparent)
                            .clickable { quality = q; PlayRoutePrefs.setQuality(context, q) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (q == "flac24bit") "24bit" else q, color = if (sel) Color.White else TextSecondary,
                            fontSize = 11.sp, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** 账号登录卡:状态 + 登录/登出 */
@Composable
private fun AccountCard(
    title: String,
    desc: String,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (loggedIn) Color(0xFF10B981) else Color(0xFF9CA3AF))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(desc, color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(Primary.copy(0.12f))
                        .clickable { onLogin() }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (loggedIn) "重新登录" else "登录", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (loggedIn) {
                    Box(
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(if (IsDark) ChipBg else Color.White.copy(0.5f))
                            .clickable { onLogout() }.padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("登出", color = TextSecondary, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

/**
 * 通用音源卡 — 代办对勾启用 + 主操作 + (可选)次操作。
 * @param active 是否为当前使用源(单激活源模型,点标题区切换)
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
    onSecondary: () -> Unit = {},
    active: Boolean = false,
    onSelect: () -> Unit = {}
) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { if (!active) onSelect() }
                        .padding(vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(Primary.copy(0.15f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text("使用中", color = Primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
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
                        Modifier.clip(RoundedCornerShape(11.dp))
                            .background(if (IsDark) ChipBg else Color.White.copy(0.5f))
                            .clickable { onSecondary() }.padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(secondaryLabel, color = TextSecondary, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** 代办对勾 — 方框 + 绿渐变填充 + 白色对勾旋转弹出 */
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
private fun MineItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
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
            Text("›", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
    }
}

/**
 * 音效设置页 — 启用开关 + 内置音效列表(一键调音预设) + 手动精调(低音/环绕/系统均衡器预设)。
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
            // ═══ 内置音效列表 ═══
            Spacer(Modifier.height(16.dp))
            SectionLabel("内置音效")
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    FxProfileItem(
                        name = "原声", desc = "不应用内置配置",
                        selected = effects.fxProfileId == AudioEffects.FX_NONE,
                        onClick = { effects.setFxProfile(AudioEffects.FX_NONE) }
                    )
                    AudioEffects.builtinProfiles.forEach { p ->
                        FxProfileItem(
                            name = p.name, desc = p.desc,
                            selected = effects.fxProfileId == p.id,
                            onClick = { effects.setFxProfile(p.id) }
                        )
                    }
                }
            }

            // ═══ 手动精调 ═══
            Spacer(Modifier.height(16.dp))
            SectionLabel("手动精调(选择后退出内置配置)")
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
                    Spacer(Modifier.height(14.dp))
                    SliderRow("低音增强", effects.bassStrength) { effects.setBass(it) }
                    Spacer(Modifier.height(14.dp))
                    SliderRow("环绕", effects.virtualStrength) { effects.setVirtual(it) }
                }
            }
        }
    }
}

/** 内置音效配置项 */
@Composable
private fun FxProfileItem(name: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Primary.copy(0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) Primary else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = TextHint, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
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
            .background(if (selected) SelectedBg else Primary.copy(0.08f))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text.ifBlank { "预设" }, color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/** 通用确认弹窗 */
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
