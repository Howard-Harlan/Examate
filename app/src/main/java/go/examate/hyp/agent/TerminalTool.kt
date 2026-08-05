package go.examate.hyp.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 终端工具 — 轻量级命令执行(借鉴 openclaw CommandRunner 模式)
 *
 * 不依赖 Termux bootstrap 或 Shizuku,使用 Android 自带的 /system/bin/sh(toybox)。
 * 在 workspace 沙箱内执行,通过命令白名单限制可执行命令,超时 10s,输出截断 5000 字符。
 *
 * 可用命令:文件操作(ls/cat/cp/mv/rm/mkdir/touch)、文本处理(grep/sed/awk/wc/head/tail/
 * sort/uniq/cut/tr/expr)、查询(date/whoami/uname/df/du/find)、算术(bc/expr)等。
 */
object TerminalTool {

    private const val TAG = "TerminalTool"
    private const val TIMEOUT_MS = 10_000L
    private const val MAX_OUTPUT = 5000

    /** 允许的命令白名单(Android toybox 自带) */
    private val ALLOWED_COMMANDS = setOf(
        // 文件操作
        "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "chmod", "ln",
        "find", "stat", "file", "basename", "dirname", "realpath",
        // 文本处理
        "grep", "egrep", "fgrep", "sed", "awk", "wc", "head", "tail", "sort",
        "uniq", "cut", "tr", "paste", "expand", "fold", "fmt", "column",
        "diff", "comm", "cmp", "tee",
        // 查询/信息
        "date", "whoami", "uname", "df", "du", "env", "printenv", "id",
        "uptime", "hostname", "pwd", "echo", "printf", "test",
        // 算术/逻辑
        "expr", "seq", "yes", "true", "false",
        // 压缩
        "gzip", "gunzip", "tar", "zcat",
        // 其他
        "sleep", "xargs", "strings", "md5sum", "sha1sum", "sha256sum",
        "base64", "od", "hexdump", "nl", "rev", "tac"
    )

    /** 危险模式:命令中出现这些内容直接拒绝 */
    private val DANGEROUS_PATTERNS = listOf(
        Regex("rm\\s+(-[rf]*r[rf]*|-[rf]+)\\s+/(?!data)"),  // rm -rf / (非 /data)
        Regex("\\bsu\\b"),                                     // 提权
        Regex("\\bdd\\s+if="),                                // 磁盘写入
        Regex("\\bmkfs\\b"),                                  // 格式化
        Regex("\\bmount\\b"),                                 // 挂载
        Regex("\\bsetenforce\\b"),                            // SELinux
        Regex(">\\s*/dev/"),                                  // 写设备文件
        Regex("\\bsh\\s+-c\\s*.*/dev/"),                      // 设备操作
        Regex(":\\(\\)\\s*\\{.*\\}\\s*;\\s*:"),               // fork bomb
        Regex("\\$\\(.*\\bsu\\b")                             // 命令替换中的 su
    )

    /** 安全检查:命令是否允许执行 */
    private fun isCommandSafe(command: String): Boolean {
        val trimmed = command.trim()

        // 检查危险模式
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                Log.w(TAG, "拒绝危险命令: ${trimmed.take(100)}")
                return false
            }
        }

        // 提取管道和分号分隔的第一个 token 检查白名单
        // 将命令按管道、分号、&&、|| 分割,检查每段的首命令
        val segments = trimmed.split(Regex("\\||;|&&|\\|\\|"))
        for (segment in segments) {
            val firstToken = segment.trim().split(Regex("\\s+")).firstOrNull()
                ?.removePrefix("(")?.removePrefix("{") ?: continue
            // 跳过空段和变量赋值(如 FOO=bar)
            if (firstToken.contains("=") && !firstToken.startsWith("$")) continue
            // 跳过以 $ 开头的(变量引用)
            if (firstToken.startsWith("$")) continue
            // 检查白名单
            if (firstToken !in ALLOWED_COMMANDS) {
                Log.w(TAG, "拒绝非白名单命令: $firstToken")
                return false
            }
        }
        return true
    }

    /** 构建沙箱环境变量 */
    private fun buildSandboxEnv(context: Context): Map<String, String> {
        val ws = FileTools.workspaceDir(context)
        val home = File(ws, "home").apply { mkdirs() }
        val tmp = File(ws, "tmp").apply { mkdirs() }

        return mapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "SHELL" to "/system/bin/sh"
        )
    }

    private val RunCommand = object : ToolRegistry.Tool {
        override val name = "run_command"
        override val description = "在沙箱终端执行 shell 命令(Android toybox)。支持文件操作、文本处理、grep/awk/sed 等。" +
            "工作目录为 workspace 沙箱,无法访问系统其他目录。"
        override val category = ToolRegistry.Category.PRIVILEGED
        override val parametersHint = "command(命令字符串,如\"ls -la\"或\"grep 'error' log.txt | head -20\")"
        override val params = listOf(ToolRegistry.ParamDef("command", "string", "命令字符串", true))

        override suspend fun execute(context: Context, args: JSONObject): ToolRegistry.Result {
            val command = args.optString("command", "").ifEmpty {
                return ToolRegistry.Result(false, "缺少 command 参数")
            }

            if (!isCommandSafe(command)) {
                return ToolRegistry.Result(false, "命令被安全检查拒绝(可能包含危险操作或非白名单命令)")
            }

            val workDir = FileTools.workspaceDir(context)
            val env = buildSandboxEnv(context)

            return try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.directory(workDir)
                pb.redirectErrorStream(false)

                val process = pb.start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exited = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (!exited) {
                    process.destroyForcibly()
                    return ToolRegistry.Result(false, "命令执行超时(${TIMEOUT_MS}ms)")
                }

                val exitCode = process.exitValue()
                val output = buildString {
                    if (stdout.isNotEmpty()) {
                        append(stdout.take(MAX_OUTPUT))
                        if (stdout.length > MAX_OUTPUT) append("\n...(输出已截断)")
                    }
                    if (stderr.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("[stderr] ").append(stderr.take(MAX_OUTPUT))
                    }
                    if (isEmpty()) append("(无输出)")
                }

                if (exitCode == 0) {
                    ToolRegistry.Result(true, "退出码: 0\n$output")
                } else {
                    ToolRegistry.Result(true, "退出码: $exitCode\n$output")
                }
            } catch (e: Exception) {
                ToolRegistry.Result(false, "执行失败: ${e.message}")
            }
        }
    }

    fun all(): List<ToolRegistry.Tool> = listOf(RunCommand)
}
