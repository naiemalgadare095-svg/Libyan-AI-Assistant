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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import android.os.SystemClock

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
    var recognitionLanguage by remember { mutableStateOf(prefs.getString("recognition_language", "auto") ?: "auto") }
    var responseLanguage by remember { mutableStateOf("ar") }
    var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak_replies", true)) }
    var pending by remember { mutableStateOf<ParsedCommand?>(null) }
    var listening by remember { mutableStateOf(false) }
    var lastCommand by remember { mutableStateOf("") }
    var lastCommandAt by remember { mutableLongStateOf(0L) }

    fun reply(message: String) {
        status = message
        if (speakReplies) say(message)
    }

    fun executeCommand(raw: String) {
                val normalized = raw.trim().let { text ->
                    if (text.startsWith(wakeWord, ignoreCase = true)) text.drop(wakeWord.length).trim() else text
                }.trimStart('،', ',', ' ')
                if (normalized.isBlank()) { reply("قوللي شن تبي نديرلك."); return }
                val now = SystemClock.elapsedRealtime()
                if (normalized.equals(lastCommand, ignoreCase = true) && now - lastCommandAt < 1500L) {
                    reply("الأمر تكرر بسرعة؛ استنى لحظة وعاود جرّب.")
                    return
                }
                lastCommand = normalized
                lastCommandAt = now
                val parsed = CommandParser.parse(normalized)
                when (parsed.action) {
                    Action.UNKNOWN -> reply(when (responseLanguage) {
                        "en" -> "I couldn't understand. Try setting an alarm or opening YouTube or WhatsApp."
                        "fr" -> "Je n'ai pas compris. Essayez une alarme ou ouvrez YouTube ou WhatsApp."
                        else -> "الأمر مش واضح. جرّب منبه بوقت محدد، افتح يوتيوب أو واتساب."
                    })
                    Action.ALARM -> try {
                        context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, parsed.hour!!)
                            putExtra(AlarmClock.EXTRA_MINUTES, parsed.minute!!)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "منبه مساعدي AI")
                        })
                        reply(when (responseLanguage) {
                            "en" -> "Alarm request sent to Clock. Please confirm it was saved."
                            "fr" -> "Demande d'alarme envoyée. Vérifiez qu'elle est enregistrée."
                            else -> "أرسلت طلب المنبه لتطبيق الساعة. تأكد إنه انحفظ."
                        })
                    } catch (_: Exception) { reply("ما قدرتش نفتح تطبيق الساعة.") }
                    Action.YOUTUBE, Action.WHATSAPP -> {
                        val pkg = if (parsed.action == Action.YOUTUBE) "com.google.android.youtube" else "com.whatsapp"
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent == null) reply("التطبيق مش مثبت أو مش متاح للفتح.")
                        else try { context.startActivity(intent); reply(when (responseLanguage) {
                            "en" -> "Opened the app."
                            "fr" -> "Application ouverte."
                            else -> "فتحت التطبيق."
                        }) }
                        catch (_: Exception) { reply("ما قدرتش نفتح التطبيق.") }
                    }
                    else -> { pending = parsed; reply("هذه العملية تحتاج موافقتك. مش حنبعت أو نتصل تلقائياً.") }
                }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            command = recognized
            responseLanguage = detectLanguage(recognized, recognitionLanguage)
            if (recognized.isBlank()) reply("ما سمعتش أمراً واضحاً.")
            else executeCommand(recognized)
        } else reply("لم يتم التعرف على الكلام أو تم إلغاء الاستماع.")
    }

    fun listen() {
        if (listening) { reply("الاستماع شغال بالفعل."); return }
        try {
            listening = true
            status = "نسمع فيك..."
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (recognitionLanguage != "auto") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguage)
                } else {
                    // Arabic fallback: providers may ignore language detection.
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-LY")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-LY")
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                        putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", arrayListOf("ar-LY", "ar", "en-US", "fr-FR"))
                    }
                }
                putExtra(RecognizerIntent.EXTRA_PROMPT, "شن تبي نديرلك؟")
            })
        } catch (_: Exception) {
            listening = false
            reply("خدمة التعرف على الكلام مش متوفرة. جرّب الكتابة أو راجع خدمة Google Speech.")
        }
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else reply("إذن الميكروفون مرفوض. تقدر تستخدم الكتابة.")
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مساعدي AI — v0.3.2", style = MaterialTheme.typography.headlineMedium)
            Text("تجربة صوتية محلية. التعرف على الكلام قد يستخدم خدمة إنترنت حسب إعدادات هاتفك.")
            OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("قول أو اكتب: يا مساعدي ديرلي منبه الساعة 6:30") }, minLines = 2)
            Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !listening, modifier = Modifier.fillMaxWidth()) { Text(if (listening) "🎙️ جاري الاستماع" else "🎙️ تحدث") }
            Button(onClick = { executeCommand(command) }, modifier = Modifier.fillMaxWidth()) { Text("تنفيذ الأمر") }
            Text(status, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("إعدادات الصوت", style = MaterialTheme.typography.titleMedium)
            Text("لغة التعرف على الكلام")
            val languages = listOf("auto" to "تلقائي (العربية افتراضياً)", "ar-LY" to "العربية الليبية", "en-US" to "English", "fr-FR" to "Français")
            languages.forEach { (code, label) ->
                Row {
                    RadioButton(selected = recognitionLanguage == code, onClick = {
                        recognitionLanguage = code
                        prefs.edit().putString("recognition_language", code).apply()
                    })
                    Text(label)
                }
            }
            Text("في الوضع التلقائي تُفضّل العربية الليبية. دعم اكتشاف اللغة يعتمد على محرك الهاتف.")
            OutlinedButton(onClick = {
                val available = android.speech.SpeechRecognizer.isRecognitionAvailable(context)
                val apiReady = android.os.Build.VERSION.SDK_INT >= 34
                status = "خدمة التعرف: " + (if (available) "متوفرة. " else "غير متوفرة. ") +
                    (if (apiReady) "واجهة اكتشاف اللغة موجودة. " else "واجهة اكتشاف اللغة تحتاج Android 14+. ") +
                    "هذا لا يؤكد دعم المحرك للاكتشاف التلقائي؛ اختبر اللغتين صوتياً."
            }) { Text("فحص محرك التعرف الصوتي") }
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
                Text("إجراء تواصل: راجع الطلب قبل فتح التطبيق. لن يتم إجراء اتصال أو إرسال رسالة تلقائياً.")
                Text("الطلب: ${pending?.message.orEmpty()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pending = null; reply("تم الإلغاء.") }) { Text("إلغاء") }
                    Button(onClick = {
                        val number = Regex("(?:\\+|00)?[0-9][0-9\\s-]{5,18}[0-9]")
                            .find(pending?.message.orEmpty())?.value
                            ?.replace(Regex("[\\s-]"), "")?.let { if (it.startsWith("00")) "+" + it.drop(2) else it }
                            .orEmpty()
                        val intent = if (pending?.action == Action.DIAL)
                            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                        else Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        try { context.startActivity(intent); reply("فتحت تطبيق التواصل. راجع المستلم وأكمل بنفسك.") }
                        catch (_: Exception) { reply("ما قدرتش نفتح تطبيق التواصل.") }
                        pending = null
                    }) { Text("فتح التطبيق") }
                }
            }
        }
    }
}

private fun detectLanguage(text: String, selected: String): String {
    if (selected.startsWith("en")) return "en"
    if (selected.startsWith("fr")) return "fr"
    if (selected.startsWith("ar")) return "ar"
    if (text.any { it in '\u0600'..'\u06FF' }) return "ar"
    val lower = text.lowercase(Locale.ROOT)
    if (Regex("\\b(ouvre|ouvrir|mets|réveille|alarme|une|heure|demain|appelle)\\b").containsMatchIn(lower)) return "fr"
    return if (text.isNotBlank()) "en" else "ar"
}
