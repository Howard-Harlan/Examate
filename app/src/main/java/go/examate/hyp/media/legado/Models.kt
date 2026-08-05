package go.examate.hyp.media.legado

/**
 * 阅读流程的数据模型(纯数据,无 legado 依赖,供 UI 直接使用)。
 */

/** 搜索结果条目 */
data class SearchBook(
    val name: String,
    val author: String = "",
    val kind: String = "",
    val wordCount: String = "",
    val lastChapter: String = "",
    val intro: String = "",
    val coverUrl: String = "",
    val bookUrl: String,               // 详情页地址(相对已补全为绝对)
    val sourceUrl: String,             // 来源书源 bookSourceUrl
    val sourceName: String = ""
)

/** 书籍详情 */
data class BookInfo(
    val name: String,
    val author: String = "",
    val kind: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val lastChapter: String = "",
    val tocUrl: String = "",           // 目录地址(空则用 bookUrl)
    val wordCount: String = "",
    val bookUrl: String,
    val sourceUrl: String
)

/** 目录章节 */
data class BookChapter(
    val title: String,
    val url: String,
    val index: Int,
    val isVip: Boolean = false,
    val updateTime: String = ""
)
