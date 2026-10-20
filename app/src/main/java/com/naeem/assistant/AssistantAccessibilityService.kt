package com.naeem.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class AssistantAccessibilityService : AccessibilityService() {
    companion object {
        private var reference:WeakReference<AssistantAccessibilityService> = WeakReference(null)
        val current:AssistantAccessibilityService? get()=reference.get()
    }
    private var panel:View?=null
    private val handler=Handler(Looper.getMainLooper())
    private var gestureBusy=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun locked()=(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    fun togglePanel(){if(panel!=null)removePanel() else showPanel()}
    private fun showPanel(){
        if(locked())return
        val wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val row=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5))
            background=GradientDrawable().apply{setColor(Color.rgb(15,31,46));cornerRadius=dp(18).toFloat();setStroke(dp(1),Color.rgb(48,84,96))}
        }
        val params=WindowManager.LayoutParams(dp(94),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{
            gravity=Gravity.END or Gravity.TOP;x=dp(8);y=dp(100)
        }
        fun control(text:String,description:String,action:()->Unit)=Button(this).apply{
            this.text=text;contentDescription=description;textSize=12f;isAllCaps=false;minHeight=dp(48);setPadding(dp(2),0,dp(2),0)
            setTextColor(Color.rgb(74,224,193));backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(22,45,60));setOnClickListener{action()}
        }
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val handle=control("⋮ تحكم","اسحب لتحريك اللوحة أو اضغط لطيها"){
            body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE
        }
        var originX=0f;var originY=0f;var startX=0;var startY=0;var moved=false
        handle.setOnTouchListener{view,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{originX=event.rawX;originY=event.rawY;startX=params.x;startY=params.y;moved=false;true}
                MotionEvent.ACTION_MOVE->{
                    val dx=event.rawX-originX;val dy=event.rawY-originY
                    if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(8))moved=true
                    if(moved){
                        params.x=(startX-dx.toInt()).coerceIn(0,(resources.displayMetrics.widthPixels-dp(94)).coerceAtLeast(0))
                        params.y=(startY+dy.toInt()).coerceIn(0,(resources.displayMetrics.heightPixels-row.height).coerceAtLeast(0))
                        if(panel===row)runCatching{wm.updateViewLayout(row,params)}
                    };true
                }
                MotionEvent.ACTION_UP->{if(!moved)view.performClick();true}
                MotionEvent.ACTION_CANCEL->true
                else->false
            }
        }
        row.addView(handle)
        listOf("رجوع" to Action.BACK,"الرئيسية" to Action.HOME,"الأخيرة" to Action.RECENTS,
            "الإشعارات" to Action.NOTIFICATIONS,"السريعة" to Action.QUICK_SETTINGS,"↑ تمرير" to Action.SCROLL_UP,"↓ تمرير" to Action.SCROLL_DOWN).forEach{(text,action)->
            body.addView(control(text,text){execute(action){ok->if(!ok)android.widget.Toast.makeText(this,"الإجراء غير متاح الآن",android.widget.Toast.LENGTH_SHORT).show()}})
        }
        body.addView(control("إغلاق","إغلاق لوحة التحكم"){removePanel()});row.addView(body)
        runCatching{wm.addView(row,params);panel=row}.onFailure{android.widget.Toast.makeText(this,"تعذر عرض لوحة التحكم",android.widget.Toast.LENGTH_SHORT).show()}
    }
    private fun removePanel(){panel?.let{runCatching{(getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)}};panel=null}
    override fun onServiceConnected(){reference=WeakReference(this)}
    override fun onAccessibilityEvent(event:AccessibilityEvent?){if(locked())removePanel() /* No screen content is read. */}
    override fun onInterrupt(){removePanel()}
    override fun onDestroy(){removePanel();handler.removeCallbacksAndMessages(null);reference.clear();super.onDestroy()}
    fun execute(action:Action,completed:(Boolean)->Unit){
        if(locked()){completed(false);return}
        val global=when(action){Action.BACK->GLOBAL_ACTION_BACK;Action.HOME->GLOBAL_ACTION_HOME;Action.RECENTS->GLOBAL_ACTION_RECENTS;Action.NOTIFICATIONS->GLOBAL_ACTION_NOTIFICATIONS;Action.QUICK_SETTINGS->GLOBAL_ACTION_QUICK_SETTINGS;else->null}
        if(global!=null){completed(runCatching{performGlobalAction(global)}.getOrDefault(false));return}
        if(action !in listOf(Action.SCROLL_UP,Action.SCROLL_DOWN) || gestureBusy){completed(false);return}
        gestureBusy=true
        val once=AtomicBoolean(false)
        fun finish(ok:Boolean){if(once.compareAndSet(false,true)){gestureBusy=false;completed(ok)}}
        val metrics=resources.displayMetrics;val x=metrics.widthPixels*.5f;val top=metrics.heightPixels*.3f;val bottom=metrics.heightPixels*.7f
        val path=Path().apply{moveTo(x,if(action==Action.SCROLL_DOWN)bottom else top);lineTo(x,if(action==Action.SCROLL_DOWN)top else bottom)}
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,350)).build()
        val accepted=runCatching{dispatchGesture(gesture,object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){finish(true)}
            override fun onCancelled(gestureDescription:GestureDescription?){finish(false)}
        },null)}.getOrDefault(false)
        if(!accepted)finish(false) else handler.postDelayed({finish(false)},2000)
    }
}
