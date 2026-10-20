package com.naeem.assistant

import java.text.Normalizer
import java.time.LocalDate

data class ParsedCommand(val action: Action, val hour: Int? = null, val minute: Int? = null, val message: String = "")
enum class Action { ALARM, YOUTUBE, WHATSAPP, DIAL, SMS, UNKNOWN }

object CommandParser {
    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
        .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
        .lowercase()

    fun parse(input: String): ParsedCommand {
        val s = normalize(input)
        if (listOf("منبه", "نبهني", "ديرلي منبه", "ضبط المنبه").any { it in s }) {
            val clock = Regex("(\\d{1,2})[:٫.](\\d{1,2})").find(s)
            val hourRaw = clock?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:الساعه|ساعة|الساعة)\\s*(\\d{1,2})").find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: listOf("ستة" to 6, "سته" to 6, "سبعة" to 7, "سبعه" to 7, "ثمانية" to 8, "تسعة" to 9, "خمسة" to 5).firstOrNull { it.first in s }?.second
            if (hourRaw == null || hourRaw !in 0..23) return ParsedCommand(Action.UNKNOWN)
            val minute = clock?.groupValues?.get(2)?.toIntOrNull()
                ?: if ("ونص" in s || "ونصف" in s) 30 else if ("وربع" in s) 15 else 0
            if (minute !in 0..59) return ParsedCommand(Action.UNKNOWN)
            var hour = hourRaw
            if (("المساء" in s || "بالليل" in s || "العشية" in s) && hour in 1..11) hour += 12
            if (("الصبح" in s || "صباح" in s) && hour == 12) hour = 0
            return ParsedCommand(Action.ALARM, hour, minute, input)
        }
        if (Regex("\\b(alarm|wake me|set an alarm|alarme|réveille|reveil)\\b").containsMatchIn(s)) {
            val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|h)?").find(s)
            if (match != null) {
                var hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val suffix = match.groupValues[3]
                if (suffix == "pm" && hour in 1..11) hour += 12
                if (suffix == "am" && hour == 12) hour = 0
                if (hour in 0..23 && minute in 0..59) return ParsedCommand(Action.ALARM, hour, minute, input)
            }
            return ParsedCommand(Action.UNKNOWN)
        }
        if (listOf("يوتيوب", "يوتوب", "youtube").any { it in s }) return ParsedCommand(Action.YOUTUBE)
        if (listOf("واتساب", "واتس اب", "الواتس", "الواتساب", "whatsapp", "whatsup").any { it in s }) return ParsedCommand(Action.WHATSAPP)
        if (listOf("اتصل", "رن على", "كلم", "call ").any { it in s }) return ParsedCommand(Action.DIAL)
        if ("رسالة" in s || "اس ام اس" in s || "sms" in s) return ParsedCommand(Action.SMS)
        return ParsedCommand(Action.UNKNOWN)
    }
}
