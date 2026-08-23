package go.examate.hyp.campus

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import go.examate.hyp.R
import go.examate.hyp.campus.model.ScheduleStorage
import go.examate.hyp.campus.model.getSemesterStart
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 课表课前提醒:基于 AlarmManager 调度,触发后弹通知 / 响铃。
 *
 * 调度策略:
 * - 仅调度未来 7 天内的提醒(滚动调度,避免一次注册过多 PendingIntent)
 * - 仅当 meta.reminderMinutes > 0 时调度
 * - 在课程开始时间前 reminderMinutes 分钟触发
 *
 * 触发后:
 * - notification 模式:发高优先级通知
 * - ring 模式:发通知 + 播放铃声(默认或自定义)
 *
 * 调度入口:
 * - [ScheduleReminderScheduler.reschedule] 在课表保存 / 提醒设置变更 / 应用启动时调用
 * - [ScheduleReminderReceiver] 在开机完成后自动重新调度
 */
object ScheduleReminderScheduler {

    const val CHANNEL_ID = "schedule_reminder"
    const val ACTION_REMIND = "go.examate.hyp.SCHEDULE_REMINDER"
    const val ACTION_RESCHEDULE = "go.examate.hyp.SCHEDULE_RESCHEDULE"

    const val EXTRA_COURSE_NAME = "course_name"
    const val EXTRA_CLASSROOM = "classroom"
    const val EXTRA_TEACHER = "teacher"
    const val EXTRA_START_TIME = "start_time"
    const val EXTRA_REMINDER_STYLE = "reminder_style"
    const val EXTRA_RINGTONE_URI = "ringtone_uri"
    const val EXTRA_ALARM_ID = "alarm_id"

    /** 滚动调度窗口:未来 7 天 */
    private const val SCHEDULE_WINDOW_DAYS = 7

