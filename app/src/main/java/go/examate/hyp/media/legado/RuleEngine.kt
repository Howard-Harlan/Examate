package go.examate.hyp.media.legado

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

/**
 * 一次规则求值的共享上下文。
 */
class RuleContext(
    val source: BookSource,
    var baseUrl: String,
    val key: String? = null,
    val page: Int? = null,
    val variables: MutableMap<String, String> = mutableMapOf()
)

/**
 * legado 规则求值核心(阶段1:jsoup 默认规则 + 正则净化 + ||/&& + $.点路径;
 * JS(@js:/<js>)、XPath(//、@XPath:)分支返回空 + 日志,阶段2/P2 实装)。
 *
 * 规则分派优先级(与 legado 对齐):
 *  1. @js: 前缀 / 含 <js> → JS(阶段1占位)
 *  2. @XPath: / // 开头  → XPath(P2占位)
 *  3. $. / @json: 开头   → JSON 点路径(最小实现)
 *  4. 其余               → jsoup 默认规则(class./id./tag./text/html/href/src/属性)
 *
 * @param jsEngine 阶段1 传 null;阶段2 注入 QuickJS 封装
 */
class RuleEngine(private val jsEngine: JsEngineApi? = null) {

    companion object { private const val TAG = "RuleEngine" }

    /** 是否含暂不支持的规则(JS/XPath),供上层标记 */
    fun isUnsupported(rule: String?): Boolean {
        val r = rule?.trim() ?: return false
        if (jsEngine == null && (r.startsWith("@js:") || r.contains("<js>"))) return true
        if (r.startsWith("@XPath:") || r.startsWith("//")) return true
        return false
    }

    /** 用 JS 引擎对当前内容求值(result=当前文本, book/baseUrl 等注入);无引擎返回空 */
    private fun runJs(content: Any, script: String, ctx: RuleContext): String {
        val js = jsEngine ?: return ""
        val resultStr = when (content) {
            is String -> content
            is Element -> content.outerHtml()
            is Document -> content.outerHtml()
            else -> content.toString()
        }
        return js.eval(script, mapOf(
            "result" to resultStr,
            "baseUrl" to ctx.baseUrl,
            "src" to resultStr,
            "key" to ctx.key,
            "page" to ctx.page
        ))
    }

    /** 处理 {{规则}} 插值:把大括号内的规则求值后替换回字符串 */
    private fun resolveInterpolation(content: Any, template: String, ctx: RuleContext): String {
        if (!template.contains("{{")) return template
        return Regex("\\{\\{(.+?)}}", RegexOption.DOT_MATCHES_ALL).replace(template) { m ->
            val inner = m.groupValues[1].trim()
            // {{js:...}} 或 直接 JS 表达式 或 规则
            when {
                inner.startsWith("@js:") -> runJs(content, inner.removePrefix("@js:"), ctx)
                else -> getString(content, inner, ctx)
            }
        }
    }

    /**
     * 取元素列表(bookList/chapterList 用)。
     * @param content jsoup Element/Document 或 String(HTML/JSON)
     */
    fun getElements(content: Any, rule: String?, ctx: RuleContext): List<Any> {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return emptyList()
        // legado 用 || 分隔备选规则,逐个尝试直到有结果
        if (r.contains("||")) {
            for (sub in r.split("||")) {
                val trimmed = sub.trim()
                if (trimmed.isEmpty()) continue
                val res = getElements(content, trimmed, ctx)
                if (res.isNotEmpty()) return res
            }
            return emptyList()
        }
        if (isUnsupported(r)) { Log.w(TAG, "getElements unsupported rule: $r"); return emptyList() }
        // <js> 管道:先跑选择器再交给 JS,或纯 JS 返回 JSON 数组文本
        if (r.contains("<js>") || r.startsWith("@js:")) {
            return jsElements(content, r, ctx)
        }
        // JSON 数组路径
        if (r.startsWith("$.") || r.startsWith("@json:")) {
            return jsonElements(content, r.removePrefix("@json:").trim())
        }
        // jsoup 选择器
        val doc = asElement(content) ?: return emptyList()
        val selector = ruleToCss(r.substringBefore("@"))  // bookList 一般不带 @属性
        return try {
            doc.select(selector).toList()
        } catch (e: Exception) {
            Log.w(TAG, "getElements select fail: $selector", e); emptyList()
        }
    }

