package go.examate.hyp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.ChipBg
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary

/**
 * 浅白色对话框(克制风格,用于表单/确认等场景)
 *
 * - 白色卡片(0.98 alpha) + 24dp 圆角
 * - 无遮罩(已移除浅黑色背景,避免影响视觉效果)
 * - 不使用 backdrop 液态玻璃,避免视觉过度
 */
@Composable
fun PlainDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(DialogBg)
                    .then(modifier)
            ) {
                content()
            }
        }
    }
}

@Composable
fun PlainDialogTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp)
    )
}

@Composable
fun PlainDialogContent(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp)
    )
}

/**
 * 浅白色对话框按钮区
 *
 * @param buttons 按钮列表,每个按钮:(text, isAccent, onClick)
 */
@Composable
fun PlainDialogButtons(
    buttons: List<Triple<String, Boolean, () -> Unit>>
) {
    Row(
        Modifier
            .padding(20.dp, 8.dp, 20.dp, 20.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 单按钮时:按钮靠右(前面加弹性间距)
        // 多按钮时:第一个按钮靠左,其余靠右
        if (buttons.size > 1) {
            // 第一个按钮(通常是删除)靠左,destructive 风格
            buttons.first().let { (text, isAccent, onClick) ->
                DialogButton(text, isAccent, onClick)
            }
            // 弹性间距把后续按钮推到右边
            Spacer(Modifier.weight(1f))
            // 其余按钮靠右排列
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                buttons.drop(1).forEach { (text, isAccent, onClick) ->
                    DialogButton(text, isAccent, onClick)
                }
            }
        } else {
            // 单按钮:弹性间距推到右边
            Spacer(Modifier.weight(1f))
            buttons.first().let { (text, isAccent, onClick) ->
                DialogButton(text, isAccent, onClick)
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    isAccent: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isAccent) AccentBlue else ChipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (isAccent) Color.White else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
