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

    private fun chooseImportDateRange() {
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
            setPadding(40, 10, 40, 10)
        }
        val info = TextView(this).apply {
            text = "First choose the SMS date range. Pace will then show only senders found in that range."
            setPadding(0, 0, 0, 18)
        }
        root.addView(info)

        val fromButton = Button(this).apply { text = "From: ${dateFormat.format(fromCal.time)}" }
        val toButton = Button(this).apply { text = "To: ${dateFormat.format(toCal.time)}" }
        root.addView(fromButton)
        root.addView(toButton)

        fromButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                fromCal.set(y, m, d, 0, 0, 0)
                fromCal.set(Calendar.MILLISECOND, 0)
                fromButton.text = "From: ${dateFormat.format(fromCal.time)}"
            }, fromCal.get(Calendar.YEAR), fromCal.get(Calendar.MONTH), fromCal.get(Calendar.DAY_OF_MONTH)).show()
        }
        toButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                toCal.set(y, m, d, 23, 59, 59)
                toCal.set(Calendar.MILLISECOND, 999)
                toButton.text = "To: ${dateFormat.format(toCal.time)}"
            }, toCal.get(Calendar.YEAR), toCal.get(Calendar.MONTH), toCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        AlertDialog.Builder(this)
            .setTitle("Choose SMS date range")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose senders", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (fromCal.after(toCal)) {
                            Toast.makeText(this, "From date cannot be after To date.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        chooseSendersForRange(fromCal.timeInMillis, toCal.timeInMillis)
                    }
                }
                dialog.show()
            }
    }

    private fun chooseSendersForRange(startMillis: Long, endMillis: Long) {
        val progress = ProgressDialog(this).apply {
            setTitle("Finding senders")
            setMessage("Reading SMS messages for the selected date range…")
            setCancelable(false)
        }
        progress.show()

        smsExecutor.execute {
            val senders = mutableListOf<String>()
            val senderCounts = linkedMapOf<String, Int>()
            var errorMessage: String? = null
            try {
                val selection = "date >= ? AND date <= ?"
                val args = arrayOf(startMillis.toString(), endMillis.toString())
                contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("address"),
                    selection,
                    args,
                    "date DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val s = c.getString(0) ?: ""
                        if (s.isNotBlank()) {
                            if (!senderCounts.containsKey(s)) senders.add(s)
                            senderCounts[s] = (senderCounts[s] ?: 0) + 1
                        }
                    }
                }
            } catch (e: SecurityException) {
                errorMessage = "SMS access is not available to Pace. Android reports: ${e.message ?: "permission denied"}"
            } catch (e: Exception) {
                errorMessage = "Could not read SMS: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (errorMessage != null) {
                    AlertDialog.Builder(this)
                        .setTitle("SMS read error")
                        .setMessage(errorMessage)
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }
                if (senders.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No SMS found")
                        .setMessage("Pace successfully queried the SMS inbox, but no messages were found between ${SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(startMillis))} and ${SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(endMillis))}.

Check that the messages are actually in the phone's SMS inbox and that the selected dates are correct.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@runOnUiThread
                }

                val labels = senders.map { "$it  (${senderCounts[it]} messages)" }.toTypedArray()
                val checked = BooleanArray(senders.size)
                AlertDialog.Builder(this)
                    .setTitle("Choose senders in date range")
                    .setMessage("Only senders with SMS in the selected date range are shown.")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setNegativeButton("Back") { _, _ -> chooseImportDateRange() }
                    .setPositiveButton("Find messages") { _, _ ->
                        val selected = senders.filterIndexed { i, _ -> checked[i] }
                        if (selected.isEmpty()) {
                            Toast.makeText(this, "Select at least one sender.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        chooseMessages(selected, startMillis, endMillis)
                    }.show()
            }
        }
    }

    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long)

    private data class SmsDateGroup(
        val dateKey: String,
        val rows: MutableList<SmsRow>,
        val header: CheckBox,
        val children: MutableList<CheckBox>
    )

    private fun chooseMessages(selectedSenders: List<String>, startMillis: Long, endMillis: Long) {
        val rows = mutableListOf<SmsRow>()
        val projection = arrayOf("_id", "address", "body", "date")
        val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, "date >= ? AND date <= ?", arrayOf(startMillis.toString(), endMillis.toString()), "date DESC")
        cursor?.use { c ->
            val idxId = c.getColumnIndex("_id")
            val idxAddress = c.getColumnIndex("address")
            val idxBody = c.getColumnIndex("body")
            val idxDate = c.getColumnIndex("date")
            while (c.moveToNext() && rows.size < 3000) {
                val address = c.getString(idxAddress) ?: ""
                if (!selectedSenders.contains(address)) continue
                rows.add(SmsRow(c.getLong(idxId), address, c.getString(idxBody) ?: "", c.getLong(idxDate)))
            }
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No messages found for the selected senders.", Toast.LENGTH_SHORT).show()
            return
        }

        // Group the inbox by calendar date so a whole day can be selected at once.
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val groups = linkedMapOf<String, MutableList<SmsRow>>()
        rows.sortedByDescending { it.date }.forEach { row ->
            val key = dateFormat.format(Date(row.date))
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 12)
        }
        scroll.addView(root)

        val summary = TextView(this).apply {
            text = "${rows.size} messages across ${groups.size} dates. Select individual messages or use Select all for a date."
            setPadding(0, 0, 0, 16)
        }
        root.addView(summary)

        val selectAll = CheckBox(this).apply {
            text = "Select all messages"
            setPadding(0, 0, 0, 10)
        }
        root.addView(selectAll)

        val groupStates = mutableListOf<SmsDateGroup>()
        var bulkUpdating = false

        groups.forEach { (dateKey, dateRows) ->
            val header = CheckBox(this).apply {
                text = "${displayFormat.format(Date(dateRows.first().date))} (${dateRows.size}) — Select all"
                setPadding(0, 12, 0, 6)
            }
            root.addView(header)

            val children = mutableListOf<CheckBox>()
            val state = SmsDateGroup(dateKey, dateRows, header, children)
            groupStates.add(state)

            header.setOnCheckedChangeListener { _, checked ->
                if (bulkUpdating) return@setOnCheckedChangeListener
                bulkUpdating = true
                children.forEach { it.isChecked = checked }
                bulkUpdating = false
            }

            dateRows.forEach { row ->
                val messageDate = SimpleDateFormat("HH:mm", Locale.US).format(Date(row.date))
                val label = "${messageDate}  ·  ${row.address}\n${row.body.replace("\\n", " ").replace("\\r", " ").take(180)}"
                val cb = CheckBox(this).apply {
                    text = label
                    setPadding(8, 6, 0, 10)
                }
                children.add(cb)
                root.addView(cb)
                cb.setOnCheckedChangeListener { _, _ ->
                    if (bulkUpdating) return@setOnCheckedChangeListener
                    val all = children.isNotEmpty() && children.all { it.isChecked }
                    val none = children.none { it.isChecked }
                    bulkUpdating = true
                    header.isChecked = all
                    if (none) header.isChecked = false
                    bulkUpdating = false
                }
            }
        }

        selectAll.setOnCheckedChangeListener { _, checked ->
            bulkUpdating = true
            groupStates.forEach { group ->
                group.header.isChecked = checked
                group.children.forEach { it.isChecked = checked }
            }
            bulkUpdating = false
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select messages by date")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Extract selected", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedRows = mutableListOf<SmsRow>()
                groupStates.forEach { group ->
                    group.children.forEachIndexed { index, checkBox ->
                        if (checkBox.isChecked) selectedRows.add(group.rows[index])
                    }
                }
                if (selectedRows.isEmpty()) {
                    Toast.makeText(this, "Select at least one message or date.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                var count = 0
                selectedRows.forEach { row ->
                    val result = TransactionParser.parse(this, "HISTORICAL_SMS", row.address, row.body, row.date) ?: return@forEach
                    addToWeb(result.tx)
                    count++
                }
                dialog.dismiss()
                Toast.makeText(this, "Imported $count matching transactions. You can edit them anytime.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    inner class NativeBridge(private val a: MainActivity) {
        @android.webkit.JavascriptInterface fun openImportSettings() { runOnUiThread { a.openImportSettings() } }
        @android.webkit.JavascriptInterface fun importPreviousMessages() { runOnUiThread { a.importPreviousMessages() } }
    }
}
