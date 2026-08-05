package go.examate.hyp.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skill 管理器:管理本地 Skills 文件
 *
 * Skills 采用 Anthropic Agent Skills 开放标准格式:
 * - 每个 Skill 是一个 SKILL.md 文件
 * - 文件开头是 YAML frontmatter(--- 包裹),包含 name 和 description
 * - 后面是 Markdown 内容:详细的指令、示例、注意事项
 *
 * Agent 对话时,已启用的 Skills 内容会被注入到系统提示词,
 * 让 AI 按特定方式工作(类似 Claude Code 的 Skills 机制)。
 *
 * 存储位置:app 私有目录 skills/
 * 元数据(启用状态等)存储在 PrefsManager
 */
object SkillManager {

    private const val DIR_NAME = "skills"

    /** Skill 数据模型 */
    data class Skill(
        val id: String,          // 文件名(不含扩展名),作为唯一标识
        val name: String,        // YAML frontmatter 中的 name
        val description: String, // YAML frontmatter 中的 description
        val content: String,     // Markdown 正文(指令内容)
        val enabled: Boolean,    // 是否启用
        val source: String = "local"  // 来源:local/imported
    )

    /** 获取 skills 目录 */
    private fun skillsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 加载所有 Skills */
    fun loadAll(context: Context): List<Skill> {
        val dir = skillsDir(context)
        val prefs = context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".md") || f.name.endsWith(".skill")) }
            ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                val text = file.readText()
                parseSkill(file.nameWithoutExtension, text, prefs.getBoolean(file.nameWithoutExtension, true))
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }

    /** 解析 SKILL.md 文件 */
    private fun parseSkill(id: String, text: String, enabled: Boolean): Skill? {
        // 解析 YAML frontmatter
        val fmRegex = Regex("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$")
        val match = fmRegex.matchEntire(text) ?: return null

        val frontmatter = match.groupValues[1]
        val content = match.groupValues[2].trim()

        // 简单解析 YAML(name: xxx / description: xxx)
        var name = id
        var description = ""
        frontmatter.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring(5).trim().removeSurrounding("\"")
            } else if (trimmed.startsWith("description:")) {
                description = trimmed.substring(12).trim().removeSurrounding("\"")
            }
        }

        return Skill(id, name, description, content, enabled)
    }

    /** 导入 Skill(从文本内容)。必须含 YAML frontmatter,否则拒绝(避免"导入成功但列表不显示") */
    fun importSkill(context: Context, fileName: String, content: String): Boolean {
        return try {
            if (!Regex("^---\\s*\\n[\\s\\S]*?\\n---\\s*\\n").containsMatchIn(content.trimStart())) return false
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(skillsDir(context), "$safeName.md")
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 从文件导入 */
    fun importFromFile(context: Context, sourceFile: File): Boolean {
        return try {
            val content = sourceFile.readText()
            importSkill(context, sourceFile.nameWithoutExtension, content)
        } catch (e: Exception) {
            false
        }
    }

    /** 删除 Skill */
    fun deleteSkill(context: Context, id: String): Boolean {
        val file = File(skillsDir(context), "$id.md")
        return file.delete()
    }

    /** 设置启用状态 */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)
            .edit().putBoolean(id, enabled).apply()
    }

    /** 获取已启用的 Skills(用于注入系统提示词) */
    fun loadEnabled(context: Context): List<Skill> {
        return loadAll(context).filter { it.enabled }
    }

    /**
     * 将已启用的 Skills 内容构建为系统提示词片段
     * 以自然的方式融入对话,而非刻板的指令
     */
    fun buildSkillsPrompt(context: Context): String {
        val skills = loadEnabled(context)
        if (skills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## 技能(Skills)\n")
        sb.append("你已装备以下技能,它们赋予你在特定场景下的专业能力。根据对话需要自然运用:\n\n")
        skills.forEach { skill ->
            sb.append("### ${skill.name}\n")
            sb.append("${skill.description}\n\n")
            sb.append(skill.content)
            sb.append("\n\n---\n")
        }
        return sb.toString()
    }

    /**
     * 构建 Skills 索引(渐进披露):只注入名称+简介,不注入全文,
     * 大幅降低每轮请求的输入 token。AI 判断某技能相关时,用 get_skill 工具取回全文。
     */
    fun buildSkillsIndexPrompt(context: Context): String {
        val skills = loadEnabled(context)
        if (skills.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## 技能(Skills)\n")
        sb.append("你已装备以下技能(仅列出名称与简介)。当某技能与当前任务相关时,调用 get_skill 工具获取其完整说明后再执行:\n")
        skills.forEach { sb.append("- ${it.name}: ${it.description}\n") }
        return sb.toString()
    }

    /** 按名称或 id 取回某技能的完整正文(供 get_skill 工具使用) */
    fun getSkillContent(context: Context, name: String): String? {
        return loadAll(context).firstOrNull { it.name == name || it.id == name }?.content
    }

    /** get_skill 工具:AI 判断某技能相关时,取回其完整说明与指令 */
    val GetSkill = object : ToolRegistry.Tool {
        override val name = "get_skill"
        override val description = "获取某个已装备技能的完整说明与指令(当技能与当前任务相关时调用)"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "name(技能名称,必填)"
        override val params = listOf(ToolRegistry.ParamDef("name", "string", "技能名称", true))

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val name = args.optString("name", "").ifEmpty { return ToolRegistry.Result(false, "缺少 name 参数") }
            val content = getSkillContent(context, name)
                ?: return ToolRegistry.Result(false, "未找到技能: $name")
            return ToolRegistry.Result(true, content)
        }
    }

    /**
     * 创建内置 Skills(首次运行时调用)
     * 规范:SKILL.md 必须以 YAML frontmatter 开头(顶格 --- 包裹),
     * 包含 name 和 description 字段,后接 Markdown 指令正文。
     *
     * 内置 Skills 存放在 assets/skills/ 目录下,启动时拷贝到 filesDir/skills/。
     * 只在目标文件不存在时拷贝,避免覆盖用户修改。
     */
    fun ensureBuiltinSkills(context: Context) {
        val dir = skillsDir(context)
        val prefs = context.getSharedPreferences("agent_skills", Context.MODE_PRIVATE)
        // 内置 skill 内容版本:每次更新 assets/skills 内容时 +1,触发覆盖刷新(用户导入的其他文件不受影响)
        val refresh = prefs.getInt("__builtin_version", 0) < BUILTIN_VERSION
        val assetManager = context.assets
        try {
            val skillFiles = assetManager.list("skills") ?: return
            for (fileName in skillFiles) {
                val targetFile = File(dir, fileName)
                // 首次拷贝;或内置版本升级时覆盖刷新
                if (!targetFile.exists() || refresh) {
                    assetManager.open("skills/$fileName").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            if (refresh) prefs.edit().putInt("__builtin_version", BUILTIN_VERSION).apply()
        } catch (e: Exception) {
            // assets 读取失败,忽略
        }
    }

    /** 内置 skill 内容版本号。更新 assets 下 skills 的 md 内容后 +1,使已装机用户获得刷新 */
    private const val BUILTIN_VERSION = 2

    /** 导出 Skill 为文本(用于分享) */
    fun exportSkill(skill: Skill): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: ${skill.name}\n")
        sb.append("description: ${skill.description}\n")
        sb.append("---\n\n")
        sb.append(skill.content)
        return sb.toString()
    }
}
