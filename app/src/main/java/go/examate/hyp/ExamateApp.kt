package go.examate.hyp

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口:注册全局未捕获异常处理器,把崩溃堆栈落盘,
 * 解决"偶发闪退、复测不出、没有日志"的排查困境。
 *
 * 崩溃日志写入 filesDir/workspace/crash_logs/(即 AI 文件工具的沙箱内),
 * 闪退后可直接在聊天里让 AI 用 list_dir / read_file 查看堆栈。
 * 只保留最近 5 个崩溃文件。
 */
class ExamateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val dir = File(File(filesDir, "workspace"), "crash_logs").apply { mkdirs() }
                val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                File(dir, "crash_$time.txt").writeText(buildString {
                    append("time: ").append(time).append('\n')
                    append("thread: ").append(thread.name).append('\n')
                    append("version: ").append(runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrDefault("?")).append('\n')
                    append('\n')
                    append(Log.getStackTraceString(e))
                })
                // 只保留最近 5 个,避免堆积
                dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            } catch (_: Throwable) { /* 落盘失败不阻断系统处理 */ }
            // 交还系统默认处理(结束进程/弹框)
            previous?.uncaughtException(thread, e) ?: kotlin.system.exitProcess(1)
        }
    }
}
