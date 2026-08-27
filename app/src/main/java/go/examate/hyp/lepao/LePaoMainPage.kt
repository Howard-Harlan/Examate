package go.examate.hyp.lepao

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.theme.BgPrimary
import go.examate.hyp.ui.theme.GlassChipTint
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SelectedBg
import go.examate.hyp.ui.theme.TextSecondary

@Composable
fun LePaoMainPage(onBack: () -> Unit, onLogout: () -> Unit) {
    val vm: LePaoViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 记录详情状态与缓存（提升到此处，返回按钮可正确关闭详情）
    var detailRecordId by remember { mutableStateOf<String?>(null) }
    val detailCache = remember { mutableStateMapOf<String, org.json.JSONObject>() }
    val trackCache = remember { mutableStateMapOf<String, List<Pair<Double, Double>>?>() }

    // 路线编辑器
    if (state.page is LePaoPage.RouteEditor) {
        FreehandRouteEditor(
            initialPoints = state.config.routePoints,
            onSave = { pts -> vm.editRouteDone(pts) },
            onCancel = { vm.navigate(LePaoPage.Main()) }
        )
        return
    }

    // 记录详情页 — 独立全屏页面，完全替换内容
    detailRecordId?.let { rid ->
        Box(Modifier.fillMaxSize().background(BgPrimary)) {
            RecordDetailPage(
                recordId = rid,
                detailCache = detailCache,
                trackCache = trackCache,
                onImportRoute = {
                    detailRecordId = null
                    vm.saveRecordAsRoute(it)
                }
            )
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = { detailRecordId = null },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp).size(40.dp)
            )
        }
        return
    }

    val selectedTab = (state.page as? LePaoPage.Main)?.tab ?: 0

    Box(Modifier.fillMaxSize().background(BgPrimary)) {
        // 内容区：填满屏幕，底栏真悬浮叠加在上
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            when (selectedTab) {
                0 -> OperationTab(
                    config = state.config, routeDistanceKm = vm.routeDistanceKm,
                    uploading = state.uploading, uploadProgress = state.uploadProgress,
                    resultMsg = state.resultMsg, resultOk = state.resultOk,
                    onEditRoute = { vm.navigate(LePaoPage.RouteEditor) },
                    onConfigChange = { vm.updateConfig(it) },
                    onUploadStart = { vm.uploadFreeRun() },
                    onCampusRunStart = { vm.uploadCampusRun() }
                )
                1 -> DataTab(
                    termScore = state.termScore,
                    loadingRecords = state.loadingRecords,
                    userRecords = state.userRecords,
                    importingRoute = state.importingRoute,
                    importMsg = state.importMsg,
                    onRefresh = { vm.fetchUserData() },
                    onSaveRecordAsRoute = { vm.saveRecordAsRoute(it) },
                    onClearImportMsg = { vm.clearImportMsg() },
                    onRecordClick = { detailRecordId = it }
                )
            }
        }

        // 悬浮返回按钮（左上）
        LiquidGlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp).size(40.dp)
        )

        // 悬浮退出按钮（右上）
        LiquidGlassIconButton(
            icon = Icons.Filled.Logout,
            contentDescription = "退出登录",
            onClick = { vm.logout(onLogout) },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp).size(40.dp)
        )

        // 底栏：液态悬浮胶囊式 Tab（参考阅读页面 BookshelfScreen 样式）
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.clip(RoundedCornerShape(24.dp)).background(GlassChipTint)) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("配置" to 0, "记录" to 1).forEach { (label, idx) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (selectedTab == idx) SelectedBg else Color.Transparent)
                                .clickable { vm.navigate(LePaoPage.Main(idx)) }
                                .padding(horizontal = 24.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (selectedTab == idx) Color.White else TextSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
