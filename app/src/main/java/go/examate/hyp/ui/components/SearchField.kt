package go.examate.hyp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.theme.IsDark
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary

/**
 * 统一美化搜索框 — 圆角胶囊、玻璃底、放大镜 + 可清除 + 键盘搜索键。
 * 音乐/阅读等模块共用,替代难看的 OutlinedTextField。
 * @param showLeadingIcon 是否显示左侧放大镜(默认 true;若右侧已有搜索按钮可关闭避免重复)
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showLeadingIcon: Boolean = true
) {
    // 深色模式降白 + 主按钮图标反色(深色下 Primary 提亮为浅灰,需配深色图标)
    val isDark = IsDark
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White.copy(if (isDark) 0.15f else 0.55f))
            .border(1.dp, Color.White.copy(if (isDark) 0.25f else 0.7f), RoundedCornerShape(23.dp))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showLeadingIcon) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = TextHint, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { onValueChange("") },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, contentDescription = "清除", tint = TextHint, modifier = Modifier.size(16.dp)) }
        }
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Primary).clickable { onSearch() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Search, contentDescription = "搜索",
                tint = if (isDark) Color(0xFF1A1A2E) else Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
