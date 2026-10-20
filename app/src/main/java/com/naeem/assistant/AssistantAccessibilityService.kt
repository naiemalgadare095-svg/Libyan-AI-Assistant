package com.naeem.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.app.KeyguardManager
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.graphics.Color
import java.lang.ref.WeakReference

class AssistantAccessibilityService : AccessibilityService() {
    companion object {
        private var reference: WeakReference<AssistantAccessibilityService> = WeakReference(null)
        val current: AssistantAccessibilityService? get()=reference.get()
    }
    private var panel: View? = null
    fun togglePanel() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if(panel != null) { wm.removeView(panel);panel=null;return }
        if((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked)return
        val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(20,50,65)) }
        listOf("رجوع" to Action.BACK,"الرئيسية" to Action.HOME,"↑" to Action.SCROLL_UP,"↓" to Action.SCROLL_DOWN).forEach { (text,action) ->
            row.addView(Button(this).apply { this.text=text;setOnClickListener {
                if(!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked)execute(action){}
            } })
        }
        row.addView(Button(this).apply { text="إغلاق";setOnClickListener { togglePanel() } })
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT)
        params.gravity=Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(row,params);panel=row
    }
    private fun removePanel() { panel?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) };panel=null }
    override fun onServiceConnected() { reference = WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No screen content is collected. */ }
    override fun onInterrupt() { removePanel();reference.clear() }
    override fun onDestroy() { removePanel();reference.clear(); super.onDestroy() }
    fun execute(action: Action, completed: (Boolean) -> Unit) {
        when (action) {
            Action.BACK -> completed(performGlobalAction(GLOBAL_ACTION_BACK))
            Action.HOME -> completed(performGlobalAction(GLOBAL_ACTION_HOME))
            Action.SCROLL_UP, Action.SCROLL_DOWN -> {
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels * .5f
                val top = metrics.heightPixels * .3f
                val bottom = metrics.heightPixels * .7f
                val path = Path().apply { moveTo(x, if(action == Action.SCROLL_DOWN) bottom else top); lineTo(x, if(action == Action.SCROLL_DOWN) top else bottom) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,350)).build()
                val accepted = dispatchGesture(gesture,object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { completed(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { completed(false) }
                },null)
                if(!accepted) completed(false)
            }
            else -> completed(false)
        }
    }
}
