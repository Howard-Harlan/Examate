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

    /** 导入 Skill(从文本内容) */
    fun importSkill(context: Context, fileName: String, content: String): Boolean {
        return try {
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
     * Agent 会按照这些指令工作
     */
    fun buildSkillsPrompt(context: Context): String {
        val skills = loadEnabled(context)
        if (skills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## 已加载技能(Skills)\n")
        sb.append("以下技能描述了你在特定场景下应如何工作,请严格遵循:\n\n")
        skills.forEach { skill ->
            sb.append("### 技能: ${skill.name}\n")
            sb.append("**描述:** ${skill.description}\n\n")
            sb.append(skill.content)
            sb.append("\n\n---\n")
        }
        return sb.toString()
    }

    /**
     * 创建内置 Skills(首次运行时调用)
     * 规范:SKILL.md 必须以 YAML frontmatter 开头(顶格 --- 包裹),
     * 包含 name 和 description 字段,后接 Markdown 指令正文。
     */
    fun ensureBuiltinSkills(context: Context) {
        val dir = skillsDir(context)
        if (dir.listFiles()?.isNotEmpty() == true) return

        // 内置 Skill: 校园服务助手
        val campusSkill = """
---
name: campus-assistant
description: 校园服务查询助手,指导如何查询成绩、课表、考试等信息
---
# 校园服务查询助手

当用户询问校园相关信息时,请遵循以下流程:

## 查询流程
1. **确认查询意图**:成绩、课表、考试、一卡通余额、第二课堂等
2. **确认时间范围**:如果用户未指定学年/学期,询问"请问要查询哪个学期?如 2024-2025 第一学期"
3. **调用工具**:使用对应的工具函数获取数据
4. **格式化输出**:用 Markdown 表格展示结果

## 成绩查询
- 调用 `get_grades` 工具,参数 xn(学年如"2024-2025"), xq(学期"1"或"2")
- 输出表格列:课程 | 学分 | 成绩 | 绩点 | 学年 | 学期
- 可选:计算加权平均分和总绩点

## 课表查询
- 调用 `get_schedule` 工具
- 按星期分组展示,列:节次 | 课程 | 教师 | 教室 | 周次

## 考试安排
- 调用 `get_exam_schedule` 工具
- 列:课程 | 日期 | 地点 | 座位号
- 提醒用户考试时间

## 注意事项
- 数据来自校园服务,确保用户已登录
- 如果工具返回"未登录",提示用户先在校园服务页面登录
- 涉及隐私数据时,提醒用户注意保密
        """.trimIndent()
        File(dir, "campus-assistant.md").writeText(campusSkill)
    }

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

/**
 * 记忆管理器:管理 AI 的长期记忆
 *
 * 记忆是一些关键信息片段,会注入到系统提示词,
 * 让 AI 记住用户的偏好、重要事项等。
 */
object MemoryManager {

    private const val PREFS_NAME = "agent_memory"
    private const val KEY_MEMORIES = "memories_json"

    /** 记忆条目 */
    data class Memory(
        val id: String,
        val content: String,
        val category: String = "通用",  // 通用/偏好/重要事项/人物
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 加载所有记忆 */
    fun loadAll(context: Context): List<Memory> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEMORIES, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Memory>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(Memory(
                id = obj.getString("id"),
                content = obj.getString("content"),
                category = obj.optString("category", "通用"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            ))
        }
        return list
    }

    /** 添加记忆 */
    fun add(context: Context, content: String, category: String = "通用"): Memory {
        val memories = loadAll(context).toMutableList()
        val memory = Memory(
            id = System.currentTimeMillis().toString(),
            content = content,
            category = category
        )
        memories.add(memory)
        save(context, memories)
        return memory
    }

    /** 删除记忆 */
    fun delete(context: Context, id: String) {
        val memories = loadAll(context).filter { it.id != id }
        save(context, memories)
    }

    /** 清空记忆 */
    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, memories: List<Memory>) {
        val arr = JSONArray()
        memories.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("content", m.content)
                put("category", m.category)
                put("createdAt", m.createdAt)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MEMORIES, arr.toString()).apply()
    }

    /** 构建记忆提示词片段 */
    fun buildMemoryPrompt(context: Context): String {
        val memories = loadAll(context)
        if (memories.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## 长期记忆\n")
        sb.append("以下是关于用户的重要信息,请在对话中参考:\n\n")
        memories.forEach { m ->
            sb.append("- [${m.category}] ${m.content}\n")
        }
        return sb.toString()
    }
}
