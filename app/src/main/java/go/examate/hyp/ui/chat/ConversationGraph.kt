package go.examate.hyp.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ChatMsg

// 布局常量(dp)
private const val COL_W = 128f   // 列间距
private const val ROW_H = 92f    // 层间距
private const val NODE_W = 108f  // 节点宽
private const val NODE_H = 52f   // 节点高
private const val PAD = 28f      // 画布内边距

private data class GNode(val msg: ChatMsg, val depth: Int, val col: Float)

/**
 * 对话分支图(知识图谱视图)。
 *
 * 自上而下的分层树:主线竖直下延、分支左右分叉;双指缩放 + 拖动平移;
 * 激活路径高亮;点击节点 → 聚焦回线性视图。节点仅显示压缩预览,
 * 完整消息(卡片/工具步骤/Markdown/图片)仍由线性视图的 MessageBubble 渲染,故与旧内容完全兼容。
 */
@Composable
fun ConversationGraph(
    messages: List<ChatMsg>,
    activePathIds: Set<String>,
    onNodeClick: (String) -> Unit,
    onClose: () -> Unit
) {
    val layout = remember(messages) { layoutGraph(messages) }
    val nodes = layout.first
    val maxCol = layout.second
    val maxDepth = layout.third
    val canvasW = PAD * 2 + (maxCol + 1f) * COL_W
    val canvasH = PAD * 2 + (maxDepth + 1f) * ROW_H

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // 打开图谱时自动居中到当前激活节点(激活路径中最深的一个叶子)
    val activeNode = remember(nodes, activePathIds) {
        nodes.filter { it.msg.id in activePathIds }.maxByOrNull { it.depth }
    }
    var centered by remember(nodes) { mutableStateOf(false) }
    LaunchedEffect(viewport, activeNode) {
        if (!centered && viewport != IntSize.Zero && activeNode != null) {
            val cx = with(density) { (PAD + activeNode.col * COL_W + NODE_W / 2).dp.toPx() }
            val cy = with(density) { (PAD + activeNode.depth * ROW_H + NODE_H / 2).dp.toPx() }
            offset = Offset(viewport.width / 2f - cx, viewport.height / 2f - cy)
            centered = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgPrimary)
            // 拦截空白处点击,防止穿透到底层顶栏(之前点返回区会误退出)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        if (nodes.isEmpty()) {
            Text("暂无对话", color = TextHint, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        } else {
            // 在 @Composable 上下文读取颜色,供 Canvas DrawScope 使用
            val activeLineColor = AccentBlue
            // 可缩放平移的画布区
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.4f, 2.5f)
                            offset += pan
                        }
                    }
            ) {
                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        }
                        .size(canvasW.dp, canvasH.dp)
                ) {
                    // 连线(父→子)
                    Canvas(Modifier.fillMaxSize()) {
                        val byId = nodes.associateBy { it.msg.id }
                        nodes.forEach { n ->
                            val parent = n.msg.parentId?.let { byId[it] } ?: return@forEach
                            val startX = (PAD + parent.col * COL_W + NODE_W / 2).dp.toPx()
                            val startY = (PAD + parent.depth * ROW_H + NODE_H).dp.toPx()
                            val endX = (PAD + n.col * COL_W + NODE_W / 2).dp.toPx()
                            val endY = (PAD + n.depth * ROW_H).dp.toPx()
                            val onActive = n.msg.id in activePathIds && parent.msg.id in activePathIds
                            drawLine(
                                color = if (onActive) activeLineColor else Color(0x33000000),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = (if (onActive) 2.5f else 1.5f).dp.toPx()
                            )
                        }
                    }
                    // 节点
                    nodes.forEach { n ->
                        GraphNode(
                            node = n,
                            active = n.msg.id in activePathIds,
                            onClick = { onNodeClick(n.msg.id) }
                        )
                    }
                }
            }
        }

        // 顶栏:标题 + 关闭
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("对话分支图", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "关闭", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBlue.copy(0.1f))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        // 底部提示
        Text(
            "双指缩放 · 拖动平移 · 点击节点跳转",
            color = TextHint, fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun GraphNode(node: GNode, active: Boolean, onClick: () -> Unit) {
    val isUser = node.msg.role == "user"
    val accent = if (isUser) AccentBlue else Color(0xFF10B981)
    val label = node.msg.content.trim().replace("\n", " ").take(12)
        .ifEmpty { if (isUser) "提问" else if (node.msg.imageBase64 != null) "图片" else "回复" }
    Box(
        Modifier
            .offset(x = (PAD + node.col * COL_W).dp, y = (PAD + node.depth * ROW_H).dp)
            .size(NODE_W.dp, NODE_H.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent.copy(0.16f) else CardBg)
            .border(
                width = if (active) 1.5.dp else 0.5.dp,
                color = if (active) accent else Color(0x22000000),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(if (isUser) "我" else "AI", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 分层树布局:后序 DFS,叶子按顺序占列,内部节点列 = 子节点列均值 */
private fun layoutGraph(messages: List<ChatMsg>): Triple<List<GNode>, Float, Int> {
    if (messages.isEmpty()) return Triple(emptyList(), 0f, 0)
    val byId = messages.associateBy { it.id }
    val childrenOf = messages.groupBy { it.parentId }
    val roots = messages.filter { it.parentId == null || it.parentId !in byId }.sortedBy { it.time }
    val out = ArrayList<GNode>()
    var nextCol = 0f
    var maxDepth = 0
    val seen = HashSet<String>()

    fun place(msg: ChatMsg, depth: Int): Float {
        if (!seen.add(msg.id)) return nextCol  // 防环
        if (depth > maxDepth) maxDepth = depth
        val kids = childrenOf[msg.id]?.sortedBy { it.time }.orEmpty()
        val col: Float
        if (kids.isEmpty()) {
            col = nextCol; nextCol += 1f
        } else {
            val xs = kids.map { place(it, depth + 1) }
            col = (xs.first() + xs.last()) / 2f
        }
        out.add(GNode(msg, depth, col))
        return col
    }
    roots.forEach { place(it, 0) }
    val maxCol = (nextCol - 1f).coerceAtLeast(0f)
    return Triple(out, maxCol, maxDepth)
}
