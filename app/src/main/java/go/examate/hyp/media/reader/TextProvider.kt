package go.examate.hyp.media.reader

/**
 * 通用阅读器内核接口 — 让阅读器 UI 与"内容从哪来"彻底解耦。
 *
 * 任何能提供"标题 + 若干章节 + 按索引取正文"的来源都可实现它:
 *  - legado 书源(在线抓取,见 LegadoTextProvider)
 *  - 本地 txt 文件(下载器下载 / workspace 导入,见 LocalTextProvider)
 *  - AI 生成的文档 等
 *
 * 约定:所有取数方法 suspend,由调用方在 IO 调度;实现内部自理缓存。
 */
interface TextProvider {
    /** 书名/文件名,用于阅读器标题 */
    val title: String

    /** 获取章节目录。单文件来源可返回单章(整篇作一章)或自行切分 */
    suspend fun loadChapters(): List<ReaderChapter>

    /** 按章节索引取正文纯文本(段落以 \n 分隔)。失败返回空串 */
    suspend fun loadContent(index: Int): String
}

/** 阅读器章节条目(与具体来源无关) */
data class ReaderChapter(
    val title: String,
    val index: Int,
    val badge: String = ""   // 可选装饰,如 "VIP"
)
