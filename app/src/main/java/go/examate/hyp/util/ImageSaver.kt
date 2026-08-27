package go.examate.hyp.util

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 图片保存到系统相册 — minSdk 31,走 MediaStore 无需存储权限。
 * 保存目录:Pictures/Examate
 */
object ImageSaver {

    /** 保存本地图片文件到相册,成功返回 uri,失败返回 null */
    fun saveToGallery(context: Context, file: File): Uri? = runCatching {
        val ext = file.extension.ifBlank { "jpg" }.lowercase()
        val mimeType = when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        // 校验可解码,避免把损坏文件写进相册
        BitmapFactory.decodeFile(file.absolutePath) ?: return null

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Examate_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Examate")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return null
        uri
    }.getOrNull()
}
