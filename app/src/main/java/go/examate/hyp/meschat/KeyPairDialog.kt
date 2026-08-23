package go.examate.hyp.meschat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.components.PlainDialogButtons
import go.examate.hyp.ui.components.PlainDialogTitle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.AccentMint
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 密钥匹对私聊对话框。
 *
 * 两个用户约定同一个配对密钥(口令),输入后即可进入同一加密房间:
 * - 消息以该密钥派生的 AES 密钥加密,只有持有相同密钥的设备能解密
 * - 房间 ID 由密钥确定性派生,不暴露密钥本身
 * - 本地网状网络与 Nostr 中继双通道同步加密广播,支持跨网络配对
 *
 * @param onDismiss 关闭
 * @param onConfirm 确认配对(key 为原始口令,交由控制器派生房间与密钥)
 */
@Composable
fun KeyPairChatDialog(
    onDismiss: () -> Unit,
    onConfirm: (key: String) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    val canConfirm = keyInput.trim().length >= 4

    PlainDialog(onDismissRequest = onDismiss, modifier = Modifier.width(320.dp)) {
        PlainDialogTitle("密钥匹对私聊")

        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = AccentMint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "与对方约定同一个配对密钥,输入后即可进入同一加密房间。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对密钥(至少4位)", color = TextHint, fontSize = 12.sp) },
                placeholder = { Text("例如:library-2024", color = TextHint, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Text(
                        if (showKey) "隐藏" else "显示",
                        color = AccentBlue,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showKey = !showKey }.padding(4.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (canConfirm) onConfirm(keyInput.trim())
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = if (IsDark) Color.White.copy(0.15f) else Color.White,
                    unfocusedContainerColor = if (IsDark) Color.White.copy(0.08f) else Color.White.copy(0.7f),
                    focusedBorderColor = AccentBlue.copy(0.5f),
                    unfocusedBorderColor = if (IsDark) Color.White.copy(0.25f) else Color(0xFFE5E5EA)
                )
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "密钥仅保存在本机;消息端到端加密,云端中继也无法读取内容。",
                color = TextHint,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "房间 ID " + (if (keyInput.isNotBlank()) NostrCrypto.roomIdFromKey(keyInput.trim()) else "--------") + "(供对方核对)",
                color = TextHint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(20.dp))
        }

        PlainDialogButtons(
            buttons = listOf(
                Triple("取消", false) { onDismiss() },
                Triple("进入房间", true) {
                    if (canConfirm) onConfirm(keyInput.trim())
                }
            )
        )
    }
}
