package com.naeem.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceSession(private val activity: Activity, private val status: (String)->Unit, private val command:(String)->Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var active = false
    private var busy = false
    private var suspended = false
    private var foreground = false
    private var generation = 0
    private var lastActivity = 0L
    private var errors = 0
    private var offline = false
    private var wake = false
    private var continuous = false
    private var language = "ar-LY"
    private val idle = Runnable {
        if(active && SystemClock.elapsedRealtime()-lastActivity >= 60_000) stop("انتهت الجلسة بعد دقيقة خمول لتوفير البطارية.")
    }
    init {
        tts = TextToSpeech(activity) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if(ttsReady) {
                tts?.language = Locale.forLanguageTag("ar")
                tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                    override fun onStart(id:String?) {}
                    override fun onDone(id:String?) { handler.post { if(id == "voice-$generation") schedule() } }
                    @Deprecated("Android callback") override fun onError(id:String?) { onDone(id) }
                })
            }
        }
    }
    fun foreground(value: Boolean) { foreground=value; if(!value) stop() }
    fun localAvailable() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
    fun start(local:Boolean, wakeWord:Boolean, loop:Boolean, locale:String) {
        stop()
        if(!foreground || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            status("فعّل إذن الميكروفون أولًا."); return
        }
        if(local && !localAvailable()) { status("التعرف المحلي غير متاح؛ استخدم الكتابة أو ثبّت محركًا محليًا. لا تحويل تلقائي للسحابة."); return }
        if(!local && !SpeechRecognizer.isRecognitionAvailable(activity)) { status("لا يوجد محرك تعرف على الكلام."); return }
        offline=local; wake=wakeWord; continuous=loop || wakeWord; language=locale
        active=true; errors=0; lastActivity=SystemClock.elapsedRealtime()
        val token=generation
        try {
            recognizer=if(local && Build.VERSION.SDK_INT>=31) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) else SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(params:Bundle?) { if(token==generation) status(if(wake) "بانتظار «يا مساعدي» — داخل التطبيق فقط" else "نسمعك…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB:Float) {}
                override fun onBufferReceived(buffer:ByteArray?) {}
                override fun onEndOfSpeech() { if(token==generation) status("جارٍ فهم الكلام…") }
                override fun onPartialResults(results:Bundle?) {}
                override fun onEvent(eventType:Int,params:Bundle?) {}
                override fun onError(error:Int) {
                    if(token!=generation || !active || suspended) return
                    busy=false; errors++
                    if(error in listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT,SpeechRecognizer.ERROR_NO_MATCH) && errors<4) schedule()
                    else stop("توقف الصوت (رمز $error). تحقق من المحرك واللغة والإذن ثم أعد المحاولة.")
                }
                override fun onResults(results:Bundle?) {
                    if(token!=generation || !active || !foreground || suspended) return
                    busy=false; errors=0
                    val heard=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val normalized=CommandParser.normalize(heard)
                    if(normalized in listOf("وقف","توقف","stop")) { stop("توقف الاستماع.");return }
                    if(wake && !normalized.startsWith("يا مساعدي")) { schedule();return }
                    if(wake) {
                        wake=false
                        val tail=heard.replaceFirst(Regex("^يا\\s+مساعدي[،,:]?\\s*"),"").trim()
                        if(tail.isBlank()) { lastActivity=SystemClock.elapsedRealtime();say("شنو تبي نديرلك؟");return }
                        deliver(tail)
                    } else deliver(heard)
                }
                private fun deliver(text:String) {
                    lastActivity=SystemClock.elapsedRealtime()
                    if(!continuous) { active=false }
                    command(text)
                    // Command may speak or leave the activity; only restart if still idle.
                    schedule()
                }
            })
            listen()
        } catch(_:Exception) { stop("تعذر بدء الصوت؛ استخدم الكتابة.") }
    }
    private fun listen() {
        if(!active || !foreground || busy || tts?.isSpeaking==true) return
        if(SystemClock.elapsedRealtime()-lastActivity>=60_000) { stop("انتهت مهلة الجلسة.");return }
        suspended=false;busy=true
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,offline)
            })
            handler.removeCallbacks(idle);handler.postDelayed(idle,60_000)
        } catch(_:Exception) { busy=false;stop("فشل بدء الاستماع.") }
    }
    private fun schedule() { val token=generation;handler.postDelayed({if(token==generation)listen()},700) }
    fun say(text:String) {
        // Never allow recognizer to hear our synthesized confirmation as user input.
        suspended=true;recognizer?.cancel();busy=false
        if(ttsReady && foreground) {
            val localVoice=tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language==Locale.forLanguageTag(language).language }
            if(localVoice==null) { status(text+" (صوت محلي غير متاح)");schedule();return }
            tts?.voice=localVoice
            tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"voice-$generation")
        } else schedule()
    }
    fun stop(message:String?=null) {
        generation++;active=false;busy=false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel();recognizer?.destroy();recognizer=null
        tts?.stop()
        if(message!=null)status(message)
    }
    fun close() { stop();tts?.shutdown();tts=null }
}
