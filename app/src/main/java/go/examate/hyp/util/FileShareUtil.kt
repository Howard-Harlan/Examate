package go.examate.hyp.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件分享/打开工具
 *
 * 通过 FileProvider 把沙箱文件(filesDir/workspace)暴露为 content:// URI,
 * 供分享到其它 App(微信/WPS/保存到下载)或用其它 App 打开(如 WPS 打开 HTML/PDF)。
 */
object FileShareUtil {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "*/*"
    }

    /** 分享文件到其它 App(微信/WPS/保存到下载等) */
    fun shareFile(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) { ToastUtil.show(context, "文件不存在"); return }
        try {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "分享 ${file.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            ToastUtil.show(context, "分享失败: ${e.message}")
        }
    }

    /** 用其它 App 打开(如 WPS 打开 HTML/PDF/CSV) */
    fun openFile(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) { ToastUtil.show(context, "文件不存在"); return }
        try {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, "打开 ${file.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            ToastUtil.show(context, "没有可打开此文件的应用")
        }
    }
}
