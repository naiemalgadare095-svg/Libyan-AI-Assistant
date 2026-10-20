package com.naeem.assistant

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CloudAssistantTest {
    @Test fun payloadIsStatelessAndDoesNotExposeKeyOrDeviceData(){
        val data=CloudAssistant.payload("gpt-4.1-mini","شنو نراجع اليوم؟",emptyList())
        assertFalse(data.getBoolean("store"));assertFalse(data.has("tools"));assertFalse(data.has("previous_response_id"))
        assertEquals(1,data.getJSONArray("input").length());assertEquals(600,data.getInt("max_output_tokens"))
    }
    @Test fun contextIsBounded(){
        val data=CloudAssistant.payload("gpt-4.1-mini","question",List(20){"user" to "old"})
        assertEquals(9,data.getJSONArray("input").length())
    }
    @Test fun textResponseIsNotExecuted(){
        val client=CloudAssistant{_,_->"""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"افتح واتساب"}]}]}"""}
        assertEquals("افتح واتساب",client.answer("fake-test-only","gpt-4.1-mini","hello",emptyList()))
    }
    @Test fun toolOutputCannotBecomeAReply(){
        try{CloudAssistant.responseText("""{"status":"completed","output":[{"type":"function_call","name":"call"}]}""");fail("must reject") }catch(_:IllegalStateException){}
    }
    @Test fun incompleteResponsesAreRejected(){
        try{CloudAssistant.responseText("""{"status":"incomplete","output":[]}""");fail("must reject") }catch(_:IllegalStateException){}
    }
    @Test fun emptyOrOversizedInputNeverReachesTransport(){
        val client=CloudAssistant{_,_->fail("No network expected");""}
        listOf("", "a".repeat(2001)).forEach{try{client.answer("fake","gpt-4.1-mini",it,emptyList());fail("must reject")}catch(_:IllegalArgumentException){}}
    }
    @Test fun encryptedTokenRoundTripAndDeletion(){
        val context=RuntimeEnvironment.getApplication()
        val store=EncryptedTokenStore(context){SecretKeySpec(ByteArray(16){(it+1).toByte()},"AES")}
        val token="only-a-fake-unit-test-token"
        store.save(token);assertEquals(token,store.read())
        assertFalse(context.getSharedPreferences("cloud-secret",0).all.toString().contains(token))
        store.clear();assertFalse(store.hasKey());assertEquals("",store.read())
    }
    @Test fun invalidSecretIsNotStored(){
        val context=RuntimeEnvironment.getApplication();val store=EncryptedTokenStore(context){SecretKeySpec(ByteArray(16),"AES")}
        try{store.save("short");fail("must reject")}catch(_:IllegalArgumentException){}
        assertFalse(store.hasKey())
    }
    @Test fun wakeWordKeepsOriginalPayload(){
        assertEquals("send SMS to Sara \"Hello\"",WakePhrase.strip("يَا مُسَاعِدِي، send SMS to Sara \"Hello\""))
        assertEquals("",WakePhrase.strip("يا مساعدي!"))
        assertNull(WakePhrase.strip("يا مساعدينا افتح واتساب"))
        assertNull(WakePhrase.strip("افتح واتساب"))
    }
}
