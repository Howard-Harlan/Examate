package go.examate.hyp.watercard

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 乐校通 V4 API 客户端
 *
 * dex 反编译确认（classes3.dex V4BusinessHttpProtocol 注解解析 + URL 分析）：
 *
 * **两个 Base URL**（dex 确认 Account.getV4HttpBusinessStore(url) 逻辑）：
 * - 学校搜索: http://ai.lxt6.cn/school（V4GetHttpUrlUtil.getV4PlatformServerUrlForSchool）
 * - 其他接口: https://ai.lxt6.cn/api（Account 中硬编码，V4HttpConfig.V4_RELEASE_SERVER）
 *
 * - 签名: MD5(token + timestamp + "A70893")（V4HeaderUtil.getSign）
 * - timestamp 为毫秒时间戳（V4HeaderUtil.getHeaderTimestamp = System.currentTimeMillis()）
 *
 * **关键：timestamp/token/sign 通过 HTTP Header 传递（@Header 注解），不是 URL 查询参数！**
 * - Header 名为小写: "timestamp"、"token"、"sign"
 * - 未登录时 token 传空字符串 ""
 *
 * V4BusinessHttpProtocol 接口方法（DEX annotation_directory_item 手动解析确认）：
 * - getSchoolPlatform → GET {SCHOOL_URL}/school/getByKeyword
 * - getLoginCodeImage → GET {API_URL}/common/user/getLoginCodeImage
 * - postLogin → POST {API_URL}/common/user/customerPersonLogin
 * - getUserInfo → GET {API_URL}/cus/customerPerson/getUserInfo
 * - getUserBalance → GET {API_URL}/cus/projectBalance/getBalanceDetail
 * - getRechargeRecord → GET {API_URL}/cus/recharge/pageQuery
 * - getConsumeRecord → GET {API_URL}/cus/deviceConsume/pageQuery
 * - postUserRecharge → POST {API_URL}/cus/recharge/userRecharge
 *
 * 所有方法为同步函数，调用方需在 Dispatchers.IO 中执行。
 */
class YxtApi {

    companion object {
        // V4 Base URL（dex 确认）
        private const val SCHOOL_URL = "https://ai.lxt6.cn/school"
        private const val API_URL = "https://ai.lxt6.cn/api"
        private val JSON_MEDIA = "application/json".toMediaType()

        // V3 Base URL（用户确认：统一用 v3-app.lxt6.cn）
        private const val V3_LOGIN_URL = "https://v3-app.lxt6.cn"

        // 抓包确认的 Header 值（官方 App 4.3.9 版本）
        private const val X_SID = "2021011300001"
        private const val X_PRODUCT_VER = "4.3.9"
        private const val USER_AGENT = "okhttp/2.7.5"
        // V4 X-clientType = "1"
        // V3 X-clientType = "1"（用户确认，不是 202206280001）
        private const val X_CLIENT_TYPE_V4 = "1"
        private const val X_CLIENT_TYPE_V3 = "1"
    }

    // Cookie 存储：手动解析 Set-Cookie（抓包确认官方 App 不使用 CookieJar）
    // getCode 和 login 的 Cookie 均为空，验证码通过 account 参数关联
    // tokenInfo 从登录响应 Set-Cookie 获取，业务接口手动设置 Cookie: tokenInfo=xxx
    private val cookieStore = mutableMapOf<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 关闭重定向：V3 接口 tokenInfo 失效时会 302 重定向到登录页，需要原样返回错误而不是跟随
        .followRedirects(false)
        .followSslRedirects(false)
        // 抓包确认官方 App 用 HTTP/1.1（okhttp/2.7.5），强制 HTTP/1.1 避免服务端网关拒绝
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    var session: YxtSession? = null

    // ════════════════════════════════════════════════
    //  通用请求
    // ════════════════════════════════════════════════

