package org.darelihsan.parents

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Supabase session JSON (access + refresh token) of the parent encrypted with an
 * AES-256-GCM key that lives in the Android Keystore and never leaves it.
 * The password is never stored; only the session the site itself created.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)

    fun save(sessionJson: String): Boolean {
        if (sessionJson.length > MAX_LEN || !looksLikeSession(sessionJson)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = cipher.doFinal(sessionJson.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            prefs.edit().putString(PREF, blob).commit()
        } catch (e: Exception) { false }
    }

    fun load(): String? {
        val blob = prefs.getString(PREF, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            clear()   // key lost or data corrupted: force a clean sign-in instead of looping on errors
            null
        }
    }

    fun clear() { prefs.edit().remove(PREF).commit() }

    private fun looksLikeSession(s: String): Boolean = try {
        val o = JSONObject(s)
        o.optString("refresh_token").isNotEmpty() && o.optString("access_token").isNotEmpty()
    } catch (e: Exception) { false }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "de_parents_session_key"
        const val PREF = "session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val MAX_LEN = 16 * 1024
    }
}
