package go.examate.hyp.lepao

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 步道乐跑协议加密工具
 * - sign: MD5(sorted_kv_concat + SALT_N)
 * - 保护端点: MD5(SALT_P + concat_excluding_deviceId)
 * - body加密: AES-CBC(goME/Wet2) + Base64
 */
object LePaoCrypto {

    const val SALT_N = "rDJiNB9j7vD2"
    const val SALT_P = "eFoqdHOdDy6ViJ0i"

    val KEY_NG = "goMEzJ1pDpncNHb9".toByteArray()
    val IV_NG = "ZUrAS2lUidjyY2gK".toByteArray()
    val KEY_TRK = "Wet2C8d34f62ndi3".toByteArray()
    val IV_TRK = "K6iv85jBD8jgf32D".toByteArray()

    /** openid/accesstoken 内层加密密钥表 (nonce%10) */
    val Z8 = arrayOf(
        "R7gT3pL9sF2kD5mQ" to "B8jN4wH6xJ2vK7rE",
        "M5dS8fG3hJ7kL2pR" to "C9tY6rU3eW4qX8sZ",
        "P2bN7vF4cD9mK6jA" to "S3nH5gJ8dL2fT9wQ",
        "X6rV9tB3yG5hN7kP" to "E4sW2qA8dZ6fC3jM",
        "K8jD3mF7nH2pL5rT" to "Q6wS4eX9cV2bN7gJ",
        "F3dG7hJ2kL5pM8rN" to "Z9xC4vB6nA2sD7fH",
        "B5vN8cM3xZ6dF2gJ" to "H7kL4jP9mQ2rT5sW",
        "N6mK3lJ7pO2iU5yT" to "G8hF4dS2fA6gH9jK",
        "V2bC7nM3vX6cZ9dF" to "J5kL8jH3gD7fS2dA",
        "H9jG4fD7sA2dF5gH" to "K3lJ6pO9iU2yT5rE"
    )

    /** record_str 密钥表 W8[uid%10] */
    val W8 = arrayOf(
        "aB3dE9fG2hJkL5mN", "PqR7sT8uV9wXyZ1", "A2bC3dE4fG5hI6j",
        "K7lM8nO9pQ0rS1t", "U2vW3xY4zA5bC6d", "E7fG8hI9jK0lM1n",
        "O2pQ3rS4tU5vW6x", "Y7zA8bC9dE0fG1h", "I2jK3lM4nO5pQ6r",
        "S7tU8vW9xY0zA1b"
    )

    /** plan "2" 密钥表 f64553c9 (nonce%15) - 用于lp47参数 */
    val T15 = arrayOf(
        "G7kH3jP8mQ2rT5sW" to "D9fS2aQ6wE3dF8gH",
        "Y4tR7eW2qS5dF9jK" to "P3oI6uY8rE2tW5qS",
        "C8vB5nM3xZ7cX2dF" to "L6jK9lJ4pO3iU7yT",
        "Q5sA3dF8gH2jK7lJ" to "R9tY4eU6iO5pJ3lK",
        "N2mK8lJ5pO7iU3yT" to "F4gH6jK9lJ2pO5iU",
        "X3rV6tB4yG8hN2kP" to "B7vC3nX5mZ9dF4gH",
        "M9dS4fG6hJ3kL8pR" to "E5sW7qA2dZ4fC9jM",
        "K6jD8mF3nH5pL2rT" to "Z3xC6vB8nA4sD5fH",
        "V7bC4nM6vX8cZ3dF" to "H2jG5fD7sA3dF9gH",
        "S8dF5aQ3wE7rT2yU" to "J4kH8gF3dS6aQ5wE",
        "A6gH2jK7lJ4pO8iU" to "T3rE7yU5iO6pJ2lK",
        "B4vN7cM2xZ5dF8gJ" to "W6qE3sA8dF2gH5jK",
        "F2dG5hJ8kL6pM3rN" to "I7uO4pJ2lK8jH3gF",
        "P8bN3vF5cD7mK4jA" to "X5rV2tB7yG4hN9kP",
        "O4iU7yT2rE5tW8qS" to "G3hF6dS4fA7gH2jK"
    )

    const val UMID = "7c0d3e6f9g2h5i8j1k4l7m0n3o6p9q2r"

    /** 生成lp47反作弊参数（Base64.DEFAULT带\n，匹配app） */
    fun genLpParam(nonce: Int): Pair<String, String> {
        val idx = ((nonce % 15) + 15) % 15
        val (key, iv) = T15[idx]
        val plaintext = UMID + md5(nonce.toString())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(iv.toByteArray()))
        val b64 = android.util.Base64.encodeToString(
            cipher.doFinal(plaintext.toByteArray()), android.util.Base64.DEFAULT
        )
        return "lp47" to b64
    }

    /** openid/accesstoken 内层加密（Base64.DEFAULT带\n，匹配app） */
    fun innerEncrypt(value: String, nonce: Int): String {
        val (key, iv) = Z8[((nonce % 10) + 10) % 10]
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(iv.toByteArray()))
        return android.util.Base64.encodeToString(
            cipher.doFinal(value.toByteArray()), android.util.Base64.DEFAULT
        )
    }

    fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun aesEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return android.util.Base64.encodeToString(
            cipher.doFinal(plaintext), android.util.Base64.NO_WRAP
        )
    }

    fun aesDecrypt(b64Data: String, key: ByteArray, iv: ByteArray): ByteArray {
        val data = android.util.Base64.decode(b64Data.trim(), android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * 计算 sign
     * @param params 请求参数（不含sign）
     * @param protected 是否为保护端点（排除mobileDeviceId，盐值前缀）
     * @param deviceId 设备ID
     * @param isLoginV339 是否为quickLoginV339（额外排除nick_name和avatar_url）
     */
    fun calcSign(params: Map<String, Any?>, protected: Boolean, deviceId: String,
                 isLoginV339: Boolean = false): String {
        val filtered = if (protected) {
            var p = params.filterKeys { it != "mobileDeviceId" }
            if (isLoginV339) {
                p = p.filterKeys { it != "nick_name" && it != "avatar_url" }
            }
            p
        } else {
            params
        }
        val concat = filtered.keys.sorted().joinToString("") { k -> "$k${filtered[k]}" }
        return if (protected) {
            md5(SALT_P + concat)
        } else {
            md5(concat + SALT_N)
        }
    }

    /** record_str = MD5(str(school_id+uid) + timeStr + W8[uid%10]) — Java int算术加法 */
    fun recordStr(schoolId: Int, uid: Long, timeStr: String = ""): String {
        val sum = schoolId + uid.toInt()  // Java int 算术加法（可能溢出，与原App一致）
        val w8Key = W8[((uid % 10).toInt() + 10) % 10]
        return md5("$sum$timeStr$w8Key")
    }
}
