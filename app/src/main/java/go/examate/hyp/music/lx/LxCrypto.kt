package go.examate.hyp.music.lx

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LX 音源 crypto 工具 — 纯 Java 实现,桥接给 QuickJS 的 utils.crypto。
 *
 * LX 源脚本用它对各音乐平台请求签名(md5/aes/rsa/base64)。所有方法容错返回空串,
 * 避免脚本内异常导致引擎崩溃。输入/输出编码尽量对齐 LX 桌面端约定。
 */
object LxCrypto {

    private const val TAG = "LxCrypto"

    fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    fun base64Encode(s: String): String = try {
        Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    fun base64Decode(s: String): String = try {
        String(Base64.decode(s, Base64.NO_WRAP))
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    /** 随机字节的 hex 串 */
    fun randomBytesHex(len: Int): String = try {
        val b = ByteArray(len.coerceIn(1, 256))
        SecureRandom().nextBytes(b)
        b.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    /**
     * AES 加密 → base64。
     * @param mode 如 "aes-128-cbc"/"aes-192-ecb"(取 cbc/ecb 与 padding)
     */
    fun aesEncrypt(data: String, key: String, iv: String, mode: String): String = try {
        val cipher = buildAesCipher(Cipher.ENCRYPT_MODE, key, iv, mode)
        Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    /** AES 解密(输入 base64) */
    fun aesDecrypt(dataB64: String, key: String, iv: String, mode: String): String = try {
        val cipher = buildAesCipher(Cipher.DECRYPT_MODE, key, iv, mode)
        String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)))
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    private fun buildAesCipher(opmode: Int, key: String, iv: String, mode: String): Cipher {
        // 注:mode(cbc/ecb)由源脚本传入,必须与目标音乐平台 web 端签名算法完全一致签名才通过;
        // 此处为互操作需要(非保护本应用敏感数据),故保留 ECB 支持,不能改 GCM(否则平台拒签)。
        val ecb = mode.contains("ecb", true)
        val transformation = if (ecb) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding"
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        if (ecb) cipher.init(opmode, keySpec)
        else cipher.init(opmode, keySpec, IvParameterSpec(iv.toByteArray()))
        return cipher
    }

    /**
     * RSA 公钥加密 → base64。publicKey 为 PEM(去头尾)或裸 base64 的 X509 公钥。
     */
    fun rsaEncrypt(data: String, publicKey: String): String = try {
        val clean = publicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.decode(clean, Base64.NO_WRAP)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    // ═══════════════════════════════════════════════
    //  落雪音乐 preload 协议桥(utils_str2b64/b642buf/str2md5/aes/rsa)
    //  输入输出均为 base64 字符串,与 lx-music 的 user-api-preload.js 对齐
    // ═══════════════════════════════════════════════

    /** str → utf8 base64(utils_str2b64) */
    fun str2b64(s: String): String = try {
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    /** base64 → "[1,2,3]" JSON 字节数组(JS 侧 new Uint8Array(JSON.parse(...))) */
    fun b64ToBufJson(b64: String): String = try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        bytes.joinToString(",", "[", "]") { it.toInt().toString() }
    } catch (_: Exception) { "[]" }

    /** URL 解码后 md5(utils_str2md5;JS 侧传入 encodeURIComponent 后的串) */
    fun md5UrlDecoded(str: String): String = try {
        md5(java.net.URLDecoder.decode(str, "UTF-8"))
    } catch (_: Exception) { md5(str) }

    /**
     * AES 加密,base64 输入输出(utils_aes_encrypt)。
     * @param mode 落雪约定:"AES/CBC/PKCS7Padding" 或 "AES/ECB/NoPadding"
     */
    fun aesEncryptB64(dataB64: String, keyB64: String, ivB64: String, mode: String): String = try {
        val data = Base64.decode(dataB64, Base64.NO_WRAP)
        val key = Base64.decode(keyB64, Base64.NO_WRAP)
        val keySpec = SecretKeySpec(key, "AES")
        if (ivB64.isBlank()) {
            val cipher = Cipher.getInstance(mode)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        } else {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val finalIv = ByteArray(16)
            System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
            val cipher = Cipher.getInstance(mode)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(finalIv))
            Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        }
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }

    /**
     * RSA 公钥加密,base64 输入输出(utils_rsa_encrypt)。
     * @param padding 落雪约定:"RSA/ECB/OAEPWithSHA1AndMGF1Padding" 或 "RSA/ECB/NoPadding"
     */
    fun rsaEncryptB64(dataB64: String, publicKey: String, padding: String): String = try {
        val clean = publicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keySpec = X509EncodedKeySpec(Base64.decode(clean, Base64.DEFAULT))
        val pub = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        Base64.encodeToString(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Base64.NO_WRAP)
    } catch (e: Exception) { Log.w(TAG, "crypto", e); "" }
}
