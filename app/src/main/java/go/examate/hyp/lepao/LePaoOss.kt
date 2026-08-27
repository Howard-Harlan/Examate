package go.examate.hyp.lepao

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OSS 上传（阿里云直传，无callback）
 * 从 LePaoTrack.kt 拆出，职责单一。
 */
object LePaoOss {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun upload(
        stsData: JSONObject,
        ossKey: String,
        content: ByteArray,
        contentType: String
    ): Boolean {
        try {
            val bucket = stsData.optString("bucket", "lptiyu-data")
            val akId = stsData.getString("AccessKeyId")
            val akSecret = stsData.getString("AccessKeySecret")
            val token = stsData.getString("SecurityToken")

            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                .format(java.util.Date())
            val contentTypeNormalized = when {
                contentType.contains("png") -> "image/png"
                contentType.contains("jpeg") || contentType.contains("jpg") -> "image/jpeg"
                else -> "text/plain"
            }
            val stringToSign = "PUT\n\n$contentTypeNormalized\n$date\nx-oss-security-token:$token\n/$bucket/$ossKey"

            val mac = javax.crypto.Mac.getInstance("HmacSHA1")
            mac.init(javax.crypto.spec.SecretKeySpec(akSecret.toByteArray(), "HmacSHA1"))
            val signature = android.util.Base64.encodeToString(
                mac.doFinal(stringToSign.toByteArray()), android.util.Base64.NO_WRAP
            )

            val url = "https://$bucket.oss-cn-hangzhou.aliyuncs.com/$ossKey"
            val req = okhttp3.Request.Builder()
                .url(url)
                .put(content.toRequestBody(contentTypeNormalized.toMediaTypeOrNull()))
                .header("Date", date)
                .header("Authorization", "OSS $akId:$signature")
                .header("x-oss-security-token", token)
                .build()

            val resp = client.newCall(req).execute()
            val code = resp.code
            resp.body?.bytes()
            return code == 200
        } catch (e: Exception) {
            Log.e("LePaoOss", "upload error", e)
            return false
        }
    }
}
