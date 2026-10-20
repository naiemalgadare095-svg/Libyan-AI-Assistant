package com.naeem.assistant

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PersonalReviewTest {
    private fun step(action:String="SMS")=JSONObject().put("action",action).put("recipient",if(action=="SMS"||action=="DIAL")"Sara" else JSONObject.NULL)
        .put("message",if(action=="SMS")"hello" else JSONObject.NULL).put("hour",JSONObject.NULL).put("minute",JSONObject.NULL).put("query",JSONObject.NULL)
    private fun proposal(item:JSONObject=step())=JSONObject().put("steps",JSONArray().put(item)).put("clarification","")
    @Test fun acceptsKnownPlanButKeepsSensitiveFlag(){val p=DevicePlan.parse(proposal().toString());assertEquals("",p.error);assertTrue(p.steps.single().sensitive)}
    @Test fun rejectsUnknownAction(){assertTrue(DevicePlan.parse(proposal(step("SHELL")).toString()).steps.isEmpty())}
    @Test fun rejectsExtraInstructionFields(){assertTrue(DevicePlan.parse(proposal(step().put("url","https://example.com")).toString()).steps.isEmpty())}
    @Test fun rejectsHiddenPayloadOnNonCommunication(){assertTrue(DevicePlan.parse(proposal(step("CAMERA").put("message","send this")).toString()).steps.isEmpty())}
    @Test fun rejectsMissingRecipient(){assertTrue(DevicePlan.parse(proposal(step().put("recipient",JSONObject.NULL)).toString()).steps.isEmpty())}
    @Test fun rejectsMoreThanSixSteps(){val p=proposal().put("steps",JSONArray(List(7){step("HOME")}));assertTrue(DevicePlan.parse(p.toString()).steps.isEmpty())}
    @Test fun supportsClarificationWithoutExecution(){val p=DevicePlan.parse("""{"steps":[],"clarification":"لمن الرسالة؟"}""");assertTrue(p.steps.isEmpty());assertEquals("لمن الرسالة؟",p.error)}
    @Test fun rejectsMixedClarificationAndSteps(){assertTrue(DevicePlan.parse(proposal().put("clarification","not sure").toString()).steps.isEmpty())}
    @Test fun rejectsInvalidAlarmAndFractionalTime(){
        for(hour in listOf<Any>(25,6.5,"6")){val s=step("ALARM").put("hour",hour).put("minute",0);assertTrue(DevicePlan.parse(proposal(s).toString()).steps.isEmpty())}
    }
    @Test fun acceptsValidAlarm(){val s=step("ALARM").put("hour",6).put("minute",30);assertEquals(Action.ALARM,DevicePlan.parse(proposal(s).toString()).steps.single().action)}
    @Test fun rejectsArbitraryQueryForOtherActions(){assertTrue(DevicePlan.parse(proposal(step("HOME").put("query","secret")).toString()).steps.isEmpty())}
    @Test fun acceptsBoundedWebSearch(){assertEquals(Action.WEB_SEARCH,DevicePlan.parse(proposal(step("WEB_SEARCH").put("query","طقس طرابلس")).toString()).steps.single().action)}
    @Test fun cancelledRequestNeverInvokesTransport(){
        val request=CloudRequest();request.cancel();val client=CloudAssistant{_,_->fail("must not send");""}
        try{client.answer("fake-test","gpt-4.1-mini","hello",emptyList(),request=request);fail("must cancel")}catch(_:java.util.concurrent.CancellationException){}
    }
    @Test fun cancellationDropsResponseBeforeItBecomesUsable(){
        val request=CloudRequest();val client=CloudAssistant{_,_->request.cancel();"""{"status":"completed","output":[]}"""}
        try{client.answer("fake","gpt-4.1-mini","hello",emptyList(),request=request);fail("must cancel")}catch(_:java.util.concurrent.CancellationException){}
    }
    @Test fun planningUsesStrictSchemaWithoutChatContext(){
        var payload=JSONObject()
        val client=CloudAssistant{_,body->payload=JSONObject(body);"""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{}"}]}]}"""}
        client.answer("fake","gpt-4.1-mini","افتح الإشعارات",listOf("user" to "private past question"),planning=true)
        assertTrue(payload.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        assertEquals(1,payload.getJSONArray("input").length());assertFalse(payload.toString().contains("private past question"))
    }
    @Test fun recipientNameDoesNotChooseWrongMessageChannel(){assertEquals(Action.SMS,CommandParser.parse("send SMS to WhatsApp support \"hello\"").action)}
    @Test fun rejectsConflictingClockTimes(){assertEquals(Action.UNKNOWN,CommandParser.parse("منبه 6:30 او 7:30").action)}
    @Test fun supportsWakeWordWithPunctuationInTypedCommand(){assertEquals(Action.YOUTUBE,CommandParser.parse("يا مساعدي، افتح يوتيوب").action)}
    @Test fun parsesDeviceActionsInOrder(){assertEquals(listOf(Action.NOTIFICATIONS,Action.RECENTS,Action.SETTINGS),CommandParser.plan("افتح الإشعارات ثم التطبيقات الأخيرة ثم افتح الإعدادات").steps.map{it.action})}
    @Test fun damagedHistoryDoesNotCrash(){assertEquals(0,LocalData.history("not-json",1000).length());assertEquals(0,LocalData.history("[1,null,{}]",1000).length())}
    @Test fun historyKeepsValidRecentRecordsOnly(){val raw="""[{"time":950,"summary":"ok"},{"time":0,"summary":"bad"},{"time":950,"summary":4}]""";assertEquals(1,LocalData.history(raw,1000).length())}
    @Test fun damagedAliasesDoNotCrashOrSupplyInvalidNumbers(){assertEquals(0,LocalData.aliases("broken").length());assertEquals(0,LocalData.aliases("""{"x":{"name":"test","phone":"*123#"}}""").length())}
    @Test fun permissionsRemainExactlyTheSame(){
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes=doc.getElementsByTagName("uses-permission")
        val names=(0 until nodes.length).map{nodes.item(it).attributes.getNamedItem("android:name").nodeValue}.toSet()
        assertEquals(setOf("android.permission.INTERNET","android.permission.RECORD_AUDIO","android.permission.READ_CONTACTS","android.permission.CALL_PHONE","com.android.alarm.permission.SET_ALARM"),names)
        for(path in listOf("src/main/res/xml/accessibility_service.xml","src/main/res/xml-v31/accessibility_service.xml")){
            val config=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
            assertEquals("false",config.getAttribute("android:canRetrieveWindowContent"));assertEquals("true",config.getAttribute("android:canPerformGestures"))
        }
    }
}
