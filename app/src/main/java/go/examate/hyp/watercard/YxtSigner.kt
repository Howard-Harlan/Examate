package go.examate.hyp.watercard

import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * 乐校通签名工具
 *
 * V4 签名（classes3.dex V4HeaderUtil）：
 * - sign = MD5(token + timestamp + "A70893")
 * - timestamp = System.currentTimeMillis()（毫秒级）
 * - token 未登录时传空字符串 ""
 *
 * V3 签名（classes.dex V3HttpBusinessStore）：
 * - sign = MD5("1FF75E512" + input)（密钥来自 asciiToString）
 * - input 为请求参数字符串（如 "account=手机号" 或 body JSON）
 *
 * 防重放：服务端校验 timestamp 是否在允许时间窗口内，超时返回 -45 "请求已过期"
 */
object YxtSigner {

    /** V4 签名密钥（硬编码在 V4HeaderUtil.getSign() 中） */
    private const val V4_KEY = "A70893"

    /** V4 Auth 签名密钥（getSignAuth 用，非通用接口） */
    private const val V4_AUTH_KEY = "a3F9Xz7bKp2RtLmN"

    /** V3 签名密钥（asciiToString("49,70,70,55,53,69,53,49,50") = "1FF75E512"） */
    private const val V3_KEY = "1FF75E512"

    /**
     * V4 签名
     * @param token 用户 token（未登录传 ""）
     * @param timestamp 毫秒时间戳
     * @return 32 位小写 hex MD5
     */
    fun sign(token: String, timestamp: String): String =
        md5(token + timestamp + V4_KEY)

    /**
     * V3 签名
     * @param input 请求参数字符串（如 "account=手机号" 或 body JSON）
     * @return 32 位小写 hex MD5
     */
    fun signV3(input: String): String = md5(V3_KEY + input)

    /** 生成当前毫秒时间戳 */
    fun timestamp(): String = System.currentTimeMillis().toString()

    /** 生成 16 位随机 hex（用于 X-B3-TraceId 等追踪头） */
    fun randomHex16(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 生成 16 位随机大写 hex（用于 X-Ghost） */
    fun randomHex16Upper(): String = randomHex16().uppercase()

    /**
     * V3 X-Ghost 计算（dex 反编译 AppUtils.getGhost() 确认）
     *
     * 算法：
     * 1. hex = Long.toHexString(currentTimeMillis())，补0到12位
     * 2. check = sumCheckCode(hex)：每2位十六进制相加取模256，转2位大写hex
     * 3. xor = xOr(hex)：所有字节异或，转hex
     * 4. result = (check + hex + xor).toLowerCase()
     *
     * 服务端解析 X-Ghost 中的时间戳并校验是否过期，过期返回 -45
     */
    fun computeGhost(): String {
        // 1. 时间戳转16进制，补0到12位
        var hex = java.lang.Long.toHexString(System.currentTimeMillis())
        while (hex.length < 12) hex = "0$hex"

        // 2. sumCheckCode：每2位相加取模256
        var sum = 0
        var i = 0
        while (i < hex.length) {
            sum += hex.substring(i, i + 2).toInt(16)
            i += 2
        }
        var check = (sum % 256).toString(16).uppercase()
        if (check.length == 1) check = "0$check"

        // 3. xOr：所有字节异或（结果必须补0到2位，否则 X-Ghost 长度不固定导致 -45）
        var xor = 0
        i = 0
        while (i < hex.length) {
            xor = xor xor hex.substring(i, i + 2).toInt(16)
            i += 2
        }
        var xorStr = xor.toString(16)
        if (xorStr.length == 1) xorStr = "0$xorStr"

        // 4. 拼接，全小写
        return (check + hex + xorStr).lowercase()
    }

    /**
     * V3 登录加密：Base64 编码（dex 确认 postUserLoginV2WithEncrypt 用 Base64，非 AES）
     * @param input JSON 字符串
     * @return Base64 编码字符串（无换行）
     */
    fun encodeBase64(input: String): String =
        Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * Gson HTML 转义：官方 App 用 Gson 序列化 UserAesData，默认开启 HTML 转义。
     * 抓包确认：base64 中的 `=` 被转义为 `\u003d`（Content-Length 125 vs 未转义 115）。
     * 签名基于转义后的 body 计算，必须保持一致。
     */
    fun gsonEscape(input: String): String =
        input.replace("=", "\\u003d")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
            .replace("'", "\\u0027")

    private fun md5(input: String): String {
        val hash = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
