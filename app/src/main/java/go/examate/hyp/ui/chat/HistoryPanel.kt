package go.examate.hyp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.util.ChatSession
import java.text.SimpleDateFormat
import java.util.Date

// ════════════════════════════════════════════════
//  历史聊天面板
// ════════════════════════════════════════════════

@Composable
fun HistoryPanel(
    sessions: List<ChatSession>, timeFmt: SimpleDateFormat, currentSessionId: String,
    onNewChat: () -> Unit, onSessionClick: (ChatSession) -> Unit,
    onDelete: (String) -> Unit, onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss
        )
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {}
                )
        ) {
            Box(
                Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(TextHint.copy(alpha = 0.3f))
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("历史聊天", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF5B8DEF)).clickable { onNewChat() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新聊天", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Divider(color = TextHint.copy(alpha = 0.15f), thickness = 0.5.dp)
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无历史聊天", color = TextHint, fontSize = 14.sp) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(sessions.size, key = { sessions[it].id }) { i ->
                        val session = sessions[i]
                        Row(Modifier.fillMaxWidth().clickable { onSessionClick(session) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(session.title, color = if (session.id == currentSessionId) Color(0xFF5B8DEF) else TextPrimary, fontSize = 14.sp, fontWeight = if (session.id == currentSessionId) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
                                Text("${session.messages.size} 条 · ${timeFmt.format(Date(session.updateTime))}", color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            Icon(Icons.Filled.Close, contentDescription = "删除", tint = TextHint, modifier = Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = { onDelete(session.id) }
                            ).padding(8.dp).size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
