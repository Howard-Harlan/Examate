package go.examate.hyp.campus.model

// ==================== 数据类 ====================

/** 学生个人信息 */
data class StudentInfo(
    val name: String,
    val studentId: String,
    val department: String,
    val major: String,
    val className: String
)

/** 成绩 */
data class Grade(
    val courseName: String,
    val credit: String,
    val score: String,
    val gpa: String,
    val year: String,
    val semester: String,
    /** 课程代码(用于查询成绩明细) */
    val courseCode: String = "",
    /** 教学班ID(用于成绩明细查询) */
    val jxbId: String = "",
    /** 成绩性质(如"正常考试") */
    val examType: String = "",
    /** 考试性质 */
    val examNature: String = "",
    /** 开课学院 */
    val college: String = ""
)

/** 成绩明细(某门课程的平时分/期末分/总分构成) */
data class GradeDetail(
    val courseName: String,
    val credit: String,
    val gpa: String,
    /** 平时成绩 */
    val regularScore: String = "",
    /** 期末成绩 */
    val finalExamScore: String = "",
    /** 总成绩 */
    val totalScore: String = "",
    /** 成绩性质 */
    val examType: String = "",
    /** 其他子项(如实验成绩、论文成绩等,key=项目名,value=分数) */
    val subItems: Map<String, String> = emptyMap()
)

/** 课表项 */
data class ScheduleItem(
    val day: String,
    val section: String,
    val courseName: String,
    val teacher: String,
    val classroom: String,
    val weeks: String,
    /** 学分 */
    val credit: String = "",
    /** 课程类别(理论/实验等) */
    val courseCategory: String = ""
)

/** 考试安排 */
data class ExamItem(
    val courseName: String,
    val date: String,
    val location: String,
    val seatNo: String
)

/** 已选课程(选课结果,来自课表确认页) */
data class SelectedCourse(
    val courseName: String,
    val teacher: String,
    val credit: String,
    val classTime: String,
    val classroom: String,
    val courseType: String,
    val selected: Boolean,
    /** 上课周次 */
    val weeks: String = "",
    /** 课程代码 */
    val courseCode: String = "",
    /** 考核方式 */
    val examMethod: String = ""
)

/** 教学评价项 */
data class EvaluationItem(
    val courseName: String,
    val teacher: String,
    val score: String,
    val status: String,
    val submitStatus: String,
    val classTime: String,
    val classroom: String,
    val college: String,
    val courseType: String,
    /** 问卷编码(wjbm,用于获取评价题目和提交) */
    val wjbm: String = "",
    /** 教师编号 */
    val teacherId: String = "",
    /** 课程ID */
    val courseId: String = "",
    /** 教学班ID */
    val jxbId: String = "",
    /** 学年名称(如"2024-2025") */
    val schoolYear: String = ""
)

/** 教学评价指标(评价页面的题目) */
data class EvaluationQuestion(
    val id: String,
    /** 指标名称/题目文字 */
    val name: String,
    /** 分值上限 */
    val maxScore: Double = 100.0,
    /** 用户已选分数(-1=未评) */
    val selectedScore: Double = -1.0,
    /** 指标类型(单选/多选等) */
    val type: String = ""
)

/** 空闲教室 */
data class EmptyClassroom(
    /** 教室名称 */
    val classroomName: String,
    /** 教学楼 */
    val buildingName: String,
    /** 座位数/容量 */
    val capacity: String = "",
    /** 教室类型(多媒体/普通等) */
    val roomType: String = "",
    /** 校区 */
    val campus: String = "",
    /** 空闲节次列表 */
    val freePeriods: List<String> = emptyList()
)

/** 教学楼信息 */
data class BuildingInfo(
    val id: String,
    val name: String,
    val campus: String = ""
)

/** 从网页 select 解析的选项(通用) */
data class SelectOption(
    val id: String,
    val name: String
)

/** 空闲教室页面数据(从 GET 页面动态解析所有 select 选项) */
data class ClassroomPageData(
    val campusOptions: List<SelectOption> = emptyList(),
    val buildingOptions: List<SelectOption> = emptyList(),
    val roomTypeOptions: List<SelectOption> = emptyList()
)

/** 选课状态 */
data class XkStatus(
    /** 是否在选课阶段:"1"=是,"0"=否 */
    val isXk: String,
    /** 学年起始(如 "2025") */
    val xn: String,
    /** 学期代码(如 "12"=第2学期) */
    val xq: String,
    /** 选课控制 ID */
    val xkkzId: String,
    /** 当前学年学期描述 */
    val currentXnxq: String
)

/** 可选课程 */
data class AvailableCourse(
    val courseName: String,
    val courseCode: String,
    val teacher: String,
    val classTime: String,
    val classroom: String,
    val credit: String,
    val capacity: String,
    val selected: String,
    /** 选课所需参数 */
    val jxbId: String,
    val kchId: String,
    val doJxbId: String,
    val kklxdm: String
)

