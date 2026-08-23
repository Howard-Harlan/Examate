package go.examate.hyp.meschat

import android.util.Base64
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Nostr 密码学工具:secp256k1 密钥对生成 + BIP-340 Schnorr 签名。
 *
 * Nostr 事件签名使用 BIP-340 Schnorr 签名算法(x-only 公钥),
 * 本类基于 BouncyCastle 的 secp256k1 曲线参数实现。
 */
object NostrCrypto {

    private val params = CustomNamedCurves.getByName("secp256k1")
    private val curve = params.curve
    private val G: ECPoint = params.g
    private val n: BigInteger = params.n

    /** SHA-256 哈希 */
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** SHA-256 哈希,返回 hex 字符串 */
    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString("") { "%02x".format(it) }

    /** 字节数组转 hex 字符串 */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** hex 字符串转字节数组 */
    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        val len = clean.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    /** BigInteger → 32 字节大端序 */
    private fun to32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> raw.copyOfRange(raw.size - 32, raw.size)
        }
    }

    /**
     * 生成 secp256k1 私钥(32 字节随机数)。
     * @return hex 编码的私钥
     */
    fun generatePrivateKey(): String {
        val random = SecureRandom()
        var d: BigInteger
        do {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            d = BigInteger(1, bytes).mod(n)
        } while (d.signum() == 0 || d >= n)
        return toHex(to32Bytes(d))
    }

    /**
     * 从私钥计算 x-only 公钥(32 字节)。
     * @param privKeyHex hex 编码的私钥
     * @return hex 编码的 x-only 公钥(32 字节)
     */
    fun getPublicKey(privKeyHex: String): String {
        val d = BigInteger(1, fromHex(privKeyHex)).mod(n)
        val P = G.multiply(d).normalize()
        // x-only 公钥:只取 x 坐标(32 字节)
        return toHex(to32Bytes(P.affineXCoord.toBigInteger()))
    }

    /**
     * BIP-340 Schnorr 签名。
     * @param messageHash 32 字节消息哈希
     * @param privKeyHex hex 编码的私钥
     * @return 64 字节签名 (x(R) || s) 的 hex 编码
     */
    fun schnorrSign(messageHash: ByteArray, privKeyHex: String): String {
        val d = BigInteger(1, fromHex(privKeyHex)).mod(n)
        val P = G.multiply(d).normalize()
        val Px = to32Bytes(P.affineXCoord.toBigInteger())
        val Py = P.affineYCoord.toBigInteger()

        // 步骤1:如果 P.y 是奇数,d' = n - d
        val dPrime = if (Py.mod(BigInteger.TWO) == BigInteger.ONE) n.subtract(d) else d

        // 步骤2:辅助随机数 t = HMAC-SHA256(d', x(P)) XOR bytes(x(P)) -- 简化为随机数
        // BIP-340 规范用确定性 t = HMAC-SHA256(d', x(P)) XOR bytes(x(P)),
        // 这里用简化版随机 t(安全但不完全符合 BIP-340 确定性要求)
        val t = ByteArray(32)
        SecureRandom().nextBytes(t)

        // 步骤3:k0 = H(tag || t || x(P) || m) mod n
        val tag = sha256("BIP0340/challenge".toByteArray())
        val k0Input = tag + tag + t + Px + messageHash
        val k0 = BigInteger(1, sha256(k0Input)).mod(n)
        require(k0.signum() != 0) { "k0 为零,重试" }

        // 步骤4:R = k0 * G
        val R = G.multiply(k0).normalize()
        val Rx = to32Bytes(R.affineXCoord.toBigInteger())
        val Ry = R.affineYCoord.toBigInteger()

        // 步骤5:如果 R.y 是奇数,k = n - k0
        val k = if (Ry.mod(BigInteger.TWO) == BigInteger.ONE) n.subtract(k0) else k0

        // 步骤6:e = H(tag || x(R) || x(P) || m) mod n
        val eInput = tag + tag + Rx + Px + messageHash
        val e = BigInteger(1, sha256(eInput)).mod(n)

        // 步骤7:s = (k + e * d') mod n
        val s = k.add(e.multiply(dPrime)).mod(n)

        // 签名 = x(R) || s
        return toHex(Rx + to32Bytes(s))
    }

    /**
     * 计算 Nostr 事件 ID。
     * 事件 ID = SHA256(JSON([0, pubkey, created_at, kind, tags, content]))
     */
    fun eventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: org.json.JSONArray,
        content: String
    ): String {
        val arr = org.json.JSONArray()
        arr.put(0)
        arr.put(pubkey)
        arr.put(createdAt)
        arr.put(kind)
        arr.put(tags)
        arr.put(content)
        return sha256Hex(arr.toString().toByteArray())
    }

    // ════════════════════════════════════════
    //  AES 工具(密钥匹对私聊 / NIP-04)
    // ════════════════════════════════════════

    /** AES-256-CBC 加密/解密(失败返回 null) */
    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从配对密钥(口令)派生 32 字节 AES 密钥:SHA-256(UTF-8 口令)。
     */
    fun deriveKey(secret: String): ByteArray =
        sha256(secret.toByteArray(Charsets.UTF_8))

    /**
     * 配对房间 ID:由配对密钥确定性派生(不暴露密钥本身)。
     * 双方输入相同密钥 → 得到相同房间 ID,订阅/过滤都以此为准。
     */
    fun roomIdFromKey(key: String): String = sha256Hex(deriveKey(key)).take(16)

    /** 配对房间加密:随机 IV + AES-CBC,返回 (iv, ciphertext);失败返回 null */
    fun roomEncrypt(key: ByteArray, plain: String): Pair<ByteArray, ByteArray>? {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = aes(Cipher.ENCRYPT_MODE, key, iv, plain.toByteArray(Charsets.UTF_8))
            ?: return null
        return iv to cipher
    }

    /** 配对房间解密;失败返回 null(密钥不符时静默丢弃) */
    fun roomDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): String? =
        aes(Cipher.DECRYPT_MODE, key, iv, cipher)?.toString(Charsets.UTF_8)

    /**
     * NIP-04 ECDH 共享密钥:secp256k1 点乘 + SHA-256(x 坐标)。
     * 公钥为 x-only(本应用生成的 pubkey),按偶数 y 重建点,双方一致。
     */
    fun sharedSecretNip04(privKeyHex: String, pubKeyHex: String): ByteArray? {
        return try {
            val d = BigInteger(1, fromHex(privKeyHex)).mod(n)
            val point = listOf("02" + pubKeyHex, "03" + pubKeyHex)
                .asSequence()
                .map { runCatching { curve.decodePoint(fromHex(it)) }.getOrNull() }
                .filterNotNull()
                .firstOrNull { it.isValid && !it.isInfinity }
                ?: return null
            val shared = point.multiply(d).normalize()
            if (shared.isInfinity) return null
            sha256(to32Bytes(shared.affineXCoord.toBigInteger()))
        } catch (e: Exception) {
            null
        }
    }

    /** NIP-04 加密:输出 "base64(iv)?iv=base64(ciphertext)" */
    fun encryptNip04(sharedKey: ByteArray, plain: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = aes(Cipher.ENCRYPT_MODE, sharedKey, iv, plain.toByteArray(Charsets.UTF_8))
            ?: return ""
        return Base64.encodeToString(iv, Base64.NO_WRAP) +
            "?iv=" + Base64.encodeToString(cipher, Base64.NO_WRAP)
    }

    /** NIP-04 解密;失败返回 null */
    fun decryptNip04(sharedKey: ByteArray, payload: String): String? {
        val parts = payload.split("?iv=")
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            aes(Cipher.DECRYPT_MODE, sharedKey, iv, ct)?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算 NIP-04 共享密钥的两种候选(公钥 x-only,偶 y 与奇 y 两个候选点)。
     * 发送方统一按偶 y 加密;解密时若偶 y 失败再尝试奇 y,
     * 兼容按奇 y 重建点的第三方实现。
     */
    fun sharedSecretsNip04(privKeyHex: String, pubKeyHex: String): List<ByteArray> {
        return try {
            val d = BigInteger(1, fromHex(privKeyHex)).mod(n)
            listOf("02" + pubKeyHex, "03" + pubKeyHex)
                .mapNotNull { prefix ->
                    runCatching { curve.decodePoint(fromHex(prefix)) }
                        .getOrNull()
                        ?.takeIf { it.isValid && !it.isInfinity }
                }
                .mapNotNull { point ->
                    val shared = point.multiply(d).normalize()
                    if (shared.isInfinity) null
                    else sha256(to32Bytes(shared.affineXCoord.toBigInteger()))
                }
                .distinctBy { it.joinToString(",") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** NIP-04 解密(尝试全部候选共享密钥);失败返回 null */
    fun decryptNip04Any(secrets: List<ByteArray>, payload: String): String? {
        for (secret in secrets) {
            val plain = decryptNip04(secret, payload)
            if (plain != null) return plain
        }
        return null
    }
}
