package go.examate.hyp.campus

import go.examate.hyp.campus.model.BankCard
import go.examate.hyp.campus.model.BillRecord
import go.examate.hyp.campus.model.EcardBalance
import go.examate.hyp.campus.model.PayArea
import go.examate.hyp.campus.model.PayBuilding
import go.examate.hyp.campus.model.PayResult
import go.examate.hyp.campus.model.RoomElectricity
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/** 充值页面解析结果(对齐 Python ecard.py get_topup_page) */
private data class TopupPageInfo(
    val bankId: String,
    val logoFileName: String,
    val walletName: String,
    /** 充值可用的签约银行卡列表(含完整卡号,供 UI 选卡) */
    val bankCards: List<BankCard>,
    /** /topUp 页面上表单中的隐藏字段,提交 /topUp/confirm 时必须携带(否则服务端返回错误页) */
    val formFields: Map<String, String> = emptyMap()
)

/** 缴费表单字段(对齐网页端 showItemListPayPage 提取的 hidden 字段) */
private data class PayFeeForm(
    val typeNum: String,
    val token: String,
    val limitMoney: String,
    val limitMode: String,
    val itemName: String,
    /** 缴费页面 <samp id="balance"> 实时钱包余额(网页端 itemPay() 以此作为 ewalletMenoy 提交) */
    val balance: String = "",
    /** 钱包下限(moneyMin),服务端校验 balance - payMoney < moneyMin 时使用 */
    val moneyMin: String = "0",
    /** 钱包ID(表单 hidden eWalletId) */
    val eWalletId: String = "1",
    /** 钱包名称(表单 hidden eWalletName,如"卡1号钱包") */
    val eWalletName: String = "",
    /** 卡账户号(表单 hidden cardAccNum,提交时字段名为 cardaccNum 小写a) */
    val cardAccNum: String = ""
)

/**
 * 校园一卡通客户端(easytong_portal,返回 HTML)— 从 [ChnuClient.EcardClient] 提取。
 *
 * 共享 [client] 以维持会话 cookie;[getServiceTicket] 回调用于 CAS SSO 单点登录获取 service ticket。
 *
 * SSO 流程:
 *  1. CAS 获取 ticket → service=/ssoLogin
 *  2. GET ssoLogin?ticket=xxx(跟随 302)→ 200 自动提交表单 HTML
 *  3. POST /easytong_portal(空表单)→ 建立 session
 *  4. GET /balance 验证含"您好"
 */
