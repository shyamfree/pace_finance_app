package com.pace.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object NotificationHelper{
 const val CHANNEL="pace_transactions"
 fun show(c:Context,t:JSONObject){
  val nm=c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel(CHANNEL,"Transaction alerts",NotificationManager.IMPORTANCE_HIGH).apply{description="Bank and payment transaction review alerts"})
  val raw=t.toString()
  val review=PendingIntent.getActivity(c,raw.hashCode(),Intent(c,MainActivity::class.java).putExtra("review",true).putExtra("tx",raw).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val ignore=PendingIntent.getBroadcast(c,raw.hashCode()+1,Intent(c,IgnoreReceiver::class.java).putExtra("fp",t.optString("fingerprint")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val amount=String.format("₹%.2f",t.optDouble("amount"))
  val n=NotificationCompat.Builder(c,CHANNEL).setSmallIcon(com.pace.app.R.drawable.pace_icon).setContentTitle("New ${if(t.optString("type")=="income")"credit" else "expense"} detected").setContentText("$amount · ${t.optString("note","Transaction")}").setStyle(NotificationCompat.BigTextStyle().bigText("$amount\n${t.optString("note","Transaction")}\nReview before adding to Pace.")).setAutoCancel(false).setPriority(NotificationCompat.PRIORITY_HIGH).addAction(0,"Review",review).addAction(0,"Ignore",ignore).setContentIntent(review).build()
  nm.notify(t.optString("fingerprint").hashCode(),n)
 }
 fun cancel(c: Context, fingerprint: String) {
  if (fingerprint.isBlank()) return
  (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(fingerprint.hashCode())
 }
}
class IgnoreReceiver:android.content.BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val fp=i.getStringExtra("fp")?:return;TransactionStore.ignored(c,fp);TransactionStore.removePending(c,fp);(c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(fp.hashCode())}}
