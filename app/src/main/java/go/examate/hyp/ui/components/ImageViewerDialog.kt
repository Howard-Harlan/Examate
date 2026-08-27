package go.examate.hyp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ImageSaver
import go.examate.hyp.util.ToastUtil
import java.io.File

/**
 * 全屏图片查看器 — 速记附图 / 笔记配图 / 聊天图片(本地文件或 content URI)共用。
 * [model] 传 File 或 Uri(AsyncImage 通用模型);点击图片区域关闭;
 * 底部提供「保存到相册」(MediaStore,无需权限;URI 来源先落临时文件)。
 */
@Composable
fun ImageViewerDialog(
    model: Any,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 保存用临时文件(URI 来源复制而来;File 来源直接用)
    val saveFile: File? = remember(model) {
        when (model) {
            is File -> model.takeIf { it.exists() }
            is Uri -> runCatching {
                val f = File(context.cacheDir, "preview_${System.currentTimeMillis()}.img")
                context.contentResolver.openInputStream(model)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                f.takeIf { it.length() > 0 }
            }.getOrNull()
            else -> null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF0141619))
                .padding(12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = "查看图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("关闭", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SelectedBg)
                        .clickable {
                            val f = saveFile
                            if (f == null) {
                                ToastUtil.show(context, "图片不可用")
                            } else if (ImageSaver.saveToGallery(context, f) != null) {
                                ToastUtil.show(context, "已保存到相册 Pictures/Examate")
                            } else {
                                ToastUtil.show(context, "保存失败")
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(5.dp))
                    Text("保存到相册", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 用系统浏览器打开链接(笔记预览 Markdown 链接点击用) */
fun openUrlExternally(context: android.content.Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        ToastUtil.show(context, "无法打开链接")
    }
}
