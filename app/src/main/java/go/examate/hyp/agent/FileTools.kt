package go.examate.hyp.agent

import android.content.Context
import go.examate.hyp.config.PrefsManager
import org.json.JSONObject
import java.io.File

/**
 * 文件工具集 — 在 App 私有目录的 workspace 沙箱内操作文件
 *
 * 沙箱根目录: context.filesDir/workspace/
 * AI 可创建/读取/编辑 HTML、Markdown、文本文件等
 * 生成的 HTML 文件可通过 WebView 预览
 */
object FileTools {

    /** 获取沙箱根目录 */
    fun workspaceDir(context: Context): File {
        val dir = File(context.filesDir, "workspace")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 安全路径检查:确保文件在沙箱内 */
    private fun safeFile(context: Context, path: String): File? {
        val ws = workspaceDir(context).canonicalPath
        val target = File(workspaceDir(context), path).canonicalPath
        return if (target.startsWith(ws)) File(target) else null
    }

    private val CreateFile = object : ToolRegistry.Tool {
        override val name = "create_file"
        override val description = "创建文件(支持 .html/.md/.txt/.json 等),可生成网页或文档供用户预览"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "path(文件名如\"report.html\"), content(文件内容)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val path = args.optString("path", "").ifEmpty { return ToolRegistry.Result(false, "缺少 path 参数") }
            val content = args.optString("content", "")
            val file = safeFile(context, path) ?: return ToolRegistry.Result(false, "非法路径")
            file.parentFile?.mkdirs()
            file.writeText(content)
            val absPath = file.absolutePath
            val msg = if (path.endsWith(".html")) "HTML 文件已创建: $path\n可在聊天中预览"
                else "文件已创建: $path"
            return ToolRegistry.Result(true, msg, filePath = absPath)
        }
    }

    private val ReadFile = object : ToolRegistry.Tool {
        override val name = "read_file"
        override val description = "读取沙箱内的文件内容"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "path(文件名)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val path = args.optString("path", "").ifEmpty { return ToolRegistry.Result(false, "缺少 path 参数") }
            val file = safeFile(context, path) ?: return ToolRegistry.Result(false, "非法路径")
            if (!file.exists() || !file.isFile) return ToolRegistry.Result(false, "文件不存在: $path")
            val content = file.readText()
            val truncated = if (content.length > 5000) content.take(5000) + "\n...(内容过长,已截断)" else content
            return ToolRegistry.Result(true, "文件内容:\n$truncated")
        }
    }

    private val ListDir = object : ToolRegistry.Tool {
        override val name = "list_dir"
        override val description = "列出沙箱目录中的文件"
        override val category = ToolRegistry.Category.SAFE
        override val parametersHint = "path(子目录,可选,默认根目录)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val path = args.optString("path", "")
            val dir = if (path.isEmpty()) workspaceDir(context) else safeFile(context, path)
            dir ?: return ToolRegistry.Result(false, "非法路径")
            if (!dir.exists() || !dir.isDirectory) return ToolRegistry.Result(false, "目录不存在")
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) return ToolRegistry.Result(true, "目录为空")
            val sb = StringBuilder("文件列表:\n| 名称 | 类型 | 大小 |\n|---|---|---|\n")
            files.forEach { f ->
                val type = if (f.isDirectory) "目录" else "文件"
                val size = if (f.isFile) "${f.length()} bytes" else "-"
                sb.append("| ${f.name} | $type | $size |\n")
            }
            return ToolRegistry.Result(true, sb.toString())
        }
    }

    private val EditFile = object : ToolRegistry.Tool {
        override val name = "edit_file"
        override val description = "编辑已有文件(替换内容或追加)。生成大文件时建议先创建骨架,再用 append 模式分多次追加,避免单次输出被截断"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "path(文件名), content(新内容), mode(\"replace\"替换/\"append\"追加,默认replace)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val path = args.optString("path", "").ifEmpty { return ToolRegistry.Result(false, "缺少 path 参数") }
            val content = args.optString("content", "")
            val mode = args.optString("mode", "replace")
            val file = safeFile(context, path) ?: return ToolRegistry.Result(false, "非法路径")
            if (!file.exists()) return ToolRegistry.Result(false, "文件不存在: $path")
            when (mode) {
                "append" -> file.appendText(content)
                else -> file.writeText(content)
            }
            return ToolRegistry.Result(true, "文件已${if (mode == "append") "追加" else "更新"}: $path", filePath = file.absolutePath)
        }
    }

    /**
     * 锚点插入工具 — 在文件的指定锚点标记处插入内容,支持分块构建大文件
     *
     * 用法:AI 先创建带锚点的骨架(如 <!-- ANCHOR:body -->),再多次调用本工具填充各锚点
     * 锚点格式:<!-- ANCHOR:名称 --> 或 {{ANCHOR:名称}} 或 %%ANCHOR:名称%%
     */
    private val InsertAtAnchor = object : ToolRegistry.Tool {
        override val name = "insert_at_anchor"
        override val description = "在文件的指定锚点处插入内容(适合分块构建大 HTML/MD)。锚点格式:<!-- ANCHOR:名称 --> 或 {{ANCHOR:名称}}。插入后锚点保留,可多次填充同一文件不同位置"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "path(文件名), anchor(锚点名称), content(要插入的内容)"

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val path = args.optString("path", "").ifEmpty { return ToolRegistry.Result(false, "缺少 path 参数") }
            val anchor = args.optString("anchor", "").ifEmpty { return ToolRegistry.Result(false, "缺少 anchor 参数") }
            val content = args.optString("content", "")
            val file = safeFile(context, path) ?: return ToolRegistry.Result(false, "非法路径")
            if (!file.exists()) return ToolRegistry.Result(false, "文件不存在: $path")

            val original = file.readText()
            // 支持三种锚点格式
            val patterns = listOf(
                "<!-- ANCHOR:$anchor -->",
                "{{ANCHOR:$anchor}}",
                "%%ANCHOR:$anchor%%"
            )
            var inserted = false
            var modified = original
            for (pattern in patterns) {
                if (modified.contains(pattern)) {
                    // 在锚点后插入内容,保留锚点(可重复填充)
                    modified = modified.replace(pattern, "$pattern\n$content")
                    inserted = true
                    break
                }
            }
            if (!inserted) {
                return ToolRegistry.Result(false, "未找到锚点: $anchor (可用格式: <!-- ANCHOR:$anchor --> 或 {{ANCHOR:$anchor}})")
            }
            file.writeText(modified)
            return ToolRegistry.Result(true, "已在锚点 '$anchor' 处插入内容(${content.length} 字符): $path", filePath = file.absolutePath)
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(CreateFile, ReadFile, ListDir, EditFile, InsertAtAnchor)
}
