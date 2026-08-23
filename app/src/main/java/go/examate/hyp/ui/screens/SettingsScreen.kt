package go.examate.hyp.ui.screens

import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    onUserSettingsClick: () -> Unit,
    onApiConfigClick: () -> Unit,
    onAgentSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSearchSettingsClick: () -> Unit,
    onSystemSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)

    // 从用户设置页返回后需刷新:监听生命周期 ON_RESUME 触发重组
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 读取用户资料,拼接 profileKey 作为 remember 的 key,数据变化时重组
    val nickname = prefs.nickname
    val school = prefs.school
    val displayFields = prefs.userDisplayFields
    val avatarPath = prefs.localAvatarPath
    val profileKey = "$refreshTick|$nickname|$school|${displayFields.sorted().joinToString()}|$avatarPath"

    Column(
        modifier
            .fillMaxSize()
            .background(BgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
            .padding(bottom = 120.dp)
    ) {
        // ═══ 顶部用户信息栏(logo 栏,扩大版) ═══
        UserProfileBar(profileKey = profileKey, onClick = onUserSettingsClick)
        Spacer(Modifier.height(24.dp))

        // Agent 设置:Skills + 记忆 + API 厂商设置
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onAgentSettingsClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Agents 设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Skills 技能导入、记忆配置、API 厂商",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 搜题功能设置(防截屏、悬浮球、悬浮菜单项、多 AI 验证)
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onSearchSettingsClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("搜题功能设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "防截屏保护、悬浮球、悬浮菜单项、多 AI 验证",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onSystemSettingsClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("系统设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "藏匿模式、首页设置、电池优化、清除缓存",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ═══ 关于 ═══
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onAboutClick() }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("关于", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "版本 ${go.examate.hyp.BuildConfig.VERSION_NAME} · 检查更新",
                        color = TextHint, fontSize = 11.sp, lineHeight = 15.sp
                    )
                }
                Text("›", color = TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 顶部用户信息栏(logo 栏):头像 + 已勾选的资料字段 + 箭头
 *
 * [profileKey] 拼接了昵称/学校/学号/展示字段,数据变化时整体重组以刷新显示。
 */
@Composable
private fun UserProfileBar(profileKey: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = PrefsManager.getInstance(context)

    // 根据 profileKey 缓存:本地头像 + 待展示字段列表
    val avatarBitmap = remember(profileKey) {
        go.examate.hyp.util.AvatarHelper.loadAvatar(context)
    }
    val displayEntries = remember(profileKey) {
        val fields = prefs.userDisplayFields
        // 若为空,至少展示昵称
        val effective = if (fields.isEmpty()) setOf("nickname") else fields
        // 固定顺序:昵称 → 学校
        listOf("nickname", "school")
            .filter { it in effective }
            .map { id ->
                when (id) {
                    "nickname" -> DisplayEntry(prefs.nickname.ifEmpty { Build.MODEL }, isHint = false)
                    "school" -> if (prefs.school.isEmpty()) {
                        DisplayEntry("未绑定学校", isHint = true)
                    } else {
                        DisplayEntry(prefs.school, isHint = false)
                    }
                    else -> DisplayEntry("", isHint = false)
                }
            }
    }

    FrostedGlassCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像:优先显示本地头像,否则与用户设置内部一致的默认占位(浅色圆底 + 人形图标)
            Box(
                Modifier
                    .size(60.dp)
                    .background(SurfaceVariant, CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                avatarBitmap?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } ?: run {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = "头像",
                        tint = TextHint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            // 资料字段:首行为主标题,其余为副标题
            Column(Modifier.weight(1f)) {
                displayEntries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.text,
                        color = if (entry.isHint) TextHint else if (index == 0) TextPrimary else TextSecondary,
                        fontSize = if (index == 0) 18.sp else 13.sp,
                        fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
            // 箭头指示可点击
            Text("›", color = TextHint, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
    }
}

/** 用户信息栏单行展示数据 */
private data class DisplayEntry(val text: String, val isHint: Boolean)
