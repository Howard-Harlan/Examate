package go.examate.hyp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextSecondary

// ════════════════════════════════════════════════
//  动态输入栏
// ════════════════════════════════════════════════

@Composable
fun DynamicInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hasImage: Boolean,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    agentsMode: Boolean = false,
    onToggleAgents: (() -> Unit)? = null
) {
    val backdrop = LocalGlassBackdrop.current
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(28.dp) },
                        effects = {
                            vibrancy()
                            blur(20.dp.toPx())
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(Color.White.copy(0.5f)) }
                    )
                } else {
                    Modifier.background(Color.White.copy(0.88f))
                }
            )
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        AnimatedVisibility(visible = hasImage) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("已选择图片", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, contentDescription = "清除", tint = TextHint, modifier = Modifier.clickable { onClearImage() }.padding(4.dp).size(16.dp))
            }
        }
        // 对话/Agents 模式切换(下划线指示激活态)
        if (onToggleAgents != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { if (agentsMode) onToggleAgents() }.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    Text("对话", color = if (!agentsMode) Color(0xFF5B8DEF) else TextHint, fontSize = 12.sp)
                    if (!agentsMode) Spacer(Modifier.width(24.dp).height(2.dp).background(Color(0xFF5B8DEF), RoundedCornerShape(1.dp)))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { if (!agentsMode) onToggleAgents() }.padding(horizontal = 14.dp, vertical = 2.dp)) {
                    Text("Agents", color = if (agentsMode) Color(0xFF5B8DEF) else TextHint, fontSize = 12.sp)
                    if (agentsMode) Spacer(Modifier.width(24.dp).height(2.dp).background(Color(0xFF5B8DEF), RoundedCornerShape(1.dp)))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(19.dp) },
                            effects = { vibrancy(); blur(12.dp.toPx()) },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(Color(0xFF5B8DEF).copy(0.12f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(19.dp)).background(Color(0xFF5B8DEF).copy(0.1f))
                    }
                ).clickable { onPickImage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "图片", tint = Color(0xFF5B8DEF), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text, onValueChange = onTextChange,
                modifier = Modifier.weight(1f).then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(22.dp) },
                            effects = { vibrancy(); blur(16.dp.toPx()) },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(Color.White.copy(0.3f)) }
                        )
                    } else Modifier
                ),
                placeholder = { Text("输入消息", color = TextHint, fontSize = 14.sp) },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                singleLine = false, maxLines = 4
            )
            AnimatedVisibility(visible = canSend, enter = fadeIn(), exit = fadeOut()) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(22.dp)).then(
                            if (backdrop != null) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RoundedRectangle(22.dp) },
                                    effects = { vibrancy(); blur(12.dp.toPx()) },
                                    highlight = { Highlight.Plain },
                                    onDrawSurface = { drawRect(Color(0xFF5B8DEF)) }
                                )
                            } else {
                                Modifier.background(Color(0xFF5B8DEF))
                            }
                        ).clickable { onSend() }.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
