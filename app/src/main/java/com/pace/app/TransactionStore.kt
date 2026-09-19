package com.pace.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object TransactionStore {
    private const val PREF = "pace_native"
    private const val PENDING = "pending"
    private const val IGNORED = "ignored"
    private const val RULES = "rules"

    fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun fingerprint(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim().hashCode().toString()

    fun addPending(c: Context, tx: JSONObject): Boolean {
        val p = prefs(c)
        val arr = JSONArray(p.getString(PENDING, "[]"))
        val fp = tx.optString("fingerprint")
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("fingerprint") == fp && fp.isNotEmpty()) return false
        arr.put(tx); p.edit().putString(PENDING, arr.toString()).apply(); return true
    }

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
        a.put(JSONObject().put("name","Generic bank debit/credit").put("sender","bank").put("regex","(?i)(?:debited|credited|withdrawn|spent|paid|received).*?(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)").put("enabled",true))
        p.edit().putString(RULES,a.toString()).apply(); return a
    }

    fun saveRules(c: Context, a: JSONArray) { prefs(c).edit().putString(RULES,a.toString()).apply() }

    fun ignored(c: Context, fp: String) { val a=JSONArray(prefs(c).getString(IGNORED,"[]")); a.put(fp); prefs(c).edit().putString(IGNORED,a.toString()).apply() }
    fun isIgnored(c: Context, fp: String): Boolean { val a=JSONArray(prefs(c).getString(IGNORED,"[]")); for(i in 0 until a.length()) if(a.optString(i)==fp)return true; return false }

    fun newId() = UUID.randomUUID().toString()
}