    /**
     * 构建带 V4 签名的请求
     *
     * 签名参数通过 **HTTP Header** 传递（dex 注解确认 @Header）：
     *   timestamp: 毫秒时间戳
     *   token:     用户 token（未登录传空字符串）
     *   sign:      MD5(token + timestamp + "A70893")
     *
     * Content-Type:
     *   GET  → application/x-www-form-urlencoded
     *   POST → application/json
     *
     * 另需 V3 HttpProtocolFactory$MyRequestInterceptor 添加的 9 个必需 Header
     * （dex 反编译 _decompiled2.txt line 77390-77503 确认，V4 复用 V3 工厂）：
     *   Cookie / X-Sid / X-Product-Ver / X-Mobile-Info / X-clientType
     *   X-B3-TraceId / X-B3-SpanId / X-Ghost / X-route
     */
    private fun buildRequest(
        url: String,
        method: String = "GET",
        body: String? = null,
        requireAuth: Boolean = false
    ): Request {
        val timestamp = YxtSigner.timestamp()
        val token = if (requireAuth) (session?.token ?: "") else ""
        val sign = YxtSigner.sign(token, timestamp)

        val builder = Request.Builder()
            .url(url)
            // V4 签名 Header（dex @Header 注解确认）
            .header("timestamp", timestamp)
            .header("token", token)
            .header("sign", sign)
            // Content-Type
            .header("Content-Type", if (method == "POST") "application/json" else "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            // Cookie: 由 CookieJar 自动管理 + 登录后额外传 token cookie
            .apply {
                if (requireAuth && token.isNotBlank()) {
                    header("Cookie", "token=$token")
                }
            }
            .header("X-Sid", X_SID)
            .header("X-Product-Ver", X_PRODUCT_VER)
            .header("X-Mobile-Info", "Android#?${Build.MODEL}#?${Build.VERSION.RELEASE}")
            .header("X-clientType", X_CLIENT_TYPE_V4)
            .header("X-B3-TraceId", YxtSigner.randomHex16())
            .header("X-B3-SpanId", YxtSigner.randomHex16())
            .header("X-Ghost", YxtSigner.computeGhost())
            .header("X-route", "")

        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                if (body != null) {
                    builder.post(body.toRequestBody(JSON_MEDIA))
                } else {
                    builder.post("".toRequestBody(JSON_MEDIA))
                }
            }
        }
        return builder.build()
    }

    /** 构建 FormUrlEncoded POST 请求（用于 @FormUrlEncoded 接口如 sendSmscode） */
    private fun buildFormRequest(url: String, formBody: String): Request {
        val timestamp = YxtSigner.timestamp()
        val token = ""
        val sign = YxtSigner.sign(token, timestamp)
        val formMedia = "application/x-www-form-urlencoded".toMediaType()
        return Request.Builder()
            .url(url)
            .header("timestamp", timestamp)
            .header("token", token)
            .header("sign", sign)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .header("X-Sid", X_SID)
            .header("X-Product-Ver", X_PRODUCT_VER)
            .header("X-Mobile-Info", "Android#?${Build.MODEL}#?${Build.VERSION.RELEASE}")
            .header("X-clientType", X_CLIENT_TYPE_V4)
            .header("X-B3-TraceId", YxtSigner.randomHex16())
            .header("X-B3-SpanId", YxtSigner.randomHex16())
            .header("X-Ghost", YxtSigner.computeGhost())
            .header("X-route", "")
            .post(formBody.toRequestBody(formMedia))
            .build()
    }

    private fun execute(req: Request): JSONObject {
        // 输出完整请求日志（调试登录问题）
        android.util.Log.d("YxtApi", "=== 请求 ===")
        android.util.Log.d("YxtApi", "URL: ${req.url}")
        android.util.Log.d("YxtApi", "Method: ${req.method}")
        req.headers.forEach { (k, v) ->
            android.util.Log.d("YxtApi", "Header: $k=$v")
        }
        android.util.Log.d("YxtApi", "CookieStore: $cookieStore")

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            android.util.Log.d("YxtApi", "=== 响应 ===")
            android.util.Log.d("YxtApi", "HTTP ${resp.code} ${resp.message}")
            android.util.Log.d("YxtApi", "Body: ${text.take(500)}")
            // 302 重定向：通常是 tokenInfo 失效，V3 服务端重定向到登录页
            if (resp.code == 302 || resp.code == 301) {
                val location = resp.header("Location") ?: ""
                throw YxtException("登录已过期(302)，请重新登录。Location=$location")
            }
            // 手动解析 Set-Cookie（不依赖 CookieJar）
            resp.headers("Set-Cookie").forEach { setCookie ->
                android.util.Log.d("YxtApi", "Set-Cookie: $setCookie")
                val name = setCookie.substringBefore("=", "")
                val value = setCookie.substringAfter("=", "").substringBefore(";")
                if (name.isNotEmpty()) cookieStore[name] = value
            }

            val json = try { JSONObject(text) } catch (_: Exception) {
                throw YxtException("响应解析失败[HTTP ${resp.code}]: ${text.take(300)}")
            }
            // V4 响应: { "code": 0, "data": ..., "message": "..." }（小写）
            // V3 响应: { "Code": 0, "Data": ..., "Message": "..." }（大写首字母）
            val code = json.optInt("code", json.optInt("Code", Int.MIN_VALUE))
            if (code != 0) {
                val msg = json.optString("message", json.optString("Message", json.optString("msg", "未知错误")))
                throw YxtException(if (code == Int.MIN_VALUE) "$msg (响应: ${text.take(200)})" else "[$code] $msg")
            }
            return json
        }
    }

    /** 获取 data 字段（兼容 V4 小写 "data" 和 V3 大写 "Data"） */
    private fun JSONObject.data(): JSONObject =
        optJSONObject("data") ?: optJSONObject("Data") ?: JSONObject()

    // ════════════════════════════════════════════════
    //  登录流程
    // ════════════════════════════════════════════════

    /** 搜索学校：GET {SCHOOL_URL}/school/getByKeyword?keyword=xxx
     *  dex 确认：V4BaseBean<List<V4PlatformInfoBean>>，data 字段直接是 JSON 数组 */
    fun searchSchool(keyword: String): List<YxtSchool> {
        val url = "$SCHOOL_URL/school/getByKeyword?keyword=" +
            URLEncoder.encode(keyword, "UTF-8")
        val json = execute(buildRequest(url, "GET"))
        android.util.Log.d("YxtApi", "searchSchool response: $json")
        // dex 确认 data 直接是数组（V4BaseBean<List<V4PlatformInfoBean>>），不是嵌套对象
        val arr: JSONArray = json.optJSONArray("data")
            ?: runCatching { JSONArray(json.opt("data")?.toString() ?: "") }.getOrNull()
            ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("schoolName").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("schoolAlias").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            YxtSchool(
                schoolName = name,
                schoolAlias = o.optString("schoolAlias").takeIf { it != "null" } ?: "",
                sysVersion = o.optInt("sysVersion"),
                schoolId = o.optString("schoolId").takeIf { it.isNotBlank() && it != "null" }
                    ?: o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: ""
            )
        }
    }

    /** 获取图形验证码：GET {API_URL}/common/user/getLoginCodeImage */
    fun getCaptcha(): YxtCaptcha {
        val json = execute(buildRequest("$API_URL/common/user/getLoginCodeImage", "GET"))
        val data = json.data()
        // V4ImageCodeBean: captcha (base64), captchaKey
        return YxtCaptcha(
            captchaBase64 = data.optString("captcha"),
            captchaKey = data.optString("captchaKey")
        )
    }

    // ════════════════════════════════════════════════
    //  V3 登录流程（抓包确认：topservice.lxt6.cn + 图片验证码 + Base64 加密）
    // ════════════════════════════════════════════════

    /** V3 业务 Base URL（登录后从 Data.url 获取，默认 v3-app.lxt6.cn） */
    private var _v3BaseUrl = "https://v3-app.lxt6.cn"
    val v3BaseUrl: String get() = _v3BaseUrl

    /**
     * V3 获取图片验证码：GET {v3-app}/user/authentication/getCode?account=xxx
     *
     * 返回: {"Code":0,"Data":"data:image/png;base64,<PNG base64>"}（5位数字图片验证码）
     * X-Sign = MD5("1FF75E512" + "account=xxx")
     */
    fun getV3Captcha(account: String): YxtCaptcha {
        val sign = YxtSigner.signV3("account=$account")
        val encodedAccount = URLEncoder.encode(account, "UTF-8")
        val url = "$V3_LOGIN_URL/user/authentication/getCode?account=$encodedAccount"
        android.util.Log.d("YxtApi", "=== V3 验证码请求 ===")
        android.util.Log.d("YxtApi", "URL: $url")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")
        val json = execute(buildV3Request(url, "GET", sign = sign))
        val dataStr = json.optString("Data", json.optString("data", ""))
        return YxtCaptcha(captchaBase64 = dataStr, captchaKey = "")
    }

    /**
     * V3 加密登录：POST {v3-app}/user/login/userLoginV2WithEncrypt
     *
     * - Body: {"data":"<base64>"}（base64 中的 = 被 Gson 转义为 \u003d）
     * - base64 = Base64(JSON({"code":"xxx","loginPassword":"xxx","studentMobile":"xxx"}))
     * - 字段顺序: code, loginPassword, studentMobile（字母序，Gson 默认）
     * - X-Sign = MD5("1FF75E512" + body)（body 含 \u003d 转义）
     * - 返回: {Data:{studentId, url, state}, Set-Cookie: tokenInfo=xxx#?yyy}
     */
    fun loginV3(account: String, password: String, code: String): YxtLoginResult {
        // 1. 构建 UserLoginBean（字段顺序: code, loginPassword, studentMobile）
        val userLoginBean = JSONObject().apply {
            put("code", code)
            put("loginPassword", password)
            put("studentMobile", account)
        }.toString()

        // 2. Base64 编码
        val encryptedData = YxtSigner.encodeBase64(userLoginBean)

        // 3. 构建 body（Gson HTML 转义：= → \u003d）
        val escapedData = YxtSigner.gsonEscape(encryptedData)
        val aesDataJson = """{"data":"$escapedData"}"""

        // 4. 签名：sign = MD5("1FF75E512" + aesDataJson)
        val sign = YxtSigner.signV3(aesDataJson)

        // 5. POST
        val url = "$V3_LOGIN_URL/user/login/userLoginV2WithEncrypt"

        android.util.Log.d("YxtApi", "=== V3 登录请求 ===")
        android.util.Log.d("YxtApi", "URL: $url")
        android.util.Log.d("YxtApi", "UserLoginBean: $userLoginBean")
        android.util.Log.d("YxtApi", "encryptedData: $encryptedData")
        android.util.Log.d("YxtApi", "aesDataJson(Gson escaped): $aesDataJson")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")

        val json = execute(buildV3Request(url, "POST", aesDataJson, sign))
        val data = json.data()
        // LoginSeccessBean: {state, studentId, url}
        val serverUrl = data.optString("url").takeIf { it.isNotBlank() && it != "null" }
        if (!serverUrl.isNullOrEmpty()) {
            _v3BaseUrl = serverUrl
        }
        val studentId = data.optString("studentId")
        // 从 Set-Cookie 提取 tokenInfo（Set-Cookie: tokenInfo=xxx#?yyy; Path=/）
        val tokenInfo = cookieStore["tokenInfo"] ?: ""

        return YxtLoginResult(
            token = studentId,
            userId = studentId,
            customerId = "",
            customerName = "",
            hexUserId = "",
            name = account
        ).also {
            // 保存 tokenInfo 供后续请求使用
            _lastTokenInfo = tokenInfo
        }
    }

    /** 临时保存最近一次登录的 tokenInfo（供 ViewModel 取用） */
    private var _lastTokenInfo: String = ""
    fun getLastTokenInfo(): String = _lastTokenInfo

    /** 恢复 session 时设置 V3 业务域名 */
    fun restoreV3BaseUrl(url: String) {
        if (url.isNotBlank()) _v3BaseUrl = url
    }

    /**
     * V3 获取已绑定学校列表：GET {v3-app}/user/binding/getSchoolInfo
     * 抓包确认：登录后调用，返回 {Data:{schoolList:[80061]}}
     * schoolId 不在搜索结果里，必须登录后从此接口获取
     *
     * 签名抓包确认：GET 无参数时 sign = MD5("1FF75E512" + "") = 00f0cb96...
     */
    fun getSchoolInfoV3(): String {
        val url = "$v3BaseUrl/user/binding/getSchoolInfo"
        val sign = YxtSigner.signV3("")
        android.util.Log.d("YxtApi", "=== V3 获取学校信息 ===")
        android.util.Log.d("YxtApi", "URL: $url")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")
        val json = execute(buildV3Request(url, "GET", sign = sign))
        val data = json.data()
        // Data.schoolList 是数组 [80061]
        val arr = data.optJSONArray("schoolList") ?: JSONArray()
        if (arr.length() == 0) return ""
        return arr.opt(0).toString()
    }

    /** 构建 V3 请求（X-Sign Header + 抓包确认的 Header 值，无 timestamp）
     *  抓包确认：登录前 Cookie 为空字符串，登录后 Cookie 为 tokenInfo=xxx
     *  所有 V3 业务接口都必须带 X-Sign（GET 无参数时 sign = signV3("")），
     *  否则服务端返回 -301 "数据安全校验不通过" */
    private fun buildV3Request(
        url: String,
        method: String = "GET",
        body: String? = null,
        sign: String = ""
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", if (method == "POST") "application/json" else "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            // V3 签名 Header（所有业务接口必传，空 sign 表示 GET 无参数）
            .header("X-Sign", sign)
            // Cookie: 登录前为空字符串，登录后为 tokenInfo=xxx（抓包确认必须有此 header）
            .apply {
                val tokenInfo = session?.tokenInfo
                if (!tokenInfo.isNullOrBlank()) {
                    header("Cookie", "tokenInfo=$tokenInfo")
                } else {
                    header("Cookie", "")
                }
            }
            .header("X-Sid", X_SID)
            .header("X-Product-Ver", X_PRODUCT_VER)
            .header("X-Mobile-Info", "Android#?${Build.MODEL}#?${Build.VERSION.RELEASE}")
            .header("X-clientType", X_CLIENT_TYPE_V3)
            .header("X-B3-TraceId", YxtSigner.randomHex16())
            .header("X-B3-SpanId", YxtSigner.randomHex16())
            .header("X-Ghost", YxtSigner.computeGhost())
            .header("X-route", "")

        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                if (body != null) {
                    builder.post(body.toRequestBody(JSON_MEDIA))
                } else {
                    builder.post("".toRequestBody(JSON_MEDIA))
                }
            }
        }
        return builder.build()
    }

    // ════════════════════════════════════════════════
    //  V3 业务接口（登录后，域名 v3-app.lxt6.cn，Cookie: tokenInfo=xxx）
    // ════════════════════════════════════════════════

    /**
     * V3 获取学生信息：GET /user/basic/getUserInfo
     * 返回: {studentName, studentMobile, studentIdentity, studentGender, studentHex}
     * 签名: signV3("")（GET 无参数）
     */
    fun getUserInfoV3(): YxtUserInfoV3 {
        val url = "$v3BaseUrl/user/basic/getUserInfo"
        val sign = YxtSigner.signV3("")
        val json = execute(buildV3Request(url, "GET", sign = sign))
        val data = json.data()
        return YxtUserInfoV3(
            studentName = data.optString("studentName"),
            studentMobile = data.optString("studentMobile"),
            studentHex = data.optString("studentHex")
        )
    }

    /**
     * V3 获取服务商信息：GET /operation/getInvestorInfoByShoolidV2?schoolId=xxx
     * 返回 Data 是数组，取第一个元素的 investorInfo.investorId
     * 抓包确认: investorId = "600000000000000050"（淮北师范恒信）
     * 签名: signV3("schoolId=xxx")（GET 带参数，签名输入为查询字符串，不含 ?）
     */
    fun getInvestorInfoV3(schoolId: String): String {
        val url = "$v3BaseUrl/operation/getInvestorInfoByShoolidV2?schoolId=" +
            URLEncoder.encode(schoolId, "UTF-8")
        val sign = YxtSigner.signV3("schoolId=$schoolId")
        android.util.Log.d("YxtApi", "=== V3 获取服务商信息 ===")
        android.util.Log.d("YxtApi", "URL: $url")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")
        val json = execute(buildV3Request(url, "GET", sign = sign))
        // Data 是数组
        val dataObj = json.opt("Data") ?: json.opt("data")
        val arr = when (dataObj) {
            is JSONArray -> dataObj
            is String -> JSONArray(dataObj)
            else -> JSONArray()
        }
        val first = arr.optJSONObject(0) ?: JSONObject()
        val investorInfo = first.optJSONObject("investorInfo") ?: JSONObject()
        return investorInfo.optString("investorId")
    }

    /**
     * V3 获取余额：POST /payment/student/getWalletInfoByInvestorid
     * Body: {investorId, schoolId, walletType:1}（不含 studentId）
     * 返回: {id, money(分), statusFlag, realApplyRefund, msg}
     * money=1052 表示 10.52 元
     *
     * 注：之前用 /paymentV1/getUserWallet（含 studentId）返回 Data:null，
     * 改用抓包确认的 /payment/student/getWalletInfoByInvestorid 接口。
     *
     * @return YxtWalletV3（id=钱包ID用于充值，balance=余额文本"10.52"）
     */
    fun getWalletV3(investorId: String, schoolId: String, studentId: String): YxtWalletV3 {
        val body = JSONObject().apply {
            put("investorId", investorId)
            put("schoolId", schoolId)
            put("walletType", 1)
        }.toString()
        val url = "$v3BaseUrl/payment/student/getWalletInfoByInvestorid"
        val sign = YxtSigner.signV3(body)
        android.util.Log.d("YxtApi", "=== V3 余额请求 ===")
        android.util.Log.d("YxtApi", "Body: $body")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")
        val json = execute(buildV3Request(url, "POST", body, sign))
        val data = json.data()
        val moneyFen = data.optInt("money", 0)
        val moneyYuan = "%.2f".format(moneyFen / 100.0)
        return YxtWalletV3(
            id = data.optString("id"),
            balance = moneyYuan,
            statusFlag = data.optInt("statusFlag", 1)
        )
    }

    /**
     * V3 充值记录：POST /bill/wallet/getOrderList
     * Body: {currentPage:1, investorId:null, ..., schoolId, selectFlag:5, typeId:0}
     * 返回: {records:[{orderId, payDateTime, price(分), giftAmount, payFlagType}]}
     */
    fun getRechargeRecordsV3(schoolId: String): List<YxtBill> {
        val body = JSONObject().apply {
            put("currentPage", 1)
            put("investorId", JSONObject.NULL)
            put("machineId", JSONObject.NULL)
            put("orderId", JSONObject.NULL)
            put("payCode", 0)
            put("schoolId", schoolId)
            put("selectFlag", 5)
            put("siteFlag", JSONObject.NULL)
            put("siteId", JSONObject.NULL)
            put("studentId", JSONObject.NULL)
            put("typeId", 0)
        }.toString()
        val url = "$v3BaseUrl/bill/wallet/getOrderList"
        val sign = YxtSigner.signV3(body)
        val json = execute(buildV3Request(url, "POST", body, sign))
        val data = json.data()
        val arr = data.optJSONArray("records") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            val priceFen = o.optInt("price", 0)
            YxtBill(
                title = "充值",
                amount = "%.2f".format(priceFen / 100.0),
                time = o.optString("payDateTime"),
                type = "recharge",
                status = when (o.optInt("payFlagType")) {
                    1 -> "微信支付"
                    2 -> "支付宝"
                    else -> "已支付"
                }
            )
        }
    }

    /**
     * V3 消费记录：POST /bill/useMachineOrder/getOrderList
     * Body: {currentPage:1, ..., payCode:1, schoolId, selectFlag:5, typeId:6}
     * 返回: {records:[{orderId, posDateTime, posPrice(分), providerName, ...}]}
     */
    fun getConsumeRecordsV3(schoolId: String): List<YxtBill> {
        val body = JSONObject().apply {
            put("currentPage", 1)
            put("investorId", JSONObject.NULL)
            put("machineId", JSONObject.NULL)
            put("orderId", JSONObject.NULL)
            put("payCode", 1)
            put("schoolId", schoolId)
            put("selectFlag", 5)
            put("siteFlag", JSONObject.NULL)
            put("siteId", JSONObject.NULL)
            put("studentId", JSONObject.NULL)
            put("typeId", 6)
        }.toString()
        val url = "$v3BaseUrl/bill/useMachineOrder/getOrderList"
        val sign = YxtSigner.signV3(body)
        val json = execute(buildV3Request(url, "POST", body, sign))
        val data = json.data()
        val arr = data.optJSONArray("records") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            val priceFen = o.optInt("posPrice", 0)
            YxtBill(
                title = o.optString("providerName").ifBlank { "消费" },
                amount = "-%.2f".format(priceFen / 100.0),
                time = o.optString("posDateTime"),
                type = "consume",
                status = "已支付"
            )
        }
    }

    /**
     * V3 充值下单：POST /unifiedOrder/createOrders
     * Body: {businessType:1, giveRulesId:-1, investorId, payFlagType, price(分), productId:钱包ID, schoolId}
     * payFlagType: 1=微信, 2=支付宝
     * 返回: {payInfo:{mode, tradeNo}, orderId, orderSign}
     *
     * @param amount 充值金额（元，字符串如 "1.00"）
     * @param payFlagType 1=微信 2=支付宝
     * @param walletId 钱包ID（从 getWalletV3 获取的 id）
     */
    fun createRechargeOrderV3(
        amount: String,
        payFlagType: Int,
        investorId: String,
        schoolId: String,
        walletId: String
    ): YxtRechargeOrder {
        val amountYuan = amount.toDoubleOrNull() ?: 0.0
        val priceFen = (amountYuan * 100).toInt()
        val body = JSONObject().apply {
            put("businessType", 1)
            put("giveRulesId", -1)
            put("investorId", investorId)
            put("modelType", JSONObject.NULL)
            put("payFlagType", payFlagType)
            put("payModelParameter", JSONObject.NULL)
            put("price", priceFen)
            put("productId", walletId.toLong())
            put("schoolId", schoolId)
        }.toString()
        val url = "$v3BaseUrl/unifiedOrder/createOrders"
        val sign = YxtSigner.signV3(body)
        android.util.Log.d("YxtApi", "=== V3 充值下单 ===")
        android.util.Log.d("YxtApi", "Body: $body")
        android.util.Log.d("YxtApi", "sign(X-Sign): $sign")
        val json = execute(buildV3Request(url, "POST", body, sign))
        val data = json.data()
        val payInfo = data.optJSONObject("payInfo") ?: JSONObject()
        android.util.Log.d("YxtApi", "=== V3 充值下单响应 ===")
        android.util.Log.d("YxtApi", "payInfo: $payInfo")
        android.util.Log.d("YxtApi", "orderId: ${data.optString("orderId")}")
        // 支付宝(payFlagType=2): payInfo.tradeNo = "alipay_sdk=...&method=alipay.trade.app.pay&..."
        // 微信(payFlagType=1):   payInfo 可能含 appId/partnerId/prepayId/nonceStr/timeStamp/sign
        //                      或 tradeNo 为 JSON 字符串含这些字段
        val tradeNo = payInfo.optString("tradeNo")
        // 尝试解析微信支付参数（字段名可能不同，多尝试几种）
        val wxAppId = payInfo.optString("appId", payInfo.optString("appid", payInfo.optString("AppId", "")))
        val wxPartnerId = payInfo.optString("partnerId", payInfo.optString("partnerid", payInfo.optString("mch_id", "")))
        val wxPrepayId = payInfo.optString("prepayId", payInfo.optString("prepayid", payInfo.optString("prepay_id", "")))
        val wxNonceStr = payInfo.optString("nonceStr", payInfo.optString("noncestr", payInfo.optString("nonce_str", "")))
        val wxTimeStamp = payInfo.optString("timeStamp", payInfo.optString("timestamp", payInfo.optString("time_stamp", "")))
        val wxSign = payInfo.optString("sign", payInfo.optString("paySign", ""))
        // 如果 tradeNo 看起来是 JSON（微信可能把参数放在 tradeNo 里）
        var appId = wxAppId
        var partnerId = wxPartnerId
        var prepayId = wxPrepayId
        var nonceStr = wxNonceStr
        var timeStamp = wxTimeStamp
        var paySign = wxSign
        if (appId.isBlank() && tradeNo.startsWith("{")) {
            try {
                val tn = JSONObject(tradeNo)
                appId = tn.optString("appId", tn.optString("appid", ""))
                partnerId = tn.optString("partnerId", tn.optString("partnerid", ""))
                prepayId = tn.optString("prepayId", tn.optString("prepayid", ""))
                nonceStr = tn.optString("nonceStr", tn.optString("noncestr", ""))
                timeStamp = tn.optString("timeStamp", tn.optString("timestamp", ""))
                paySign = tn.optString("sign", tn.optString("paySign", ""))
            } catch (_: Exception) {}
        }
        return YxtRechargeOrder(
            tradeNo = tradeNo,
            orderString = tradeNo,
            appId = appId, partnerId = partnerId, prepayId = prepayId,
            nonceStr = nonceStr, timeStamp = timeStamp, sign = paySign, signType = "MD5",
            mode = payInfo.optInt("mode"),
            raw = data.toString()
        )
    }

    /**
     * V4 登录：POST {API_URL}/common/user/customerPersonLogin
     * Body (V4Login): { userName, password, captcha, captchaKey }
     */
    fun login(userName: String, password: String, captcha: String, captchaKey: String): YxtLoginResult {
        val body = JSONObject().apply {
            put("userName", userName)
            put("password", password)
            put("captcha", captcha)
            put("captchaKey", captchaKey)
        }.toString()
        android.util.Log.d("YxtApi", "=== V4 登录请求体 ===")
        android.util.Log.d("YxtApi", "Body: $body")
        val json = execute(buildRequest("$API_URL/common/user/customerPersonLogin", "POST", body))
        val data = json.data()
        return YxtLoginResult(
            token = data.optString("token"),
            userId = data.optString("userId"),
            customerId = data.optString("customerId"),
            customerName = data.optString("customerName"),
            hexUserId = data.optString("hexUserId"),
            name = data.optString("name")
        )
    }

    /**
     * V4 发送短信验证码：POST {API_URL}/sms/sendSmscode
     * @FormUrlEncoded, 3个 @Field: mobile + captcha + captchaKey
     * dex 确认参数名，需先通过图形验证码验证才能发送短信
     */
    fun sendSmsCode(mobile: String, captcha: String, captchaKey: String) {
        // FormUrlEncoded 用 URL 编码的 form body
        val formBody = "mobile=${URLEncoder.encode(mobile, "UTF-8")}" +
            "&captcha=${URLEncoder.encode(captcha, "UTF-8")}" +
            "&captchaKey=${URLEncoder.encode(captchaKey, "UTF-8")}"
        android.util.Log.d("YxtApi", "=== V4 发送短信 ===")
        android.util.Log.d("YxtApi", "mobile=$mobile captcha=$captcha captchaKey=$captchaKey")
        // sendSmscode 是 @FormUrlEncoded，Content-Type 必须是 x-www-form-urlencoded
        execute(buildFormRequest("$API_URL/sms/sendSmscode", formBody))
    }

    /**
     * V4 重置登录密码：POST {API_URL}/common/user/resetPassword
     * Body (V4ResetPassword): { mobile, password, smsCode }
     * dex 确认字段: mobile + password(新密码明文) + smsCode(收到的短信码)
     */
    fun resetPassword(mobile: String, newPassword: String, smsCode: String) {
        val body = JSONObject().apply {
            put("mobile", mobile)
            put("password", newPassword)
            put("smsCode", smsCode)
        }.toString()
        android.util.Log.d("YxtApi", "=== V4 重置密码 ===")
        android.util.Log.d("YxtApi", "mobile=$mobile smsCode=$smsCode newPassword=***")
        execute(buildRequest("$API_URL/common/user/resetPassword", "POST", body))
    }

    // ════════════════════════════════════════════════
    //  登录后初始化
    // ════════════════════════════════════════════════

    /**
     * 获取用户详情：GET /cus/customerPerson/getUserInfo
     * 返回 V4UserInfoDetailBean，含 balanceId / projectId / serviceId 等
     *
     * 注意：getListCustomerByUserId 是 getSchoolInfos（返回 V4SchoolInfoBean 列表），
     *       getUserInfo 才返回完整的 V4UserInfoDetailBean。
     */
    fun getUserInfo(): YxtUserInfo {
        val json = execute(buildRequest("$API_URL/cus/customerPerson/getUserInfo", "GET", requireAuth = true))
        val data = json.data()
        // V4UserInfoDetailBean 字段
        return YxtUserInfo(
            balanceId = data.optString("balanceId"),
            balance = data.optString("balance"),
            totalBalance = data.optString("totalBalance"),
            projectId = data.optString("projectId"),
            serviceId = data.optString("serviceId"),
            serviceName = data.optString("serviceName"),
            customerId = data.optString("customerId"),
            cpersonId = data.optString("cpersonId"),
            customerPhone = data.optString("customerPhone"),
            customerName = data.optString("customerName"),
            name = data.optString("name"),
            hexUserId = data.optString("hexUserId"),
            userId = data.optString("userId")
        )
    }

    // ════════════════════════════════════════════════
    //  核心业务：余额 / 账单 / 充值
    // ════════════════════════════════════════════════

    /** 查询余额：GET /cus/projectBalance/getBalanceDetail?balanceId=xxx */
    fun getWalletInfo(balanceId: String): YxtWallet {
        val url = "$API_URL/cus/projectBalance/getBalanceDetail?balanceId=" +
            URLEncoder.encode(balanceId, "UTF-8")
        val json = execute(buildRequest(url, "GET", requireAuth = true))
        val data = json.data()
        // V4WalletInfoBean: balance, giftBalance, totalBalance
        return YxtWallet(
            balance = data.optString("balance"),
            giftBalance = data.optString("giftBalance"),
            totalBalance = data.optString("totalBalance")
        )
    }

    /** 查询充值记录：GET /cus/recharge/pageQuery */
    fun getRechargeRecords(): List<YxtBill> {
        val json = execute(buildRequest("$API_URL/cus/recharge/pageQuery", "GET", requireAuth = true))
        return parseRechargeRecords(json.data())
    }

    /**
     * 查询消费记录：GET /cus/deviceConsume/pageQuery
     * Query: timeFrameType, pageSize, pageIndex, productId, pay
     */
    fun getConsumeRecords(): List<YxtBill> {
        val url = "$API_URL/cus/deviceConsume/pageQuery" +
            "?timeFrameType=0&pageSize=20&pageIndex=1&productId=0&pay=0"
        val json = execute(buildRequest(url, "GET", requireAuth = true))
        return parseConsumeRecords(json.data())
    }

    private fun parseRechargeRecords(data: JSONObject): List<YxtBill> {
        // V4RechargeRecordListBean: records (List<V4RechargeRecordBean>)
        val arr = data.optJSONArray("records") ?: data.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            YxtBill(
                title = o.optString("title", o.optString("productName", o.optString("remark", "充值"))),
                amount = o.optString("amount", o.optString("money", o.optString("rechargeAmount", "0"))),
                time = o.optString("createTime", o.optString("rechargeTime", o.optString("time", ""))),
                type = "recharge",
                status = o.optString("status", o.optString("rechargeStatus", ""))
            )
        }
    }

    private fun parseConsumeRecords(data: JSONObject): List<YxtBill> {
        // V4BathRecordListBean: records (List<V4BathRecordBean>)
        // V4BathRecordBean: address, consume, consumeOrderId, createTime, deviceName, mac, pay, payName, useTime
        val arr = data.optJSONArray("records") ?: data.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            YxtBill(
                title = o.optString("deviceName", o.optString("address", "消费")),
                amount = "-" + o.optString("consume", o.optString("pay", "0")),
                time = o.optString("createTime", o.optString("useTime", "")),
                type = "consume",
                status = if (o.optBoolean("isPayed", true)) "已支付" else "未支付"
            )
        }
    }

    /**
     * 创建充值订单：POST /cus/recharge/userRecharge
     * Body (V4Recharge): { amount, payMent, projectId, rechargeAmountDefId, productType, mac, way }
     *
     * dex 确认 V4HttpBusinessStore.postUserRecharge 方法构建 V4Recharge bean:
     *   amount, payMent, projectId (从用户信息获取), rechargeAmountDefId, productType=1
     *   mac 和 way 未设置（默认值）
     *
     * @param amount 充值金额（元，字符串如 "10.00"）
     * @param payMent 支付方式：1=微信 2=支付宝（PayFlagType.WX=1, ZFB=2）
     * @param projectId 从 getUserInfo 获取
     */
    fun createRechargeOrder(
        amount: String,
        payMent: Int,
        projectId: String
    ): YxtRechargeOrder {
        val body = JSONObject().apply {
            put("amount", amount)
            put("payMent", payMent)
            put("productType", 1)
            put("projectId", projectId)
            put("rechargeAmountDefId", "")
            put("mac", "")
            put("way", payMent)
        }.toString()
        val json = execute(buildRequest("$API_URL/cus/recharge/userRecharge", "POST", body, requireAuth = true))
        val data = json.data()
        // V4RechargeBean: appId, mode, nonceStr, orderString, partnerId, prepayId, sign, signType, sysRandom, timeStamp, tradeNo
        return YxtRechargeOrder(
            tradeNo = data.optString("tradeNo"),
            orderString = data.optString("orderString"),
            appId = data.optString("appId"),
            partnerId = data.optString("partnerId"),
            prepayId = data.optString("prepayId"),
            nonceStr = data.optString("nonceStr"),
            timeStamp = data.optString("timeStamp"),
            sign = data.optString("sign"),
            signType = data.optString("signType"),
            mode = data.optInt("mode"),
            raw = data.toString()
        )
    }
}

/** 水卡异常 */
class YxtException(message: String) : Exception(message)
