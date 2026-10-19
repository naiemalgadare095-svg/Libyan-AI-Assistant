package com.naeem.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var speaker: TextToSpeech? = null
    private var voiceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = speaker ?: return@TextToSpeech
                val ly = Locale("ar", "LY")
                val availability = engine.isLanguageAvailable(ly)
                engine.language = if (availability >= TextToSpeech.LANG_AVAILABLE) ly else Locale("ar")
                voiceReady = true
            }
        }
        setContent { AssistantScreen { message ->
            if (voiceReady) speaker?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
        } }
    }

    override fun onDestroy() {
        speaker?.stop()
        speaker?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AssistantScreen(say: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("assistant_settings", Activity.MODE_PRIVATE) }
    var wakeWord by remember { mutableStateOf(prefs.getString("wake_word", "يا مساعدي") ?: "يا مساعدي") }
    var wakeInput by remember { mutableStateOf(wakeWord) }
    var command by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("جاهز — اكتب أمرك أو اضغط الميكروفون") }
    var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak_replies", true)) }
    var pending by remember { mutableStateOf<ParsedCommand?>(null) }

    fun reply(message: String) {
        status = message
        if (speakReplies) say(message)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            command = recognized
            reply(if (recognized.isBlank()) "ما سمعتش أمراً واضحاً." else "سمعتك. راجع الكلام واضغط تنفيذ الأمر.")
        } else reply("لم يتم التعرف على الكلام أو تم إلغاء الاستماع.")
    }

    fun listen() {
        try {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-LY")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-LY")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "شن تبي نديرلك؟")
            })
        } catch (_: Exception) { reply("خدمة التعرف على الكلام مش متوفرة على الجهاز.") }
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else reply("إذن الميكروفون مرفوض. تقدر تستخدم الكتابة.")
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مساعدي AI — v0.2", style = MaterialTheme.typography.headlineMedium)
            Text("تجربة صوتية محلية. التعرف على الكلام قد يستخدم خدمة إنترنت حسب إعدادات هاتفك.")
            OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("قول أو اكتب: يا مساعدي ديرلي منبه الساعة 6:30") }, minLines = 2)
            Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.fillMaxWidth()) { Text("🎙️ تحدث") }
            Button(onClick = {
                val normalized = command.trim().removePrefix(wakeWord).trim().removePrefix("،").trim()
                val parsed = CommandParser.parse(normalized)
                when (parsed.action) {
                    Action.UNKNOWN -> reply("الأمر مش واضح. جرّب منبه بوقت محدد، افتح يوتيوب أو واتساب.")
                    Action.ALARM -> try {
                        context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, parsed.hour!!)
                            putExtra(AlarmClock.EXTRA_MINUTES, parsed.minute!!)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "منبه مساعدي AI")
                        })
                        reply("أرسلت طلب المنبه لتطبيق الساعة. تأكد إنه انحفظ.")
                    } catch (_: Exception) { reply("ما قدرتش نفتح تطبيق الساعة.") }
                    Action.YOUTUBE, Action.WHATSAPP -> {
                        val pkg = if (parsed.action == Action.YOUTUBE) "com.google.android.youtube" else "com.whatsapp"
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent == null) reply("التطبيق مش مثبت أو مش متاح للفتح.")
                        else try { context.startActivity(intent); reply("فتحت التطبيق.") }
                        catch (_: Exception) { reply("ما قدرتش نفتح التطبيق.") }
                    }
                    else -> { pending = parsed; reply("هذه العملية تحتاج موافقتك. مش حنبعت أو نتصل تلقائياً.") }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("تنفيذ الأمر") }
            Text(status, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("إعدادات الصوت", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = wakeInput, onValueChange = { wakeInput = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("كلمة الاستدعاء") }, singleLine = true)
            OutlinedButton(onClick = {
                val candidate = wakeInput.trim()
                if (candidate.length in 2..40) {
                    wakeWord = candidate
                    prefs.edit().putString("wake_word", candidate).apply()
                    reply("تم حفظ كلمة الاستدعاء: $candidate")
                } else reply("اختار كلمة من حرفين إلى 40 حرف.")
            }) { Text("حفظ كلمة الاستدعاء") }
            Text("الكلمة تُستخدم حالياً لفهم الأوامر المكتوبة أو المسجلة، ولا تشغّل استماعاً دائماً.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الرد الصوتي")
                Switch(checked = speakReplies, onCheckedChange = {
                    speakReplies = it
                    prefs.edit().putBoolean("speak_replies", it).apply()
                })
            }
            if (pending != null) {
                Text("إجراء تواصل: راجع الطلب قبل فتح التطبيق.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pending = null; reply("تم الإلغاء.") }) { Text("إلغاء") }
                    Button(onClick = {
                        val intent = if (pending?.action == Action.DIAL) Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                        else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                        try { context.startActivity(intent); reply("فتحت تطبيق التواصل. اختار المستلم وأكمل بنفسك.") }
                        catch (_: Exception) { reply("ما قدرتش نفتح تطبيق التواصل.") }
                        pending = null
                    }) { Text("فتح التطبيق") }
                }
            }
        }
    }
}
