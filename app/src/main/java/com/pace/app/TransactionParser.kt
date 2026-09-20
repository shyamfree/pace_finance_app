package com.pace.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransactionParser {
    data class Result(val tx: JSONObject)

    private val amountPatterns = listOf(
        Regex("(?i)(?:₹|INR|Rs\\.?)[ \\t]*([0-9,]+(?:\\.[0-9]{1,2})?)"),
        Regex("(?i)(?:amount|amt)[ \\t]*(?:is|:)?[ \\t]*(?:₹|INR|Rs\\.?)[ \\t]*([0-9,]+(?:\\.[0-9]{1,2})?)")
    )

    fun parse(context: Context, source: String, sender: String?, body: String, timestampMillis: Long = System.currentTimeMillis()): Result? {
        if (body.isBlank()) return null
        val rules = TransactionStore.rules(context)
        val matchedRule = findMatchingRule(rules, sender, body)
        if (matchedRule == null && !looksLikeTransaction(body)) return null

        val amount = extractAmount(body, matchedRule?.optString("regex")) ?: return null
        val lower = body.lowercase(Locale.US)
        val type = classifyType(lower)
        val merchant = extractMerchant(body, type)
        val upiRef = extractUpiReference(body)
        val date = extractDate(body) ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMillis))
        val fp = TransactionStore.transactionFingerprint(type, amount, merchant, upiRef, date, sender ?: "", body)
        if (TransactionStore.isIgnored(context, fp)) return null

        val category = guessCategory(merchant, body)
        return Result(JSONObject()
            .put("id", TransactionStore.newId())
            .put("type", type)
            .put("amount", amount)
            .put("category", category)
            .put("date", date)
            .put("note", merchant.ifBlank { body.take(80) })
            .put("merchant", merchant)
            .put("upiReference", upiRef)
            .put("source", source)
            .put("sender", sender ?: "")
            .put("raw", body.take(1000))
            .put("fingerprint", fp)
            .put("rule", matchedRule?.optString("name", "") ?: "Generic transaction detection"))
    }

    private fun findMatchingRule(rules: org.json.JSONArray, sender: String?, body: String): JSONObject? {
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (!r.optBoolean("enabled", true)) continue
            val senderNeed = r.optString("sender").trim()
            if (senderNeed.isNotEmpty() && !(senderNeed.equals("bank", true) || (sender ?: "").contains(senderNeed, true))) continue
            val pattern = r.optString("regex")
            if (pattern.isNotBlank()) {
                try { if (Regex(pattern).containsMatchIn(body)) return r } catch (_: Exception) { }
            }
        }
        return if (rules.length() == 0) null else if (looksLikeTransaction(body)) JSONObject().put("name", "Built-in transaction detection") else null
    }

    private fun looksLikeTransaction(body: String): Boolean {
        val x = body.lowercase(Locale.US)
        return Regex("\\b(debited|debit|credited|credit|withdrawn|withdrawal|spent|paid|payment|received|refund|cashback|salary)\\b").containsMatchIn(x) && amountPatterns.any { it.containsMatchIn(body) }
    }

    private fun extractAmount(body: String, customPattern: String?): Double? {
        if (!customPattern.isNullOrBlank()) {
            try {
                val m = Regex(customPattern).find(body)
                val group = m?.groups?.get(1)?.value
                val value = group?.replace(",", "")?.toDoubleOrNull()
                if (value != null) return value
            } catch (_: Exception) { }
        }
        for (p in amountPatterns) {
            val m = p.find(body) ?: continue
            val value = m.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun classifyType(lower: String): String {
        // Context matters: "Acct debited ... recipient credited" is an expense.
        val accountDebit = Regex("(?:a/c|acct|account|your|my).{0,80}\\b(debited|debit)\\b").containsMatchIn(lower)
        val accountCredit = Regex("(?:a/c|acct|account|your|my).{0,80}\\b(credited|credit)\\b").containsMatchIn(lower)
        val debit = Regex("\\b(debited|debit|withdrawn|withdrawal|spent|paid|payment made|purchase)\\b").containsMatchIn(lower)
        val credit = Regex("\\b(credited|credit|received|refund|cashback|salary|deposit)\\b").containsMatchIn(lower)
        return when {
            accountDebit -> "expense"
            accountCredit -> "income"
            debit -> "expense"
            credit && !debit -> "income"
            else -> "expense"
        }
    }

    private fun extractMerchant(body: String, type: String): String {
        val patterns = if (type == "expense") listOf(
            Regex("(?i)\\bto\\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\\s+(?:on|via|using|ref|txn|upi)\\b|[.;,]|$)"),
            Regex("(?i)\\bmerchant\\s*[:=-]?\\s*([A-Za-z0-9@._&' -]{2,60})"),
            Regex("(?i)\\b(?:at|for)\\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\\s+(?:on|via|ref|txn|upi)\\b|[.;,]|$)"),
            Regex("(?i)\\b(?:towards|to VPA)\\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\\s+(?:on|via|ref|txn|upi)\\b|[.;,]|$)"),
            Regex("(?i)[;,\\-]\\s*([A-Za-z][A-Za-z0-9 .&_-]{1,50}?)\\s+credited\\b")
        ) else listOf(
            Regex("(?i)\\bfrom\\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\\s+(?:on|via|ref|txn|upi)\\b|[.;,]|$)"),
            Regex("(?i)\\b(?:salary|refund|cashback)\\s+(?:from|by)\\s+([A-Za-z0-9@._&' -]{2,60}?)(?=\\s+(?:on|via|ref|txn|upi)\\b|[.;,]|$)")
        )
        for (p in patterns) {
            val m = p.find(body) ?: continue
            val value = m.groupValues.getOrNull(1)?.trim()?.trim('.', ';', ',') ?: continue
            if (value.length >= 2) return value
        }
        return ""
    }

    private fun extractUpiReference(body: String): String {
        val patterns = listOf(
            Regex("(?i)\\bUPI\\s*[:#-]?\\s*([0-9]{8,20})\\b"),
            Regex("(?i)\\b(?:UTR|RRN|Txn(?:\\s*ID)?|Transaction(?:\\s*ID)?)\\s*[:#-]?\\s*([A-Za-z0-9-]{6,30})\\b")
        )
        for (p in patterns) p.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    private fun extractDate(body: String): String? {
        val patterns = listOf("dd-MMM-yy", "dd-MM-yy", "dd/MM/yy", "dd-MM-yyyy", "dd/MM/yyyy")
        val regexes = listOf(
            Regex("\\b([0-9]{1,2}-[A-Za-z]{3}-[0-9]{2})\\b"),
            Regex("\\b([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4})\\b")
        )
        for ((idx, r) in regexes.withIndex()) {
            val raw = r.find(body)?.groupValues?.getOrNull(1) ?: continue
            for (fmt in if (idx == 0) listOf(patterns[0]) else patterns.drop(1)) {
                try { return SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }.parse(raw)?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) } } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun guessCategory(merchant: String, body: String): String {
        val x = (merchant + " " + body).lowercase(Locale.US)
        return when {
            listOf("uber", "ola", "metro", "rapido", "fuel", "petrol", "diesel").any { x.contains(it) } -> "transport"
            listOf("electric", "water", "gas", "jio", "airtel", "vi ", "recharge", "broadband", "internet").any { x.contains(it) } -> "bills"
            listOf("swiggy", "zomato", "restaurant", "cafe", "food", "grocery", "zepto", "blinkit").any { x.contains(it) } -> "food"
            listOf("amazon", "flipkart", "myntra", "shopping").any { x.contains(it) } -> "shopping"
            listOf("salary", "payroll", "credited").any { x.contains(it) } && !x.contains("debited") -> "salary"
            else -> "other"
        }
    }
}
