package com.pace.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class TransactionNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val big = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val body = listOf(title, text, big).filter { it.isNotBlank() }.distinct().joinToString(" — ")
        if (body.isBlank()) return
        val r = TransactionParser.parse(this, "NOTIFICATION:${sbn.packageName}", sbn.packageName, body) ?: return
        if (TransactionStore.addPending(this, r.tx)) NotificationHelper.show(this, r.tx)
    }
}
