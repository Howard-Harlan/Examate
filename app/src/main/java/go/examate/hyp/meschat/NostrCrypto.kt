package go.examate.hyp.meschat

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

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
}
