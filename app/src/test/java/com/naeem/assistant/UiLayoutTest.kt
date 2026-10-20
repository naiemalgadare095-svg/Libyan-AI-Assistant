package com.naeem.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="ar-rLY-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiLayoutTest {
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    @Test fun renderScreensAndKeepDraftAcrossNavigation() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get();val decor=(activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        fun layout(){decor.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));decor.layout(0,0,720,1600)}
        fun capture(name:String) {
            layout();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();layout();val bitmap=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888);decor.draw(Canvas(bitmap))
            val dir=File("build/reports/ui-preview").apply{mkdirs()}
            FileOutputStream(File(dir,"$name.png")).use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        }
        try {
            val input=views(decor).filterIsInstance<EditText>().first()
            capture("assistant")
            input.setText("افتح يوتيوب")
            views(decor).filterIsInstance<Button>().first{it.text=="التحكم"}.performClick();capture("controls")
            views(decor).filterIsInstance<Button>().first{it.text=="السجل"}.performClick();capture("history")
            views(decor).filterIsInstance<Button>().first{it.text=="الإعدادات"}.performClick();capture("settings")
            views(decor).filterIsInstance<Button>().first{it.text=="المساعد"}.performClick();layout()
            assertEquals("افتح يوتيوب",input.text.toString())
            assertTrue(input.isShown)
            val nav=views(decor).filterIsInstance<Button>().filter{it.text in listOf("المساعد","التحكم","السجل","الإعدادات")}
            assertEquals(4,nav.size)
            nav.forEach { assertTrue(it.height>=48*activity.resources.displayMetrics.density);assertTrue(it.isShown) }
        } finally { controller.pause().stop().destroy() }
    }
}
