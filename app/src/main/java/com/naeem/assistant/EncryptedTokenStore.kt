package com.naeem.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedTokenStore(context:Context,private val keyProvider:(()->SecretKey)?=null) {
    private val prefs=context.getSharedPreferences("cloud-secret",Context.MODE_PRIVATE)
    private fun key():SecretKey {
        keyProvider?.let{return it()}
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("assistant-cloud-key",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder("assistant-cloud-key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun hasKey()=prefs.contains("ciphertext")
    fun save(token:String){
        require(token.length in 16..512 && token.none{it.isWhitespace()})
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
    }
    fun read():String {
        val encrypted=prefs.getString("ciphertext",null)?:return ""
        val iv=prefs.getString("iv",null)?:return ""
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))}
        return cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun clear(){prefs.edit().clear().apply()}
}
