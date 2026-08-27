package go.examate.hyp.lepao

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.ui.components.ButtonStyle
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassButton
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LePaoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(LePaoPrefs.isLoggedIn(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                loggedIn = LePaoPrefs.isLoggedIn(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (loggedIn) {
        LePaoMainPage(onBack = onBack, onLogout = { loggedIn = false })
    } else {
        LePaoLoginPage(onBack = onBack, onLoginSuccess = { loggedIn = true })
    }
}

@Composable
private fun LePaoLoginPage(onBack: () -> Unit, onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uid by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var schoolId by remember { mutableStateOf(LePaoPrefs.DEFAULT_SCHOOL_ID) }
    var studentNum by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }


    /** 通用登录包装 — 统一 loading/message/异常处理，消除三个登录函数的重复模板 */
    fun runLogin(
        apiCall: () -> org.json.JSONObject,
        onSuccess: (org.json.JSONObject) -> Unit,
        errorPrefix: String = "登录失败"
    ) {
        loading = true; message = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = apiCall()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loading = false
                    if (result.optInt("status") == 1) onSuccess(result)
                    else message = "$errorPrefix: ${result.optString("info")}"
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { loading = false; message = "网络错误: ${e.message}" }
            }
        }
    }

    fun doManualLogin() {
        if (uid.isBlank() || token.isBlank()) return
        runLogin(
            apiCall = { LePaoApi(uid, token, schoolId, studentNum).getUserInfo() },
            onSuccess = {
                LePaoPrefs.save(context, uid, token, schoolId, studentNum)
                isSuccess = true; onLoginSuccess()
            }
        )
    }


    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 56.dp, bottom = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // QQ扫码登录
                    LiquidGlassButton(
                        text = "QQ扫码登录",
                        onClick = {
                            val intent = android.content.Intent(context, LePaoScanLoginActivity::class.java)
                            intent.putExtra(LePaoScanLoginActivity.EXTRA_PLATFORM, LePaoScanLoginActivity.PLATFORM_QQ)
                            context.startActivity(intent)
                        },
                        style = ButtonStyle.White, height = 48.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = CardBg.copy(alpha = 0.5f))

                    // 手动输入凭据（折叠）
                    Row(Modifier.fillMaxWidth().clickable { showManualInput = !showManualInput }, verticalAlignment = Alignment.CenterVertically) {
                        Text("手动输入凭据", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(if (showManualInput) "▾" else "›", color = TextHint, fontSize = 14.sp)
                    }
                    if (showManualInput) {
                        OutlinedTextField(
                            value = uid, onValueChange = { uid = it },
                            label = { Text("UID") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = token, onValueChange = { token = it },
                            label = { Text("Token") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = schoolId, onValueChange = { schoolId = it },
                                label = { Text("School ID") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f), singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = studentNum, onValueChange = { studentNum = it },
                                label = { Text("学号") },
                                modifier = Modifier.weight(1.5f), singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                        LiquidGlassButton(
                            text = if (loading) "正在验证..." else "验证并登录",
                            onClick = { if (!loading && uid.isNotBlank() && token.isNotBlank()) doManualLogin() },
                            style = ButtonStyle.White, height = 44.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    message?.let { Text(it, color = if (isSuccess) Success else Error, fontSize = 13.sp) }
                }
            }
        }

        Box(Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 4.dp)) {
            LiquidGlassIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp))
        }
    }
}
