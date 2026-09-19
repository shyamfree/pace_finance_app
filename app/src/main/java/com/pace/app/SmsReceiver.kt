package com.pace.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION)return
        for(sms in Telephony.Sms.Intents.getMessagesFromIntent(intent)){
            val body=sms.messageBody ?: continue; val sender=sms.originatingAddress ?: ""
            val r=TransactionParser.parse(context,"SMS",sender,body) ?: continue
            val t=r.tx
            if(TransactionStore.addPending(context,t)) NotificationHelper.show(context,t)
        }
    }
}
