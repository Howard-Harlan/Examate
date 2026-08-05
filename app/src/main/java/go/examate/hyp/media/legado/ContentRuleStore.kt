package go.examate.hyp.media.legado

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内容净化规则存储 — SharedPreferences(JSON 数组)。
 *
 * 每条规则:name(名称)+ rules(字符串,每行一个 pattern##replacement)。
 * 在 [BookSourceRepository.getContent] 中,源自带 replaceRegex 净化之后追加用户规则。
 *
 * 导入格式兼容 legado 净化规则 JSON:
 * - 可能是数组,每项含 name 与 rules 字段;
 * - 也可能是单个对象;
 * - rules 既可以是字符串(每行 pattern##replacement),也可以是对象数组
 *   ([{"regex":"...","replacement":"..."}],legado 的 replaceRule 形态)。
 */
object ContentRuleStore {

    private const val PREFS = "content_rule_prefs"
    private const val KEY_RULES = "rules"

    /** 一条净化规则。rules 为多行文本,每行一个 pattern##replacement */
    data class Rule(val id: String, val name: String, val rules: String)

    /** 加载全部已导入的净化规则 */
    fun loadAll(context: Context): List<Rule> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = sp.getString(KEY_RULES, "[]") ?: "[]"
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val list = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "未命名规则")
            val rules = o.optString("rules", "")
            val id = o.optString("id", "${name}_$i")
            list.add(Rule(id, name, rules))
        }
        return list
    }

    /**
     * 导入净化规则 JSON(兼容 legado 格式,数组或单对象)。
     * 已有规则保留,新规则追加。
     * @return 成功导入条数
     */
    fun importFromJson(context: Context, jsonText: String): Int {
        val incoming = parse(jsonText)
        if (incoming.isEmpty()) return 0
        val merged = loadAll(context).toMutableList().apply { addAll(incoming) }
        save(context, merged)
        return incoming.size
    }

    /** 删除指定 id 的规则 */
    fun delete(context: Context, id: String) {
        save(context, loadAll(context).filterNot { it.id == id })
    }

    /** 清空全部规则 */
    fun clearAll(context: Context) {
        save(context, emptyList())
    }

    /** 持久化规则列表 */
    private fun save(context: Context, list: List<Rule>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("rules", r.rules)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }

    /**
     * 解析 legado 净化规则 JSON。
     * 支持数组与单对象;rules 字段支持字符串或对象数组。
     */
    private fun parse(jsonText: String): List<Rule> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()
        val arr: JSONArray = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONArray().also { it.put(JSONObject(trimmed)) }   // 单对象包成数组
        } catch (_: Exception) {
            return emptyList()
        }
        val list = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "未命名规则").ifBlank { "未命名规则" }
            val rulesStr = rulesToString(o.opt("rules"))
            if (rulesStr.isEmpty() && name == "未命名规则") continue
            val id = "${name}_${System.currentTimeMillis()}_$i"
            list.add(Rule(id, name, rulesStr))
        }
        return list
    }

    /** 把 rules 字段统一转成多行字符串(每行 pattern##replacement) */
    private fun rulesToString(raw: Any?): String {
        return when (raw) {
            is JSONArray -> buildString {
                for (j in 0 until raw.length()) {
                    val item = raw.optJSONObject(j) ?: continue
                    val re = item.optString("regex", item.optString("pattern", ""))
                    val rep = item.optString("replacement", "")
                    if (re.isNotEmpty()) append(re).append("##").append(rep).append("\n")
                }
            }.trimEnd()
            is String -> raw
            else -> ""
        }
    }
}
