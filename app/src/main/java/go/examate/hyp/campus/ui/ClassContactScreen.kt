package go.examate.hyp.campus.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.campus.CampusException
import go.examate.hyp.campus.CampusRepository
import go.examate.hyp.campus.model.ClassContact
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 班级通讯录页面 — 从智慧门户(oshall)查询同班同学电话列表。
 *
 * CAS SSO → GET apiZhxy/cas/client/validateLogin 获取 JWT token
 * → GET apiZhxy/applets/synthesis/query/studentPhoneListPage 获取通讯录
 * 返回字段:phone / realname / departName
 */
@Composable
fun ClassContactScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getInstance(context) }
    val repo = remember { CampusRepository.getInstance() }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ClassContact>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            contacts = withContext(Dispatchers.IO) { repo.getStudentPhoneList(prefs) }
            if (contacts.isEmpty()) {
                error = "暂无通讯录数据"
            }
        } catch (e: CampusException) {
            error = e.message ?: "加载失败"
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
        }
        loading = false
    }

    // 按姓名/手机号过滤
    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter {
            it.studentName.contains(query, ignoreCase = true) ||
            it.phone.contains(query, ignoreCase = true) ||
            it.className.contains(query, ignoreCase = true)
        }
    }

    CampusPageScaffold(title = "班级通讯录", onBack = onBack) {
        if (loading) {
            LoadingBlock("正在查询班级通讯录...")
        } else if (error.isNotEmpty()) {
            ErrorBlock(error)
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FrostedGlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("班级通讯录", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("共 ${contacts.size} 位同学", color = TextHint, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            // 搜索框
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("搜索姓名 / 手机号 / 班级", color = TextHint, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentBlue,
                                    unfocusedBorderColor = TextHint.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                items(filtered, key = { it.studentName + it.phone }) { contact ->
                    ContactCard(contact)
                }
            }
        }
    }
}

/** 通讯录条目卡片:姓名首字头像 + 姓名 + 班级 + 手机号 + 拨号按钮 */
@Composable
private fun ContactCard(contact: ClassContact) {
    FrostedGlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像(姓名首字)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.studentName.firstOrNull()?.toString() ?: "?",
                    color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(12.dp))
            // 信息
            Column(Modifier.weight(1f)) {
                Text(contact.studentName.ifEmpty { "未知" }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (contact.className.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(contact.className, color = TextSecondary, fontSize = 11.sp)
                }
                if (contact.phone.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(contact.phone, color = AccentBlue, fontSize = 12.sp)
                }
            }
            // 拨号按钮
            if (contact.phone.isNotEmpty()) {
                val context = LocalContext.current
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}")))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Call, contentDescription = "拨号", tint = AccentBlue, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
