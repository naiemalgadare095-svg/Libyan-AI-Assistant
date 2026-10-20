package com.naeem.assistant

import java.text.Normalizer

enum class Action { ALARM, YOUTUBE, WHATSAPP, DIAL, SMS, BACK, HOME, SCROLL_UP, SCROLL_DOWN, UNKNOWN }
data class ParsedCommand(
    val action: Action, val hour: Int? = null, val minute: Int? = null,
    val recipient: String = "", val message: String = "", val problem: String = ""
) {
    val sensitive get() = action == Action.DIAL || action == Action.SMS || (action == Action.WHATSAPP && recipient.isNotBlank())
}
data class CommandPlan(val steps: List<ParsedCommand>, val error: String = "")

object CommandParser {
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else if(c in '۰'..'۹') '0' + (c - '۰') else c }.joinToString("")
        .lowercase().trim().replace(Regex("\\s+"), " ")

    // Quoted message text is opaque: conjunctions inside it never become actions.
    fun plan(input: String): CommandPlan {
        val parts = mutableListOf<String>(); val segment = StringBuilder(); var quote: Char? = null
        var i = 0
        val divider = Regex("^(?:\\s*[؛;]\\s*|\\s+(?:ثم|وبعدين|بعدين|بعدها|و بعدها|and then|then)\\s+|\\s+و(?=(?:افتح|اتصل|عيط|دير|اضبط|ارسل|ابعت|ارجع)))", RegexOption.IGNORE_CASE)
        while (i < input.length) {
            val c = input[i]
            if (quote != null) {
                segment.append(c)
                if (c == quote || (quote == '«' && c == '»') || (quote == '“' && c == '”')) quote = null
                i++; continue
            }
            if (c == '"' || c == '«' || c == '“') { quote = c; segment.append(c); i++; continue }
            val match = divider.find(input.substring(i))
            if (match != null && segment.isNotBlank()) {
                parts.add(segment.toString().trim()); segment.clear(); i += match.value.length
            } else { segment.append(c); i++ }
        }
        if (quote != null) return CommandPlan(emptyList(), "أغلق علامات التنصيص حول الرسالة.")
        if (segment.isNotBlank()) parts.add(segment.toString().trim())
        if (parts.isEmpty() || parts.size > 6) return CommandPlan(emptyList(), "اكتب من أمر واحد إلى ستة أوامر.")
        val steps = parts.map(::parse)
        val invalid = steps.firstOrNull { it.action == Action.UNKNOWN }
        return if (invalid != null) CommandPlan(emptyList(), invalid.problem.ifBlank { "لم أفهم إحدى الخطوات؛ لم يتم تنفيذ أي خطوة." }) else CommandPlan(steps)
    }

    fun parse(input: String): ParsedCommand {
        val s = normalize(input.takeWhile { it != '"' && it != '«' && it != '“' }).removePrefix("يا مساعدي ").trim()
        fun bad(text: String) = ParsedCommand(Action.UNKNOWN, problem = text)
        if (s.matches(Regex("(?:ارجع|رجوع|back|go back)"))) return ParsedCommand(Action.BACK)
        if (s.matches(Regex("(?:الرئيسية|الشاشه الرئيسية|الشاشة الرئيسية|home|go home)"))) return ParsedCommand(Action.HOME)
        if (s.matches(Regex("(?:انزل|مرر لاسفل|scroll down)"))) return ParsedCommand(Action.SCROLL_DOWN)
        if (s.matches(Regex("(?:اطلع|مرر لاعلي|scroll up)"))) return ParsedCommand(Action.SCROLL_UP)
        if (Regex("^(?:(?:ديرلي|دير|اضبط|ضبط|set)\\s+)?(?:منبه|المنبه|نبهني|alarm)(?:\\s|$)").containsMatchIn(s)) {
            val clock = Regex("(?<!\\d)(\\d{1,2})[:٫.](\\d{1,2})(?!\\d)").find(s)
            val raw = clock?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:الساعه|الساعة|ساعة|at)\\s*(\\d{1,2})(?!\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: mapOf("واحدة" to 1,"اثنين" to 2,"ثلاثة" to 3,"اربعة" to 4,"خمسة" to 5,"ستة" to 6,"سته" to 6,"سبعة" to 7,"سبعه" to 7,"ثمانية" to 8,"تسعة" to 9,"عشرة" to 10).entries.firstOrNull { Regex("(?:^|\\s)${it.key}(?:\\s|$)").containsMatchIn(s) }?.value
                ?: return bad("حدد وقت المنبه، مثل: ديرلي منبه الساعة 6:30 الصبح.")
            val minute = clock?.groupValues?.get(2)?.toIntOrNull() ?: if ("ونص" in s || "ونصف" in s) 30 else if ("وربع" in s) 15 else 0
            if (raw !in 0..23 || minute !in 0..59) return bad("وقت المنبه غير صحيح.")
            val pm = Regex("المساء|بالليل|العشية|\\bpm\\b").containsMatchIn(s)
            val am = Regex("الصبح|صباح|\\bam\\b").containsMatchIn(s)
            if ((pm && am) || ((pm || am) && raw !in 1..12)) return bad("حدد وقتًا واحدًا دون تعارض بين الصباح والمساء.")
            val hour = if(pm && raw < 12) raw+12 else if(am && raw == 12) 0 else raw
            return ParsedCommand(Action.ALARM,hour,minute)
        }
        val call = Regex("^(?:اتصل|عيط|رن|call|dial)(?:\\s+(?:علي|على|ب|to))?\\s+(.+)$").find(s)
        if (call != null) return ParsedCommand(Action.DIAL, recipient=call.groupValues[1].trim())
        val wa = "واتساب" in s || "واتس اب" in s || "whatsapp" in s
        val messaging = Regex("^(?:ارسل|ابعت|ابعث|نبعت|نبي نبعت|send|جهز)").containsMatchIn(s)
        if (messaging) {
            val rawQuote = Regex("[\"«“](.*)[\"»”]\\s*$").find(input)
                ?: return bad("ضع نص الرسالة بين علامتي تنصيص، مثل: ابعت SMS إلى أحمد \"بنتأخر\".")
            val prefix = normalize(input.substring(0,rawQuote.range.first)).removePrefix("يا مساعدي ")
            val channel = if(wa) Action.WHATSAPP else if(Regex("sms|اس ام اس|رسالة نصية").containsMatchIn(prefix)) Action.SMS else return bad("حدد قناة الرسالة: SMS أو واتساب.")
            val to = Regex("(?:الي|الى|لـ?|to)\\s+(.+)$").find(prefix)?.groupValues?.get(1)?.trim()
                ?: return bad("حدد المستلم بوضوح: إلى أحمد أو to Sara.")
            if(rawQuote.groupValues[1].isBlank()) return bad("الرسالة فارغة.")
            return ParsedCommand(channel, recipient=to, message=rawQuote.groupValues[1])
        }
        if (Regex("^(?:افتح|شغل|open|launch)\\s+(?:تطبيق\\s+)?(?:يوتيوب|youtube)$").matches(s)) return ParsedCommand(Action.YOUTUBE)
        if (Regex("^(?:افتح|شغل|open|launch)\\s+(?:تطبيق\\s+)?(?:واتساب|واتس اب|whatsapp)$").matches(s)) return ParsedCommand(Action.WHATSAPP)
        return bad("جرّب: افتح يوتيوب، اتصل على أحمد، أو ابعت SMS إلى أحمد \"بنتأخر\".")
    }
    fun phone(input: String): String? {
        val s = normalize(input).replace(Regex("[()\\s-]"), "")
        return s.takeIf { Regex("\\+?[0-9]{5,15}").matches(it) }
    }
}

// A confirmation belongs to one immutable action, one foreground session, and one deadline.
class ConfirmationGate {
    private var ticket: String? = null
    private var deadline = 0L
    fun arm(id: String, now: Long) { ticket=id; deadline=now+30_000 }
    fun invalidate() { ticket=null; deadline=0 }
    fun accept(id: String, now: Long, unlocked: Boolean, foreground: Boolean): Boolean {
        val ok = id == ticket && now < deadline && unlocked && foreground
        invalidate()
        return ok
    }
}