    /** JS 规则取列表:JS 返回 JSON 数组字符串 → 解析为元素列表 */
    private fun jsElements(content: Any, rule: String, ctx: RuleContext): List<Any> {
        // 支持 选择器<js>脚本</js> 形式:先用选择器缩小范围(阶段2 简化:整块交 JS)
        val script = extractJs(rule)
        val out = runJs(content, script, ctx)
        if (out.isBlank()) return emptyList()
        // JS 常返回 JSON 数组(元素为对象或 HTML 片段字符串)
        return try {
            val t = out.trim()
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                (0 until arr.length()).mapNotNull { arr.opt(it) }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** 从规则中取出 JS 代码(@js: 前缀 或 <js>...</js>) */
    private fun extractJs(rule: String): String {
        val r = rule.trim()
        if (r.startsWith("@js:")) return r.removePrefix("@js:")
        val m = Regex("<js>([\\s\\S]*?)</js>").find(r)
        return m?.groupValues?.get(1) ?: r
    }

    /**
     * 取字符串(字段提取)。含 ||(失败回退)、&&(拼接)、## 正则净化。
     * @param isUrl true 时把结果补全为绝对地址
     */
    fun getString(content: Any, rule: String?, ctx: RuleContext, isUrl: Boolean = false): String {
        val raw = rule?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (isUnsupported(raw)) { Log.w(TAG, "getString unsupported: $raw"); return "" }

        // <js> 管道 或 @js: 纯脚本:整块交给 JS 引擎
        if (raw.startsWith("@js:") || raw.contains("<js>")) {
            var r = runJs(content, extractJs(raw), ctx).trim()
            if (isUrl && r.isNotEmpty()) r = NetworkUtils.absoluteUrl(ctx.baseUrl, r)
            return r
        }
        // {{...}} 插值
        val interpolated = if (raw.contains("{{")) resolveInterpolation(content, raw, ctx) else raw

        // 分离净化正则(## 之后)
        val (rulePart, purify) = splitPurify(interpolated)

        // || 回退:任一非空即返回
        val orParts = splitTop(rulePart, "||")
        var result = ""
        for (part in orParts) {
            // && 拼接
            val andParts = splitTop(part, "&&")
            val sb = StringBuilder()
            for (ap in andParts) {
                sb.append(evalSingle(content, ap.trim(), ctx))
            }
            result = sb.toString()
            if (result.isNotEmpty()) break
        }

        // 净化
        if (purify.isNotEmpty()) result = applyPurify(result, purify)
        result = result.trim()

        if (isUrl && result.isNotEmpty()) result = NetworkUtils.absoluteUrl(ctx.baseUrl, result)
        return result
    }

    /** 单条规则求值(不含 ||/&&/##) */
    private fun evalSingle(content: Any, rule: String, ctx: RuleContext): String {
        if (rule.isEmpty()) return ""
        // @js: 内嵌脚本
        if (rule.startsWith("@js:")) return runJs(content, rule.removePrefix("@js:"), ctx)
        // 常量文本("xxx" 或 'xxx')
        if ((rule.startsWith("\"") && rule.endsWith("\"")) || (rule.startsWith("'") && rule.endsWith("'"))) {
            return rule.substring(1, rule.length - 1)
        }
        // JSON 路径
        if (rule.startsWith("$.") || rule.startsWith("@json:")) {
            return jsonString(content, rule.removePrefix("@json:").trim())
        }
        // jsoup: 形如 class.name.0@tag.a.0@text / id.xxx@href / tag.img@src
        return jsoupString(content, rule, ctx)
    }

    // ══════════════════════════════════════ jsoup ══════════════════════════════════════

    /** 解析 legado jsoup 规则:按 @ 分段,最后一段可能是取值(text/html/href/src/attr) */
    private fun jsoupString(content: Any, rule: String, ctx: RuleContext): String {
        val el = asElement(content) ?: return ""
        val parts = rule.split("@")
        if (parts.isEmpty()) return ""
        // 最后一段判断是否取值动作
        val lastRaw = parts.last().trim()
        val (selectorParts, action) = if (isValueAction(lastRaw)) {
            parts.dropLast(1) to lastRaw
        } else parts to "text"

        // 逐段 select(支持 class.x.index / id.x / tag.x.index / 纯 css)
        var current: List<Element> = listOf(el)
        for (seg in selectorParts) {
            if (seg.isBlank()) continue
            val css = ruleToCss(seg)
            val idx = extractIndex(seg)
            val next = mutableListOf<Element>()
            for (e in current) {
                val sel = try { e.select(css) } catch (_: Exception) { continue }
                if (idx != null) {
                    val real = if (idx < 0) sel.size + idx else idx
                    sel.getOrNull(real)?.let { next.add(it) }
                } else {
                    next.addAll(sel)
                }
            }
            current = next
            if (current.isEmpty()) return ""
        }

        val target = current.firstOrNull() ?: return ""
        return when {
            action == "text" -> current.joinToString("\n") { it.text() }.trim()
            action == "textNodes" -> target.textNodes().joinToString("\n") { it.text().trim() }
            action == "html" || action == "innerHtml" -> target.html()
            action == "all" || action == "outerHtml" -> target.outerHtml()
            action == "href" || action == "src" -> {
                // 取属性,优先绝对
                val v = target.attr(action).ifBlank { target.attr("data-$action") }
                v
            }
            action.startsWith("attr.") -> target.attr(action.removePrefix("attr."))
            else -> target.attr(action).ifBlank { target.text().trim() }
        }
    }

    /** legado 段 → jsoup CSS。class.name → .name;id.x → #x;tag.a → a;支持去尾部 .index */
    private fun ruleToCss(seg0: String): String {
        val seg = seg0.substringBefore("!")  // 去掉 !0 排除语法(阶段1忽略排除)
        val noIdx = seg.trim().let {
            // 去掉末尾 .数字(索引),保留选择器主体
            it.replace(Regex("\\.-?\\d+$"), "")
        }
        return when {
            noIdx.startsWith("class.") -> "." + noIdx.removePrefix("class.").replace(".", " .")
            noIdx.startsWith("id.") -> "#" + noIdx.removePrefix("id.")
            noIdx.startsWith("tag.") -> noIdx.removePrefix("tag.")
            noIdx.startsWith("text.") -> noIdx.removePrefix("text.")  // 近似
            else -> noIdx  // 认为是原生 css
        }
    }

    /** 从段尾提取索引(class.name.0 → 0;支持负数) */
    private fun extractIndex(seg: String): Int? {
        val m = Regex("\\.(-?\\d+)$").find(seg.substringBefore("!")) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun isValueAction(s: String): Boolean {
        val a = s.substringBefore(".")
        return a in setOf("text", "textNodes", "html", "innerHtml", "outerHtml", "all", "href", "src", "attr") ||
                s.startsWith("attr.")
    }

    // ══════════════════════════════════════ JSON 点路径 ══════════════════════════════════════

    /** $.a.b.c / $.list[*] / $.list.* 最小实现 → 元素列表(JSONObject) */
    private fun jsonElements(content: Any, path: String): List<Any> {
        val root = asJson(content) ?: return emptyList()
        val p = path.removePrefix("$").removePrefix(".").removeSuffix("[*]").removeSuffix(".*")
        // 若路径以 .* 结尾,先导航到父节点再展开数组
        if (path.removePrefix("$").removePrefix(".").endsWith(".*")) {
            val node = navigate(root, p) ?: return emptyList()
            return when (node) {
                is JSONArray -> (0 until node.length()).mapNotNull { node.opt(it) }
                is JSONObject -> node.keys().asSequence().mapNotNull { node.opt(it) }.toList()
                else -> listOf(node)
            }
        }
        val node = navigate(root, p) ?: return emptyList()
        return when (node) {
            is JSONArray -> (0 until node.length()).mapNotNull { node.opt(it) }
            else -> listOf(node)
        }
    }

    /** $.a.b 取字符串 */
    private fun jsonString(content: Any, path: String): String {
        val root = asJson(content) ?: return ""
        val p = path.removePrefix("$").removePrefix(".")
        return navigate(root, p)?.toString().orEmpty()
    }

    /** 点路径导航(支持 a.b.c 与 a[0].b) */
    private fun navigate(root: Any, path: String): Any? {
        if (path.isEmpty()) return root
        var cur: Any? = root
        for (segRaw in path.split(".")) {
            if (segRaw.isEmpty()) continue
            val arrM = Regex("^(.*?)\\[(\\d+)]$").find(segRaw)
            if (arrM != null) {
                val key = arrM.groupValues[1]
                val i = arrM.groupValues[2].toInt()
                val obj = if (key.isEmpty()) cur else (cur as? JSONObject)?.opt(key)
                cur = (obj as? JSONArray)?.opt(i)
            } else {
                cur = (cur as? JSONObject)?.opt(segRaw)
            }
            if (cur == null) return null
        }
        return cur
    }

    private fun asJson(content: Any): Any? = when (content) {
        is JSONObject, is JSONArray -> content
        is String -> try {
            val t = content.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (_: Exception) { null }
        else -> null
    }

    private fun asElement(content: Any): Element? = when (content) {
        is Element -> content
        is Document -> content
        is String -> try { org.jsoup.Jsoup.parse(content) } catch (_: Exception) { null }
        else -> null
    }

    // ══════════════════════════════════════ 净化 / 分割 ══════════════════════════════════════

    /** 分离 ## 净化部分。返回 (规则, 净化串) */
    private fun splitPurify(raw: String): Pair<String, String> {
        val idx = raw.indexOf("##")
        return if (idx < 0) raw to "" else raw.substring(0, idx) to raw.substring(idx + 2)
    }

    /** 净化:pattern##replacement###(legado 语法)。这里 purify 已去掉开头 ## */
    private fun applyPurify(input: String, purify: String): String {
        // purify 形如 pattern 或 pattern##replacement,可能带尾部 ### 表示只替第一个
        val onlyFirst = purify.endsWith("###")
        val body = purify.removeSuffix("###")
        val segs = body.split("##")
        val pattern = segs.getOrNull(0).orEmpty()
        val replacement = segs.getOrNull(1).orEmpty()
        if (pattern.isEmpty()) return input
        return try {
            val re = Regex(pattern)
            if (onlyFirst) re.replaceFirst(input, replacement) else re.replace(input, replacement)
        } catch (e: Exception) {
            Log.w(TAG, "purify fail: $pattern", e); input
        }
    }

    /** 顶层分割(不进入引号/js;阶段1 简化:直接按分隔符切,兼顾常见书源) */
    private fun splitTop(s: String, delim: String): List<String> {
        if (!s.contains(delim)) return listOf(s)
        return s.split(delim)
    }
}

/**
 * JS 引擎接口(阶段2 由 QuickJS 实现;阶段1 为 null)。
 * 抽象出来使 RuleEngine 不直接依赖具体引擎,便于回退 Rhino。
 */
interface JsEngineApi {
    fun eval(script: String, bindings: Map<String, Any?>): String
}
