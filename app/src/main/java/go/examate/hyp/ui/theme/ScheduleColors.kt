package go.examate.hyp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 课程表相关颜色集中定义。
 *
 * 之前散落在 ScheduleEntry.kt / ScheduleComponents.kt / AddCoursePage.kt 等
 * 多个文件的硬编码色值,统一抽取到这里,便于统一调整与复用。
 */

/** 课表节次格子背景(原 `Color(0xFFF0F0F2)`,9 处重复) */
val ScheduleCellBg = Color(0xFFF0F0F2)

/** 课表卡片渐变起始(原 `Color(0xFF374151)`,3 处重复) */
val ScheduleGradientStart = Color(0xFF374151)

/** 课表卡片渐变结束(原 `Color(0xFF4B5563)`,3 处重复) */
val ScheduleGradientEnd = Color(0xFF4B5563)

/** 淮师心声成功色(原 `Color(0xFF1D9E75)`,4 处重复) */
val HsxsSuccess = Color(0xFF1D9E75)

/** 淮师心声错误色(原 `Color(0xFFE24B4A)`,4 处重复) */
val HsxsError = Color(0xFFE24B4A)

/**
 * 课程调色板(新增课程时随机分配,便于区分)。
 * 原 ScheduleEntry.kt:131-138 硬编码,现集中管理。
 */
val ScheduleCoursePalette = listOf(
    Color(0xFF6C8EE3), // 蓝
    Color(0xFF5BAA6F), // 绿
    Color(0xFFE39B5B), // 橙
    Color(0xFFB57BB8), // 紫
    Color(0xFFE36C6C), // 红
    Color(0xFF5BBAA8), // 青
    Color(0xFFB8B57B), // 卡其
    Color(0xFF7B8EB8)  // 灰蓝
)
