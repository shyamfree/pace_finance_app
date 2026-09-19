package com.pace.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val smsReq=4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web=WebView(this).apply {
            settings.javaScriptEnabled=true; settings.domStorageEnabled=true; settings.allowFileAccess=true
            webViewClient=object:WebViewClient(){ override fun onPageFinished(v:WebView,url:String){ syncPending(); } }
            addJavascriptInterface(NativeBridge(this@MainActivity),"PaceNative")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        requestPermissionsIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent){ super.onNewIntent(intent); handleIntent(intent) }

    private fun requestPermissionsIfNeeded(){
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),4102)
        if(checkSelfPermission(Manifest.permission.RECEIVE_SMS)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS),smsReq)
    }

    private fun handleIntent(i:Intent){
        if(i.getBooleanExtra("review",false)){
            val raw=i.getStringExtra("tx") ?: return
            try{ review(JSONObject(raw)) }catch(_:Exception){}
        }
    }

    private fun syncPending(){
        val a=TransactionStore.pending(this)
        for(i in 0 until a.length()){
            val t=a.optJSONObject(i) ?: continue
            val json=t.toString().replace("\\","\\\\").replace("'","\\'")
            web.evaluateJavascript("window.PaceNativeImport && window.PaceNativeImport({type:'${t.optString("type")}',amount:${t.optDouble("amount")},category:'${t.optString("category")}',date:'${t.optString("date")}',note:'${t.optString("note").replace("'","\\'")}',fingerprint:'${t.optString("fingerprint")}'});",null)
        }
        if(a.length()>0){ for(i in 0 until a.length()) TransactionStore.removePending(this,a.getJSONObject(i).optString("fingerprint")) }
    }

    fun review(t:JSONObject){
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(40,8,40,0)
        fun field(label:String,value:String):EditText{ val e=EditText(this); e.hint=label; e.setText(value); box.addView(e); return e }
        val amount=field("Amount",String.format(Locale.US,"%.2f",t.optDouble("amount"))); val merchant=field("Merchant / note",t.optString("note"))
        val type=Spinner(this); type.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("Expense","Income")); type.setSelection(if(t.optString("type")=="income")1 else 0); box.addView(type)
        val cat=field("Category (food, bills, transport, shopping, other)",t.optString("category"))
        AlertDialog.Builder(this).setTitle("Review transaction").setMessage("Detected from ${t.optString("source","message")}").setView(box).setNegativeButton("Ignore"){_,_->TransactionStore.ignored(this,t.optString("fingerprint"))}.setPositiveButton("Add"){_,_->
            val out=JSONObject(t.toString()); out.put("amount",amount.text.toString().replace(",","").toDoubleOrNull()?:t.optDouble("amount")); out.put("note",merchant.text.toString()); out.put("category",cat.text.toString().ifBlank{"other"}); out.put("type",if(type.selectedItemPosition==1)"income" else "expense"); addToWeb(out); TransactionStore.removePending(this,t.optString("fingerprint"))
        }.show()
    }

    private fun addToWeb(t:JSONObject){
        val js="window.PaceNativeImport({type:'${t.optString("type")}',amount:${t.optDouble("amount")},category:'${t.optString("category").replace("'","\\'")}',date:'${t.optString("date")}',note:'${t.optString("note").replace("'","\\'")}',fingerprint:'${t.optString("fingerprint")}'});"
        web.evaluateJavascript(js,null)
    }

    fun openImportSettings(){
        val root=LinearLayout(this); root.orientation=LinearLayout.VERTICAL; root.setPadding(40,10,40,10)
        val sms=Switch(this); sms.text="Read bank SMS"; sms.isChecked=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED; root.addView(sms)
        val n=Switch(this); n.text="Payment/bank notifications"; n.isChecked=NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName); root.addView(n)
        val sample=EditText(this); sample.hint="Paste a sample bank message"; sample.minLines=4; root.addView(sample)
        val regex=EditText(this); regex.hint="Optional regex (capture amount in group 1)"; root.addView(regex)
        val sender=EditText(this); sender.hint="Optional sender/app keyword"; root.addView(sender)
        AlertDialog.Builder(this).setTitle("Bank & payment imports").setView(root).setNeutralButton("Notification access"){_,_->startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))}.setNegativeButton("Close",null).setPositiveButton("Save rule"){_,_->
            if(sample.text.isNotBlank() || regex.text.isNotBlank()){
                val a=TransactionStore.rules(this); a.put(JSONObject().put("name","Custom rule").put("sender",sender.text.toString()).put("regex",regex.text.toString().ifBlank{"(?i)(?:debited|credited|spent|paid|received).*?(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"}).put("enabled",true)); TransactionStore.saveRules(this,a); Toast.makeText(this,"Rule saved",Toast.LENGTH_SHORT).show()
            }
            if(checkSelfPermission(Manifest.permission.RECEIVE_SMS)!=PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS),smsReq)
        }.show()
    }

    inner class NativeBridge(private val a:MainActivity){
        @JavascriptInterface fun openImportSettings(){runOnUiThread{a.openImportSettings()}}
    }
}
