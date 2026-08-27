package go.examate.hyp.lepao

import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 步道乐跑 API 客户端
 * 端点分类:
 * - PROTECTED (w7): sign用SALT_P前缀, 排除mobileDeviceId
 * - ENCRYPTED_BODY: 请求体AES加密为 key=<base64>
 */
class LePaoApi(
    var uid: String = "",
    var token: String = "",
    var schoolId: String = LePaoPrefs.DEFAULT_SCHOOL_ID,
    var studentNum: String = "",
    var deviceId: String = "ffffffff-84c0-0f0d-ffff-ffffef05ac4a"
) {
    companion object {
        private const val TAG = "LePaoApi"
        const val BASE = "https://api2.lptiyu.com/v3/api.php"
        const val UA = "Dalvik/2.1.0 (Linux; U; Android 12; 24031PN0DC Build/5a6ecfb.0)"

        /** 获取设备本地IP地址 */
        fun getLocalIpAddress(): String {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    // 优先使用wlan0(WiFi)
                    if (intf.name.contains("wlan") || intf.name.contains("eth")) {
                        val addrs = intf.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                return addr.hostAddress ?: continue
                            }
                        }
                    }
                }
                // 备选：任意非回环IPv4
                val interfaces2 = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces2.hasMoreElements()) {
                    val addrs = interfaces2.nextElement().inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            } catch (_: Exception) { }
            return "192.168.1.100" // 兜底值（看起来像真实内网IP）
        }

        /** 获取随机OSS分片号(1~999) */
        fun randomOssShard(): Int = (1..999).random()

        /** 共享 OkHttpClient（连接池复用） */
        private val SHARED_CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "okhttp/3.12")
                    .build()
                chain.proceed(req)
            }
            .build()

        val PROTECTED = setOf(
            "Login/quickLoginV339", "Login/RefreshToken",
            "Run2/beforeRunV260", "Run/stopRunV278", "Run2/gyroscope",
            "Run/setRunLocationRecord", "Report/getOssSign", "report/getOssSts"
        )
        val ENCRYPTED_BODY = setOf(
            "login/RegisterV14", "login/LoginV14", "Login/quickLoginV339",
            "Login/RefreshToken",
            "Run2/simVerify", "Run2/beforeRunV260",
            "Report/getOssSign", "report/getOssSts"
        )
        /** stopFreeRunV220 用 Wet2 密钥加密 */
        val WET2_BODY = setOf("Run/stopFreeRunV220")
    }

    private val client = SHARED_CLIENT

    /** 通用请求方法，自动处理sign和加密 */
    fun call(endpoint: String, extra: Map<String, Any?> = emptyMap(), fixedNonce: Int? = null): JSONObject {
        val nonce = fixedNonce ?: randomNonce()
        val params = mutableMapOf<String, Any?>(
            "version" to "226",
            "version_name" to "4.2.6",
            "mobileModel" to "24031PN0DC",
            "mobileDeviceId" to deviceId,
            "mobileOsVersion" to "12",
            "ostype" to "1",
            "school_id" to schoolId,
            "uid" to uid.ifEmpty { "1" },
            "token" to token,
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "nonce" to nonce.toString()
        )
        params.putAll(extra)
        // 只移除null值（保留空字符串，app也发送空字符串字段）
        val cleaned = params.filter { it.value != null }
        params.clear()
        params.putAll(cleaned)

        // 登录端点需要排除token字段
        if (endpoint == "Login/quickLoginV339") {
            params.remove("token")
        }

        val isProtected = endpoint in PROTECTED
        val isLogin = endpoint == "Login/quickLoginV339"
        android.util.Log.d("LePaoApi", "$endpoint params: $params")
        params["sign"] = LePaoCrypto.calcSign(params, isProtected, deviceId, isLogin)

        val bodyStr: String? = when {
            endpoint in WET2_BODY -> {
                val json = sortedJson(params)
                "key=" + java.net.URLEncoder.encode(
                    LePaoCrypto.aesEncrypt(json.toByteArray(), LePaoCrypto.KEY_TRK, LePaoCrypto.IV_TRK),
                    "UTF-8"
                )
            }
            endpoint in ENCRYPTED_BODY -> {
                val json = sortedJson(params)
                "key=" + java.net.URLEncoder.encode(
                    LePaoCrypto.aesEncrypt(json.toByteArray(), LePaoCrypto.KEY_NG, LePaoCrypto.IV_NG),
                    "UTF-8"
                )
            }
            else -> null
        }

        val request: Request = if (bodyStr != null) {
            Request.Builder()
                .url("$BASE/$endpoint")
                .post(
                    bodyStr.toRequestBody(
                        "application/x-www-form-urlencoded;charset=UTF-8".toMediaTypeOrNull()
                    )
                )
                .build()
        } else {
            val form = FormBody.Builder()
            params.forEach { (k, v) -> form.add(k, v.toString()) }
            Request.Builder()
                .url("$BASE/$endpoint")
                .post(form.build())
                .build()
        }

        android.util.Log.d("LePaoApi", "$endpoint body: ${(bodyStr ?: "form").take(100)}")
        val response = client.newCall(request).execute()
        val bodyText = response.body?.string() ?: "{}"
        Log.d(TAG, "$endpoint → ${response.code} ${bodyText.take(200)}")

        val result = JSONObject(bodyText)
        return decryptResponse(result)
    }

    /** 构建排序后的紧凑 JSON（匹配 app 格式） */
    private fun sortedJson(params: Map<String, Any?>): String {
        val obj = JSONObject()
        params.keys.sorted().forEach { k ->
            val v = params[k]
            when (v) {
                is Int -> obj.put(k, v)
                is Long -> obj.put(k, v)
                is Boolean -> obj.put(k, v)
                is JSONArray -> obj.put(k, v)
                else -> obj.put(k, v.toString())
            }
        }
        return obj.toString()
    }

    private fun randomNonce(): String {
        return (SecureRandom().nextInt(900000) + 100000).toString()
    }

    /** 自动解密 is_encrypt=1 的响应（尝试 TRK 与 NG 两套密钥），解密失败时标记错误而非保留密文 */
    private fun decryptResponse(result: JSONObject): JSONObject {
        if (result.optInt("is_encrypt") == 1 && result.has("data")) {
            val encData = result.getString("data")
            var decrypted = false
            for ((k, iv) in listOf(
                LePaoCrypto.KEY_TRK to LePaoCrypto.IV_TRK,
                LePaoCrypto.KEY_NG to LePaoCrypto.IV_NG
            )) {
                try {
                    val dec = LePaoCrypto.aesDecrypt(encData, k, iv)
                    result.put("data", JSONObject(String(dec)))
                    result.remove("is_encrypt")
                    decrypted = true
                    break
                } catch (_: Exception) { }
            }
            if (!decrypted) {
                result.put("status", 0)
                result.put("info", "响应解密失败")
                result.remove("is_encrypt")
            }
        }
        return result
    }

    /** 构建步频信息 JSON — 每60秒步数带自然波动 */
    private fun buildStepInfo(durationS: Int, stepNum: Int): String {
        val minutes = maxOf(durationS / 60, 1)
        val base = stepNum.toDouble() / minutes
        val rng = java.util.Random()
        val list = mutableListOf<Int>()
        var remaining = stepNum
        for (i in 0 until minutes) {
            if (i == minutes - 1) {
                list.add(remaining.coerceAtLeast(0))
            } else {
                val jitter = (rng.nextGaussian() * base * 0.1).toInt()
                val steps = (base + jitter).toInt().coerceIn(1, (base * 1.3).toInt())
                list.add(steps)
                remaining -= steps
            }
        }
        return org.json.JSONObject()
            .put("interval", 60)
            .put("list", JSONArray(list))
            .toString()
    }

    /** 构建 run_check_res — 格式: "runStatus-gpsSpeed"，runStatus=1表示正常跑步 */
    private fun buildRunCheckRes(targetSpeed: Float, durationS: Int): String {
        val minutes = maxOf(durationS / 60, 1)
        val numChecks = minOf(minutes, 5)
        val speedPairs = mutableListOf<String>()
        for (i in 0 until numChecks) {
            val gpsSpeed = targetSpeed + (Random.nextFloat() - 0.5f) * 0.4f
            // 格式: runStatus-gpsSpeed，1=正常跑步
            speedPairs.add("1-%.1f".format(gpsSpeed))
        }
        return JSONArray().put(JSONObject().apply {
            put("uid", uid)
            put("plan", 1)
            put("inteval_result", List(numChecks) { "0" }.joinToString(","))
            put("inteval_value", speedPairs.joinToString(","))
            put("is_stop", 0)
            put("is_cheat", 0)
        }).toString()
    }

    // ===== 高层API =====

    /**
     * 快速登录(QQ扫码 / 微信) — 统一封装 openid/accesstoken 内层加密与 lp47 反作弊参数。
     * @param type "2"=QQ, "3"=微信
     * @return API 响应(status=1 时 data 含 uid/access_token)
     */
    fun quickLogin(openid: String, accessToken: String, type: String): JSONObject {
        val nonce = (100000..999999).random()
        val encOpenid = LePaoCrypto.innerEncrypt(openid, nonce)
        val encToken = LePaoCrypto.innerEncrypt(accessToken, nonce)
        val (lpName, lpValue) = LePaoCrypto.genLpParam(nonce)
        return call("Login/quickLoginV339", mapOf(
            "openid" to encOpenid,
            "accesstoken" to encToken,
            "type" to type,
            "uid" to "1", "school_id" to "0",
            "nick_name" to "", "avatar_url" to "",
            lpName to lpValue
        ), fixedNonce = nonce)
    }


    fun beforeRun(runType: String = "1"): JSONObject =
        call("Run2/beforeRunV260", mapOf("type" to runType))

    fun uploadLocation(lat: Double, lng: Double, gameId: String = "691"): JSONObject =
        call("Run/setRunLocationRecord", mapOf(
            "latitude" to "%.5f".format(lat),
            "longitude" to "%.5f".format(lng),
            "game_id" to gameId,
            "point_ids" to ""
        ))

    fun getOssSts(): JSONObject = call("report/getOssSts")

    fun getUserInfo(): JSONObject = call("User/User")

    fun recordDetail(recordId: String): JSONObject =
        call("Run/recordDetailV270", mapOf("record_id" to recordId))

    fun allRecords(year: String, page: Int = 1): JSONObject =
        call("Run2/allRecordList", mapOf("year" to year, "page" to page.toString()))

    /** 循环翻页获取全部记录（每页约30~40条） */
    fun fetchAllRecords(year: String, maxPages: Int = 10): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        for (page in 1..maxPages) {
            val resp = allRecords(year, page)
            if (resp.optInt("status") != 1) break
            val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) all.add(arr.getJSONObject(i))
        }
        return all
    }

    fun getTermScore(termId: String): JSONObject =
        call("RunStat/getMyTermScore", mapOf("term_id" to termId))

    fun getAllScores(): JSONObject =
        call("RunStat/getAllScore")

    /**
     * 提交自由跑记录
     */
    fun stopFreeRun(
        distanceM: Float,
        durationS: Int,
        stepNum: Int,
        startTs: Long,
        endTs: Long,
        recordFile: String,
        fileImg: String,
        timeStr: String = "",
        targetSpeed: Float = 3.0f
    ): JSONObject {
        val uidInt = uid.toLongOrNull() ?: 1L
        val schoolInt = schoolId.toIntOrNull() ?: LePaoPrefs.DEFAULT_SCHOOL_ID.toInt()

        val stepInfoJson = buildStepInfo(durationS, stepNum)
        val runCheckRes = buildRunCheckRes(targetSpeed, durationS)

        return call("Run/stopFreeRunV220", mapOf(
            "distance" to "%.2f".format(distanceM / 1000f),
            "start_time" to startTs.toString(),
            "end_time" to endTs.toString(),
            "used_time" to durationS.toString(),
            "step_num" to stepNum.toString(),
            "student_num" to studentNum,
            "step_info" to stepInfoJson,
            "record_file" to recordFile,
            "file_img" to fileImg.ifEmpty { "1" },
            "record_img" to "",
            "newMobileDeviceId" to "",
            "record_str" to LePaoCrypto.recordStr(schoolInt, uidInt, timeStr),
            "run_check_res" to runCheckRes,
            "gee_token" to "",
            "ip" to getLocalIpAddress(),
            "pass_total" to "0",
            "pass_effective_num" to "0"
        ))
    }

    /** 获取服务端时间戳(timeStr用于record_str和lp参数) */
    fun getTimestamp(gameId: String = "691"): JSONObject =
        call("Run/getTimestampV278", mapOf("game_id" to gameId))

    /** 注册分配的打卡点 */
    fun registerCheckpoints(pointIds: List<Long>): JSONObject =
        call("Run2/getStudentVenaPoint", mapOf(
            "point_info" to org.json.JSONArray(pointIds).toString()
        ))

    /**
     * 校园跑提交 (Run/stopRunV278)
     * 加密用goME, sign用SALT_P前缀排除DID
     */
    fun stopCampusRun(
        distanceM: Float,
        durationS: Int,
        stepNum: Int,
        startTs: Long,
        endTs: Long,
        recordFile: String,
        fileImg: String,
        timeStr: String,
        logData: String,
        termId: String = LePaoPrefs.DEFAULT_TERM_ID,
        gameId: String = "691",
        passTotal: Int,
        passEffectiveNum: Int,
        targetSpeed: Float = 3.0f
    ): JSONObject {
        val uidInt = uid.toLongOrNull() ?: 1L
        val schoolInt = schoolId.toIntOrNull() ?: LePaoPrefs.DEFAULT_SCHOOL_ID.toInt()

        val stepInfoJson = buildStepInfo(durationS, stepNum)
        val runCheckRes = buildRunCheckRes(targetSpeed, durationS)

        return call("Run/stopRunV278", mapOf(
            // 身份
            "school_id" to schoolId,
            "term_id" to termId,
            "game_id" to gameId,
            // 跑步数据
            "distance" to "%.2f".format(distanceM / 1000f),
            "start_time" to startTs.toString(),
            "end_time" to endTs.toString(),
            "used_time" to durationS.toString(),
            "step_num" to stepNum.toString(),
            "student_num" to studentNum,
            "step_info" to stepInfoJson,
            // 打卡数据
            "log_data" to logData,
            "pass_total" to passTotal.toString(),
            "pass_effective_num" to passEffectiveNum.toString(),
            "is_running_area_valid" to "1",
            // 文件
            "record_file" to recordFile,
            "file_img" to fileImg.ifEmpty { "1" },
            "record_img" to "",
            // 反作弊
            "record_str" to LePaoCrypto.recordStr(schoolInt, uidInt, timeStr),
            "run_check_res" to runCheckRes,
            "newMobileDeviceId" to "",
            "gee_token" to "",
            "ip" to getLocalIpAddress()
        ))
    }

    /** 上报传感器文件关联 (Run2/gyroscope) — stopRun成功后调用 */
    fun uploadGyroscopeRef(recordId: String, gyroscopeFileOssKey: String): JSONObject =
        call("Run2/gyroscope", mapOf(
            "record_id" to recordId,
            "gyroscope_file" to gyroscopeFileOssKey
        ))

    /**
     * 人脸验证 (Run2/faceVerify)
     * 上传人脸照片到OSS后调用此接口获取 run_face_verify_id
     * @param faceFileOssKey 人脸照片的OSS路径
     * @param type 验证阶段: 1=开始, 2=中途
     * @param priorFaceVerifyId 之前验证返回的ID(用于关联中途→结束)
     */
    fun faceVerify(faceFileOssKey: String, verifyType: Int, priorFaceVerifyId: String? = null): JSONObject {
        val extra = mutableMapOf<String, Any?>(
            "face_file" to faceFileOssKey,
            "type" to verifyType.toString()
        )
        if (!priorFaceVerifyId.isNullOrEmpty()) {
            extra["run_face_verify_id"] = priorFaceVerifyId
        }
        return call("Run2/faceVerify", extra)
    }

    /**
     * 获取极验信息 (Run/getGeeInfo)
     * 在跑步过程中随机触发时调用
     */
    fun getGeeInfo(geeToken: String, startTime: Long): JSONObject =
        call("Run/getGeeInfo", mapOf(
            "gee_token" to geeToken,
            "ip" to getLocalIpAddress(),
            "start_time" to startTime.toString()
        ))

    /**
     * 设备检查 (Run2/checkUserDeviceV278)
     */
    fun checkUserDevice(): JSONObject =
        call("Run2/checkUserDeviceV278")

    /**
     * SIM卡验证 (Run2/simVerify)
     */
    fun simVerify(): JSONObject =
        call("Run2/simVerify")

    /**
     * 获取拍照检查状态 (Run2/getCaptureStatus)
     */
    fun getCaptureStatus(startTime: Long, distance: Float, gameId: String): JSONObject =
        call("Run2/getCaptureStatus", mapOf(
            "start_time" to startTime.toString(),
            "distance" to "%.2f".format(distance / 1000f),
            "game_id" to gameId
        ))

    /**
     * 深度感知风控查询 (Run/getDeepknowInfo)
     */
    fun getDeepknowInfo(sessionId: String): JSONObject =
        call("Run/getDeepknowInfo", mapOf("session_id" to sessionId))

    /**
     * Token续期 (Login/RefreshToken)
     */

    fun refreshToken(refreshToken: String): JSONObject =
        call("Login/RefreshToken", mapOf(
            "access_token" to token,
            "refresh_token" to refreshToken
        ))

    /** 服务端登出 (Login/Logout) */
    fun logout(): JSONObject =
        call("Login/Logout", mapOf("access_token" to token))
}









