package go.examate.hyp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import go.examate.hyp.util.ToastUtil
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Success
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ToolStatus
import go.examate.hyp.util.ToolStep

/**
 * 自定义复制图标(material-icons-core 不含 ContentCopy,避免引入 extended 包)。
 * 两个重叠的圆角矩形,经典复制图标造型。
 */
private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            strokeLineWidth = 0f
        ) {
            // 前景矩形(被复制的文档)
            moveTo(8f, 2f)
            horizontalLineTo(16f)
            arcTo(2f, 2f, 0f, false, true, 18f, 4f)
            verticalLineTo(16f)
            arcTo(2f, 2f, 0f, false, true, 16f, 18f)
            horizontalLineTo(8f)
            arcTo(2f, 2f, 0f, false, true, 6f, 16f)
            verticalLineTo(4f)
            arcTo(2f, 2f, 0f, false, true, 8f, 2f)
            close()
        }
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            strokeLineWidth = 0f
        ) {
            // 背景矩形(原始文档)
            moveTo(10f, 6f)
            verticalLineTo(20f)
            arcTo(2f, 2f, 0f, false, false, 12f, 22f)
            horizontalLineTo(20f)
            arcTo(2f, 2f, 0f, false, false, 22f, 20f)
            verticalLineTo(8f)
            arcTo(2f, 2f, 0f, false, false, 20f, 6f)
            horizontalLineTo(10f)
            close()
        }
    }.build()
}

// ═══════════════════════════════════════════════════════════════
// 链式思考容器(参考 rikkahub ChainOfThought,简化适配我们的样式)
// ═══════════════════════════════════════════════════════════════

/**
 * 工具调用时间线:左侧竖线 + 步骤图标 + 标题,支持折叠/展开,
 * 步骤超过 [collapsedVisibleCount] 时折叠只显示尾部若干步。
 *
 * 样式:浅灰圆角卡片(非液态玻璃,保持轻量),与消息气泡区分。
 * 图标用背景色遮罩覆盖竖线,形成"步骤节点"效果。
 */
@Composable
fun ToolStepsTimeline(
    steps: List<ToolStep>,
    modifier: Modifier = Modifier,
    collapsedVisibleCount: Int = 2
) {
    if (steps.isEmpty()) return
    var expanded by remember(steps.size) { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val visibleSteps = if (expanded || !canCollapse) steps else steps.takeLast(collapsedVisibleCount)
    val lineColor = Color(0xFFE0E4EC)
    val cardBg = Color(0xFFF7F8FA)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(tween(200))
    ) {
        // 折叠/展开控制条(顶部,与步骤图标对齐)
        if (canCollapse) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = AccentBlue
                )
                Text(
                    text = if (expanded) "收起" else "展开全部 (${steps.size})",
                    color = AccentBlue,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }

        // 时间线区域:左侧竖线 + 步骤列表
        Box(
            modifier = Modifier.drawBehind {
                val x = 11.dp.toPx()
                val pad = 10.dp.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(x, pad),
                    end = Offset(x, size.height - pad),
                    strokeWidth = 1.dp.toPx()
                )
            }
        ) {
            Column {
                visibleSteps.forEach { step ->
                    ToolStepRow(step, cardBg)
                }
            }
        }
    }
}

@Composable
private fun ToolStepRow(step: ToolStep, coverColor: Color) {
    var showOutput by remember(step.name) { mutableStateOf(false) }
    val hasOutput = step.output.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasOutput) Modifier.clickable { showOutput = !showOutput }
                else Modifier
            )
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 步骤图标(22dp 区域,18dp 背景遮罩覆盖竖线)
            Box(
                modifier = Modifier.size(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(coverColor),
                    contentAlignment = Alignment.Center
                ) {
                    when (step.status) {
                        ToolStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.dp,
                            color = AccentBlue
                        )
                        ToolStatus.DONE -> Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Success
                        )
                        ToolStatus.ERROR -> Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Danger
                        )
                    }
                }
            }

            // 标题
            Text(
                text = step.label,
                color = if (step.status == ToolStatus.RUNNING) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 展开指示器(有输出时)
            if (hasOutput) {
                Icon(
                    imageVector = if (showOutput) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = TextHint
                )
            }
        }

        // 输出详情(展开时)
        if (showOutput && hasOutput) {
            Text(
                text = step.output,
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
                modifier = Modifier
                    .padding(start = 26.dp, top = 2.dp, bottom = 2.dp, end = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFEEEEEE))
                    .padding(6.dp),
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 消息操作行(参考 rikkahub ChatMessageActions,简化为图标按钮)
// ═══════════════════════════════════════════════════════════════

/**
 * 消息操作行:复制 / 重新生成(仅助手) / 删除。
 * 圆形图标按钮,默认低对比度,hover/press 时通过 ripples 反馈。
 * 删除采用两段式:首次点击变红提示"再次点击删除",3 秒内再次点击执行。
 */
@Composable
fun MessageActionRow(
    isUser: Boolean,
    onCopy: () -> Unit,
    onBranch: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf(false) }
    LaunchedEffect(pendingDelete) {
        if (pendingDelete) { delay(3000); pendingDelete = false }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(CopyIcon, "复制", TextSecondary, onCopy)
        if (!isUser) {
            ActionChip(Icons.Filled.AltRoute, "追问", AccentBlue, onBranch)
            ActionChip(Icons.Filled.Refresh, "重新生成", TextSecondary, onRegenerate)
        }
        ActionChip(
            icon = Icons.Filled.Delete,
            label = if (pendingDelete) "再点删除" else "删除",
            tint = if (pendingDelete) Danger else TextSecondary
        ) {
            if (pendingDelete) { pendingDelete = false; onDelete() } else pendingDelete = true
        }
    }
}

/** 操作行小按钮:图标 + 文字标签(比原纯图标更大更易点) */
@Composable
private fun ActionChip(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** 复制消息文本到剪贴板并 Toast 提示 */
internal fun copyMessageText(context: Context, content: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", content))
    ToastUtil.show(context, "已复制")
}