/** 选课学分信息 */
data class XkCredits(
    /** 应修学分 */
    val requiredCredits: String,
    /** 已修学分 */
    val obtainedCredits: String,
    /** 历年已获学分 */
    val historyCredits: String
)

/** 一卡通余额 */
data class EcardBalance(
    val balance: String,
    val accountName: String,
    /** 校园卡卡号 */
    val cardNo: String = "",
    /** 银行卡列表(从 /bankCard 页面获取) */
    val bankCards: List<BankCard> = emptyList()
)

/** 银行卡信息 */
data class BankCard(
    val cardId: String,
    val cardType: String,
    /** 掩码卡号(如 **** **** **** 0926) */
    val cardNumberMasked: String,
    /** 短卡号(如 **0926) */
    val cardNumberShort: String,
    /** 完整卡号(从 radio input value 提取) */
    val cardNumberFull: String,
    val bankTip: String,
    /** 银行 logo 文件名(充值页解析,如 BOC);缴费/展示页可为空 */
    val logoFileName: String = ""
)

/** 房间电费信息 */
data class RoomElectricity(
    /** 剩余金额(元) */
    val balance: String,
    /** 昨日用电(度) */
    val dailyUsed: String
)

/** 账单记录 */
data class BillRecord(
    val time: String,
    val amount: String,
    val description: String,
    val type: String,
    val balanceAfter: String = ""
)

/** 充值/缴费结果 */
data class PayResult(
    val success: Boolean,
    val message: String,
    val amount: String
)

/** 生活缴费校区 */
data class PayArea(
    val id: String,
    val name: String
)

/** 生活缴费楼栋 */
data class PayBuilding(
    val id: String,
    val name: String
)

/** 生活缴费房间 */
data class PayRoom(
    val id: String,
    val name: String
)

/** 第二课堂成绩明细项 */
data class ScoreDetail(
    val moduleName: String,
    val itemName: String,
    val score: String
)

/** 第二课堂成绩汇总 */
data class ScoreSummary(
    val totalScore: String,
    val modules: List<ModuleScore>
)

/** 模块得分 */
data class ModuleScore(
    val moduleName: String,
    val score: String
)

// ════════════════════════════════════════════════
//  智慧思政(jgf) — 综合测评成绩
// ════════════════════════════════════════════════

/** 综合测评成绩明细项(对应 jgf ChangeScore/List 返回字段) */
data class SzsScoreItem(
    val testBatchName: String,      // 测评批次(如 2024-2025学年)
    val studentName: String,        // 学生姓名
    val studentNo: String,          // 学号
    val className: String,          // 班级
    val college: String,            // 学院
    val major: String,              // 专业
    val dyScore: String,            // 德育成绩
    val zyScore: String,            // 专业成绩
    val tyScore: String,            // 体育成绩
    val nlfzEdu: String,            // 劳动教育
    val tzEdu: String,              // 体质
    val ygpbEdu: String,            // 应购书本
    val dykjScore: String,          // 党员科技
    val dyDeclareScore: String      // 党员申报
)

// ════════════════════════════════════════════════
//  班级通讯录(oshall) — 学生电话列表
// ════════════════════════════════════════════════

/** 班级通讯录条目(对应 oshall studentPhoneListPage 返回字段) */
data class ClassContact(
    val studentName: String,    // 姓名(realname)
    val phone: String,          // 手机号(phone)
    val className: String       // 班级(departName)
)

// ════════════════════════════════════════════════
//  淮师心声(Hsxs) — 失物招领 & 满意度调查
//  API: https://hsxs.chnu.edu.cn/api/
// ════════════════════════════════════════════════

/** 失物招领物品 */
data class HsxsLostItem(
    val id: String = "",
    val title: String = "",
    val type: String = "",          // "lost"=寻物, "found"=招领
    val category: String = "",      // 物品分类
    val description: String = "",   // 详细描述
    val location: String = "",      // 丢失/拾取地点
    val contactName: String = "",   // 联系人
    val contactPhone: String = "",  // 联系方式
    val imageUrl: String = "",      // 物品图片
    val status: String = "",        // 状态
    val publishTime: String = "",   // 发布时间
    val publisherName: String = ""  // 发布人
)

/** 失物招领物品列表响应 */
data class HsxsLostListResult(
    val total: Int = 0,
    val rows: List<HsxsLostItem> = emptyList()
)

/** 满意度调查问卷 */
data class HsxsQuestion(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val status: String = "",        // "open"=进行中
    val answerCount: Int = 0,       // 已答人数
    val questions: List<HsxsQuestionItem> = emptyList()
)

/** 问卷题目 */
data class HsxsQuestionItem(
    val id: String = "",
    val title: String = "",         // 题目文字
    val type: String = "",          // "radio"=单选, "checkbox"=多选, "textarea"=简答
    val required: Boolean = false,  // 是否必填
    val options: List<String> = emptyList(),  // 选项列表
    val answer: String = ""         // 用户已选答案(查看时)
)

/** 问卷列表响应 */
data class HsxsQuestionListResult(
    val total: Int = 0,
    val rows: List<HsxsQuestion> = emptyList()
)
