package go.examate.hyp.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.entity.ModelProvider
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.DialogBg
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

// ════════════════════════════════════════════════
//  下拉选择模型
// ════════════════════════════════════════════════

@Composable
fun ModelSelector(providers: List<ModelProvider>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val safeIndex = if (providers.isEmpty()) 0 else selectedIndex.coerceIn(0, providers.size - 1)
    val currentName = if (providers.isNotEmpty()) providers[safeIndex].displayName else "未配置 API"
    Box {
        Row(
            Modifier.clickable { expanded = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentName,
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextHint, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(220.dp),
            shape = RoundedCornerShape(20.dp),
            // 主题化容器:浅色白/深色深灰
            containerColor = DialogBg,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            providers.forEachIndexed { i, p ->
                val isSelected = i == safeIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(i); expanded = false }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.displayName,
                        color = if (isSelected) AccentBlue else TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Text("✓", color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
