package go.examate.hyp.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 更新检查器:通过 GitHub Releases API 检查最新版本
 *
 * 实现说明:
 * - 请求 `https://api.github.com/repos/{owner}/{repo}/releases/latest`
 * - 国内网络:api.github.com 通常可访问,git clone / release 下载可能慢
 * - 下载链接用 ghproxy 加速:将 github.com → ghproxy.com/https://github.com
 *
 * 使用方式:
 * ```
 * val result = UpdateChecker.checkUpdate()
 * when (result) {
 *     is UpdateChecker.Result.NewVersion -> // 有新版本,显示更新弹窗
 *     is UpdateChecker.Result.UpToDate -> // 已是最新
 *     is UpdateChecker.Result.Error -> // 检查失败
 * }
 * ```
 *
 * 配置:发布到 GitHub 后,修改 [OWNER] 和 [REPO] 为实际仓库地址。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** GitHub 仓库所有者 */
    private const val OWNER = "duringk"

    /** GitHub 仓库名 */
    private const val REPO = "Examate"

    /** GitHub API 地址(api.github.com 在国内一般可访问) */
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 检查结果 */
    sealed class Result {
        /** 有新版本 */
        data class NewVersion(
            val latestVersion: String,   // 最新版本号(如 "1.1")
            val currentVersion: String,  // 当前版本号(如 "1.0")
            val releaseNotes: String,    // 更新日志(markdown)
            val downloadUrl: String,     // APK 下载链接(已加速)
            val releasePageUrl: String   // Release 页面链接
        ) : Result()

        /** 已是最新版本 */
        data class UpToDate(val currentVersion: String) : Result()

        /** 检查失败 */
        data class Error(val message: String) : Result()
    }

    /**
     * 检查更新
     * @param currentVersion 当前版本号(从 BuildConfig.VERSION_NAME 获取)
     */
    suspend fun checkUpdate(currentVersion: String): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ExamMate-App")  // GitHub API 要求 User-Agent
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Error("GitHub API 返回 ${resp.code}")
                }

                val body = resp.body?.string()
                    ?: return@withContext Result.Error("响应为空")

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                // tag_name 通常是 "v1.0" 或 "1.0",去掉前缀 v
                val latestVersion = tagName.removePrefix("v").removePrefix("V").trim()
                val releaseNotes = json.optString("body", "暂无更新说明")
                val releasePageUrl = json.optString("html_url", "")

                // 从 assets 中找 APK 下载链接
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                // 如果没有 apk asset,用 release 页面让用户手动下载
                val downloadUrl = if (apkUrl.isNotEmpty()) {
                    accelerateUrl(apkUrl)
                } else {
                    releasePageUrl
                }

                if (latestVersion.isEmpty()) {
                    return@withContext Result.Error("无法解析版本号")
                }

                // 版本比较
                if (isNewer(latestVersion, currentVersion)) {
                    Result.NewVersion(latestVersion, currentVersion, releaseNotes, downloadUrl, releasePageUrl)
                } else {
                    Result.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            Result.Error("网络异常: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 加速 GitHub 下载链接(国内 ghproxy 镜像)
     * github.com/xxx → ghproxy.com/https://github.com/xxx
     */
    private fun accelerateUrl(url: String): String {
        return if (url.contains("github.com")) {
            "https://ghproxy.com/$url"
        } else {
            url
        }
    }

    /**
     * 版本号比较:判断 latest 是否比 current 新
     * 支持 "1.0" / "1.0.1" / "1.2.3" 格式
     */
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
        return false  // 相等
    }
}