    /** 重新调度所有提醒(取消旧的 + 根据当前课表与设置安排新的) */
    fun reschedule(context: Context) {
        cancelAll(context)
        ensureChannel(context)
        val meta = ScheduleStorage.getCurrentScheduleMeta(context)
        if (meta.reminderMinutes <= 0) return

        val entries = ScheduleStorage.loadEntries(context, meta.id)
        if (entries.isEmpty()) return

        val periodTimes = runCatching { ScheduleStorage.getCurrentTimeTable(context).periods }
            .getOrDefault(go.examate.hyp.campus.model.PERIOD_TIMES)

        val semStart = getSemesterStart(meta)
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        for (dayOffset in 0 until SCHEDULE_WINDOW_DAYS) {
            val date = today.plusDays(dayOffset.toLong())
            val daysSinceStart = ChronoUnit.DAYS.between(semStart, date)
            if (daysSinceStart < 0) continue
            val week = (daysSinceStart / 7).toInt() + 1
            if (week > meta.totalWeeks) continue
            val dow = date.dayOfWeek.value

            entries
                .filter { it.dayOfWeek == dow && week in it.weeks }
                .forEach { entry ->
                    val startTime = periodTimes.getOrNull(entry.startSection - 1)?.first ?: return@forEach
                    val startLocalTime = runCatching { LocalTime.parse(startTime, fmt) }.getOrNull()
                        ?: return@forEach
                    val startDateTime = LocalDateTime.of(date, startLocalTime)
                    val triggerAt = startDateTime.minusMinutes(meta.reminderMinutes.toLong())
                    if (!triggerAt.isAfter(now)) return@forEach

                    val alarmId = "${entry.id}_${date}".hashCode()
                    val intent = Intent(context, ScheduleReminderReceiver::class.java).apply {
                        action = ACTION_REMIND
                        putExtra(EXTRA_COURSE_NAME, entry.courseName)
                        putExtra(EXTRA_CLASSROOM, entry.classroom)
                        putExtra(EXTRA_TEACHER, entry.teacher)
                        putExtra(EXTRA_START_TIME, startTime)
                        putExtra(EXTRA_REMINDER_STYLE, meta.reminderStyle)
                        putExtra(EXTRA_RINGTONE_URI, meta.ringtoneUri)
                        putExtra(EXTRA_ALARM_ID, alarmId)
                    }
                    val triggerMillis = triggerAt
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val pi = PendingIntent.getBroadcast(
                        context, alarmId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (alarmManager.canScheduleExactAlarms()) {
                                alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP, triggerMillis, pi
                                )
                            } else {
                                alarmManager.setWindow(
                                    AlarmManager.RTC_WAKEUP, triggerMillis, 60_000, pi
                                )
                            }
                        } else {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, triggerMillis, pi
                            )
                        }
                    } catch (_: SecurityException) {
                        alarmManager.setWindow(
                            AlarmManager.RTC_WAKEUP, triggerMillis, 60_000, pi
                        )
                    }
                }
        }
    }

    /** 取消当前课表未来窗口内的所有已调度提醒 */
    private fun cancelAll(context: Context) {
        val meta = ScheduleStorage.getCurrentScheduleMeta(context)
        val entries = ScheduleStorage.loadEntries(context, meta.id)
        val today = LocalDate.now()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (dayOffset in 0 until SCHEDULE_WINDOW_DAYS) {
            val date = today.plusDays(dayOffset.toLong())
            entries.forEach { entry ->
                val alarmId = "${entry.id}_${date}".hashCode()
                val intent = Intent(context, ScheduleReminderReceiver::class.java).apply {
                    action = ACTION_REMIND
                }
                val pi = PendingIntent.getBroadcast(
                    context, alarmId, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            }
        }
    }

    /** 创建通知渠道(幂等) */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "课表提醒", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "课前提醒通知"
                    enableVibration(true)
                }
                nm?.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * 课前提醒触发接收器:
 * - 收到 [ScheduleReminderScheduler.ACTION_REMIND] 时弹通知 + 可选响铃
 * - 收到 [ScheduleReminderScheduler.ACTION_RESCHEDULE] 时重新调度(开机完成 / 应用更新)
 */
class ScheduleReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ScheduleReminderScheduler.reschedule(context)
            }
            ScheduleReminderScheduler.ACTION_REMIND -> showReminder(context, intent)
            ScheduleReminderScheduler.ACTION_RESCHEDULE -> ScheduleReminderScheduler.reschedule(context)
        }
    }

    private fun showReminder(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_COURSE_NAME) ?: "课程"
        val classroom = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_CLASSROOM) ?: ""
        val teacher = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_TEACHER) ?: ""
        val startTime = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_START_TIME) ?: ""
        val style = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_REMINDER_STYLE)
            ?: "notification"
        val ringtoneUriStr = intent.getStringExtra(ScheduleReminderScheduler.EXTRA_RINGTONE_URI) ?: ""
        val alarmId = intent.getIntExtra(
            ScheduleReminderScheduler.EXTRA_ALARM_ID,
            System.currentTimeMillis().toInt()
        )

        ScheduleReminderScheduler.ensureChannel(context)

        val title = "即将上课:$courseName"
        val text = buildString {
            if (classroom.isNotEmpty()) append(classroom)
            if (teacher.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(teacher)
            }
            if (startTime.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(startTime).append(" 开始")
            }
        }

        val notif = NotificationCompat.Builder(context, ScheduleReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wait)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                NotificationManagerCompat.from(context).notify(alarmId, notif)
            }
        } catch (_: Throwable) { /* 权限被撤销时静默 */ }

        if (style == "ring") playRingtone(context, ringtoneUriStr)
    }

    private fun playRingtone(context: Context, uriString: String) {
        try {
            val uri = if (uriString.isNotEmpty()) Uri.parse(uriString)
                else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (_: Throwable) { /* 静默忽略 */ }
    }
}
