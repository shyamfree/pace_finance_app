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
import android.view.WindowManager
import android.widget.*
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val smsReq = 4101
    private val smsExecutor = Executors.newSingleThreadExecutor()

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
        chooseImportDateRange()
    }

    private var importWizardDialog: Dialog? = null

    private data class ImportSender(val address: String, val count: Int)

    private fun makeWizardButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun showImportWizard(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        if (!dialog.isShowing) dialog.show()
    }

    private fun chooseImportDateRange() {
        val dialog = Dialog(this)
        importWizardDialog = dialog
        dialog.setTitle("Import previous SMS")
        dialog.setCancelable(true)
        dialog.setOnDismissListener { if (importWizardDialog === dialog) importWizardDialog = null }

        val cal = Calendar.getInstance()
        val fromCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -30)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val toCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
        }
        root.addView(TextView(this).apply {
            text = "STEP 1 OF 3\nChoose the SMS date range. Pace will then find the senders that actually have messages in this range."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        })

        val fromButton = makeWizardButton("FROM: ${dateFormat.format(fromCal.time)}") {
            DatePickerDialog(this, { _, y, m, d ->
                fromCal.set(y, m, d, 0, 0, 0)
                fromCal.set(Calendar.MILLISECOND, 0)
                fromButton.text = "FROM: ${dateFormat.format(fromCal.time)}"
            }, fromCal.get(Calendar.YEAR), fromCal.get(Calendar.MONTH), fromCal.get(Calendar.DAY_OF_MONTH)).show()
        }
        val toButton = makeWizardButton("TO: ${dateFormat.format(toCal.time)}") {
            DatePickerDialog(this, { _, y, m, d ->
                toCal.set(y, m, d, 23, 59, 59)
                toCal.set(Calendar.MILLISECOND, 999)
                toButton.text = "TO: ${dateFormat.format(toCal.time)}"
            }, toCal.get(Calendar.YEAR), toCal.get(Calendar.MONTH), toCal.get(Calendar.DAY_OF_MONTH)).show()
        }
        root.addView(fromButton)
        root.addView(toButton)

        val status = TextView(this).apply {
            text = ""
            setPadding(0, 18, 0, 10)
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        buttons.addView(makeWizardButton("CANCEL") { dialog.dismiss() })
        buttons.addView(makeWizardButton("NEXT: FIND SENDERS") {
            if (fromCal.after(toCal)) {
                status.text = "From date cannot be after To date."
                return@makeWizardButton
            }
            chooseSendersForRange(dialog, fromCal.timeInMillis, toCal.timeInMillis)
        })
        root.addView(buttons)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        showImportWizard(dialog, root)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun chooseSendersForRange(dialog: Dialog, startMillis: Long, endMillis: Long) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
        }
        val title = TextView(this).apply {
            text = "STEP 2 OF 3\nFinding senders..."
            textSize = 18f
        }
        root.addView(title)
        val status = TextView(this).apply {
            text = "Reading SMS inbox for:\n${formatDate(startMillis)} → ${formatDate(endMillis)}"
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.removeView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val back = makeWizardButton("BACK") { chooseImportDateRange() }
        val next = makeWizardButton("NEXT: FIND MESSAGES") { }
        next.isEnabled = false
        bottom.addView(back)
        bottom.addView(next)
        root.addView(bottom)

        showImportWizard(dialog, root)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.88).toInt())

        smsExecutor.execute {
            val counts = linkedMapOf<String, Int>()
            var queryError: String? = null
            try {
                val projection = arrayOf("address")
                val selection = "date >= ? AND date <= ? AND type = ?"
                val args = arrayOf(startMillis.toString(), endMillis.toString(), "1")
                contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, "date DESC")?.use { cursor ->
                    val addressIndex = cursor.getColumnIndex("address")
                    while (cursor.moveToNext()) {
                        val address = if (addressIndex >= 0) cursor.getString(addressIndex).orEmpty().trim() else ""
                        if (address.isNotEmpty()) counts[address] = (counts[address] ?: 0) + 1
                    }
                }
            } catch (e: SecurityException) {
                queryError = "Android denied SMS access: ${e.message ?: "permission denied"}"
            } catch (e: Exception) {
                queryError = "SMS query failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || importWizardDialog !== dialog) return@runOnUiThread
                listContainer.removeAllViews()
                if (queryError != null) {
                    title.text = "STEP 2 OF 3\nSMS access error"
                    status.text = queryError
                    next.isEnabled = false
                    return@runOnUiThread
                }
                if (counts.isEmpty()) {
                    title.text = "STEP 2 OF 3\nNo senders found"
                    status.text = "No inbox SMS were found between ${formatDate(startMillis)} and ${formatDate(endMillis)}.\n\nTry a wider date range and make sure the messages are present in the phone's SMS app."
                    next.isEnabled = false
                    return@runOnUiThread
                }

                title.text = "STEP 2 OF 3\nSelect senders"
                status.text = "${counts.values.sum()} SMS found from ${counts.size} senders. Select the bank/payment senders you want to import."
                val selected = mutableSetOf<String>()

                val selectAll = CheckBox(this).apply { text = "Select all senders" }
                listContainer.addView(selectAll)
                val boxes = mutableListOf<Pair<String, CheckBox>>()
                counts.entries.sortedByDescending { it.value }.forEach { entry ->
                    val cb = CheckBox(this).apply {
                        text = "${entry.key}  (${entry.value} messages)"
                        setPadding(0, 8, 0, 8)
                    }
                    boxes.add(entry.key to cb)
                    cb.setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(entry.key) else selected.remove(entry.key)
                        next.isEnabled = selected.isNotEmpty()
                        if (!checked) selectAll.isChecked = false
                    }
                    listContainer.addView(cb)
                }
                selectAll.setOnCheckedChangeListener { _, checked ->
                    boxes.forEach { (_, cb) -> cb.isChecked = checked }
                    if (checked) selected.addAll(counts.keys) else selected.clear()
                    next.isEnabled = selected.isNotEmpty()
                }
                next.setOnClickListener {
                    val chosen = selected.toList()
                    if (chosen.isEmpty()) return@setOnClickListener
                    chooseMessages(dialog, chosen, startMillis, endMillis)
                }
                next.isEnabled = false
            }
        }
    }

    private fun formatDate(millis: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(millis))

    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long)

    private fun chooseMessages(dialog: Dialog, selectedSenders: List<String>, startMillis: Long, endMillis: Long) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        val title = TextView(this).apply {
            text = "STEP 3 OF 3\nSelect messages"
            textSize = 18f
        }
        root.addView(title)
        val status = TextView(this).apply {
            text = "Loading messages..."
            setPadding(0, 10, 0, 10)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        bottom.addView(makeWizardButton("BACK") { chooseSendersForRange(dialog, startMillis, endMillis) })
        val importButton = makeWizardButton("IMPORT SELECTED") { }
        importButton.isEnabled = false
        bottom.addView(importButton)
        root.addView(bottom)
        showImportWizard(dialog, root)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.88).toInt())

        smsExecutor.execute {
            val rows = mutableListOf<SmsRow>()
            var error: String? = null
            try {
                val projection = arrayOf("_id", "address", "body", "date")
                val selection = "date >= ? AND date <= ? AND type = ?"
                val args = arrayOf(startMillis.toString(), endMillis.toString(), "1")
                contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, "date DESC")?.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val addressIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    while (c.moveToNext() && rows.size < 5000) {
                        val address = c.getString(addressIdx).orEmpty()
                        if (selectedSenders.contains(address)) {
                            rows.add(SmsRow(c.getLong(idIdx), address, c.getString(bodyIdx).orEmpty(), c.getLong(dateIdx)))
                        }
                    }
                }
            } catch (e: SecurityException) {
                error = "Android denied SMS access: ${e.message ?: "permission denied"}"
            } catch (e: Exception) {
                error = "SMS query failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || importWizardDialog !== dialog) return@runOnUiThread
                list.removeAllViews()
                if (error != null) {
                    status.text = error
                    return@runOnUiThread
                }
                if (rows.isEmpty()) {
                    status.text = "No messages were found for the selected senders in this date range."
                    return@runOnUiThread
                }

                val groups = linkedMapOf<String, MutableList<SmsRow>>()
                val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
                rows.sortedByDescending { it.date }.forEach { row ->
                    groups.getOrPut(keyFormat.format(Date(row.date))) { mutableListOf() }.add(row)
                }
                status.text = "${rows.size} messages across ${groups.size} days. Select individual messages or an entire day."
                val allChecks = mutableListOf<CheckBox>()
                val dayChecks = mutableListOf<Pair<CheckBox, List<CheckBox>>>()
                var updating = false

                val all = CheckBox(this).apply { text = "SELECT ALL ${rows.size} MESSAGES" }
                list.addView(all)
                groups.forEach { (_, dayRows) ->
                    val dayHeader = CheckBox(this).apply { text = "${displayFormat.format(Date(dayRows.first().date))} — SELECT ALL (${dayRows.size})" }
                    list.addView(dayHeader)
                    val children = mutableListOf<CheckBox>()
                    dayRows.forEach { row ->
                        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(row.date))
                        val body = row.body.replace("\n", " ").replace("\r", " ").take(220)
                        val cb = CheckBox(this).apply {
                            text = "$time · ${row.address}\n$body"
                            setPadding(10, 5, 0, 8)
                        }
                        children.add(cb)
                        allChecks.add(cb)
                        list.addView(cb)
                        cb.setOnCheckedChangeListener { _, _ ->
                            if (updating) return@setOnCheckedChangeListener
                            val allDay = children.isNotEmpty() && children.all { it.isChecked }
                            updating = true
                            dayHeader.isChecked = allDay
                            all.isChecked = allChecks.isNotEmpty() && allChecks.all { it.isChecked }
                            updating = false
                            importButton.isEnabled = allChecks.any { it.isChecked }
                        }
                    }
                    dayChecks.add(dayHeader to children)
                    dayHeader.setOnCheckedChangeListener { _, checked ->
                        if (updating) return@setOnCheckedChangeListener
                        updating = true
                        children.forEach { it.isChecked = checked }
                        all.isChecked = allChecks.isNotEmpty() && allChecks.all { it.isChecked }
                        updating = false
                        importButton.isEnabled = allChecks.any { it.isChecked }
                    }
                }
                all.setOnCheckedChangeListener { _, checked ->
                    if (updating) return@setOnCheckedChangeListener
                    updating = true
                    dayChecks.forEach { (header, children) ->
                        header.isChecked = checked
                        children.forEach { it.isChecked = checked }
                    }
                    updating = false
                    importButton.isEnabled = checked
                }
                importButton.setOnClickListener {
                    val selectedRows = mutableListOf<SmsRow>()
                    var index = 0
                    groups.forEach { (_, dayRows) ->
                        dayRows.forEach { row ->
                            if (allChecks[index].isChecked) selectedRows.add(row)
                            index++
                        }
                    }
                    var imported = 0
                    selectedRows.forEach { row ->
                        val result = TransactionParser.parse(this, "HISTORICAL_SMS", row.address, row.body, row.date)
                        if (result != null) {
                            addToWeb(result.tx)
                            imported++
                        }
                    }
                    dialog.dismiss()
                    Toast.makeText(this, "Imported $imported matching transactions from ${selectedRows.size} selected SMS.", Toast.LENGTH_LONG).show()
                }
                importButton.isEnabled = false
            }
        }
    }

    inner class NativeBridge(private val a: MainActivity) {
        @android.webkit.JavascriptInterface fun openImportSettings() { runOnUiThread { a.openImportSettings() } }
        @android.webkit.JavascriptInterface fun importPreviousMessages() { runOnUiThread { a.importPreviousMessages() } }
    }
}
