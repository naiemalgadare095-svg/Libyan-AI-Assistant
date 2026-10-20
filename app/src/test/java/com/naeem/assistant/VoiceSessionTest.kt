package com.naeem.assistant

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class VoiceSessionTest {
    private lateinit var controller:org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var voice:VoiceSession
    private val heard=mutableListOf<String>()
    private val statuses=mutableListOf<String>()
    @Before fun setup(){
        controller=Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        voice=VoiceSession(controller.get(),{statuses.add(it)},{heard.add(it)})
        voice.foreground(true)
    }
    @After fun close(){voice.close();controller.pause().stop().destroy()}
    private fun result(text:String)=Bundle().apply{putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text))}
    @Test fun lateRecognitionResultAfterStopIsIgnored(){
        voice.start(true,false,true,"ar-LY");shadowOf(Looper.getMainLooper()).idle()
        val old=ShadowSpeechRecognizer.getLatestSpeechRecognizer();assertNotNull(old)
        voice.stop();shadowOf(old).triggerOnResults(result("اتصل على أحمد"));shadowOf(Looper.getMainLooper()).idle()
        assertTrue(heard.isEmpty())
    }
    @Test fun retryUsesFreshRecognizerAndDropsOldResults(){
        voice.start(true,false,true,"ar-LY");shadowOf(Looper.getMainLooper()).idle();val old=ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        shadowOf(old).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        val current=ShadowSpeechRecognizer.getLatestSpeechRecognizer();assertNotSame(old,current);assertTrue(shadowOf(old).isDestroyed)
        shadowOf(old).triggerOnResults(result("call Sara"));assertTrue(heard.isEmpty())
        shadowOf(current).triggerOnResults(result("افتح يوتيوب"));assertEquals(listOf("افتح يوتيوب"),heard)
    }
    @Test fun backgroundInvalidatesSpeechCallbacks(){
        voice.start(true,false,true,"ar-LY");shadowOf(Looper.getMainLooper()).idle();val recognizer=ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        voice.foreground(false);shadowOf(recognizer).triggerOnResults(result("افتح واتساب"));shadowOf(Looper.getMainLooper()).idle()
        assertTrue(heard.isEmpty())
    }
    @Test fun localModeNeverFallsBackWhenUnavailable(){
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        voice.start(true,false,true,"ar-LY");shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());assertTrue(statuses.last().contains("غير متاح"))
    }
}
