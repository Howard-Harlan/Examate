package go.examate.hyp.media.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import go.examate.hyp.config.PrefsManager
import go.examate.hyp.media.legado.ContentRuleStore
import go.examate.hyp.ui.components.FrostedGlassCard
import go.examate.hyp.ui.components.LiquidGlassIconButton
import go.examate.hyp.ui.components.LiquidGlassToggle
import go.examate.hyp.ui.theme.AccentBlue
import go.examate.hyp.ui.theme.Danger
import go.examate.hyp.ui.theme.Primary
import go.examate.hyp.ui.theme.SurfaceVariant
import go.examate.hyp.ui.theme.TextHint
import go.examate.hyp.ui.theme.TextPrimary
import go.examate.hyp.ui.theme.TextSecondary
import go.examate.hyp.util.ReadingFont
import go.examate.hyp.util.ToastUtil

/**
 * 阅读设置 — 分两 Tab:①阅读偏好(字号/背景/自定义字体/CSS样式,实时预览即存) ②内容管理(书源/本地文件)。
 * 自定义字体与 Agents 设置共享导入(同一字体文件),但阅读器有独立的启用开关,避免重复导入占用体积。
 *
 * @param initialTab 初始 Tab(0=阅读偏好 1=内容管理);从书源管理返回时传 1
 */
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    onManageSources: () -> Unit,
    onOpenLocalFile: () -> Unit,
    onImportRule: () -> Unit = {},
    initialTab: Int = 0
) {
    val context = LocalContext.current
    var fontLevel by remember { mutableIntStateOf(ReaderPrefs.getFontLevel(context)) }
    var bgTheme by remember { mutableIntStateOf(ReaderPrefs.getBgTheme(context)) }
    var settingsTab by remember { mutableIntStateOf(initialTab) }
    var concurrency by remember { mutableIntStateOf(ReaderPrefs.getSearchConcurrency(context)) }
    var stripHtml by remember { mutableStateOf(ReaderPrefs.isStripHtmlTags(context)) }
    // 自定义字体:路径与 Agents 共享(PrefsManager.chatFontPath),启用开关独立(ReaderPrefs)
    var fontPathState by remember { mutableStateOf(PrefsManager.getInstance(context).chatFontPath) }
    var customFontEnabled by remember { mutableStateOf(ReaderPrefs.isCustomFontEnabled(context)) }
    // CSS 自定义样式
    var cssText by remember { mutableStateOf(ReaderPrefs.getCustomCss(context)) }
    var showCssEditor by remember { mutableStateOf(false) }
    // 净化规则:导入/删除后 +1 触发列表刷新
    var ruleTick by remember { mutableIntStateOf(0) }
    val contentRules = remember(ruleTick) { ContentRuleStore.loadAll(context) }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ok = ReadingFont.import(context, uri)
            ToastUtil.show(context, if (ok) "字体已导入" else "导入失败(请选 ttf/otf)")
            if (ok) fontPathState = PrefsManager.getInstance(context).chatFontPath
        }
    }

    // 预览用的字体:启用开关打开且有导入字体时才应用
    val previewFamily = if (customFontEnabled) ReadingFont.family(context) else null

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶栏
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            LiquidGlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                onClick = onBack, modifier = Modifier.size(40.dp).align(Alignment.CenterStart)
            )
            Text("阅读设置", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center))
        }

        // ═══ 顶部分类:阅读偏好 / 内容管理 ═══
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("阅读偏好", "内容管理").forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (settingsTab == index) Primary else SurfaceVariant)
                        .clickable { settingsTab = index }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (settingsTab == index) Color.White else TextHint,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 40.dp)
        ) {
            if (settingsTab == 0) {
                // ═══ 阅读偏好 ═══
                SectionTitle("字号与背景")
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        // 字号:5 档文字标签(小/偏小/常规/偏大/大)
                        Text("字号", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
            listOf(0 to "小", 1 to "偏小", 2 to "常规", 3 to "偏大", 4 to "大").forEach { (level, label) ->
                                FontLabelChip(
                                    label = label,
                                    selected = fontLevel == level,
                                    onClick = {
                                        fontLevel = level
                                        ReaderPrefs.setFontLevel(context, level)
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("背景", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        // 背景:三个色块(调小)+ 名称,选中描边高亮
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(
                                Triple(0, "纸白", ReaderPrefs.bgColor(0)),
                                Triple(1, "护眼", ReaderPrefs.bgColor(1)),
                                Triple(2, "夜间", ReaderPrefs.bgColor(2))
                            ).forEach { (theme, label, color) ->
                                BgSwatch(
                                    label = label, color = color, selected = bgTheme == theme,
                                    onClick = { bgTheme = theme; ReaderPrefs.setBgTheme(context, theme) }
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        // 实时预览条:用当前字号/背景/字体+CSS样式 渲染一句正文
                        val previewStyle = remember(cssText) { ReaderPrefs.parseCss(cssText) }
                        val previewFontSize = previewStyle.fontSize ?: ReaderPrefs.fontSizeSp(fontLevel)
                        val previewColor = previewStyle.textColor ?: ReaderPrefs.textColor(bgTheme)
                        val previewLineHeight = (previewFontSize + (previewStyle.lineHeightExtra ?: 8)).sp
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(ReaderPrefs.bgColor(bgTheme)).padding(14.dp)
                        ) {
                            Text(
                                "夜色渐浓,他翻开书页,字里行间皆是山河。",
                                color = previewColor,
                                fontSize = previewFontSize.sp,
                                lineHeight = previewLineHeight,
                                fontFamily = previewFamily,
                                fontWeight = previewStyle.fontWeight ?: FontWeight.Normal,
                                textAlign = previewStyle.textAlign ?: TextAlign.Start
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                // ═══ 自定义字体(与 Agents 共享导入,独立启用) ═══
                SectionTitle("自定义字体")
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("正文字体", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (fontPathState.isEmpty()) "系统默认" else "已导入自定义字体",
                                    color = TextHint, fontSize = 11.sp
                                )
                            }
                            if (fontPathState.isNotEmpty()) {
                                Text("恢复默认", color = Danger, fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        ReadingFont.clear(context); fontPathState = ""
                                        ToastUtil.show(context, "已恢复默认字体")
                                    }.padding(horizontal = 6.dp, vertical = 4.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("导入字体", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { fontPicker.launch("*/*") }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                        }

                        // 启用开关:仅控制阅读器自身,字体文件与 Agents 共享
                        if (fontPathState.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("应用到阅读器", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("关闭后阅读器使用系统字体,Agents 不受影响", color = TextHint, fontSize = 11.sp)
                                }
                                LiquidGlassToggle(
                                    selected = customFontEnabled,
                                    onSelect = {
                                        customFontEnabled = it
                                        ReaderPrefs.setCustomFontEnabled(context, it)
                                    }
                                )
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "字体与 Agents 设置共享,导入一次即可在两处使用,无需重复导入",
                                color = TextHint, fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                // ═══ 自定义 CSS 样式(类似 CSS 重写阅读器样式) ═══
                SectionTitle("自定义样式")
                SettingItem(
                    icon = Icons.Filled.Code,
                    title = "CSS 样式",
                    subtitle = if (cssText.isBlank()) "未设置,点击编辑" else "已设置自定义样式,点击编辑",
                    onClick = { showCssEditor = true }
                )
            } else {
                // ═══ 内容管理(统一样式:书源管理/导入本地书籍/搜索设置/规则净化 均用卡片) ═══
                SectionTitle("书源")
                SettingItem(Icons.AutoMirrored.Filled.MenuBook, "书源管理", "导入 / 启用 / 删除 legado 书源", onManageSources)
                Spacer(Modifier.height(8.dp))
                SettingItem(Icons.Filled.Description, "导入本地书籍", "支持 txt/epub/md/html 等文本格式(自动加入书架)", onOpenLocalFile)
                Spacer(Modifier.height(12.dp))

                // ═══ 搜索设置 ═══
                SectionTitle("搜索设置")
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        // 并行搜索数
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("并行搜索数", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("同时搜索的书源数,越大越快但耗资源", color = TextHint, fontSize = 11.sp)
                            }
                            StepBtn("−", enabled = concurrency > 1) {
                                concurrency--; ReaderPrefs.setSearchConcurrency(context, concurrency)
                            }
                            Text(
                                "$concurrency",
                                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                            StepBtn("+", enabled = concurrency < 16) {
                                concurrency++; ReaderPrefs.setSearchConcurrency(context, concurrency)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // HTML标签净化开关
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("净化HTML标签", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("剥离正文中的<p></p><br>等标签(默认开启)", color = TextHint, fontSize = 11.sp)
                            }
                            LiquidGlassToggle(
                                selected = stripHtml,
                                onSelect = { stripHtml = it; ReaderPrefs.setStripHtmlTags(context, it) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ═══ 规则净化 ═══
                SectionTitle("规则净化")
                FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("净化规则", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("导入后对所有书源正文生效(在源规则之后追加)", color = TextHint, fontSize = 11.sp)
                            }
                            Text("导入", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { onImportRule() }.padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                        if (contentRules.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            contentRules.forEach { rule ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rule.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                        val lineCnt = rule.rules.lineSequence().count { it.isNotBlank() }
                                        Text("共 $lineCnt 条替换", color = TextHint, fontSize = 11.sp)
                                    }
                                    Text("删除", color = Danger, fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            ContentRuleStore.delete(context, rule.id); ruleTick++
                                        }.padding(horizontal = 6.dp, vertical = 4.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("清空全部", color = Danger, fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    ContentRuleStore.clearAll(context); ruleTick++
                                }.padding(horizontal = 6.dp, vertical = 4.dp))
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text("尚未导入净化规则,支持 legado 净化规则 JSON", color = TextHint, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    // ═══ CSS 样式编辑器(全屏对话框) ═══
    if (showCssEditor) {
        CssStyleEditor(
            initialText = cssText,
            onDismiss = { showCssEditor = false },
            onSave = { text ->
                cssText = text
                ReaderPrefs.setCustomCss(context, text)
                ToastUtil.show(context, "样式已保存")
                showCssEditor = false
            }
        )
    }
}

/** CSS 样式编辑器 — 全屏,多行输入,底部说明支持的属性 */
@Composable
private fun CssStyleEditor(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自定义 CSS 样式", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("恢复", color = Danger, fontSize = 12.sp,
                    modifier = Modifier.clickable { text = "" }.padding(horizontal = 6.dp, vertical = 4.dp))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                    placeholder = {
                        Text(
                            "font-size: 20px;\nline-height: 1.6;\ncolor: #2A2A2A;\nletter-spacing: 0.5px;\ntext-indent: 2em;\nparagraph-spacing: 16px;\ntext-align: justify;\nfont-weight: normal;",
                            color = TextHint, fontSize = 12.sp
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "支持属性:font-size, line-height, color, letter-spacing, text-indent, paragraph-spacing, text-align, font-weight。留空则使用阅读器默认样式。",
                    color = TextHint, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceVariant)
                        .clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("取消", color = TextSecondary, fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Primary)
                        .clickable { onSave(text) }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("保存", color = Color.White, fontSize = 13.sp) }
            }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp))
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(if (enabled) Primary.copy(0.12f) else Primary.copy(0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Primary else TextHint, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** 字号档位标签芯片(小/偏小/常规/偏大/大) */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FontLabelChip(
    label: String, selected: Boolean, onClick: () -> Unit
) {
    Box(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Primary.copy(0.15f) else SurfaceVariant.copy(0.5f))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Primary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BgSwatch(
    label: String, color: Color, selected: Boolean, onClick: () -> Unit
) {
    Column(
        Modifier.weight(1f).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp)).background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) AccentBlue else Color.Black.copy(0.08f),
                    shape = RoundedCornerShape(8.dp)
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) AccentBlue else TextSecondary, fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun SettingItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = title, tint = AccentBlue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextHint, fontSize = 11.sp)
            }
            Text("›", color = TextHint, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
    }
}
