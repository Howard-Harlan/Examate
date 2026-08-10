package go.examate.hyp.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.CardBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ChatSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

// ════════════════════════════════════════════════
//  历史聊天面板(可向上拖拽 + 搜索)
// ════════════════════════════════════════════════

@Composable
fun HistoryPanel(
    sessions: List<ChatSession>, timeFmt: SimpleDateFormat, currentSessionId: String,
    onNewChat: () -> Unit, onSessionClick: (ChatSession) -> Unit,
    onDelete: (String) -> Unit, onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 面板高度比例:0.55 初始 → 0.92 拖到顶部
    val heightFraction = remember { Animatable(0.55f) }
    var searchQuery by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = onDismiss
        )
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction.value)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(CardBg)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = {}
                )
        ) {
            // ═══ 拖拽手柄(向上拖动扩展至接近状态栏) ═══
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            val delta = -dragAmount / size.height
                            scope.launch {
                                heightFraction.animateTo(
                                    (heightFraction.value + delta).coerceIn(0.4f, 0.92f),
                                    tween(60)
                                )
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextHint.copy(alpha = 0.3f))
                )
            }

            // ═══ 标题行 ═══
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("历史聊天", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(AccentBlue)
                        .clickable { onNewChat() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新聊天", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ═══ 搜索框 ═══
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索历史聊天", color = TextHint, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除",
                                tint = TextHint,
                                modifier = Modifier.clickable { searchQuery = "" }.padding(4.dp).size(14.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
            }

            HorizontalDivider(color = TextHint.copy(alpha = 0.15f), thickness = 0.5.dp)

            // ═══ 会话列表(按搜索词过滤) ═══
            val filtered = if (searchQuery.isBlank()) sessions
            else sessions.filter { session ->
                session.title.contains(searchQuery, ignoreCase = true) ||
                    session.messages.any { msg ->
                        msg.content.contains(searchQuery, ignoreCase = true)
                    }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isNotEmpty()) "未找到匹配的聊天" else "暂无历史聊天",
                        color = TextHint, fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(filtered, key = { it.id }) { session ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSessionClick(session) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    color = if (session.id == currentSessionId) AccentBlue else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (session.id == currentSessionId) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1
                                )
                                Text(
                                    "${session.messages.size} 条 · ${timeFmt.format(Date(session.updateTime))}",
                                    color = TextHint,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除",
                                tint = TextHint,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onDelete(session.id) }
                                ).padding(8.dp).size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
