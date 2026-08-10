package go.examate.hyp.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
    imageUris: List<String>,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveImageAt: (Int) -> Unit,
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
                        highlight = { Highlight.Ambient },
                        onDrawSurface = { drawRect(Color.White.copy(0.38f)) }
                    )
                } else {
                    Modifier.background(Color.White.copy(0.88f))
                }
            )
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // 附件缩略图预览(有图片时显示,展开/收起动画;多图横向排列)
        AnimatedVisibility(
            visible = imageUris.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ImagePreviewRow(imageUris, onRemoveImageAt)
        }
        var showAttachPanel by remember { mutableStateOf(false) }
        // 主操作行:附件按钮 + 输入框 + 发送/停止按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左侧附件按钮 — 点击在下方展开附件面板(拍照/图库/文件),+ 旋转为 ×
            val addInteraction = remember { MutableInteractionSource() }
            val addPressed by addInteraction.collectIsPressedAsState()
            val addScale by animateFloatAsState(
                if (addPressed) 0.85f else 1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "addScale"
            )
            val addRotation by animateFloatAsState(if (showAttachPanel) 45f else 0f, label = "addRot")
            // 在 @Composable 上下文读取颜色,供 onDrawSurface lambda 使用
            val accentBlueColor = AccentBlue
            Box(
                Modifier
                    .size(38.dp)
                    .graphicsLayer { scaleX = addScale; scaleY = addScale; rotationZ = addRotation }
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedRectangle(19.dp) },
                                effects = { vibrancy(); blur(12.dp.toPx()) },
                                highlight = { Highlight.Ambient },
                                onDrawSurface = { drawRect(accentBlueColor.copy(0.12f)) }
                            )
                        } else {
                            Modifier.clip(RoundedCornerShape(19.dp)).background(AccentBlue.copy(0.1f))
                        }
                    )
                    .clickable(interactionSource = addInteraction, indication = null) { showAttachPanel = !showAttachPanel },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加附件", tint = AccentBlue, modifier = Modifier.size(22.dp))
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
                            highlight = { Highlight.Ambient },
                            onDrawSurface = { drawRect(Color.White.copy(0.2f)) }
                        )
                    } else Modifier
                ),
                placeholder = { Text("发掘无限可能", color = TextHint, fontSize = 14.sp) },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = AccentBlue.copy(0.45f),
                    unfocusedBorderColor = Color.White.copy(0.35f),
                    cursorColor = AccentBlue
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                singleLine = false, maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            // 发送/停止按钮合一(参考 rikkahub:loading 时变停止,有内容时变发送)
            SendStopButton(canSend = canSend, isLoading = isLoading, onSend = onSend, onStop = onStop)
        }
        // 附件面板:点 + 后在下方展开(拍照/图库/文件 方格)
        AnimatedVisibility(
            visible = showAttachPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            AttachPanel(
                onTakePhoto = { showAttachPanel = false; onTakePhoto() },
                onPickGallery = { showAttachPanel = false; onPickGallery() },
                onPickFile = { showAttachPanel = false; onPickFile() }
            )
        }
    }
}

/** 发送/停止按钮 — 始终显示,状态切换带颜色过渡、按压回弹与图标 Crossfade */
@Composable
private fun SendStopButton(
    canSend: Boolean,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    val isStop = isLoading
    val enabled = isStop || canSend
    val targetColor = when {
        isStop -> Danger
        canSend -> AccentBlue
        else -> AccentBlue.copy(0.35f)  // 禁用态
    }
    // 颜色平滑过渡(禁用→可发送→停止之间不再生硬跳变)
    val containerColor by animateColorAsState(targetColor, tween(220), label = "sendColor")
    val iconTint = Color.White

    // 按压回弹
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed && enabled) 0.85f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "sendPress"
    )
    // 就绪弹入:可发送/停止时放大到满,禁用时略缩 —— 让按钮随输入“活”起来
    val readyScale by animateFloatAsState(
        if (enabled) 1f else 0.8f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sendReady"
    )
    // 发送箭头在禁用→可发送时旋转入场
    val iconRotation by animateFloatAsState(if (canSend || isStop) 0f else -45f, tween(240), label = "sendIconRot")
    // 渐变填充(顶部提亮),比纯色更有质感
    val gradient = Brush.linearGradient(listOf(lerp(containerColor, Color.White, 0.22f), containerColor))

    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = pressScale * readyScale; scaleY = pressScale * readyScale }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(20.dp) },
                        effects = { vibrancy(); blur(12.dp.toPx()) },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(brush = gradient) }
                    )
                } else {
                    Modifier.clip(RoundedCornerShape(20.dp)).background(gradient)
                }
            )
            .then(
                if (enabled) Modifier.clickable(interactionSource = interaction, indication = null) {
                    if (isStop) onStop() else onSend()
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = isStop, label = "sendIcon") { stop ->
            if (stop) {
                Icon(Icons.Filled.Close, contentDescription = "停止", tint = iconTint, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = iconTint,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = iconRotation }
                )
            }
        }
    }
}

/** 附件面板:拍照 / 图库 / 文件 三个方格入口 */
@Composable
private fun AttachPanel(onTakePhoto: () -> Unit, onPickGallery: () -> Unit, onPickFile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AttachCell(Modifier.weight(1f), Icons.Filled.CameraAlt, "拍照", onTakePhoto)
        AttachCell(Modifier.weight(1f), Icons.Filled.PhotoLibrary, "图库", onPickGallery)
        AttachCell(Modifier.weight(1f), Icons.Filled.Description, "文件", onPickFile)
    }
}

@Composable
private fun AttachCell(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AccentBlue.copy(0.08f))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = AccentBlue, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

/** 多图缩略图预览行(横向排列,每张右上角可删除) */
@Composable
private fun ImagePreviewRow(imageUris: List<String>, onRemoveAt: (Int) -> Unit) {
    Row(
        Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        imageUris.forEachIndexed { index, uri ->
            ImageThumb(uri) { onRemoveAt(index) }
        }
    }
}

@Composable
private fun ImageThumb(uriString: String, onRemove: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uriString) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(56.dp)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "待发送图片",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } ?: Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(TextHint.copy(0.2f)))
        // 右上角删除按钮
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp)
                .clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(0.5f))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}
