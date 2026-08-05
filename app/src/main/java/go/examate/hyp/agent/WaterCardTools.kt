package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.watercard.YxtApi
import go.examate.hyp.watercard.YxtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 乐校通水卡工具 — 供 all in agent 调用查询水卡余额。
 *
 * 乐校通(lxt6.cn)是独立于校园服务的第三方水电平台,登录含验证码,无法在 Agent 内自动登录;
 * 因此本工具复用用户在「水卡」页面登录后持久化的会话(watercard_session),只做只读余额查询。
 * 未登录时返回明确提示,引导用户先去水卡页面登录。
 */
object WaterCardTools {

    /** 从水卡会话存储恢复 YxtSession(与 WaterCardViewModel.restoreSession 保持一致) */
    private fun loadSession(context: Context): YxtSession? {
        val p = context.getSharedPreferences("watercard_session", Context.MODE_PRIVATE)
        val token = p.getString("token", "") ?: ""
        if (token.isBlank()) return null
        val s = YxtSession(
            token = token,
            userId = p.getString("userId", "") ?: "",
            customerId = p.getString("customerId", "") ?: "",
            customerName = p.getString("customerName", "") ?: "",
            balanceId = p.getString("balanceId", "") ?: "",
            projectId = p.getString("projectId", "") ?: "",
            serviceId = p.getString("serviceId", "") ?: "",
            serviceName = p.getString("serviceName", "") ?: "",
            customerPhone = p.getString("customerPhone", "") ?: "",
            userName = p.getString("userName", "") ?: "",
            isV4 = p.getBoolean("isV4", true),
            tokenInfo = p.getString("tokenInfo", "") ?: "",
            v3BaseUrl = p.getString("v3BaseUrl", "") ?: "",
            schoolId = p.getString("schoolId", "") ?: "",
            investorId = p.getString("investorId", "") ?: "",
            studentHex = p.getString("studentHex", "") ?: ""
        )
        return if (s.isLoggedIn) s else null
    }

    /** 查询乐校通水卡余额 */
    val GetWaterBalance = object : ToolRegistry.Tool {
        override val name = "get_water_balance"
        override val description = "查询乐校通水卡余额(用于宿舍热水等;需先在水卡页面登录乐校通)"
        override val category = ToolRegistry.Category.SAFE
        // 使用独立的水卡会话,不依赖校园服务登录
        override val requiresLogin = false
        override val parametersHint = ""

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val session = loadSession(context)
                ?: return ToolRegistry.Result(false, "尚未登录乐校通水卡,请先在「水卡」页面登录后再查询")
            return try {
                withContext(Dispatchers.IO) {
                    val api = YxtApi()
                    api.session = session
                    if (!session.isV4) api.restoreV3BaseUrl(session.v3BaseUrl)
                    if (session.isV4) {
                        if (session.balanceId.isBlank()) {
                            return@withContext ToolRegistry.Result(false, "水卡信息不完整,请打开水卡页面刷新一次后重试")
                        }
                        val w = api.getWalletInfo(session.balanceId)
                        val sb = StringBuilder("乐校通水卡余额: ${w.balanceText} 元")
                        if (w.giftBalance.isNotBlank() && w.giftBalance != "0.00") sb.append("\n赠送余额: ${w.giftBalance} 元")
                        if (w.totalBalance.isNotBlank() && w.totalBalance != w.balanceText) sb.append("\n总余额: ${w.totalBalance} 元")
                        if (session.serviceName.isNotBlank()) sb.append("\n服务: ${session.serviceName}")
                        ToolRegistry.Result(true, sb.toString())
                    } else {
                        if (session.investorId.isBlank() || session.schoolId.isBlank()) {
                            return@withContext ToolRegistry.Result(false, "水卡信息不完整,请打开水卡页面刷新一次后重试")
                        }
                        val w = api.getWalletV3(session.investorId, session.schoolId, session.userId)
                        ToolRegistry.Result(true, "乐校通水卡余额: ${w.balance} 元")
                    }
                }
            } catch (e: Exception) {
                ToolRegistry.Result(false, "查询水卡余额失败: ${e.message ?: "网络错误"},可能登录已过期,请到水卡页面重新登录")
            }
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(GetWaterBalance)
}
