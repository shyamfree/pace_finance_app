package com.pace.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransactionParser {
    data class Result(val tx: JSONObject)

    fun parse(context: Context, source: String, sender: String?, body: String): Result? {
        if (body.isBlank()) return null
        val rules = TransactionStore.rules(context)
        for (i in 0 until rules.length()) {
            val r=rules.getJSONObject(i); if(!r.optBoolean("enabled",true)) continue
            val senderNeed=r.optString("sender").trim()
            if(senderNeed.isNotEmpty() && !(senderNeed.equals("bank",true) || (sender ?: "").contains(senderNeed,true))) continue
            val pattern=r.optString("regex")
            if(pattern.isBlank()) continue
            val m=Regex(pattern).find(body) ?: continue
            val amountText=(m.groups.getOrNull(1)?.value ?: Regex("(?:₹|INR|Rs\\.?\\s*)([0-9,]+(?:\\.[0-9]{1,2})?)",RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)) ?: continue
            val amount=amountText.replace(",","").toDoubleOrNull() ?: continue
            val lower=body.lowercase()
            val income=Regex("\\b(credited|received|refund|cashback|salary)\\b").containsMatchIn(lower) && !Regex("\\b(debited|spent|paid|withdrawn)\\b").containsMatchIn(lower)
            val merchant=extractMerchant(body)
            val fp=TransactionStore.fingerprint("$source|$sender|$body")
            if(TransactionStore.isIgnored(context,fp)) return null
            return Result(JSONObject().put("id",TransactionStore.newId()).put("type",if(income)"income" else "expense").put("amount",amount).put("category",guessCategory(merchant,body)).put("date",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())).put("note",merchant.ifBlank{body.take(80)}).put("merchant",merchant).put("source",source).put("sender",sender ?: "").put("raw",body.take(500)).put("fingerprint",fp))
        }
        return null
    }

    private fun extractMerchant(body:String):String {
        val patterns=listOf(
            Regex("(?i)\\bto\\s+([A-Za-z0-9 .&_-]{2,50}?)(?:\\s+on\\s+|\\s+ref|\\s+upi|\\s+txn|\\.|$)"),
            Regex("(?i)\\bat\\s+([A-Za-z0-9 .&_-]{2,50}?)(?:\\.|\\s+on\\s+|$)")
        )
        for(p in patterns){ val m=p.find(body); if(m!=null)return m.groupValues[1].trim() }
        return ""
    }
    private fun guessCategory(merchant:String,body:String):String {
        val x=(merchant+" "+body).lowercase()
        return when { listOf("uber","ola","metro","rapido","fuel","petrol","diesel").any{x.contains(it)}->"transport"; listOf("electric","water","gas","jio","airtel","vi ","recharge","broadband","internet").any{x.contains(it)}->"bills"; listOf("swiggy","zomato","restaurant","cafe","food","grocery","zepto","blinkit").any{x.contains(it)}->"food"; listOf("amazon","flipkart","myntra","shopping").any{x.contains(it)}->"shopping"; else->"other" }
    }
}
