package go.examate.hyp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 更新检查器:双源 fallback 策略检查最新版本。
 *
 * - 主源 Gitee(国内快): https://gitee.com/api/v5/repos/danfuu/Examate/releases/latest
 * - 备源 GitHub:        https://api.github.com/repos/duringk/Examate/releases/latest
 *
 * Gitee 无 release 或请求失败时自动回退到 GitHub,两个源都失败才报错。
 * tag_name 支持任意前缀(如 "版本更新v1.1"),用正则提取版本号。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val GITEE_API = "https://gitee.com/api/v5/repos/danfuu/Examate/releases/latest"
    private const val GITHUB_API = "https://api.github.com/repos/duringk/Examate/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        data class NewVersion(
            val latestVersion: String,
            val currentVersion: String,
            val releaseNotes: String,
            val downloadUrl: String,
            val releasePageUrl: String
        ) : Result()

        data class UpToDate(val currentVersion: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun checkUpdate(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val github = fetchFromSource(GITHUB_API, isGitHub = true, currentVersion)
        if (github !is Result.Error) return@withContext github

        val gitee = fetchFromSource(GITEE_API, isGitHub = false, currentVersion)
        if (gitee !is Result.Error) return@withContext gitee

        Result.Error("GitHub: ${github.message}; Gitee: ${gitee.message}")
    }

    private fun fetchFromSource(apiUrl: String, isGitHub: Boolean, currentVersion: String): Result {
        try {
            val builder = Request.Builder().url(apiUrl)
            if (isGitHub) {
                builder.header("Accept", "application/vnd.github+json")
                builder.header("User-Agent", "Examate-App")
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return Result.Error("API 返回 ${resp.code}")
                val body = resp.body?.string() ?: return Result.Error("响应为空")
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "")
                val latestVersion = Regex("\\d+(\\.\\d+)+").find(tagName)?.value
                    ?: tagName.removePrefix("v").removePrefix("V").trim()
                if (latestVersion.isEmpty()) return Result.Error("无法解析版本号")

                val releaseNotes = json.optString("body", "暂无更新说明")
                val releasePageUrl = json.optString("html_url", "")

                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name", "").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                val downloadUrl = if (apkUrl.isNotEmpty()) apkUrl else releasePageUrl

                return if (isNewer(latestVersion, currentVersion)) {
                    Result.NewVersion(latestVersion, currentVersion, releaseNotes, downloadUrl, releasePageUrl)
                } else {
                    Result.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromSource($apiUrl) failed", e)
            return Result.Error("网络异常: ${e.message ?: "未知错误"}")
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    // ════════════════════════════════════════
    //  静默检查(当天首次进入应用时后台触发)
    // ════════════════════════════════════════

    private const val CHANNEL_ID = "update_check"
    private const val NOTIFICATION_ID = 1001

    /**
     * 当天首次调用时静默检查更新,有新版本则发通知。
     * 同一天只检查一次(用 SharedPreferences 记录日期)。
     */
    fun silentCheckIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("update_check", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("last_check_date", "") == today) return
        prefs.edit().putString("last_check_date", today).apply()

        val currentVersion = go.examate.hyp.BuildConfig.VERSION_NAME
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = checkUpdate(currentVersion)) {
                is Result.NewVersion -> showUpdateNotification(context, result)
                else -> {}
            }
        }
    }

    private fun showUpdateNotification(context: Context, result: Result.NewVersion) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "更新提醒", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val intent = Intent(context, go.examate.hyp.AboutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("发现新版本 ${result.latestVersion}")
            .setContentText(result.releaseNotes.take(60))
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.releaseNotes))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
