package com.pace.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.Telephony
import android.provider.Settings
import android.view.View
import android.widget.*
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val smsReq = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : android.webkit.WebViewClient() { override fun onPageFinished(v: WebView, url: String) { syncPending() } }
            addJavascriptInterface(NativeBridge(this@MainActivity), "PaceNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        requestPermissionsIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun requestPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), smsReq)
    }

    private fun handleIntent(i: Intent) {
        if (i.getBooleanExtra("review", false)) {
            val raw = i.getStringExtra("tx") ?: return
            try { review(JSONObject(raw)) } catch (_: Exception) { }
        }
    }

    private fun syncPending() {
        // Pending live transactions must NEVER be imported directly into the web UI.
        // They are intentionally held until the user explicitly chooses Add or Ignore
        // from the review dialog. This prevents a transaction from being added once
        // automatically and again after the user reviews it.
        // The Android notification remains the review entry point.
    }

    private fun jsQuote(v: String): String = JSONObject.quote(v)

    private fun addToWeb(t: JSONObject) {
        val js = "window.PaceNativeImport && window.PaceNativeImport({" +
                "type:" + jsQuote(t.optString("type")) + "," +
                "amount:" + t.optDouble("amount") + "," +
                "category:" + jsQuote(t.optString("category", "other")) + "," +
                "date:" + jsQuote(t.optString("date")) + "," +
                "note:" + jsQuote(t.optString("note")) + "," +
                "merchant:" + jsQuote(t.optString("merchant")) + "," +
                "upiReference:" + jsQuote(t.optString("upiReference")) + "," +
                "source:" + jsQuote(t.optString("source")) + "," +
                "sender:" + jsQuote(t.optString("sender")) + "," +
                "raw:" + jsQuote(t.optString("raw")) + "," +
                "fingerprint:" + jsQuote(t.optString("fingerprint")) + "});"
        web.evaluateJavascript(js, null)
    }

    fun review(t: JSONObject) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0) }
        fun field(label: String, value: String): EditText { val e = EditText(this); e.hint = label; e.setText(value); box.addView(e); return e }
        val amount = field("Amount", String.format(Locale.US, "%.2f", t.optDouble("amount")))
        val merchant = field("Merchant / note", t.optString("note"))
        val date = field("Date (yyyy-MM-dd)", t.optString("date"))
        val type = Spinner(this); type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Expense", "Income")); type.setSelection(if (t.optString("type") == "income") 1 else 0); box.addView(type)
        val cat = field("Category", t.optString("category", "other"))
        AlertDialog.Builder(this).setTitle("Review transaction")
            .setMessage("Detected from ${t.optString("source", "message")}\n${t.optString("upiReference").takeIf { it.isNotBlank() }?.let { "UPI/Reference: $it" } ?: ""}")
            .setView(box).setNegativeButton("Ignore") { _, _ ->
                val fp = t.optString("fingerprint")
                TransactionStore.ignored(this, fp)
                TransactionStore.removePending(this, fp)
                NotificationHelper.cancel(this, fp)
            }
            .setPositiveButton("Add") { _, _ ->
                val out = JSONObject(t.toString())
                out.put("amount", amount.text.toString().replace(",", "").toDoubleOrNull() ?: t.optDouble("amount"))
                out.put("note", merchant.text.toString())
                out.put("date", date.text.toString().ifBlank { t.optString("date") })
                out.put("category", cat.text.toString().ifBlank { "other" })
                out.put("type", if (type.selectedItemPosition == 1) "income" else "expense")
                addToWeb(out)
                TransactionStore.removePending(this, t.optString("fingerprint"))
                NotificationHelper.cancel(this, t.optString("fingerprint"))
            }.show()
    }

    fun openImportSettings() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 10) }
        val sms = Switch(this).apply { text = "Read bank SMS"; isChecked = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED }
        root.addView(sms)
        val n = Switch(this).apply { text = "Payment/bank notifications"; isChecked = NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity).contains(packageName) }
        root.addView(n)
        val sample = EditText(this).apply { hint = "Paste a sample bank message"; minLines = 4; gravity = android.view.Gravity.TOP }
        root.addView(sample)
        val regex = EditText(this).apply { hint = "Optional regex (amount must be capture group 1)"; minLines = 2 }
        root.addView(regex)
        val sender = EditText(this).apply { hint = "Optional sender/app keyword (e.g. ICICIB)" }
        root.addView(sender)
        val help = TextView(this).apply { text = "Example: ICICI message with 'Acct debited' is classified as Expense even if the recipient is described as credited."; setPadding(0, 12, 0, 0) }
        root.addView(help)
        AlertDialog.Builder(this).setTitle("Bank & payment imports").setView(root)
            .setNeutralButton("Notification access") { _, _ -> startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
            .setNegativeButton("Close", null)
            .setPositiveButton("Save rule") { _, _ ->
                if (sample.text.isNotBlank() || regex.text.isNotBlank()) {
                    val pattern = regex.text.toString().ifBlank { "(?i)(?:debited|credited|withdrawn|spent|paid|received|refund|cashback|salary).*?(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)" }
                    val a = TransactionStore.rules(this)
                    a.put(JSONObject().put("name", "Custom ${sender.text.toString().ifBlank { "bank" }}").put("sender", sender.text.toString()).put("regex", pattern).put("sample", sample.text.toString()).put("enabled", true))
                    TransactionStore.saveRules(this, a)
                    Toast.makeText(this, "Rule saved", Toast.LENGTH_SHORT).show()
                }
                if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), smsReq)
            }.show()
    }

    fun importPreviousMessages() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), smsReq)
            Toast.makeText(this, "Allow SMS access, then open Import Previous Transactions again.", Toast.LENGTH_LONG).show()
            return
        }
        val senders = mutableListOf<String>()
        val senderCursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address"), null, null, "date DESC")
        senderCursor?.use { c -> while (c.moveToNext()) { val s = c.getString(0) ?: ""; if (s.isNotBlank() && !senders.contains(s)) senders.add(s); if (senders.size >= 80) break } }
        if (senders.isEmpty()) { Toast.makeText(this, "No SMS messages found.", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(senders.size)
        AlertDialog.Builder(this).setTitle("Choose bank / sender")
            .setMultiChoiceItems(senders.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null).setPositiveButton("Find messages") { _, _ ->
                val selected = senders.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) { Toast.makeText(this, "Select at least one sender.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                chooseMessages(selected)
            }.show()
    }

    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long)

    private fun chooseMessages(selectedSenders: List<String>) {
        val rows = mutableListOf<SmsRow>()
        val projection = arrayOf("_id", "address", "body", "date")
        val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "date DESC")
        cursor?.use { c ->
            val idxId = c.getColumnIndex("_id"); val idxAddress = c.getColumnIndex("address"); val idxBody = c.getColumnIndex("body"); val idxDate = c.getColumnIndex("date")
            while (c.moveToNext() && rows.size < 500) {
                val address = c.getString(idxAddress) ?: ""
                if (!selectedSenders.contains(address)) continue
                rows.add(SmsRow(c.getLong(idxId), address, c.getString(idxBody) ?: "", c.getLong(idxDate)))
            }
        }
        if (rows.isEmpty()) { Toast.makeText(this, "No messages found for the selected senders.", Toast.LENGTH_SHORT).show(); return }
        val labels = rows.map { r ->
            val d = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(r.date))
            "$d  ·  ${r.address}\n${r.body.replace("\\n", " ").take(110)}"
        }.toTypedArray()
        val checked = BooleanArray(rows.size)
        AlertDialog.Builder(this).setTitle("Select messages (${rows.size})")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null).setPositiveButton("Extract selected") { _, _ ->
                var count = 0
                rows.forEachIndexed { i, row ->
                    if (!checked[i]) return@forEachIndexed
                    val result = TransactionParser.parse(this, "HISTORICAL_SMS", row.address, row.body, row.date) ?: return@forEachIndexed
                    addToWeb(result.tx); count++
                }
                Toast.makeText(this, "Imported $count matching transactions. You can edit them anytime.", Toast.LENGTH_LONG).show()
            }.show()
    }

    inner class NativeBridge(private val a: MainActivity) {
        @android.webkit.JavascriptInterface fun openImportSettings() { runOnUiThread { a.openImportSettings() } }
        @android.webkit.JavascriptInterface fun importPreviousMessages() { runOnUiThread { a.importPreviousMessages() } }
    }
}
