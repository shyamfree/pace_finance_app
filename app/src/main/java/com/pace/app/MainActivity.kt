package com.pace.app

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var categoryCatalog = mutableListOf<Category>()

    data class Category(val id: String, val name: String, val type: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    syncPending()
                    updateCategoryCatalogFromWeb()
                }
            }
            addJavascriptInterface(NativeBridge(this@MainActivity), "PaceNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        requestNotificationPermissionIfNeeded()
        ReminderScheduler.schedule(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
    }

    private fun handleIntent(intent: Intent) {
        if (!intent.getBooleanExtra("review", false)) return
        val raw = intent.getStringExtra("tx") ?: return
        try {
            val tx = JSONObject(raw)
            NotificationHelper.cancel(this, tx.optString("fingerprint"))
            review(tx)
        } catch (_: Exception) { }
        intent.removeExtra("review")
        intent.removeExtra("tx")
    }

    private fun syncPending() {
        // Live notification transactions stay in the native pending store until
        // the user explicitly chooses Add or Ignore in the review screen.
    }

    private fun jsQuote(value: String): String = JSONObject.quote(value)

    private fun addToWeb(tx: JSONObject) {
        val js = "window.PaceNativeImport && window.PaceNativeImport({" +
            "type:" + jsQuote(tx.optString("type")) + "," +
            "amount:" + tx.optDouble("amount") + "," +
            "category:" + jsQuote(tx.optString("category", "other")) + "," +
            "date:" + jsQuote(tx.optString("date")) + "," +
            "time:" + jsQuote(tx.optString("time", "00:00")) + "," +
            "note:" + jsQuote(tx.optString("note")) + "," +
            "merchant:" + jsQuote(tx.optString("merchant")) + "," +
            "upiReference:" + jsQuote(tx.optString("upiReference")) + "," +
            "source:" + jsQuote(tx.optString("source")) + "," +
            "sender:" + jsQuote(tx.optString("sender")) + "," +
            "raw:" + jsQuote(tx.optString("raw")) + "," +
            "fingerprint:" + jsQuote(tx.optString("fingerprint")) + "});"
        web.evaluateJavascript(js, null)
    }

    private fun categoryOptions(type: String, selected: String): Pair<Array<String>, Array<String>> {
        val wanted = if (type == "income") "income" else "expense"
        val defaults = if (wanted == "income") {
            listOf(
                Category("salary", "Salary", "income"),
                Category("side", "Side income", "income"),
                Category("gifts", "Gifts", "income"),
                Category("otherinc", "Other income", "income")
            )
        } else {
            listOf(
                Category("food", "Food", "expense"),
                Category("housing", "Housing", "expense"),
                Category("transport", "Transport", "expense"),
                Category("bills", "Bills", "expense"),
                Category("health", "Health", "expense"),
                Category("shopping", "Shopping", "expense"),
                Category("fun", "Fun", "expense"),
                Category("other", "Other", "expense")
            )
        }
        val merged = LinkedHashMap<String, Category>()
        defaults.forEach { merged[it.id] = it }
        categoryCatalog.filter { it.type == wanted }.forEach { merged[it.id] = it }
        if (!merged.containsKey(selected)) merged[selected] = Category(selected, selected.ifBlank { "Other" }, wanted)
        val list = merged.values.toList()
        return list.map { it.name }.toTypedArray() to list.map { it.id }.toTypedArray()
    }

    fun review(tx: JSONObject) {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 12, 36, 8)
        }
        scroll.addView(box)

        fun field(label: String, value: String): EditText = EditText(this).apply {
            hint = label
            setText(value)
            setSingleLine()
            box.addView(this)
        }

        val amount = field("Amount", String.format(Locale.US, "%.2f", tx.optDouble("amount")))
        val merchant = field("Merchant", tx.optString("merchant"))
        val note = field("Note", tx.optString("note"))
        val date = field("Date (yyyy-MM-dd)", tx.optString("date"))
        val time = field("Time (HH:mm)", tx.optString("time", "00:00"))

        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Expense", "Income"))
            setSelection(if (tx.optString("type") == "income") 1 else 0)
        }
        box.addView(labelView("Type")); box.addView(type)

        val categoryLabel = labelView("Category")
        box.addView(categoryLabel)
        val category = Spinner(this)
        box.addView(category)

        fun refreshCategories() {
            val currentType = if (type.selectedItemPosition == 1) "income" else "expense"
            val currentId = tx.optString("category", if (currentType == "income") "otherinc" else "other")
            val (labels, ids) = categoryOptions(currentType, currentId)
            category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val idx = ids.indexOf(currentId).let { if (it >= 0) it else 0 }
            category.setSelection(idx)
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { refreshCategories() }
        }
        refreshCategories()

        val ref = field("UPI / transaction reference", tx.optString("upiReference"))
        box.addView(labelView("Source: ${tx.optString("source", "payment notification")}"))

        AlertDialog.Builder(this)
            .setTitle("Review transaction")
            .setView(scroll)
            .setNegativeButton("Ignore") { _, _ ->
                val fp = tx.optString("fingerprint")
                TransactionStore.ignored(this, fp)
                TransactionStore.removePending(this, fp)
                NotificationHelper.cancel(this, fp)
            }
            .setPositiveButton("Add") { _, _ ->
                val out = JSONObject(tx.toString())
                out.put("amount", amount.text.toString().replace(",", "").toDoubleOrNull() ?: tx.optDouble("amount"))
                out.put("merchant", merchant.text.toString().trim())
                out.put("note", note.text.toString().trim())
                out.put("date", date.text.toString().ifBlank { tx.optString("date") })
                out.put("time", time.text.toString().ifBlank { "00:00" })
                out.put("type", if (type.selectedItemPosition == 1) "income" else "expense")
                val (_, ids) = categoryOptions(if (type.selectedItemPosition == 1) "income" else "expense", "other")
                val idx = category.selectedItemPosition.coerceIn(0, ids.size - 1)
                out.put("category", ids[idx])
                out.put("upiReference", ref.text.toString().trim())
                addToWeb(out)
                TransactionStore.removePending(this, tx.optString("fingerprint"))
                NotificationHelper.cancel(this, tx.optString("fingerprint"))
            }
            .show()
    }

    private fun labelView(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 12, 0, 2)
    }

    fun openNotificationSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    private fun updateCategoryCatalogFromWeb() {
        web.evaluateJavascript("window.PaceGetCategoryCatalog ? window.PaceGetCategoryCatalog() : ''") { value ->
            try {
                val decoded = org.json.JSONTokener(value).nextValue()?.toString() ?: return@evaluateJavascript
                setCategoryCatalog(decoded)
            } catch (_: Exception) { }
        }
    }

    private fun setCategoryCatalog(json: String) {
        try {
            val arr = JSONArray(json)
            categoryCatalog = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Category(o.optString("id"), o.optString("name"), o.optString("type", "expense")))
                }
            }.toMutableList()
        } catch (_: Exception) { }
    }

    inner class NativeBridge(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun setCategoryCatalog(json: String) { activity.setCategoryCatalog(json) }

        @android.webkit.JavascriptInterface
        fun openNotificationSettings() { activity.runOnUiThread { activity.openNotificationSettings() } }

        @android.webkit.JavascriptInterface
        fun setDailyReminder(enabled: Boolean, time: String, note: String) {
            ReminderScheduler.saveAndSchedule(activity, enabled, time, note)
        }

        @android.webkit.JavascriptInterface
        fun updateDailySummary(json: String) {
            ReminderScheduler.saveSummary(activity, json)
        }
    }
}
