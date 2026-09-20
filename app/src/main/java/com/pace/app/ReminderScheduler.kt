package com.pace.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object ReminderScheduler {
    private const val PREF = "pace_reminder"
    private const val ENABLED = "enabled"
    private const val TIME = "time"
    private const val NOTE = "note"
    private const val SUMMARY = "summary"
    private const val REQUEST = 7301

    fun saveAndSchedule(context: Context, enabled: Boolean, time: String, note: String) {
        val safeTime = if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) time else "20:00"
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled)
            .putString(TIME, safeTime)
            .putString(NOTE, note.trim().take(160))
            .apply()
        schedule(context)
    }

    fun saveSummary(context: Context, json: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(SUMMARY, json).apply()
    }

    fun schedule(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        alarm.cancel(pi)
        if (!prefs.getBoolean(ENABLED, false)) return

        val parts = prefs.getString(TIME, "20:00")!!.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    fun postReminder(context: Context) {
        NotificationHelper.showDailyReminder(context)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
