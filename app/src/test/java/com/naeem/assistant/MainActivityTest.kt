package com.naeem.assistant

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MainActivityTest {
    private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var activity:MainActivity
    @Before fun setup() { controller=Robolectric.buildActivity(MainActivity::class.java).setup();activity=controller.get() }
    @After fun teardown() { controller.pause().stop().destroy() }
    private fun views(view:View=activity.window.decorView):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun button(prefix:String)=views().filterIsInstance<Button>().first { it.text.toString().startsWith(prefix) }
    private fun text()=views().filterIsInstance<TextView>().joinToString("\n"){it.text.toString()}
    private fun makePlan(command:String) {
        views().filterIsInstance<EditText>().first().setText(command)
        button("راجع خطة").performClick()
    }
    private fun pending():Any? = MainActivity::class.java.getDeclaredField("pending").apply{isAccessible=true}.get(activity)
    private fun smsPlan() { makePlan("send SMS to +218912345678 \"hello\"");button("نفّذ الخطوة").performClick() }
    @Test fun launchesAndShowsHonestVersionAndLocalPrivacy() {
        assertTrue(text().contains("0.5.0"));assertTrue(text().contains("لا تُحفظ نصوص"))
    }
    @Test fun planDoesNotLaunchExternalApp() {
        makePlan("افتح يوتيوب ثم افتح واتساب")
        assertTrue(text().contains("1. فتح يوتيوب"));assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun messageNeedsFreshConfirmationAndOnlyOpensComposer() {
        smsPlan();assertNotNull(pending());assertNull(shadowOf(activity).nextStartedActivity)
        button("أكد فتح المسودة").performClick()
        val intent=shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO,intent.action);assertEquals("smsto",intent.data?.scheme)
        assertEquals("hello",intent.getStringExtra("sms_body"));assertNull(pending())
    }
    @Test fun editingCommandInvalidatesPendingAction() {
        smsPlan();views().filterIsInstance<EditText>().first().setText("افتح يوتيوب")
        assertNull(pending());assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun backgroundCancelsConfirmationAndResumeAllowsReview() {
        smsPlan();controller.pause();assertNull(pending());controller.resume()
        assertEquals(View.VISIBLE,button("نفّذ الخطوة").visibility)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun expiredConfirmationNeverLaunches() {
        smsPlan();shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertNull(pending());assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(text().contains("انتهت مهلة التأكيد"))
    }
    @Test fun unknownStepPreventsPartialPlan() {
        makePlan("افتح يوتيوب ثم امسح الملفات")
        assertEquals(View.GONE,button("نفّذ الخطوة").visibility)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun cancellingOnlyStopsRemainingSteps() {
        smsPlan();button("إيقاف الصوت").performClick()
        assertNull(pending());assertEquals(View.GONE,button("نفّذ الخطوة").visibility)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun contactAndMessageContentsAreAbsentFromStoredHistory() {
        smsPlan()
        val history=activity.getSharedPreferences("assistant-local",0).getString("history","").orEmpty()
        assertFalse(history.contains("218912345678"));assertFalse(history.contains("hello"))
    }
}
