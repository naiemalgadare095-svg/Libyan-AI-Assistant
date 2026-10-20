package com.naeem.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CloudRequest {
    @Volatile private var cancelled=false
    private var connection:HttpsURLConnection?=null
    @Synchronized fun bind(value:HttpsURLConnection){checkActive();connection=value}
    @Synchronized fun cancel(){cancelled=true;connection?.disconnect();connection=null}
    fun checkActive(){if(cancelled || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()}
}

/** Text-only client. No screen, contacts, audio, tools or automatic device actions. */
class CloudAssistant(private val transport:((String,String)->String)?=null) {
    @Volatile private var connection:HttpsURLConnection?=null
    fun cancel(){connection?.disconnect();connection=null}
    fun answer(key:String,model:String,text:String,context:List<Pair<String,String>>,planning:Boolean=false,request:CloudRequest=CloudRequest()):String {
        require(text.isNotBlank() && text.length<=2000){"اكتب سؤالًا من 1 إلى 2000 حرف."}
        require(Regex("[A-Za-z0-9._-]{1,80}").matches(model)){"اسم النموذج غير صحيح."}
        request.checkActive()
        val data=payload(model,text,if(planning)emptyList() else context)
        if(planning){data.put("max_output_tokens",1200);data.put("instructions",DevicePlan.instructions);data.put("text",JSONObject().put("format",DevicePlan.format()))}
        val body=data.toString()
        val raw=transport?.invoke(key,body)?:send(key,body,request)
        request.checkActive()
        return responseText(raw)
    }
    private fun send(key:String,body:String,request:CloudRequest):String {
        request.checkActive()
        val conn=URL("https://api.openai.com/v1/responses").openConnection() as HttpsURLConnection
        connection=conn
        try {
            request.bind(conn)
            conn.requestMethod="POST";conn.instanceFollowRedirects=false
            conn.connectTimeout=15_000;conn.readTimeout=35_000;conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer $key")
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
            request.checkActive()
            conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
            val code=conn.responseCode
            if(code !in 200..299)throw IllegalStateException(when(code){
                401,403->"الخدمة رفضت الوصول. تحقق من المفتاح والصلاحيات وتوفر الخدمة في منطقتك."
                429->"تجاوزت الخدمة الحد أو الرصيد المتاح. تحقق من حساب API ثم حاول يدويًا."
                else->"لم تكتمل استجابة الخدمة (HTTP $code). لم تُنفذ أي مهمة على الجهاز."
            })
            return conn.inputStream.bufferedReader().use { reader ->
                val result=StringBuilder();val buffer=CharArray(4096)
                while(true){request.checkActive();val n=reader.read(buffer);if(n<0)break;result.append(buffer,0,n);if(result.length>262144)throw IllegalStateException("رد الخدمة أكبر من الحد المسموح.")}
                result.toString()
            }
        } finally {conn.disconnect();if(connection===conn)connection=null}
    }
    companion object {
        fun payload(model:String,text:String,context:List<Pair<String,String>>):JSONObject {
            val input=JSONArray()
            context.takeLast(8).forEach{(role,content)->
                require(role=="user"||role=="assistant")
                input.put(JSONObject().put("role",role).put("content",content.take(4000)))
            }
            input.put(JSONObject().put("role","user").put("content",text))
            return JSONObject().put("model",model).put("store",false).put("max_output_tokens",600)
                .put("instructions","أنت مساعد محادثة للمستخدم. تحدث بلهجة ليبية مفهومة أو بلغة المستخدم، واسمح بمزج الإنجليزية. لا تدع أنك فتحت تطبيقًا أو أرسلت رسالة أو بدأت اتصالًا. لا تملك أدوات تنفيذ. لا تطلب مفاتيح أو كلمات مرور. قدم إجابة واضحة ومختصرة، واسأل عند الغموض. تنفيذ الجهاز منفصل داخل واجهة الأوامر المحلية.")
                .put("input",input)
        }
        fun responseText(raw:String):String {
            val data=JSONObject(raw)
            if(data.optString("status")!="completed")throw IllegalStateException("لم يكتمل رد AI. أعد المحاولة يدويًا.")
            val texts=mutableListOf<String>();val output=data.optJSONArray("output")?:JSONArray()
            for(i in 0 until output.length()){
                val message=output.optJSONObject(i)?:continue
                if(message.optString("type")!="message" || message.optString("role")!="assistant")continue
                val content=message.optJSONArray("content")?:continue
                for(j in 0 until content.length()){
                    val part=content.optJSONObject(j)?:continue
                    if(part.optString("type")=="output_text")texts.add(part.optString("text"))
                    if(part.optString("type")=="refusal")texts.add(part.optString("refusal"))
                }
            }
            return texts.joinToString("\n").trim().take(8000).ifBlank{throw IllegalStateException("لم يرجع AI نصًا صالحًا. لم يُنفذ أي إجراء.")}
        }
    }
}