internal class EcardApi(
    private val client: OkHttpClient,
    private val getServiceTicket: (String) -> Pair<String?, String?>
) {
    private val baseUrl: String = ChnuConfig.ECARD_BASE_URL

    @Volatile
    var loggedIn: Boolean = false
        private set

    /** 卡信息(从余额页提取的 cardAccNum/eWalletId/epId) */
    private val cardInfo = mutableMapOf<String, String>()

    /** SSO 登录一卡通系统 */
    fun login() {
        val ssoService = "$baseUrl/ssoLogin"
        val (ticket, redirectUrl) = getServiceTicket(ssoService)
        if (ticket == null || redirectUrl == null) {
            throw CampusException("无法获取一卡通 CAS ticket")
        }
        // Step 2: 访问 ssoLogin?ticket=xxx(跟随重定向)→ 自动提交表单 HTML
        try {
            val req1 = Request.Builder().url(redirectUrl)
                .header("User-Agent", ChnuConfig.UA).build()
            client.newCall(req1).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                // Step 3: 检测自动提交表单,POST /easytong_portal 完成 session
                if (text.contains("myForm") && text.contains("easytong_portal")) {
                    val req2 = Request.Builder().url(baseUrl)
                        .post(FormBody.Builder().build())
                        .header("User-Agent", ChnuConfig.UA).build()
                    client.newCall(req2).execute().use { /* 建立 session */ }
                }
            }
        } catch (e: IOException) {
            // 即使异常,session cookie 可能已设置,继续验证
        }
        // Step 4: 验证登录状态(访问余额页)
        val req = Request.Builder().url("$baseUrl/balance")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (text.contains("智慧一卡通－登录")) {
                throw CampusException("一卡通登录失败: 仍在登录页面")
            }
            if (text.contains("您好")) {
                extractCardInfo(text)
                loggedIn = true
                return
            }
            throw CampusException("一卡通系统登录失败")
        }
    }

    private fun checkSession() {
        if (!loggedIn) throw CampusException("一卡通系统未登录,请先调用 login()")
    }

    /** 从余额页 HTML 提取卡信息(余额/账户名/cardAccNum/eWalletId/epId) */
    private fun extractCardInfo(html: String) {
        listOf("cardAccNum", "eWalletId", "epId").forEach { name ->
            Regex("""(?:id|name)="?$name"?\s+value="([^"]*)"""").find(html)?.let {
                cardInfo[name] = it.groupValues[1]
            }
        }
        Regex("""<p class="money">([0-9.]+)""").find(html)?.let {
            cardInfo["balance"] = it.groupValues[1]
        }
        Regex("""您好[！!](.+?)<""").find(html)?.let {
            cardInfo["account_name"] = it.groupValues[1].trim()
        }
    }

    /** 查询余额,返回余额、账户名、卡号与银行卡列表 */
    fun getBalance(): EcardBalance {
        checkSession()
        val req = Request.Builder().url("$baseUrl/balance")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            extractCardInfo(html)
            // 同时获取银行卡信息
            val bankCards = getBankCards()
            return EcardBalance(
                balance = cardInfo["balance"] ?: "",
                accountName = cardInfo["account_name"] ?: "",
                cardNo = cardInfo["cardAccNum"] ?: "",
                bankCards = bankCards
            )
        }
    }

    /**
     * 查询已签约银行卡列表。
     * 解析 /bankCard 页面的 bankCardTypeName-{id}、bankCardNum-{id}、bankCard-{id} 元素及 radio input 完整卡号。
     */
    fun getBankCards(): List<BankCard> {
        checkSession()
        val req = Request.Builder().url("$baseUrl/bankCard")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            val cards = mutableListOf<BankCard>()
            // 提取 bank-tip
            val bankTip = Regex("""id="bank-tip"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
            // 提取卡 ID 列表(从 bankCardTypeName-{id} 模式)
            val cardIds = mutableSetOf<String>()
            Regex("""id="bankCardTypeName-(\d+)"""").findAll(html).forEach {
                cardIds.add(it.groupValues[1])
            }
            Regex("""id="bankCardNum-(\d+)"""").findAll(html).forEach {
                cardIds.add(it.groupValues[1])
            }
            cardIds.forEach { id ->
                val cardType = Regex("""id="bankCardTypeName-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                val masked = Regex("""id="bankCardNum-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                val short = Regex("""id="bankCard-$id"[^>]*>([^<]*)""").find(html)?.groupValues?.get(1)?.trim() ?: ""
                // 完整卡号:从 radio input 中按 id 关联查找
                // radio input 的 id 格式通常为 "bankCardRadio-{id}" 或 name 包含卡 id
                val full = Regex("""<input[^>]*type="radio"[^>]*(?:id|name)="[^"]*$id[^"]*"[^>]*value="([^"]+)\"""").find(html)?.groupValues?.get(1)
                    ?: Regex("""<input[^>]*type="radio"[^>]*value="([^"]+)"[^>]*(?:id|name)="[^"]*$id[^"]*""""").find(html)?.groupValues?.get(1)
                    ?: ""
                cards.add(BankCard(id, cardType, masked, short, full, bankTip))
            }
            return cards
        }
    }

    /**
     * 查询房间电费(剩余金额与昨日用电)。
     * POST /payFee/getBalance,返回 JSON {balance, eleDailyUsed}。
     */
    fun getRoomElectricity(areaNo: String, buildingNo: String, roomNo: String, itemNum: String = "3"): RoomElectricity {
        checkSession()
        // Step 1: 先初始化缴费页面(否则 getBalance 会返回空)
        val initForm = FormBody.Builder().add("itemNum", itemNum).build()
        val initReq = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(initForm)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        try {
            client.newCall(initReq).execute().use { /* 初始化 session */ }
        } catch (_: Exception) { /* 即使异常也继续 */ }
        // Step 2: 查询房间余额,返回 {"feeDate":{"balance":797.0,"eleDailyUsed":0.0},"ajaxState":"3"}
        val jsonBody = JSONObject()
            .put("itemNum", itemNum)
            .put("areano", areaNo)
            .put("buildingno", buildingNo)
            .put("roomno", roomNo)
            .toString()
        val form = FormBody.Builder().add("data", jsonBody).build()
        val req = Request.Builder().url("$baseUrl/payFee/getBalance").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return RoomElectricity("", "")
            val feeDate = json.optJSONObject("feeDate") ?: json
            return RoomElectricity(
                balance = feeDate.optString("balance", json.optString("balance", "")),
                dailyUsed = feeDate.optString("eleDailyUsed", feeDate.optString("dailyUsed", ""))
            )
        }
    }

    /**
     * 查询账单记录。
     * @param startDate 开始日期(YYYY-MM-DD),默认 7 天前
     * @param endDate 结束日期(YYYY-MM-DD),默认今天
     * @param recordType 记录类型:1=消费,2=充值,3=补助,4=互转,""=全部
     *
     * 全部类型(""):分别查询消费(1)和充值(2)并合并。
     * 因为全类型查询页面不返回商户/地点/银行名称字段,只有按类型查才有。
     */
    fun getBillRecords(
        startDate: String? = null,
        endDate: String? = null,
        recordType: String = ""
    ): List<BillRecord> {
        checkSession()
        val now = LocalDate.now()
        val end = endDate ?: now.toString()
        val start = startDate ?: now.minusDays(7).toString()
        // 全部类型:分别查消费和充值,合并结果(两类页面字段最全)
        if (recordType.isEmpty()) {
            val consume = queryBillByType(start, end, "1", "消费记录")
            val topup = queryBillByType(start, end, "2", "充值记录")
            // 按时间倒序合并
            return (consume + topup).sortedByDescending { it.time }
        }
        val typeName = mapOf(
            "1" to "消费记录", "2" to "充值记录",
            "3" to "补助流水", "4" to "互转流水"
        )[recordType] ?: "记录"
        return queryBillByType(start, end, recordType, typeName)
    }

    /** 按类型查询账单(内部):GET /bill 取 hidden → POST /bill 带分页参数 */
    private fun queryBillByType(start: String, end: String, typeFlag: String, typeName: String): List<BillRecord> {
        // Step 1: GET /bill 获取隐藏字段
        val reqGet = Request.Builder().url("$baseUrl/bill")
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        val hidden = mutableMapOf<String, String>()
        client.newCall(reqGet).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                if (name != null) hidden[name] = value
            }
        }
        // Step 2: POST /bill 查询(start/end/size=1000 一次取全部)
        val form = FormBody.Builder()
            .add("timeInterval", hidden["timeInterval"] ?: "")
            .add("typeFlag", typeFlag)
            .add("startdealTime", start)
            .add("enddealTime", end)
            .add("payepId", hidden["payepId"] ?: "")
            .add("eWalletId", hidden["eWalletId"] ?: "")
            .add("cardaccNum", hidden["cardaccNum"] ?: "")
            .add("start", "1")
            .add("end", "1000")
            .add("size", "1000")
            .build()
        val req = Request.Builder().url("$baseUrl/bill").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/bill")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            return parseBillHtml(html, typeName)
        }
    }

    /**
     * 一卡通充值(银校转账)
     *
     * 流程对齐 Python 参考实现(chnu/ecard.py top_up):
     *  1. 确保卡信息(余额页解析 cardAccNum/eWalletId/epId)
     *  2. 校验查询密码(RSA),并取回加密后的密码
     *  3. POST /topUp/confirm 获取确认页(form#pay-form),
     *     该接口服务器会以 chunked 方式提前断开连接,需用 [readBodyTolerant] 容错读取
     *  4. 解析 form#pay-form 的 action 与隐藏字段
     *  5. RSA 加密密码 → POST 到 pay-form action 提交充值
     *
     * @param amount 充值金额(元),如 50.00
     * @param queryPassword 查询密码(通常为身份证后六位)
     * @param bankCardNum 签约银行卡完整卡号,为空时自动取第一张签约卡
     * @throws CampusException 未登录/密码错误/无签约卡/服务器内部错误
     */
    /**
     * 获取充值页面信息,动态提取签约银行卡 ID / 银行 logo / 钱包名称。
     * 对齐 Python ecard.py get_topup_page。
     * 之前硬编码 bankId="25"/logoFileName="",对非默认银行卡用户会导致充值失败。
     */
    /**
     * 获取充值页面信息,解析签约银行卡列表(含完整卡号)及钱包名称。
     * 对齐 Python ecard.py get_topup_page:签约卡来自充值页的
     * <label id="bankCard-{id}">…<input type="radio" name="inlineRadioOptions" value="完整卡号">…</label>。
     * 注意:完整卡号必须从充值页解析;/bankCard 页通常只给脱敏号,直接用于充值会失败。
     */
    private fun getTopupPage(): TopupPageInfo {
        if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
        val form = FormBody.Builder()
            .add("cardAccNum", cardInfo["cardAccNum"] ?: "")
            .add("eWalletId", cardInfo["eWalletId"] ?: "")
            .add("epId", cardInfo["epId"] ?: "")
            .build()
        val req = Request.Builder().url("$baseUrl/topUp").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            // 钱包名称(recharge-box)
            val walletName = Regex("""<div[^>]*class="recharge-box"[^>]*>([\s\S]*?)</div>""")
                .find(html)?.groupValues?.get(1)?.let { ChnuHtmlHelpers.stripTags(it).trim() } ?: ""
            // 签约银行卡列表:遍历 <label id="bankCard-{id}">…</label>,
            // 内部含 radio(input value=完整卡号)与 <i/img logofilename=…>
            val cards = mutableListOf<BankCard>()
            var firstLogo = ""
            val labelRegex = Regex("""id="bankCard-(\d+)"[^>]*>([\s\S]*?)</label>""")
            val fullRegex = Regex("""value="(\d{11,})"""")
            val logoRegex = Regex("""logofilename="([^"]+)"""")
            labelRegex.findAll(html).forEach { m ->
                val id = m.groupValues[1]
                val inner = m.groupValues[2]
                val full = fullRegex.find(inner)?.groupValues?.get(1) ?: return@forEach
                val short = ChnuHtmlHelpers.stripTags(inner).trim()
                val logo = logoRegex.find(inner)?.groupValues?.get(1) ?: ""
                if (firstLogo.isEmpty()) firstLogo = logo
                cards.add(BankCard(id, "", "", short, full, "", logo))
            }
            val bankId = cards.firstOrNull()?.cardId ?: "25"
            // 提取所有隐藏表单字段(对齐 Python _extract_card_info),后续提交 /topUp/confirm 时必须携带
            val formFields = mutableMapOf<String, String>()
            Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                if (name != null) formFields[name] = value
            }
            return TopupPageInfo(bankId, firstLogo, walletName, cards, formFields)
        }
    }

    /**
     * 获取充值可用的签约银行卡列表(含完整卡号),供 UI 选卡步骤使用。
     * 对齐 Python ecard.py top_up 的"先展示签约卡让用户选择"流程。
     */
    fun getTopupBankCards(): List<BankCard> {
        checkSession()
        if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
        return getTopupPage().bankCards
    }

    fun topUp(amount: Double, queryPassword: String, bankCardNum: String = ""): PayResult {
        checkSession()
        if (queryPassword.isBlank()) throw CampusException("请输入密码")
        val amountStr = "%.2f".format(amount)
        // 确保卡信息(余额页解析 cardAccNum/eWalletId/epId)
        if (cardInfo["cardAccNum"].isNullOrEmpty()) getBalance()
        val cardAccNum = cardInfo["cardAccNum"] ?: "35719"
        val eWalletId = cardInfo["eWalletId"] ?: "1"
        val epId = cardInfo["epId"] ?: "1"

        // Step 1: 校验查询密码(RSA),并取回加密后的密码(后续提交复用,避免重复取公钥)
        val encryptedPwd = rsaEncryptPassword(queryPassword)

        // 获取充值页面信息(含签约银行卡列表)
        val topupPage = getTopupPage()
        // 确定签约银行卡:优先使用 UI 选中的卡号,否则取充值页解析到的第一张可用卡
        val bankNum = if (bankCardNum.isNotEmpty()) bankCardNum
        else topupPage.bankCards.firstOrNull { it.cardNumberFull.length > 10 }?.cardNumberFull
            ?: topupPage.bankCards.firstOrNull { it.cardNumberFull.isNotEmpty() }?.cardNumberFull
            ?: ""
        if (bankNum.isEmpty()) {
            throw CampusException("没有可用的签约银行卡,无法进行银校转账")
        }
        // 选中卡对应的 bankId / logo(不带正确 bankId 会导致充值失败)
        val selCard = topupPage.bankCards.firstOrNull { it.cardNumberFull == bankNum }
        val usedBankId = selCard?.cardId ?: topupPage.bankId
        val usedLogo = selCard?.logoFileName ?: topupPage.logoFileName
        // Step 3: 构建充值步骤数据并 POST /topUp/confirm
        //    关键:必须携带 /topUp 页面的隐藏表单字段,否则服务端返回内部错误页("服务不可用")
        val step1 = mutableMapOf(
            "epId" to epId,
            "cardAccNum" to cardAccNum,
            "eWalletId" to eWalletId,
            "eWalletName" to topupPage.walletName,
            "bankCardNum" to bankNum,
            "bankCardNumWithAsterisk" to "**${bankNum.takeLast(4)}",
            "payMode" to "1",
            "bankId" to usedBankId,
            "logoFileName" to usedLogo,
            "payChannelId" to "",
            "topUpDisableTime" to ""
        )
        // 合并 /topUp 页面表单隐藏字段(服务端要求)
        topupPage.formFields.forEach { (k, v) -> if (k !in step1) step1[k] = v }
        // 获取限额信息(dayLimit/oneLimit/moneyMax/continueTopUpMoney)
        try {
            val limitReq = Request.Builder().url("$baseUrl/public/queryContinueTopUpMoney").post(
                FormBody.Builder()
                    .add("epId", epId)
                    .add("cardAccNum", cardAccNum)
                    .add("eWalletId", eWalletId)
                    .build()
            ).header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/topUp")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(limitReq).execute().use { resp ->
                val json = ChnuHtmlHelpers.parseJsonObject(readBodyTolerant(resp))
                val map = json?.optJSONObject("retMap")
                map?.optString("dayLimit")?.takeIf { it.isNotEmpty() }?.let { step1["dayLimit"] = it }
                map?.optString("oneLimit")?.takeIf { it.isNotEmpty() }?.let { step1["oneLimit"] = it }
                map?.optString("continueTopUpMoney")?.takeIf { it.isNotEmpty() }
                    ?.let { step1["continueTopUpMoney"] = it }
                map?.optString("moneyMax")?.takeIf { it.isNotEmpty() }?.let { step1["moneyMax"] = it }
            }
        } catch (_: Exception) { /* 限额信息可选 */ }
        // 获取银行禁止充值时间
        try {
            val disableReq = Request.Builder().url("$baseUrl/public/queryTopUpDisableTime").post(
                FormBody.Builder().add("epId", epId).add("bankId", usedBankId).build()
            ).header("User-Agent", ChnuConfig.UA)
                .header("Referer", "$baseUrl/topUp")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(disableReq).execute().use { resp ->
                val json = ChnuHtmlHelpers.parseJsonObject(readBodyTolerant(resp))
                val begin = json?.optString("disableBegin", "") ?: ""
                val end = json?.optString("disableEnd", "") ?: ""
                if (begin.isNotEmpty() && end.isNotEmpty()) {
                    step1["topUpDisableTime"] = "$begin-$end"
                }
            }
        } catch (_: Exception) { /* 禁用时间可选 */ }

        // Step 3: POST /topUp/confirm 获取确认页(该接口会提前断开 chunked 连接)
        val confirmReq = Request.Builder().url("$baseUrl/topUp/confirm").post(
            FormBody.Builder().apply { step1.forEach { (k, v) -> add(k, v) } }.build()
        ).header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/topUp")
            .build()
        val confirmHtml = client.newCall(confirmReq).execute().use { readBodyTolerant(it) }
        if (confirmHtml.isEmpty()) {
            return PayResult(false, "获取充值确认页面失败(服务器无响应)", amountStr)
        }
        // 只检测明确的服务器 500 错误(不检测业务文案,业务错误由最终提交页解析)
        if (confirmHtml.contains("java.lang") || confirmHtml.contains("NullPointerException")) {
            val errMsg = extractPageErrorMessage(confirmHtml)
                ?: "一卡通充值服务暂时不可用(服务器内部错误),请稍后重试"
            return PayResult(false, errMsg, amountStr)
        }
        // 解析 pay-form 的 action 与隐藏字段(确认页是正常步骤,不是错误)
        val (formAction, hiddenFields) = parsePayForm(confirmHtml)
        // 确定提交地址:优先使用 pay-form 的 action,解析失败则回退到 /topUp/quickPay
        val submitUrl = if (formAction.isNotEmpty()) resolveSubmitUrl(formAction)
            else resolveSubmitUrl("$baseUrl/topUp/quickPay")

        // 提交充值数据
        val postData = mutableMapOf<String, String>()
        if (hiddenFields.isNotEmpty()) postData.putAll(hiddenFields)
        postData["cardAccNum"] = cardAccNum
        postData["eWalletId"] = eWalletId
        postData["epId"] = epId
        postData["bankId"] = usedBankId
        postData["bankCardNum"] = bankNum
        postData["payMode"] = "1"
        postData["money"] = amountStr
        postData["payPwd"] = encryptedPwd

        val submitReq = Request.Builder().url(submitUrl).post(
            FormBody.Builder().apply { postData.forEach { (k, v) -> add(k, v) } }.build()
        ).header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/topUp/confirm")
            .build()
        val finalHtml = client.newCall(submitReq).execute().use { readBodyTolerant(it) }
        return parsePayResult(finalHtml, amountStr)
    }

    /**
     * 获取生活缴费校区列表
     * @param itemNum 缴费项目编号:"2"=常工电控缴费,"3"=滨湖1-5宿舍照明电费
     */
    fun getPayAreas(itemNum: String = "3"): List<PayArea> {
        checkSession()
        val form = FormBody.Builder().add("itemNum", itemNum).build()
        val req = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            val areas = mutableListOf<PayArea>()
            // 提取 select name="areano" 的 option 列表
            val selectRegex = Regex("""<select[^>]*(?:name|id)="areano"[^>]*>([\s\S]*?)</select>""")
            val selectContent = selectRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
            val optionRegex = Regex("""<option[^>]*value="([^"]*)"[^>]*>([^<]*)</option>""")
            optionRegex.findAll(selectContent).forEach { m ->
                val id = m.groupValues[1].trim()
                val name = m.groupValues[2].trim()
                if (id.isNotEmpty()) areas.add(PayArea(id, name))
            }
            return areas
        }
    }

    /** 获取楼栋列表(AJAX) */
    fun getBuildings(areaNo: String, itemNum: String = "3"): List<PayBuilding> {
        checkSession()
        val jsonBody = JSONObject().put("itemNum", itemNum).put("areano", areaNo).toString()
        val form = FormBody.Builder().add("data", jsonBody).build()
        val req = Request.Builder().url("$baseUrl/payFee/getbuilding").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return parseDormList(text, "buildingList")
        }
    }

    /** 获取房间列表(AJAX) */
    fun getRooms(areaNo: String, buildingNo: String, itemNum: String = "3"): List<PayBuilding> {
        checkSession()
        val jsonBody = JSONObject().put("itemNum", itemNum)
            .put("areano", areaNo)
            .put("buildingno", buildingNo)
            .toString()
        val form = FormBody.Builder().add("data", jsonBody).build()
        val req = Request.Builder().url("$baseUrl/payFee/getroom").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            return parseDormList(text, "roomList")
        }
    }

    /**
     * 获取缴费表单字段(类型编号/防重放 token/限额/项目名称等)。
     * 对齐 Python ecard.py get_pay_fee_page + get_pay_fee_form。
     * 这些字段缺失会导致服务端拒绝缴费提交。
     */
    private fun getPayFeeForm(itemNum: String): PayFeeForm {
        val form = FormBody.Builder().add("itemNum", itemNum).build()
        val req = Request.Builder().url("$baseUrl/payFee/showItemListPayPage").post(form)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            // 提取隐藏字段
            val fields = mutableMapOf<String, String>()
            Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(html).forEach { tag ->
                val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                if (name != null) fields[name] = value
            }
            // 项目名称
            val knownItemNames = mapOf("2" to "常工电控缴费", "3" to "滨湖1-5宿舍照明电费")
            var itemName = knownItemNames[itemNum] ?: ""
            val nameMatch = Regex("""(常工电控缴费|滨湖1-5宿舍照明电费|[^<]{1,20}(?:电费|电控|照明)[^<]{0,20})""").find(html)
            if (nameMatch != null) itemName = nameMatch.groupValues[1].trim()
            // ★ 实时钱包余额:网页端 itemPay() 用 $(".active #balance").html() 作为 ewalletMenoy 提交。
            //   之前用 cardInfo["balance"](登录时的快照),用户消费后会过期,服务端校验 balance-payMoney<moneyMin 失败→"钱包余额低于下限"。
            //   现改为从缴费页面 <samp id="balance"> 83.02 </samp> 实时提取。
            val balanceMatch = Regex("""<samp[^>]*id="balance"[^>]*>\s*([0-9.]+)\s*</samp>""").find(html)
            val pageBalance = balanceMatch?.groupValues?.get(1)?.trim() ?: ""
            val moneyMin = fields["moneyMin"] ?: "0"
            return PayFeeForm(
                typeNum = fields["typeNum"] ?: "",
                token = fields["token"] ?: "",
                limitMoney = fields["limitMoney"] ?: "",
                limitMode = fields["limitMode"] ?: "",
                itemName = itemName,
                balance = pageBalance,
                moneyMin = moneyMin,
                eWalletId = fields["eWalletId"] ?: "1",
                eWalletName = fields["eWalletName"] ?: "",
                cardAccNum = fields["cardAccNum"] ?: ""
            )
        }
    }

    /**
     * 生活缴费(宿舍电费等)。
     *
     * 流程严格对齐网页端 itemPay() JS(lifeservice.js 第408行):
     *  1. POST /payFee/showItemListPayPage 获取缴费页面表单字段
     *  2. POST /publiccombo/keyPair 获取 RSA 公钥
     *  3. RSA 加密支付密码
     *  4. POST /publiccombo/checkpaypwd 校验支付密码(字段 paypassword)
     *     - ajaxState=3(SUCCESS)/4(PASSED)=通过; 2(FAILED)=密码错误
     *  5. POST /payFee/payItemlist 提交缴费(字段对齐 elementObj)
     *
     * ★ ajaxState 语义(对齐 portalcontants.js AJAXSTATE 常量):
     *   NOLOGIN:0 / NOAUTH:1 / FAILED:2 / SUCCESS:3 / PASSED:4
     *
     * ★ 提交字段名严格对齐 lifeservice.js itemPay() 的 elementObj:
     *   payMenoy(注意拼写,非 itemMoney), ewalletMenoy, cardaccNum(小写a),
     *   eWalletId, eWalletName, typeNum, itemName, token, itemNum
     *
     * @param queryPassword 支付密码(6位数字)
     */
    fun payFee(
        areaNo: String,
        buildingNo: String,
        roomNo: String,
        amount: Double,
        queryPassword: String,
        itemNum: String = "3"
    ): PayResult {
        checkSession()
        val amountStr = "%.2f".format(amount)

        // Step 1: 获取缴费表单字段
        val feeForm = getPayFeeForm(itemNum)
        val limitMoney = feeForm.limitMoney.toDoubleOrNull() ?: 0.0
        if (limitMoney > 0 && amount > limitMoney) {
            return PayResult(false, "缴费金额超过限额(${feeForm.limitMoney}元)", amountStr)
        }

        // Step 2: RSA 加密支付密码
        val encryptedPwd = rsaEncryptPassword(queryPassword)

        // Step 3: 校验支付密码(对齐网页端 /publiccombo/checkpaypwd)
        val checkForm = FormBody.Builder().add("paypassword", encryptedPwd).build()
        val checkReq = Request.Builder().url("$baseUrl/publiccombo/checkpaypwd").post(checkForm)
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/payFee/showItemListPayPage")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(checkReq).execute().use { resp ->
            val text = readBodyTolerant(resp)
            val json = ChnuHtmlHelpers.parseJsonObject(text)
            val state = json?.optString("ajaxState", "") ?: ""
            val msg = json?.optString("msg", "") ?: ""
            // ajaxState: 3=SUCCESS, 4=PASSED 通过; 2=FAILED 密码错误; 0=NOLOGIN; 1=NOAUTH
            if (state != "3" && state != "4") {
                return PayResult(false,
                    when (state) {
                        "2" -> msg.ifEmpty { "支付密码错误" }
                        "0" -> "登录已过期,请重新登录"
                        "1" -> "无权限"
                        else -> msg.ifEmpty { "支付密码校验失败(ajaxState=$state)" }
                    }, amountStr)
            }
        }

        // Step 4: 提交缴费(字段名严格对齐 lifeservice.js itemPay() elementObj)
        //   payMenoy(拼写), ewalletMenoy, cardaccNum(小写a), eWalletId, eWalletName,
        //   typeNum, itemName, token, itemNum, areano, buildingno, roomno
        val formBuilder = FormBody.Builder()
            .add("itemNum", itemNum)
            .add("eWalletId", feeForm.eWalletId)
            .add("typeNum", feeForm.typeNum)
            .add("itemName", feeForm.itemName)
            .add("payMenoy", amountStr)
            .add("ewalletMenoy", feeForm.balance)
            .add("eWalletName", feeForm.eWalletName)
            .add("areano", areaNo)
            .add("buildingno", buildingNo)
            .add("roomno", roomNo)
        if (feeForm.cardAccNum.isNotEmpty()) {
            formBuilder.add("cardaccNum", feeForm.cardAccNum)
        }
        if (feeForm.token.isNotEmpty()) {
            formBuilder.add("token", feeForm.token)
        }

        val req = Request.Builder().url("$baseUrl/payFee/payItemlist").post(formBuilder.build())
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/payFee/showItemListPayPage")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val html = readBodyTolerant(resp)
                parsePayResult(html, amountStr)
            }
        } catch (e: Exception) {
            PayResult(false, "缴费提交失败: ${e.message}", amountStr)
        }
    }

    /** RSA加密支付密码(获取公钥并加密,供 checkpaypwd/payItemlist 使用) */
    private fun rsaEncryptPassword(queryPassword: String): String {
        val reqKey = Request.Builder().url("$baseUrl/publiccombo/keyPair").post(FormBody.Builder().build())
            .header("User-Agent", ChnuConfig.UA)
            .header("Referer", "$baseUrl/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        var modulus = ""
        var exponent = ""
        client.newCall(reqKey).execute().use { resp ->
            val text = readBodyTolerant(resp)
            val json = ChnuHtmlHelpers.parseJsonObject(text) ?: throw CampusException("获取RSA公钥失败: $text")
            val keyMap = json.optJSONObject("publicKeyMap") ?: json
            modulus = keyMap.optString("modulus", "")
            exponent = keyMap.optString("exponent", "")
        }
        if (modulus.isEmpty()) throw CampusException("RSA公钥为空")
        return rsaEncrypt(queryPassword, modulus, exponent)
    }

    /** 解析楼栋/房间列表 JSON(返回 dormList/buildingList/roomList) */
    private fun parseDormList(text: String, field: String): List<PayBuilding> {
        val json = ChnuHtmlHelpers.parseJsonObject(text) ?: return emptyList()
        val arr = json.optJSONArray(field) ?: json.optJSONArray("dormList") ?: return emptyList()
        val result = mutableListOf<PayBuilding>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            result.add(PayBuilding(
                id = it.optString("no", it.optString("buildingNo", "")),
                name = it.optString("name", it.optString("buildingName", ""))
            ))
        }
        return result
    }

    /** 解析充值/缴费结果(兼容 JSON ajaxState 与 HTML 文本) */
    private fun parsePayResult(html: String, amount: String): PayResult {
        val trimmed = html.trim()
        // 优先按 JSON 解析(提交端点可能直接返回 {"ajaxState":"3","msg":"..."})
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val json = ChnuHtmlHelpers.parseJsonObject(trimmed)
            if (json != null) {
                val state = json.optString("ajaxState", "")
                val msg = json.optString("msg", "")
                return when (state) {
                    // 对齐 portalcontants.js AJAXSTATE: 3=SUCCESS, 4=PASSED 成功; 2=FAILED; 0=NOLOGIN; 1=NOAUTH
                    "3", "4" -> PayResult(true, msg.ifEmpty { "操作成功" }, amount)
                    "0", "1", "2" -> PayResult(false, msg.ifEmpty { "操作失败" }, amount)
                    else -> if (msg.isNotEmpty()) PayResult(false, msg, amount)
                    else parsePayResultHtml(trimmed, amount)
                }
            }
        }
        return parsePayResultHtml(trimmed, amount)
    }

    /** 从 HTML 文本解析充值/缴费结果(对齐网页端 result-box 结构) */
    private fun parsePayResultHtml(html: String, amount: String): PayResult {
        // 网页端结果页格式:
        //   <div class="result-box"><i class="iconfont error">...</i>
        //     <div class="result-info"><p class="one-lined"><strong>错误信息</strong></p></div></div>
        val isErrorPage = Regex("""iconfont\s+error""").containsMatchIn(html)
        // 提取 one-lined 里的 <strong> 文本(成功和失败都用这个结构)
        val oneLinedMsg = Regex("""one-lined[^>]*>\s*<strong>([^<]+)</strong>""").find(html)?.groupValues?.get(1)?.trim()
        val hasSuccessKw = Regex("""(缴费成功|充值成功|操作成功|支付成功|提交成功)""").containsMatchIn(html)
        val hasFailKw = Regex("""(缴费失败|密码错误|充值失败|操作失败|验证失败|重复提交|余额不足|低于下限)""").containsMatchIn(html)

        // 判断成功/失败: error 页面=失败; 成功关键词=成功; 否则看 one-lined 内容
        val success = !isErrorPage && !hasFailKw && (hasSuccessKw || oneLinedMsg?.let {
            !Regex("""(失败|错误|不足|下限|重复|无效|过期)""").containsMatchIn(it)
        } == true)

        val msg = oneLinedMsg
            ?: Regex("""alert\s*[(（]\s*["'`]([^"'`]+)["'`]""").find(html)?.groupValues?.get(1)
            ?: Regex("""(缴费成功|充值成功|操作成功|提交成功)""").find(html)?.groupValues?.get(1)
            ?: if (success) "缴费成功" else if (isErrorPage) "缴费失败" else "操作失败"
        return PayResult(success, msg, amount)
    }

    /**
     * 容错读取响应体。一卡通部分接口(如 /topUp/confirm)会以 chunked 方式提前
     * 断开连接,OkHttp 在读取完整分块流时会抛出 IOException(message 常为 null)。
     * 这里改用流式读取,捕获截断异常后保留已读取的部分内容
     * (对齐 Python ecard.py 的 _request_chunked)。
     */
    private fun readBodyTolerant(resp: Response): String {
        val body = resp.body ?: return ""
        val source = body.source()
        val out = StringBuilder()
        val buf = okio.Buffer()
        try {
            while (true) {
                val n = source.read(buf, 8192L)
                if (n == -1L) break
                if (n > 0L) out.append(buf.readString(Charsets.UTF_8))
            }
        } catch (e: IOException) {
            // 截断/分块连接提前关闭:保留已读取的部分数据,继续解析
        }
        return out.toString()
    }

    /** 从确认页 HTML 解析 pay-form 的 action 与隐藏字段 */
    private fun parsePayForm(html: String): Pair<String, Map<String, String>> {
        var action = ""
        val hidden = mutableMapOf<String, String>()
        var found = false
        Regex("""<form\b([\s\S]*?)</form>""").findAll(html).forEach { m ->
            val formTag = m.groupValues[1]
            val formFull = m.value
            val isPayForm = formTag.contains("""id="pay-form"""") || formTag.contains("""id='pay-form'""")
            if (isPayForm || !found) {
                val act = Regex("""action="([^"]*)"""").find(formTag)?.groupValues?.get(1) ?: ""
                val fields = mutableMapOf<String, String>()
                Regex("""<input[^>]*type="hidden"[^>]*>""").findAll(formFull).forEach { tag ->
                    val name = Regex("""name="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: Regex("""id="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    val value = Regex("""value="([^"]*)"""").find(tag.value)?.groupValues?.get(1) ?: ""
                    if (name != null) fields[name] = value
                }
                if (isPayForm) { action = act; hidden.putAll(fields); found = true }
                else if (!found) { action = act; hidden.putAll(fields) }
            }
        }
        return action to hidden
    }

    /** 从错误 HTML 页面中提取可读错误消息 */
    private fun extractPageErrorMessage(html: String): String? {
        // 1. JS alert 弹窗
        Regex("""alert\(["']([^"']{2,})["']""").find(html)?.groupValues?.get(1)?.let { return it }
        // 2. 页面醒目文字(如"充值失败，原因:银行服务时间为00:00~20:00")
        Regex("""(?:充值|缴费)?失败[，。,.\s]*原因[：:]\s*([^<。]{5,})""").find(html)
            ?.groupValues?.get(1)?.let { return "充值失败: $it" }
        // 3. <div class="error"> 等错误提示容器
        Regex("""class="[^"]*error[^"]*"[^>]*>([^<]{3,})<""").find(html)
            ?.groupValues?.get(1)?.let { return it.trim() }
        // 4. 包含"失败"或"错误"的醒目文本
        Regex("""(充值失败|缴费失败|操作失败|密码错误|余额不足|服务时间[^<。]{0,30})""").find(html)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    /** 将确认页 pay-form 的 action 解析为绝对 URL */
    private fun resolveSubmitUrl(action: String): String {
        if (action.startsWith("http://") || action.startsWith("https://")) return action
        if (action.isEmpty()) return "$baseUrl/topUp/confirm"
        if (action.startsWith("/")) {
            val base = baseUrl.toHttpUrl()
            val port = if (base.port != 80 && base.port != 443 && base.port > 0) ":${base.port}" else ""
            return "${base.scheme}://${base.host}$port$action"
        }
        return "$baseUrl/$action"
    }

    /**
     * RSA 加密(一卡通查询密码)。
     *
     * 对齐网页端 security.js 的 RSAUtils.encryptedString:
     *   ● modulus/exponent 十六进制 → BigInteger
     *   ● 明文分块,每块 2*(modulusDigits-1) 字节,零填充
     *   ● 逐块 modular exponentiation(无 PKCS1 填充)
     *   ● 输出 HEX 字符串,块间空格分隔
     *
     * 注意:这里不使用标准 PKCS1 填充,必须用 Raw RSA 对齐 JS 代码。
     */
    private fun rsaEncrypt(plain: String, modulusHex: String, exponentHex: String): String {
        try {
            val modulus = java.math.BigInteger(modulusHex, 16)
            val exponent = java.math.BigInteger(exponentHex, 16)
            // chunkSize 对齐 JS: 2 * biHighIndex(this.m) = modulusBytes - 2
            val modulusBytes = (modulus.bitLength() + 7) / 8
            val chunkSize = modulusBytes - 2
            val bytes = plain.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val block = ByteArray(chunkSize)
                var j = 0
                while (j < chunkSize && i + j < bytes.size) {
                    block[j] = bytes[i + j]
                    j++
                }
                // JS digits[j] = a[k] + a[k+1]*256, 等价于整个 block 反转字节序
                block.reverse()
                val m = java.math.BigInteger(1, block)
                val enc = m.modPow(exponent, modulus)
                val hex = enc.toString(16)
                val expectedHexLen = modulus.bitLength() / 4
                if (hex.length < expectedHexLen) {
                    sb.append("0".repeat(expectedHexLen - hex.length))
                }
                sb.append(hex)
                if (i + chunkSize < bytes.size) sb.append(" ")
                i += chunkSize
            }
            return sb.toString()
        } catch (e: Exception) {
            throw CampusException("RSA加密失败: ${e.message}")
        }
    }

    /** 解析账单 HTML 表格为记录列表。
     *  不依赖固定列索引,通过 class 属性定位关键字段,避免列数变化导致解析错误 */
    private fun parseBillHtml(html: String, typeName: String): List<BillRecord> {
        val records = mutableListOf<BillRecord>()
        val tableMatch = Regex("""<table[^>]*>([\s\S]*?)</table>""").find(html)
        val tableContent = tableMatch?.groupValues?.get(1) ?: return emptyList()
        val rows = Regex("""<tr[^>]*>([\s\S]*?)</tr>""").findAll(tableContent).toList()
        if (rows.size < 2) return emptyList()
        for (i in 1 until rows.size) {
            val rawRow = rows[i].value
            // 1. 变动后余额:从 HTML 注释提取 <!-- <td class="text-right">83.01</td> -->
            val balanceAfter = Regex("""<!--\s*<td[^>]*>\s*([0-9.]+)\s*</td>\s*-->""").find(rawRow)
                ?.groupValues?.get(1)?.trim() ?: ""
            // 2. 变化金额:从 <strong class="price">-0.01</strong> 提取
            val amount = Regex("""<strong[^>]*class="price"[^>]*>\s*([^<]+?)\s*</strong>""").find(rawRow)
                ?.groupValues?.get(1)?.trim() ?: ""
            // 3. 时间:从 <td class="time">2026-07-21<p>21:41</p></td> 提取
            val timeHtml = Regex("""<td[^>]*class="time"[^>]*>([\s\S]*?)</td>""").find(rawRow)
                ?.groupValues?.get(1) ?: ""
            val time = ChnuHtmlHelpers.stripTags(timeHtml.replace(Regex("<p[^>]*>"), " ").replace("</p>", "")).trim()
            // 4. 描述:移除注释后,取所有非空 td 文本(排除时间/金额/操作列),组合为描述
            val rowHtml = rawRow.replace(Regex("""<!--[\s\S]*?-->"""), "")
            val allCells = Regex("""<td[^>]*>([\s\S]*?)</td>""").findAll(rowHtml)
                .map { c ->
                    ChnuHtmlHelpers.stripTags(c.groupValues[1].replace(Regex("<p[^>]*>"), " ").replace("</p>", "")).trim()
                }.toList()
            if (allCells.size < 2) continue
            if (allCells.all { it.isEmpty() }) continue
            // 描述 = 非"单位/操作/详情"的非空文本,排除时间和金额
            val descParts = allCells.filter { cell ->
                cell.isNotEmpty() &&
                    cell != time &&
                    cell != amount &&
                    !cell.contains("详情") &&
                    !cell.contains("淮北师范") &&  // 排除单位名
                    !cell.contains("交易成功") &&
                    !cell.contains("待领取")
            }
            val desc = descParts.joinToString(" · ").ifEmpty { typeName }
            records.add(BillRecord(
                time = time,
                amount = amount,
                description = desc,
                type = typeName,
                balanceAfter = balanceAfter
            ))
        }
        return records
    }
}
