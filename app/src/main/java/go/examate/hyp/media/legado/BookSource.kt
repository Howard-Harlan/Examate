package go.examate.hyp.media.legado

import org.json.JSONArray
import org.json.JSONObject

/**
 * legado(阅读3.0)书源模型与解析。
 *
 * 完整保留 legado 关键字段;规则语法(jsoup/正则/JS/变量)由 RuleEngine 解释,本文件只负责结构化。
 * rawJson 保留原始文本,用于保真导出与兜底。
 */
data class BookSource(
    val bookSourceUrl: String,           // 唯一键(站点根地址)
    val bookSourceName: String,
    val bookSourceType: Int = 0,         // 0文本 1音频 2图片(漫画) 3文件
    val bookSourceGroup: String = "",
    val enabled: Boolean = true,
    val header: String? = null,          // JSON 或 @js:
    val loginUrl: String? = null,        // 仅存,不实现(P3)
    val searchUrl: String? = null,
    val exploreUrl: String? = null,      // 仅存(P3 发现页)
    val ruleSearch: SearchRule? = null,
    val ruleBookInfo: BookInfoRule? = null,
    val ruleToc: TocRule? = null,
    val ruleContent: ContentRule? = null,
    val rawJson: String = ""             // 原始 JSON 全文
)

data class SearchRule(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val bookUrl: String? = null
)

data class BookInfoRule(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val lastChapter: String? = null,
    val tocUrl: String? = null,
    val wordCount: String? = null
)

data class TocRule(
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val isVip: String? = null,
    val updateTime: String? = null,
    val nextTocUrl: String? = null
)

data class ContentRule(
    val content: String? = null,
    val nextContentUrl: String? = null,
    val replaceRegex: String? = null
)

/**
 * 书源解析器 — JSON ↔ BookSource。
 * 兼容单对象或数组;坏条目跳过不中断整体导入。
 */
object BookSourceParser {

    fun parseList(text: String): List<BookSource> {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseOne(it) } }
                }
                trimmed.startsWith("{") -> listOfNotNull(parseOne(JSONObject(trimmed)))
                else -> emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun parseOne(o: JSONObject): BookSource? {
        val url = o.optString("bookSourceUrl").ifBlank { return null }
        return try {
            BookSource(
                bookSourceUrl = url,
                bookSourceName = o.optString("bookSourceName").ifBlank { url },
                bookSourceType = o.optInt("bookSourceType", 0),
                bookSourceGroup = o.optString("bookSourceGroup"),
                enabled = o.optBoolean("enabled", true),
                header = o.optStringOrNull("header"),
                loginUrl = o.optStringOrNull("loginUrl"),
                searchUrl = o.optStringOrNull("searchUrl"),
                exploreUrl = o.optStringOrNull("exploreUrl"),
                ruleSearch = o.optJSONObject("ruleSearch")?.let { r ->
                    SearchRule(
                        bookList = r.optStringOrNull("bookList"),
                        name = r.optStringOrNull("name"),
                        author = r.optStringOrNull("author"),
                        kind = r.optStringOrNull("kind"),
                        wordCount = r.optStringOrNull("wordCount"),
                        lastChapter = r.optStringOrNull("lastChapter"),
                        intro = r.optStringOrNull("intro"),
                        coverUrl = r.optStringOrNull("coverUrl"),
                        bookUrl = r.optStringOrNull("bookUrl")
                    )
                },
                ruleBookInfo = o.optJSONObject("ruleBookInfo")?.let { r ->
                    BookInfoRule(
                        init = r.optStringOrNull("init"),
                        name = r.optStringOrNull("name"),
                        author = r.optStringOrNull("author"),
                        kind = r.optStringOrNull("kind"),
                        coverUrl = r.optStringOrNull("coverUrl"),
                        intro = r.optStringOrNull("intro"),
                        lastChapter = r.optStringOrNull("lastChapter"),
                        tocUrl = r.optStringOrNull("tocUrl"),
                        wordCount = r.optStringOrNull("wordCount")
                    )
                },
                ruleToc = o.optJSONObject("ruleToc")?.let { r ->
                    TocRule(
                        chapterList = r.optStringOrNull("chapterList"),
                        chapterName = r.optStringOrNull("chapterName"),
                        chapterUrl = r.optStringOrNull("chapterUrl"),
                        isVip = r.optStringOrNull("isVip"),
                        updateTime = r.optStringOrNull("updateTime"),
                        nextTocUrl = r.optStringOrNull("nextTocUrl")
                    )
                },
                ruleContent = o.optJSONObject("ruleContent")?.let { r ->
                    ContentRule(
                        content = r.optStringOrNull("content"),
                        nextContentUrl = r.optStringOrNull("nextContentUrl"),
                        replaceRegex = r.optStringOrNull("replaceRegex")
                    )
                },
                rawJson = o.toString()
            )
        } catch (_: Exception) { null }
    }

    /** 扫描规则串是否含内嵌 JS 或变量插值(阶段1 用于标记"含JS,暂不支持") */
    fun containsJs(source: BookSource): Boolean {
        val blob = buildString {
            append(source.searchUrl ?: "")
            append(source.exploreUrl ?: "")
            append(source.header ?: "")
            listOfNotNull(
                source.ruleSearch?.let { "${it.bookList}${it.name}${it.author}${it.coverUrl}${it.bookUrl}${it.intro}${it.kind}${it.lastChapter}${it.wordCount}" },
                source.ruleBookInfo?.let { "${it.name}${it.author}${it.coverUrl}${it.intro}${it.tocUrl}${it.kind}${it.lastChapter}${it.wordCount}" },
                source.ruleToc?.let { "${it.chapterList}${it.chapterName}${it.chapterUrl}${it.isVip}${it.updateTime}${it.nextTocUrl}" },
                source.ruleContent?.let { "${it.content}${it.nextContentUrl}${it.replaceRegex}" }
            ).forEach { append(it) }
        }
        return blob.contains("@js:") || blob.contains("<js>") || blob.contains("{{")
    }
}

/** JSONObject 取字符串,空串归一为 null(便于规则判空) */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}
