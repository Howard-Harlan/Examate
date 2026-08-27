package go.examate.hyp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 搜题启动模式选择弹窗 — 原深层学习页的启动弹窗抽出共享,
 * 聊天页快捷按钮复用同一交互与权限启动链。
 */
@Composable
fun SearchLaunchDialog(
    onDismiss: () -> Unit,
    onLaunchAccessibility: () -> Unit,
    onLaunchScreenshot: () -> Unit
) {
    PlainDialog(onDismissRequest = onDismiss) {
        PlainDialogTitle("选择启动模式")
        PlainDialogContent("无障碍搜题:通过无障碍服务读取屏幕文本自动搜题。\n截屏搜题:通过截屏框选区域,由 AI 视觉识别题目。")
        Row(
            Modifier
                .padding(20.dp, 8.dp, 20.dp, 20.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LaunchDialogButton("无障碍", isAccent = true, Modifier.weight(1f)) {
                onDismiss()
                onLaunchAccessibility()
            }
            LaunchDialogButton("取消", isAccent = false, Modifier.weight(1f)) {
                onDismiss()
            }
            LaunchDialogButton("截屏", isAccent = true, Modifier.weight(1f)) {
                onDismiss()
                onLaunchScreenshot()
            }
        }
    }
}

@Composable
private fun LaunchDialogButton(
    text: String,
    isAccent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isAccent) AccentBlue else ChipBg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isAccent) Color.White else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
