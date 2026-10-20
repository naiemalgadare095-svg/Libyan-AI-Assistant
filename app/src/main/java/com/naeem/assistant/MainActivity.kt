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
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var taskCard: LinearLayout
    private lateinit var progress: TextView
    private lateinit var historyCards: LinearLayout
    private lateinit var voiceHint: TextView
    private lateinit var input: EditText
    private val pages=mutableListOf<ScrollView>()
    private val navButtons=mutableListOf<Button>()
    private var selectedPage=0
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
    private lateinit var aiReply: TextView
    private lateinit var cloudState: TextView
    private val cloud=CloudAssistant()
    private val cloudWorker=Executors.newSingleThreadExecutor()
    private val chatContext=mutableListOf<Pair<String,String>>()
    @Volatile private var cloudGeneration=0
    private var cloudRequest:CloudRequest?=null
    private var executing=false
    private lateinit var readiness:TextView
    private val tokens by lazy { EncryptedTokenStore(this) }
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
    private val navy=Color.rgb(10,18,29)
    private val teal=Color.rgb(74,224,193)
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
        updateReadiness()
    }
    override fun onResume() {
        super.onResume();resumed=true
        if(::voice.isInitialized)voice.foreground(true)
        updateReadiness()
        if(::preview.isInitialized && plan.isNotEmpty())renderPlan()
    }
    override fun onPause() {
        resumed=false;planGeneration++;resolving=false;executing=false
        cancelCloud(true)
        gate.invalidate();pending=null;handler.removeCallbacksAndMessages(null)
        if(::confirm.isInitialized)confirm.visibility=View.GONE
        dialog?.dismiss();dialog=null
        if(::voice.isInitialized)voice.foreground(false)
        super.onPause()
    }
    override fun onDestroy() { if(::voice.isInitialized)voice.close();contactsWorker.shutdownNow();cloudWorker.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy() }
    private val muted=Color.rgb(159,179,197)
    private val surface=Color.rgb(20,34,50)
    private val line=Color.rgb(41,61,80)
    private fun shape(color:Int,radius:Int=20,stroke:Int?=null)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat()
        if(stroke!=null)setStroke(dp(1),stroke)
    }
    private fun label(text:String,size:Float=16f)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(white);setPadding(0,dp(5),0,dp(5))
        setLineSpacing(dp(2).toFloat(),1.08f);textDirection=View.TEXT_DIRECTION_FIRST_STRONG
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun button(text:String,secondary:Boolean=false,click:()->Unit)=Button(this).apply {
        this.text=text;isAllCaps=false;textSize=15f;setTypeface(null,Typeface.BOLD)
        setTextColor(if(secondary)white else navy);backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(45,255,255,255)),shape(if(secondary)surface else teal,14,if(secondary)line else null),null)
        setPadding(dp(14),dp(12),dp(14),dp(12));minHeight=dp(52);minimumWidth=0
        setOnClickListener{click()}
    }
    private fun addButton(text:String,secondary:Boolean=false,click:()->Unit):Button {
        val b=button(text,secondary,click)
        content.addView(b,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) });return b
    }
    private fun card(parent:LinearLayout=content):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(15),dp(18),dp(18));background=shape(surface,22,line)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
    }
    private fun heading(parent:LinearLayout,title:String,subtitle:String?=null) {
        parent.addView(label(title,20f).apply{setTypeface(null,Typeface.BOLD)})
        if(subtitle!=null)parent.addView(label(subtitle,13f).apply{setTextColor(muted)})
    }
    private fun toggle(text:String,key:String,default:Boolean,description:String):Switch {
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(8));content.addView(this)
        }
        val copy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        copy.addView(label(text,15f));copy.addView(label(description,12f).apply{setTextColor(muted)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        return Switch(this).apply {
            contentDescription=text;minHeight=dp(56);minWidth=dp(56);isChecked=prefs.getBoolean(key,default)
            thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(teal,muted))
            trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(28,103,94),line))
            row.addView(this)
            setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean(key,checked).apply();if(::voice.isInitialized)voice.stop();cancelPlan(false) }
        }
    }
    private fun newPage(frame:FrameLayout):LinearLayout {
        val inner=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(24))}
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;addView(inner);isVerticalScrollBarEnabled=false}
        pages.add(scroll);frame.addView(scroll,FrameLayout.LayoutParams(-1,-1));return inner
    }
    private fun selectPage(page:Int) {
        if(::input.isInitialized)input.clearFocus()
        root.requestFocus()
        selectedPage=page
        pages.forEachIndexed { i,v -> v.visibility=if(i==page)View.VISIBLE else View.GONE }
        navButtons.forEachIndexed { i,b ->
            b.isSelected=i==page;b.setTextColor(if(i==page)teal else muted)
            b.background=shape(if(i==page)Color.rgb(24,56,59) else navy,14)
        }
        if(page!=0 && ::voice.isInitialized)voice.stop()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)
    }
    private fun buildUi() {
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(navy);layoutDirection=View.LAYOUT_DIRECTION_RTL;isFocusableInTouchMode=true}
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _,insets ->
            root.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets
        }
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(12),dp(22),dp(8))}
        header.addView(label("مساعدي",23f).apply{setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(label("شخصي / 0.7.0",12f).apply{setTextColor(teal);textDirection=View.TEXT_DIRECTION_LTR;setTypeface(Typeface.MONOSPACE,Typeface.NORMAL)})
        header.addView(button("إيقاف الكل",true){voice.stop();cancelPlan();showStatus("أُوقفت الجلسة وأُلغيت الإجراءات التي لم تُنفذ.",false)},LinearLayout.LayoutParams(dp(82),dp(48)).apply{marginStart=dp(8)})
        root.addView(header)
        val frame=FrameLayout(this);root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        val home=newPage(frame);val controlPage=newPage(frame);val historyPage=newPage(frame);val settingsPage=newPage(frame)
        content=home
        readiness=label("",12f).apply{setTextColor(teal);gravity=Gravity.CENTER;setOnClickListener{permissionsDialog()};contentDescription="حالة الصلاحيات والمحركات"};content.addView(readiness)
        content.addView(label("أوامرك بإذنك",12f).apply{setTextColor(teal);gravity=Gravity.CENTER})
        content.addView(label("شنو نقدر نساعدك اليوم؟",23f).apply{setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER})
        content.addView(label("تحدّث أو اكتب. نرتّب الخطوات، وأنت تختار متى ننفذ.",14f).apply{setTextColor(muted);gravity=Gravity.CENTER})
        val mic=button("ابدأ الاستماع") {startVoice()}.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(0,com.naeem.assistant.R.drawable.ic_mic,0,0)
            compoundDrawablePadding=dp(10);textSize=15f;contentDescription="ابدأ الاستماع للأوامر الصوتية"
            background=RippleDrawable(ColorStateList.valueOf(Color.argb(40,255,255,255)),shape(Color.rgb(22,62,64),38,Color.rgb(42,114,108)),null)
            setTextColor(teal)
        }
        content.addView(mic,LinearLayout.LayoutParams(dp(110),dp(98)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(18);bottomMargin=dp(4)})
        voiceHint=label("جلسة صوتية داخل التطبيق",12f).apply{setTextColor(muted);gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END};content.addView(voiceHint)
        addButton("إيقاف الصوت وإلغاء الخطة",true){voice.stop();cancelPlan();showStatus("توقف الاستماع وأُلغيت الخطوات المتبقية.",false)}
        val composer=card()
        heading(composer,"اكتب طلبك","أوامر واضحة، خطوة بخطوة")
        input=EditText(this).apply {
            id=View.generateViewId();hint="مثلاً: افتح يوتيوب ثم ديرلي منبه الساعة 6:30 الصبح"
            setHintTextColor(muted);setTextColor(white);textSize=16f;minLines=2;maxLines=6;gravity=Gravity.TOP or Gravity.START
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background=shape(navy,14,line);setPadding(dp(14),dp(14),dp(14),dp(14))
            contentDescription="اكتب أمرًا أو عدة أوامر للمساعد"
            composer.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){cancelPlan(false)}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        content=composer
        addButton("راجع خطة الأوامر") {
            voice.stop();input.clearFocus();(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            createPlan(input.text.toString());pages[0].post { pages[0].smoothScrollTo(0,taskCard.top) }
        }
        addButton("اسأل AI عن هذا النص",true){requestAi(input.text.toString())}
        addButton("اقترح خطة للتحكم بواسطة AI",true){requestAi(input.text.toString(),true)}
        content=home
        content.addView(label("جرّب طلبًا سريعًا",14f).apply{setTextColor(muted);setPadding(0,dp(18),0,dp(4))})
        val examples=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("منبّه الصبح" to "ديرلي منبه الساعة 6:30 الصبح","افتح يوتيوب" to "افتح يوتيوب").forEach { (title,command) ->
            examples.addView(button(title,true){input.setText(command);input.requestFocus()},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(3);marginEnd=dp(3)})
        }
        home.addView(examples)
        taskCard=card(home);content=taskCard
        progress=label("خطتك القادمة",18f).apply{setTextColor(teal);setTypeface(null,Typeface.BOLD)};content.addView(progress)
        preview=label("لا توجد خطة نشطة. اكتب طلبك وراجعه لتظهر الخطوات هنا.",15f).apply{setLineSpacing(dp(8).toFloat(),1.1f)};content.addView(preview)
        status=label("جاهز. الصوت اختياري؛ يمكنك استخدام الكتابة بلا إنترنت.",14f).apply {
            setTextColor(muted);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0,dp(12),0,dp(8));setTextIsSelectable(true)
        };content.addView(status)
        continueButton=addButton("نفّذ الخطوة التالية"){runNext()}.apply{visibility=View.GONE}
        confirm=addButton("أكد الإجراء المعروض"){confirmPending()}.apply{visibility=View.GONE}
        addButton("إلغاء الخطة الحالية",true){voice.stop();cancelPlan();showStatus("أُلغيت الخطوات المتبقية.",false)}
        content.addView(label("لا إرسال تلقائي للرسائل. راجع المستلم والنص داخل التطبيق الآخر.",12f).apply{setTextColor(muted)})
        content=home
        val conversation=card();content=conversation
        heading(content,"محادثة AI","عبر الإنترنت بإذنك؛ لا تنفذ ردود AI إجراءات على الجهاز")
        aiReply=label("اسأل من محرر الطلب. تُرسل رسالتك بعد موافقة منفصلة، وتبقى الأوامر الأساسية محلية.",15f).apply{setTextIsSelectable(true);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};content.addView(aiReply)
        addButton("مسح سياق المحادثة",true){cancelCloud(true);aiReply.text="حُذفت المحادثة من ذاكرة التطبيق.";showStatus("حُذف سياق AI المحلي؛ لا يعني ذلك حذف بيانات لدى مزود الخدمة.",false)}
        content=controlPage
        heading(content,"التحكم في جهازك","إجراءات تختارها أنت، بالصلاحيات الموجودة")
        val device=card();content=device
        heading(content,"تنقل وتحكم","تتطلب أزرار التنقل تفعيل خدمة الوصول الحالية")
        val controls=listOf("رجوع" to "ارجع","الشاشة الرئيسية" to "الرئيسية","التطبيقات الأخيرة" to "التطبيقات الأخيرة","افتح الإشعارات" to "افتح الإشعارات","الإعدادات السريعة" to "الإعدادات السريعة","تمرير لأعلى" to "اطلع","تمرير لأسفل" to "انزل")
        controls.forEach{(title,command)->addButton(title,true){createPlan(command);runNext()}}
        addButton("لوحة التحكم العائمة",true){accessibilityDialog()}
        content=controlPage
        val tools=card();content=tools;heading(content,"أدوات وتواصل")
        addButton("تجهيز رسالة أو مكالمة",true){communicationComposer()}
        addButton("فتح إعدادات الهاتف",true){createPlan("افتح الإعدادات");runNext()}
        addButton("فتح تطبيق الكاميرا",true){createPlan("افتح الكاميرا");runNext()}
        addButton("جهات الاتصال والألقاب",true){contactsDialog()}
        content=historyPage
        heading(content,"سجل مهامك","نشاطك في مكان واحد، محفوظ على جهازك")
        history=label("",14f).apply{setTextColor(muted)};content.addView(history)
        historyCards=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(historyCards)
        content=settingsPage
        heading(content,"إعداداتك","اضبط الصوت والوصول بما يناسبك")
        val hybrid=card();content=hybrid
        heading(content,"الذكاء الاصطناعي الهجين","الأوامر محلية؛ المحادثة السحابية بالنص وبموافقة لكل طلب")
        cloudState=label(if(tokens.hasKey())"مفتاح شخصي محفوظ؛ الاتصال لم يُختبر بعد" else "غير مفعّل: أضف مفتاح خدمة API الشخصي",13f).apply{setTextColor(muted)};content.addView(cloudState)
        addButton("إعداد اتصال AI",true){cloudSettings()}
        content=settingsPage
        val audio=card();content=audio
        heading(content,"الصوت والمحادثة","الاستماع يعمل أثناء بقاء التطبيق ظاهرًا")
        content.addView(label("لغة التعرف",14f))
        language=Spinner(this).apply {
            contentDescription="لغة التعرف على الكلام"
            adapter=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("العربية الليبية · ar-LY","English · en-US")) {
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View=super.getView(position,convertView,parent).also{(it as TextView).setTextColor(white);it.textSize=15f;it.setPadding(dp(12),dp(12),dp(12),dp(12))}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View=super.getDropDownView(position,convertView,parent).also{(it as TextView).setTextColor(white);it.setBackgroundColor(surface);it.setPadding(dp(14),dp(14),dp(14),dp(14))}
            }
            background=shape(navy,12,line);content.addView(this,LinearLayout.LayoutParams(-1,dp(52)))
            setSelection(prefs.getInt("language",0).coerceIn(0,1))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?){}
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                    if(position!=prefs.getInt("language",0)){prefs.edit().putInt("language",position).apply();if(::voice.isInitialized)voice.stop();cancelPlan(false)}
                }
            }
        }
        offline=toggle("صوت محلي فقط","offline",true,"حسب توفر محرك اللغة على هاتفك؛ لا تحويل تلقائي للشبكة")
        continuous=toggle("محادثة متواصلة","continuous",false,"جلسة متعددة الأدوار تنتهي بعد دقيقة خمول")
        wake=toggle("كلمة التنبيه: يا مساعدي","wake",false,"تجريبية، داخل التطبيق فقط")
        content=settingsPage
        val access=card();content=access
        heading(content,"الوصول والتحكم")
        addButton("الصلاحيات وحالة الجهاز",true){permissionsDialog()}
        addButton("إدارة الألقاب المحلية",true){contactsDialog()}
        addButton("التحكم بالتطبيقات • Accessibility",true){accessibilityDialog()}
        content=settingsPage
        val privacy=card();content=privacy
        heading(content,"خصوصيتك أولًا","لا تُحفظ نصوص الأوامر أو التسجيلات. السجل لا يتضمن محتوى الرسائل أو أرقام المستلمين.")
        addButton("أمثلة وحدود النسخة",true){infoDialog()}
        addButton("حذف السجل والألقاب والبيانات المحلية",true){
            AlertDialog.Builder(this).setTitle("حذف البيانات المحلية؟").setMessage("يحذف السجل والألقاب والإعدادات. لا يحذف رسائل أرسلتها من تطبيق آخر.")
                .setNegativeButton("إلغاء",null).setPositiveButton("حذف"){_,_->
                    voice.stop();cancelPlan(false);tokens.clear();chatContext.clear();cloudState.setText(R.string.cloud_not_configured);aiReply.text="حُذف سياق المحادثة المحلي.";prefs.edit().clear().apply();offline.isChecked=true;continuous.isChecked=false;wake.isChecked=false;language.setSelection(0)
                    input.text.clear();updateHistory();showStatus("حُذفت البيانات المحلية.",false)
                }.show()
        }.setTextColor(Color.rgb(255,163,163))
        settingsPage.addView(label("مساعدي AI · 0.7.0-personal\nنسخة تجربة • أوامرك بإذنك",12f).apply{setTextColor(muted);gravity=Gravity.CENTER;setPadding(0,dp(20),0,dp(8))})
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=shape(navy,0,line)}
        listOf("المساعد","التحكم","السجل","الإعدادات").forEachIndexed { i,title ->
            val b=button(title,true){selectPage(i)}.apply{contentDescription="افتح $title";textSize=12f;setPadding(dp(3),dp(12),dp(3),dp(12))}
            navButtons.add(b);nav.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(3);marginEnd=dp(3)})
        }
        root.addView(nav);content=home;selectPage(0)
    }
    private fun showStatus(message:String,speak:Boolean=true) {
        if(isFinishing || isDestroyed)return
        status.text=message
        if(::voiceHint.isInitialized)voiceHint.text=message
        if(selectedPage!=0)Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        if(::taskCard.isInitialized)taskCard.background=shape(surface,22,if(pending!=null)Color.rgb(243,193,107) else line)
        if(speak && ::voice.isInitialized)voice.say(message)
    }
    private fun startVoice() {
        selectPage(0)
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
        input.setText(text)
        if(CommandParser.plan(text).error.isNotBlank() && tokens.hasKey())requestAi(text) else createPlan(text)
    }
    private fun cancelPlan(log:Boolean=true) {
        if(!::preview.isInitialized)return
        cancelCloud(false)
        if(log && index<plan.size)record("أُلغيت الخطوات المتبقية")
        planGeneration++;resolving=false;executing=false;plan=emptyList();index=0;pending=null;gate.invalidate();handler.removeCallbacksAndMessages(null)
        dialog?.dismiss();dialog=null
        progress.text="خطتك القادمة"
        preview.text="لا توجد خطة نشطة. اكتب طلبك وراجعه لتظهر الخطوات هنا."
        taskCard.background=shape(surface,22,line)
        if(::status.isInitialized)status.text="راجع طلبك لبدء خطة جديدة."
        confirm.visibility=View.GONE;continueButton.visibility=View.GONE
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
        Action.RECENTS -> "عرض التطبيقات الأخيرة";Action.NOTIFICATIONS -> "فتح الإشعارات";Action.QUICK_SETTINGS -> "فتح الإعدادات السريعة"
        Action.SETTINGS -> "فتح إعدادات الهاتف";Action.CAMERA -> "فتح تطبيق الكاميرا";Action.WEB_SEARCH -> "البحث عن ${step.query}"
        Action.BACK -> "رجوع";Action.HOME -> "الرئيسية";Action.SCROLL_UP -> "تمرير لأعلى";Action.SCROLL_DOWN -> "تمرير لأسفل"
        else -> "غير مدعوم"
    }
    private fun renderPlan() {
        progress.text=if(index>=plan.size)"اكتملت الخطة" else "خطتك · ${index+1} من ${plan.size}"
        preview.text=plan.mapIndexed { i,s -> "${if(i<index)"✓" else if(i==index)"◉" else "○"} ${i+1}. ${title(s)}" }.joinToString("\n")
        continueButton.visibility=if(index<plan.size && pending==null)View.VISIBLE else View.GONE
    }
    private fun runNext() {
        if(!resumed || locked()) { showStatus("افتح قفل الجهاز قبل التنفيذ.");return }
        if(executing){showStatus("الإجراء الحالي لم ينتهِ بعد.",false);return}
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
        val aliases=LocalData.aliases(prefs.getString("aliases","{}"))
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
        confirm.visibility=View.VISIBLE;progress.setText(R.string.confirmation_waiting);selectPage(0);showStatus(message)
        pages[0].post{pages[0].smoothScrollTo(0,taskCard.top)}
        confirmationCountdown(item.id,SystemClock.elapsedRealtime()+30_000)
    }
    private fun confirmationCountdown(id:String,deadline:Long) {
        if(pending?.id!=id)return
        val remaining=deadline-SystemClock.elapsedRealtime()
        if(remaining<=0){pending=null;gate.invalidate();confirm.visibility=View.GONE;renderPlan();showStatus("انتهت مهلة التأكيد؛ لم يتم تنفيذ الإجراء.");return}
        progress.text=getString(R.string.confirmation_countdown,((remaining+999)/1000).toInt())
        handler.postDelayed({confirmationCountdown(id,deadline)},1000)
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
            Action.SETTINGS -> {actionIntent=Intent(Settings.ACTION_SETTINGS);result="فُتحت إعدادات الهاتف؛ أي تغيير يتم منك داخل النظام."}
            Action.CAMERA -> {actionIntent=Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);result="فُتح طلب تطبيق الكاميرا؛ لم يُلتقط أو يُرسل شيء."}
            Action.WEB_SEARCH -> {actionIntent=Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q=${Uri.encode(step.query)}"));result="فُتح البحث في المتصفح؛ قد يحتاج الإنترنت."}
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
            Action.BACK,Action.HOME,Action.SCROLL_UP,Action.SCROLL_DOWN,Action.RECENTS,Action.NOTIFICATIONS,Action.QUICK_SETTINGS -> {
                external=false
                val service=AssistantAccessibilityService.current
                if(service==null){showStatus("فعّل خدمة التحكم من إعدادات Accessibility أولًا.");return}
                val epoch=planGeneration;executing=true
                service.execute(step.action){ok->runOnUiThread{
                    if(epoch==planGeneration){executing=false;if(ok)complete("طلب تحكم نُفّذ؛ تحقق من الواجهة.")else showStatus("لم ينفذ النظام إجراء التحكم؛ توقفت الخطة.")}
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
        val list=LocalData.history(prefs.getString("history","[]"),System.currentTimeMillis())
        val next=JSONArray();next.put(JSONObject().put("time",System.currentTimeMillis()).put("summary",summary))
        for(i in 0 until minOf(list.length(),99)){val item=list.getJSONObject(i);if(System.currentTimeMillis()-item.optLong("time")<30L*24*60*60*1000)next.put(item)}
        prefs.edit().putString("history",next.toString()).apply();updateHistory()
    }
    private fun updateHistory() {
        if(!::history.isInitialized)return
        val list=LocalData.history(prefs.getString("history","[]"),System.currentTimeMillis());val valid=JSONArray()
        for(i in 0 until list.length()){val item=list.getJSONObject(i);if(System.currentTimeMillis()-item.optLong("time")<30L*24*60*60*1000)valid.put(item)}
        prefs.edit().putString("history",valid.toString()).apply()
        history.text=if(valid.length()==0)"لا توجد أنشطة حتى الآن." else "${valid.length()} نشاط محفوظ · آخر 30 يومًا"
        historyCards.removeAllViews()
        if(valid.length()==0){
            val empty=card(historyCards);heading(empty,"بداية جديدة","عندما تراجع خطة أو تنفذ خطوة، يظهر ملخصها هنا. لا تُحفظ نصوص الرسائل أو أرقام المستلمين.")
        }
        for(i in 0 until minOf(valid.length(),30)){
            val item=valid.getJSONObject(i);val entry=card(historyCards)
            entry.addView(label(item.getString("summary"),15f))
            entry.addView(label(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.getLong("time"))),12f).apply{setTextColor(muted)})
        }
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
                val obj=LocalData.aliases(prefs.getString("aliases","{}"));obj.put(key,JSONObject().put("name",name.text.toString()).put("phone",phone));prefs.edit().putString("aliases",obj.toString()).apply()
                showStatus("تم حفظ اللقب محليًا.",false)
            }.show()
    }
    private fun accessibilityDialog() {
        voice.stop()
        AlertDialog.Builder(this).setTitle("تحكم محدود وبإذنك")
            .setMessage("الخدمة تنفذ الرجوع والرئيسية والتطبيقات الأخيرة والإشعارات والإعدادات السريعة والتمرير عبر أزرار تضغطها أو أوامر واضحة. لا تقرأ محتوى الشاشة ولا تنقر إرسال ولا تنفذ أفعالًا مستقلة. يمكنك سحب لوحة التحكم أو طيها. لوحة عائمة اختيارية تتيح التحكم أثناء فتح التطبيقات. فعّل الخدمة يدويًا من Android بعد الموافقة، وأوقفها من هناك متى شئت.")
            .setNegativeButton("إلغاء",null)
            .setNeutralButton("إظهار/إخفاء اللوحة") { _,_->
                val service=AssistantAccessibilityService.current
                if(service==null)showStatus("فعّل الخدمة أولًا.",false) else service.togglePanel()
            }
            .setPositiveButton("أوافق، افتح إعدادات التفعيل") { _,_->startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
    }
    private fun cancelCloud(clearHistory:Boolean) {
        cloudGeneration++;cloudRequest?.cancel();cloudRequest=null;cloud.cancel()
        if(clearHistory){chatContext.clear();if(::aiReply.isInitialized)aiReply.text="ابدأ محادثة جديدة. السياق السابق غير محفوظ."}
    }
    private fun requestAi(text:String,planning:Boolean=false) {
        if(!resumed || locked()){showStatus("افتح الجهاز قبل إرسال طلب AI.",false);return}
        if(text.isBlank() || text.length>2000){showStatus("اكتب سؤالًا من 1 إلى 2000 حرف.",false);return}
        voice.stop();cancelPlan(false)
        if(!tokens.hasKey()){showStatus("أضف مفتاح API الشخصي من الإعدادات ← إعداد اتصال AI. الأوامر المحلية تعمل دون مفتاح.",false);return}
        val epoch=cloudGeneration
        dialog=AlertDialog.Builder(this).setTitle(if(planning)"اقتراح خطة عبر OpenAI؟" else "إرسال النص إلى OpenAI؟")
            .setMessage("سيُرسل النص أدناه إلى api.openai.com. ${if(planning)"لا يُرفق سجل المحادثة. الاقتراح يخضع للمراجعة ولا ينفذ تلقائيًا." else "قد يُرفق آخر 8 رسائل من سياق محادثة AI الحالية."} لا نرفق الصوت أو جهات الاتصال أو سجل المهام. قد يُحتسب الاستخدام على حساب API. طلب عدم تخزين الرد ليس ضمانًا لانعدام احتفاظ المزود.\n\n$text")
            .setNegativeButton("إلغاء",null).setPositiveButton("أوافق، أرسل النص"){_,_->
                if(epoch!=cloudGeneration || !resumed || locked())return@setPositiveButton
                val key=try{tokens.read()}catch(_:Exception){showStatus("تعذر فتح المفتاح المحمي. أعد حفظه من الإعدادات.",false);return@setPositiveButton}
                if(key.isBlank()){showStatus("مفتاح الخدمة غير متاح.",false);return@setPositiveButton}
                val model=prefs.getString("cloud-model","gpt-4.1-mini")?:"gpt-4.1-mini"
                val context=chatContext.toList();dialog=null
                val request=CloudRequest();cloudRequest=request
                selectPage(0);aiReply.setText(R.string.cloud_waiting);showStatus("جارٍ إرسال النص المأذون إلى خدمة AI…",false)
                cloudWorker.execute{
                    try{
                        request.checkActive()
                        val reply=cloud.answer(key,model,text,context,planning,request)
                        runOnUiThread{
                            if(epoch!=cloudGeneration || !resumed || locked())return@runOnUiThread
                            if(planning){previewProposal(reply);return@runOnUiThread}
                            chatContext.add("user" to text);chatContext.add("assistant" to reply)
                            while(chatContext.size>8)chatContext.removeAt(0)
                            cloudState.text="نجح آخر طلب اتصال بالخدمة";aiReply.text=reply;showStatus("وصل رد AI؛ لم يُنفذ أي إجراء على الجهاز.",false);voice.say(reply)
                            pages[0].post{(aiReply.parent as? View)?.let{pages[0].smoothScrollTo(0,it.top)}}
                        }
                    }catch(error:Exception){
                        runOnUiThread{
                            if(epoch==cloudGeneration && resumed){
                                val message=if(error is IllegalStateException)error.message?:"تعذر إكمال الطلب." else "تعذر الاتصال بخدمة AI. تحقق من الإنترنت ثم أعد المحاولة يدويًا."
                                aiReply.text=message;showStatus(message,false)
                            }
                        }
                    }
                }
            }.show()
    }
    private fun cloudSettings() {
        voice.stop();cancelCloud(true)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
        layout.addView(label("مفتاحك الشخصي لهذا الهاتف فقط. يُحفظ مشفرًا بمفتاح Android Keystore، ولا يُضمّن في APK. تحتاج حساب API صالحًا ورصيدًا؛ لا تضع مفتاحًا مشتركًا لتوزيعه على آخرين.",14f))
        val secret=EditText(this).apply{hint=if(tokens.hasKey())"اتركه فارغًا للاحتفاظ بالمفتاح" else "مفتاح API الشخصي";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;setAutofillHints(null);layout.addView(this)}
        val model=EditText(this).apply{hint="اسم النموذج";setText(prefs.getString("cloud-model","gpt-4.1-mini"));layout.addView(this)}
        val config=AlertDialog.Builder(this).setTitle("اتصال AI الشخصي").setView(layout)
            .setNegativeButton("إلغاء",null).setNeutralButton("حذف المفتاح"){_,_->tokens.clear();chatContext.clear();cloudState.text="غير مفعّل: لا يوجد مفتاح محفوظ";showStatus("حُذف المفتاح المحلي. يمكنك إلغاء المفتاح نفسه من حساب مزود API.",false)}
            .setPositiveButton("حفظ",null).create()
        dialog=config
        config.setOnDismissListener{secret.text.clear();if(dialog===config)dialog=null}
        config.setOnShowListener{config.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val value=secret.text.toString().trim();val chosen=model.text.toString().trim()
            if(!Regex("[A-Za-z0-9._-]{1,80}").matches(chosen)){model.error="اسم نموذج غير صحيح";return@setOnClickListener}
            if(value.isBlank() && !tokens.hasKey()){secret.error="أدخل مفتاح API";return@setOnClickListener}
            try{
                if(value.isNotBlank())tokens.save(value)
                prefs.edit().putString("cloud-model",chosen).apply();secret.text.clear()
                cloudState.text=getString(R.string.cloud_saved_model,chosen)
                config.dismiss();showStatus("حُفظت إعدادات AI. لن يُرسل أي نص قبل موافقتك.",false)
            }catch(_:Exception){secret.error="تعذر الحفظ المحمي؛ لم نحفظ المفتاح كنص صريح."}
        }}
        config.show()
        config.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun updateReadiness(){
        if(!::readiness.isInitialized)return
        val mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        val control=AssistantAccessibilityService.current!=null
        readiness.text=listOf(if(mic)"الميكروفون جاهز" else "الكتابة جاهزة",if(control)"التحكم مفعّل" else "التحكم يحتاج تفعيلًا").joinToString(" · ")
    }
    private fun previewProposal(raw:String){
        val proposed=DevicePlan.parse(raw)
        if(proposed.error.isNotBlank()){showStatus(proposed.error,false);aiReply.text=proposed.error;return}
        cancelPlan(false);plan=proposed.steps;index=0;renderPlan();selectPage(0)
        aiReply.text="اقتُرحت خطة قابلة للمراجعة. لم يُنفذ أي إجراء؛ راجع المستلم والنص والوقت قبل المتابعة."
        record("اقتراح خطة AI • ${plan.size} خطوة");showStatus("راجع خطة AI ثم اختر تنفيذ الخطوة التالية. التواصل يحتاج تأكيدًا مستقلًا.",false)
        pages[0].post{pages[0].smoothScrollTo(0,taskCard.top)}
    }
    private fun communicationComposer(){
        voice.stop();cancelPlan(false)
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(6))}
        val channel=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("SMS","WhatsApp","مكالمة هاتف"));form.addView(this)}
        val recipient=EditText(this).apply{hint="اسم أو لقب محفوظ أو رقم دولي";contentDescription="مستلم الرسالة أو المكالمة";form.addView(this)}
        val body=EditText(this).apply{hint="نص الرسالة دون علامات تنصيص";minLines=3;gravity=Gravity.TOP or Gravity.START;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;form.addView(this)}
        channel.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){body.visibility=if(position==2)View.GONE else View.VISIBLE}
        }
        form.addView(label("سنجهز خطة للمراجعة. لا إرسال أو اتصال عند حفظ النموذج.",13f))
        val sheet=AlertDialog.Builder(this).setTitle("تجهيز تواصل").setView(ScrollView(this).apply{addView(form)})
            .setNegativeButton("إلغاء",null).setPositiveButton("مراجعة الخطة",null).create()
        dialog=sheet
        sheet.setOnShowListener{sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val to=recipient.text.toString().trim();val message=body.text.toString()
            if(to.isBlank()||to.length>120){recipient.error="حدد مستلمًا واضحًا";return@setOnClickListener}
            if(channel.selectedItemPosition!=2 && (message.isBlank()||message.length>2000)){body.error="اكتب رسالة من 1 إلى 2000 حرف";return@setOnClickListener}
            val action=listOf(Action.SMS,Action.WHATSAPP,Action.DIAL)[channel.selectedItemPosition]
            sheet.dismiss();dialog=null;cancelPlan(false)
            plan=listOf(ParsedCommand(action,recipient=to,message=if(action==Action.DIAL)"" else message));index=0
            renderPlan();selectPage(0);record("خطة تواصل للمراجعة")
            showStatus("راجع خطة التواصل؛ لم يُفتح تطبيق ولم تُرسل رسالة بعد.",false)
            pages[0].post{pages[0].smoothScrollTo(0,taskCard.top)}
        }}
        sheet.show()
    }
    private fun infoDialog() {
        voice.stop()
        AlertDialog.Builder(this).setTitle("أمثلة وحدود نسخة التجربة")
            .setMessage("افتح يوتيوب ثم افتح واتساب\nديرلي منبه الساعة 6:30 الصبح\nاتصل على أحمد\ncall Sara\nابعت SMS إلى أحمد \"بنتأخر\"\nsend WhatsApp to Sara \"I am late\"\n\nقل: التالي، إلغاء، أو أكد الاتصال. الرسائل تُفتح كمسودات وتتطلب إرسالًا يدويًا. محادثة AI السحابية متاحة بعد إعداد مفتاحك وموافقتك على إرسال النص؛ لا يوجد محرك صوت مخصص للهجة. التعرف الصوتي حسب هاتفك. أثناء نطق الرد استخدم زر الإيقاف للمقاطعة. الوضع المحلي يحتاج Android 12+ ومحركًا يدعم اللغة. التنبيه «يا مساعدي» تجريبي داخل التطبيق ولمدة جلسة محدودة، وليس كشفًا دائمًا منخفض الطاقة. لا فتح تلقائي للقفل أو تنفيذ بالخلفية. التراجع يلغي الخطوات المتبقية فقط. سياق محادثة AI في الذاكرة فقط ويُمسح عند الخروج. لا تُحفظ التسجيلات أو نصوص الأوامر في السجل؛ السجل المختصر يحذف بعد 30 يومًا وبحد أقصى 100 حدث.")
            .setPositiveButton("فهمت",null).show()
    }
}
