package com.naeem.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssistantScreen() }
    }
}

@Composable
private fun AssistantScreen() {
    val context = LocalContext.current
    var command by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("جاهز — اكتب أمرك أو اضغط الميكروفون") }
    var pending by remember { mutableStateOf<ParsedCommand?>(null) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            command = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            status = "تم التعرف على الكلام. راجع النص ثم اضغط تنفيذ."
        } else status = "لم يتم التعرف على الكلام أو تم إلغاء الاستماع."
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-LY")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "شنو تبي نديرلك؟")
                })
            } catch (_: Exception) { status = "خدمة التعرف على الكلام غير متوفرة." }
        } else status = "إذن الميكروفون مرفوض. تقدر تستخدم الكتابة."
    }
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("مساعدي AI", style = MaterialTheme.typography.headlineMedium)
            Text("نسخة تجريبية محلية — لا تتصل بخدمة ذكاء اصطناعي بعد")
            OutlinedTextField(value = command, onValueChange = { command = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("قول أو اكتب: ديرلي منبه الساعة 6:30") }, minLines = 2)
            Button(onClick = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-LY")
                        })
                    } catch (_: Exception) { status = "خدمة التعرف على الكلام غير متوفرة." }
                } else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.fillMaxWidth()) { Text("🎙️ تحدث") }
            Button(onClick = {
                val parsed = CommandParser.parse(command)
                if (parsed.action == Action.UNKNOWN) {
                    status = "الأمر غير واضح. جرّب منبه بوقت محدد، افتح يوتيوب، أو افتح واتساب."
                } else if (parsed.action == Action.ALARM) {
                    // AlarmClock may open its own UI; do not claim confirmed creation.
                    try {
                        context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, parsed.hour!!)
                            putExtra(AlarmClock.EXTRA_MINUTES, parsed.minute!!)
                            putExtra(AlarmClock.EXTRA_MESSAGE, "منبه مساعدي AI")
                        })
                        status = "تم إرسال طلب ضبط المنبه لتطبيق الساعة؛ تأكد من ظهوره هناك."
                    } catch (_: Exception) { status = "تعذر فتح تطبيق الساعة." }
                } else if (parsed.action == Action.YOUTUBE || parsed.action == Action.WHATSAPP) {
                    val pkg = if (parsed.action == Action.YOUTUBE) "com.google.android.youtube" else "com.whatsapp"
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) try { context.startActivity(intent); status = "تم فتح التطبيق." }
                    catch (_: Exception) { status = "تعذر فتح التطبيق." }
                    else status = "التطبيق غير مثبت أو غير متاح للفتح."
                } else {
                    pending = parsed
                    status = "هذه العملية تتطلب مراجعة منك. لن نرسل أو نتصل تلقائياً."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("تنفيذ الأمر") }
            Text(status, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
            if (pending != null) {
                Text("التواصل: لا يوجد مستلم محدد بعد. نفتح التطبيق المناسب فقط.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pending = null; status = "تم الإلغاء." }) { Text("إلغاء") }
                    Button(onClick = {
                        val action = pending!!.action
                        val intent = if (action == Action.DIAL) Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                            else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                        try { context.startActivity(intent); status = "تم فتح تطبيق التواصل؛ حدد المستلم وأكمل بنفسك." }
                        catch (_: Exception) { status = "تعذر فتح التطبيق المطلوب." }
                        pending = null
                    }) { Text("فتح التطبيق") }
                }
            }
        }
    }
}
