package go.examate.hyp.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import go.examate.hyp.ui.components.LocalGlassBackdrop
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ════════════════════════════════════════════════
//  动态输入栏(参考 rikkahub:发送/停止合一 + 附件缩略图 + 回车发送)
// ════════════════════════════════════════════════

@Composable
fun DynamicInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hasImage: Boolean,
    imageUriString: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    canSend: Boolean,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
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
        // 附件缩略图预览(有图片时显示)
        AnimatedVisibility(visible = hasImage) {
            ImagePreviewChip(imageUriString, onClearImage)
        }
        // 主操作行:附件按钮 + 输入框 + 发送/停止按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左侧附件按钮(液态玻璃圆形)
            Box(
                Modifier.size(38.dp).then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(19.dp) },
                            effects = { vibrancy(); blur(12.dp.toPx()) },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(AccentBlue.copy(0.12f)) }
                        )
                    } else {
                        Modifier.clip(RoundedCornerShape(19.dp)).background(AccentBlue.copy(0.1f))
                    }
                ).clickable { onPickImage() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "图片", tint = AccentBlue, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            // 输入框(液态玻璃)
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
                placeholder = { Text("发掘无限可能", color = TextHint, fontSize = 14.sp) },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                singleLine = false, maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            // 发送/停止按钮合一(参考 rikkahub:loading 时变停止,有内容时变发送)
            SendStopButton(canSend = canSend, isLoading = isLoading, onSend = onSend, onStop = onStop)
        }
    }
}

/** 发送/停止按钮 — 始终显示,根据状态切换图标和颜色 */
@Composable
private fun SendStopButton(
    canSend: Boolean,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    val isStop = isLoading
    val containerColor = when {
        isStop -> Danger
        canSend -> AccentBlue
        else -> AccentBlue.copy(0.35f)  // 禁用态
    }
    val iconTint = Color.White
    val enabled = isStop || canSend

    Box(
        Modifier.size(38.dp).then(
            if (backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(19.dp) },
                    effects = { vibrancy(); blur(12.dp.toPx()) },
                    highlight = { Highlight.Plain },
                    onDrawSurface = { drawRect(containerColor) }
                )
            } else {
                Modifier.clip(RoundedCornerShape(19.dp)).background(containerColor)
            }
        ).then(if (enabled) Modifier.clickable { if (isStop) onStop() else onSend() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = isStop, enter = fadeIn(), exit = fadeOut()) {
            Icon(Icons.Filled.Close, contentDescription = "停止", tint = iconTint, modifier = Modifier.size(20.dp))
        }
        AnimatedVisibility(visible = !isStop, enter = fadeIn(), exit = fadeOut()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = iconTint, modifier = Modifier.size(18.dp))
        }
    }
}

/** 图片缩略图预览 chip(替代原来的"已选择图片"文字) */
@Composable
private fun ImagePreviewChip(imageUriString: String?, onClear: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(imageUriString) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(imageUriString) {
        if (imageUriString == null) { bitmap = null; return@LaunchedEffect }
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(imageUriString))?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) { null }
        }
    }

    Row(
        Modifier.padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图(48dp,8dp 圆角)
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "待发送图片",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } ?: Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(TextHint.copy(0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = TextHint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("已选择图片", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).clickable { onClear() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "清除", tint = TextHint, modifier = Modifier.size(16.dp))
        }
    }
}
