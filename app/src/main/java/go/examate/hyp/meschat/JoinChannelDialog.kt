package go.examate.hyp.meschat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.AccentPurple
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 频道加入/创建对话框 — 液态玻璃风格。
 *
 * 支持两种模式:
 * - 加入已有频道:输入频道名(可带或不带 #)
 * - 创建新频道:频道名 + 可选密码
 *
 * 密码留空则为公开频道。
 *
 * @param onDismiss 关闭
 * @param onJoinChannel 确认加入/创建 (channelName 不带 #, password 可空)
 */
@Composable
fun JoinChannelDialog(
    onDismiss: () -> Unit,
    onJoinChannel: (channel: String, password: String?) -> Unit
) {
    var channelInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }

    // 解析频道名:去除前导 # 和空白
    fun resolveChannel(): String? {
        val raw = channelInput.trim().removePrefix("#").removePrefix("＃")
        return raw.ifBlank { null }
    }

    PlainDialog(onDismissRequest = onDismiss, modifier = Modifier.width(320.dp)) {
        PlainDialogTitle("加入 / 创建频道")

        Column(Modifier.padding(horizontal = 24.dp)) {
            // 频道名输入
            OutlinedTextField(
                value = channelInput,
                onValueChange = { channelInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("频道名", color = TextHint, fontSize = 12.sp) },
                placeholder = { Text("例如:general", color = TextHint, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Tag, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White.copy(0.7f),
                    focusedBorderColor = AccentBlue.copy(0.5f),
                    unfocusedBorderColor = Color(0xFFE5E5EA)
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "频道名无需 # 前缀,留空则使用随机频道",
                color = TextHint,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(14.dp))

            // 密码保护开关
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (usePassword) AccentBlue else ChipBg)
                        .clickable { usePassword = !usePassword },
                    contentAlignment = Alignment.Center
                ) {
                    if (usePassword) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("设为私密频道(需要密码)", color = TextSecondary, fontSize = 13.sp)
            }

            // 密码输入(开关打开时显示)
            if (usePassword) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("频道密码", color = TextHint, fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White.copy(0.7f),
                        focusedBorderColor = AccentBlue.copy(0.5f),
                        unfocusedBorderColor = Color(0xFFE5E5EA)
                    )
                )
            }

            Spacer(Modifier.height(20.dp))
        }

        PlainDialogButtons(
            buttons = listOf(
                Triple("取消", false) { onDismiss() },
                Triple("加入", true) {
                    val ch = resolveChannel()
                    if (ch != null) {
                        val pwd = if (usePassword && passwordInput.isNotBlank()) passwordInput else null
                        onJoinChannel(ch, pwd)
                    }
                }
            )
        )
    }
}

// ════════════════════════════════════════
//  IRC 命令帮助面板(BottomSheet 内容)
// ════════════════════════════════════════

/**
 * IRC 命令帮助面板 — 可作为 ModalBottomSheet 的内容。
 *
 * 在聊天输入框中以 `/` 开头时可触发命令,支持的命令见 [IrcCommand]。
 */
@Composable
fun IrcCommandHelpPanel(onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("命令帮助", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("关闭", color = AccentBlue, fontSize = 13.sp, modifier = Modifier.clickable { onDismiss() })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "在输入框中以 / 开头可触发命令。命令不区分大小写。",
            color = TextHint,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))

        IrcCommand.entries.forEach { cmd ->
            IrcCommandRow(cmd)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IrcCommandRow(cmd: IrcCommand) {
    FrostedGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cmd.usage,
                    color = AccentBlue,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(cmd.label, color = TextHint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(cmd.description, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

// ════════════════════════════════════════
//  IRC 命令定义
// ════════════════════════════════════════

/**
 * 支持的 IRC 风格命令。
 *
 * 用于在聊天输入框中执行快捷操作。
 */
enum class IrcCommand(
    val usage: String,
    val label: String,
    val description: String
) {
    JOIN(
        "/join #频道 [密码]",
        "加入/创建频道",
        "加入指定频道;频道不存在则创建。私密频道需提供密码。"
    ),
    LEAVE(
        "/leave [#频道]",
        "离开频道",
        "离开当前频道或指定频道。离开后将不再接收该频道消息。"
    ),
    MSG(
        "/msg 昵称 消息内容",
        "发起私聊",
        "向指定昵称发送私聊消息,会自动创建私聊会话。"
    ),
    NICK(
        "/nick 新昵称",
        "修改昵称",
        "修改自己的昵称,其他节点会收到昵称更新通知。"
    ),
    WHO(
        "/who",
        "查看在线节点",
        "列出当前已连接的所有 Mesh 节点及其传输通道。"
    ),
    ME(
        "/me 动作描述",
        "发送动作",
        "发送一条第三人称动作消息(例如:/me 笑了)。"
    ),
    MUTE(
        "/mute [#频道]",
        "静音频道",
        "静音当前或指定频道,不再接收通知提醒(仍可查看消息)。"
    ),
    HELP(
        "/help",
        "查看命令",
        "显示所有可用命令的帮助信息。"
    )
}

/**
 * 解析输入框文本为 IRC 命令。
 *
 * @return 命令名(小写,不带 /)和参数列表;非命令返回 null。
 */
fun parseIrcCommand(input: String): Pair<String, List<String>>? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/")) return null
    val parts = trimmed.removePrefix("/").split(" ", "\t").filter { it.isNotBlank() }
    if (parts.isEmpty()) return null
    return parts.first().lowercase() to parts.drop(1)
}
