package go.examate.hyp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import go.examate.hyp.ui.components.PlainDialog
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.FestivalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 节日欢迎页宿主 — 挂载在主界面任意 Tab 之上。
 *
 * 每次 ON_RESUME 检查激活节日,活动期间首次进入时弹出一次欢迎页
 * (按节日 id + 年份 持久化,同一年内不重复打扰)。
 */
@Composable
fun FestivalWelcomeHost() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<FestivalManager.Festival?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTick) {
        val festivals = withContext(Dispatchers.IO) { FestivalManager.activeFestivals(context) }
        val notWelcomed = festivals.firstOrNull { FestivalManager.shouldShowWelcome(context, it) }
        if (notWelcomed != null) pending = notWelcomed
    }

    pending?.let { festival ->
        FestivalWelcomeDialog(
            festival = festival,
            onDismiss = {
                FestivalManager.markWelcomeShown(context, festival)
                pending = null
                // 若还有其它激活节日未欢迎,继续展示下一个
                refreshTick++
            }
        )
    }
}

/** 节日欢迎页:大 emoji + 渐变光斑 + 节日名 + 祝福语 */
@Composable
private fun FestivalWelcomeDialog(
    festival: FestivalManager.Festival,
    onDismiss: () -> Unit
) {
    PlainDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部渐变横幅区
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(festival.color).copy(alpha = 0.22f),
                                Color(festival.color).copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color(festival.color).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(festival.emoji, fontSize = 36.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "${festival.name}好呀",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))
            Text(
                festival.greeting,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(AccentBlue)
                    .clickable { onDismiss() }
                    .padding(horizontal = 40.dp, vertical = 11.dp)
            ) {
                Text("开始今天", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text("活动期间,首页会一直显示节日横幅", color = TextHint, fontSize = 10.sp)
        }
    }
}
