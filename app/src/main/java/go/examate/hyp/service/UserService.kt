package go.examate.hyp.service

import android.util.Log

import go.examate.hyp.ExecResult
import go.examate.hyp.IUserService

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class UserService : IUserService.Stub() {

    init {
        Log.i("UserService", "constructor")
    }

    override fun destroy() {
        Log.i("UserService", "destroy")
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String?): ExecResult {
        var process: Process? = null
        val stdoutSB = StringBuilder()
        val stderrSB = StringBuilder()

        val result = ExecResult()

        // 检查命令是否为空或仅包含空白字符
        if (command == null || command.trim().isEmpty()) {
            result.stdout = ""
            result.stderr = "Error: Empty command"
            result.exitCode = -1
            return result
        }

        try {
            // 分割命令（按一个或多个空白字符）
            val cmdArray = command.trim().split("\\s+".toRegex())
            process = Runtime.getRuntime().exec(cmdArray.toTypedArray())

            // 读取标准输出
            BufferedReader(InputStreamReader(process.inputStream)).use { stdoutReader ->
                var line: String?
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdoutSB.append(line).append('\n')
                }
            }

            // 读取标准错误
            BufferedReader(InputStreamReader(process.errorStream)).use { stderrReader ->
                var line: String?
                while (stderrReader.readLine().also { line = it } != null) {
                    stderrSB.append(line).append('\n')
                }
            }

            // 等待进程结束并获取退出码
            result.exitCode = process.waitFor()
            result.stdout = stdoutSB.toString()
            result.stderr = stderrSB.toString()

        } catch (e: SecurityException) {
            result.stdout = ""
            result.stderr = "Permission denied: " + e.message
            result.exitCode = -2
        } catch (e: IOException) {
            result.stdout = ""
            result.stderr = "IO error: " + e.message
            result.exitCode = -3
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            result.stdout = ""
            result.stderr = "Execution interrupted: " + e.message
            result.exitCode = -4
        } finally {
            process?.destroyForcibly()
        }
        return result
    }
}
