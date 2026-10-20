package com.naeem.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * Experimental wake-phrase recognition using Android SpeechRecognizer.
 * Not a dedicated always-on hotword engine: providers may time out or refuse
 * background recognition, especially with the screen off.
 */
class AssistantBackgroundService : Service() {
    companion object {
        const val ACTION_STOP = "com.naeem.assistant.STOP_BACKGROUND"
        private const val CHANNEL_ID = "assistant_background"
        private const val NOTIFICATION_ID = 42
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var failures = 0
    private var lastWakeAt = 0L
    private val restart = Runnable { beginListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "مساعدي AI في الخلفية", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun showStatus(message: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantBackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("مساعدي AI — تجريبي")
            .setContentText(message)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        showStatus("بدء الاستماع التجريبي لعبارة «يا مساعدي»")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("التعرف الصوتي غير متوفر؛ أوقف الخدمة من الإشعار")
            return START_NOT_STICKY
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) { checkWake(partialResults) }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        failures = 0
                        checkWake(results)
                        scheduleRestart(1000)
                    }
                    override fun onError(error: Int) {
                        failures++
                        if (failures >= 5) {
                            showStatus("توقف الاستماع بعد أخطاء متكررة ($error). أوقف الخدمة وأعد تشغيلها.")
                        } else scheduleRestart(2500L * failures)
                    }
                })
            }
            beginListening()
        } catch (_: Exception) {
            showStatus("تعذر بدء محرك التعرف. أوقف الخدمة من الإشعار.")
        }
        return START_NOT_STICKY
    }

    private fun checkWake(bundle: Bundle?) {
        val phrase = getSharedPreferences("assistant_settings", MODE_PRIVATE)
            .getString("wake_word", "يا مساعدي")?.trim().orEmpty()
        if (phrase.isBlank()) return
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val matched = texts.any { it.contains(phrase, ignoreCase = true) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (matched && now - lastWakeAt > 5000) {
            lastWakeAt = now
            showStatus("سمعت «$phrase»! افتح التطبيق لإعطاء الأمر.")
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || failures >= 5) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    private fun beginListening() {
        if (!running || failures >= 5) return
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-LY")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Exception) {
            failures++
            scheduleRestart(2500)
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
