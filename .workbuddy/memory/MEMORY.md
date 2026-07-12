# 项目长期记忆：FloatStudyAssistant（淮北师范大学智慧校园）

## 架构约定
- 语言 Kotlin 为主；架构 = Repository 单例 + `ChnuClient` 单例 + 协程（Compose MVVM）。
- 网络层：`ChnuClient` 用 OkHttp3 裸调用（无 Retrofit），CAS SSO 共享 `SimpleCookieJar`，SSL 信任所有证书。
- 一卡通系统为内网 HTTP（`http://210.45.128.224:8081/easytong_portal`，easytong_portal），返回 HTML 页面，自签名/明文需放行 `network_security_config.xml`。

## 已知坑（一卡通充值）
- `/topUp/confirm` 接口会以 chunked 方式**提前断开连接**，OkHttp `resp.body?.string()` 会抛 `IOException`（`message` 常为 null）→ 表现为"网络异常: null"。必须用流式容错读取 `readBodyTolerant`（见 ChnuClient.kt）。
- 充值正确流程（对齐 `C:\project\api\chnu\ecard.py`）：两步提交——先 POST `/topUp/confirm` 取确认页 `form#pay-form` 的 action+隐藏字段，再向其 action 提交；金额用元(`%.2f`)，密码用 RSA(十六进制公钥/PKCS1 v1.5/Base64) 加密；必带 `bankCardNum/payMode=1`。
- **`bankId`/`logoFileName`/`eWalletName` 必须来自 `getTopupPage()` 动态解析**（POST `/topUp` 页面，从 `label id="bankCard-{id}"`、`<i/img logofilename=>`、`div.recharge-box` 提取），**禁止硬编码 `bankId="25"`**（非默认银行卡用户会失败）。
- **缴费 `payFee` 必须带齐表单字段**：`typeNum`/`token`/`limitMoney`/`limitMode`(来自 `getPayFeeForm()` POST `/payFee/showItemListPayPage` 的隐藏字段) + `itemName` + `cardAccNum`/`eWalletId`/`epId`，并做限额校验（超 `limitMoney` 直接失败）。

## 参考实现来源 & 同步纪律
- `C:\project\api\chnu/`（Python SDK：cas_auth.py / ecard.py / jwxt.py / sc.py）是该校园服务各系统的"正确实现"参考，本 Android 项目由它转换而来。
- **同步纪律**：Android `campus` 包的一卡通逻辑以 `C:\project\api\chnu\ecard.py` 为唯一正确参考。每次该 Python 文件更新后，应比对 `ChnuClient.EcardClient` 的差异并同步（2026-07-12 已做过一轮：补齐 `getTopupPage()`/`getPayFeeForm()`）。jwxt/sc 子系统同理。
- Kotlin 注意：`inner class` 内**不能**声明嵌套 `class`/`data class`，需放文件级（但仍可 `private` 仅本文件可见）。
