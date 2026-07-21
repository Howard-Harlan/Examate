package go.examate.hyp.watercard

/** 学校信息（V4PlatformInfoBean）
 *  sysVersion: 4=V4平台，3=V3平台 */
data class YxtSchool(
    val schoolName: String,
    val schoolAlias: String,
    val sysVersion: Int,
    val schoolId: String = ""
) {
    /** 是否为 V4 平台 */
    val isV4: Boolean get() = sysVersion == 4
}

/** 验证码
 *  V4: {captchaBase64, captchaKey}（JPEG, 4位字母数字）
 *  V3: {captchaBase64, captchaKey=""}（PNG, 5位数字，不需要 captchaKey） */
data class YxtCaptcha(
    val captchaBase64: String,
    val captchaKey: String
)

/** 登录结果（V4LoginBean + 用户详情） */
data class YxtLoginResult(
    val token: String,
    val userId: String,
    val customerId: String,
    val customerName: String,
    val hexUserId: String,
    val name: String
)

/** 用户详情（V4UserInfoDetailBean，含 balanceId） */
data class YxtUserInfo(
    val balanceId: String,
    val balance: String,
    val totalBalance: String,
    val projectId: String,
    val serviceId: String,
    val serviceName: String,
    val customerId: String,
    val cpersonId: String,
    val customerPhone: String,
    val customerName: String,
    val name: String,
    val hexUserId: String,
    val userId: String
)

/** 钱包余额（V4WalletInfoBean） */
data class YxtWallet(
    val balance: String,
    val giftBalance: String,
    val totalBalance: String
) {
    /** 余额文本（元） */
    val balanceText: String get() = balance.ifBlank { "0.00" }
}

/** 账单记录 */
data class YxtBill(
    val title: String,
    val amount: String,
    val time: String,
    val type: String,
    val status: String
) {
    val isIncome: Boolean get() = amount.toDoubleOrNull()?.let { it > 0 } ?: false
    val amountText: String get() {
        val v = amount.toDoubleOrNull() ?: 0.0
        return "%.2f".format(kotlin.math.abs(v))
    }
}

/** 充值订单（V4RechargeBean） */
data class YxtRechargeOrder(
    val tradeNo: String,
    val orderString: String,
    val appId: String,
    val partnerId: String,
    val prepayId: String,
    val nonceStr: String,
    val timeStamp: String,
    val sign: String,
    val signType: String,
    val mode: Int,
    val raw: String
)

/** 水卡会话（持久化用）
 *  V4: token/customerId/balanceId/projectId
 *  V3: tokenInfo(完整cookie值)/studentId/v3BaseUrl(业务域名)/schoolId/investorId */
data class YxtSession(
    val token: String,
    val userId: String,
    val customerId: String,
    val customerName: String,
    val balanceId: String,
    val projectId: String,
    val serviceId: String,
    val serviceName: String,
    val customerPhone: String,
    val userName: String,
    // V3 专用
    val isV4: Boolean = true,
    val tokenInfo: String = "",        // V3 Cookie: tokenInfo=xxx#?yyy 的完整值
    val v3BaseUrl: String = "",        // V3 业务域名（登录后从 Data.url 获取）
    val schoolId: String = "",         // V3 学校 ID（如 80061）
    val investorId: String = "",       // V3 服务商 ID
    val studentHex: String = ""        // V3 学生 Hex ID
) {
    val isLoggedIn: Boolean get() = if (isV4) {
        token.isNotBlank() && userId.isNotBlank()
    } else {
        tokenInfo.isNotBlank() && userId.isNotBlank()
    }
}

/** V3 用户信息 */
data class YxtUserInfoV3(
    val studentName: String,
    val studentMobile: String,
    val studentHex: String
)

/** V3 钱包余额
 *  id: 钱包 ID（用于充值下单的 productId）
 *  balance: 余额文本（元，如 "10.52"）
 *  statusFlag: 钱包状态（1=正常） */
data class YxtWalletV3(
    val id: String,
    val balance: String,
    val statusFlag: Int
)
