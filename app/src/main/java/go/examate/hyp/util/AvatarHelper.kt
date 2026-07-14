package go.examate.hyp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import go.examate.hyp.config.PrefsManager
import java.io.File

/**
 * 本地头像管理工具
 *
 * - 头像保存在应用内部存储,永久保存
 * - 未设置头像时返回 null,由调用方显示默认图标
 * - 支持两种头像:用户本地头像(localAvatar)与 Agent 头像(agentAvatar)
 */
object AvatarHelper {

    private const val AVATAR_DIR = "avatars"
    private const val AVATAR_FILE = "local_avatar.jpg"
    private const val AGENT_AVATAR_FILE = "agent_avatar.jpg"

    /** 用户头像文件 */
    fun avatarFile(context: Context): File =
        File(context.getDir(AVATAR_DIR, Context.MODE_PRIVATE), AVATAR_FILE)

    /** Agent 头像文件 */
    fun agentAvatarFile(context: Context): File =
        File(context.getDir(AVATAR_DIR, Context.MODE_PRIVATE), AGENT_AVATAR_FILE)

    /** 是否已设置用户头像 */
    fun hasAvatar(context: Context): Boolean {
        val path = PrefsManager.getInstance(context).localAvatarPath
        return path.isNotEmpty() && File(path).exists()
    }

    /** 加载用户头像 Bitmap,未设置返回 null */
    fun loadAvatar(context: Context): Bitmap? {
        val path = PrefsManager.getInstance(context).localAvatarPath
        if (path.isEmpty() || !File(path).exists()) return null
        return decodeSampledBitmap(path, 200, 200)
    }

    /**
     * 从 URI 保存用户头像(压缩为 JPEG)。
     * @return true 保存成功
     */
    fun saveAvatar(context: Context, uri: Uri): Boolean = saveAvatarToFile(context, uri, avatarFile(context)) { path ->
        PrefsManager.getInstance(context).localAvatarPath = path
    }

    /** 清除用户头像 */
    fun clearAvatar(context: Context) {
        val prefs = PrefsManager.getInstance(context)
        val path = prefs.localAvatarPath
        if (path.isNotEmpty()) {
            try { File(path).delete() } catch (e: Exception) { }
        }
        prefs.localAvatarPath = ""
    }

    // ═══ Agent 头像 ═══

    /** 是否已设置 Agent 头像 */
    fun hasAgentAvatar(context: Context): Boolean {
        val path = PrefsManager.getInstance(context).agentAvatarPath
        return path.isNotEmpty() && File(path).exists()
    }

    /** 加载 Agent 头像 Bitmap,未设置返回 null */
    fun loadAgentAvatar(context: Context): Bitmap? {
        val path = PrefsManager.getInstance(context).agentAvatarPath
        if (path.isEmpty() || !File(path).exists()) return null
        return decodeSampledBitmap(path, 200, 200)
    }

    /** 从 URI 保存 Agent 头像 */
    fun saveAgentAvatar(context: Context, uri: Uri): Boolean = saveAvatarToFile(context, uri, agentAvatarFile(context)) { path ->
        PrefsManager.getInstance(context).agentAvatarPath = path
    }

    /** 清除 Agent 头像 */
    fun clearAgentAvatar(context: Context) {
        val prefs = PrefsManager.getInstance(context)
        val path = prefs.agentAvatarPath
        if (path.isNotEmpty()) {
            try { File(path).delete() } catch (e: Exception) { }
        }
        prefs.agentAvatarPath = ""
    }

    // ═══ 内部 ═══

    private fun saveAvatarToFile(
        context: Context,
        uri: Uri,
        target: File,
        onSaved: (String) -> Unit
    ): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(input) ?: run {
                input.close()
                return false
            }
            input.close()
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            onSaved(target.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 采样解码避免 OOM */
    private fun decodeSampledBitmap(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sampleSize = 1
            while (opts.outWidth / sampleSize > reqW || opts.outHeight / sampleSize > reqH) {
                sampleSize *= 2
            }
            val realOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, realOpts)
        } catch (e: Exception) { null }
    }
}
