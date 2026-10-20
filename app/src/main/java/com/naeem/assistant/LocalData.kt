package com.naeem.assistant

import org.json.JSONArray
import org.json.JSONObject

object LocalData {
    fun history(raw:String?,now:Long):JSONArray {
        val input=runCatching{JSONArray(raw?:"[]")}.getOrDefault(JSONArray());val result=JSONArray()
        for(i in 0 until minOf(input.length(),100)){
            val item=input.optJSONObject(i)?:continue
            val time=item.optLong("time",0);val summary=item.opt("summary") as? String?:continue
            if(time>0 && time<=now+60_000 && now-time<30L*24*60*60*1000 && summary.isNotBlank())
                result.put(JSONObject().put("time",time).put("summary",summary.take(300)))
        }
        return result
    }
    fun aliases(raw:String?):JSONObject {
        val input=runCatching{JSONObject(raw?:"{}")}.getOrDefault(JSONObject());val result=JSONObject()
        input.keys().asSequence().take(200).forEach{key->
            val item=input.optJSONObject(key)?:return@forEach
            val name=item.opt("name") as? String?:return@forEach
            val phone=CommandParser.phone(item.optString("phone"))?:return@forEach
            if(key.isNotBlank() && name.isNotBlank())result.put(key,JSONObject().put("name",name.take(100)).put("phone",phone))
        }
        return result
    }
}
