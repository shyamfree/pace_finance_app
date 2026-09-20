package com.pace.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object NotificationHelper {
    const val CHANNEL = "pace_transactions"
    private const val REMINDER_CHANNEL = "pace_daily_reminder"

    fun show(c: Context, t: JSONObject) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)
        val raw = t.toString()
        val review = PendingIntent.getActivity(
            c, raw.hashCode(),
            Intent(c, MainActivity::class.java).putExtra("review", true).putExtra("tx", raw)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ignore = PendingIntent.getBroadcast(
            c, raw.hashCode() + 1,
            Intent(c, IgnoreReceiver::class.java).putExtra("fp", t.optString("fingerprint")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val amount = String.format("₹%.2f", t.optDouble("amount"))
        val merchant = t.optString("merchant").ifBlank { "Transaction" }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.pace_icon)
            .setContentTitle("New ${if (t.optString("type") == "income") "income" else "expense"}")
            .setContentText("$amount · $merchant")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$amount\n$merchant\nReview before adding to Pace."))
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Review", review)
            .addAction(0, "Ignore", ignore)
            .setContentIntent(review)
            .build()
        nm.notify(t.optString("fingerprint").hashCode(), n)
    }

    fun showDailyReminder(c: Context) {
        val prefs = c.getSharedPreferences("pace_reminder", Context.MODE_PRIVATE)
        val note = prefs.getString("note", "Review today's expenses")?.ifBlank { "Review today's expenses" } ?: "Review today's expenses"
        val summary = try { JSONObject(prefs.getString("summary", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val expense = summary.optDouble("expense", 0.0)
        val income = summary.optDouble("income", 0.0)
        val top = summary.optString("topCategory", "")
        val currency = summary.optString("currency", "INR")
        val symbol = when (currency) { "INR" -> "₹"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> currency + " " }
        val details = "Today's expenses: $symbol${String.format("%.2f", expense)} · Income: $symbol${String.format("%.2f", income)}" +
            if (top.isNotBlank()) " · Highest category: $top" else ""
        val intent = PendingIntent.getActivity(
            c, 7302, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)
        nm.notify(7302, NotificationCompat.Builder(c, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.pace_icon)
            .setContentTitle("Pace daily money check")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$details\n\n$note"))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build())
    }

    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transaction alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Payment notification transaction review alerts"
            })
            nm.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Daily money reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily Pace expense and budget reminders"
            })
        }
    }

    fun cancel(c: Context, fingerprint: String) {
        if (fingerprint.isBlank()) return
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(fingerprint.hashCode())
    }
}

class IgnoreReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val fp = i.getStringExtra("fp") ?: return
        TransactionStore.ignored(c, fp)
        TransactionStore.removePending(c, fp)
        NotificationHelper.cancel(c, fp)
    }
}
