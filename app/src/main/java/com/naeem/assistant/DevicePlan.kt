package com.naeem.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Untrusted model output becomes a reviewable plan; this class never executes actions. */
object DevicePlan {
    private val allowed=Action.entries.filter{it!=Action.UNKNOWN}.map{it.name}
    val instructions="""حوّل طلب المستخدم إلى اقتراح خطة فقط، دون ادعاء التنفيذ. استخدم الأفعال المسموحة حرفيًا. لا تخمن اسم مستلم أو رقمًا أو نص رسالة أو وقتًا. إذا لم تتوفر التفاصيل أو كان الطلب غير مدعوم، أرجع steps فارغة وclarification بسؤال عربي محدد. بحد أقصى ست خطوات. hour وminute فقط للمنبه بنظام 24 ساعة. recipient وmessage للتواصل فقط. query للبحث WEB_SEARCH فقط. لا تنفيذ كود أو روابط أو نقر عشوائي، ولا تجاوز لقفل أو صلاحيات. يجب مراجعة الخطة محليًا ثم تأكيد التواصل."""
    fun format():JSONObject {
        val props=JSONObject().put("action",JSONObject().put("type","string").put("enum",JSONArray(allowed)))
        for(name in listOf("recipient","message","query"))props.put(name,JSONObject().put("type",JSONArray(listOf("string","null"))))
        for(name in listOf("hour","minute"))props.put(name,JSONObject().put("type",JSONArray(listOf("integer","null"))))
        val step=JSONObject().put("type","object").put("additionalProperties",false).put("properties",props)
            .put("required",JSONArray(listOf("action","recipient","message","query","hour","minute")))
        val schema=JSONObject().put("type","object").put("additionalProperties",false)
            .put("properties",JSONObject().put("steps",JSONObject().put("type","array").put("items",step))
                .put("clarification",JSONObject().put("type","string")))
            .put("required",JSONArray(listOf("steps","clarification")))
        return JSONObject().put("type","json_schema").put("name","personal_device_plan").put("strict",true).put("schema",schema)
    }
    fun parse(raw:String):CommandPlan = try {
        require(raw.length<=20000)
        val root=JSONObject(raw);require(root.keys().asSequence().toSet()==setOf("steps","clarification"))
        require(root.get("clarification") is String)
        val clarification=root.getString("clarification").trim().take(300)
        val steps=root.getJSONArray("steps");require(steps.length()<=6)
        if(steps.length()==0)CommandPlan(emptyList(),clarification.ifBlank{"الطلب يحتاج تفاصيل إضافية."})
        else {
            require(clarification.isBlank())
            val result=(0 until steps.length()).map { i ->
                val obj=steps.getJSONObject(i)
                require(obj.keys().asSequence().toSet()==setOf("action","recipient","message","query","hour","minute"))
                val action=Action.valueOf(obj.getString("action"));require(action.name in allowed)
                fun string(name:String):String {if(obj.isNull(name))return "";require(obj.get(name) is String);return obj.getString(name).trim()}
                fun number(name:String):Int? {if(obj.isNull(name))return null;val value=obj.get(name);require(value is Number && value.toDouble()==value.toInt().toDouble());return value.toInt()}
                val recipient=string("recipient");val message=string("message");val query=string("query")
                val hour=number("hour");val minute=number("minute")
                require(recipient.length<=120 && message.length<=2000 && query.length<=500)
                if(action==Action.ALARM)require(hour!=null && hour in 0..23 && minute!=null && minute in 0..59) else require(hour==null && minute==null)
                val communication=action in listOf(Action.DIAL,Action.SMS,Action.WHATSAPP)
                if(!communication)require(recipient.isEmpty() && message.isEmpty())
                if(action==Action.DIAL)require(recipient.isNotBlank() && message.isEmpty())
                if(action==Action.SMS || (action==Action.WHATSAPP && (recipient.isNotEmpty() || message.isNotEmpty())))require(recipient.isNotBlank() && message.isNotBlank())
                if(action==Action.WEB_SEARCH)require(query.isNotBlank()) else require(query.isEmpty())
                ParsedCommand(action,hour,minute,recipient,message,query=query)
            }
            CommandPlan(result)
        }
    }catch(_:Exception){CommandPlan(emptyList(),"رفضت الخطة المقترحة لعدم مطابقتها للإجراءات أو التفاصيل المسموحة. عدّل طلبك؛ لم يُنفذ شيء.")}
}
