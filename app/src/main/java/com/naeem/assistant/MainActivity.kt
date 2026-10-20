package com.naeem.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var history: TextView
    private lateinit var confirm: Button
    private lateinit var continueButton: Button
    private lateinit var offline: Switch
    private lateinit var continuous: Switch
    private lateinit var wake: Switch
    private lateinit var language: Spinner
    private lateinit var voice: VoiceSession
    private val handler=Handler(Looper.getMainLooper())
    private val gate=ConfirmationGate()
    private val contactsWorker=Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("assistant-local",MODE_PRIVATE) }
    private var plan=emptyList<ParsedCommand>()
    private var index=0
    private var pending: Pending?=null
    private var resumed=false
    private var resolving=false
    private var dialog: AlertDialog?=null
    private var planGeneration=0
    private data class Contact(val name:String,val phone:String)
    private data class Pending(val id:String,val step:ParsedCommand,val contact:Contact)
    private val navy=Color.rgb(15,30,47)
    private val teal=Color.rgb(23,192,172)
    private val white=Color.rgb(231,240,245)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun locked()=(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=navy;window.navigationBarColor=navy
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        voice=VoiceSession(this,{message -> showStatus(message,false)},::onSpeech)
        updateHistory()
    }
    override fun onResume() {
        super.onResume();resumed=true
        if(::voice.isInitialized)voice.foreground(true)
        if(::preview.isInitialized && plan.isNotEmpty())renderPlan()
    }
    override fun onPause() {
        resumed=false;planGeneration++;resolving=false
        gate.invalidate();pending=null;handler.removeCallbacksAndMessages(null)
        if(::confirm.isInitialized)confirm.visibility=View.GONE
        dialog?.dismiss();dialog=null
        if(::voice.isInitialized)voice.foreground(false)
        super.onPause()
    }
    override fun onDestroy() { if(::voice.isInitialized)voice.close();contactsWorker.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy() }
    private fun label(text:String,size:Float=16f)=TextView(this).apply { this.text=text;textSize=size;setTextColor(white);setPadding(0,dp(6),0,dp(6)) }
    private fun button(text:String,click:()->Unit)=Button(this).apply {
        this.text=text;isAllCaps=false;setTextColor(navy);backgroundTintList=android.content.res.ColorStateList.valueOf(teal)
        setOnClickListener{click()};minHeight=dp(48)
    }
    private fun addButton(text:String,click:()->Unit):Button { val b=button(text,click);root.addView(b,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(7) });return b }
    private fun toggle(text:String,key:String,default:Boolean):Switch {
        return Switch(this).apply { this.text=text;setTextColor(white);isChecked=prefs.getBoolean(key,default);setPadding(0,dp(8),0,dp(8));root.addView(this)
            setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean(key,checked).apply();if(::voice.isInitialized)voice.stop();cancelPlan(false) }
        }
    }
    private fun buildUi() {
        val scroll=ScrollView(this).apply { setBackgroundColor(navy);isFillViewport=true }
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(28),dp(22),dp(28));layoutDirection=View.LAYOUT_DIRECTION_RTL }
        scroll.addView(root);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _,insets ->
            root.setPadding(dp(22),dp(28)+insets.systemWindowInsetTop,dp(22),dp(28)+insets.systemWindowInsetBottom)
            insets
        }
        root.addView(label("مساعدي AI",30f).apply{setTypeface(null,Typeface.BOLD)})
        root.addView(label("نسخة تجربة 0.5.0 • أوامرك بإذنك",14f).apply{setTextColor(teal)})
        root.addView(label("اكتب أو تحدّث، راجع الخطة، ثم أكد التواصل. فتح مسودة رسالة لا يعني إرسالها.",15f))
        input=EditText(this).apply {
            hint="افتح يوتيوب ثم ديرلي منبه الساعة 6:30 الصبح"
            setHintTextColor(Color.rgb(145,166,186));setTextColor(white);textSize=18f;minLines=2;gravity=Gravity.TOP or Gravity.START
            background=GradientDrawable().apply {setColor(Color.rgb(27,47,68));cornerRadius=dp(14).toFloat();setStroke(dp(1),Color.rgb(54,88,109))}
            setPadding(dp(14),dp(14),dp(14),dp(14));root.addView(this,LinearLayout.LayoutParams(-1,-2))
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){cancelPlan(false)}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        language=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("العربية الليبية • ar-LY","English • en-US"))
            setBackgroundColor(Color.rgb(225,236,240));root.addView(this)
        }
        offline=toggle("تعرف صوتي محلي فقط (حسب جهازك)","offline",true)
        continuous=toggle("محادثة متواصلة داخل التطبيق","continuous",false)
        wake=toggle("تنبيه «يا مساعدي» داخل التطبيق فقط","wake",false)
        addButton("🎙  ابدأ الاستماع") { startVoice() }
        addButton("إيقاف الصوت وإلغاء الخطة") { voice.stop();cancelPlan();showStatus("توقف الاستماع وأُلغيت الخطوات المتبقية.",false) }
        addButton("راجع خطة الأوامر") { voice.stop();createPlan(input.text.toString()) }
        preview=label("لا توجد خطة نشطة.",16f);root.addView(preview)
        continueButton=addButton("نفّذ الخطوة التالية") { runNext() }.apply{visibility=View.GONE}
        confirm=addButton("أكد الإجراء المعروض") { confirmPending() }.apply{visibility=View.GONE}
        status=label("جاهز. الصوت اختياري؛ يمكنك استخدام الكتابة بلا إنترنت.",16f).apply{setTextColor(teal)};root.addView(status)
        root.addView(label("الإعداد والخصوصية",21f))
        addButton("الصلاحيات وحالة الجهاز") { permissionsDialog() }
        addButton("جهات الاتصال والألقاب") { contactsDialog() }
        addButton("التحكم بالتطبيقات • Accessibility") { accessibilityDialog() }
        addButton("أمثلة وحدود النسخة") { infoDialog() }
        root.addView(label("سجل المهام المحلي",21f))
        history=label("",14f);root.addView(history)
        addButton("حذف السجل والألقاب والبيانات المحلية") {
            AlertDialog.Builder(this).setTitle("حذف البيانات المحلية؟").setMessage("يحذف السجل والألقاب والإعدادات. لا يحذف رسائل أرسلتها من تطبيق آخر.")
                .setNegativeButton("إلغاء",null).setPositiveButton("حذف") { _,_ ->
                    voice.stop();cancelPlan(false);prefs.edit().clear().apply();offline.isChecked=true;continuous.isChecked=false;wake.isChecked=false
                    input.text.clear();updateHistory();showStatus("حُذفت البيانات المحلية.",false)
                }.show()
        }
    }
    private fun showStatus(message:String,speak:Boolean=true) {
        if(isFinishing || isDestroyed)return
        status.text=message
        if(speak && ::voice.isInitialized)voice.say(message)
    }
    private fun startVoice() {
        if(locked()) { showStatus("افتح قفل الجهاز أولًا.",false);return }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("استخدام الميكروفون")
                .setMessage("يُستخدم أثناء الجلسة فقط. الوضع المحلي لا يتحول للشبكة تلقائيًا. عند تعطيله قد يرسل محرك التعرف المثبّت صوتك إلى مزوده؛ راجع سياسة المحرك. لا يحفظ مساعدي تسجيلات صوتية.")
                .setNegativeButton("إلغاء",null).setPositiveButton("متابعة") { _,_->requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10) }.show();return
        }
        if(!offline.isChecked && !prefs.getBoolean("provider-consent",false)) {
            AlertDialog.Builder(this).setTitle("محرك تعرف قد يستخدم الإنترنت")
                .setMessage("قد يعالج مزود محرك الصوت المثبّت على هاتفك الصوت عبر الشبكة. لا يملك التطبيق سياسة احتفاظ ذلك المزود. يمكنك العودة للوضع المحلي أو استخدام الكتابة.")
                .setNegativeButton("إلغاء",null).setPositiveButton("أوافق لهذه الجلسات") { _,_->prefs.edit().putBoolean("provider-consent",true).apply();startVoice() }.show();return
        }
        voice.start(offline.isChecked,wake.isChecked,continuous.isChecked,if(language.selectedItemPosition==0)"ar-LY" else "en-US")
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        showStatus(if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)"تم منح الإذن. اضغط الإجراء مرة أخرى؛ لا تنفيذ تلقائي بعد الإذن." else "الإذن مرفوض. الكتابة والإجراءات اليدوية متاحة.",false)
    }
    private fun onSpeech(text:String) {
        if(!resumed || locked())return
        when(CommandParser.normalize(text)) {
            "الغاء","الغي","لا","no","cancel","تراجع","undo" -> { cancelPlan();showStatus("ألغيت الخطوات المتبقية؛ لا يمكن سحب رسالة أو مكالمة تمت خارجيًا.");return }
            "اكد الاتصال","أكد الاتصال","confirm call" -> { if(pending?.step?.action==Action.DIAL)confirmPending() else showStatus("لا توجد مكالمة تنتظر التأكيد.");return }
            "اكد فتح المسودة","confirm draft" -> { if(pending?.step?.action in listOf(Action.SMS,Action.WHATSAPP))confirmPending() else showStatus("لا توجد مسودة تنتظر التأكيد.");return }
            "التالي","نفذ التالي","next" -> { runNext();return }
        }
        // A new utterance always replaces the old plan and invalidates its approval.
        input.setText(text);createPlan(text)
    }
    private fun cancelPlan(log:Boolean=true) {
        if(!::preview.isInitialized)return
        if(log && index<plan.size)record("أُلغيت الخطوات المتبقية")
        planGeneration++;resolving=false;plan=emptyList();index=0;pending=null;gate.invalidate();handler.removeCallbacksAndMessages(null)
        dialog?.dismiss();dialog=null
        preview.text="لا توجد خطة نشطة.";confirm.visibility=View.GONE;continueButton.visibility=View.GONE
    }
    private fun createPlan(text:String) {
        cancelPlan(false)
        val parsed=CommandParser.plan(text)
        if(parsed.error.isNotBlank()) { showStatus(parsed.error);return }
        plan=parsed.steps
        renderPlan();record("خطة جديدة • ${plan.size} خطوة")
        showStatus("الخطة جاهزة من ${plan.size} خطوة. قل التالي أو اضغط تنفيذ الخطوة التالية.")
    }
    private fun title(step:ParsedCommand):String=when(step.action) {
        Action.ALARM -> "طلب منبه ${step.hour}:${step.minute.toString().padStart(2,'0')}"
        Action.YOUTUBE -> "فتح يوتيوب"
        Action.WHATSAPP -> if(step.sensitive)"مسودة واتساب إلى ${step.recipient}" else "فتح واتساب"
        Action.DIAL -> "اتصال إلى ${step.recipient}"
        Action.SMS -> "مسودة SMS إلى ${step.recipient}"
        Action.BACK -> "رجوع";Action.HOME -> "الرئيسية";Action.SCROLL_UP -> "تمرير لأعلى";Action.SCROLL_DOWN -> "تمرير لأسفل"
        else -> "غير مدعوم"
    }
    private fun renderPlan() {
        preview.text=plan.mapIndexed { i,s -> "${if(i<index)"✓" else if(i==index)"◉" else "○"} ${i+1}. ${title(s)}" }.joinToString("\n")
        continueButton.visibility=if(index<plan.size && pending==null)View.VISIBLE else View.GONE
    }
    private fun runNext() {
        if(!resumed || locked()) { showStatus("افتح قفل الجهاز قبل التنفيذ.");return }
        if(resolving){showStatus("انتظر اختيار المستلم أو ألغِ الخطة.",false);return}
        if(pending!=null) { showStatus("أكد الإجراء الحالي أو ألغِه أولًا.");return }
        val step=plan.getOrNull(index)?:run{showStatus("لا توجد خطوات متبقية.");return}
        if(step.sensitive) {
            if(step.action==Action.DIAL && checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this).setTitle("إذن بدء الاتصال")
                    .setMessage("يسمح ببدء مكالمة بعد قراءة الاسم والرقم وتأكيدك. قد تترتب تكلفة. بعد منح الإذن ستراجع الخطوة وتؤكدها من جديد.")
                    .setNegativeButton("إلغاء",null).setPositiveButton("طلب الإذن") { _,_->requestPermissions(arrayOf(Manifest.permission.CALL_PHONE),12) }.show();return
            }
            val epoch=planGeneration
            resolving=true
            resolve(step.recipient) { contact -> if(epoch==planGeneration && resumed && !locked()){resolving=false;arm(step,contact)} }
        } else execute(step,null)
    }
    private fun resolve(target:String,done:(Contact)->Unit) {
        CommandParser.phone(target)?.let { done(Contact(target,it));return }
        val aliases=JSONObject(prefs.getString("aliases","{}")?:"{}")
        val saved=aliases.optJSONObject(CommandParser.normalize(target))
        if(saved!=null) { done(Contact(saved.getString("name"),saved.getString("phone")));return }
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) {
            resolving=false;showStatus("اسم غير محفوظ كلقب. فعّل جهات الاتصال من الإعدادات أو اكتب الرقم الدولي.");return
        }
        val epoch=planGeneration
        showStatus("جارٍ البحث في جهات الاتصال…",false)
        contactsWorker.execute {
            val matches=mutableListOf<Contact>()
            try {
                contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,null)?.use { c ->
                    while(c.moveToNext() && !Thread.currentThread().isInterrupted) {
                        val name=c.getString(0).orEmpty();val number=CommandParser.phone(c.getString(1).orEmpty())?:continue
                        if(CommandParser.normalize(name).contains(CommandParser.normalize(target)))matches.add(Contact(name,number))
                    }
                }
            } catch(_:Exception) {
                runOnUiThread { if(epoch==planGeneration && resumed){resolving=false;showStatus("تعذر البحث. تحقق من الإذن ثم أعد المحاولة.")} };return@execute
            }
            val unique=matches.distinctBy { it.name to it.phone }
            runOnUiThread {
                if(epoch!=planGeneration || !resumed || locked())return@runOnUiThread
                if(unique.isEmpty()) { resolving=false;showStatus("لم أجد المستلم. استخدم الرقم أو أضف لقبًا في جهات الاتصال.");return@runOnUiThread }
                dialog=AlertDialog.Builder(this).setTitle("اختر المستلم والرقم")
                    .setItems(unique.map { "${it.name}\n${it.phone}" }.toTypedArray()) { _,which->done(unique[which]) }
                    .setNegativeButton("إلغاء",null).setOnDismissListener { resolving=false }.show()
            }
        }
    }

    private fun arm(step:ParsedCommand,contact:Contact) {
        val item=Pending(UUID.randomUUID().toString(),step,contact);pending=item
        gate.arm(item.id,SystemClock.elapsedRealtime());continueButton.visibility=View.GONE
        val channel=if(step.action==Action.DIAL)"مكالمة هاتف" else if(step.action==Action.SMS)"مسودة SMS" else "مسودة واتساب"
        val message="$channel إلى ${contact.name}، الرقم ${contact.phone}. "+
            if(step.action==Action.DIAL)"قل أكد الاتصال أو اضغط التأكيد خلال 30 ثانية. قد تترتب تكلفة، ولا يمكن التراجع عن بدء المكالمة." else "النص: ${step.message}. قل أكد فتح المسودة أو اضغط التأكيد خلال 30 ثانية. الإرسال النهائي يتم منك داخل التطبيق الآخر."
        confirm.text=if(step.action==Action.DIAL)"أكد الاتصال: ${contact.name} • ${contact.phone}" else "أكد فتح المسودة: ${contact.name} • ${contact.phone}"
        confirm.visibility=View.VISIBLE;showStatus(message)
        handler.postDelayed({if(pending?.id==item.id){pending=null;gate.invalidate();confirm.visibility=View.GONE;renderPlan();showStatus("انتهت مهلة التأكيد؛ لم يتم تنفيذ الإجراء.")}},30_000)
    }
    private fun confirmPending() {
        val item=pending?:return
        pending=null;confirm.visibility=View.GONE
        if(!gate.accept(item.id,SystemClock.elapsedRealtime(),!locked(),resumed)) {renderPlan();showStatus("التأكيد غير صالح. راجع الخطوة من جديد.");return}
        handler.removeCallbacksAndMessages(null)
        execute(item.step,item.contact)
    }
    private fun execute(step:ParsedCommand,contact:Contact?) {
        if(!resumed || locked()){showStatus("افتح الجهاز أولًا.");return}
        var external=true
        var actionIntent:Intent?=null
        var result=""
        when(step.action) {
            Action.ALARM -> {
                actionIntent=Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,step.hour!!).putExtra(AlarmClock.EXTRA_MINUTES,step.minute!!).putExtra(AlarmClock.EXTRA_MESSAGE,"منبه مساعدي AI")
                result="أُرسل طلب المنبه لتطبيق الساعة؛ تحقق من ضبطه هناك."
            }
            Action.YOUTUBE -> {actionIntent=packageManager.getLaunchIntentForPackage("com.google.android.youtube");result="فُتح يوتيوب."}
            Action.WHATSAPP -> {
                if(contact==null)actionIntent=packageManager.getLaunchIntentForPackage("com.whatsapp")
                else {
                    if(!contact.phone.startsWith("+")){showStatus("واتساب يحتاج رقمًا دوليًا يبدأ بعلامة +. عدّل الرقم؛ لم تُفتح المسودة.");renderPlan();return}
                    actionIntent=Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/${contact.phone.removePrefix("+")}?text=${Uri.encode(step.message)}")).setPackage("com.whatsapp")
                }
                result=if(contact==null)"فُتح واتساب." else "فُتحت مسودة واتساب فقط؛ راجع المستلم والنص واضغط إرسال بنفسك."
            }
            Action.DIAL -> {
                if(contact==null)return
                if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){showStatus("إذن المكالمات غير متاح؛ لم يبدأ اتصال.");renderPlan();return}
                actionIntent=Intent(Intent.ACTION_CALL,Uri.fromParts("tel",contact.phone,null));result="أُرسل طلب بدء المكالمة للنظام؛ تحقق من شاشة الاتصال."
            }
            Action.SMS -> {
                if(contact==null)return
                actionIntent=Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",contact.phone,null)).putExtra("sms_body",step.message)
                result="فُتحت مسودة SMS فقط. اختر الشريحة وراجع التكلفة ثم أرسل بنفسك."
            }
            Action.BACK,Action.HOME,Action.SCROLL_UP,Action.SCROLL_DOWN -> {
                external=false
                val service=AssistantAccessibilityService.current
                if(service==null){showStatus("فعّل خدمة التحكم من إعدادات Accessibility أولًا.");return}
                val epoch=planGeneration
                service.execute(step.action){ok->runOnUiThread{
                    if(epoch==planGeneration){if(ok)complete("طلب تحكم نُفّذ؛ تحقق من الواجهة.")else showStatus("لم ينفذ النظام إجراء التحكم؛ توقفت الخطة.")}
                }}
            }
            else -> {showStatus("إجراء غير مدعوم.");return}
        }
        if(!external)return
        if(actionIntent==null){showStatus("التطبيق غير مثبت أو غير متاح. توقفت الخطة عند هذه الخطوة.");return}
        try {
            voice.stop()
            startActivity(actionIntent)
            complete(result)
        } catch(_:Exception){showStatus("تعذر فتح التطبيق أو تنفيذ الطلب. لم تُستكمل الخطوات التالية.",false);record("فشل فتح إجراء خارجي");renderPlan()}
    }
    private fun complete(message:String) {
        index++;record(message);renderPlan()
        showStatus(message+if(index<plan.size)" ارجع لمساعدي واضغط التالي عند التأكد من اكتمال الخطوة." else " انتهت الخطة.",false)
    }
    private fun record(summary:String) {
        val list=JSONArray(prefs.getString("history","[]")?:"[]")
        val next=JSONArray();next.put(JSONObject().put("time",System.currentTimeMillis()).put("summary",summary))
        for(i in 0 until minOf(list.length(),99)){val item=list.getJSONObject(i);if(System.currentTimeMillis()-item.optLong("time")<30L*24*60*60*1000)next.put(item)}
        prefs.edit().putString("history",next.toString()).apply();updateHistory()
    }
    private fun updateHistory() {
        if(!::history.isInitialized)return
        val list=JSONArray(prefs.getString("history","[]")?:"[]");val valid=JSONArray()
        for(i in 0 until list.length()){val item=list.getJSONObject(i);if(System.currentTimeMillis()-item.optLong("time")<30L*24*60*60*1000)valid.put(item)}
        prefs.edit().putString("history",valid.toString()).apply()
        history.text=(0 until minOf(valid.length(),12)).joinToString("\n\n"){i->val item=valid.getJSONObject(i);DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.getLong("time")))+" • "+item.getString("summary")}.ifBlank{"لا توجد مهام محفوظة. لا تُحفظ نصوص الرسائل أو الأرقام في السجل."}
    }
    private fun permissionsDialog() {
        voice.stop()
        fun granted(permission:String)=if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)"ممنوح" else "غير ممنوح"
        val power=getSystemService(POWER_SERVICE) as PowerManager
        AlertDialog.Builder(this).setTitle("الصلاحيات والبطارية")
            .setMessage("الميكروفون: ${granted(Manifest.permission.RECORD_AUDIO)}\nجهات الاتصال: ${granted(Manifest.permission.READ_CONTACTS)}\nالمكالمات: ${granted(Manifest.permission.CALL_PHONE)}\nالصوت المحلي: ${if(voice.localAvailable())"محرك متاح؛ دعم العربية حسب المحرك" else "غير متاح"}\nتوفير الطاقة: ${if(power.isPowerSaveMode)"مفعّل" else "غير مفعّل"}\n\nلا استماع في الخلفية أو أثناء قفل الشاشة. تتوقف الجلسة عند مغادرة التطبيق وبعد دقيقة خمول. لا حاجة لتعطيل تحسين البطارية.")
            .setNeutralButton("إعدادات التطبيق") { _,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
            .setNegativeButton("إغلاق",null).setPositiveButton("إذن جهات الاتصال") { _,_->requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),11) }.show()
    }
    private fun contactsDialog() {
        voice.stop()
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),0)}
        val alias=EditText(this).apply {hint="اللقب: بابا أو Sara";layout.addView(this)}
        val name=EditText(this).apply {hint="اسم العرض";layout.addView(this)}
        val number=EditText(this).apply {hint="رقم دولي مثل +218…";inputType=android.text.InputType.TYPE_CLASS_PHONE;layout.addView(this)}
        layout.addView(TextView(this).apply{text="الألقاب محفوظة محليًا فقط. تكرار اللقب يستبدل رقمه بعد مراجعتك. تحذف من زر حذف البيانات."})
        AlertDialog.Builder(this).setTitle("لقب محلي لجهة اتصال").setView(layout).setNegativeButton("إلغاء",null)
            .setNeutralButton("طلب إذن البحث") { _,_->requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),11) }
            .setPositiveButton("حفظ") { _,_->
                val phone=CommandParser.phone(number.text.toString());val key=CommandParser.normalize(alias.text.toString())
                if(phone==null || key.isBlank() || name.text.isBlank()){showStatus("أدخل لقبًا واسمًا ورقمًا صحيحًا.");return@setPositiveButton}
                val obj=JSONObject(prefs.getString("aliases","{}")?:"{}");obj.put(key,JSONObject().put("name",name.text.toString()).put("phone",phone));prefs.edit().putString("aliases",obj.toString()).apply()
                showStatus("تم حفظ اللقب محليًا.",false)
            }.show()
    }
    private fun accessibilityDialog() {
        voice.stop()
        AlertDialog.Builder(this).setTitle("تحكم محدود وبإذنك")
            .setMessage("الخدمة تنفذ رجوع والرئيسية والتمرير فقط عبر أزرار تضغطها أو أوامر واضحة. لا تقرأ محتوى الشاشة ولا تنقر إرسال ولا تخطط لأفعال مستقلة. لوحة عائمة اختيارية تتيح التحكم أثناء فتح التطبيقات. فعّل الخدمة يدويًا من Android بعد الموافقة، وأوقفها من هناك متى شئت.")
            .setNegativeButton("إلغاء",null)
            .setNeutralButton("إظهار/إخفاء اللوحة") { _,_->
                val service=AssistantAccessibilityService.current
                if(service==null)showStatus("فعّل الخدمة أولًا.",false) else service.togglePanel()
            }
            .setPositiveButton("أوافق، افتح إعدادات التفعيل") { _,_->startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
    }
    private fun infoDialog() {
        voice.stop()
        AlertDialog.Builder(this).setTitle("أمثلة وحدود نسخة التجربة")
            .setMessage("افتح يوتيوب ثم افتح واتساب\nديرلي منبه الساعة 6:30 الصبح\nاتصل على أحمد\ncall Sara\nابعت SMS إلى أحمد \"بنتأخر\"\nsend WhatsApp to Sara \"I am late\"\n\nقل: التالي، إلغاء، أو أكد الاتصال. الرسائل تُفتح كمسودات وتتطلب إرسالًا يدويًا. لا يوجد نموذج ذكاء اصطناعي حر أو محرك مخصص للهجة؛ التعرف حسب هاتفك. أثناء نطق الرد استخدم زر الإيقاف للمقاطعة. الوضع المحلي يحتاج Android 12+ ومحركًا يدعم اللغة. التنبيه «يا مساعدي» تجريبي داخل التطبيق ولمدة جلسة محدودة، وليس كشفًا دائمًا منخفض الطاقة. لا فتح تلقائي للقفل أو تنفيذ بالخلفية. التراجع يلغي الخطوات المتبقية فقط. لا تُحفظ نصوص الأوامر أو التسجيلات؛ السجل المختصر يحذف بعد 30 يومًا وبحد أقصى 100 حدث.")
            .setPositiveButton("فهمت",null).show()
    }
}
