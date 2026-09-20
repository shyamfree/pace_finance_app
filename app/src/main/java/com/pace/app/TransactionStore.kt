package com.pace.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object TransactionStore {
    private const val PREF = "pace_native"
    private const val PENDING = "pending"
    private const val IGNORED = "ignored"
    private const val RULES = "rules"
    private const val CATEGORIES = "categories"

    fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun fingerprint(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.lowercase().replace(Regex("\\s+"), " ").trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun transactionFingerprint(type: String, amount: Double, merchant: String, ref: String, date: String, sender: String, raw: String): String {
        // The same payment can arrive as both an SMS and a payment-app notification.
        // Never include the source package/sender or full raw message in the primary
        // fingerprint because those differ even when the underlying transaction is the same.
        val normalizedMerchant = merchant.lowercase().replace(Regex("[^a-z0-9@]+"), " ").trim()
        val normalizedRef = ref.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()
        return if (normalizedRef.isNotEmpty()) {
            fingerprint(listOf("ref", type, "%.2f".format(amount), normalizedRef).joinToString("|"))
        } else {
            val normalizedDate = date.trim()
            val merchantKey = normalizedMerchant.ifBlank { "unknown" }
            fingerprint(listOf("basic", type, "%.2f".format(amount), merchantKey, normalizedDate).joinToString("|"))
        }
    }

    fun addPending(c: Context, tx: JSONObject): Boolean {
        val p = prefs(c)
        val arr = JSONArray(p.getString(PENDING, "[]"))
        val fp = tx.optString("fingerprint")
        for (i in 0 until arr.length()) {
            val old = arr.getJSONObject(i)
            if (fp.isNotEmpty() && old.optString("fingerprint") == fp) return false
            if (equivalentTransaction(old, tx)) return false
        }
        arr.put(tx)
        p.edit().putString(PENDING, arr.toString()).apply()
        return true
    }

    private fun equivalentTransaction(a: JSONObject, b: JSONObject): Boolean {
        if (a.optString("type") != b.optString("type")) return false
        if (kotlin.math.abs(a.optDouble("amount") - b.optDouble("amount")) > 0.009) return false
        if (a.optString("date") != b.optString("date")) return false

        val ar = a.optString("upiReference").lowercase().replace(Regex("[^a-z0-9]"), "")
        val br = b.optString("upiReference").lowercase().replace(Regex("[^a-z0-9]"), "")
        if (ar.isNotEmpty() && br.isNotEmpty()) return ar == br

        val am = normalizeMerchant(a.optString("merchant").ifBlank { a.optString("note") })
        val bm = normalizeMerchant(b.optString("merchant").ifBlank { b.optString("note") })
        if (am.isBlank() || bm.isBlank()) return true
        return am == bm || am.contains(bm) || bm.contains(am)
    }

    private fun normalizeMerchant(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9@]+"), " ").trim()

    fun pending(c: Context): JSONArray = JSONArray(prefs(c).getString(PENDING, "[]"))

    fun removePending(c: Context, fingerprint: String) {
        val old = pending(c); val out = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("fingerprint") != fingerprint) out.put(old.getJSONObject(i))
        prefs(c).edit().putString(PENDING, out.toString()).apply()
    }

    fun rules(c: Context): JSONArray {
        val p = prefs(c); val raw = p.getString(RULES, null)
        if (raw != null) return JSONArray(raw)
        val a = JSONArray()
        a.put(JSONObject().put("name", "Generic bank debit/credit").put("sender", "").put("regex", "(?i)(?:debited|credited|withdrawn|spent|paid|received|refund|cashback|salary).*?(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)").put("enabled", true))
        p.edit().putString(RULES, a.toString()).apply(); return a
    }

    fun saveRules(c: Context, a: JSONArray) { prefs(c).edit().putString(RULES, a.toString()).apply() }

    fun saveCategories(c: Context, categories: JSONObject) {
        prefs(c).edit().putString(CATEGORIES, categories.toString()).apply()
    }

    fun categories(c: Context): JSONObject {
        return try { JSONObject(prefs(c).getString(CATEGORIES, "{}")) } catch (_: Exception) { JSONObject() }
    }
    fun ignored(c: Context, fp: String) { val a = JSONArray(prefs(c).getString(IGNORED, "[]")); a.put(fp); prefs(c).edit().putString(IGNORED, a.toString()).apply() }
    fun isIgnored(c: Context, fp: String): Boolean { val a = JSONArray(prefs(c).getString(IGNORED, "[]")); for (i in 0 until a.length()) if (a.optString(i) == fp) return true; return false }
    fun newId() = UUID.randomUUID().toString()
}
